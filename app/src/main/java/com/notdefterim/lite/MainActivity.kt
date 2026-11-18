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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val LITE_BG = Color(0xFF09090B)
private val LITE_CARD = Color(0xFF18181B)
private val LITE_BORDER = Color(0xFF27272A)
private val PDF_RED = Color(0xFFDC2626)
private val HONOR_BLUE = Color(0xFF3B82F6)

private enum class SortMode(val label: String) {
    RECENT("Son Açılan / Tarih"),
    SIZE("Dosya Boyutu"),
    ALPHA("Alfabetik (A–Z)")
}

class MainActivity : ComponentActivity() {
    private var incomingPdfUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            incomingPdfUri = intent.data
        }
    }

    @Composable
    private fun LiteAppRoot() {
        val scope = rememberCoroutineScope()
        var openPdf by remember { mutableStateOf<Pair<String, String>?>(null) }
        var recents by remember { mutableStateOf(PdfLiteEngine.loadRecents(this)) }
        var folders by remember { mutableStateOf(PdfLiteEngine.loadFolders(this)) }

        LaunchedEffect(incomingPdfUri) {
            val uri = incomingPdfUri ?: return@LaunchedEffect
            val imported = withContext(Dispatchers.IO) { PdfLiteEngine.importPdfUri(this@MainActivity, uri) }
            if (imported != null) {
                if (imported.isLargeFile) {
                    Toast.makeText(this@MainActivity, "Büyük dosya (>300 MB) — optimizasyon biraz sürebilir", Toast.LENGTH_LONG).show()
                }
                val meta = withContext(Dispatchers.IO) { PdfLiteEngine.getOrBuildMeta(this@MainActivity, imported.path) }
                withContext(Dispatchers.IO) {
                    PdfLiteEngine.saveRecent(
                        this@MainActivity,
                        RecentPdfItem(
                            title = imported.title,
                            path = imported.path,
                            pageCount = meta.pageCount,
                            openedAtMs = System.currentTimeMillis(),
                            fileSizeBytes = imported.fileSizeBytes
                        )
                    )
                }
                recents = PdfLiteEngine.loadRecents(this@MainActivity)
                openPdf = imported.path to imported.title
            } else {
                Toast.makeText(this@MainActivity, "Geçersiz veya desteklenmeyen PDF reddedildi", Toast.LENGTH_LONG).show()
            }
            incomingPdfUri = null
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val imported = withContext(Dispatchers.IO) { PdfLiteEngine.importPdfUri(this@MainActivity, uri) }
                if (imported != null) {
                    if (imported.isLargeFile) {
                        Toast.makeText(this@MainActivity, "Büyük dosya (>300 MB) — optimizasyon biraz sürebilir", Toast.LENGTH_LONG).show()
                    }
                    val meta = withContext(Dispatchers.IO) { PdfLiteEngine.getOrBuildMeta(this@MainActivity, imported.path) }
                    withContext(Dispatchers.IO) {
                        PdfLiteEngine.saveRecent(
                            this@MainActivity,
                            RecentPdfItem(
                                title = imported.title,
                                path = imported.path,
                                pageCount = meta.pageCount,
                                openedAtMs = System.currentTimeMillis(),
                                fileSizeBytes = imported.fileSizeBytes
                            )
                        )
                    }
                    recents = PdfLiteEngine.loadRecents(this@MainActivity)
                    openPdf = imported.path to imported.title
                } else {
                    Toast.makeText(this@MainActivity, "Geçersiz veya desteklenmeyen PDF reddedildi", Toast.LENGTH_LONG).show()
                }
            }
        }

        val active = openPdf
        if (active == null) {
            HomeScreen(
                recents = recents,
                folders = folders,
                onPickPdf = { picker.launch(arrayOf("application/pdf")) },
                onOpenRecent = { item ->
                    scope.launch(Dispatchers.IO) {
                        PdfLiteEngine.saveRecent(this@MainActivity, item.copy(openedAtMs = System.currentTimeMillis()))
                    }
                    openPdf = item.path to item.title
                },
                onDeletePdf = { item ->
                    scope.launch {
                        withContext(Dispatchers.IO) { PdfLiteEngine.deletePdf(this@MainActivity, item.path) }
                        recents = PdfLiteEngine.loadRecents(this@MainActivity)
                    }
                },
                onMoveToFolder = { item, folder ->
                    PdfLiteEngine.setPdfFolder(this@MainActivity, item.path, folder)
                    recents = PdfLiteEngine.loadRecents(this@MainActivity)
                },
                onCreateFolder = { name ->
                    folders = PdfLiteEngine.addFolder(this@MainActivity, name)
                },
                onDeleteFolder = { name ->
                    folders = PdfLiteEngine.deleteFolder(this@MainActivity, name)
                    recents = PdfLiteEngine.loadRecents(this@MainActivity)
                },
                onRefreshRecents = {
                    recents = PdfLiteEngine.loadRecents(this@MainActivity)
                }
            )
        } else {
            ReaderScreen(
                pdfPath = active.first,
                title = active.second,
                onBack = {
                    openPdf = null
                    recents = PdfLiteEngine.loadRecents(this@MainActivity)
                }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen(
        recents: List<RecentPdfItem>,
        folders: List<String>,
        onPickPdf: () -> Unit,
        onOpenRecent: (RecentPdfItem) -> Unit,
        onDeletePdf: (RecentPdfItem) -> Unit,
        onMoveToFolder: (RecentPdfItem, String) -> Unit,
        onCreateFolder: (String) -> Unit,
        onDeleteFolder: (String) -> Unit,
        onRefreshRecents: () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        var sortMode by remember { mutableStateOf(SortMode.RECENT) }
        var sortReversed by remember { mutableStateOf(false) }
        var showSortMenu by remember { mutableStateOf(false) }

        var selectedFolder by remember { mutableStateOf<String?>(null) }
        var showNewFolderDialog by remember { mutableStateOf(false) }
        var newFolderName by remember { mutableStateOf("") }

        var moveTargetItem by remember { mutableStateOf<RecentPdfItem?>(null) }
        var deleteTargetItem by remember { mutableStateOf<RecentPdfItem?>(null) }

        var showMergeDialog by remember { mutableStateOf(false) }
        var mergeSelection by remember { mutableStateOf<List<String>>(emptyList()) }
        var mergeTitle by remember { mutableStateOf("Birlesik_PDF") }
        var isMerging by remember { mutableStateOf(false) }

        val displayedList = remember(recents, selectedFolder, sortMode, sortReversed) {
            val filtered = if (selectedFolder == null) recents else recents.filter { it.folder == selectedFolder }
            val sorted = when (sortMode) {
                SortMode.RECENT -> filtered.sortedByDescending { it.openedAtMs }
                SortMode.SIZE -> filtered.sortedByDescending { it.fileSizeBytes }
                SortMode.ALPHA -> filtered.sortedBy { it.title.lowercase(java.util.Locale("tr", "TR")) }
            }
            if (sortReversed) sorted.reversed() else sorted
        }

        if (showNewFolderDialog) {
            AlertDialog(
                onDismissRequest = { showNewFolderDialog = false },
                containerColor = LITE_CARD,
                title = { Text("Yeni Klasör", color = Color.White, fontSize = 16.sp) },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Klasör adı") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newFolderName.isNotBlank()) onCreateFolder(newFolderName)
                        newFolderName = ""
                        showNewFolderDialog = false
                    }) { Text("Oluştur", color = HONOR_BLUE, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showNewFolderDialog = false }) { Text("İptal", color = Color(0xFFA1A1AA)) }
                }
            )
        }

        moveTargetItem?.let { item ->
            AlertDialog(
                onDismissRequest = { moveTargetItem = null },
                containerColor = LITE_CARD,
                title = { Text("Klasöre Taşı", color = Color.White, fontSize = 16.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, color = Color(0xFFA1A1AA), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (item.folder.isEmpty()) HONOR_BLUE.copy(alpha = 0.2f) else LITE_BG,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onMoveToFolder(item, "")
                                moveTargetItem = null
                            }
                        ) {
                            Text("Klasörsüz (Tümü)", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                        }
                        folders.forEach { f ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (item.folder == f) HONOR_BLUE.copy(alpha = 0.2f) else LITE_BG,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onMoveToFolder(item, f)
                                    moveTargetItem = null
                                }
                            ) {
                                Text(f, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { moveTargetItem = null }) { Text("Kapat", color = Color(0xFFA1A1AA)) }
                }
            )
        }

        deleteTargetItem?.let { item ->
            AlertDialog(
                onDismissRequest = { deleteTargetItem = null },
                containerColor = LITE_CARD,
                title = { Text("PDF Silinsin mi?", color = Color.White, fontSize = 16.sp) },
                text = { Text("'${item.title}' ve önbelleği uygulamadan silinecek.", color = Color(0xFFA1A1AA), fontSize = 13.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        onDeletePdf(item)
                        deleteTargetItem = null
                    }) { Text("Sil", color = PDF_RED, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTargetItem = null }) { Text("İptal", color = Color(0xFFA1A1AA)) }
                }
            )
        }

        if (showMergeDialog) {
            AlertDialog(
                onDismissRequest = { if (!isMerging) showMergeDialog = false },
                containerColor = LITE_CARD,
                title = { Text("PDF Birleştir", color = Color.White, fontSize = 16.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = mergeTitle,
                            onValueChange = { mergeTitle = it },
                            label = { Text("Yeni PDF adı") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Birleştirilecek PDF'leri sırayla seç (en az 2):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(recents, key = { it.path }) { item ->
                                val orderIdx = mergeSelection.indexOf(item.path)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (orderIdx >= 0) HONOR_BLUE.copy(alpha = 0.18f) else LITE_BG)
                                        .clickable {
                                            mergeSelection = if (orderIdx >= 0) mergeSelection - item.path else mergeSelection + item.path
                                        }
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        if (orderIdx >= 0) "${orderIdx + 1}." else "○",
                                        color = if (orderIdx >= 0) HONOR_BLUE else Color(0xFF71717A),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(item.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = mergeSelection.size >= 2 && !isMerging,
                        onClick = {
                            isMerging = true
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    PdfLiteEngine.mergePdfs(this@MainActivity, mergeSelection, mergeTitle)
                                }
                                if (res != null) {
                                    val imp = withContext(Dispatchers.IO) { PdfLiteEngine.importPdfUri(this@MainActivity, res.first) }
                                    if (imp != null) {
                                        val meta = withContext(Dispatchers.IO) { PdfLiteEngine.getOrBuildMeta(this@MainActivity, imp.path) }
                                        withContext(Dispatchers.IO) {
                                            PdfLiteEngine.saveRecent(
                                                this@MainActivity,
                                                RecentPdfItem(imp.title, imp.path, meta.pageCount, System.currentTimeMillis(), fileSizeBytes = imp.fileSizeBytes)
                                            )
                                        }
                                        onRefreshRecents()
                                    }
                                    Toast.makeText(this@MainActivity, "'${res.second}' İndirilenlere kaydedildi!", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Birleştirme başarısız", Toast.LENGTH_SHORT).show()
                                }
                                isMerging = false
                                showMergeDialog = false
                                mergeSelection = emptyList()
                            }
                        }
                    ) { Text(if (isMerging) "Birleştiriliyor..." else "Birleştir", color = PDF_RED, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(enabled = !isMerging, onClick = { showMergeDialog = false }) { Text("İptal", color = Color(0xFFA1A1AA)) }
                }
            )
        }

        Scaffold(
            containerColor = LITE_BG,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = LITE_BG, titleContentColor = Color.White),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(PDF_RED),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("PDF", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            }
                            Text("PDF Okuyucu Lite", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                    },
                    actions = {
                        if (recents.size >= 2) {
                            IconButton(onClick = { mergeSelection = emptyList(); showMergeDialog = true }) {
                                Icon(Icons.Default.CallMerge, contentDescription = "PDF Birleştir", tint = Color.White)
                            }
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sırala", tint = Color.White)
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }, modifier = Modifier.background(LITE_CARD)) {
                                SortMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.label, color = if (sortMode == m) HONOR_BLUE else Color.White) },
                                        onClick = {
                                            if (sortMode == m) sortReversed = !sortReversed else { sortMode = m; sortReversed = false }
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { sortReversed = !sortReversed }) {
                            Icon(
                                imageVector = if (sortReversed) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = "Sırayı Ters Çevir",
                                tint = Color(0xFFA1A1AA)
                            )
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
                    text = { Text("PDF Aç", fontWeight = FontWeight.Bold) }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = selectedFolder == null,
                        onClick = { selectedFolder = null },
                        label = { Text("Tümü", fontSize = 12.sp) }
                    )
                    folders.forEach { f ->
                        FilterChip(
                            selected = selectedFolder == f,
                            onClick = { selectedFolder = if (selectedFolder == f) null else f },
                            label = { Text(f, fontSize = 12.sp) },
                            trailingIcon = if (selectedFolder == f) {
                                {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Klasörü Sil",
                                        modifier = Modifier.size(14.dp).clickable {
                                            selectedFolder = null
                                            onDeleteFolder(f)
                                        }
                                    )
                                }
                            } else null
                        )
                    }
                    AssistChip(
                        onClick = { showNewFolderDialog = true },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Klasör", fontSize = 12.sp) }
                    )
                }

                if (displayedList.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = LITE_CARD,
                        border = BorderStroke(1.dp, LITE_BORDER),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (selectedFolder == null) "Henüz PDF açılmadı" else "'$selectedFolder' klasörü boş",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Sağ alttan PDF seç veya Dosyalar/WhatsApp'tan doğrudan PDF aç.",
                                color = Color(0xFFA1A1AA),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 84.dp)
                    ) {
                        items(displayedList, key = { it.path }) { item ->
                            val thumb by produceState<Bitmap?>(initialValue = null, item.path) {
                                value = withContext(Dispatchers.IO) { PdfLiteEngine.getOrCreateThumbnail(this@MainActivity, item.path) }
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = LITE_CARD,
                                border = BorderStroke(0.8.dp, LITE_BORDER),
                                modifier = Modifier.fillMaxWidth().clickable { onOpenRecent(item) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 44.dp, height = 58.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF27272A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val tBmp = thumb
                                        if (tBmp != null) {
                                            Image(
                                                bitmap = tBmp.asImageBitmap(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = PDF_RED, modifier = Modifier.size(24.dp))
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(item.title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val sizeTxt = PdfLiteEngine.formatSize(item.fileSizeBytes)
                                        val sub = buildString {
                                            append("${item.pageCount} sf")
                                            if (sizeTxt.isNotEmpty()) append(" • $sizeTxt")
                                            if (item.lastPage > 0) append(" • Kaldığın: ${item.lastPage + 1}.")
                                        }
                                        Text(sub, color = Color(0xFFA1A1AA), fontSize = 11.sp, maxLines = 1)
                                    }
                                    IconButton(onClick = { moveTargetItem = item }, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "Klasöre Taşı", tint = Color(0xFFA1A1AA), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { deleteTargetItem = item }, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Sil", tint = Color(0xFFA1A1AA), modifier = Modifier.size(18.dp))
                                    }
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
        var metaLoaded by remember(pdfPath) { mutableStateOf(false) }
        var optProgress by remember(pdfPath) { mutableStateOf<Pair<Int, Int>?>(null) }
        var securityReport by remember(pdfPath) { mutableStateOf<PdfSecurityReport?>(null) }
        var showSecurityBanner by remember(pdfPath) { mutableStateOf(false) }
        var showSecurityDialog by remember { mutableStateOf(false) }

        LaunchedEffect(pdfPath) {
            val (meta, savedPage, savedStrokes) = withContext(Dispatchers.IO) {
                val m = PdfLiteEngine.getOrBuildMeta(this@MainActivity, pdfPath)
                val lp = PdfLiteEngine.getLastPage(this@MainActivity, pdfPath).coerceIn(0, m.pageCount - 1)
                val st = PdfLiteEngine.loadStrokes(this@MainActivity, pdfPath)
                Triple(m, lp, st)
            }
            val rep = withContext(Dispatchers.IO) { PdfLiteEngine.scanPdfSecurity(pdfPath) }
            securityReport = rep
            if (!rep.isSafe) showSecurityBanner = true

            pageCount = meta.pageCount
            splitEndStr = meta.pageCount.toString()
            pageRatios = meta.ratios
            strokesByPage = savedStrokes
            activePageIdx = savedPage
            pageBitmaps = List(meta.pageCount) { null }
            metaLoaded = true
            if (savedPage > 0) {
                jumpTargetPage = savedPage
                jumpTargetRect = null
                jumpTrigger++
            }
            if (!meta.optimized) {
                withContext(Dispatchers.IO) {
                    PdfLiteEngine.optimizePdfInBackground(
                        context = this@MainActivity,
                        pdfPath = pdfPath,
                        meta = meta,
                        startPage = savedPage,
                        isCancelled = { !isActive },
                        onProgress = { done, total ->
                            optProgress = if (done < total) done to total else null
                        }
                    )
                }
                optProgress = null
            }
        }

        // Auto-hide initial security warning banner after 4.5s (stays accessible in More menu)
        LaunchedEffect(showSecurityBanner) {
            if (showSecurityBanner) {
                delay(4500L)
                showSecurityBanner = false
            }
        }

        LaunchedEffect(activePageIdx, metaLoaded, pdfPath) {
            if (!metaLoaded) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                PdfLiteEngine.saveLastPage(this@MainActivity, pdfPath, activePageIdx)
            }
        }

        LaunchedEffect(activePageIdx, pageCount, metaLoaded, pdfPath) {
            if (!metaLoaded || pageBitmaps.size != pageCount) return@LaunchedEffect
            val winStart = (activePageIdx - 5).coerceAtLeast(0)
            val winEnd = (activePageIdx + 7).coerceAtMost(pageCount - 1)
            val bmps = pageBitmaps.toMutableList()
            var changed = false
            for (i in bmps.indices) {
                if (i < winStart || i > winEnd) {
                    if (bmps[i] != null) { bmps[i] = null; changed = true }
                } else if (bmps[i] == null) {
                    bmps[i] = withContext(Dispatchers.IO) {
                        PdfLiteEngine.renderPageBitmap(this@MainActivity, pdfPath, i, 1200)
                    }
                    changed = true
                }
            }
            if (changed) pageBitmaps = bmps
        }

        if (showSecurityDialog) {
            val rep = securityReport
            AlertDialog(
                onDismissRequest = { showSecurityDialog = false },
                containerColor = LITE_CARD,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = if (rep?.isSafe != false) Icons.Default.VerifiedUser else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (rep?.isSafe != false) Color(0xFF22C55E) else Color(0xFFF59E0B)
                        )
                        Text(
                            if (rep?.isSafe != false) "Güvenli PDF" else "Riskli Etiket Tespit Edildi",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rep == null || rep.isSafe) {
                            Text("Dosya içinde gömülü JavaScript, otomatik çalıştırma (/OpenAction, /Launch) veya gizli ek bulunmadı.", fontSize = 13.sp, color = Color(0xFFD4D4D8))
                        } else {
                            Text("Bu PDF içinde şu aktif bileşenler tespit edildi:", fontSize = 13.sp, color = Color(0xFFFDE68A))
                            rep.findings.forEach { f ->
                                Text("• $f", fontSize = 12.sp, color = Color.White)
                            }
                        }
                        Text(
                            "İzole Koruma Aktif: Uygulamanın internet izni yoktur, betik/WebView motoru içermez ve sayfaları yalnızca piksel görüntüsü olarak işler.",
                            fontSize = 12.sp,
                            color = Color(0xFFA1A1AA)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSecurityDialog = false }) {
                        Text("Tamam", color = HONOR_BLUE, fontWeight = FontWeight.Bold)
                    }
                }
            )
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
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                DropdownMenuItem(
                                    text = { Text("Güvenlik Raporu", color = Color.White) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (securityReport?.isSafe != false) Icons.Default.VerifiedUser else Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = if (securityReport?.isSafe != false) Color(0xFF22C55E) else Color(0xFFF59E0B)
                                        )
                                    },
                                    onClick = { showMoreMenu = false; showSecurityDialog = true }
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
                                        if (cur.isNotEmpty()) {
                                            val next = strokesByPage + (activePageIdx to cur.dropLast(1))
                                            strokesByPage = next
                                            scope.launch(Dispatchers.IO) { PdfLiteEngine.saveStrokes(this@MainActivity, pdfPath, next) }
                                        }
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
                        val next = strokesByPage + (pIdx to (cur + st))
                        strokesByPage = next
                        scope.launch(Dispatchers.IO) { PdfLiteEngine.saveStrokes(this@MainActivity, pdfPath, next) }
                    },
                    onStrokeErased = { pIdx, rem ->
                        val next = strokesByPage + (pIdx to rem)
                        strokesByPage = next
                        scope.launch(Dispatchers.IO) { PdfLiteEngine.saveStrokes(this@MainActivity, pdfPath, next) }
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
                            .padding(top = 16.dp, bottom = 16.dp, end = 4.dp)
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

                optProgress?.let { (done, total) ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = LITE_CARD.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, HONOR_BLUE.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                    ) {
                        Text(
                            "Optimize ediliyor: $done / $total",
                            fontSize = 11.sp,
                            color = Color(0xFFE4E4E7),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                if (showSecurityBanner) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF271F0E).copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                            Text(
                                "Riskli etiket bulundu (izole modda güvenli)",
                                fontSize = 12.sp,
                                color = Color(0xFFFDE68A),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Detay",
                                fontSize = 12.sp,
                                color = HONOR_BLUE,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    showSecurityBanner = false
                                    showSecurityDialog = true
                                }
                            )
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Gizle",
                                tint = Color(0xFFA1A1AA),
                                modifier = Modifier.size(16.dp).clickable { showSecurityBanner = false }
                            )
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
                                        val res = withContext(Dispatchers.IO) { PdfLiteEngine.searchPdfContent(pdfPath, q) }
                                        searchHits = res
                                        searchHitIdx = 0
                                        if (res.isNotEmpty()) {
                                            jumpTargetPage = res[0].pageIdx
                                            jumpTargetRect = res[0].rectVirtual
                                            jumpTrigger++
                                        }
                                    }
                                },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
                                cursorBrush = SolidColor(PDF_RED),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) Text("Dosya içinde bul...", fontSize = 14.sp, color = Color(0xFF71717A))
                                    inner()
                                },
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                            )
                            if (searchHits.isNotEmpty()) {
                                Text("${searchHitIdx + 1}/${searchHits.size}", fontSize = 11.sp, color = Color(0xFFA1A1AA))
                            }
                            IconButton(
                                enabled = searchHits.isNotEmpty(),
                                onClick = {
                                    val prev = (searchHitIdx - 1 + searchHits.size) % searchHits.size
                                    searchHitIdx = prev
                                    jumpTargetPage = searchHits[prev].pageIdx
                                    jumpTargetRect = searchHits[prev].rectVirtual
                                    jumpTrigger++
                                },
                                modifier = Modifier.size(28.dp)
                            ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.White) }
                            IconButton(
                                enabled = searchHits.isNotEmpty(),
                                onClick = {
                                    val next = (searchHitIdx + 1) % searchHits.size
                                    searchHitIdx = next
                                    jumpTargetPage = searchHits[next].pageIdx
                                    jumpTargetRect = searchHits[next].rectVirtual
                                    jumpTrigger++
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
