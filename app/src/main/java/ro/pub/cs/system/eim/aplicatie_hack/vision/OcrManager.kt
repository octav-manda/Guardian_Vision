package ro.pub.cs.system.eim.aplicatie_hack.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.min

class OcrManager {

    companion object {
        private const val TAG               = "OcrManager"
        private const val SKIP_EVERY        = 5
        private const val MIN_TEXT_LENGTH   = 3
        private const val MIN_CONFIDENCE    = 0.7f
        private const val CHANGE_RATIO      = 0.20f
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var frameCounter = 0

    @Volatile var lastOcrText: String = ""
        private set

    /** Procesează [bitmap] dacă nu este frame-ul de skip. Thread-safe: poate fi apelat din cameraExecutor. */
    fun processFrame(bitmap: Bitmap) {
        frameCounter++
        if (frameCounter % SKIP_EVERY != 0) return

        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.textBlocks
                    .filter { block ->
                        block.text.length >= MIN_TEXT_LENGTH &&
                        block.lines.flatMap { it.elements }.let { elements ->
                            elements.isEmpty() || elements.all { el ->
                                el.confidence == null || el.confidence!! >= MIN_CONFIDENCE
                            }
                        }
                    }
                    .sortedWith(compareBy(
                        { it.boundingBox?.top  ?: 0 },
                        { it.boundingBox?.left ?: 0 }
                    ))
                    .joinToString(" ") { it.text.trim() }
                    .trim()

                if (text.isNotEmpty() && hasSignificantChange(lastOcrText, text)) {
                    lastOcrText = text
                    Log.d(TAG, "OCR actualizat: \"${text.take(80)}\"")
                }
            }
            .addOnFailureListener { Log.w(TAG, "OCR eșuat: ${it.message}") }
    }

    fun close() = recognizer.close()

    private fun hasSignificantChange(old: String, new: String): Boolean {
        if (old.isEmpty()) return true
        return levenshtein(old, new).toFloat() / old.length > CHANGE_RATIO
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length; val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val dp = Array(m + 1) { i -> IntArray(n + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                       else 1 + min(dp[i - 1][j - 1], min(dp[i - 1][j], dp[i][j - 1]))
        }
        return dp[m][n]
    }
}