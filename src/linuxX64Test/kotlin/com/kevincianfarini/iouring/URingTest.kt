package com.kevincianfarini.iouring

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class URingTest {

    @Test fun `URing awaitForCompletion returns`() = runBlocking {
        URing(QueueDepth(2u), 0u).use { ring ->
            val event = SubmissionQueueEvent(
                opcode = Opcode.NoOp,
                fileDescriptor = 0,
            )
            ring.awaitCompletionFor(event)
        }
    }
}