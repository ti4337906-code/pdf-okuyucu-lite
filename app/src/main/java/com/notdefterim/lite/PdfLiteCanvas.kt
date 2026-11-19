package com.notdefterim.lite

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.*

class PdfReaderView(context: Context) : View(context) {
    var pageCount: Int = 0
        set(value) { if (field != value) { field = value; rebuildMetrics(); invalidate() } }
    var pageRatios: List<Float> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                rebuildMetrics()
                clampPan()
                invalidate()
            }
        }
    var pageBitmaps: List<Bitmap?> = emptyList()
        set(value) { field = value; invalidate() }
    var strokesByPage: Map<Int, List<LiteStroke>> = emptyMap()
        set(value) { field = value; invalidate() }

    var singlePageMode: Boolean = false
        set(value) {
            if (field != value) {
                scroller.forceFinished(true)
                field = value
                panX = 0f
                panY = if (!value && currentSinglePage in cachedTops.indices) {
                    -(cachedTops[currentSinglePage] * totalScale())
                } else {
                    0f
                }
                clampPan()
                invalidate()
            }
        }
    var currentSinglePage: Int = 0
        set(value) {
            if (field != value) {
                field = value
                if (singlePageMode) {
                    scroller.forceFinished(true)
                    panX = 0f
                    panY = 0f
                    clampPan()
                    invalidate()
                }
            }
        }

    var darkInvert: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    // Finger-first: drawEnabled = false means 1-finger scrolls smoothly; true = 1-finger marks up
    var drawEnabled: Boolean = false
    var isHighlighterTool: Boolean = true
    var isEraserTool: Boolean = false
    var activeColorArgb: Int = 0xFFFFD60A.toInt()
    var activeStrokeWidth: Float = 26f

    var highlightPageIdx: Int = -1
    var highlightRect: RectF? = null

    var onStrokeAdded: ((Int, LiteStroke) -> Unit)? = null
    var onStrokeErased: ((Int, List<LiteStroke>) -> Unit)? = null
    var onActivePageChanged: ((Int) -> Unit)? = null
    var onScrollFractionChanged: ((Float) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null

    private var zoomFactor = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false
    private var isFingerScrolling = false

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val pageGapV = 22f

    private var cachedHeights = FloatArray(0)
    private var cachedTops = FloatArray(0)
    private var cachedTotalH = 1414f

    private val activePoints = mutableListOf<PointF>()
    private val activePath = Path()
    private var activeDrawPage = 0

    // Ergonomic Night Mode: matte charcoal (#11110F) background + warm off-white (#E0DACC) text
    // Reduces blue-light glare and prevents white-on-black halation during long night reading
    private val invertPaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            floatArrayOf(
                -0.81f, 0f, 0f, 0f, 224f,
                0f, -0.79f, 0f, 0f, 218f,
                0f, 0f, -0.74f, 0f, 204f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4F4F5.toInt() }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val searchFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FF9F0A }
    private val searchBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9F0A.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val pageNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF71717A.toInt(); textSize = 24f }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prev = zoomFactor
            val next = (zoomFactor * detector.scaleFactor).coerceIn(0.8f, 4.5f)
            if (next != prev) {
                val r = next / prev
                panX = detector.focusX - (detector.focusX - panX) * r
                panY = detector.focusY - (detector.focusY - panY) * r
                zoomFactor = next
                clampPan()
                onZoomChanged?.invoke(zoomFactor)
                invalidate()
            }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!drawEnabled) {
                onSingleTap?.invoke()
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (drawEnabled) return false
            val target = if (zoomFactor > 1.3f) 1f else 2.2f
            val r = target / zoomFactor
            panX = e.x - (e.x - panX) * r
            panY = e.y - (e.y - panY) * r
            zoomFactor = target
            if (target == 1f) panX = 0f
            clampPan()
            onZoomChanged?.invoke(zoomFactor)
            invalidate()
            return true
        }
    })

    private fun rebuildMetrics() {
        val n = pageCount
        val hArr = FloatArray(n)
        val tArr = FloatArray(n)
        var y = 12f
        for (i in 0 until n) {
            tArr[i] = y
            val ratio = pageRatios.getOrElse(i) { 1f / 1.414f }.coerceIn(0.3f, 3f)
            val h = 1000f / ratio
            hArr[i] = h
            y += h + pageGapV
        }
        cachedHeights = hArr
        cachedTops = tArr
        cachedTotalH = if (n == 0) 1414f else y - pageGapV + 60f
    }

    private fun baseScale(): Float = if (width > 0) (width - 20f).coerceAtLeast(100f) / 1000f else 1f
    private fun totalScale(): Float = baseScale() * zoomFactor
    private fun leftMargin(): Float = 10f

    private fun minPanX(): Float {
        val s = totalScale()
        val docW = 1000f * s + leftMargin() * 2f * zoomFactor
        return (width - docW).coerceAtMost(0f)
    }

    private fun minPanY(): Float {
        val s = totalScale()
        val h = if (singlePageMode && currentSinglePage in cachedHeights.indices) {
            cachedHeights[currentSinglePage] + 40f
        } else {
            cachedTotalH
        }
        return (height - h * s).coerceAtMost(0f)
    }

    private fun clampPan() {
        if (width <= 0 || height <= 0) return
        val s = totalScale()
        val contentW = 1000f * s
        panX = if (contentW <= width - 20f) (width - contentW) / 2f - leftMargin() else panX.coerceIn(minPanX(), 0f)
        panY = panY.coerceIn(minPanY(), 0f)
    }

    private fun notifyScroll() {
        val minY = minPanY()
        val frac = if (minY < -1f) (panY / minY).coerceIn(0f, 1f) else 0f
        onScrollFractionChanged?.invoke(frac)
        if (!singlePageMode && cachedTops.isNotEmpty()) {
            val docY = screenToDocY(height * 0.35f)
            val (p, _) = findPageAtDocY(docY)
            onActivePageChanged?.invoke(p)
        }
    }

    fun setScrollFraction(frac: Float) {
        scroller.forceFinished(true)
        panY = minPanY() * frac.coerceIn(0f, 1f)
        clampPan()
        notifyScroll()
        invalidate()
    }

    fun jumpToPage(pageIdx: Int, rect: RectF?) {
        scroller.forceFinished(true)
        highlightPageIdx = pageIdx
        highlightRect = rect
        if (singlePageMode) {
            currentSinglePage = pageIdx.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            panY = 0f
        } else if (pageIdx in cachedTops.indices) {
            val docY = cachedTops[pageIdx] + (rect?.centerY() ?: 0f)
            panY = -(docY * totalScale() - height * 0.3f)
        }
        clampPan()
        onActivePageChanged?.invoke(pageIdx)
        val minY = minPanY()
        val frac = if (minY < -1f) (panY / minY).coerceIn(0f, 1f) else 0f
        onScrollFractionChanged?.invoke(frac)
        invalidate()
    }

    fun resetZoom() {
        scroller.forceFinished(true)
        zoomFactor = 1f; panX = 0f; clampPan()
        onZoomChanged?.invoke(1f); invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            clampPan()
            notifyScroll()
            postInvalidateOnAnimation()
        }
    }

    private fun screenToDocX(sx: Float): Float = (sx - leftMargin() - panX) / totalScale()
    private fun screenToDocY(sy: Float): Float = (sy - panY) / totalScale()

    private fun findPageAtDocY(docY: Float): Pair<Int, Float> {
        if (singlePageMode) {
            val h = cachedHeights.getOrElse(currentSinglePage) { 1414f }
            return currentSinglePage to (docY - 12f).coerceIn(0f, h)
        }
        val n = cachedHeights.size
        if (n == 0) return 0 to docY.coerceAtLeast(0f)
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cachedTops[mid] <= docY + pageGapV * 0.5f) lo = mid else hi = mid - 1
        }
        return lo to (docY - cachedTops[lo]).coerceIn(0f, cachedHeights[lo])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.pointerCount >= 2) {
            scroller.forceFinished(true)
            velocityTracker?.recycle(); velocityTracker = null
            activePoints.clear(); activePath.reset()
            isFingerScrolling = false
            scaleDetector.onTouchEvent(event)
            val cx = (event.getX(0) + event.getX(1)) / 2f
            val cy = (event.getY(0) + event.getY(1)) / 2f
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> { isPanning = true; lastPanX = cx; lastPanY = cy }
                MotionEvent.ACTION_MOVE -> if (isPanning) {
                    panX += cx - lastPanX; panY += cy - lastPanY
                    lastPanX = cx; lastPanY = cy
                    clampPan(); notifyScroll(); invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> isPanning = false
            }
            return true
        }
        isPanning = false

        val action = event.actionMasked
        if (!drawEnabled) {
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
            velocityTracker?.addMovement(event)
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    scroller.forceFinished(true)
                    isFingerScrolling = true
                    lastPanX = event.x; lastPanY = event.y
                }
                MotionEvent.ACTION_MOVE -> if (isFingerScrolling) {
                    if (zoomFactor > 1.01f) panX += event.x - lastPanX
                    panY += event.y - lastPanY
                    lastPanX = event.x; lastPanY = event.y
                    clampPan(); notifyScroll(); invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    isFingerScrolling = false
                    velocityTracker?.let { vt ->
                        vt.computeCurrentVelocity(1000, 14000f)
                        val vx = if (zoomFactor > 1.01f) vt.xVelocity.toInt() else 0
                        val vy = vt.yVelocity.toInt()
                        if (abs(vy) > 120 || abs(vx) > 120) {
                            scroller.fling(panX.toInt(), panY.toInt(), vx, vy, minPanX().toInt(), 0, minPanY().toInt(), 0)
                            postInvalidateOnAnimation()
                        }
                        vt.recycle()
                    }
                    velocityTracker = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    isFingerScrolling = false
                    velocityTracker?.recycle(); velocityTracker = null
                }
            }
            return true
        }

        // Finger markup mode (drawEnabled == true)
        val docX = screenToDocX(event.x)
        val docY = screenToDocY(event.y)
        val (hitPage, localY) = findPageAtDocY(docY)

        if (isEraserTool) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                val cur = strokesByPage[hitPage].orEmpty()
                val rem = cur.filterNot { st -> st.points.any { hypot(it.x - docX, it.y - localY) <= 32f } }
                if (rem.size != cur.size) onStrokeErased?.invoke(hitPage, rem)
            }
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activeDrawPage = hitPage
                activePoints.clear()
                activePoints.add(PointF(docX, localY))
                activePath.reset()
                activePath.moveTo(docX, localY)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePoints.isEmpty()) return true
                val topY = if (singlePageMode) 12f else cachedTops.getOrElse(activeDrawPage) { 12f }
                val maxH = cachedHeights.getOrElse(activeDrawPage) { 1414f }
                val cx = docX
                val cy = (docY - topY).coerceIn(0f, maxH)
                val last = activePoints.lastOrNull()
                if (last == null || hypot(cx - last.x, cy - last.y) >= 1.5f) {
                    activePoints.add(PointF(cx, cy))
                    activePath.lineTo(cx, cy)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (activePoints.size >= 2) {
                    onStrokeAdded?.invoke(
                        activeDrawPage,
                        LiteStroke(activePoints.toList(), activeColorArgb, activeStrokeWidth, isHighlighterTool)
                    )
                }
                activePoints.clear(); activePath.reset(); invalidate()
            }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = totalScale()
        val visTop = screenToDocY(0f) - 40f
        val visBot = screenToDocY(height.toFloat()) + 40f

        canvas.save()
        canvas.translate(leftMargin() + panX, panY)
        canvas.scale(s, s)

        val indices = if (singlePageMode && currentSinglePage in 0 until pageCount) {
            listOf(currentSinglePage)
        } else {
            (0 until pageCount).toList()
        }

        for (i in indices) {
            val yOff = if (singlePageMode) 12f else cachedTops.getOrElse(i) { 12f }
            val pageH = cachedHeights.getOrElse(i) { 1414f }
            if (singlePageMode || (yOff + pageH >= visTop && yOff <= visBot)) {
                canvas.save()
                canvas.translate(0f, yOff)
                canvas.clipRect(0f, 0f, 1000f, pageH)

                val bmp = pageBitmaps.getOrNull(i)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, RectF(0f, 0f, 1000f, pageH), if (darkInvert) invertPaint else null)
                } else {
                    canvas.drawRoundRect(RectF(0f, 0f, 1000f, pageH), 10f, 10f, paperPaint)
                }

                // Highlighters & Pen strokes
                strokesByPage[i]?.forEach { st ->
                    if (st.points.size >= 2) {
                        strokePaint.color = st.colorArgb
                        strokePaint.alpha = if (st.isHighlighter) 110 else 255
                        strokePaint.strokeWidth = st.width
                        val p = Path().apply {
                            moveTo(st.points[0].x, st.points[0].y)
                            for (k in 1 until st.points.size) lineTo(st.points[k].x, st.points[k].y)
                        }
                        canvas.drawPath(p, strokePaint)
                    }
                }

                if (i == activeDrawPage && activePoints.size >= 2) {
                    strokePaint.color = activeColorArgb
                    strokePaint.alpha = if (isHighlighterTool) 110 else 255
                    strokePaint.strokeWidth = activeStrokeWidth
                    canvas.drawPath(activePath, strokePaint)
                }

                if (i == highlightPageIdx) {
                    highlightRect?.let { hr ->
                        val r = RectF(hr.left - 8f, hr.top - 8f, hr.right + 8f, hr.bottom + 8f)
                        canvas.drawRoundRect(r, 6f, 6f, searchFillPaint)
                        canvas.drawRoundRect(r, 6f, 6f, searchBorderPaint)
                    }
                }

                canvas.drawText("${i + 1}", 952f, pageH - 16f, pageNumPaint)
                canvas.restore()
            }
        }
        canvas.restore()
    }
}

