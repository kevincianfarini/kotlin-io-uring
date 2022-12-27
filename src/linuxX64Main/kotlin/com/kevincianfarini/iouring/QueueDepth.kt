package com.kevincianfarini.iouring

public value class QueueDepth(public val depth: UInt) {
    init { require(depth.isPowerOf2() && depth <= 32_788u) }
}

private inline fun UInt.isPowerOf2(): Boolean {
    return (this != 0u) && (this and (this - 1u) == 0u)
}
