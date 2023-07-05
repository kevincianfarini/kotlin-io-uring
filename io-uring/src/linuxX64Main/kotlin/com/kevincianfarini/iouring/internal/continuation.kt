package com.kevincianfarini.iouring.internal

import kotlinx.cinterop.*
import kotlinx.coroutines.CancellableContinuation
import liburing.io_uring
import liburing.io_uring_register_sync_cancel
import liburing.io_uring_sync_cancel_reg

internal inline fun CancellableContinuation<*>.registerIOUringCancellation(
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
