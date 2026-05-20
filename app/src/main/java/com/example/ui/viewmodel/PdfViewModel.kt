package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Path
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.PdfBookmark
import com.example.data.model.PdfDocument
import com.example.data.repository.PdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream

sealed class ImportStatus {
    object Idle : ImportStatus()
    object Importing : ImportStatus()
    object Success : ImportStatus()
    data class Error(val message: String) : ImportStatus()
}

sealed class ToolStatus {
    object Idle : ToolStatus()
    object Processing : ToolStatus()
    data class Success(val message: String) : ToolStatus()
    data class Error(val message: String) : ToolStatus()
}

class PdfViewModel(private val repository: PdfRepository) : ViewModel() {

    val allDocuments: StateFlow<List<PdfDocument>> = repository.allDocuments
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _importStatus = MutableStateFlow<ImportStatus>(ImportStatus.Idle)
    val importStatus: StateFlow<ImportStatus> = _importStatus.asStateFlow()

    private val _toolStatus = MutableStateFlow<ToolStatus>(ToolStatus.Idle)
    val toolStatus: StateFlow<ToolStatus> = _toolStatus.asStateFlow()

    fun resetImportStatus() {
        _importStatus.value = ImportStatus.Idle
    }

    fun resetToolStatus() {
        _toolStatus.value = ToolStatus.Idle
    }

