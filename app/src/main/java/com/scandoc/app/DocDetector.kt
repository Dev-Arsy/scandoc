package com.scandoc.app

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds the paper in a photo without any external library.
 *
 * Idea: blur, split the picture into "light" and "dark" regions with Otsu's threshold, take the biggest blob
 * (tried for both polarities), and use its four extreme points as the page corners. The blob that looks most
 * like a filled quadrilateral and does not hug the image border wins. If nothing convincing is found the
 * caller falls back to [defaultQuad], and the user can drag the corners by hand anyway.
 *
 * No Android classes here on purpose, so it can be unit-tested on a plain JVM.
 */
object DocDetector {

    /** Corners as x0,y0,x1,y1,x2,y2,x3,y3 = TL, TR, BR, BL in the pixel space of [gray], or null. */
    fun detect(gray: IntArray, w: Int, h: Int): FloatArray? {
        if (w < 16 || h < 16 || gray.size < w * h) return null

        val blurred = boxBlur(boxBlur(gray, w, h, 2), w, h, 2)
        val threshold = otsu(blurred)

        var best: FloatArray? = null
        var bestScore = 0.0

        for (bright in booleanArrayOf(true, false)) {
            val mask = BooleanArray(w * h) { if (bright) blurred[it] > threshold else blurred[it] <= threshold }
            val comp = largestComponent(mask, w, h) ?: continue

            val quadArea = polygonArea(comp.quad)
            val areaFrac = quadArea / (w.toDouble() * h)
            if (areaFrac < 0.08) continue
            val fill = minOf(1.0, comp.area / quadArea)
            if (fill < 0.5) continue

            val border = borderFraction(mask, w, h)
            // The whole picture is one flat blob: nothing was actually found.
            if (areaFrac > 0.97 && border > 0.9) continue

            val score = fill * sqrt(areaFrac) * (1.0 - 0.7 * border)
            if (score > bestScore) {
                bestScore = score
                best = comp.quad
            }
        }
        return best
    }

    /** A rectangle inset from the image edges: the starting point when detection fails. */
    fun defaultQuad(w: Float, h: Float, inset: Float = 0.08f): FloatArray {
        val l = w * inset
        val t = h * inset
        val r = w - l
        val b = h - t
        return floatArrayOf(l, t, r, t, r, b, l, b)
    }

    // ------------------------------------------------------------------ internals

    private class Comp(val area: Int, val quad: FloatArray)

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): Comp? {
        val n = w * h
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        var bestArea = 0
        var bestQuad: FloatArray? = null

        for (start in 0 until n) {
            if (!mask[start] || visited[start]) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            var minS = Int.MAX_VALUE
            var maxS = Int.MIN_VALUE
            var minD = Int.MAX_VALUE
            var maxD = Int.MIN_VALUE
            var iTL = start
            var iBR = start
            var iTR = start
            var iBL = start

            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                val s = x + y
                val d = x - y
                if (s < minS) { minS = s; iTL = p }
                if (s > maxS) { maxS = s; iBR = p }
                if (d > maxD) { maxD = d; iTR = p }
                if (d < minD) { minD = d; iBL = p }

                if (x > 0) {
                    val q = p - 1
                    if (mask[q] && !visited[q]) { visited[q] = true; queue[tail++] = q }
                }
                if (x < w - 1) {
                    val q = p + 1
                    if (mask[q] && !visited[q]) { visited[q] = true; queue[tail++] = q }
                }
                if (y > 0) {
                    val q = p - w
                    if (mask[q] && !visited[q]) { visited[q] = true; queue[tail++] = q }
                }
                if (y < h - 1) {
                    val q = p + w
                    if (mask[q] && !visited[q]) { visited[q] = true; queue[tail++] = q }
                }
            }

            if (tail > bestArea) {
                bestArea = tail
                bestQuad = floatArrayOf(
                    (iTL % w).toFloat(), (iTL / w).toFloat(),
                    (iTR % w).toFloat(), (iTR / w).toFloat(),
                    (iBR % w).toFloat(), (iBR / w).toFloat(),
                    (iBL % w).toFloat(), (iBL / w).toFloat()
                )
            }
        }
        val q = bestQuad ?: return null
        return Comp(bestArea, q)
    }

    private fun polygonArea(q: FloatArray): Double {
        var s = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[2 * i].toDouble() * q[2 * j + 1] - q[2 * j].toDouble() * q[2 * i + 1]
        }
        return abs(s) / 2.0
    }

    /** Share of the image border that lies inside the mask (a background blob covers most of it). */
    private fun borderFraction(mask: BooleanArray, w: Int, h: Int): Double {
        var inside = 0
        var total = 0
        for (x in 0 until w) {
            if (mask[x]) inside++
            if (mask[(h - 1) * w + x]) inside++
            total += 2
        }
        for (y in 1 until h - 1) {
            if (mask[y * w]) inside++
            if (mask[y * w + w - 1]) inside++
            total += 2
        }
        return inside.toDouble() / total
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0
                var c = 0
                for (k in -r..r) {
                    val xx = x + k
                    if (xx in 0 until w) { s += src[row + xx]; c++ }
                }
                tmp[row + x] = s / c
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
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

    private fun otsu(g: IntArray): Int {
        val hist = IntArray(256)
        for (v in g) hist[v.coerceIn(0, 255)]++
        val total = g.size
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = 0.0
        var thr = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        return thr
    }
}
