package com.kevincianfarini.iouring

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import liburing.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public class URing(
    queueDepth: QueueDepth,
    ringFlags: UInt,
    private val heap: NativeFreeablePlacement = nativeHeap,
) : Closeable {

    private val scope = CoroutineScope(Job())
    private val ring: io_uring = heap.alloc()

    init {
        io_uring_queue_init(queueDepth.depth, ring.ptr, ringFlags)
        setupWorkerLoop()
    }

    /**
     * Acquire a [SubmissionQueueEntry] to submit requests to io_uring.
     * This function will return null when the submission queue is full,
     * indicating that a [submit] system call should be made to begin
     * processing queued requests.
     */
    public fun getSubmissionQueueEntry(): SubmissionQueueEntry? {
        return io_uring_get_sqe(ring.ptr)?.pointed?.let(::SubmissionQueueEntry)
    }

    /**
     * Submit the queued entries to the submission queue. This will being
     * processing and resuming coroutines running suspending io_uring operations.
     *
     * This performs an underlying system call and is therefore an expensive
     * operation.
     */
    public fun submit() {
        val ret = io_uring_submit(ring.ptr)
        check(ret > -1) { "Submission error $ret." }
    }

    public suspend fun noOp(entry: SubmissionQueueEntry) {
        return suspendCancellableCoroutine { cont ->
            val continuation = UnitContinuation(cont)
            val ref = StableRef.create(continuation)
            io_uring_prep_nop(entry.sqe.ptr)
            io_uring_sqe_set_data64(entry.sqe.ptr, ref.userData)
            continuation.registerIOUringCancellation(ring, entry.sqe, ref)
            io_uring_submit(ring.ptr)
        }
    }

    public suspend fun open(
        entry: SubmissionQueueEntry,
        filePath: String,
        directoryFileDescriptor: Int = AT_FDCWD,
        flags: Int = 0,
        mode: Int = 0,
        resolve: Int = 0,
    ): Int {
        return suspendCancellableCoroutine { cont ->
            val pathPointer = filePath.utf8.getPointer(heap)
            val how = heap.alloc<open_how> {
                this.flags = flags.convert()
                this.mode = mode.convert()
                this.resolve = resolve.convert()
            }
            val continuation = IntContinuation(cont) {
                heap.free(how)
                heap.free(pathPointer)
            }

            val ref = StableRef.create(continuation)
            io_uring_prep_openat2(
                sqe = entry.sqe.ptr,
                dfd = directoryFileDescriptor,
                path = pathPointer,
                how = how.ptr,
            )
            io_uring_sqe_set_data64(entry.sqe.ptr, ref.userData)
            continuation.registerIOUringCancellation(ring, entry.sqe, ref)
            io_uring_submit(ring.ptr)
        }
    }

    public suspend fun close(
        entry: SubmissionQueueEntry,
        fileDescriptor: Int
    ) {
        return suspendCancellableCoroutine { cont ->
            val continuation = UnitContinuation(cont)
            val ref = StableRef.create(continuation)
            io_uring_prep_close(sqe = entry.sqe.ptr, fd = fileDescriptor)
            io_uring_sqe_set_data64(entry.sqe.ptr, ref.userData)
            continuation.registerIOUringCancellation(ring, entry.sqe, ref)
            io_uring_submit(ring.ptr)
        }
    }

    public suspend fun vectorRead(
        entry: SubmissionQueueEntry,
        fileDescriptor: Int,
        vararg buffers: ByteArray,
        offset: ULong = 0u,
        flags: Int = 0,
    ): Int {
        return suspendCancellableCoroutine { cont ->
            val pinnedBuffers = buffers.map { it.pin() }
            val iovecs = heap.allocArray<iovec>(buffers.size) { index ->
                iov_len = pinnedBuffers[index].get().size.convert()
                iov_base = pinnedBuffers[index].addressOf(0)
            }
            io_uring_prep_readv2(
                sqe = entry.sqe.ptr,
                fd = fileDescriptor,
                iovecs = iovecs,
                nr_vecs = buffers.size.convert(),
                offset = offset,
                flags = flags,
            )
            val continuation = IntContinuation(cont) {
                pinnedBuffers.forEach { it.unpin() }
                heap.free(iovecs)
            }
            val ref = StableRef.create(continuation)
            io_uring_sqe_set_data64(entry.sqe.ptr, ref.userData)
            continuation.registerIOUringCancellation(ring, entry.sqe, ref)
            io_uring_submit(ring.ptr)
        }
    }

    public suspend fun vectorWrite(
        entry: SubmissionQueueEntry,
        fileDescriptor: Int,
        vararg buffers: ByteArray,
        offset: ULong = 0u,
        flags: Int = 0,
    ): Int {
        return suspendCancellableCoroutine { cont ->
            val pinnedBuffers = buffers.map { it.pin() }
            val iovecs = heap.allocArray<iovec>(buffers.size) { index ->
                iov_len = pinnedBuffers[index].get().size.convert()
                iov_base = pinnedBuffers[index].addressOf(0)
            }
            io_uring_prep_writev2(
                sqe = entry.sqe.ptr,
                fd = fileDescriptor,
                iovecs = iovecs,
                nr_vecs = buffers.size.convert(),
                offset = offset,
                flags = flags,
            )
            val continuation = IntContinuation(cont) {
                pinnedBuffers.forEach { it.unpin() }
                heap.free(iovecs)
            }
            val ref = StableRef.create(continuation)
            io_uring_sqe_set_data64(entry.sqe.ptr, ref.userData)
            continuation.registerIOUringCancellation(ring, entry.sqe, ref)
            io_uring_submit(ring.ptr)
        }
    }

    private fun setupWorkerLoop() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val workerThread = newSingleThreadContext("io_uring thread")
        scope.launch(context = CoroutineName("io_uring poll job") + workerThread) {
            memScoped {
                val cqe = allocPointerTo<io_uring_cqe>()
                while (isActive) { resumeContinuation(cqe) }
            }
        }
    }

    private fun resumeContinuation(cqe: CPointerVar<io_uring_cqe>) {
        io_uring_wait_cqe(ring.ptr, cqe.ptr)
        io_uring_cqe_seen(ring.ptr, cqe.value)
        val hydratedCqe = cqe.pointed!!
        val userDataPointer = checkNotNull(hydratedCqe.user_data.toVoidPointer()) {
            "No user data found in completion queue entry."
        }
        val res = hydratedCqe.res
        userDataPointer.asStableRef<DisposingContinuation<*>>().use { cont ->
            cont.resumeWithIntRes(res)
        }
    }

    override fun close() {
        scope.cancel()
        io_uring_queue_exit(ring.ptr)
        heap.free(ring.ptr)
    }
}

