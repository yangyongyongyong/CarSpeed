package com.thomas.carspeed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 颜色转换是导出里吃 CPU 的部分。按行切开，段数跟着可用核心走，多核同时写互不重叠的行。
 */
internal object ExportCoreSplit {
    fun workerCount(cores: Int, height: Int): Int {
        if (cores <= 1 || height < 64) return 1
        val usable = (cores - 1).coerceAtLeast(2)
        return usable.coerceAtMost(6).coerceAtMost(height / 32).coerceAtLeast(1)
    }

    fun rowBands(height: Int, workers: Int): List<IntRange> {
        val rows = height.coerceAtLeast(0)
        if (rows == 0) return emptyList()
        val count = workers.coerceAtLeast(1).coerceAtMost(rows)
        if (count == 1) return listOf(0 until rows)
        val base = rows / count
        val extra = rows % count
        var start = 0
        return List(count) { index ->
            val span = base + if (index < extra) 1 else 0
            val end = start + span
            (start until end).also { start = end }
        }
    }
}

internal object ExportYuv {
    fun writeRows(
        startRow: Int,
        endRow: Int,
        width: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yPos: Int,
        uPos: Int,
        vPos: Int,
        yStride: Int,
        uStride: Int,
        vStride: Int,
        yPixel: Int,
        uPixel: Int,
        vPixel: Int,
        argb: IntArray
    ) {
        for (row in startRow until endRow) {
            val yRow = yPos + row * yStride
            val uRow = uPos + (row shr 1) * uStride
            val vRow = vPos + (row shr 1) * vStride
            var offset = row * width
            for (col in 0 until width) {
                val yValue = (y[yRow + col * yPixel].toInt() and 0xff) - 16
                val uvCol = col shr 1
                val uValue = (u[uRow + uvCol * uPixel].toInt() and 0xff) - 128
                val vValue = (v[vRow + uvCol * vPixel].toInt() and 0xff) - 128
                val y298 = 298 * yValue
                var r = (y298 + 409 * vValue + 128) shr 8
                var g = (y298 - 100 * uValue - 208 * vValue + 128) shr 8
                var b = (y298 + 516 * uValue + 128) shr 8
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                argb[offset] = -0x1000000 or (r shl 16) or (g shl 8) or b
                offset += 1
            }
        }
    }
}

internal class ExportYuvPool(workers: Int) : AutoCloseable {
    private val count = workers.coerceAtLeast(1)
    private val pool = if (count == 1) {
        null
    } else {
        Executors.newFixedThreadPool(count) { runnable ->
            Thread(runnable, "export-yuv").apply { isDaemon = true }
        }
    }

    fun convert(
        width: Int,
        height: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yPos: Int,
        uPos: Int,
        vPos: Int,
        yStride: Int,
        uStride: Int,
        vStride: Int,
        yPixel: Int,
        uPixel: Int,
        vPixel: Int,
        argb: IntArray
    ) {
        val bands = ExportCoreSplit.rowBands(height, count)
        val executor = pool
        if (executor == null || bands.size <= 1) {
            ExportYuv.writeRows(
                0, height, width, y, u, v, yPos, uPos, vPos,
                yStride, uStride, vStride, yPixel, uPixel, vPixel, argb
            )
            return
        }
        val latch = CountDownLatch(bands.size)
        val failure = AtomicReference<Throwable>()
        for (band in bands) {
            executor.execute {
                try {
                    ExportYuv.writeRows(
                        band.first, band.last + 1, width, y, u, v, yPos, uPos, vPos,
                        yStride, uStride, vStride, yPixel, uPixel, vPixel, argb
                    )
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        failure.get()?.let { throw it }
    }

    override fun close() {
        pool?.shutdownNow()
    }
}
