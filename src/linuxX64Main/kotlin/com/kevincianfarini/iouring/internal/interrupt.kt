package com.kevincianfarini.iouring.internal

import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.*
import liburing.SIGINT
import liburing.pthread_kill
import liburing.pthread_t
import platform.posix.pthread_self
import platform.posix.signal
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A rudimentary implementation of runInterruptible similar to Kotlin/JVM's implementation.
 * This function will run [block] within [coroutineContext]. Upon cancellation, this function
 * will send a [signal] to the underlying thread of [coroutineContext] via [pthread_kill].
 *
 * Successful usage of this function assumes that [block] can be interrupted from an operating
 * system signal. Usage which does not respect signals may hang or deadlock.
 */
internal suspend fun <T> runInterruptible(
    signal: Int = SIGINT,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    block: () -> T
) {
    val thread = withContext(coroutineContext) { pthread_self() }
    signal(signal, staticCFunction<Int, Unit> {
        throw CancellationException("SIGINT!")
    })
    return coroutineScope {
        launch { interruptWhenCancelled(thread, signal) }
        withContext(coroutineContext) { block() }
    }
}
private suspend fun interruptWhenCancelled(thread: pthread_t, signal: Int): Nothing {
    suspendCancellableCoroutine<Nothing> { cont ->
        cont.invokeOnCancellation { pthread_kill(thread, signal) }
    }
}
