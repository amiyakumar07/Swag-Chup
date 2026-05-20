package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.PdfBookmark
import com.example.data.model.PdfDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDao {
    @Query("SELECT * FROM pdf_documents ORDER BY lastReadDate DESC")
    fun getAllDocuments(): Flow<List<PdfDocument>>

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    suspend fun getDocumentByIdDirect(id: Int): PdfDocument?

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    fun getDocumentById(id: Int): Flow<PdfDocument?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: PdfDocument): Long

    @Update
    suspend fun updateDocument(document: PdfDocument)

    @Query("DELETE FROM pdf_documents WHERE id = :id")
    suspend fun deleteDocumentById(id: Int)

    @Query("SELECT * FROM pdf_bookmarks WHERE pdfId = :pdfId ORDER BY pageNumber ASC")
    fun getBookmarksForPdf(pdfId: Int): Flow<List<PdfBookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: PdfBookmark): Long

    @Query("DELETE FROM pdf_bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Int)

    @Query("DELETE FROM pdf_bookmarks WHERE pdfId = :pdfId AND pageNumber = :pageNumber")
    suspend fun deleteBookmarkAtPage(pdfId: Int, pageNumber: Int)
}
