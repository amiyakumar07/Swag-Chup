package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
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
