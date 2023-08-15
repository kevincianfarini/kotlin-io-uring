package com.kevincianfarini.iouring

import com.kevincianfarini.iouring.internal.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import liburing.*
import kotlin.coroutines.resume

public class KernelURing(
    queueDepth: QueueDepth,
    ringFlags: UInt,
    parentScope: CoroutineScope,
    private val heap: NativeFreeablePlacement = nativeHeap,
) : URing {

    private val submissionEvents = Channel<Unit>(capacity = Channel.RENDEZVOUS)
    private val submissionQueueEvents = Channel<Unit>(capacity = queueDepth.depth.toInt()).apply {
        repeat(queueDepth.depth.toInt()) {
            trySend(Unit).getOrElse { t ->
                throw IllegalStateException("Failed to initialize submission queue.", t)
            }
        }
    }
    private val ring: io_uring = heap.alloc()
    private val job: Job = Job(parent = parentScope.coroutineContext[Job]).apply {
        invokeOnCompletion {
            io_uring_queue_exit(ring.ptr)
            heap.free(ring.ptr)
        }
    }
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val pollingStarted = MutableStateFlow(false)

    init {
        io_uring_queue_init(queueDepth.depth, ring.ptr, ringFlags)
        scope.launch { poll() }
    }

    override suspend fun noOp() {
        ensureActive()
        val sqe = getSubmissionQueueEntryAndMaybeSubmit()
        val intResult = suspendCancellableCoroutine<Int> { cont ->
            val ref = StableRef.create(cont)
            io_uring_prep_nop(sqe)
            io_uring_sqe_set_data(sqe, ref.asCPointer())
            cont.registerIOUringCancellation(ring, ref)
        }
        checkIntResult(intResult)
    }

    override suspend fun open(
        filePath: String,
        directoryFileDescriptor: Int,
        flags: Int,
        mode: Int,
        resolve: Int
    ): Int = memScoped {
        ensureActive()
        val sqe = getSubmissionQueueEntryAndMaybeSubmit()
        val result = suspendCancellableCoroutine<Int> { cont ->
            val pathPointer = filePath.utf8.getPointer(this)
            val how = alloc<open_how> {
                this.flags = flags.convert()
                this.mode = mode.convert()
                this.resolve = resolve.convert()
            }
            val ref = StableRef.create(cont)
            io_uring_prep_openat2(
                sqe = sqe,
                dfd = directoryFileDescriptor,
                path = pathPointer,
                how = how.ptr,
            )
            io_uring_sqe_set_data(sqe, ref.asCPointer())
            cont.registerIOUringCancellation(ring, ref)
        }

        return checkIntResult(result)
    }

    override suspend fun close(fileDescriptor: Int) {
        ensureActive()
        val sqe = getSubmissionQueueEntryAndMaybeSubmit()
        val result = suspendCancellableCoroutine<Int> { cont ->
            val ref = StableRef.create(cont)
            io_uring_prep_close(sqe = sqe, fd = fileDescriptor)
            io_uring_sqe_set_data(sqe, ref.asCPointer())
            cont.registerIOUringCancellation(ring, ref)
        }
        checkIntResult(result)
    }

    override suspend fun fileStatus(
        filePath: String,
        request: FileStatusRequest,
        directoryFileDescriptor: Int
    ): FileStatusResult = memScoped {
        ensureActive()
        val sqe = getSubmissionQueueEntryAndMaybeSubmit()
        val pathPointer = filePath.utf8.getPointer(this)
        val statxbuf = alloc<statx>()
        val result = suspendCancellableCoroutine<Int> { cont ->
            io_uring_prep_statx(
                sqe = sqe,
                dfd = directoryFileDescriptor,
                path = pathPointer,
                flags = 0,
                mask = request.bitMask,
                statxbuf = statxbuf.ptr,
            )
            val ref = StableRef.create(cont)
            io_uring_sqe_set_data(sqe, ref.asCPointer())
            cont.registerIOUringCancellation(ring, ref)
        }
        checkIntResult(result)
        statxbuf.toFileStatusResult()
    }

    override suspend fun vectorRead(
        fileDescriptor: Int,
        vararg buffers: ByteArray,
        offset: ULong,
        flags: Int
    ): Int = memScoped {
        ensureActive()
        val sqe = getSubmissionQueueEntryAndMaybeSubmit()
        val pinnedBuffers = buffers.map { it.pin() }
        val iovecs = allocArray<iovec>(buffers.size) { index ->
            iov_len = pinnedBuffers[index].get().size.convert()
            iov_base = pinnedBuffers[index].addressOf(0)
        }
        val result = suspendCancellableCoroutine<Int> { cont ->
            io_uring_prep_readv2(
                sqe = sqe,
                fd = fileDescriptor,
                iovecs = iovecs,
                nr_vecs = buffers.size.convert(),
                offset = offset,
                flags = flags,
            )
            val ref = StableRef.create(cont)
            io_uring_sqe_set_data(sqe, ref.asCPointer())
            cont.registerIOUringCancellation(ring, ref)
        }
        pinnedBuffers.forEach { it.unpin() }
        checkIntResult(result)
    }

    override suspend fun vectorWrite(
        fileDescriptor: Int,
        vararg buffers: ByteArray,
        offset: ULong,
        flags: Int
    ): Int = memScoped {
        ensureActive()
        val sqe = getSubmissionQueueEntryAndMaybeSubmit()
        val pinnedBuffers = buffers.map { it.pin() }
        val iovecs = allocArray<iovec>(buffers.size) { index ->
            iov_len = pinnedBuffers[index].get().size.convert()
            iov_base = pinnedBuffers[index].addressOf(0)
        }
        val result = suspendCancellableCoroutine<Int> { cont ->
            io_uring_prep_writev2(
                sqe = sqe,
                fd = fileDescriptor,
                iovecs = iovecs,
                nr_vecs = buffers.size.convert(),
                offset = offset,
                flags = flags,
            )
            val ref = StableRef.create(cont)
            io_uring_sqe_set_data(sqe, ref.asCPointer())
            cont.registerIOUringCancellation(ring, ref)
        }
        pinnedBuffers.forEach { it.unpin() }
        checkIntResult(result)
    }

    override suspend fun submit() {
        ensureActive()
        submissionEvents.send(Unit)
    }

    /**
     * Try to synchronously get a SQE if possible, otherwise submit the current queue and wait
     * for the next submission queue entry event to be available.
     */
    private suspend fun getSubmissionQueueEntryAndMaybeSubmit(): CPointer<io_uring_sqe> {
        return when (submissionQueueEvents.tryReceive().getOrNull()) {
            null -> {
                // We've tried to acquire a SQE when one is not synchronously available.
                // We should submit the current queue and then suspend until another SQE
                // available and then try again.
                submit()
                submissionQueueEvents.receive()
                getSubmissionQueueEvent()
            }
            else -> getSubmissionQueueEvent()
        }
    }

    private fun getSubmissionQueueEvent(): CPointer<io_uring_sqe> {
        return checkNotNull(io_uring_get_sqe(ring.ptr)) {
            "io_uring_get_sqe unexpectedly returned null."
        }
    }

    private suspend fun poll() = coroutineScope {
        launch { pollSubmissions() }
        launch { pollCompletions() }
        launch { cooperativelyCancel() }
    }

    private suspend fun cooperativelyCancel(): Nothing = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            // The ring has been canceled. In the event that there's no readily available
            // CQE to be processed in the queue, we prime the ring with a nop. This allows
            // the thread looping over completion events to unblock from io_uring_wait_cqe,
            // process the event, and then check for cancellation. Upon seeing that the scope
            // has been cancelled, we do not enter the loop again and thus gracefully exit.
            submissionQueueEvents.tryReceive().getOrNull()?.run {
                val sqe = getSubmissionQueueEvent()
                io_uring_prep_nop(sqe)
                // Explicitly set the data of this SQE as null. Calling io_uring_cqe_get_data
                // without first setting the data in a SQE results in an undefined return value.
                // In practice this will return a stale pointer to a continuation which has
                // been unpinned from memory. When we try to convert it back into a StableRef
                // the pointer has moved, and thus causes a segfault.
                //
                // Explicitly setting this data as null allows us to properly handle this nop
                // on the CQE process side without attempting to resume a bogus continuation.
                io_uring_sqe_set_data(sqe = sqe, data = null)
            }
            io_uring_submit(ring.ptr)
        }
    }

    private suspend fun pollSubmissions() = withContext(CoroutineName("io_uring submission job")) {
        // Wait for the polling thread to initialize and flip this flag before we start consuming submission events.
        pollingStarted.first { it }
        for (event in submissionEvents) {
            val result = io_uring_submit(ring.ptr)
            check(result >= 0) { "io_uring_submit failed with error code $result." }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private suspend fun pollCompletions() {
        val workerThread = newSingleThreadContext("io_uring worker thread")
        try {
            val coroutineName = CoroutineName("io_uring completion queue job")
            withContext(workerThread + coroutineName) {
                pollCompletionsBlocking()
            }
        } finally {
            workerThread.close()
        }
    }

    private fun CoroutineScope.pollCompletionsBlocking() = memScoped {
        val cqe = allocPointerTo<io_uring_cqe>()
        while (isActive) { blockForCompletionEvent(cqe) }
    }

    private fun blockForCompletionEvent(cqe: CPointerVar<io_uring_cqe>) {
        pollingStarted.value = true
        return when (val cqeRes = io_uring_wait_cqe(ring.ptr, cqe.ptr)) {
            0 -> {
                // We have at least one populated CQE. Resume it and then loop other potentially remaining ones.
                resumeContinuation(cqe)
                loopAvailableCqes(cqe)
            }
            else -> error("Failed to get CQE. Error $cqeRes")
        }
    }

    private fun loopAvailableCqes(cqe: CPointerVar<io_uring_cqe>) {
        while (io_uring_peek_cqe(ring.ptr, cqe.ptr) == 0) {
            resumeContinuation(cqe)
        }
    }

    private fun resumeContinuation(cqe: CPointerVar<io_uring_cqe>) {
        io_uring_cqe_seen(ring.ptr, cqe.value)
        io_uring_cqe_get_data(cqe.value)?.let { value ->
            val hydratedCqe = cqe.pointed!!
            val res = hydratedCqe.res
            value.asStableRef<CancellableContinuation<Int>>().use { cont ->
                cont.resume(res)
            }
        }
        submissionQueueEvents.trySend(Unit).getOrThrow()
    }

    private fun ensureActive() = job.ensureActive()
}
