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

    public suspend fun noOp() {
        val sqe = ring.getSubmissionQueueEvent()
        return suspendCancellableCoroutine { cont ->
            val continuation = UnitContinuation(cont)
            val ref = StableRef.create(continuation)
            io_uring_prep_nop(sqe.ptr)
            io_uring_sqe_set_data64(sqe.ptr, ref.userData)
            continuation.registerIOUringCancellation(ring, sqe, ref)
            io_uring_submit(ring.ptr)
        }
    }

    public suspend fun open(
        filePath: String,
        directoryFileDescriptor: Int = AT_FDCWD,
        flags: Int = 0,
        mode: Int = 0,
        resolve: Int = 0,
    ): Int {
        val sqe = ring.getSubmissionQueueEvent()
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
                sqe = sqe.ptr,
                dfd = directoryFileDescriptor,
                path = pathPointer,
                how = how.ptr,
            )
            io_uring_sqe_set_data64(sqe.ptr, ref.userData)
            continuation.registerIOUringCancellation(ring, sqe, ref)
            io_uring_submit(ring.ptr)
        }
    }

    private fun setupWorkerLoop() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val workerThread = newSingleThreadContext("io_uring thread")
        scope.launch(context = CoroutineName("io_uring poll job") + workerThread) {
            memScoped {
                val cqe = allocPointerTo<io_uring_cqe>()
                while (isActive) {
                    io_uring_wait_cqe(ring.ptr, cqe.ptr)
                    io_uring_cqe_seen(ring.ptr, cqe.value)
                    val hydratedCqe = cqe.pointed!!
                    val userDataPointer = checkNotNull(hydratedCqe.user_data.toVoidPointer()) {
                        "No user data found in completion queue entry."
                    }
                    val res = hydratedCqe.res
                    userDataPointer.asStableRef<DisposingContinuation<*>>().use { cont ->
                        when (cont) {
                            is IntContinuation -> when {
                                res < 0 -> cont.resumeWithException(
                                    IllegalStateException("Error code $res.")
                                )
                                else -> cont.resume(res)
                            }
                            is UnitContinuation -> when (res) {
                                0 -> cont.resume(Unit)
                                else -> cont.resumeWithException(
                                    IllegalStateException("io_uring error number $res.")
                                )
                            }
                        }
                    }
                }
            }
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

private suspend inline fun io_uring.getSubmissionQueueEvent(): io_uring_sqe {
    var sqe: io_uring_sqe? = io_uring_get_sqe(ptr)?.pointed
    while (sqe == null) {
        // Loop and yield control to other coroutines. Do this until a call
        // to `io_uring_get_sqe` results in a usable SQE; this function returns
        // a NULL pointer if the submission queue is full.
        yield()
        sqe = io_uring_get_sqe(ptr)?.pointed
    }
    return sqe
}

private fun <T : CVariable> CValues<T>.getPointer(
    placement: NativeFreeablePlacement
): CPointer<T> = place(interpretCPointer(placement.alloc(size, align).rawPtr)!!)

private inline fun <T : Any> StableRef<T>.use(block: (T) -> Unit) = try {
    block(get())
} finally {
    dispose()
}
