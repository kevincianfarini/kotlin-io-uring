package com.kevincianfarini.iouring

class SubmissionQueueEvent(
    val opcode: Opcode,
    val fileDescriptor: Int,
)