package com.kevincianfarini.iouring

interface Closeable {
    fun close()
}

inline fun <T : Closeable> T.use(block: (T) -> Unit) = try {
    block(this)
} finally {
    close()
}