@Composable
fun PdfReaderCanvas(
    pageCount: Int,
    pageRatios: List<Float>,
    pageBitmaps: List<Bitmap?>,
    strokesByPage: Map<Int, List<LiteStroke>>,
    singlePageMode: Boolean,
    currentSinglePage: Int,
    darkInvert: Boolean,
    drawEnabled: Boolean,
    isHighlighterTool: Boolean,
    isEraserTool: Boolean,
    activeColorArgb: Int,
    activeStrokeWidth: Float,
    jumpTrigger: Int,
    jumpTargetPage: Int,
    jumpTargetRect: RectF?,
    scrollTrigger: Int,
    targetScrollFraction: Float,
    resetZoomTrigger: Int,
    onStrokeAdded: (Int, LiteStroke) -> Unit,
    onStrokeErased: (Int, List<LiteStroke>) -> Unit,
    onActivePageChanged: (Int) -> Unit,
    onScrollFractionChanged: (Float) -> Unit,
    onZoomChanged: (Float) -> Unit,
    onSingleTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PdfReaderView(ctx).apply {
                this.onStrokeAdded = onStrokeAdded
                this.onStrokeErased = onStrokeErased
                this.onActivePageChanged = onActivePageChanged
                this.onScrollFractionChanged = onScrollFractionChanged
                this.onZoomChanged = onZoomChanged
                this.onSingleTap = onSingleTap
            }
        },
        update = { view ->
            view.pageCount = pageCount
            view.pageRatios = pageRatios
            view.pageBitmaps = pageBitmaps
            view.strokesByPage = strokesByPage
            view.singlePageMode = singlePageMode
            view.currentSinglePage = currentSinglePage
            view.darkInvert = darkInvert
            view.drawEnabled = drawEnabled
            view.isHighlighterTool = isHighlighterTool
            view.isEraserTool = isEraserTool
            view.activeColorArgb = activeColorArgb
            view.activeStrokeWidth = activeStrokeWidth
            view.onStrokeAdded = onStrokeAdded
            view.onStrokeErased = onStrokeErased
            view.onActivePageChanged = onActivePageChanged
            view.onScrollFractionChanged = onScrollFractionChanged
            view.onZoomChanged = onZoomChanged
            view.onSingleTap = onSingleTap

            val tags = (view.tag as? IntArray) ?: intArrayOf(0, 0, 0)
            if (jumpTrigger > 0 && tags[0] != jumpTrigger) view.jumpToPage(jumpTargetPage, jumpTargetRect)
            if (scrollTrigger > 0 && tags[1] != scrollTrigger) view.setScrollFraction(targetScrollFraction)
            if (resetZoomTrigger > 0 && tags[2] != resetZoomTrigger) view.resetZoom()
            view.tag = intArrayOf(jumpTrigger, scrollTrigger, resetZoomTrigger)
        }
    )
}
