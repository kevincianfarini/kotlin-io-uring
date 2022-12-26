package com.kevincianfarini.iouring

public sealed interface SubmissionQueueEvent<ReturnValue : Any> {

    public object NoOp : SubmissionQueueEvent<Unit>
}