package com.kevincianfarini.iouring

public class SubmissionQueueEvent(
    public val opcode: Opcode,
    public val fileDescriptor: Int,
)