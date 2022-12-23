package com.kevincianfarini.iouring

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletionHandler

internal class DisposableContinuation<T>(
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