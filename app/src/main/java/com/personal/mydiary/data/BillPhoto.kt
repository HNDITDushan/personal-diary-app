package com.personal.mydiary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads a bill photo on the phone; nothing is uploaded. ML Kit finds the rows and reads figures and English
 * well but can't read Sinhala, so each item's name line is read again with Tesseract in Sinhala and English.
 */
object BillPhoto {
    /** Text reads best when lines are a few dozen pixels tall; bigger photos only slow it down. */
    private const val MAX_SIDE = 2400

    private class Row(val box: Rect, val text: String)

    /** A file the camera app can save a new bill photo into. */
    fun newPhotoUri(context: Context): Uri {
        val dir = File(context.cacheDir, "bills").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "bill-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    suspend fun read(context: Context, uri: Uri): ParsedBill = withContext(Dispatchers.Default) {
        val bitmap = load(context, uri)
        try {
            val rows = rowsOf(recognize(bitmap))
            ReceiptText.parse(withNames(context, bitmap, rows))
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): Text {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
    }

    /**
     * Puts recognised lines that sit side by side into one row, left to right. A bill prints the name
     * on the left and the figures on the right, and recognition often returns them as separate lines.
     */
    private fun rowsOf(text: Text): List<Row> {
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { it to line.text } }
            .sortedBy { it.first.centerY() }
        val rows = mutableListOf<MutableList<Pair<Rect, String>>>()
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
        return rows.map { row ->
            val parts = row.sortedBy { it.first.left }
            val box = Rect(parts.first().first).apply { parts.forEach { union(it.first) } }
            Row(box, parts.joinToString(" ") { it.second })
        }
    }

    /**
     * Where a row holds only an item's figures, its name is on the line above. That strip of the photo is read
     * with Tesseract; a Sinhala name replaces whatever ML Kit made of it, and English names are left to ML Kit.
     */
    private fun withNames(context: Context, bitmap: Bitmap, rows: List<Row>): List<String> {
        if (rows.none { ReceiptText.isFiguresOnly(it.text) }) return rows.map { it.text }
        // Names start left of the figures, about where the bill's other text starts; going further takes in the background.
        val left = (rows.minOf { it.box.left } - bitmap.width / 40).coerceAtLeast(0)
        val right = (rows.maxOf { it.box.right } + bitmap.width / 40).coerceAtMost(bitmap.width)
        val api = TessBaseAPI()
        check(api.init(modelDir(context).absolutePath, "sin+eng")) { "The Sinhala text reader couldn't start" }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        try {
            val out = mutableListOf<String>()
            val between = mutableListOf<Row>()
            var lastFiguresBottom: Int? = null
            for (row in rows) {
                if (!ReceiptText.isFiguresOnly(row.text)) {
                    between += row
                    continue
                }
                val h = row.box.height()
                // The name is the line between the item above and these figures.
                val top = maxOf(lastFiguresBottom ?: 0, row.box.top - h * 3, 0)
                val bottom = row.box.top
                // The figures row ends inside the paper; past it is background.
                val name = if (bottom - top > h / 2) readStrip(api, bitmap, Rect(left, top, minOf(right, row.box.right), bottom)) else null
                // A Sinhala name replaces what ML Kit made of that strip; lines above it (shop, date) stay.
                if (name != null) {
                    out += between.filter { it.box.centerY() < top }.map { it.text }
                    out += name
                } else {
                    out += between.map { it.text }
                }
                between.clear()
                out += row.text
                lastFiguresBottom = row.box.bottom
            }
            out += between.map { it.text }
            return out
        } finally {
            api.recycle()
        }
    }

    /**
     * The Sinhala name in a strip of the photo, or null when none reads clearly. The strip is read as it is and
     * cleaned up, and the reading Tesseract is surer of is kept; an unsure one is left for you to type.
     */
    private fun readStrip(api: TessBaseAPI, bitmap: Bitmap, box: Rect): String? {
        val plain = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        val readings = listOf(plain.copy(Bitmap.Config.ARGB_8888, false), clean(plain)).map { img ->
            try {
                api.setImage(img)
                val text = sinhalaName(api.utF8Text.orEmpty())
                val confidence = api.meanConfidence()
                text to confidence
            } finally {
                api.clear()
                img.recycle()
            }
        }
        // Even an unsure reading is a better start than "Item 4"; it's checked, and matched to past names, before saving.
        return readings.filter { it.first != null }.maxByOrNull { it.second }?.first
    }

