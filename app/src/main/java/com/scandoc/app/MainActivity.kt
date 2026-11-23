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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private val adapter = ScanAdapter()
    private var docs: List<ScanDoc> = emptyList()

    /** File waiting to be copied to the location the user picks in the system "save" dialog. */
    private var pendingSave: File? = null

    private val thumbCache = object : LruCache<String, Bitmap>(40) {}

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
                if (result != null) onScanned(result)
            }
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

        listView = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> showActions(docs[position]) }
        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener { startScan() }

        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING, pendingSave?.absolutePath)
    }

    // ---------------------------------------------------------------- scanning

    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                toast(getString(R.string.scan_failed, e.message ?: ""))
            }
    }

    private fun onScanned(result: GmsDocumentScanningResult) {
        val pdfUri: Uri? = result.pdf?.uri
        val pageUris: List<Uri> = result.pages?.map { it.imageUri } ?: emptyList()
        if (pdfUri == null) {
            toast(getString(R.string.error_generic))
            return
        }

        val dialog = busyDialog(getString(R.string.saving))
        thread {
            val doc = runCatching { ScanStore.create(this, pdfUri, pageUris) }.getOrNull()
            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                refresh()
                if (doc != null) showActions(doc) else toast(getString(R.string.error_generic))
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
        thumbCache.get(doc.id)?.let { return it }
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
        private const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
