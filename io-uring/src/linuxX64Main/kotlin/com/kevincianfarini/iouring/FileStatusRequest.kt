package com.kevincianfarini.iouring

import liburing.*

public value class FileStatusRequest internal constructor(internal val bitMask: UInt) {

    public operator fun plus(other: FileStatusRequest): FileStatusRequest {
        return FileStatusRequest(bitMask or other.bitMask)
    }

    public operator fun minus(other: FileStatusRequest): FileStatusRequest {
        return FileStatusRequest(bitMask and other.bitMask.inv())
    }

    public companion object {
        public val Size: FileStatusRequest = FileStatusRequest(STATX_SIZE)
        public val Blocks: FileStatusRequest = FileStatusRequest(STATX_BLOCKS)
        public val UserIdentifier: FileStatusRequest = FileStatusRequest(STATX_UID)
        public val GroupIdentifier: FileStatusRequest = FileStatusRequest(STATX_GID)
        public val Mode: FileStatusRequest = FileStatusRequest(STATX_MODE)
        public val LastAccessed: FileStatusRequest = FileStatusRequest(STATX_ATIME)
        public val Created: FileStatusRequest = FileStatusRequest(STATX_BTIME)
        public val LastModified: FileStatusRequest = FileStatusRequest(STATX_MTIME)
        public val StatusChanged: FileStatusRequest = FileStatusRequest(STATX_CTIME)
    }
}
