package com.mabu.faceoverlay

object SerialPort {
    init { System.loadLibrary("mabuserial") }

    /** Returns a non-negative fd on success, or a negative errno on failure. */
    @JvmStatic external fun openTty(path: String, baud: Int): Int

    /** Returns bytes written, or negative errno. */
    @JvmStatic external fun writeBytes(fd: Int, data: ByteArray, off: Int, len: Int): Int

    @JvmStatic external fun closeTty(fd: Int)
}
