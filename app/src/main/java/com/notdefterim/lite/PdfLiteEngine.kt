package com.notdefterim.lite

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class RecentPdfItem(
    val title: String,
    val path: String,
    val pageCount: Int,
    val openedAtMs: Long,
    val lastPage: Int = 0,
    val fileSizeBytes: Long = 0L,
    val folder: String = "",
    val isFavorite: Boolean = false
)

data class ImportResult(
    val path: String,
    val title: String,
    val fileSizeBytes: Long,
    val isLargeFile: Boolean
)

data class LiteStroke(
    val points: List<PointF>,
    val colorArgb: Int,
    val width: Float,
    val isHighlighter: Boolean
)

data class PdfSearchHit(
    val pageIdx: Int,
    val rectVirtual: RectF?
)

data class PdfSecurityReport(
    val isSafe: Boolean,
    val findings: List<String>
)

data class PdfMeta(
    val pageCount: Int,
    val ratios: List<Float>,
    val optimized: Boolean
)

object PdfLiteEngine {
    private val pageCache = LruCache<String, Bitmap>(48)
    private val thumbCache = LruCache<String, Bitmap>(64)

    private fun docId(pdfPath: String): String = File(pdfPath).nameWithoutExtension

    private fun optDir(context: Context, pdfPath: String): File =
        File(context.cacheDir, "pdf_opt/${docId(pdfPath)}").apply { mkdirs() }

    private fun pagesDir(context: Context, pdfPath: String): File =
        File(optDir(context, pdfPath), "pages").apply { mkdirs() }

    private fun pageFile(context: Context, pdfPath: String, pageIdx: Int): File =
        File(pagesDir(context, pdfPath), "p_$pageIdx.webp")

    private fun thumbFile(context: Context, pdfPath: String): File =
        File(optDir(context, pdfPath), "thumb.webp")

    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP

