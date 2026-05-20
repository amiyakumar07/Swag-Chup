package com.example.data.repository

import com.example.data.db.PdfDao
import com.example.data.model.PdfBookmark
import com.example.data.model.PdfDocument
import kotlinx.coroutines.flow.Flow

class PdfRepository(private val pdfDao: PdfDao) {
    val allDocuments: Flow<List<PdfDocument>> = pdfDao.getAllDocuments()

    fun getDocumentById(id: Int): Flow<PdfDocument?> {
        return pdfDao.getDocumentById(id)
    }

    suspend fun getDocumentByIdDirect(id: Int): PdfDocument? {
        return pdfDao.getDocumentByIdDirect(id)
    }

    suspend fun insertDocument(document: PdfDocument): Long {
        return pdfDao.insertDocument(document)
    }

    suspend fun updateDocument(document: PdfDocument) {
        pdfDao.updateDocument(document)
    }

    suspend fun deleteDocument(id: Int) {
        pdfDao.deleteDocumentById(id)
    }

    fun getBookmarks(pdfId: Int): Flow<List<PdfBookmark>> {
        return pdfDao.getBookmarksForPdf(pdfId)
    }

    suspend fun insertBookmark(bookmark: PdfBookmark): Long {
        return pdfDao.insertBookmark(bookmark)
    }

    suspend fun deleteBookmark(id: Int) {
        pdfDao.deleteBookmark(id)
    }

    suspend fun deleteBookmarkAtPage(pdfId: Int, pageNumber: Int) {
        pdfDao.deleteBookmarkAtPage(pdfId, pageNumber)
    }
}
