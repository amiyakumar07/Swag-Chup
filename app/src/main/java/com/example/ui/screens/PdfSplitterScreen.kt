package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.PdfViewModel
import com.example.ui.viewmodel.ToolStatus

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PdfSplitterScreen(
    viewModel: PdfViewModel,
    docId: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allDocs by viewModel.allDocuments.collectAsState()
    val doc = allDocs.find { it.id == docId }

    if (doc == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Document not found.")
        }
        return
    }

    val toolStatus by viewModel.toolStatus.collectAsState()

    var newFileName by remember { mutableStateOf("${doc.fileName}_extracted") }
    val selectedPages = remember { mutableStateListOf<Int>() }

    // Intercept back actions
    BackHandler {
        viewModel.resetToolStatus()
        onBack()
    }

    // Success dialog handle
    var showSuccessDialog by remember { mutableStateOf(false) }
    var runningSuccessMessage by remember { mutableStateOf("") }

    LaunchedEffect(toolStatus) {
        if (toolStatus is ToolStatus.Success) {
            runningSuccessMessage = (toolStatus as ToolStatus.Success).message
            showSuccessDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Page Extractor Tools",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                        Text(
                            doc.fileName,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.resetToolStatus()
                        onBack()
                    }, modifier = Modifier.testTag("splitter_back_btn")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            selectedPages.clear()
                            selectedPages.addAll(0 until doc.totalPages)
                        }
                    ) {
                        Text("All", fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = { selectedPages.clear() }
                    ) {
                        Text("Clear", fontWeight = FontWeight.Bold)
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
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("Output Document Name") },
                        placeholder = { Text("E.g. Class_Notes_Split") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            Text(
                                ".pdf",
                                modifier = Modifier.padding(end = 12.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("output_pdf_name_input")
                    )

                    Button(
                        onClick = {
                            viewModel.extractPages(
                                context = context,
                                doc = doc,
                                selectedPages = selectedPages.toList().sorted(),
                                newTitle = newFileName
                            )
                        },
                        enabled = selectedPages.isNotEmpty() && toolStatus != ToolStatus.Processing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("process_extract_btn"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (toolStatus == ToolStatus.Processing) {
                            CircularProgressIndicator(
                                strokeWidth = 3.dp,
                                color = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Splitting and Saving PDF...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Extract (${selectedPages.size} pages) & Save File",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Preset Selection Filters row
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Autoselect:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Even Pages index
                    FilterChip(
                        selected = false,
                        onClick = {
                            selectedPages.clear()
                            (0 until doc.totalPages).forEach { page ->
                                if ((page + 1) % 2 == 0) selectedPages.add(page)
                            }
                        },
                        label = { Text("Evens", fontSize = 11.sp) }
                    )

                    // Odd Pages index
                    FilterChip(
                        selected = false,
                        onClick = {
                            selectedPages.clear()
                            (0 until doc.totalPages).forEach { page ->
                                if ((page + 1) % 2 != 0) selectedPages.add(page)
                            }
                        },
                        label = { Text("Odds", fontSize = 11.sp) }
                    )

                    // First Half index
                    FilterChip(
                        selected = false,
                        onClick = {
                            selectedPages.clear()
                            val midPoint = doc.totalPages / 2
                            selectedPages.addAll(0 until midPoint.coerceAtLeast(1))
                        },
                        label = { Text("First Half", fontSize = 11.sp) }
                    )
                }
            }

            // Message / Error handling warning banner
            if (toolStatus is ToolStatus.Error) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Error icon", tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            text = (toolStatus as ToolStatus.Error).message,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.resetToolStatus() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close error message", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // Page Selector grid Layout
            Text(
                "Click individual pages below to select ranges for extraction:",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(doc.totalPages) { index ->
                    val isChecked = selectedPages.contains(index)
                    PageGridCell(
                        pageNumber = index + 1,
                        isSelected = isChecked,
                        onClick = {
                            if (isChecked) {
                                selectedPages.remove(index)
                            } else {
                                selectedPages.add(index)
                            }
                        },
                        modifier = Modifier.testTag("splitter_page_cell_$index")
                    )
                }
            }
        }
    }

    // Extraction Success Dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.resetToolStatus()
                showSuccessDialog = false
                onBack()
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(28.dp)
                    )
                    Text("Successful Extraction", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    runningSuccessMessage + "\n\nThe split PDF has been successfully added as a clean standalone file on your dashboard bookshelf.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetToolStatus()
                        showSuccessDialog = false
                        onBack()
                    }
                ) {
                    Text("Go To Library", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun PageGridCell(
    pageNumber: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .shadow(if (isSelected) 4.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(12.dp)
        ) {
            // Elegant mock vector page illustration
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                "Page $pageNumber",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Checkbox/Radio circle indicator
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }
    }
}
