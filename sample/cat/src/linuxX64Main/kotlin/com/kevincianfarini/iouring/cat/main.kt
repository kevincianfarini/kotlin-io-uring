package com.kevincianfarini.iouring.cat

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.kevincianfarini.iouring.FileStatusRequest
import com.kevincianfarini.iouring.KernelURing
import com.kevincianfarini.iouring.QueueDepth
import com.kevincianfarini.iouring.URing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import platform.posix.O_RDONLY

fun main(args: Array<String>) = Cat().main(args)

class Cat : CliktCommand() {

    private val filePath: String by argument()

    @ExperimentalStdlibApi
    override fun run() = runBlocking {
    }
}

private const val blockSize = 1024UL

@ExperimentalStdlibApi
private suspend fun URing.catFile(filePath: String) = coroutineScope {
    val fd = async { open(filePath, flags = O_RDONLY) }
    submit()
    fd.await()
    val fileSize = async { fileStatus(filePath, FileStatusRequest.Size) }
    submit()
    val numBlocks = (fileSize.await().size / blockSize).let { size ->
        if (fileSize.await().size % blockSize > 0u) size + 1u else size
    }
    val blocks = Array(numBlocks.toInt()) { ByteArray(blockSize.toInt()) }
    var bytesRemaining = fileSize.await().size

    while (bytesRemaining > 0u) {
        val bytesToRead = minOf(blockSize, bytesRemaining)
        val bytesRead = async { vectorRead(fd.await(), *blocks) }
        submit()
        bytesRemaining -= bytesRead.await().toUInt()
    }
}