private val StableRef<*>.userData: ULong get() = asCPointer().rawValue.toLong().toULong()
private fun ULong.toVoidPointer(): COpaquePointer? {
    return toLong().toCPointer()
}

private inline fun <T : Any> CancellableContinuation<T>.registerIOUringCancellation(
    ring: io_uring,
    sqe: io_uring_sqe,
    ref: StableRef<*>,
) = invokeOnCancellation {
    io_uring_prep_cancel64(sqe.ptr, ref.userData, flags = 0)
    io_uring_submit(ring.ptr)
}

private fun <T : CVariable> CValues<T>.getPointer(
    placement: NativeFreeablePlacement
): CPointer<T> = place(interpretCPointer(placement.alloc(size, align).rawPtr)!!)

private inline fun <T : Any> StableRef<T>.use(block: (T) -> Unit) = try {
    block(get())
} finally {
    dispose()
}

private inline fun DisposingContinuation<*>.resumeWithIntRes(res: Int) = when (this) {
    is IntContinuation -> when {
        res < 0 -> resumeWithException(
            IllegalStateException("Error code $res.")
        )
        else -> resume(res)
    }
    is UnitContinuation -> when (res) {
        0 -> resume(Unit)
        else -> resumeWithException(
            IllegalStateException("io_uring error number $res.")
        )
    }
}
