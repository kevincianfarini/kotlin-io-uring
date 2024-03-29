package com.kevincianfarini.iouring

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import liburing.*
import kotlin.test.*

class KernelURingTest {

   @Test fun `URing exits when parent scope cancels`() = runTest {
       val trigger = Channel<Unit>()
       val job = launch {
           val ring = KernelURing(QueueDepth(2u), 0u, this)
           val foo = launch { ring.noOp() }
           ring.submit()
           foo.join()
           trigger.send(Unit)
       }
       trigger.receive()
       job.cancelAndJoin()
   }

    @Test fun `URing no-op returns`() = runTest {
        useRing { ring ->
            val job = launch { ring.noOp() }
            ring.submit()
            job.join()
        }
    }

    @Test fun `URing no-op cancels`() = runTest {
        useRing { ring ->
            val job = async {
                ring.noOp()
                throw AssertionError("no-op completed!")
            }.also { it.cancel() }
            ring.submit()
            assertFailsWith<CancellationException> { job.await() }
        }
    }

    @Test fun `URing no-op fails after close`() = runTest {
        ringCancellationAssert { noOp() }
    }

    @Test fun `URing automatically submits and replenishes submission queue`() = runTest {
        useRing { ring ->
            val job1 = launch { ring.noOp() }
            val job2 = launch { ring.noOp() }
            val job3 = launch { ring.noOp() }
            job1.join()
            job2.join()
            ring.submit()
            job3.join()
        }
    }

    @Test fun `URing opens file`() = runTest {
        useRing { ring ->
            val fd = async { ring.open(filePath = "./src/linuxX64Test/resources/hello.txt") }
            ring.submit()
            assertTrue(fd.await() > 0)
        }
    }

    @Test fun `URing fails to open file after ring close`() = runTest {
        ringCancellationAssert {
            open(filePath = "./src/linuxX64Test/resources/hello.txt")
        }
    }

    @Test fun `URing closes file descriptor`() = runTest {
        useRing { ring ->
            val fd = async { ring.open(filePath = "./src/linuxX64Test/resources/hello.txt") }
            ring.submit()
            fd.await()
            val job = launch { ring.close(fd.await()) }
            ring.submit()
            job.join()
        }
    }

    @Test fun `URing fails to close file descriptor after ring close`() = runTest {
        ringCancellationAssert {
            close(fileDescriptor = -1)
        }
    }

    @Test fun `URing fails to close bad file descriptor`() {
        runTest {
            useRing { ring ->
                val job = launch {
                    val e = assertFailsWith<IllegalStateException> {
                        ring.close(-1)
                    }
                    assertEquals(expected = "io_uring error number -9.", actual = e.message)
                }
                ring.submit()
                job.join()
            }
        }
    }


    @Test fun `URing fileStatus returns file size`() = runTest {
        useRing { ring ->
            val status = async {
                ring.fileStatus(
                    filePath = "./src/linuxX64Test/resources/hello.txt",
                    request = FileStatusRequest.Size,
                )
            }
            ring.submit()
            assertEquals(
                expected = 12u,
                actual = status.await().size,
            )
        }
    }

    @Test fun `URing vectorRead reads into buffer`() = runTest {
        useRing { ring ->
            val fd = async { ring.open(filePath = "./src/linuxX64Test/resources/hello.txt") }
            ring.submit()
            val buffer = ByteArray(100)
            fd.await()
            val bytesRead = async { ring.vectorRead(fd.await(), buffer) }
            ring.submit()
            assertTrue(bytesRead.await() > 0)
            assertEquals(
                expected = "hello world!",
                actual = buffer.decodeToString(endIndex = bytesRead.await()),
            )
        }
    }

    @Test fun `URing vectorRead fails after ring close`() = runTest {
        ringCancellationAssert {
            vectorRead(fileDescriptor = -1)
        }
    }

    @Test fun `URing vectorWrite writes buffer contents into file`() = runTest {
        useRing { ring ->
            val fd = async {
                ring.open(
                    filePath = "./src/linuxX64Test/resources",
                    flags = O_TMPFILE or O_RDWR,
                    mode = S_IRWXO,
                )
            }
            ring.submit()
            fd.await()
            val buffer = "goodbye world!".encodeToByteArray()
            val bytesWritten = async {
                ring.vectorWrite(fd.await(), buffer)
            }
            ring.submit()
            assertEquals(expected = buffer.size, actual = bytesWritten.await())

            val readBuffer = ByteArray(100)
            val bytesRead = async {
                ring.vectorRead(fd.await(), readBuffer)
            }
            ring.submit()
            assertEquals(
                expected = "goodbye world!",
                actual = readBuffer.decodeToString(endIndex = bytesRead.await()),
            )
        }
    }

    @Test fun `URing vectorWrite fails after ring close`() = runTest {
        ringCancellationAssert {
            vectorWrite( fileDescriptor = -1)
        }
    }
}

/**
 * This test helper ensures that [assert] completed before we cancel the ring therefore not giving false positives for
 * tests.
 */
private suspend fun useRing(
    queueDepth: QueueDepth = QueueDepth(2u),
    ringFlags: UInt = 0u,
    assert: suspend CoroutineScope.(ring: KernelURing) -> Unit,
) = coroutineScope {
    val trigger = Channel<Unit>()
    val job = launch {
        val ring = KernelURing(queueDepth, ringFlags, this)
        assert(ring)
        trigger.send(Unit)
    }
    trigger.receive()
    job.cancelAndJoin()
}

private suspend fun ringCancellationAssert(
    assert: suspend KernelURing.() -> Unit,
) {
    val scope = CoroutineScope(Job())
    val ring = KernelURing(QueueDepth(2u), 0u, scope)
    scope.cancel()
    assertFailsWith<CancellationException> { ring.assert() }
}
