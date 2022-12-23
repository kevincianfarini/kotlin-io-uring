package com.kevincianfarini.iouring

public sealed interface SubmissionQueueEvent {

    public object NoOp : SubmissionQueueEvent
}