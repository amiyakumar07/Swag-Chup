package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PdfBookmark
import com.example.ui.viewmodel.PdfViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    viewModel: PdfViewModel,
    docId: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val docFlow = remember(docId) { viewModel.allDocuments }
    val allDocs by docFlow.collectAsState()
    val doc = allDocs.find { it.id == docId }

    if (doc == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Document not found.")
        }
        return
    }

    // Bookmarks for this document
    val bookmarksFlow = remember(docId) { viewModel.getBookmarks(docId) }
    val bookmarks by bookmarksFlow.collectAsState()

    val file = remember(doc.localPath) { File(doc.localPath) }
    var fileExists by remember { mutableStateOf(file.exists()) }

    var isDarkReader by remember { mutableStateOf(false) }
    var currentProgressPage by remember { mutableIntStateOf(doc.lastReadPage) }

    val scrollState = rememberLazyListState(initialFirstVisibleItemIndex = doc.lastReadPage)
    val coroutineScope = rememberCoroutineScope()

    var showBookmarkDrawer by remember { mutableStateOf(false) }
    var showBookmarkAddDialog by remember { mutableStateOf(false) }

    // Intercept hardware or software back clicks
    BackHandler {
        onBack()
    }

    // Auto-update db reading progress when scrolled
    LaunchedEffect(scrollState.firstVisibleItemIndex) {
        val currPageIdx = scrollState.firstVisibleItemIndex
        if (currPageIdx != currentProgressPage && currPageIdx < doc.totalPages) {
            currentProgressPage = currPageIdx
            viewModel.updateReadingProgress(doc, currPageIdx)
        }
    }

    // Scroll back to last read position on start
    LaunchedEffect(docId) {
        if (doc.lastReadPage > 0 && doc.lastReadPage < doc.totalPages) {
            scrollState.scrollToItem(doc.lastReadPage)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = doc.fileName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Page ${currentProgressPage + 1} of ${doc.totalPages}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("reader_back_button")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { isDarkReader = !isDarkReader },
                        modifier = Modifier.testTag("toggle_dark_reader")
                    ) {
                        Icon(
                            imageVector = if (isDarkReader) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle high contrast mode"
                        )
                    }

                    IconButton(
                        onClick = { showBookmarkAddDialog = true },
                        modifier = Modifier.testTag("add_bookmark_button")
                    ) {
                        val isBookmarkedOnThisPage = bookmarks.any { it.pageNumber == currentProgressPage }
                        Icon(
                            imageVector = if (isBookmarkedOnThisPage) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Bookmark this page"
                        )
                    }

                    IconButton(
                        onClick = { showBookmarkDrawer = true },
                        modifier = Modifier.testTag("bookmark_drawer_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = "Show table of notes and bookmarks"
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Jump page:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Page ${currentProgressPage + 1} / ${doc.totalPages}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // Page slider
                    if (doc.totalPages > 1) {
                        Slider(
                            value = currentProgressPage.toFloat(),
                            onValueChange = { pageVal ->
                                val targetPage = pageVal.toInt()
                                if (targetPage != currentProgressPage) {
                                    currentProgressPage = targetPage
                                    coroutineScope.launch {
                                        scrollState.scrollToItem(targetPage)
                                    }
                                }
                            },
                            valueRange = 0f..(doc.totalPages - 1).toFloat(),
                            steps = if (doc.totalPages > 2) doc.totalPages - 2 else 0,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (!fileExists) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(
                    "The local file for this document does not exist. It may have been uninstalled or modified external to the application.",
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            ZoomableBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(if (isDarkReader) Color(0xFF0F0F0F) else Color(0xFFECEFF1))
            ) { scale, offset ->
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(doc.totalPages) { idx ->
                        PdfRendererPage(
                            filePath = doc.localPath,
                            pageIndex = idx,
                            isDarkReader = isDarkReader,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pdf_page_card_$idx")
                        )
                    }
                }
            }
        }
    }

    // Bookmark & notes bottom sheet drawer representation
    if (showBookmarkDrawer) {
        ModalBottomSheet(
            onDismissRequest = { showBookmarkDrawer = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Study Bookmarks & Notes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { showBookmarkDrawer = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close sheet")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (bookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No bookmarks added to this PDF yet. Bookmark pages to save formulas, definitions, or custom reading notes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(bookmarks) { b ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showBookmarkDrawer = false
                                        coroutineScope.launch {
                                            scrollState.scrollToItem(b.pageNumber)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    "Page ${b.pageNumber + 1}",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp
                                                )
                                            }
                                            Text(b.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                        if (b.note.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                b.note,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                            )
                                        }
                                    }

                                    IconButton(onClick = { viewModel.removeBookmark(b.id) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete bookmark",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Bookmark Add Dialog
    if (showBookmarkAddDialog) {
        var labelInput by remember { mutableStateOf("High Point") }
        var noteInput by remember { mutableStateOf("") }
        val checkBookmarkPage = bookmarks.any { it.pageNumber == currentProgressPage }

        AlertDialog(
            onDismissRequest = { showBookmarkAddDialog = false },
            title = {
                Text(
                    text = if (checkBookmarkPage) "Page Already Bookmarked" else "Bookmark Page ${currentProgressPage + 1}",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (checkBookmarkPage) {
                        Text(
                            "This page has already been marked. You can create an extra card or delete the current one from the reading notes tray.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Bookmark Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text("Personal Study Note (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (labelInput.trim().isNotEmpty()) {
                            viewModel.addBookmark(docId, currentProgressPage, labelInput.trim(), noteInput.trim())
                        }
                        showBookmarkAddDialog = false
                    }
                ) {
                    Text("Save Bookmark")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBookmarkAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Render Engine: Draws the exact page canvas of a given PDF on demand
@Composable
fun PdfRendererPage(
    filePath: String,
    pageIndex: Int,
    isDarkReader: Boolean,
    modifier: Modifier = Modifier
) {
    var pageBitmap by remember(filePath, pageIndex, isDarkReader) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(filePath, pageIndex, isDarkReader) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                if (pageIndex < renderer.pageCount) {
                    val page = renderer.openPage(pageIndex)

                    // Standard high scale density rendering
                    val scale = 2
                    val width = page.width * scale
                    val height = page.height * scale

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                    // Pre-fill canvas
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    // Inverse-shader adjustment for dark mode readability
                    if (isDarkReader) {
                        val paint = android.graphics.Paint()
                        val matrix = android.graphics.ColorMatrix(floatArrayOf(
                            -1.0f, 0.0f, 0.0f, 0.0f, 255.0f, // Red
                            0.0f, -1.0f, 0.0f, 0.0f, 255.0f, // Green
                            0.0f, 0.0f, -1.0f, 0.0f, 255.0f, // Blue
                            0.0f, 0.0f, 0.0f, 1.0f, 0.0f     // Alpha
                        ))
                        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)

                        val invertedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val invCanvas = android.graphics.Canvas(invertedBitmap)
                        invCanvas.drawBitmap(bitmap, 0f, 0f, paint)

                        pageBitmap = invertedBitmap
                    } else {
                        pageBitmap = bitmap
                    }

                    page.close()
                }
                renderer.close()
                pfd.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.707f) // ISO letter ratio
            .clip(RoundedCornerShape(8.dp))
            .shadow(2.dp, shape = RoundedCornerShape(8.dp))
            .background(if (isDarkReader) Color(0xFF1E1E1E) else Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (pageBitmap != null) {
            Image(
                bitmap = pageBitmap!!.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                Text("Rendering page ${pageIndex + 1}...", fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

// Gesture engine enabling rich pinch, zoom, double tap, and custom offset panning behavior
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(scale: Float, offset: Offset) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += offsetChange * scale
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    }
                )
            }
            .transformable(state = transformState)
    ) {
        content(scale, offset)
    }
}
