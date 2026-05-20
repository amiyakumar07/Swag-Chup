package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pdf_bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = PdfDocument::class,
            parentColumns = ["id"],
            childColumns = ["pdfId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["pdfId"])]
)
data class PdfBookmark(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pdfId: Int,
    val pageNumber: Int,
    val label: String, // E.g., "Key Point", "Diagram"
    val note: String = "", // Optional text note
    val timestamp: Long = System.currentTimeMillis()
)
