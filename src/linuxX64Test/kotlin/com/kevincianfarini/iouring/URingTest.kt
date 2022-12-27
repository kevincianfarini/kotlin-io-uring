package com.kevincianfarini.iouring

import kotlinx.coroutines.runBlocking
import liburing.*
import kotlin.test.*

class URingTest {

    @Test fun `URing no-op returns`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            ring.noOp()
        }
    }

    @Test fun `URing opens file`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            assertTrue(ring.open("./src/linuxX64Test/resources/hello.txt") > 0)
        }
    }

    @Test fun `URing closes file descriptor`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            val fd = ring.open("./src/linuxX64Test/resources/hello.txt")
            ring.close(fd)
        }
    }

    @Test fun `URing fails to close bad file descriptor`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            assertFailsWith<IllegalStateException> { ring.close(-1) }
        }
    }

    @Test fun `URing vectorRead reads into buffer`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            val fd = ring.open("./src/linuxX64Test/resources/hello.txt")
            val buffer = ByteArray(100)
            val bytesRead = ring.vectorRead(fd, buffer)
            assertTrue(bytesRead > 0)
            assertEquals(
                expected = "hello world!",
                actual = buffer.decodeToString(endIndex = bytesRead),
            )
        }
    }
}