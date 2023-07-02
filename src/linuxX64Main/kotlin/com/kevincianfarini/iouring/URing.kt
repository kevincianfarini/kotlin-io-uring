package com.kevincianfarini.iouring

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import liburing.*

public class URing(
    queueDepth: QueueDepth,
    ringFlags: UInt,
    parentScope: CoroutineScope,
    private val heap: NativeFreeablePlacement = nativeHeap,
) {

    private val ring: io_uring = heap.alloc()
    private val job: Job = Job().apply {
        invokeOnCompletion {
            io_uring_queue_exit(ring.ptr)
            heap.free(ring.ptr)
        }
    }
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    init {
        io_uring_queue_init(queueDepth.depth, ring.ptr, ringFlags)
        scope.launch { poll() }
    }

    /**
     * Acquire a [SubmissionQueueEntry] to submit requests to io_uring.
     * This function will return null when the submission queue is full,
     * indicating that a [submit] system call should be made to begin
     * processing queued requests.
     */
    public fun getSubmissionQueueEntry(): SubmissionQueueEntry? {
        ensureActive()
        return io_uring_get_sqe(ring.ptr)?.pointed?.let(::SubmissionQueueEntry)
    }

    /**
     * Submit the queued entries to the submission queue. This will being
     * processing and resuming coroutines running suspending io_uring operations.
     *
     * This performs an underlying system call and is therefore an expensive
     * operation.
     */
    public fun submit(): Int {
        ensureActive()
        val ret = io_uring_submit(ring.ptr)
        check(ret > -1) { "Submission error $ret." }
        return ret
    }

    public fun noOp(entry: SubmissionQueueEntry): Deferred<Unit> {
        ensureActive()
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
                val continuation = UnitContinuation(cont)
                val ref = StableRef.create(continuation)
                io_uring_prep_nop(entry.sqe.ptr)
                io_uring_sqe_set_data(entry.sqe.ptr, ref.asCPointer())
                continuation.registerIOUringCancellation(ring, ref)
            }
        }
    }

    public fun open(
        entry: SubmissionQueueEntry,
        filePath: String,
        directoryFileDescriptor: Int = AT_FDCWD,
        flags: Int = 0,
        mode: Int = 0,
        resolve: Int = 0,
    ): Deferred<Int> {
        ensureActive()
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
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
                io_uring_sqe_set_data(entry.sqe.ptr, ref.asCPointer())
                continuation.registerIOUringCancellation(ring, ref)
            }
        }
    }

    public fun close(entry: SubmissionQueueEntry, fileDescriptor: Int): Deferred<Unit> {
        ensureActive()
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
                val continuation = UnitContinuation(cont)
                val ref = StableRef.create(continuation)
                io_uring_prep_close(sqe = entry.sqe.ptr, fd = fileDescriptor)
                io_uring_sqe_set_data(entry.sqe.ptr, ref.asCPointer())
                continuation.registerIOUringCancellation(ring, ref)
            }
        }
    }

    public fun fileStatus(
        entry: SubmissionQueueEntry,
        filePath: String,
        request: FileStatusRequest,
        directoryFileDescriptor: Int = AT_FDCWD,
    ): Deferred<FileStatusResult> {
        ensureActive()
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
                val pathPointer = filePath.utf8.getPointer(heap)
                val statxbuf = heap.alloc<statx>()
                io_uring_prep_statx(
                    sqe = entry.sqe.ptr,
                    dfd = directoryFileDescriptor,
                    path = pathPointer,
                    flags = 0,
                    mask = request.bitMask,
                    statxbuf = statxbuf.ptr,
                )

                val continuation = ValueProducingContinuation(cont, statxbuf::toFileStatusResult) {
                    heap.free(pathPointer)
                    heap.free(statxbuf)
                }
                val ref = StableRef.create(continuation)
                io_uring_sqe_set_data(entry.sqe.ptr, ref.asCPointer())
                continuation.registerIOUringCancellation(ring, ref)
            }
        }
    }

    public fun vectorRead(
        entry: SubmissionQueueEntry,
        fileDescriptor: Int,
        vararg buffers: ByteArray,
        offset: ULong = 0u,
        flags: Int = 0,
    ): Deferred<Int> {
        ensureActive()
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
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
                io_uring_sqe_set_data(entry.sqe.ptr, ref.asCPointer())
                continuation.registerIOUringCancellation(ring, ref)
            }
        }
    }

    public fun vectorWrite(
        entry: SubmissionQueueEntry,
        fileDescriptor: Int,
        vararg buffers: ByteArray,
        offset: ULong = 0u,
        flags: Int = 0,
    ): Deferred<Int> {
        ensureActive()
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
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
                io_uring_sqe_set_data(entry.sqe.ptr, ref.asCPointer())
                continuation.registerIOUringCancellation(ring, ref)
            }
        }
    }

    private suspend fun poll() = withContext(context = CoroutineName("io_uring poll job")) {
        memScoped {
            val cqe = allocPointerTo<io_uring_cqe>()
            while (isActive) {
                resumeContinuation(cqe)
            }
        }
    }

    private suspend fun resumeContinuation(cqe: CPointerVar<io_uring_cqe>) {
        when (val cqeRes = io_uring_peek_cqe(ring.ptr, cqe.ptr)) {
            0 -> {
                io_uring_cqe_seen(ring.ptr, cqe.value)
                val userDataPointer = checkNotNull(io_uring_cqe_get_data(cqe.value)) {
                    """
                        | No completion found for completed event. Did you 
                        | call `getSubmissionEvent` and `submit` without
                        | preparing an IO operation?
                    """.trimMargin()
                }
                val hydratedCqe = cqe.pointed!!
                val res = hydratedCqe.res
                userDataPointer.asStableRef<DisposingContinuation<*>>().use { cont ->
                    cont.resumeWithIntResult(res)
                }
            }
            // There were no synchronously available completion queue events ready to be resumed. This
            // coroutine will [yield] to other coroutines awaiting execution under the assumption that
            // eventually, a completion queue event will become available.
            -EAGAIN -> yield()
            else -> error("Failed to get CQE. Error $cqeRes")
        }
    }

    public suspend fun stop(): Unit = job.cancelAndJoin()

    private fun ensureActive() = check(job.isActive) { "URing was cancelled or closed." }
}

public suspend inline fun URing.use(block: (URing) -> Unit) {
    try {
        block(this)
    } finally {
        stop()
    }
}

private inline fun <T : Any> CancellableContinuation<T>.registerIOUringCancellation(
    ring: io_uring,
    ref: StableRef<*>,
) = invokeOnCancellation {
    memScoped {
        val cancellationRegistration = alloc<io_uring_sync_cancel_reg> {
            this.addr = ref.asCPointer().toLong().convert()
        }
        io_uring_register_sync_cancel(ring.ptr, cancellationRegistration.ptr)
    }
}

private fun <T : CVariable> CValues<T>.getPointer(
    placement: NativeFreeablePlacement
): CPointer<T> = place(interpretCPointer(placement.alloc(size, align).rawPtr)!!)

private inline fun <T : Any> StableRef<T>.use(block: (T) -> Unit) = try {
    block(get())
} finally {
    dispose()
}

private inline fun statx.toFileStatusResult() = FileStatusResult(
    size = stx_size,
)
