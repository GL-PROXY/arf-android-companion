package com.flipper.psadecrypt

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

data class KlBfResult(
    val found: Boolean,
    val mfkey: Long = 0,
    val devkey: Long = 0,
    val cnt: Int = 0,
    val elapsedMs: Long = 0,
    val learnType: Int = 0
)

class KeeloqBfExecutor {
    private val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    private val executor = Executors.newFixedThreadPool(numThreads)
    private val cancelled = AtomicBoolean(false)
    private val bf = KeeloqBruteForce()

    fun getTotalKeysTested(): Long {
        var sum = 0L
        for (i in 0 until numThreads) {
            sum += (bf.nativeGetKeysTested(i).toLong() and 0xFFFFFFFFL)
        }
        return sum
    }

    fun cancel() {
        cancelled.set(true)
        for (i in 0 until numThreads) {
            bf.nativeSetCancel(i)
        }
    }

    fun run(
        learningType: Int,
        serial: Int, fix: Int,
        hop1: Int, hop2: Int,
        rangeStart: Long, rangeEnd: Long
    ): KlBfResult {
        cancelled.set(false)
        for (i in 0 until numThreads) {
            bf.nativeResetThread(i)
        }

        val startTime = System.currentTimeMillis()
        val rangeSize = rangeEnd - rangeStart
        val chunkSize = rangeSize / numThreads

        val futures = mutableListOf<Future<KlBfResult>>()

        for (i in 0 until numThreads) {
            val chunkStart = rangeStart + (chunkSize * i)
            val chunkEnd = if (i == numThreads - 1) rangeEnd else chunkStart + chunkSize

            val threadIdx = i
            val future = executor.submit<KlBfResult> {
                val threadBf = KeeloqBruteForce()
                val resultOut = LongArray(3)
                val found = threadBf.nativeBruteForce(
                    learningType, serial, fix, hop1, hop2,
                    chunkStart.toInt(), chunkEnd.toInt(),
                    threadIdx,
                    resultOut
                )
                if (found) {
                    cancel()
                    KlBfResult(
                        found = true,
                        mfkey = resultOut[0],
                        devkey = resultOut[1],
                        cnt = resultOut[2].toInt(),
                        elapsedMs = System.currentTimeMillis() - startTime,
                        learnType = learningType
                    )
                } else {
                    KlBfResult(found = false, elapsedMs = System.currentTimeMillis() - startTime)
                }
            }
            futures.add(future)
        }

        var winner: KlBfResult? = null
        for (future in futures) {
            val result = future.get()
            if (result.found && winner == null) {
                winner = result
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        return winner ?: KlBfResult(found = false, elapsedMs = elapsed)
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }
}
