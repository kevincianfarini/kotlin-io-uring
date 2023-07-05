package com.kevincianfarini.iouring.internal

/**
 * Assert that [result] is a non-error io_uring API Int result.
 */
internal inline fun checkIntResult(result: Int): Int {
    check(result >= 0) { "io_uring error number $result." }
    return result
}