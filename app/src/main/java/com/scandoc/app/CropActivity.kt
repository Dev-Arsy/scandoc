package com.scandoc.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File
import kotlin.concurrent.thread

/**
 * Two steps for one page:
 *  1. CROP   - drag the corners (auto-detected first), rotate if needed.
 *  2. REVIEW - straightened page with a filter choice (Original / Magic / B&W), then save as a JPEG.
 */
class CropActivity : AppCompatActivity() {

    private lateinit var crop: CropView
    private lateinit var preview: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var hint: TextView
    private lateinit var cropBar: View
    private lateinit var reviewBar: View
    private lateinit var filters: MaterialButtonToggleGroup
    private lateinit var outFile: File

    private var source: Bitmap? = null   // upright photo being cropped
    private var warped: Bitmap? = null   // straightened page, no filter
    private var result: Bitmap? = null   // warped + filter, what gets saved
    private var mode = Filters.MAGIC

    private var inReview = false
    private var busy = false
    private var token = 0                // drops outdated filter results when buttons are tapped quickly

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        val imagePath = intent.getStringExtra(EXTRA_IMAGE)
        val outPath = intent.getStringExtra(EXTRA_OUT)
        if (imagePath == null || outPath == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        outFile = File(outPath)

        crop = findViewById(R.id.crop)
        preview = findViewById(R.id.preview)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)
        cropBar = findViewById(R.id.cropBar)
        reviewBar = findViewById(R.id.reviewBar)
        filters = findViewById(R.id.filters)

        findViewById<View>(R.id.btnRotate).setOnClickListener { if (!busy) rotate() }
        findViewById<View>(R.id.btnAuto).setOnClickListener { if (!busy) autoDetect() }
        findViewById<View>(R.id.btnNext).setOnClickListener { if (!busy) next() }
        findViewById<View>(R.id.btnBack).setOnClickListener { if (!busy) showReview(false) }
        findViewById<View>(R.id.btnSave).setOnClickListener { if (!busy) save() }

        filters.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.f_original -> Filters.ORIGINAL
                R.id.f_bw -> Filters.BW
                else -> Filters.MAGIC
            }
            if (inReview) applyMode()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inReview) {
                    showReview(false)
                } else {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        })

        loadImage(imagePath)
    }

    // ------------------------------------------------------------------ step 1: crop

    private fun loadImage(path: String) {
        setBusy(true)
        thread {
            val bmp = try { ImageOps.decodeUpright(path) } catch (t: Throwable) { null }
            val corners = bmp?.let {
                try {
                    ImageOps.detectCorners(it)
                } catch (t: Throwable) {
                    DocDetector.defaultQuad(it.width.toFloat(), it.height.toFloat())
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (bmp == null || corners == null) {
                    toast(getString(R.string.no_image))
                    setResult(RESULT_CANCELED)
                    finish()
                    return@runOnUiThread
                }
                source = bmp
                crop.setImage(bmp, corners)
            }
        }
    }

    private fun rotate() {
        val s = source ?: return
        val c = crop.getCorners()
        val h = s.height.toFloat()
        val rotatedOrNull: Bitmap? = try { ImageOps.rotate90(s) } catch (t: Throwable) { null }
        val rotated = rotatedOrNull ?: return

        // 90 degrees clockwise: (x, y) -> (h - y, x). The old BL becomes the new TL, and so on.
        val newCorners = floatArrayOf(
            h - c[7], c[6],   // new TL = old BL
            h - c[1], c[0],   // new TR = old TL
            h - c[3], c[2],   // new BR = old TR
            h - c[5], c[4]    // new BL = old BR
        )
        source = rotated
        crop.setImage(rotated, newCorners)
    }

    private fun autoDetect() {
        val s = source ?: return
        setBusy(true)
        thread {
            val corners = try { ImageOps.detectCorners(s) } catch (t: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (corners != null) crop.setCorners(corners)
            }
        }
    }

    private fun next() {
        val s = source ?: return
        val corners = crop.getCorners()
        val currentMode = mode
        setBusy(true)
        thread {
            val w = try { ImageOps.warp(s, corners) } catch (t: Throwable) { null }
            val r = if (w != null) {
                try { ImageOps.applyFilter(w, currentMode) } catch (t: Throwable) { null }
            } else {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (w == null || r == null) {
                    toast(getString(R.string.error_generic))
                    return@runOnUiThread
                }
                warped = w
                result = r
                preview.setImageBitmap(r)
                showReview(true)
            }
        }
    }

    // ---------------------------------------------------------------- step 2: review

    private fun applyMode() {
        val w = warped ?: return
        val m = mode
        val mine = ++token
        setBusy(true)
        thread {
            val r = try { ImageOps.applyFilter(w, m) } catch (t: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed || mine != token) return@runOnUiThread
                setBusy(false)
                if (r == null) {
                    toast(getString(R.string.error_generic))
                    return@runOnUiThread
                }
                result = r
                preview.setImageBitmap(r)
            }
        }
    }

    private fun save() {
        val r = result ?: return
        val quality = if (mode == Filters.BW) 85 else 90
        setBusy(true)
        thread {
            val ok = try {
                ImageOps.saveJpeg(r, outFile, quality)
                true
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (ok) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    toast(getString(R.string.error_generic))
                }
            }
        }
    }

    private fun showReview(on: Boolean) {
        inReview = on
        crop.visibility = if (on) View.GONE else View.VISIBLE
        preview.visibility = if (on) View.VISIBLE else View.GONE
        cropBar.visibility = if (on) View.GONE else View.VISIBLE
        reviewBar.visibility = if (on) View.VISIBLE else View.GONE
        hint.setText(if (on) R.string.review_hint else R.string.crop_hint)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_IMAGE = "image_path"
        const val EXTRA_OUT = "out_path"
    }
}
