package com.scandoc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved scan: a folder with page JPEGs, a thumbnail, the PDF and (once exported) the DOCX. */
data class ScanDoc(
    val id: String,
    val dir: File,
    val title: String,
    val pageCount: Int
) {
    val pdf: File get() = File(dir, "Scan_$id.pdf")
    val docx: File get() = File(dir, "Scan_$id.docx")
    val thumb: File get() = File(dir, "thumb.jpg")

    fun pages(): List<File> =
        dir.listFiles { f -> f.name.matches(ScanStore.PAGE_REGEX) }?.sortedBy { it.name } ?: emptyList()
}

object ScanStore {
    val PAGE_REGEX = Regex("p\\d{3}\\.jpg")
    private const val ID_FORMAT = "yyyyMMdd_HHmmss"

    private fun root(ctx: Context): File = File(ctx.filesDir, "scans").apply { mkdirs() }

    fun list(ctx: Context): List<ScanDoc> =
        root(ctx).listFiles { f -> f.isDirectory }
            ?.filter { File(it, "Scan_${it.name}.pdf").exists() }
            ?.sortedByDescending { it.name }
            ?.map { toDoc(it) }
            ?: emptyList()

    private fun toDoc(dir: File): ScanDoc {
        val id = dir.name
        val date = runCatching { SimpleDateFormat(ID_FORMAT, Locale.US).parse(id) }.getOrNull()
        val title = if (date != null) {
            "Scan " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(date)
        } else {
            "Scan $id"
        }
        val count = dir.listFiles()?.count { it.name.matches(PAGE_REGEX) } ?: 0
        return ScanDoc(id, dir, title, count)
    }

    /** Copies the scanner output into app storage. Call from a background thread. */
    fun create(ctx: Context, pdfUri: Uri, pageUris: List<Uri>): ScanDoc {
        val id = SimpleDateFormat(ID_FORMAT, Locale.US).format(Date())
        val dir = File(root(ctx), id).apply { mkdirs() }

        copy(ctx, pdfUri, File(dir, "Scan_$id.pdf"))
        pageUris.forEachIndexed { i, uri ->
            copy(ctx, uri, File(dir, "p%03d.jpg".format(i + 1)))
        }
        val first = File(dir, "p001.jpg")
        if (first.exists()) makeThumb(first, File(dir, "thumb.jpg"))
        return toDoc(dir)
    }

    private fun copy(ctx: Context, src: Uri, dest: File) {
        val input = ctx.contentResolver.openInputStream(src) ?: throw IOException("Cannot open $src")
        input.use { ins -> FileOutputStream(dest).use { out -> ins.copyTo(out) } }
    }

    private fun makeThumb(src: File, dest: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 240) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(src.path, opts) ?: return
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
    }
}
