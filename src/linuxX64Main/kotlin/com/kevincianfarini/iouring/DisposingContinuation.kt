package com.kevincianfarini.iouring

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletionHandler
import platform.posix.close

internal sealed class DisposingContinuation<T>(
    private val delegate: CancellableContinuation<T>,
    private val closeable: Closeable? = null,
) : CancellableContinuation<T> by delegate {

    private var registeredCancellationHandler: CompletionHandler? = null

    init {
        delegate.invokeOnCancellation { t ->
            closeable?.close()
            registeredCancellationHandler?.invoke(t)
        }
    }

    override fun resumeWith(result: Result<T>) {
        closeable?.close()
        delegate.resumeWith(result)
    }

    override fun invokeOnCancellation(handler: CompletionHandler) {
        check(registeredCancellationHandler == null) { "Already registered a cancellation handler." }
        registeredCancellationHandler = handler
    }
}

internal class IntContinuation(
    delegate: CancellableContinuation<Int>,
    closeable: Closeable? = null,
): DisposingContinuation<Int>(delegate = delegate, closeable = closeable)

internal class UnitContinuation(
    delegate: CancellableContinuation<Unit>,
    closeable: Closeable? = null,
) : DisposingContinuation<Unit>(delegate = delegate, closeable = closeable)