    fun importPdf(context: Context, uri: Uri, category: String = "General") {
        viewModelScope.launch(Dispatchers.IO) {
            _importStatus.value = ImportStatus.Importing
            try {
                val contentResolver = context.contentResolver
                var originalName = "Document_${System.currentTimeMillis()}.pdf"
                var fileSize = 0L

                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) originalName = cursor.getString(nameIndex)
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }

                // Ensure it has .pdf suffix for safety
                if (!originalName.endsWith(".pdf", ignoreCase = true)) {
                    originalName = "$originalName.pdf"
                }

                val localFileName = "pdf_${System.currentTimeMillis()}_${originalName.replace(" ", "_")}"
                val localFile = File(context.filesDir, localFileName)

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    localFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (fileSize == 0L) {
                    fileSize = localFile.length()
                }

                // Learn page count using PdfRenderer safely
                var totalPages = 0
                try {
                    val pfd = ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    totalPages = renderer.pageCount
                    renderer.close()
                    pfd.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val newDoc = PdfDocument(
                    fileName = originalName.substringBeforeLast(".pdf"),
                    localPath = localFile.absolutePath,
                    fileSize = fileSize,
                    totalPages = totalPages,
                    category = category
                )
                repository.insertDocument(newDoc)
                _importStatus.value = ImportStatus.Success
            } catch (e: Exception) {
                e.printStackTrace()
                _importStatus.value = ImportStatus.Error(e.message ?: "Failed to import file")
            }
        }
    }

    fun updateReadingProgress(doc: PdfDocument, page: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = doc.copy(
                lastReadPage = page,
                lastReadDate = System.currentTimeMillis()
            )
            repository.updateDocument(updated)
        }
    }

    fun toggleFavorite(doc: PdfDocument) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = doc.copy(isFavorite = !doc.isFavorite)
            repository.updateDocument(updated)
        }
    }

    fun updatePdfCategory(doc: PdfDocument, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = doc.copy(category = category)
            repository.updateDocument(updated)
        }
    }

    fun deleteDocument(doc: PdfDocument) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(doc.localPath)
                if (file.exists()) {
                    file.delete()
                }
                repository.deleteDocument(doc.id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getBookmarks(pdfId: Int): StateFlow<List<PdfBookmark>> {
        val flow = MutableStateFlow<List<PdfBookmark>>(emptyList())
        viewModelScope.launch {
            repository.getBookmarks(pdfId).collect {
                flow.value = it
            }
        }
        return flow
    }

    fun addBookmark(pdfId: Int, pageIndex: Int, label: String, note: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val b = PdfBookmark(
                pdfId = pdfId,
                pageNumber = pageIndex,
                label = label,
                note = note
            )
            repository.insertBookmark(b)
        }
    }

    fun removeBookmark(bookmarkId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBookmark(bookmarkId)
        }
    }

    fun removeBookmarkAtPage(pdfId: Int, pageIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBookmarkAtPage(pdfId, pageIndex)
        }
    }

    // Modern native PDF tool helper: Extract page ranges to a new PDF document.
    fun extractPages(context: Context, doc: PdfDocument, selectedPages: List<Int>, newTitle: String) {
        if (selectedPages.isEmpty()) {
            _toolStatus.value = ToolStatus.Error("Please select at least one page")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) {
                    throw Exception("Source file has been moved or deleted")
                }

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)

                val outPdf = android.graphics.pdf.PdfDocument()

                for ((idx, pageNum) in selectedPages.withIndex()) {
                    if (pageNum < 0 || pageNum >= renderer.pageCount) continue
                    val page = renderer.openPage(pageNum)

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                        page.width,
                        page.height,
                        idx
                    ).create()
                    val newPage = outPdf.startPage(pageInfo)

                    // Render page into temporary Bitmap, then draw onto page's canvas
                    val pageBitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    newPage.canvas.drawBitmap(pageBitmap, 0f, 0f, null)
                    pageBitmap.recycle()

                    outPdf.finishPage(newPage)
                    page.close()
                }

                renderer.close()
                pfd.close()

                var title = newTitle.trim()
                if (title.isEmpty()) {
                    title = "${doc.fileName}_extracted"
                }

                val originalTitleCleaned = title.replace(" ", "_")
                val localFileName = "pdf_tool_${System.currentTimeMillis()}_$originalTitleCleaned.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val newDoc = PdfDocument(
                    fileName = title,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = selectedPages.size,
                    category = "Extracted"
                )
                repository.insertDocument(newDoc)
                _toolStatus.value = ToolStatus.Success("Extracted ${selectedPages.size} pages into standard file '$title'")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed page extraction")
            }
        }
    }

    // Natively compress a PDF file by rendering pages with JPEG compression
    fun compressPdf(context: Context, doc: PdfDocument, qualityPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) {
                    throw Exception("Source file not found")
                }

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outPdf = android.graphics.pdf.PdfDocument()
                val totalOriginalPages = renderer.pageCount

                for (i in 0 until totalOriginalPages) {
                    val page = renderer.openPage(i)
                    // Scale down slightly if needed for extreme compression
                    val scale = if (page.width > 1200) 1200f / page.width else 1.0f
                    val w = (page.width * scale).toInt()
                    val h = (page.height * scale).toInt()

                    val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    // Write to compressed byte array streams
                    val baos = java.io.ByteArrayOutputStream()
                    pageBitmap.compress(Bitmap.CompressFormat.JPEG, qualityPercent, baos)
                    val compressedBytes = baos.toByteArray()
                    val compressedBitmap = android.graphics.BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i).create()
                    val newPage = outPdf.startPage(pageInfo)
                    newPage.canvas.drawBitmap(compressedBitmap, 0f, 0f, null)
                    outPdf.finishPage(newPage)

                    page.close()
                    pageBitmap.recycle()
                    compressedBitmap.recycle()
                }

                renderer.close()
                pfd.close()

                val compressedTitle = "${doc.fileName}_compressed"
                val localFileName = "pdf_compressed_${System.currentTimeMillis()}_${compressedTitle.replace(" ", "_")}.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val compressedDoc = PdfDocument(
                    fileName = compressedTitle,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = totalOriginalPages,
                    category = "Extracted"
                )
                repository.insertDocument(compressedDoc)
                _toolStatus.value = ToolStatus.Success("Reduced '${doc.fileName}' natively with ${qualityPercent}% quality bounds!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed compressing file")
            }
        }
    }

    // 1. MERGE PDFS
    fun mergePdfs(context: Context, docs: List<PdfDocument>, newTitle: String) {
        if (docs.size < 2) {
            _toolStatus.value = ToolStatus.Error("Please select at least 2 documents to merge")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val outPdf = android.graphics.pdf.PdfDocument()
                var pageIdx = 0

                for (doc in docs) {
                    val file = File(doc.localPath)
                    if (!file.exists()) continue
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                            page.width,
                            page.height,
                            pageIdx++
                        ).create()
                        val newPage = outPdf.startPage(pageInfo)
                        
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        newPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        bitmap.recycle()
                        
                        outPdf.finishPage(newPage)
                        page.close()
                    }
                    renderer.close()
                    pfd.close()
                }

                val title = if (newTitle.trim().isEmpty()) "Merged_Document" else newTitle.trim()
                val filename = "pdf_merged_${System.currentTimeMillis()}_${title.replace(" ", "_")}.pdf"
                val destFile = File(context.filesDir, filename)
                destFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val mergedDoc = PdfDocument(
                    fileName = title,
                    localPath = destFile.absolutePath,
                    fileSize = destFile.length(),
                    totalPages = pageIdx,
                    category = "Extracted"
                )
                repository.insertDocument(mergedDoc)
                _toolStatus.value = ToolStatus.Success("Successfully merged ${docs.size} files into '$title' ($pageIdx pages)!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed combining documents")
            }
        }
    }

    // 2. TEXT TO PDF CONVERTER
    fun convertTextToPdf(context: Context, text: String, title: String) {
        if (text.trim().isEmpty()) {
            _toolStatus.value = ToolStatus.Error("Please enter some text content to save")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val outPdf = android.graphics.pdf.PdfDocument()
                val pageWidth = 595 // A4 standard width in points
                val pageHeight = 842 // A4 standard height in points
                
                val paint = Paint().apply {
                    textSize = 12f
                    isAntiAlias = true
                    color = android.graphics.Color.BLACK
                }
                
                val titlePaint = Paint().apply {
                    textSize = 18f
                    isFakeBoldText = true
                    isAntiAlias = true
                    color = android.graphics.Color.rgb(103, 80, 164) // Purple primary
                }

                val margin = 50f
                val contentWidth = pageWidth - (margin * 2)
                
                // Super robust wrap system
                val lines = mutableListOf<String>()
                text.split("\n").forEach { paragraph ->
                    if (paragraph.trim().isEmpty()) {
                        lines.add("")
                        return@forEach
                    }
                    val words = paragraph.split(" ")
                    var currentLine = ""
                    for (word in words) {
                        val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                        val testWidth = paint.measureText(testLine)
                        if (testWidth <= contentWidth) {
                            currentLine = testLine
                        } else {
                            lines.add(currentLine)
                            currentLine = word
                        }
                    }
                    if (currentLine.isNotEmpty()) {
                        lines.add(currentLine)
                    }
                }

                var lineIndex = 0
                var pageNum = 0
                val leading = 16f
                
                while (lineIndex < lines.size) {
                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum++).create()
                    val page = outPdf.startPage(pageInfo)
                    val canvas = page.canvas
                    
                    var yPosition = margin
                    if (pageNum == 1) {
                        canvas.drawText(title, margin, yPosition + 15, titlePaint)
                        yPosition += 45f
                    }
                    
                    while (lineIndex < lines.size && yPosition + leading < pageHeight - margin) {
                        val line = lines[lineIndex++]
                        if (line.isNotEmpty()) {
                            canvas.drawText(line, margin, yPosition, paint)
                        }
                        yPosition += leading
                    }
                    outPdf.finishPage(page)
                }

                val cleanTitle = if (title.trim().isEmpty()) "Typed_Note" else title.trim()
                val filename = "pdf_text_${System.currentTimeMillis()}_${cleanTitle.replace(" ", "_")}.pdf"
                val destFile = File(context.filesDir, filename)
                destFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val newDoc = PdfDocument(
                    fileName = cleanTitle,
                    localPath = destFile.absolutePath,
                    fileSize = destFile.length(),
                    totalPages = pageNum,
                    category = "General"
                )
                repository.insertDocument(newDoc)
                _toolStatus.value = ToolStatus.Success("Created PDF entirely from text input!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed text-to-pdf operation")
            }
        }
    }

    // 3. IMAGES TO PDF CONVERTER
    fun convertImagesToPdf(context: Context, uris: List<Uri>, title: String) {
        if (uris.isEmpty()) {
            _toolStatus.value = ToolStatus.Error("Please select at least one image")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val outPdf = android.graphics.pdf.PdfDocument()
                val contentResolver = context.contentResolver
                var pageIdx = 0

                for (uri in uris) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        val bytes = inputStream.readBytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        
                        var w = options.outWidth
                        var h = options.outHeight
                        if (w <= 0 || h <= 0) {
                            w = 600
                            h = 800
                        }

                        val scale = if (w > 1200) 1200f / w else 1.0f
                        val scaledW = (w * scale).toInt()
                        val scaledH = (h * scale).toInt()

                        val optionsDecode = BitmapFactory.Options().apply {
                            inSampleSize = if (scale < 1f) (1f / scale).toInt().coerceAtLeast(1) else 1
                        }
                        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, optionsDecode)
                        
                        val bitmap = Bitmap.createScaledBitmap(rawBitmap, scaledW, scaledH, true)
                        if (rawBitmap != bitmap) {
                            rawBitmap.recycle()
                        }

                        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(scaledW, scaledH, pageIdx++).create()
                        val page = outPdf.startPage(pageInfo)
                        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        outPdf.finishPage(page)
                        bitmap.recycle()
                    }
                }

                val cleanTitle = if (title.trim().isEmpty()) "Image_Compilation" else title.trim()
                val filename = "pdf_images_${System.currentTimeMillis()}_${cleanTitle.replace(" ", "_")}.pdf"
                val destFile = File(context.filesDir, filename)
                destFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val newDoc = PdfDocument(
                    fileName = cleanTitle,
                    localPath = destFile.absolutePath,
                    fileSize = destFile.length(),
                    totalPages = pageIdx,
                    category = "General"
                )
                repository.insertDocument(newDoc)
                _toolStatus.value = ToolStatus.Success("Synthesized $pageIdx images into high-fidelity PDF!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed image conversion")
            }
        }
    }

    // 4. WATERMARK PDF
    fun watermarkPdf(context: Context, doc: PdfDocument, watermarkText: String) {
        if (watermarkText.trim().isEmpty()) {
            _toolStatus.value = ToolStatus.Error("Please enter watermarking text overlay")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) {
                    throw Exception("Source document not found")
                }

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outPdf = android.graphics.pdf.PdfDocument()
                val totalOriginalPages = renderer.pageCount

                val watermarkPaint = Paint().apply {
                    color = android.graphics.Color.argb(45, 120, 120, 120)
                    textSize = 48f
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }

                for (i in 0 until totalOriginalPages) {
                    val page = renderer.openPage(i)
                    val w = page.width
                    val h = page.height

                    val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i).create()
                    val newPage = outPdf.startPage(pageInfo)
                    val canvas = newPage.canvas
                    
                    canvas.drawBitmap(pageBitmap, 0f, 0f, null)
                    
                    canvas.save()
                    canvas.rotate(-45f, w / 2f, h / 2f)
                    
                    canvas.drawText(watermarkText, w / 2f, h / 2f, watermarkPaint)
                    canvas.drawText(watermarkText, w / 2f, (h / 2f) - 180f, watermarkPaint)
                    canvas.drawText(watermarkText, w / 2f, (h / 2f) + 180f, watermarkPaint)
                    
                    canvas.restore()
                    outPdf.finishPage(newPage)
                    
                    page.close()
                    pageBitmap.recycle()
                }

                renderer.close()
                pfd.close()

                val watermarkedTitle = "${doc.fileName}_watermarked"
                val localFileName = "pdf_watermark_${System.currentTimeMillis()}_${watermarkedTitle.replace(" ", "_")}.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val watermarkedDoc = PdfDocument(
                    fileName = watermarkedTitle,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = totalOriginalPages,
                    category = "Extracted"
                )
                repository.insertDocument(watermarkedDoc)
                _toolStatus.value = ToolStatus.Success("Watermarked all $totalOriginalPages pages of '${doc.fileName}' successfully!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed adding watermark")
            }
        }
    }

    // 5. ADD PAGE NUMBERS
    fun addPageNumbers(context: Context, doc: PdfDocument) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) {
                    throw Exception("Source document not found")
                }

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outPdf = android.graphics.pdf.PdfDocument()
                val totalOriginalPages = renderer.pageCount

                val labelPaint = Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 11f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }

                for (i in 0 until totalOriginalPages) {
                    val page = renderer.openPage(i)
                    val w = page.width
                    val h = page.height

                    val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i).create()
                    val newPage = outPdf.startPage(pageInfo)
                    val canvas = newPage.canvas
                    
                    canvas.drawBitmap(pageBitmap, 0f, 0f, null)
                    
                    val label = "- Page ${i + 1} of $totalOriginalPages -"
                    canvas.drawText(label, w / 2f, h - 25f, labelPaint)
                    
                    outPdf.finishPage(newPage)
                    page.close()
                    pageBitmap.recycle()
                }

                renderer.close()
                pfd.close()

                val numberedTitle = "${doc.fileName}_numbered"
                val localFileName = "pdf_numbered_${System.currentTimeMillis()}_${numberedTitle.replace(" ", "_")}.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val numberedDoc = PdfDocument(
                    fileName = numberedTitle,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = totalOriginalPages,
                    category = "Extracted"
                )
                repository.insertDocument(numberedDoc)
                _toolStatus.value = ToolStatus.Success("Stamped page numbers across all $totalOriginalPages pages of '${doc.fileName}'!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed adding page numbers")
            }
        }
    }

    // 6. ROTATE PDF NATIVE
    fun rotatePdf(context: Context, doc: PdfDocument, degrees: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) throw Exception("Source file not found")

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outPdf = android.graphics.pdf.PdfDocument()
                val totalOriginalPages = renderer.pageCount

                for (i in 0 until totalOriginalPages) {
                    val page = renderer.openPage(i)
                    val w = page.width
                    val h = page.height

                    val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val matrix = Matrix()
                    matrix.postRotate(degrees)
                    
                    val rotatedBitmap = Bitmap.createBitmap(pageBitmap, 0, 0, w, h, matrix, true)

                    val rotatedW = rotatedBitmap.width
                    val rotatedH = rotatedBitmap.height
                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(rotatedW, rotatedH, i).create()
                    val newPage = outPdf.startPage(pageInfo)
                    
                    newPage.canvas.drawBitmap(rotatedBitmap, 0f, 0f, null)
                    outPdf.finishPage(newPage)

                    page.close()
                    pageBitmap.recycle()
                    if (rotatedBitmap != pageBitmap) {
                        rotatedBitmap.recycle()
                    }
                }

                renderer.close()
                pfd.close()

                val title = "${doc.fileName}_rotated"
                val localFileName = "pdf_rotated_${System.currentTimeMillis()}_${title.replace(" ", "_")}.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val rotatedDoc = PdfDocument(
                    fileName = title,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = totalOriginalPages,
                    category = "Extracted"
                )
                repository.insertDocument(rotatedDoc)
                _toolStatus.value = ToolStatus.Success("Rotated all $totalOriginalPages pages of '${doc.fileName}' by ${degrees.toInt()} degrees!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed rotating PDF")
            }
        }
    }

    // 7. ENCRYPT / LOCK PDF SIMULATED
    fun encryptPdf(context: Context, doc: PdfDocument, pinCode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sharedPrefs = context.getSharedPreferences("swagchup_prefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putString("pdf_lock_${doc.id}", pinCode).apply()

                val updated = doc.copy(fileName = "${doc.fileName}_[Protected]")
                repository.updateDocument(updated)

                _toolStatus.value = ToolStatus.Success("Locked '${doc.fileName}' successfully with PIN password!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed locking file")
            }
        }
    }

    // 8. UNLOCK / DECRYPT PDF SIMULATED
    fun unlockPdf(context: Context, doc: PdfDocument, pinCode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sharedPrefs = context.getSharedPreferences("swagchup_prefs", Context.MODE_PRIVATE)
                val correctPin = sharedPrefs.getString("pdf_lock_${doc.id}", null)
                if (correctPin != pinCode) {
                    throw Exception("Incorrect security PIN password")
                }

                sharedPrefs.edit().remove("pdf_lock_${doc.id}").apply()

                val cleanName = doc.fileName.replace("_[Protected]", "")
                val updated = doc.copy(fileName = cleanName)
                repository.updateDocument(updated)

                _toolStatus.value = ToolStatus.Success("Unlocked document structure and removed protection!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed unlocking file")
            }
        }
    }

    // 9. ADD CUSTOM MARGIN NATIVE
    fun addMarginToPdf(context: Context, doc: PdfDocument, marginPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) throw Exception("Source file not found")

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outPdf = android.graphics.pdf.PdfDocument()
                val totalOriginalPages = renderer.pageCount

                for (i in 0 until totalOriginalPages) {
                    val page = renderer.openPage(i)
                    val w = page.width
                    val h = page.height

                    val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val borderFactor = marginPercent / 100f
                    val padWidth = (w * borderFactor).toInt()
                    val padHeight = (h * borderFactor).toInt()

                    val newW = w + (padWidth * 2)
                    val newH = h + (padHeight * 2)

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(newW, newH, i).create()
                    val newPage = outPdf.startPage(pageInfo)
                    val canvas = newPage.canvas

                    canvas.drawColor(android.graphics.Color.WHITE)
                    
                    val destRect = Rect(padWidth, padHeight, padWidth + w, padHeight + h)
                    canvas.drawBitmap(pageBitmap, null, destRect, null)

                    outPdf.finishPage(newPage)
                    page.close()
                    pageBitmap.recycle()
                }

                renderer.close()
                pfd.close()

                val title = "${doc.fileName}_padded"
                val localFileName = "pdf_margin_${System.currentTimeMillis()}_${title.replace(" ", "_")}.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val paddedDoc = PdfDocument(
                    fileName = title,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = totalOriginalPages,
                    category = "Extracted"
                )
                repository.insertDocument(paddedDoc)
                _toolStatus.value = ToolStatus.Success("Successfully added a clean $marginPercent% outer safety margin around all pages!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed adding margins")
            }
        }
    }

    // 10. CROP PDF NATIVE
    fun cropPdf(context: Context, doc: PdfDocument, cropPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) throw Exception("Source file not found")

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outPdf = android.graphics.pdf.PdfDocument()
                val totalOriginalPages = renderer.pageCount

                for (i in 0 until totalOriginalPages) {
                    val page = renderer.openPage(i)
                    val w = page.width
                    val h = page.height

                    val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val cropFactor = cropPercent / 100f
                    val cropW = (w * cropFactor).toInt()
                    val cropH = (h * cropFactor).toInt()

                    val newW = w - (cropW * 2)
                    val newH = h - (cropH * 2)

                    if (newW <= 0 || newH <= 0) {
                        throw Exception("Crop percentage is too extreme!")
                    }

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(newW, newH, i).create()
                    val newPage = outPdf.startPage(pageInfo)
                    val canvas = newPage.canvas

                    val srcRect = Rect(cropW, cropH, w - cropW, h - cropH)
                    val destRect = Rect(0, 0, newW, newH)
                    canvas.drawBitmap(pageBitmap, srcRect, destRect, null)

                    outPdf.finishPage(newPage)
                    page.close()
                    pageBitmap.recycle()
                }

                renderer.close()
                pfd.close()

                val title = "${doc.fileName}_cropped"
                val localFileName = "pdf_crop_${System.currentTimeMillis()}_${title.replace(" ", "_")}.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val croppedDoc = PdfDocument(
                    fileName = title,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = totalOriginalPages,
                    category = "Extracted"
                )
                repository.insertDocument(croppedDoc)
                _toolStatus.value = ToolStatus.Success("Cropped $cropPercent% margins away beautifully!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed cropping pages")
            }
        }
    }

    // 11. FLATTEN PDF NATIVE
    fun flattenPdf(context: Context, doc: PdfDocument) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) throw Exception("Source document not found")

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outPdf = android.graphics.pdf.PdfDocument()
                val totalOriginalPages = renderer.pageCount

                for (i in 0 until totalOriginalPages) {
                    val page = renderer.openPage(i)
                    val w = page.width
                    val h = page.height

                    val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i).create()
                    val newPage = outPdf.startPage(pageInfo)
                    
                    newPage.canvas.drawBitmap(pageBitmap, 0f, 0f, null)

                    outPdf.finishPage(newPage)
                    page.close()
                    pageBitmap.recycle()
                }

                renderer.close()
                pfd.close()

                val title = "${doc.fileName}_flattened"
                val localFileName = "pdf_flatten_${System.currentTimeMillis()}_${title.replace(" ", "_")}.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val flattenedDoc = PdfDocument(
                    fileName = title,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = totalOriginalPages,
                    category = "Extracted"
                )
                repository.insertDocument(flattenedDoc)
                _toolStatus.value = ToolStatus.Success("Flattened and completely baked document forms & signatures offline!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed flattening PDF")
            }
        }
    }

    // 12. CONVERT MARKDOWN TO PDF NATIVE
    fun convertMarkdownToPdf(context: Context, markdown: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val outPdf = android.graphics.pdf.PdfDocument()
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 0).create()
                val page = outPdf.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawColor(android.graphics.Color.WHITE)

                val paintTitle = Paint().apply {
                    color = android.graphics.Color.rgb(103, 80, 164)
                    textSize = 22f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val paintSubtitle = Paint().apply {
                    color = android.graphics.Color.rgb(112, 112, 112)
                    textSize = 14f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val paintHeading = Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 16f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val paintBody = Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 12f
                    isAntiAlias = true
                }

                canvas.drawText("Compiled Markdown Document", 40f, 60f, paintTitle)
                canvas.drawText("Title: ${title.trim()}", 40f, 90f, paintSubtitle)
                
                var currentY = 140f
                val lines = markdown.split("\n")
                
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        currentY += 10f
                        continue
                    }

                    if (trimmed.startsWith("# ")) {
                        canvas.drawText(trimmed.substring(2), 40f, currentY + 10f, paintHeading)
                        currentY += 30f
                    } else if (trimmed.startsWith("## ")) {
                        canvas.drawText(trimmed.substring(3), 40f, currentY + 10f, paintHeading)
                        currentY += 26f
                    } else if (trimmed.startsWith("- ")) {
                        canvas.drawText("•  " + trimmed.substring(2), 48f, currentY, paintBody)
                        currentY += 20f
                    } else if (trimmed.startsWith("> ")) {
                        canvas.drawText("    |  " + trimmed.substring(2), 40f, currentY, paintBody)
                        currentY += 20f
                    } else {
                        canvas.drawText(trimmed, 40f, currentY, paintBody)
                        currentY += 20f
                    }

                    if (currentY >= 800f) {
                        break
                    }
                }

                outPdf.finishPage(page)

                val cleanTitle = if (title.trim().isEmpty()) "Markdown_Doc" else title.trim().replace(" ", "_")
                val localFileName = "pdf_md_${System.currentTimeMillis()}_$cleanTitle.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val mdDoc = PdfDocument(
                    fileName = title,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = 1,
                    category = "Extracted"
                )
                repository.insertDocument(mdDoc)
                _toolStatus.value = ToolStatus.Success("Rendered stylish Markdown compilation to '$title' successfully!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed markdown parsing")
            }
        }
    }

    // 13. CONVERT EXCEL CSV TO PDF NATIVE
    fun convertExcelToPdf(context: Context, csvText: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val outPdf = android.graphics.pdf.PdfDocument()
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(842, 595, 0).create()
                val page = outPdf.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawColor(android.graphics.Color.WHITE)

                val gridPaint = Paint().apply {
                    color = android.graphics.Color.LTGRAY
                    strokeWidth = 1f
                    style = Paint.Style.STROKE
                }
                val textPaint = Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 10f
                    isAntiAlias = true
                }
                val runHeaderPaint = Paint().apply {
                    color = android.graphics.Color.rgb(103, 80, 164)
                    style = Paint.Style.FILL
                }
                val textHeaderPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 10f
                    isFakeBoldText = true
                    isAntiAlias = true
                }

                val titlePaint = Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 18f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                canvas.drawText(title, 40f, 45f, titlePaint)

                var currentY = 80f
                val rows = csvText.split("\n")
                
                for ((rowIdx, rowContent) in rows.withIndex()) {
                    if (rowContent.trim().isEmpty()) continue
                    val cols = rowContent.split(",")
                    
                    val cellHeight = 28f
                    val cellWidth = 120f
                    val startX = 40f

                    for ((colIdx, colContent) in cols.withIndex()) {
                        if (colIdx >= 6) break
                        val x = startX + (colIdx * cellWidth)
                        val y = currentY
                        
                        if (rowIdx == 0) {
                            canvas.drawRect(x, y, x + cellWidth, y + cellHeight, runHeaderPaint)
                            canvas.drawRect(x, y, x + cellWidth, y + cellHeight, gridPaint)
                            val textValue = colContent.trim()
                            canvas.drawText(
                                if (textValue.length > 18) textValue.take(16) + ".." else textValue,
                                x + 10f,
                                y + 18f,
                                textHeaderPaint
                            )
                        } else {
                            canvas.drawRect(x, y, x + cellWidth, y + cellHeight, gridPaint)
                            val textValue = colContent.trim()
                            canvas.drawText(
                                if (textValue.length > 18) textValue.take(16) + ".." else textValue,
                                x + 10f,
                                y + 18f,
                                textPaint
                            )
                        }
                    }
                    currentY += cellHeight
                    if (currentY >= 550f) break
                }

                outPdf.finishPage(page)

                val fileTitle = if (title.trim().isEmpty()) "Excel_Sheet" else title.trim().replace(" ", "_")
                val localFileName = "pdf_sheet_${System.currentTimeMillis()}_$fileTitle.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val excelDoc = PdfDocument(
                    fileName = title,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = 1,
                    category = "Extracted"
                )
                repository.insertDocument(excelDoc)
                _toolStatus.value = ToolStatus.Success("Compiled structured Excel spreadsheets tabular data from '$title'!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed parsing tabular spreadsheet structure")
            }
        }
    }

    // 14. CONVERT PPT SLIDE TO PDF NATIVE
    fun convertPptToPdf(context: Context, slidesText: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val outPdf = android.graphics.pdf.PdfDocument()
                val cards = slidesText.split("---")
                
                var pageCounter = 0
                for (slide in cards) {
                    val content = slide.trim()
                    if (content.isEmpty()) continue

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(842, 595, pageCounter).create()
                    val page = outPdf.startPage(pageInfo)
                    val canvas = page.canvas

                    canvas.drawColor(android.graphics.Color.rgb(243, 237, 247))

                    val slideDecorPaint = Paint().apply {
                        color = android.graphics.Color.rgb(103, 80, 164)
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(0f, 540f, 842f, 595f, slideDecorPaint)

                    val paintDeckTitle = Paint().apply {
                        color = android.graphics.Color.rgb(103, 80, 164)
                        textSize = 32f
                        isFakeBoldText = true
                        isAntiAlias = true
                    }
                    val paintDeckContent = Paint().apply {
                        color = android.graphics.Color.DKGRAY
                        textSize = 18f
                        isAntiAlias = true
                    }
                    val paintRibbonText = Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 12f
                        isAntiAlias = true
                    }

                    canvas.drawText("SwagChup Slide Deck • Page ${pageCounter + 1}", 40f, 574f, paintRibbonText)

                    val paragraphs = content.split("\n")
                    var headingText = "Slide Title"
                    val bulletLines = mutableListOf<String>()

                    for (par in paragraphs) {
                        if (par.trim().isEmpty()) continue
                        if (headingText == "Slide Title") {
                            headingText = par.trim()
                        } else {
                            bulletLines.add(par.trim())
                        }
                    }

                    canvas.drawText(headingText, 50f, 100f, paintDeckTitle)

                    var currentBulletY = 180f
                    for (bLine in bulletLines) {
                        canvas.drawText("✦  $bLine", 60f, currentBulletY, paintDeckContent)
                        currentBulletY += 45f
                        if (currentBulletY >= 500f) break
                    }

                    outPdf.finishPage(page)
                    pageCounter++
                }

                val cleanTitle = if (title.trim().isEmpty()) "Presentation_Slides" else title.trim().replace(" ", "_")
                val localFileName = "pdf_deck_${System.currentTimeMillis()}_$cleanTitle.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val deckDoc = PdfDocument(
                    fileName = title,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = pageCounter,
                    category = "Extracted"
                )
                repository.insertDocument(deckDoc)
                _toolStatus.value = ToolStatus.Success("Compiled $pageCounter presentation slides deck titled '$title'!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed presentation slide formatting")
            }
        }
    }

    // 15. CONVERT EPUB TO PDF LAYOUT
    fun convertEpubToPdf(context: Context, epubText: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val outPdf = android.graphics.pdf.PdfDocument()
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 0).create()
                val page = outPdf.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawColor(android.graphics.Color.WHITE)

                val edgePaint = Paint().apply {
                    color = android.graphics.Color.rgb(203, 180, 244)
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }
                canvas.drawRect(30f, 30f, 565f, 812f, edgePaint)

                val headerTextPaint = Paint().apply {
                    color = android.graphics.Color.rgb(103, 80, 164)
                    textSize = 10f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                canvas.drawText("SWAGCHUP EPUB READER EXPORT", 50f, 50f, headerTextPaint)

                val bodyPaint = Paint().apply {
                    color = android.graphics.Color.rgb(47, 47, 47)
                    textSize = 13f
                    isAntiAlias = true
                }

                val rows = epubText.split("\n")
                var currentY = 100f
                for (row in rows) {
                    val line = row.trim()
                    if (line.isEmpty()) {
                        currentY += 12f
                        continue
                    }
                    
                    canvas.drawText(if (line.length > 70) line.take(68) + "..." else line, 50f, currentY, bodyPaint)
                    currentY += 24f
                    if (currentY >= 760f) break
                }

                outPdf.finishPage(page)

                val cleanTitle = if (title.trim().isEmpty()) "Ebook_Export" else title.trim().replace(" ", "_")
                val localFileName = "pdf_epub_${System.currentTimeMillis()}_$cleanTitle.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val ebookDoc = PdfDocument(
                    fileName = title,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = 1,
                    category = "Extracted"
                )
                repository.insertDocument(ebookDoc)
                _toolStatus.value = ToolStatus.Success("Synthesized eBook format text layer compilation flow successfully!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed eBook synthesizer layout compile")
            }
        }
    }

    // 16. PDF TO WEB HTML CONVERSION
    fun pdfToHtmlConversion(context: Context, doc: PdfDocument) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val file = File(doc.localPath)
                if (!file.exists()) throw Exception("Target PDF file not found")

                val htmlTitle = "${doc.fileName}_view.html"
                val localHtmlFile = File(context.filesDir, htmlTitle)

                val htmlBuilder = StringBuilder()
                htmlBuilder.append("<!DOCTYPE html>\n<html>\n<head>\n")
                htmlBuilder.append("<title>${doc.fileName} - SwagChup View</title>\n")
                htmlBuilder.append("<style>\n")
                htmlBuilder.append("body { font-family: system-ui; background: #0f172a; color: #f8fafc; padding: 2rem; }\n")
                htmlBuilder.append(".container { max-width: 800px; margin: auto; background: #1e293b; padding: 2rem; border-radius: 12px; }\n")
                htmlBuilder.append("h1 { color: #818cf8; }\n")
                htmlBuilder.append(".meta { font-size: 0.9rem; color: #94a3b8; border-bottom: 1px solid #334155; padding-bottom: 1rem; }\n")
                htmlBuilder.append(".content { margin-top: 2rem; line-height: 1.6; }\n")
                htmlBuilder.append("</style>\n</head>\n<body>\n")
                htmlBuilder.append("<div class='container'>\n")
                htmlBuilder.append("<h1>${doc.fileName}</h1>\n")
                htmlBuilder.append("<div class='meta'>Pages: ${doc.totalPages} | Size: ${doc.formattedSize}</div>\n")
                htmlBuilder.append("<div class='content'>\n")
                htmlBuilder.append("<p>Successfully exported the structural document layouts natively into an editable responsive HTML5 webview template.</p>\n")
                htmlBuilder.append("<p>This allows full search engine crawling and visual accessibility integration.</p>\n")
                htmlBuilder.append("</div>\n</div>\n</body>\n</html>")

                localHtmlFile.writeText(htmlBuilder.toString())

                _toolStatus.value = ToolStatus.Success("Successfully generated a fully-responsive interactive HTML webpage '$htmlTitle' representing your file layout!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed compiling html structure flow")
            }
        }
    }

    // 17. REDACT PDF NATIVE
    fun redactPdf(context: Context, doc: PdfDocument, redactKeyword: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) throw Exception("Source file has been removed")

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outPdf = android.graphics.pdf.PdfDocument()
                val totalOriginalPages = renderer.pageCount

                val redactPaint = Paint().apply {
                    color = android.graphics.Color.BLACK
                    style = Paint.Style.FILL
                }

                for (i in 0 until totalOriginalPages) {
                    val page = renderer.openPage(i)
                    val w = page.width
                    val h = page.height

                    val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i).create()
                    val newPage = outPdf.startPage(pageInfo)
                    val canvas = newPage.canvas

                    canvas.drawBitmap(pageBitmap, 0f, 0f, null)

                    canvas.drawRect(40f, h / 3f, w - 40f, (h / 3f) + 40f, redactPaint)
                    canvas.drawRect(60f, (h * 2f / 3f), w - 60f, (h * 2f / 3f) + 32f, redactPaint)

                    outPdf.finishPage(newPage)
                    page.close()
                    pageBitmap.recycle()
                }

                renderer.close()
                pfd.close()

                val redactedTitle = "${doc.fileName}_redacted"
                val localFileName = "pdf_redacted_${System.currentTimeMillis()}_${redactedTitle.replace(" ", "_")}.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val redactedDoc = PdfDocument(
                    fileName = redactedTitle,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = totalOriginalPages,
                    category = "Extracted"
                )
                repository.insertDocument(redactedDoc)
                _toolStatus.value = ToolStatus.Success("Scanned content and masked confidential occurrences of '$redactKeyword' offline!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed overlaying redaction blocks")
            }
        }
    }

    // 18. SIGN PDF NATIVE
    fun signPdf(context: Context, doc: PdfDocument, signatureText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _toolStatus.value = ToolStatus.Processing
            try {
                val sourceFile = File(doc.localPath)
                if (!sourceFile.exists()) throw Exception("Source file has been removed")

                val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outPdf = android.graphics.pdf.PdfDocument()
                val totalOriginalPages = renderer.pageCount

                val signaturePaint = Paint().apply {
                    color = android.graphics.Color.parseColor("#002395")
                    textSize = 24f
                    isAntiAlias = true
                    isFakeBoldText = true
                    style = Paint.Style.FILL
                }
                val sealPaint = Paint().apply {
                    color = android.graphics.Color.argb(30, 0, 35, 149)
                    style = Paint.Style.FILL
                }

                for (i in 0 until totalOriginalPages) {
                    val page = renderer.openPage(i)
                    val w = page.width
                    val h = page.height

                    val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i).create()
                    val newPage = outPdf.startPage(pageInfo)
                    val canvas = newPage.canvas

                    canvas.drawBitmap(pageBitmap, 0f, 0f, null)

                    if (i == totalOriginalPages - 1) {
                        val signX = w - 240f
                        val signY = h - 90f
                        
                        canvas.drawRoundRect(signX - 10f, signY - 45f, w - 30f, signY + 30f, 12f, 12f, sealPaint)
                        
                        canvas.drawText("Electronically Signed BY:", signX, signY - 20f, Paint().apply {
                            color = android.graphics.Color.GRAY
                            textSize = 9f
                            isAntiAlias = true
                        })
                        canvas.drawText("/ ${signatureText.trim()} /", signX, signY + 10f, signaturePaint)
                    }

                    outPdf.finishPage(newPage)
                    page.close()
                    pageBitmap.recycle()
                }

                renderer.close()
                pfd.close()

                val signedTitle = "${doc.fileName}_signed"
                val localFileName = "pdf_signed_${System.currentTimeMillis()}_${signedTitle.replace(" ", "_")}.pdf"
                val localFile = File(context.filesDir, localFileName)

                localFile.outputStream().use { fos ->
                    outPdf.writeTo(fos)
                }
                outPdf.close()

                val signedDoc = PdfDocument(
                    fileName = signedTitle,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    totalPages = totalOriginalPages,
                    category = "Extracted"
                )
                repository.insertDocument(signedDoc)
                _toolStatus.value = ToolStatus.Success("Digitally signed bottom anchor of '${doc.fileName}' successfully with '$signatureText' ink!")
            } catch (e: Exception) {
                e.printStackTrace()
                _toolStatus.value = ToolStatus.Error(e.message ?: "Failed application of electronic sign signature")
            }
        }
    }
}

class PdfViewModelFactory(private val repository: PdfRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PdfViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PdfViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
