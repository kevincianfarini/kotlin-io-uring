package com.kevincianfarini.iouring

import liburing.STATX_SIZE

public value class FileStatusRequest internal constructor(internal val bitMask: UInt)

public class FileStatusRequestBuilder internal constructor() {

    private var bitMask: UInt = 0u

    public fun requestFileSize() { bitMask = bitMask or STATX_SIZE }

    public fun build(): FileStatusRequest = FileStatusRequest(bitMask)
}

public fun FileStatusRequest(build: FileStatusRequestBuilder.() -> Unit): FileStatusRequest {
    return FileStatusRequestBuilder().apply(build).build()
}