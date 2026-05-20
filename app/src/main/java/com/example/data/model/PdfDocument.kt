package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_documents")
data class PdfDocument(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val localPath: String, // Path in app's internal filesDir
    val fileSize: Long,
    val totalPages: Int,
    val addedDate: Long = System.currentTimeMillis(),
    val lastReadDate: Long = System.currentTimeMillis(),
    val lastReadPage: Int = 0,
    val category: String = "General", // General, Work, Study, Personal, etc.
    val isFavorite: Boolean = false
) {
    val formattedSize: String
        get() {
            val kb = fileSize / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format("%.2f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$fileSize Bytes"
            }
        }
}
