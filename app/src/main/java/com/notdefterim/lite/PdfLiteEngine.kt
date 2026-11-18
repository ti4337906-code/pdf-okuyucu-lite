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
    val lastPage: Int = 0
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
    // 48-page in-memory LRU cache for instant back-and-forth scrolling
    private val pageCache = LruCache<String, Bitmap>(48)

    private fun docId(pdfPath: String): String = File(pdfPath).nameWithoutExtension

    private fun optDir(context: Context, pdfPath: String): File =
        File(context.cacheDir, "pdf_opt/${docId(pdfPath)}").apply { mkdirs() }

    private fun pagesDir(context: Context, pdfPath: String): File =
        File(optDir(context, pdfPath), "pages").apply { mkdirs() }

    private fun pageFile(context: Context, pdfPath: String, pageIdx: Int): File =
        File(pagesDir(context, pdfPath), "p_$pageIdx.webp")

    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP

    private fun hasPdfMagicHeader(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val head = ByteArray(1024)
                val n = fis.read(head)
                if (n <= 4) return false
                val str = String(head, 0, n, Charsets.ISO_8859_1)
                str.contains("%PDF-")
            }
        } catch (_: Exception) { false }
    }

    /**
     * Scans the PDF byte stream in bounded chunks for active/risky PDF tags
     * (/JavaScript, /JS, /Launch, /OpenAction, /AA, /EmbeddedFile, /RichMedia, /SubmitForm).
     * Note: PdfRenderer renders purely to bitmaps and never executes scripts or actions.
     */
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

    /**
     * Streams URI once while computing SHA-256 fingerprint so opening the same PDF again
     * reuses the existing internal file, its pre-rendered WebP cache, last page, and strokes.
     */
    fun importPdfUri(context: Context, uri: Uri): Pair<String, String>? {
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

            if (totalBytes <= 8L || totalBytes > 300L * 1024L * 1024L || !hasPdfMagicHeader(tmp)) {
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
            target.absolutePath to title
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Loads cached pageCount + aspect ratios in <1ms on subsequent opens;
     * falls back to a single PdfRenderer pass on first open and persists meta.json.
     */
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

    /**
     * Fast-path bitmap loader:
     * 1) In-memory LruCache (0ms)
     * 2) Pre-optimized WebP on disk decoded as RGB_565 (50% less RAM, ~4ms)
     * 3) Live PdfRenderer fallback (and saves WebP for next open)
     */
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

    /**
     * Full first-open background optimization:
     * Renders every page into a WebP disk cache in a single PdfRenderer session,
     * starting from the user's current page outward so subsequent opens are instant.
     */
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
        // Order pages outward from startPage
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

    fun getLastPage(context: Context, pdfPath: String): Int =
        context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
            .getInt("lp_${docId(pdfPath)}", 0).coerceAtLeast(0)

    fun saveLastPage(context: Context, pdfPath: String, pageIdx: Int) {
        val prefs = context.getSharedPreferences("lite_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("lp_${docId(pdfPath)}", pageIdx.coerceAtLeast(0)).apply()
        // Keep recents list synced with lastPage
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

    /**
     * Uses Android 15 (Honor 400 MagicOS 9) native PdfRenderer.Page.searchContent via safe reflection
     * so it compiles on any minSdk while giving native PDFium search speed on Android 15+.
     */
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

    /**
     * Splits pages [startPage1..endPage1] (1-based) into a standalone PDF saved in Downloads.
     */
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
        val prevLast = existing.firstOrNull { it.path == item.path }?.lastPage ?: item.lastPage
        val list = existing.filterNot { it.path == item.path }.toMutableList()
        list.add(0, item.copy(lastPage = prevLast))
        writeRecents(context, list.take(12))
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
                RecentPdfItem(
                    title = o.getString("t"),
                    path = o.getString("p"),
                    pageCount = o.getInt("c"),
                    openedAtMs = o.getLong("ts"),
                    lastPage = o.optInt("lp", 0)
                )
            }.filter { File(it.path).exists() }
        } catch (_: Exception) { emptyList() }
    }
}
