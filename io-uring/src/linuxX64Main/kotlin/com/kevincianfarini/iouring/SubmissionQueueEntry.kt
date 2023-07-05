package com.kevincianfarini.iouring

import liburing.io_uring_sqe

public value class SubmissionQueueEntry(internal val sqe: io_uring_sqe)