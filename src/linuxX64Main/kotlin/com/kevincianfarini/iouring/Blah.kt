package com.kevincianfarini.iouring

import kotlinx.cinterop.*
import liburing.io_uring
import liburing.io_uring_queue_exit
import liburing.io_uring_queue_init
import liburing.open
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY

//private const val QUEUE_DEPTH: UInt = 64u
//
//fun main() = setupRing().use { ring ->
//    val fileDescriptors = getFileDescrirptors("", "")
//    copyFile(ring, fileDescriptors)
//    close()
//}
//
//private fun copyFile(ring: URing, inFd: Int, outFd: Int) {
//
//}
//
//private fun getFileDescrirptors(filePath: String): Int {
//    val outFileDescritor = open(outFilePath, O_WRONLY or O_CREAT or O_TRUNC, 644).also { fd ->
//        check(fd > 0) { "Failed to open $inFilePath." }
//    }
//
//    return inFileDescriptor to outFileDescritor
//}
//
//private fun setupRing(): URing {
//    val arena = Arena()
//    val ring: io_uring = arena.alloc()
//
//    io_uring_queue_init(
//        entries = QUEUE_DEPTH,
//        ring = ring.ptr,
//        flags = 0u,
//    ).also { checkReturnCode(it) { "Queue init failed." } }
//
//    return URing(ring, arena)
//}
//
//class URing(
//    val ring: io_uring,
//    val arena: Arena,
//) : Closeable {
//
//    override fun close() {
//        io_uring_queue_exit(ring.ptr)
//        arena.clear()
//    }
//}
//
//interface Closeable {
//    fun close()
//}
//
//fun <T : Closeable> T.use(block: (T) -> Unit) = try {
//    block(this)
//} finally {
//    close()
//}
//
//private fun FileDescriptor()
//
//internal inline fun checkReturnCode(code: Int, message: () -> String) {
//    check(code == 0) { message() }
//}