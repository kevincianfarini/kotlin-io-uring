package com.kevincianfarini.iouring

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletionHandler
import platform.posix.close

internal sealed class DisposingContinuation<T>(
    private val delegate: CancellableContinuation<T>,
    private vararg val closeables: Closeable,
) : CancellableContinuation<T> by delegate {

    private var registeredCancellationHandler: CompletionHandler? = null

    init {
        delegate.invokeOnCancellation { t ->
            closeables.forEach(Closeable::close)
            registeredCancellationHandler?.invoke(t)
        }
    }

    override fun resumeWith(result: Result<T>) {
        closeables.forEach(Closeable::close)
        delegate.resumeWith(result)
    }

    override fun invokeOnCancellation(handler: CompletionHandler) {
        check(registeredCancellationHandler == null) { "Already registered a cancellation handler." }
        registeredCancellationHandler = handler
    }
}

internal class IntContinuation(
    delegate: CancellableContinuation<Int>,
    vararg closeables: Closeable,
): DisposingContinuation<Int>(delegate = delegate, closeables = closeables)

internal class UnitContinuation(
    delegate: CancellableContinuation<Unit>,
    vararg closeables: Closeable,
) : DisposingContinuation<Unit>(delegate = delegate, closeables = closeables)