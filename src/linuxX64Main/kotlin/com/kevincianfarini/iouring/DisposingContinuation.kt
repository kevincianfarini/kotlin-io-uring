package com.kevincianfarini.iouring

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        delegate.resumeWith(result)
        disposable?.dispose()
    }

    override fun invokeOnCancellation(handler: CompletionHandler) {
        check(registeredCancellationHandler == null) { "Already registered a cancellation handler." }
        registeredCancellationHandler = handler
    }

    abstract fun resumeWithIntResult(result: Int)
}

internal class IntContinuation(
    delegate: CancellableContinuation<Int>,
    disposable: Disposable? = null,
): DisposingContinuation<Int>(delegate = delegate, disposable = disposable) {

    override fun resumeWithIntResult(result: Int) = when {
        result < 0 -> resumeWithException(
            IllegalStateException("io_uring error number $result.")
        )
        else -> resume(result)
    }
}

internal class UnitContinuation(
    delegate: CancellableContinuation<Unit>,
    disposable: Disposable? = null,
) : DisposingContinuation<Unit>(delegate = delegate, disposable = disposable) {

    override fun resumeWithIntResult(result: Int) = when (result) {
        0 -> resume(Unit)
        else -> resumeWithException(
            IllegalStateException("io_uring error number $result.")
        )
    }
}

internal class ValueProducingContinuation<T>(
    delegate: CancellableContinuation<T>,
    private val produceValue: () -> T,
    disposable: Disposable? = null,
) : DisposingContinuation<T>(delegate = delegate, disposable = disposable) {

    override fun resumeWithIntResult(result: Int) = when (result) {
        0 -> resume(produceValue())
        else -> resumeWithException(
            IllegalStateException("io_uring error number $result.")
        )
    }
}