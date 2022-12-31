package com.kevincianfarini.iouring

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletionHandler
import platform.posix.close

internal sealed class DisposingContinuation<T>(
    private val delegate: CancellableContinuation<T>,
    private val disposable: Disposable? = null,
) : CancellableContinuation<T> by delegate {

    private var registeredCancellationHandler: CompletionHandler? = null

    init {
        delegate.invokeOnCancellation { t ->
            disposable?.dispose()
            registeredCancellationHandler?.invoke(t)
        }
    }

    override fun resumeWith(result: Result<T>) {
        disposable?.dispose()
        delegate.resumeWith(result)
    }

    override fun invokeOnCancellation(handler: CompletionHandler) {
        check(registeredCancellationHandler == null) { "Already registered a cancellation handler." }
        registeredCancellationHandler = handler
    }
}

internal class IntContinuation(
    delegate: CancellableContinuation<Int>,
    disposable: Disposable? = null,
): DisposingContinuation<Int>(delegate = delegate, disposable = disposable)

internal class UnitContinuation(
    delegate: CancellableContinuation<Unit>,
    disposable: Disposable? = null,
) : DisposingContinuation<Unit>(delegate = delegate, disposable = disposable)