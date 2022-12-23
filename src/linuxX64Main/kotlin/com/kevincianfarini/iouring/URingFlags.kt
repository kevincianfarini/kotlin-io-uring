package com.kevincianfarini.iouring

sealed interface URingFlag {
    val flag: UInt

    object NoFlags

    class Composite(override val flag: UInt) : URingFlag
}