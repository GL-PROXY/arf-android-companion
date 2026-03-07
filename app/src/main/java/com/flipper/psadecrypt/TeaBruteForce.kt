package com.flipper.psadecrypt

/**
 * JNI wrapper for native TEA brute-force.
 * Each instance handles one sub-range of the key space.
 */
class TeaBruteForce {
    companion object {
        init {
            System.loadLibrary("tea_bruteforce")
        }
    }

    /**
     * Run brute-force on a sub-range.
     * @param bfType 1 = BF1 (3 TEA ops), 2 = BF2 (1 TEA + CRC16)
     * @param w0 first encrypted word
     * @param w1 second encrypted word
     * @param rangeStart start counter (inclusive)
     * @param rangeEnd end counter (exclusive)
     * @param cancelFlag int[1] — set [0] to non-zero to cancel
     * @param keysTested int[1] — updated with keys tested so far
     * @param resultOut int[3] — [counter, dec_v0, dec_v1] on success
     * @return true if key found
     */
    external fun nativeBruteForce(
        bfType: Int,
        w0: Int, w1: Int,
        rangeStart: Int, rangeEnd: Int,
        cancelFlag: IntArray,
        keysTested: IntArray,
        resultOut: IntArray
    ): Boolean
}
