package com.mabu.facetrackadb

object SerialPort {
    init { System.loadLibrary("mabuserial") }

    @JvmStatic external fun openTty(path: String, baud: Int): Int
    @JvmStatic external fun writeBytes(fd: Int, data: ByteArray, off: Int, len: Int): Int
    @JvmStatic external fun closeTty(fd: Int)
}
