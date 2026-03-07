package com.flipper.psadecrypt

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-threaded BF executor — splits key range across CPU cores.
 */
data class BfResult(
    val found: Boolean,
    val counter: Int = 0,
    val decV0: Int = 0,
    val decV1: Int = 0,
    val elapsedMs: Long = 0
)

class BruteForceExecutor {
    private val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    private val executor = Executors.newFixedThreadPool(numThreads)
    private val cancelled = AtomicBoolean(false)
    private val totalKeysTested = AtomicInteger(0)

    /** Per-thread cancel flags and progress counters (shared with JNI) */
    private val cancelFlags = Array(numThreads) { intArrayOf(0) }
    private val perThreadTested = Array(numThreads) { intArrayOf(0) }

    fun getTotalKeysTested(): Int {
        var sum = 0
        for (arr in perThreadTested) {
            sum += arr[0]
        }
        return sum
    }

    fun cancel() {
        cancelled.set(true)
        for (flag in cancelFlags) {
            flag[0] = 1
        }
    }

    /**
     * Run BF across all cores. Blocks until done or cancelled.
     * @param bfType 1 or 2
     * @param w0 encrypted word 0
     * @param w1 encrypted word 1
     * @param rangeStart full range start (e.g. 0x23000000 for BF1)
     * @param rangeEnd full range end (e.g. 0x24000000 for BF1)
     */
    fun run(bfType: Int, w0: Int, w1: Int, rangeStart: Int, rangeEnd: Int): BfResult {
        cancelled.set(false)
        for (flag in cancelFlags) flag[0] = 0
        for (arr in perThreadTested) arr[0] = 0

        val startTime = System.currentTimeMillis()

        // Split range into chunks (using unsigned arithmetic via Long)
        val rangeSize = (rangeEnd.toLong() and 0xFFFFFFFFL) - (rangeStart.toLong() and 0xFFFFFFFFL)
        val chunkSize = rangeSize / numThreads

        val futures = mutableListOf<Future<BfResult>>()

        for (i in 0 until numThreads) {
            val chunkStart = (rangeStart.toLong() and 0xFFFFFFFFL) + (chunkSize * i)
            val chunkEnd = if (i == numThreads - 1) {
                rangeEnd.toLong() and 0xFFFFFFFFL
            } else {
                chunkStart + chunkSize
            }

            val threadIdx = i
            val future = executor.submit<BfResult> {
                val bf = TeaBruteForce()
                val resultOut = IntArray(3)
                val found = bf.nativeBruteForce(
                    bfType, w0, w1,
                    chunkStart.toInt(), chunkEnd.toInt(),
                    cancelFlags[threadIdx],
                    perThreadTested[threadIdx],
                    resultOut
                )
                if (found) {
                    // Cancel all other threads
                    cancel()
                    BfResult(
                        found = true,
                        counter = resultOut[0],
                        decV0 = resultOut[1],
                        decV1 = resultOut[2],
                        elapsedMs = System.currentTimeMillis() - startTime
                    )
                } else {
                    BfResult(found = false, elapsedMs = System.currentTimeMillis() - startTime)
                }
            }
            futures.add(future)
        }

        // Wait for all threads and find the winning result
        var winner: BfResult? = null
        for (future in futures) {
            val result = future.get()
            if (result.found && winner == null) {
                winner = result
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        return winner ?: BfResult(found = false, elapsedMs = elapsed)
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }
}
