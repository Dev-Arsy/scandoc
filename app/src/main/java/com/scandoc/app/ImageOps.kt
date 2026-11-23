package com.scandoc.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Android-side image helpers: decode, detect, perspective-correct and clean up a page photo. */
object ImageOps {

    /** Longest side the photo is decoded to while editing (keeps memory sane on 3-4 GB phones). */
    private const val MAX_SIDE = 3200

    /** Longest side of the finished page. A4 at about 300 dpi. */
    private const val OUT_MAX_SIDE = 3000

    private const val DETECT_SIDE = 320
    private const val BG_SIDE = 160

    /** Decodes [path], applies the EXIF rotation and caps the size. Returns null if it cannot be read. */
    fun decodeUpright(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        val decoded: Bitmap? = try {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            null
        }
        var bmp: Bitmap = decoded ?: return null

        val longSide = max(bmp.width, bmp.height)
        if (longSide > MAX_SIDE * 5 / 4) {
            val s = MAX_SIDE.toFloat() / longSide
            bmp = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * s).roundToInt().coerceAtLeast(1),
                (bmp.height * s).roundToInt().coerceAtLeast(1),
                true
            )
        }

        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Page corners (TL, TR, BR, BL as x,y pairs) in [bmp] pixels. Falls back to an inset rectangle. */
    fun detectCorners(bmp: Bitmap): FloatArray {
        val fallback = DocDetector.defaultQuad(bmp.width.toFloat(), bmp.height.toFloat())

        val scale = DETECT_SIDE.toFloat() / max(bmp.width, bmp.height)
        val sw = max(16, (bmp.width * scale).roundToInt())
        val sh = max(16, (bmp.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)

        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val gray = IntArray(sw * sh) { luma(px[it]) }

        val q = DocDetector.detect(gray, sw, sh) ?: return fallback
        val sx = bmp.width.toFloat() / sw
        val sy = bmp.height.toFloat() / sh
        return FloatArray(8) { if (it % 2 == 0) q[it] * sx else q[it] * sy }
    }

    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { setRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Straightens the quadrilateral [c] (TL, TR, BR, BL) of [src] into an upright rectangle. */
    fun warp(src: Bitmap, c: FloatArray): Bitmap {
        val top = hypot(c[2] - c[0], c[3] - c[1])
        val bottom = hypot(c[4] - c[6], c[5] - c[7])
        val left = hypot(c[6] - c[0], c[7] - c[1])
        val right = hypot(c[4] - c[2], c[5] - c[3])

        var w = max(top, bottom)
        var h = max(left, right)
        if (w < 8f || h < 8f) return src

        val k = OUT_MAX_SIDE / max(w, h)
        if (k < 1f) {
            w *= k
            h *= k
        }
        val ow = w.roundToInt().coerceAtLeast(16)
        val oh = h.roundToInt().coerceAtLeast(16)

        val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(c, 0, dst, 0, 4)) {
            // Degenerate corners: just crop the bounding box.
            val l = min(min(c[0], c[6]), min(c[2], c[4])).toInt().coerceIn(0, src.width - 1)
            val t = min(min(c[1], c[3]), min(c[5], c[7])).toInt().coerceIn(0, src.height - 1)
            val r = max(max(c[0], c[6]), max(c[2], c[4])).toInt().coerceIn(l + 1, src.width)
            val b = max(max(c[1], c[3]), max(c[5], c[7])).toInt().coerceIn(t + 1, src.height)
            return Bitmap.createBitmap(src, l, t, r - l, b - t)
        }

        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, m, paint)
        return out
    }

    /** Returns [src] untouched for ORIGINAL, otherwise a new, cleaned-up bitmap. */
    fun applyFilter(src: Bitmap, mode: Int): Bitmap {
        if (mode == Filters.ORIGINAL) return src

        val w = src.width
        val h = src.height

        // Small copy -> paper brightness map.
        val k = BG_SIDE.toFloat() / max(w, h)
        val sw = max(8, (w * k).roundToInt())
        val sh = max(8, (h * k).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val bg = Filters.background(IntArray(sw * sh) { luma(sp[it]) }, sw, sh)
        val sampler = Filters.BgSampler(bg, sw, sh, w, h)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val chunkRows = 16
        val buf = IntArray(w * chunkRows)
        val row = IntArray(w)
        val bgRow = FloatArray(w)

        var y = 0
        while (y < h) {
            val rows = min(chunkRows, h - y)
            src.getPixels(buf, 0, w, 0, y, w, rows)
            for (r in 0 until rows) {
                System.arraycopy(buf, r * w, row, 0, w)
                sampler.row(y + r, bgRow)
                Filters.processRow(row, w, bgRow, mode)
                System.arraycopy(row, 0, buf, r * w, w)
            }
            out.setPixels(buf, 0, w, 0, y, w, rows)
            y += rows
        }
        return out
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 255
        val g = (p shr 8) and 255
        val b = p and 255
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
