package com.flipper.psadecrypt

class KeeloqBruteForce {
    companion object {
        init {
            System.loadLibrary("keeloq_bruteforce")
        }
    }

    external fun nativeBruteForce(
        learningType: Int,
        serial: Int, fix: Int,
        hop1: Int, hop2: Int,
        rangeStart: Int, rangeEnd: Int,
        threadIdx: Int,
        resultOut: LongArray
    ): Boolean

    external fun nativeResetThread(threadIdx: Int)
    external fun nativeSetCancel(threadIdx: Int)
    external fun nativeGetKeysTested(threadIdx: Int): Int
}
