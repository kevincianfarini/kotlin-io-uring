package com.kevincianfarini.iouring

import liburing.AT_FDCWD

public interface URing {

    /**
     * An operation that performs no actual work, but execises the [URing] infrastructure.
     * This function is generally useful for determining the optimal settings under which
     * io_uring should operate.
     *
     * This function will automatically [submit] existing work requests if this task cannot be
     * synchronously submitted for execution. In this scenario this function will suspend prior
     * to submission. Once execution capacity has become available this function will resume,
     * submit, and then suspend until work is complete.
     */
    public suspend fun noOp()

    /**
     * Open a [filePath] located within [directoryFileDescriptor]. This will return an integer file descriptor.
     *
     * This function will automatically [submit] existing work requests if this task cannot be
     * synchronously submitted for execution. In this scenario this function will suspend prior
     * to submission. Once execution capacity has become available this function will resume,
     * submit, and then suspend until work is complete.
     */
    public suspend fun open(
        filePath: String,
        directoryFileDescriptor: Int = AT_FDCWD,
        flags: Int = 0,
        mode: Int = 0,
        resolve: Int = 0,
    ): Int

    /**
     * Close [fileDescriptor].
     *
     * This function will automatically [submit] existing work requests if this task cannot be
     * synchronously submitted for execution. In this scenario this function will suspend prior
     * to submission. Once execution capacity has become available this function will resume,
     * submit, and then suspend until work is complete.
     */
    public suspend fun close(fileDescriptor: Int)

    /**
     * Query a [filePath] located within [directoryFileDescriptor] for its status using a [request].
     *
     * This function will automatically [submit] existing work requests if this task cannot be
     * synchronously submitted for execution. In this scenario this function will suspend prior
     * to submission. Once execution capacity has become available this function will resume,
     * submit, and then suspend until work is complete.
     */
    public suspend fun fileStatus(
        filePath: String,
        request: FileStatusRequest,
        directoryFileDescriptor: Int = AT_FDCWD,
    ): FileStatusResult

    /**
     * Read data from the file underlying [fileDescriptor] into the supplied [buffers] starting at [offset].
     *
     * This function will automatically [submit] existing work requests if this task cannot be
     * synchronously submitted for execution. In this scenario this function will suspend prior
     * to submission. Once execution capacity has become available this function will resume,
     * submit, and then suspend until work is complete.
     */
    public suspend fun vectorRead(
        fileDescriptor: Int,
        vararg buffers: ByteArray,
        offset: ULong = 0u,
        flags: Int = 0,
    ): Int

    /**
     * Write data from the supplied [buffers] into the file underlying [fileDescriptor] starting at [offset].
     *
     * This function will automatically [submit] existing work requests if this task cannot be
     * synchronously submitted for execution. In this scenario this function will suspend prior
     * to submission. Once execution capacity has become available this function will resume,
     * submit, and then suspend until work is complete.
     */
    public suspend fun vectorWrite(
        fileDescriptor: Int,
        vararg buffers: ByteArray,
        offset: ULong = 0u,
        flags: Int = 0,
    ): Int

    /**
     * Flush the queue of tasks awaiting execution.
     *
     * Real, kernel-based implementations of this function will invoke an expensive system call.
     * Therefore, this function should be used only when necessary.
     */
    public suspend fun submit()
}