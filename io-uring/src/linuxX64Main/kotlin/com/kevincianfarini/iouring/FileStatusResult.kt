package com.kevincianfarini.iouring

import liburing.statx

public data class FileStatusResult(
    val size: ULong,
)

internal fun statx.toFileStatusResult() = FileStatusResult(
    size = stx_size,
)