package com.scandoc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Shows a photo with four draggable corner handles. Corners are kept in bitmap pixel coordinates. */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bmp: Bitmap? = null

    /** TL, TR, BR, BL as x,y pairs in bitmap pixels. */
    private val pts = FloatArray(8)

    private val drawMatrix = Matrix()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var active = -1

    private val density = resources.displayMetrics.density
    private val pad = 28f * density
    private val handleRadius = 11f * density
    private val touchRadius = 60f * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3314B8A6")
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#14B8A6")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F766E")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val path = Path()

    fun setImage(bitmap: Bitmap, corners: FloatArray) {
        bmp = bitmap
        System.arraycopy(corners, 0, pts, 0, 8)
        layoutImage()
        invalidate()
    }

    fun setCorners(corners: FloatArray) {
        System.arraycopy(corners, 0, pts, 0, 8)
        invalidate()
    }

    fun getCorners(): FloatArray = pts.copyOf()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImage()
    }

    private fun layoutImage() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val availW = width - 2f * pad
        val availH = height - 2f * pad
        scale = min(availW / b.width, availH / b.height)
        offX = (width - b.width * scale) / 2f
        offY = (height - b.height * scale) / 2f
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(offX, offY)
    }

    private fun vx(i: Int) = pts[2 * i] * scale + offX
    private fun vy(i: Int) = pts[2 * i + 1] * scale + offY

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        canvas.drawBitmap(b, drawMatrix, bitmapPaint)

        path.reset()
        for (i in 0 until 4) {
            if (i == 0) path.moveTo(vx(i), vy(i)) else path.lineTo(vx(i), vy(i))
        }
        path.close()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, linePaint)

        for (i in 0 until 4) {
            canvas.drawCircle(vx(i), vy(i), handleRadius, handleFill)
            canvas.drawCircle(vx(i), vy(i), handleRadius, handleStroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = bmp ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = nearestHandle(event.x, event.y)
                if (active < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active >= 0) {
                    pts[2 * active] = ((event.x - offX) / scale).coerceIn(0f, b.width.toFloat())
                    pts[2 * active + 1] = ((event.y - offY) / scale).coerceIn(0f, b.height.toFloat())
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                return true
            }
        }
        return true
    }

    private fun nearestHandle(x: Float, y: Float): Int {
        var best = -1
        var bestDist = touchRadius
        for (i in 0 until 4) {
            val d = hypot(x - vx(i), y - vy(i))
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }
}
