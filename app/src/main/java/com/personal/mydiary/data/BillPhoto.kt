package com.personal.mydiary.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads a bill photo on the phone with ML Kit text recognition. */
object BillPhoto {
    /** A file the camera app can save a new bill photo into. */
    fun newPhotoUri(context: Context): Uri {
        val dir = File(context.cacheDir, "bills").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "bill-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    suspend fun read(context: Context, uri: Uri): ParsedBill {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            return ReceiptText.parse(rowsOf(text))
        } finally {
            recognizer.close()
        }
    }

    /**
     * Puts recognised lines that sit side by side into one row, left to right. A bill prints the name
     * on the left and the figures on the right, and recognition often returns them as separate lines.
     */
    private fun rowsOf(text: Text): List<String> {
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { it to line.text } }
            .sortedBy { it.first.centerY() }
        val rows = mutableListOf<MutableList<Pair<android.graphics.Rect, String>>>()
        for (line in lines) {
            val row = rows.lastOrNull()
            val box = line.first
            if (row != null) {
                val ref = row.first().first
                if (kotlin.math.abs(box.centerY() - ref.centerY()) < minOf(box.height(), ref.height()) * 0.6) {
                    row += line
                    continue
                }
            }
            rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.first.left }.joinToString(" ") { it.second } }
    }
}