    private fun hasPdfMagicHeader(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val head = ByteArray(1024)
                val n = fis.read(head)
                if (n <= 4) return false
                String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
            }
        } catch (_: Exception) { false }
    }

    fun scanPdfSecurity(pdfPath: String): PdfSecurityReport {
        val file = File(pdfPath)
        if (!file.exists()) return PdfSecurityReport(false, listOf("Dosya bulunamadı"))
        val triggers = linkedMapOf(
            "/JavaScript" to "Gömülü JavaScript (/JavaScript)",
            "/JS" to "Kısa JavaScript (/JS)",
            "/Launch" to "Harici Program Başlatma (/Launch)",
            "/OpenAction" to "Açılışta Otomatik Eylem (/OpenAction)",
            "/AA" to "Otomatik Tetikleyici (/AA)",
            "/EmbeddedFile" to "Gömülü Dosya (/EmbeddedFile)",
            "/RichMedia" to "Zengin Medya/Flash (/RichMedia)",
            "/SubmitForm" to "Veri Gönderme Formu (/SubmitForm)"
        )
        val found = linkedSetOf<String>()
        try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(65536)
                var overlap = ""
                var scanned = 0L
                val maxScan = 25L * 1024L * 1024L
                while (scanned < maxScan) {
                    val r = fis.read(buf)
                    if (r <= 0) break
                    scanned += r
                    val chunk = overlap + String(buf, 0, r, Charsets.ISO_8859_1)
                    triggers.forEach { (tag, label) ->
                        if (label !in found && Regex(Regex.escape(tag) + "(?![A-Za-z0-9])").containsMatchIn(chunk)) {
                            found.add(label)
                        }
                    }
                    overlap = if (chunk.length > 32) chunk.takeLast(32) else chunk
                    if (found.size == triggers.size) break
                }
            }
        } catch (_: Exception) {}
        return PdfSecurityReport(isSafe = found.isEmpty(), findings = found.toList())
    }

    fun getDisplayName(context: Context, uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (_: Exception) {}
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        val clean = (name ?: "Belge").substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        return clean.ifBlank { "Belge" }
    }

    fun importPdfUri(context: Context, uri: Uri): ImportResult? {
        return try {
            val title = getDisplayName(context, uri)
            val dir = File(context.filesDir, "lite_pdfs").apply { mkdirs() }
            val tmp = File(dir, "tmp_${UUID.randomUUID()}.pdf")
            val md = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val r = inp.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        md.update(buf, 0, r)
                        totalBytes += r
                    }
                }
            } ?: return null

            if (totalBytes <= 8L || !hasPdfMagicHeader(tmp)) {
                tmp.delete()
                return null
            }

            val hex = md.digest().take(8).joinToString("") { "%02x".format(it) }
            val target = File(dir, "${hex}_${totalBytes}.pdf")
            if (target.exists() && target.length() == totalBytes) {
                tmp.delete()
            } else {
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            ImportResult(
                path = target.absolutePath,
                title = title,
                fileSizeBytes = totalBytes,
                isLargeFile = totalBytes > 300L * 1024L * 1024L
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getOrCreateThumbnail(context: Context, pdfPath: String): Bitmap? {
        val id = docId(pdfPath)
        thumbCache.get(id)?.let { if (!it.isRecycled) return it }
        val tf = thumbFile(context, pdfPath)
        if (tf.exists() && tf.length() > 0L) {
            try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                BitmapFactory.decodeFile(tf.absolutePath, opts)?.let {
                    thumbCache.put(id, it)
                    return it
                }
            } catch (_: Exception) {}
        }
        val file = File(pdfPath)
        if (!file.exists()) return null
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount <= 0) return null
                    renderer.openPage(0).use { page ->
                        val w = 220
                        val scale = w.toFloat() / page.width.coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceIn(1, 500)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        try {
                            FileOutputStream(tf).use { out -> bmp.compress(webpFormat(), 85, out) }
                        } catch (_: Exception) {}
                        thumbCache.put(id, bmp)
                        bmp
                    }
                }
            }
        } catch (_: Exception) { null }
    }

    fun getOrBuildMeta(context: Context, pdfPath: String): PdfMeta {
        val metaFile = File(optDir(context, pdfPath), "meta.json")
        if (metaFile.exists()) {
            try {
                val obj = JSONObject(metaFile.readText())
                val count = obj.getInt("c").coerceAtLeast(1)
                val arr = obj.getJSONArray("r")
                val ratios = List(count) { i ->
                    if (i < arr.length()) arr.getDouble(i).toFloat() else 1f / 1.414f
                }
                val opt = obj.optBoolean("opt", false)
                return PdfMeta(count, ratios, opt)
            } catch (_: Exception) {}
        }

        val file = File(pdfPath)
        if (!file.exists()) return PdfMeta(1, listOf(1f / 1.414f), false)
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = renderer.pageCount.coerceAtLeast(1)
                    val ratios = List(count) { i ->
                        if (i < renderer.pageCount) {
                            renderer.openPage(i).use { p ->
                                p.width.toFloat() / p.height.coerceAtLeast(1).toFloat()
                            }
                        } else 1f / 1.414f
                    }
                    saveMeta(context, pdfPath, PdfMeta(count, ratios, false))
                    PdfMeta(count, ratios, false)
                }
            }
        } catch (_: Exception) {
            PdfMeta(1, listOf(1f / 1.414f), false)
        }
    }

    private fun saveMeta(context: Context, pdfPath: String, meta: PdfMeta) {
        try {
            val obj = JSONObject().apply {
                put("c", meta.pageCount)
                put("opt", meta.optimized)
                put("r", JSONArray().apply { meta.ratios.forEach { put(it.toDouble()) } })
            }
            File(optDir(context, pdfPath), "meta.json").writeText(obj.toString())
        } catch (_: Exception) {}
    }

    fun renderPageBitmap(context: Context, pdfPath: String, pageIdx: Int, targetWidth: Int = 1200): Bitmap? {
        val key = "${docId(pdfPath)}:$pageIdx:$targetWidth"
        pageCache.get(key)?.let { if (!it.isRecycled) return it }

        val pf = pageFile(context, pdfPath, pageIdx)
        if (pf.exists() && pf.length() > 0L) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val decoded = BitmapFactory.decodeFile(pf.absolutePath, opts)
                if (decoded != null) {
                    pageCache.put(key, decoded)
                    return decoded
                }
            } catch (_: Exception) {}
        }

        val file = File(pdfPath)
        if (!file.exists()) return null
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (pageIdx !in 0 until renderer.pageCount) return null
                    renderer.openPage(pageIdx).use { page ->
                        val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceIn(1, 4096)
                        val bmp = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        try {
                            FileOutputStream(pf).use { out -> bmp.compress(webpFormat(), 90, out) }
                        } catch (_: Exception) {}
                        pageCache.put(key, bmp)
                        bmp
                    }
                }
            }
        } catch (_: Exception) { null }
    }

    fun optimizePdfInBackground(
        context: Context,
        pdfPath: String,
        meta: PdfMeta,
        startPage: Int,
        targetWidth: Int = 1200,
        isCancelled: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        if (meta.optimized) return
        val file = File(pdfPath)
        if (!file.exists()) return
        val total = meta.pageCount
        val order = (0 until total).sortedBy { kotlin.math.abs(it - startPage) }
        var doneCount = 0
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val fmt = webpFormat()
                    var reusableBmp: Bitmap? = null
                    for (idx in order) {
                        if (isCancelled()) break
                        val pf = pageFile(context, pdfPath, idx)
                        if (pf.exists() && pf.length() > 0L) {
                            doneCount++
                            onProgress(doneCount, total)
                            continue
                        }
                        if (idx in 0 until renderer.pageCount) {
                            renderer.openPage(idx).use { page ->
                                val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                                val h = (page.height * scale).toInt().coerceIn(1, 4096)
                                val bmp = if (reusableBmp != null && reusableBmp.width == targetWidth && reusableBmp.height == h) {
                                    reusableBmp
                                } else {
                                    reusableBmp?.recycle()
                                    Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888).also { reusableBmp = it }
                                }
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                FileOutputStream(pf).use { out -> bmp.compress(fmt, 90, out) }
                            }
                        }
                        doneCount++
                        onProgress(doneCount, total)
                    }
                    reusableBmp?.recycle()
                }
            }
            if (!isCancelled() && doneCount >= total) {
                saveMeta(context, pdfPath, meta.copy(optimized = true))
            }
        } catch (_: Exception) {}
    }

    fun deletePdf(context: Context, pdfPath: String) {
        try {
            val id = docId(pdfPath)
            thumbCache.remove(id)
            File(pdfPath).delete()
            File(context.cacheDir, "pdf_opt/$id").deleteRecursively()
            context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
                .edit().remove("lp_$id").apply()
            val list = loadRecents(context).filterNot { it.path == pdfPath }
            writeRecents(context, list)
        } catch (_: Exception) {}
    }

    fun mergePdfs(
        context: Context,
        pdfPaths: List<String>,
        outTitle: String
    ): Pair<Uri, String>? {
        if (pdfPaths.size < 2) return null
        return try {
            val doc = PdfDocument()
            var outNum = 1
            for (path in pdfPaths) {
                val f = File(path)
                if (!f.exists()) continue
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        for (i in 0 until renderer.pageCount) {
                            renderer.openPage(i).use { page ->
                                val w = 1080
                                val scale = w.toFloat() / page.width.coerceAtLeast(1)
                                val h = (page.height * scale).toInt().coerceIn(1, 4096)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val info = PdfDocument.PageInfo.Builder(w, h, outNum++).create()
                                val outPage = doc.startPage(info)
                                outPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                                bmp.recycle()
                                doc.finishPage(outPage)
                            }
                        }
                    }
                }
            }
            if (outNum == 1) {
                doc.close()
                return null
            }
            val safe = outTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Birlesik_PDF" }
            val outName = "$safe.pdf"
            val outUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val u = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore error")
                context.contentResolver.openOutputStream(u)?.use { doc.writeTo(it) }
                u
            } else {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val f = File(dir, outName)
                FileOutputStream(f).use { doc.writeTo(it) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            }
            doc.close()
            outUri to outName
        } catch (_: Exception) {
            null
        }
    }

    fun getLastPage(context: Context, pdfPath: String): Int =
        context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
            .getInt("lp_${docId(pdfPath)}", 0).coerceAtLeast(0)

    fun saveLastPage(context: Context, pdfPath: String, pageIdx: Int) {
        val prefs = context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("lp_${docId(pdfPath)}", pageIdx.coerceAtLeast(0)).apply()
        val list = loadRecents(context).map {
            if (it.path == pdfPath) it.copy(lastPage = pageIdx.coerceAtLeast(0)) else it
        }
        writeRecents(context, list)
    }

    fun saveStrokes(context: Context, pdfPath: String, strokesByPage: Map<Int, List<LiteStroke>>) {
        try {
            val file = File(optDir(context, pdfPath), "strokes.json")
            if (strokesByPage.values.all { it.isEmpty() }) {
                if (file.exists()) file.delete()
                return
            }
            val root = JSONObject()
            strokesByPage.forEach { (pIdx, list) ->
                if (list.isNotEmpty()) {
                    val arr = JSONArray()
                    list.forEach { st ->
                        val o = JSONObject()
                        o.put("c", st.colorArgb)
                        o.put("w", st.width.toDouble())
                        o.put("h", st.isHighlighter)
                        val pts = JSONArray()
                        st.points.forEach { pt ->
                            pts.put(pt.x.toDouble())
                            pts.put(pt.y.toDouble())
                        }
                        o.put("p", pts)
                        arr.put(o)
                    }
                    root.put(pIdx.toString(), arr)
                }
            }
            file.writeText(root.toString())
        } catch (_: Exception) {}
    }

    fun loadStrokes(context: Context, pdfPath: String): Map<Int, List<LiteStroke>> {
        val file = File(optDir(context, pdfPath), "strokes.json")
        if (!file.exists()) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            val out = mutableMapOf<Int, List<LiteStroke>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val pIdx = k.toIntOrNull() ?: continue
                val arr = root.getJSONArray(k)
                val list = mutableListOf<LiteStroke>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ptsArr = o.getJSONArray("p")
                    val pts = ArrayList<PointF>(ptsArr.length() / 2)
                    var j = 0
                    while (j + 1 < ptsArr.length()) {
                        pts.add(PointF(ptsArr.getDouble(j).toFloat(), ptsArr.getDouble(j + 1).toFloat()))
                        j += 2
                    }
                    list.add(
                        LiteStroke(
                            points = pts,
                            colorArgb = o.getInt("c"),
                            width = o.getDouble("w").toFloat(),
                            isHighlighter = o.getBoolean("h")
                        )
                    )
                }
                if (list.isNotEmpty()) out[pIdx] = list
            }
            out
        } catch (_: Exception) { emptyMap() }
    }

    fun searchPdfContent(pdfPath: String, query: String): List<PdfSearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val file = File(pdfPath)
        if (!file.exists()) return emptyList()
        val hits = mutableListOf<PdfSearchHit>()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val searchMethod = PdfRenderer.Page::class.java.methods.firstOrNull {
                        it.name == "searchContent" && it.parameterCount == 1
                    } ?: return emptyList()
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val scale = 1000f / page.width.coerceAtLeast(1).toFloat()
                            @Suppress("UNCHECKED_CAST")
                            val matches = searchMethod.invoke(page, q) as? List<Any> ?: emptyList()
                            matches.forEach { match ->
                                val boundsMethod = match.javaClass.methods.firstOrNull { it.name == "getBounds" }
                                @Suppress("UNCHECKED_CAST")
                                val rects = boundsMethod?.invoke(match) as? List<RectF>
                                val firstRect = rects?.firstOrNull()?.let { r ->
                                    RectF(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale)
                                }
                                hits.add(PdfSearchHit(i, firstRect))
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return hits
    }

    fun splitPdfPages(
        context: Context,
        pdfPath: String,
        baseName: String,
        startPage1: Int,
        endPage1: Int,
        strokesByPage: Map<Int, List<LiteStroke>>
    ): Pair<Uri, String>? {
        return try {
            val file = File(pdfPath)
            if (!file.exists()) return null
            val doc = PdfDocument()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val from = (startPage1 - 1).coerceIn(0, renderer.pageCount - 1)
                    val to = (endPage1 - 1).coerceIn(from, renderer.pageCount - 1)
                    var outNum = 1
                    for (i in from..to) {
                        renderer.openPage(i).use { page ->
                            val w = 1080
                            val scale = w.toFloat() / page.width.coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceIn(1, 4096)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val pageInfo = PdfDocument.PageInfo.Builder(w, h, outNum++).create()
                            val outPage = doc.startPage(pageInfo)
                            outPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                            bmp.recycle()

                            val sScale = w / 1000f
                            outPage.canvas.save()
                            outPage.canvas.scale(sScale, sScale)
                            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                            }
                            strokesByPage[i]?.forEach { st ->
                                if (st.points.size >= 2) {
                                    p.color = st.colorArgb
                                    p.alpha = if (st.isHighlighter) 115 else 255
                                    p.strokeWidth = st.width
                                    val path = Path().apply {
                                        moveTo(st.points[0].x, st.points[0].y)
                                        for (k in 1 until st.points.size) lineTo(st.points[k].x, st.points[k].y)
                                    }
                                    outPage.canvas.drawPath(path, p)
                                }
                            }
                            outPage.canvas.restore()
                            doc.finishPage(outPage)
                        }
                    }
                }
            }

            val safe = baseName.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Belge" }
            val outName = "${safe}_s${startPage1}-${endPage1}.pdf"
            val outUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val u = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore error")
                context.contentResolver.openOutputStream(u)?.use { doc.writeTo(it) }
                u
            } else {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val f = File(dir, outName)
                FileOutputStream(f).use { doc.writeTo(it) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            }
            doc.close()
            outUri to outName
        } catch (_: Exception) {
            null
        }
    }

    fun printPdf(activity: Activity, pdfPath: String, title: String) {
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("${title.ifBlank { "Belge" }}.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    FileInputStream(File(pdfPath)).use { inp ->
                        FileOutputStream(destination.fileDescriptor).use { out ->
                            inp.copyTo(out)
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.localizedMessage)
                }
            }
        }
        printManager.print(title.ifBlank { "PDF Belgesi" }, adapter, null)
    }

    fun getShareUri(context: Context, pdfPath: String): Uri? {
        return try {
            val f = File(pdfPath)
            if (!f.exists()) return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        } catch (_: Exception) { null }
    }

    fun saveRecent(context: Context, item: RecentPdfItem) {
        val existing = loadRecents(context)
        val prev = existing.firstOrNull { it.path == item.path }
        val list = existing.filterNot { it.path == item.path }.toMutableList()
        list.add(
            0,
            item.copy(
                lastPage = prev?.lastPage ?: item.lastPage,
                folder = if (item.folder.isNotEmpty()) item.folder else (prev?.folder ?: ""),
                fileSizeBytes = if (item.fileSizeBytes > 0L) item.fileSizeBytes else (prev?.fileSizeBytes ?: File(item.path).length()),
                isFavorite = prev?.isFavorite ?: item.isFavorite
            )
        )
        writeRecents(context, list.take(50))
    }

    fun renamePdf(context: Context, pdfPath: String, newTitle: String) {
        val clean = newTitle.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        if (clean.isBlank()) return
        val list = loadRecents(context).map {
            if (it.path == pdfPath) it.copy(title = clean) else it
        }
        writeRecents(context, list)
    }

    fun toggleFavorite(context: Context, pdfPath: String) {
        val list = loadRecents(context).map {
            if (it.path == pdfPath) it.copy(isFavorite = !it.isFavorite) else it
        }
        writeRecents(context, list)
    }

    fun setPdfFolder(context: Context, pdfPath: String, folder: String) {
        val list = loadRecents(context).map {
            if (it.path == pdfPath) it.copy(folder = folder) else it
        }
        writeRecents(context, list)
    }

    fun loadFolders(context: Context): List<String> {
        val raw = context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
            .getString("folders", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    fun addFolder(context: Context, name: String): List<String> {
        val clean = name.trim()
        if (clean.isBlank()) return loadFolders(context)
        val cur = loadFolders(context).toMutableList()
        if (clean !in cur) cur.add(clean)
        context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
            .edit().putString("folders", JSONArray(cur).toString()).apply()
        return cur
    }

    fun deleteFolder(context: Context, name: String): List<String> {
        val cur = loadFolders(context).filterNot { it == name }
        context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
            .edit().putString("folders", JSONArray(cur).toString()).apply()
        val updated = loadRecents(context).map { if (it.folder == name) it.copy(folder = "") else it }
        writeRecents(context, updated)
        return cur
    }

    private fun writeRecents(context: Context, items: List<RecentPdfItem>) {
        val arr = JSONArray()
        items.forEach { r ->
            val o = JSONObject()
            o.put("t", r.title)
            o.put("p", r.path)
            o.put("c", r.pageCount)
            o.put("ts", r.openedAtMs)
            o.put("lp", r.lastPage)
            o.put("sz", r.fileSizeBytes)
            o.put("fd", r.folder)
            o.put("fav", r.isFavorite)
            arr.put(o)
        }
        context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
            .edit().putString("recents", arr.toString()).apply()
    }

    fun loadRecents(context: Context): List<RecentPdfItem> {
        val raw = context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
            .getString("recents", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val path = o.getString("p")
                val f = File(path)
                val sz = o.optLong("sz", 0L).let { if (it > 0L) it else if (f.exists()) f.length() else 0L }
                RecentPdfItem(
                    title = o.getString("t"),
                    path = path,
                    pageCount = o.getInt("c"),
                    openedAtMs = o.getLong("ts"),
                    lastPage = o.optInt("lp", 0),
                    fileSizeBytes = sz,
                    folder = o.optString("fd", ""),
                    isFavorite = o.optBoolean("fav", false)
                )
            }.filter { File(it.path).exists() }
        } catch (_: Exception) { emptyList() }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format(java.util.Locale.US, "%.1f MB", mb)
        else String.format(java.util.Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
    }
}
