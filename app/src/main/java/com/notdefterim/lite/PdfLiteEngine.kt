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
import java.util.UUID

data class RecentPdfItem(
    val title: String,
    val path: String,
    val pageCount: Int,
    val openedAtMs: Long
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

object PdfLiteEngine {
    private val pageCache = LruCache<String, Bitmap>(32)

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
        return (name ?: "Belge").substringBeforeLast('.').ifBlank { "Belge" }
    }

    fun importPdfUri(context: Context, uri: Uri): Pair<String, String>? {
        return try {
            val title = getDisplayName(context, uri)
            val dir = File(context.filesDir, "lite_pdfs").apply { mkdirs() }
            val outFile = File(dir, "${UUID.randomUUID()}.pdf")
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(outFile).use { out -> inp.copyTo(out) }
            } ?: return null
            outFile.absolutePath to title
        } catch (_: Exception) {
            null
        }
    }

    fun getPageCount(pdfPath: String): Int {
        val file = File(pdfPath)
        if (!file.exists()) return 0
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
        } catch (_: Exception) { 0 }
    }

    fun getPageAspectRatio(pdfPath: String, pageIdx: Int): Float {
        val file = File(pdfPath)
        if (!file.exists()) return 1f / 1.414f
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (pageIdx !in 0 until renderer.pageCount) return 1f / 1.414f
                    renderer.openPage(pageIdx).use { p ->
                        p.width.toFloat() / p.height.coerceAtLeast(1).toFloat()
                    }
                }
            }
        } catch (_: Exception) { 1f / 1.414f }
    }

    fun renderPageBitmap(pdfPath: String, pageIdx: Int, targetWidth: Int = 1200): Bitmap? {
        val key = "$pdfPath:$pageIdx:$targetWidth"
        pageCache.get(key)?.let { if (!it.isRecycled) return it }
        val file = File(pdfPath)
        if (!file.exists()) return null
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (pageIdx !in 0 until renderer.pageCount) return null
                    renderer.openPage(pageIdx).use { page ->
                        val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pageCache.put(key, bmp)
                        bmp
                    }
                }
            }
        } catch (_: Exception) { null }
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
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
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
        val list = loadRecents(context).filterNot { it.path == item.path }.toMutableList()
        list.add(0, item)
        val trimmed = list.take(12)
        val arr = JSONArray()
        trimmed.forEach { r ->
            val o = JSONObject()
            o.put("t", r.title)
            o.put("p", r.path)
            o.put("c", r.pageCount)
            o.put("ts", r.openedAtMs)
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
                    openedAtMs = o.getLong("ts")
                )
            }.filter { File(it.path).exists() }
        } catch (_: Exception) { emptyList() }
    }
}