    /** The Sinhala words of the last line that has any; stray Latin letters around them are misread specks. */
    private fun sinhalaName(text: String): String? = text.lines()
        .filterNot { line -> listOf("Kg", "Pcs", "මිල").any { it in line } }
        .map { line ->
            // Words of one or two letters next to a name are nearly always specks read as letters.
            line.split(Regex("\\s+")).filter { w -> w.count(ReceiptText::isSinhala) >= 3 }
                .joinToString(" ") { w -> w.filter { ReceiptText.isSinhala(it) || it == '\u200D' } }
        }
        .lastOrNull { it.isNotBlank() }

    /**
     * Faint thermal print reads better made bigger and turned into plain black on white: the strip is doubled in
     * size, split into ink and paper at the level that best separates them (Otsu's method), and given a margin.
     */
    private fun clean(strip: Bitmap): Bitmap {
        val w = strip.width * 2
        val h = strip.height * 2
        val big = Bitmap.createScaledBitmap(strip, w, h, true)
        if (big !== strip) strip.recycle()
        val px = IntArray(w * h)
        big.getPixels(px, 0, w, 0, 0, w, h)
        big.recycle()
        val grey = IntArray(px.size) { px[it] and 0xFF }
        val hist = IntArray(256).also { hist -> grey.forEach { hist[it]++ } }
        val total = grey.size
        val sumAll = hist.indices.sumOf { it.toLong() * hist[it] }
        var sumBack = 0L
        var back = 0
        var best = 0.0
        var level = 128
        for (t in 0 until 256) {
            back += hist[t]
            if (back == 0) continue
            val fore = total - back
            if (fore == 0) break
            sumBack += t.toLong() * hist[t]
            val meanBack = sumBack.toDouble() / back
            val meanFore = (sumAll - sumBack).toDouble() / fore
            val between = back.toDouble() * fore * (meanBack - meanFore) * (meanBack - meanFore)
            if (between > best) { best = between; level = t }
        }
        // Trim to the ink, so the name isn't lost in a wide empty strip; specks at the edges are ignored.
        val ink = BooleanArray(px.size) { grey[it] <= level }
        val colInk = IntArray(w).also { c -> for (y in 0 until h) for (x in 0 until w) if (ink[y * w + x]) c[x]++ }
        // Columns with ink form pieces; the paper's edge is a thin bar and the background past it a solid block,
        // so only pieces shaped like writing are kept.
        val minRun = maxOf(1, h / 20)
        val pieces = mutableListOf<IntRange>()
        var x = 0
        while (x < w) {
            if (colInk[x] < minRun) { x++; continue }
            var end = x
            var gap = 0
            var k = x
            while (k < w && gap < h / 3) { if (colInk[k] >= minRun) { end = k; gap = 0 } else gap++; k++ }
            pieces += x..end
            x = end + 1
        }
        val writing = pieces.filter { r ->
            val width = r.last - r.first + 1
            val density = r.sumOf { colInk[it] }.toDouble() / (width * h)
            width >= h / 4 && density < 0.45
        }
        val x0 = writing.firstOrNull()?.first ?: 0
        val x1 = writing.lastOrNull()?.last ?: (w - 1)
        val cw = (x1 - x0 + 1).coerceAtLeast(1)
        val pad = h / 4
        val out = Bitmap.createBitmap(cw + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        out.eraseColor(android.graphics.Color.WHITE)
        val bw = IntArray(cw * h) { i -> if (ink[(i / cw) * w + x0 + i % cw]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        out.setPixels(bw, 0, cw, pad, pad, cw, h)
        return out
    }

    /** Copies the language files out of the app, once, to where Tesseract can open them. */
    private fun modelDir(context: Context): File {
        val root = File(context.filesDir, "tesseract")
        val dir = File(root, "tessdata").apply { mkdirs() }
        for (name in context.assets.list("tessdata").orEmpty()) {
            val out = File(dir, name)
            val size = context.assets.openFd("tessdata/$name").use { it.length }
            if (out.length() != size) {
                context.assets.open("tessdata/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        return root
    }

    /** The photo upright, no bigger than needed, in shades of grey. */
    private fun load(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("The photo couldn't be opened")
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        val grey = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
        Canvas(grey).drawBitmap(upright, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
        upright.recycle()
        return grey
    }
}
