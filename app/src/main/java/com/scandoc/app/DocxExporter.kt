package com.scandoc.app

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** OCRs every page of a scan (on-device) and writes an editable .docx next to the PDF. */
object DocxExporter {

    /** Blocking. Call from a background thread. Throws on failure. */
    fun export(ctx: Context, doc: ScanDoc) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val pages: List<List<String>> = doc.pages().map { file ->
                val image = InputImage.fromFilePath(ctx, Uri.fromFile(file))
                val result = Tasks.await(recognizer.process(image))
                result.textBlocks
                    .map { block -> block.lines.joinToString(" ") { it.text }.trim() }
                    .filter { it.isNotEmpty() }
            }
            val tmp = File(doc.dir, "export.tmp")
            DocxWriter.write(tmp, pages)
            if (!tmp.renameTo(doc.docx)) {
                tmp.copyTo(doc.docx, overwrite = true)
                tmp.delete()
            }
        } finally {
            recognizer.close()
        }
    }
}

/** Minimal, dependency-free WordprocessingML writer: text paragraphs, one page break per scanned page. */
object DocxWriter {

    private val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""

    private val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    private val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>"""

    private val DOC_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>"""

    private val DOC_TAIL = """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""

    private val PAGE_BREAK = """<w:p><w:r><w:br w:type="page"/></w:r></w:p>"""

    fun write(out: File, pages: List<List<String>>) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("word/_rels/document.xml.rels", DOC_RELS)
            zip.put("word/styles.xml", STYLES)
            zip.put("word/document.xml", documentXml(pages))
        }
    }

    private fun documentXml(pages: List<List<String>>): String {
        val sb = StringBuilder(DOC_HEAD)
        pages.forEachIndexed { index, paragraphs ->
            if (index > 0) sb.append(PAGE_BREAK)
            if (paragraphs.isEmpty()) sb.append("<w:p/>")
            for (p in paragraphs) {
                sb.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                sb.append(escape(p))
                sb.append("</w:t></w:r></w:p>")
            }
        }
        sb.append(DOC_TAIL)
        return sb.toString()
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c.code < 0x20 -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
