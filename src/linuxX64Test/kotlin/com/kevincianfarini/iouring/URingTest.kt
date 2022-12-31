package com.kevincianfarini.iouring

import kotlinx.coroutines.runBlocking
import liburing.*
import kotlin.test.*

class URingTest {

    @Test fun `URing returns submission queue entry`() = runBlocking {
        URing(QueueDepth(2u), 0u, this).use { ring ->
            assertNotNull(ring.getSubmissionQueueEntry())
        }
    }

    @Test fun `URing returns null submission queue entry when full`() = runBlocking {
        URing(QueueDepth(2u), 0u, this).use { ring ->
            assertNotNull(ring.getSubmissionQueueEntry())
            assertNotNull(ring.getSubmissionQueueEntry())
            assertNull(ring.getSubmissionQueueEntry())
        }
    }

    @Test fun `URing getSubmissionQueueEntry fails after close`() {
        runBlocking {
            val ring = URing(QueueDepth(2u), 0u, this).also { it.stop() }
            assertFailsWith<IllegalStateException> { ring.getSubmissionQueueEntry() }
        }
    }

    @Test fun `URing no-op returns`() = runBlocking {
        URing(QueueDepth(2u), 0u, this).use { ring ->
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            val nop = ring.noOp(entry)
            assertEquals(expected = 1, actual = ring.submit())
            nop.await()
        }
    }

    @Test fun `URing no-op fails after close`() {
        runBlocking {
            val ring = URing(QueueDepth(2u), 0u, this)
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            ring.stop()
            assertFailsWith<IllegalStateException> { ring.noOp(entry).await() }
        }
    }

    @Test fun `URing opens file`() = runBlocking {
        URing(QueueDepth(2u), 0u, this).use { ring ->
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            val fd = ring.open(
                entry = entry,
                filePath = "./src/linuxX64Test/resources/hello.txt"
            )
            assertEquals(expected = 1, actual = ring.submit())
            assertTrue(fd.await() > 0)
        }
    }

    @Test fun `URing fails to open file after ring close`() {
        runBlocking {
            val ring = URing(QueueDepth(2u), 0u, this)
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            ring.stop()
            assertFailsWith<IllegalStateException> (message = "Uring was cancelled or closed."){
                ring.open(entry, filePath = "./src/linuxX64Test/resources/hello.txt").await()
            }
        }
    }

    @Test fun `URing closes file descriptor`() = runBlocking {
        URing(QueueDepth(2u), 0u, this).use { ring ->
            val openEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val fd = ring.open(
                entry = openEntry,
                filePath = "./src/linuxX64Test/resources/hello.txt",
            )
            assertEquals(expected = 1, actual = ring.submit())
            val closeEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val deferred = ring.close(closeEntry, fd.await())
            assertEquals(expected = 1, actual = ring.submit())
            deferred.await()
        }
    }

    @Test fun `URing fails to close file descriptor after ring close`() {
        runBlocking {
            val ring = URing(QueueDepth(2u), 0u, this)
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            ring.stop()
            assertFailsWith<IllegalStateException> (message = "Uring was cancelled or closed."){
                ring.close(entry, fileDescriptor = -1).await()
            }
        }
    }

    @Test fun `URing fails to close bad file descriptor`() = runBlocking {
        URing(QueueDepth(2u), 0u, this).use { ring ->
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            assertFailsWith<IllegalStateException> {
                ring.close(entry, -1).let { deferred ->
                    assertEquals(expected = 1, actual = ring.submit())
                    deferred.await()
                }
            }
        }
    }

    @Test fun `URing vectorRead reads into buffer`() = runBlocking {
        URing(QueueDepth(2u), 0u, this).use { ring ->
            val openEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val fd = ring.open(openEntry, filePath = "./src/linuxX64Test/resources/hello.txt").let {
                assertEquals(expected = 1, actual = ring.submit())
                it.await()
            }
            val buffer = ByteArray(100)
            val readEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val bytesRead = ring.vectorRead(readEntry, fd, buffer).let {
                assertEquals(expected = 1, actual = ring.submit())
                it.await()
            }
            assertTrue(bytesRead > 0)
            assertEquals(
                expected = "hello world!",
                actual = buffer.decodeToString(endIndex = bytesRead),
            )
        }
    }

    @Test fun `URing vectorRead fails after ring close`() {
        runBlocking {
            val ring = URing(QueueDepth(2u), 0u, this)
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            ring.stop()
            assertFailsWith<IllegalStateException> (message = "Uring was cancelled or closed.") {
                ring.vectorRead(entry, fileDescriptor = -1).await()
            }
        }
    }

    @Test fun `URing vectorWrite writes buffer contents into file`() = runBlocking {
        URing(QueueDepth(2u), 0u, this).use { ring ->
            val openEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val fd = ring.open(
                entry = openEntry,
                filePath = "./src/linuxX64Test/resources",
                flags = O_TMPFILE or O_RDWR,
                mode = S_IRWXO,
            ).let {
                assertEquals(expected = 1, actual = ring.submit())
                it.await()
            }
            val buffer = "goodbye world!".encodeToByteArray()
            val writeEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val bytesWritten = ring.vectorWrite(writeEntry, fd, buffer).let {
                assertEquals(expected = 1, actual = ring.submit())
                it.await()
            }
            assertEquals(expected = buffer.size, actual = bytesWritten)

            val readEntry = checkNotNull(ring.getSubmissionQueueEntry())
            val readBuffer = ByteArray(100)
            val bytesRead = ring.vectorRead(readEntry, fd, readBuffer).let {
                assertEquals(expected = 1, actual = ring.submit())
                it.await()
            }
            assertEquals(
                expected = "goodbye world!",
                actual = readBuffer.decodeToString(endIndex = bytesRead),
            )
        }
    }

    @Test fun `URing vectorWrite fails after ring close`() {
        runBlocking {
            val ring = URing(QueueDepth(2u), 0u, this)
            val entry = checkNotNull(ring.getSubmissionQueueEntry())
            ring.stop()
            assertFailsWith<IllegalStateException> (message = "Uring was cancelled or closed."){
                ring.vectorWrite(entry, fileDescriptor = -1).await()
            }
        }
    }
}