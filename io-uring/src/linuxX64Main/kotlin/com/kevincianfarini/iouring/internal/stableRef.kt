package com.kevincianfarini.iouring.internal

import kotlinx.cinterop.StableRef

internal inline fun <T : Any> StableRef<T>.use(block: (T) -> Unit) = try {
    block(get())
} finally {
    dispose()
}
