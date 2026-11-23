package com.scandoc.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private val adapter = ScanAdapter()
    private var docs: List<ScanDoc> = emptyList()

    /** File waiting to be copied to the location the user picks in the system "save" dialog. */
    private var pendingSave: File? = null

    /** Folder collecting the pages of the scan in progress (null when no scan is running). */
    private var sessionDir: File? = null

    /** Temporary photo (camera shot or gallery import) currently being cropped. */
    private var captureFile: File? = null

    private val thumbCache = object : LruCache<String, Bitmap>(40) {}

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = captureFile
            if (ok && f != null && f.length() > 0) openCrop(f) else afterCancelledStep()
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importUri(uri) else afterCancelledStep()
        }

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            captureFile?.delete()
            captureFile = null
            if (res.resultCode == RESULT_OK) showSessionMenu() else afterCancelledStep()
        }

    private val savePdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) copyPendingTo(uri)
        }

    private val saveDocxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(DOCX_MIME)) { uri ->
            if (uri != null) copyPendingTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pendingSave = savedInstanceState?.getString(KEY_PENDING)?.let { File(it) }
        sessionDir = savedInstanceState?.getString(KEY_SESSION)?.let { File(it) }
        captureFile = savedInstanceState?.getString(KEY_CAPTURE)?.let { File(it) }

        listView = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> showActions(docs[position]) }
        findViewById<View>(R.id.fab).setOnClickListener { startCamera() }
        findViewById<View>(R.id.fabGallery).setOnClickListener { startImport() }

        ScanStore.cleanup(this, sessionDir)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING, pendingSave?.absolutePath)
        outState.putString(KEY_SESSION, sessionDir?.absolutePath)
        outState.putString(KEY_CAPTURE, captureFile?.absolutePath)
    }

    // ---------------------------------------------------------------- scanning

    private fun captureDir(): File = File(cacheDir, "capture").apply { mkdirs() }

    private fun startCamera() {
        val f = File(captureDir(), "cap_${System.currentTimeMillis()}.jpg")
        captureFile = f
        try {
            takePicture.launch(uriFor(f))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_camera))
        }
    }

    private fun startImport() {
        try {
            pickImage.launch("image/*")
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_picker))
        }
    }

    private fun importUri(uri: Uri) {
        val dialog = busyDialog(getString(R.string.loading))
        thread {
            val f = File(captureDir(), "imp_${System.currentTimeMillis()}.jpg")
            val ok = runCatching { ScanStore.copyUriToFile(this, uri, f) }.isSuccess
            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    captureFile = f
                    openCrop(f)
                } else {
                    toast(getString(R.string.error_generic))
                    afterCancelledStep()
                }
            }
        }
    }

    private fun openCrop(photo: File) {
        val session = sessionDir ?: ScanStore.newSession(this).also { sessionDir = it }
        val out = ScanStore.nextPageFile(session)
        val intent = Intent(this, CropActivity::class.java)
            .putExtra(CropActivity.EXTRA_IMAGE, photo.path)
            .putExtra(CropActivity.EXTRA_OUT, out.path)
        cropLauncher.launch(intent)
    }

    /** The user backed out of the camera, the picker or the crop screen. */
    private fun afterCancelledStep() {
        val dir = sessionDir ?: return
        if (ScanStore.pageCount(dir) > 0) showSessionMenu() else discardSession()
    }

    private fun showSessionMenu() {
        val dir = sessionDir ?: return
        val n = ScanStore.pageCount(dir)
        val items = arrayOf(
            getString(R.string.scan_next),
            getString(R.string.import_next),
            getString(R.string.finish_pdf),
            getString(R.string.discard)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(resources.getQuantityString(R.plurals.pages_added, n, n))
            .setCancelable(false)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startCamera()
                    1 -> startImport()
                    2 -> finishSession()
                    3 -> confirmDiscard()
                }
            }
            .show()
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.discard_confirm)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> showSessionMenu() }
            .setPositiveButton(R.string.discard) { _, _ -> discardSession() }
            .show()
    }

    private fun discardSession() {
        sessionDir?.deleteRecursively()
        sessionDir = null
    }

    private fun finishSession() {
        val dir = sessionDir ?: return
        val dialog = busyDialog(getString(R.string.saving))
        thread {
            val doc = runCatching { ScanStore.finishSession(dir) }.getOrNull()
            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (doc != null) {
                    sessionDir = null
                    refresh()
                    showActions(doc)
                } else {
                    toast(getString(R.string.error_generic))
                    showSessionMenu()
                }
            }
        }
    }

    // ----------------------------------------------------------------- actions

    private fun showActions(doc: ScanDoc) {
        val items = arrayOf(
            getString(R.string.open_pdf),
            getString(R.string.share_pdf),
            getString(R.string.save_pdf),
            getString(R.string.share_word),
            getString(R.string.save_word),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(doc.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> viewPdf(doc.pdf)
                    1 -> shareFile(doc.pdf, "application/pdf")
                    2 -> {
                        pendingSave = doc.pdf
                        savePdfLauncher.launch(doc.pdf.name)
                    }
                    3 -> withDocx(doc) { shareFile(it, DOCX_MIME) }
                    4 -> withDocx(doc) {
                        pendingSave = it
                        saveDocxLauncher.launch(it.name)
                    }
                    5 -> confirmDelete(doc)
                }
            }
            .show()
    }

    private fun withDocx(doc: ScanDoc, onReady: (File) -> Unit) {
        if (doc.docx.exists()) {
            onReady(doc.docx)
            return
        }
        val dialog = busyDialog(getString(R.string.reading_text))
        thread {
            val ok = runCatching { DocxExporter.export(this, doc) }.isSuccess
            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok && doc.docx.exists()) onReady(doc.docx) else toast(getString(R.string.error_generic))
            }
        }
    }

    private fun confirmDelete(doc: ScanDoc) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                doc.dir.deleteRecursively()
                refresh()
            }
            .show()
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun viewPdf(file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(file), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_viewer))
        }
    }

    private fun shareFile(file: File, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun copyPendingTo(target: Uri) {
        val src = pendingSave ?: return
        val ok = runCatching {
            val out = contentResolver.openOutputStream(target) ?: error("no stream")
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
        }.isSuccess
        toast(getString(if (ok) R.string.saved else R.string.error_generic))
    }

    // ---------------------------------------------------------------------- UI

    private fun refresh() {
        docs = ScanStore.list(this)
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun busyDialog(message: String): AlertDialog {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(ProgressBar(context).apply { isIndeterminate = true })
            addView(
                TextView(context).apply { text = message; textSize = 16f },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(16) }
            )
        }
        return MaterialAlertDialogBuilder(this)
            .setView(row)
            .setCancelable(false)
            .show()
    }

    private fun thumbFor(doc: ScanDoc): Bitmap? {
        val cached = thumbCache.get(doc.id)
        if (cached != null) return cached
        val bmp = BitmapFactory.decodeFile(doc.thumb.path) ?: return null
        thumbCache.put(doc.id, bmp)
        return bmp
    }

    private inner class ScanAdapter : BaseAdapter() {
        override fun getCount() = docs.size
        override fun getItem(position: Int) = docs[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_scan, parent, false)
            val doc = docs[position]
            view.findViewById<TextView>(R.id.name).text = doc.title
            view.findViewById<TextView>(R.id.meta).text =
                resources.getQuantityString(R.plurals.pages, doc.pageCount, doc.pageCount)
            view.findViewById<ImageView>(R.id.thumb).setImageBitmap(thumbFor(doc))
            return view
        }
    }

    companion object {
        private const val KEY_PENDING = "pending_save"
        private const val KEY_SESSION = "session_dir"
        private const val KEY_CAPTURE = "capture_file"
        private const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
