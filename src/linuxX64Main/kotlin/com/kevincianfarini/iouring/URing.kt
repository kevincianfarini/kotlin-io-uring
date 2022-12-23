package com.kevincianfarini.iouring

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import liburing.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public class URing(queueDepth: QueueDepth, ringFlags: UInt) : Closeable {

    private val scope = CoroutineScope(Job())
    private val arena = Arena()
    private val ring: io_uring = arena.alloc()

    init {
        io_uring_queue_init(queueDepth.depth, ring.ptr, ringFlags)
        setupWorkerLoop()
    }

    public suspend fun awaitCompletionFor(event: SubmissionQueueEvent) {
        var sqe: io_uring_sqe? = io_uring_get_sqe(ring.ptr)?.pointed
        while (sqe == null) {
            // Loop and yield control to other coroutines. Do this until a call
            // to `io_uring_get_sqe` results in a usable SQE; this function returns
            // a NULL pointer if the submission queue is full.
            yield()
            sqe = io_uring_get_sqe(ring.ptr)?.pointed
        }

        suspendCancellableCoroutine<Unit> { cont ->
            val ref = StableRef.create(cont)
            io_uring_sqe_set_data64(sqe.ptr, ref.userData)
            queueSubmission(sqe, event)

            cont.invokeOnCancellation {
                 io_uring_prep_cancel64(
                     user_data = ref.userData,
                     sqe = sqe.ptr,
                     flags = 0,
                 )
                io_uring_submit(ring.ptr)
                ref.dispose()
            }
        }
    }

    private fun queueSubmission(sqe: io_uring_sqe, event: SubmissionQueueEvent) {
        when (event) {
            SubmissionQueueEvent.NoOp -> io_uring_prep_nop(sqe.ptr)
        }
        io_uring_submit(ring.ptr)
    }

    private fun setupWorkerLoop() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val workerThread = newSingleThreadContext("io_uring thread")
        val channel = Channel<Pair<Int, StableRef<CancellableContinuation<Unit>>>>()
        scope.launch(context = CoroutineName("io_uring poll job") + workerThread) {
            val cqe = arena.allocPointerTo<io_uring_cqe>()
            while (isActive) {
                io_uring_wait_cqe(ring.ptr, cqe.ptr)
                io_uring_cqe_seen(ring.ptr, cqe.value)
                val hydratedCqe = cqe.pointed!!
                val ref = hydratedCqe.user_data.toVoidPointer()!!.asStableRef<CancellableContinuation<Unit>>()
                channel.send(hydratedCqe.res to ref)
            }
        }

        scope.launch {
            for ((res, ref) in channel) {
                val continuation = ref.get()
                ref.dispose()
                when (res) {
                    0 -> continuation.resume(Unit)
                    else -> continuation.resumeWithException(
                        IllegalStateException("io_uring error number $res.")
                    )
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        io_uring_queue_exit(ring.ptr)
        arena.clear()
    }
}

public value class QueueDepth(public val depth: UInt) {
    init { require(depth.isPowerOf2() && depth <= 32_788u) }
}

private inline fun UInt.isPowerOf2(): Boolean {
    return (this != 0u) && (this and (this - 1u) == 0u)
}

private val StableRef<*>.userData: ULong get() = asCPointer().rawValue.toLong().toULong()
private fun ULong.toVoidPointer(): COpaquePointer? {
    return toLong().toCPointer()
}