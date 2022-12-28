package com.kevincianfarini.iouring

import kotlinx.coroutines.runBlocking
import liburing.*
import kotlin.test.*

class URingTest {

    @Test fun `URing no-op returns`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            ring.noOp(entry)
        }
    }

    @Test fun `URing opens file`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            val fd = ring.open(
                entry = entry,
                filePath = "./src/linuxX64Test/resources/hello.txt"
            )
            assertTrue(fd > 0)
        }
    }

    @Test fun `URing closes file descriptor`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            val openEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val fd = ring.open(
                entry = openEntry,
                filePath = "./src/linuxX64Test/resources/hello.txt",
            )
            val closeEntry = checkNotNull(ring.getSubmissionQueueEntry())
            ring.close(closeEntry, fd)
        }
    }

    @Test fun `URing fails to close bad file descriptor`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            assertFailsWith<IllegalStateException> { ring.close(entry, -1) }
        }
    }

    @Test fun `URing vectorRead reads into buffer`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            val openEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val fd = ring.open(openEntry, filePath = "./src/linuxX64Test/resources/hello.txt")
            val buffer = ByteArray(100)
            val readEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val bytesRead = ring.vectorRead(readEntry, fd, buffer)
            assertTrue(bytesRead > 0)
            assertEquals(
                expected = "hello world!",
                actual = buffer.decodeToString(endIndex = bytesRead),
            )
        }
    }

    @Test fun `URing vectorWrite writes buffer contents into file`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            val openEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val fd = ring.open(
                entry = openEntry,
                filePath = "./src/linuxX64Test/resources",
                flags = O_TMPFILE or O_RDWR,
                mode = S_IRWXO,
            )
            val buffer = "goodbye world!".encodeToByteArray()
            val writeEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val bytesWritten = ring.vectorWrite(writeEntry, fd, buffer)
            assertEquals(expected = buffer.size, actual = bytesWritten)

            val readEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val readBuffer = ByteArray(100)
            val bytesRead = ring.vectorRead(readEntry, fd, readBuffer)
            assertEquals(
                expected = "goodbye world!",
                actual = readBuffer.decodeToString(endIndex = bytesRead),
            )
        }
    }
}