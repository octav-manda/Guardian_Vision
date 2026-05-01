package ro.pub.cs.system.eim.aplicatie_hack.vision

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.Locale

/**
 * Detectează automat luminozitatea scenei prin analiza planului Y (luminanță) al frame-urilor YUV.
 *
 * Comportament:
 * - Mediere pe fereastră de [WINDOW_SIZE] frame-uri → rezistentă la fluctuații momentane
 * - Histerezis LOW→HIGH: intră în modul noapte < [LOW_LIGHT_THRESHOLD], iese > [BRIGHT_THRESHOLD]
 * - La schimbare de mod: apelează [onNightModeChange] pentru control torch și alte ajustări
 * - Anunță vocal prin TTS la prima activare/dezactivare (debounce [ANNOUNCE_INTERVAL_MS])
 * - Sampling la 1 pixel din 8 pe ambele axe (1/64 din pixeli) → overhead neglijabil
 */
class NightModeDetector(
    context: Context,
    private val onNightModeChange: (isNight: Boolean) -> Unit,
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "NightMode"

        private const val LOW_LIGHT_THRESHOLD  = 45f   // <45/255 → întuneric
        private const val BRIGHT_THRESHOLD     = 65f   // >65/255 → lumină normală (histerezis)
        private const val WINDOW_SIZE          = 20    // ~2s la 10fps inferență
        private const val ANNOUNCE_INTERVAL_MS = 15_000L
        private const val SAMPLE_STEP          = 8     // 1 pixel din 8 pe fiecare axă
    }

    private val luminanceWindow = ArrayDeque<Float>(WINDOW_SIZE + 1)
    var isNightMode = false
        private set

    private var lastAnnounceMs = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ro", "RO")
            ttsReady = true
        }
    }

    /**
     * Analizează un frame și actualizează starea de mod noapte.
     * Trebuie apelat de pe thread-ul de analiză cameră (nu pe main thread).
     *
     * @return `true` dacă modul noapte este activ după această analiză.
     */
    fun analyze(proxy: ImageProxy): Boolean {
        val lum = computeLuminance(proxy)
        luminanceWindow.addLast(lum)
        if (luminanceWindow.size > WINDOW_SIZE) luminanceWindow.removeFirst()

        if (luminanceWindow.size < WINDOW_SIZE) return isNightMode

        val avg = luminanceWindow.average().toFloat()

        val newNightMode = when {
            isNightMode  && avg > BRIGHT_THRESHOLD    -> false
            !isNightMode && avg < LOW_LIGHT_THRESHOLD -> true
            else                                      -> isNightMode
        }

        if (newNightMode != isNightMode) {
            isNightMode = newNightMode
            Log.i(TAG, "Mod ${if (newNightMode) "noapte" else "zi"} detectat. Luminanță medie: $avg")
            onNightModeChange(newNightMode)
            announceIfNeeded(newNightMode)
        }

        return isNightMode
    }

    fun destroy() {
        tts?.shutdown()
        tts = null
    }

    // ── Privat ────────────────────────────────────────────────────────────────

    private fun computeLuminance(proxy: ImageProxy): Float {
        val plane      = proxy.planes[0]
        val buffer     = plane.buffer.duplicate()  // duplicate = citim fără a avansa position-ul original
        val rowStride  = plane.rowStride
        val pixelStride = plane.pixelStride
        val height     = proxy.height
        val width      = proxy.width
        val limit      = buffer.limit()

        var sum   = 0L
        var count = 0

        var row = 0
        while (row < height) {
            var col = 0
            while (col < width) {
                val idx = row * rowStride + col * pixelStride
                if (idx < limit) {
                    sum += buffer.get(idx).toInt() and 0xFF
                    count++
                }
                col += SAMPLE_STEP
            }
            row += SAMPLE_STEP
        }

        return if (count > 0) sum.toFloat() / count else 128f
    }

    private fun announceIfNeeded(nightMode: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastAnnounceMs < ANNOUNCE_INTERVAL_MS) return
        if (!ttsReady) return
        lastAnnounceMs = now

        val msg = if (nightMode)
            "Lumină slabă detectată. Lanternă activată pentru îmbunătățirea vizibilității."
        else
            "Lumină normală. Lanternă dezactivată."

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {}
        })
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "night_mode_announce")
    }
}