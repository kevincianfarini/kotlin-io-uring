package com.kevincianfarini.iouring

import kotlinx.coroutines.runBlocking
import liburing.*
import kotlin.test.Test
import kotlin.test.assertTrue

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
}