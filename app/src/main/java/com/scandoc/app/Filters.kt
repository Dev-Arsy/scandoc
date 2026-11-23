package com.scandoc.app

import kotlin.math.max
import kotlin.math.min

/**
 * Page clean-up filters, row by row so big photos don't need a second full-size int array.
 *
 * MAGIC: divides every pixel by the estimated paper brightness (removes shadows and uneven light, keeps colour),
 *        then stretches contrast so paper turns white and ink turns dark.
 * BW:    same normalisation, then a soft threshold: clean black text on white.
 *
 * No Android classes here on purpose, so it can be unit-tested on a plain JVM.
 */
object Filters {
    const val ORIGINAL = 0
    const val MAGIC = 1
    const val BW = 2

    private const val MIN_BG = 50f

    private const val LOW = 25
    private const val HIGH = 230
    private val LUT = IntArray(256) { ((it - LOW) * 255 / (HIGH - LOW)).coerceIn(0, 255) }

    /**
     * Estimates the paper brightness from a small luminance image: a max filter wipes out dark strokes,
     * then blurring smooths the result.
     */
    fun background(lum: IntArray, sw: Int, sh: Int): FloatArray {
        val dil = maxFilter(lum, sw, sh, 3)
        var f = FloatArray(sw * sh) { dil[it].toFloat() }
        f = blurF(f, sw, sh, 4)
        f = blurF(f, sw, sh, 4)
        return f
    }

    /** Bilinearly stretches the small background map to the full output size, one row at a time. */
    class BgSampler(
        private val bg: FloatArray,
        private val sw: Int,
        private val sh: Int,
        private val outW: Int,
        private val outH: Int
    ) {
        private val x0 = IntArray(outW)
        private val x1 = IntArray(outW)
        private val wx = FloatArray(outW)

        init {
            for (x in 0 until outW) {
                val fx = ((x + 0.5f) * sw / outW - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val i = fx.toInt()
                x0[x] = i
                x1[x] = min(i + 1, sw - 1)
                wx[x] = fx - i
            }
        }

        fun row(y: Int, dst: FloatArray) {
            val fy = ((y + 0.5f) * sh / outH - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val j = fy.toInt()
            val j1 = min(j + 1, sh - 1)
            val wy = fy - j
            val r0 = j * sw
            val r1 = j1 * sw
            for (x in 0 until outW) {
                val a = bg[r0 + x0[x]] * (1f - wx[x]) + bg[r0 + x1[x]] * wx[x]
                val b = bg[r1 + x0[x]] * (1f - wx[x]) + bg[r1 + x1[x]] * wx[x]
                dst[x] = a * (1f - wy) + b * wy
            }
        }
    }

    /** Filters [n] ARGB pixels of [row] in place. [bgRow] is the paper brightness for each of those pixels. */
    fun processRow(row: IntArray, n: Int, bgRow: FloatArray, mode: Int) {
        if (mode == ORIGINAL) return
        for (i in 0 until n) {
            val p = row[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val bgv = max(bgRow[i], MIN_BG)

            if (mode == MAGIC) {
                val k = 255f / bgv
                val nr = LUT[min(255, (r * k).toInt())]
                val ng = LUT[min(255, (g * k).toInt())]
                val nb = LUT[min(255, (b * k).toInt())]
                row[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            } else {
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val v = (smooth(lum / bgv, 0.55f, 0.82f) * 255f + 0.5f).toInt()
                row[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun smooth(x: Float, a: Float, b: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun maxFilter(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 0
                for (k in -r..r) {
                    val xx = x + k
                    if (xx in 0 until w) m = max(m, src[y * w + xx])
                }
                tmp[y * w + x] = m
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 0
                for (k in -r..r) {
                    val yy = y + k
                    if (yy in 0 until h) m = max(m, tmp[yy * w + x])
                }
                out[y * w + x] = m
            }
        }
        return out
    }

    private fun blurF(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var c = 0
                for (k in -r..r) {
                    val xx = x + k
                    if (xx in 0 until w) { s += src[y * w + xx]; c++ }
                }
                tmp[y * w + x] = s / c
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var c = 0
                for (k in -r..r) {
                    val yy = y + k
                    if (yy in 0 until h) { s += tmp[yy * w + x]; c++ }
                }
                out[y * w + x] = s / c
            }
        }
        return out
    }
}
