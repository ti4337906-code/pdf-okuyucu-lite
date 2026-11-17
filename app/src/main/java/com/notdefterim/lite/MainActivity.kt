package com.notdefterim.lite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val LITE_BG = Color(0xFF09090B)
private val LITE_CARD = Color(0xFF18181B)
private val LITE_BORDER = Color(0xFF27272A)
private val PDF_RED = Color(0xFFDC2626)
private val HONOR_BLUE = Color(0xFF3B82F6)

class MainActivity : ComponentActivity() {
    private var incomingPdfUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lock to highest refresh rate (120Hz on Honor 400)
        try {
            val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val best = disp?.supportedModes?.maxByOrNull { it.refreshRate }
            if (best != null) {
                val attrs = window.attributes
                attrs.preferredDisplayModeId = best.modeId
                window.attributes = attrs
            }
        } catch (_: Exception) {}

        if (intent?.action == Intent.ACTION_VIEW) {
            incomingPdfUri = intent.data
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = PDF_RED,
                    background = LITE_BG,
                    surface = LITE_CARD
                )
            ) {
                LiteAppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            incomingPdfUri = intent.data
        }
    }

    @Composable
    private fun LiteAppRoot() {
        val scope = rememberCoroutineScope()
        var openPdf by remember { mutableStateOf<Pair<String, String>?>(null) } // path to title
        var recents by remember { mutableStateOf(PdfLiteEngine.loadRecents(this)) }

        LaunchedEffect(incomingPdfUri) {
            val uri = incomingPdfUri ?: return@LaunchedEffect
            val imported = withContext(Dispatchers.IO) { PdfLiteEngine.importPdfUri(this@MainActivity, uri) }
            if (imported != null) {
                val count = withContext(Dispatchers.IO) { PdfLiteEngine.getPageCount(imported.first) }
                withContext(Dispatchers.IO) {
                    PdfLiteEngine.saveRecent(
                        this@MainActivity,
                        RecentPdfItem(imported.second, imported.first, count, System.currentTimeMillis())
                    )
                }
                recents = PdfLiteEngine.loadRecents(this@MainActivity)
                openPdf = imported
            } else {
                Toast.makeText(this@MainActivity, "PDF açılamadı", Toast.LENGTH_SHORT).show()
            }
            incomingPdfUri = null
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val imported = withContext(Dispatchers.IO) { PdfLiteEngine.importPdfUri(this@MainActivity, uri) }
                if (imported != null) {
                    val count = withContext(Dispatchers.IO) { PdfLiteEngine.getPageCount(imported.first) }
                    withContext(Dispatchers.IO) {
                        PdfLiteEngine.saveRecent(
                            this@MainActivity,
                            RecentPdfItem(imported.second, imported.first, count, System.currentTimeMillis())
                        )
                    }
                    recents = PdfLiteEngine.loadRecents(this@MainActivity)
                    openPdf = imported
                }
            }
        }

        val active = openPdf
        if (active == null) {
            HomeScreen(
                recents = recents,
                onPickPdf = { picker.launch(arrayOf("application/pdf")) },
                onOpenRecent = { item -> openPdf = item.path to item.title }
            )
        } else {
            ReaderScreen(
                pdfPath = active.first,
                title = active.second,
                onBack = { openPdf = null }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen(
        recents: List<RecentPdfItem>,
        onPickPdf: () -> Unit,
        onOpenRecent: (RecentPdfItem) -> Unit
    ) {
        Scaffold(
            containerColor = LITE_BG,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = LITE_BG, titleContentColor = Color.White),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(shape = RoundedCornerShape(8.dp), color = PDF_RED, modifier = Modifier.size(30.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("PDF", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                }
                            }
                            Text("PDF Okuyucu Lite", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onPickPdf,
                    containerColor = PDF_RED,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    text = { Text("PDF Aç", fontWeight = FontWeight.SemiBold) }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Son Açılan PDF'ler", fontSize = 13.sp, color = Color(0xFFA1A1AA), modifier = Modifier.padding(top = 6.dp))
                if (recents.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = LITE_CARD,
                        border = BorderStroke(1.dp, LITE_BORDER),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Henüz PDF açılmadı", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Sağ alttan PDF seçebilir veya dosya yöneticisinden herhangi bir PDF'e basıp varsayılan olarak bu uygulamayı seçebilirsin.",
                                fontSize = 12.sp,
                                color = Color(0xFFA1A1AA)
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recents) { item ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = LITE_CARD,
                                border = BorderStroke(0.8.dp, LITE_BORDER),
                                modifier = Modifier.fillMaxWidth().clickable { onOpenRecent(item) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(14.dp)
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = PDF_RED, modifier = Modifier.size(28.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                                        Text("${item.pageCount} sayfa", color = Color(0xFFA1A1AA), fontSize = 11.sp)
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF71717A))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReaderScreen(
        pdfPath: String,
        title: String,
        onBack: () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        var pageCount by remember(pdfPath) { mutableIntStateOf(1) }
        var pageRatios by remember(pdfPath) { mutableStateOf<List<Float>>(emptyList()) }
        var pageBitmaps by remember(pdfPath) { mutableStateOf<List<Bitmap?>>(emptyList()) }
        var strokesByPage by remember(pdfPath) { mutableStateOf<Map<Int, List<LiteStroke>>>(emptyMap()) }

        var activePageIdx by remember(pdfPath) { mutableIntStateOf(0) }
        var singlePageMode by remember { mutableStateOf(false) }
        var darkInvert by remember { mutableStateOf(false) }
        var drawEnabled by remember { mutableStateOf(false) }
        var isHighlighterTool by remember { mutableStateOf(true) }
        var isEraserTool by remember { mutableStateOf(false) }
        var activeColor by remember { mutableStateOf(Color(0xFFFFD60A)) }
        var zoomLevel by remember { mutableFloatStateOf(1f) }

        var showSearchBar by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var searchHits by remember { mutableStateOf<List<PdfSearchHit>>(emptyList()) }
        var searchHitIdx by remember { mutableIntStateOf(0) }

        var showMoreMenu by remember { mutableStateOf(false) }
        var showLayoutMenu by remember { mutableStateOf(false) }
        var showSplitDialog by remember { mutableStateOf(false) }
        var splitStartStr by remember { mutableStateOf("1") }
        var splitEndStr by remember { mutableStateOf("1") }

        var jumpTrigger by remember { mutableIntStateOf(0) }
        var jumpTargetPage by remember { mutableIntStateOf(0) }
        var jumpTargetRect by remember { mutableStateOf<RectF?>(null) }
        var scrollTrigger by remember { mutableIntStateOf(0) }
        var targetScrollFraction by remember { mutableFloatStateOf(0f) }
        var scrollFraction by remember { mutableFloatStateOf(0f) }
        var isDraggingScrollbar by remember { mutableStateOf(false) }
        var resetZoomTrigger by remember { mutableIntStateOf(0) }

        LaunchedEffect(pdfPath) {
            val count = withContext(Dispatchers.IO) { PdfLiteEngine.getPageCount(pdfPath).coerceAtLeast(1) }
            pageCount = count
            splitEndStr = count.toString()
            val bmps = MutableList<Bitmap?>(count) { null }
            val ratios = MutableList(count) { 1f / 1.414f }
            for (i in 0 until count) {
                val (b, r) = withContext(Dispatchers.IO) {
                    val bmp = PdfLiteEngine.renderPageBitmap(pdfPath, i, 1200)
                    val rt = if (bmp != null) bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
                    else PdfLiteEngine.getPageAspectRatio(pdfPath, i)
                    bmp to rt
                }
                bmps[i] = b
                ratios[i] = r
                if (i == 0 || i == count - 1 || i % 3 == 0) {
                    pageBitmaps = bmps.toList()
                    pageRatios = ratios.toList()
                }
            }
        }

        // Priority preload around activePageIdx on fast jumps
        LaunchedEffect(activePageIdx, pageCount) {
            if (pageBitmaps.isEmpty()) return@LaunchedEffect
            val s = (activePageIdx - 1).coerceAtLeast(0)
            val e = (activePageIdx + 2).coerceAtMost(pageCount - 1)
            var changed = false
            val bmps = pageBitmaps.toMutableList()
            val ratios = pageRatios.toMutableList()
            for (i in s..e) {
                if (i in bmps.indices && bmps[i] == null) {
                    val (b, r) = withContext(Dispatchers.IO) {
                        val bmp = PdfLiteEngine.renderPageBitmap(pdfPath, i, 1200)
                        val rt = if (bmp != null) bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
                        else PdfLiteEngine.getPageAspectRatio(pdfPath, i)
                        bmp to rt
                    }
                    bmps[i] = b
                    ratios[i] = r
                    changed = true
                }
            }
            if (changed) {
                pageBitmaps = bmps
                pageRatios = ratios
            }
        }

        if (showSplitDialog) {
            AlertDialog(
                onDismissRequest = { showSplitDialog = false },
                containerColor = LITE_CARD,
                title = { Text("PDF Sayfa Böl (1–$pageCount)", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Ayırmak istediğin sayfa aralığını gir; İndirilenler klasörüne yeni PDF olarak kaydedilir.", fontSize = 12.sp, color = Color(0xFFA1A1AA))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = splitStartStr,
                                onValueChange = { splitStartStr = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Başlangıç") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = splitEndStr,
                                onValueChange = { splitEndStr = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Bitiş") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val s = splitStartStr.toIntOrNull() ?: 1
                        val e = splitEndStr.toIntOrNull() ?: pageCount
                        showSplitDialog = false
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                PdfLiteEngine.splitPdfPages(this@MainActivity, pdfPath, title, s, e, strokesByPage)
                            }
                            if (res != null) {
                                Toast.makeText(this@MainActivity, "'${res.second}' İndirilenlere kaydedildi!", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@MainActivity, "PDF bölünemedi", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("Böl & Kaydet", color = PDF_RED, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showSplitDialog = false }) { Text("İptal", color = Color(0xFFA1A1AA)) }
                }
            )
        }

        Scaffold(
            containerColor = LITE_BG,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = LITE_BG, titleContentColor = Color.White),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = Color.White)
                        }
                    },
                    title = {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    },
                    actions = {
                        IconButton(onClick = {
                            showSearchBar = !showSearchBar
                            if (!showSearchBar) {
                                jumpTargetPage = -1
                                jumpTargetRect = null
                            }
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Bul", tint = if (showSearchBar) PDF_RED else Color.White)
                        }
                        Box {
                            IconButton(onClick = { showLayoutMenu = true }) {
                                Icon(Icons.Default.ViewAgenda, contentDescription = "Sayfa Düzeni", tint = Color.White)
                            }
                            DropdownMenu(expanded = showLayoutMenu, onDismissRequest = { showLayoutMenu = false }, modifier = Modifier.background(LITE_CARD)) {
                                DropdownMenuItem(
                                    text = { Text(if (singlePageMode) "Sürekli Kaydırma Modu" else "Tek Sayfa Modu", color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.AutoStories, contentDescription = null, tint = HONOR_BLUE) },
                                    onClick = { singlePageMode = !singlePageMode; showLayoutMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (darkInvert) "Gece Modu: Açık" else "Gece Modu: Kapalı", color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null, tint = Color(0xFFFFD60A)) },
                                    onClick = { darkInvert = !darkInvert; showLayoutMenu = false }
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menü", tint = Color.White)
                            }
                            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }, modifier = Modifier.background(LITE_CARD)) {
                                DropdownMenuItem(
                                    text = { Text("Sayfalara Böl (Ayır)", color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null, tint = PDF_RED) },
                                    onClick = { showMoreMenu = false; showSplitDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Yazdır", color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.Print, contentDescription = null, tint = Color.White) },
                                    onClick = { showMoreMenu = false; PdfLiteEngine.printPdf(this@MainActivity, pdfPath, title) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Paylaş", color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = HONOR_BLUE) },
                                    onClick = {
                                        showMoreMenu = false
                                        PdfLiteEngine.getShareUri(this@MainActivity, pdfPath)?.let { u ->
                                            val send = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/pdf"
                                                putExtra(Intent.EXTRA_STREAM, u)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            startActivity(Intent.createChooser(send, "PDF Paylaş"))
                                        }
                                    }
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                Surface(
                    color = LITE_CARD,
                    border = BorderStroke(0.8.dp, LITE_BORDER),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (drawEnabled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            ) {
                                FilterChip(
                                    selected = !isHighlighterTool && !isEraserTool,
                                    onClick = { isHighlighterTool = false; isEraserTool = false; activeColor = Color(0xFFDC2626) },
                                    label = { Text("Kalem", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = isHighlighterTool && !isEraserTool,
                                    onClick = { isHighlighterTool = true; isEraserTool = false; activeColor = Color(0xFFFFD60A) },
                                    label = { Text("Fosforlu", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = isEraserTool,
                                    onClick = { isEraserTool = true },
                                    label = { Text("Silgi", fontSize = 11.sp) }
                                )
                                listOf(Color(0xFFFFD60A), Color(0xFFDC2626), Color(0xFF3B82F6), Color(0xFF22C55E), Color(0xFF0F172A)).forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .border(2.dp, if (activeColor == c && !isEraserTool) Color.White else LITE_BORDER, CircleShape)
                                            .clickable { activeColor = c; isEraserTool = false }
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val cur = strokesByPage[activePageIdx].orEmpty()
                                        if (cur.isNotEmpty()) strokesByPage = strokesByPage + (activePageIdx to cur.dropLast(1))
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Geri Al", tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (singlePageMode) {
                                    IconButton(
                                        enabled = activePageIdx > 0,
                                        onClick = { jumpTargetPage = (activePageIdx - 1).coerceAtLeast(0); jumpTargetRect = null; jumpTrigger++ },
                                        modifier = Modifier.size(30.dp)
                                    ) { Icon(Icons.Default.ChevronLeft, contentDescription = "Önceki", tint = Color.White) }
                                }
                                Text("${activePageIdx + 1} / $pageCount", fontSize = 12.sp, color = Color(0xFFA1A1AA), fontWeight = FontWeight.Medium)
                                if (singlePageMode) {
                                    IconButton(
                                        enabled = activePageIdx < pageCount - 1,
                                        onClick = { jumpTargetPage = (activePageIdx + 1).coerceAtMost(pageCount - 1); jumpTargetRect = null; jumpTrigger++ },
                                        modifier = Modifier.size(30.dp)
                                    ) { Icon(Icons.Default.ChevronRight, contentDescription = "Sonraki", tint = Color.White) }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (zoomLevel < 0.95f || zoomLevel > 1.05f) {
                                    AssistChip(onClick = { resetZoomTrigger++ }, label = { Text("${(zoomLevel * 100).toInt()}% ✕", fontSize = 11.sp, color = Color.White) })
                                }
                                FilterChip(
                                    selected = drawEnabled,
                                    onClick = { drawEnabled = !drawEnabled },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp)) },
                                    label = { Text(if (drawEnabled) "İşaretleme Açık" else "İşaretle", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = LITE_BG,
                                        labelColor = Color.White,
                                        selectedContainerColor = PDF_RED,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                PdfReaderCanvas(
                    pageCount = pageCount,
                    pageRatios = pageRatios,
                    pageBitmaps = pageBitmaps,
                    strokesByPage = strokesByPage,
                    singlePageMode = singlePageMode,
                    currentSinglePage = activePageIdx,
                    darkInvert = darkInvert,
                    drawEnabled = drawEnabled,
                    isHighlighterTool = isHighlighterTool,
                    isEraserTool = isEraserTool,
                    activeColorArgb = activeColor.toArgb(),
                    activeStrokeWidth = if (isHighlighterTool) 28f else 5f,
                    jumpTrigger = jumpTrigger,
                    jumpTargetPage = jumpTargetPage,
                    jumpTargetRect = jumpTargetRect,
                    scrollTrigger = scrollTrigger,
                    targetScrollFraction = targetScrollFraction,
                    resetZoomTrigger = resetZoomTrigger,
                    onStrokeAdded = { pIdx, st ->
                        val cur = strokesByPage[pIdx].orEmpty()
                        strokesByPage = strokesByPage + (pIdx to (cur + st))
                    },
                    onStrokeErased = { pIdx, rem ->
                        strokesByPage = strokesByPage + (pIdx to rem)
                    },
                    onActivePageChanged = { activePageIdx = it },
                    onScrollFractionChanged = { if (!isDraggingScrollbar) scrollFraction = it },
                    onZoomChanged = { zoomLevel = it },
                    modifier = Modifier.fillMaxSize()
                )

                if (!singlePageMode && pageCount > 1) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(vertical = 16.dp, end = 4.dp)
                            .width(32.dp)
                    ) {
                        val density = LocalDensity.current
                        val thumbH = with(density) { 48.dp.toPx() }
                        val usable = (with(density) { maxHeight.toPx() } - thumbH).coerceAtLeast(1f)
                        val offsetY = (scrollFraction * usable).roundToInt()

                        if (isDraggingScrollbar) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = LITE_CARD,
                                border = BorderStroke(1.dp, PDF_RED),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset { IntOffset(-with(density) { 36.dp.roundToPx() }, offsetY + with(density) { 8.dp.roundToPx() }) }
                            ) {
                                Text("${activePageIdx + 1}/$pageCount", fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isDraggingScrollbar) PDF_RED else Color(0xFF3F3F46).copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset { IntOffset(0, offsetY) }
                                .width(20.dp)
                                .height(48.dp)
                                .pointerInput(usable) {
                                    detectVerticalDragGestures(
                                        onDragStart = { isDraggingScrollbar = true },
                                        onDragEnd = { isDraggingScrollbar = false },
                                        onDragCancel = { isDraggingScrollbar = false },
                                        onVerticalDrag = { change, drag ->
                                            change.consume()
                                            val next = (scrollFraction + drag / usable).coerceIn(0f, 1f)
                                            scrollFraction = next
                                            targetScrollFraction = next
                                            scrollTrigger++
                                        }
                                    )
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.UnfoldMore, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                if (showSearchBar) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = LITE_CARD.copy(alpha = 0.97f),
                        border = BorderStroke(1.dp, PDF_RED.copy(alpha = 0.7f)),
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(10.dp)
                            .fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = PDF_RED, modifier = Modifier.size(18.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { q ->
                                    searchQuery = q
                                    scope.launch {
                                        val hits = withContext(Dispatchers.IO) { PdfLiteEngine.searchPdfContent(pdfPath, q) }
                                        searchHits = hits
                                        searchHitIdx = 0
                                        if (hits.isNotEmpty()) {
                                            jumpTargetPage = hits[0].pageIdx
                                            jumpTargetRect = hits[0].rectVirtual
                                            jumpTrigger++
                                        }
                                    }
                                },
                                singleLine = true,
                                cursorBrush = SolidColor(PDF_RED),
                                textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) Text("Dosya içinde bul...", fontSize = 13.sp, color = Color(0xFF71717A))
                                    inner()
                                },
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                            )
                            if (searchQuery.isNotEmpty()) {
                                Text(
                                    if (searchHits.isEmpty()) "0/0" else "${searchHitIdx + 1}/${searchHits.size}",
                                    fontSize = 11.sp,
                                    color = if (searchHits.isEmpty()) PDF_RED else Color(0xFF22C55E)
                                )
                            }
                            IconButton(
                                enabled = searchHits.isNotEmpty(),
                                onClick = {
                                    searchHitIdx = if (searchHitIdx - 1 < 0) searchHits.lastIndex else searchHitIdx - 1
                                    val h = searchHits[searchHitIdx]
                                    jumpTargetPage = h.pageIdx; jumpTargetRect = h.rectVirtual; jumpTrigger++
                                },
                                modifier = Modifier.size(28.dp)
                            ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.White) }
                            IconButton(
                                enabled = searchHits.isNotEmpty(),
                                onClick = {
                                    searchHitIdx = (searchHitIdx + 1) % searchHits.size
                                    val h = searchHits[searchHitIdx]
                                    jumpTargetPage = h.pageIdx; jumpTargetRect = h.rectVirtual; jumpTrigger++
                                },
                                modifier = Modifier.size(28.dp)
                            ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White) }
                            IconButton(
                                onClick = { showSearchBar = false; jumpTargetPage = -1; jumpTargetRect = null },
                                modifier = Modifier.size(28.dp)
                            ) { Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFF71717A), modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}
