package com.kevincianfarini.iouring

public fun interface Closeable {
    public fun close()
}

public inline fun <T : Closeable> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        close()
    }
}
