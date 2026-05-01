package ro.pub.cs.system.eim.aplicatie_hack.vision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ro.pub.cs.system.eim.aplicatie_hack.BuildConfig
import ro.pub.cs.system.eim.aplicatie_hack.wearable.WearableMessenger
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Trimite frame-ul curent la Gemini Flash și redă descrierea vocal + difuzează mesajul spre UI.
 *
 * ## Flux de date
 * ```
 * VisionForegroundService.lastFrame (AtomicReference<Bitmap>)
 *   └─→ describe(bitmap)
 *         ├─→ cooldown check (client-side throttle)
 *         ├─→ callGeminiWithRetry(bitmap)  — HTTP POST cu backoff exponențial
 *         │     ├─ 200 OK  → GeminiResult.Success(text)
 *         │     ├─ 429     → delay backoff → retry (max MAX_RETRIES ori)
 *         │     └─ altă eroare → GeminiResult.Error(msg)
 *         ├─→ TextToSpeech.speak(text)    — redare vocală în română
 *         └─→ sendBroadcast(ACTION_SHOW_MESSAGE) → MainActivity (pop-up vizual)
 * ```
 *
 * ## De ce apare 429 chiar și cu cotă disponibilă?
 * Gemini impune două limite independente:
 *  - **RPM** (Requests Per Minute): ex. 60 req/min pe tier gratuit
 *  - **TPM** (Tokens Per Minute): limita de tokeni
 * Un 429 apare când RPM este depășit, chiar dacă TPM are spațiu. Rafale de cereri
 * (ex. utilizatorul declanșează descrierea de mai multe ori rapid) vor lovi RPM.
 *
 * ## Strategia de backoff exponențial cu jitter
 * ```
 * delay = BASE_BACKOFF_MS * 2^attempt + Random(0..JITTER_MS)
 * attempt 0 → ~1.0–1.5s
 * attempt 1 → ~2.0–2.5s
 * attempt 2 → ~4.0–4.5s
 * max_delay → MAX_BACKOFF_MS (30s)
 * ```
 * Jitter-ul previne "thundering herd" (mai mulți clienți care reîncep simultan).
 * Dacă headerul `Retry-After` este prezent în răspunsul 429, îl respectăm cu prioritate.
 */
class SceneDescriptionManager(
    private val context: Context,
    private val messenger: WearableMessenger? = null,
    private val ocrTextProvider: () -> String = { "" },
) {

    companion object {
        const val TAG = "SceneDesc"
        const val ACTION_SHOW_MESSAGE = "ro.pub.cs.system.eim.aplicatie_hack.SHOW_MESSAGE"
        const val EXTRA_MESSAGE       = "extra_message"
        const val EXTRA_IS_ERROR      = "extra_is_error"

        private const val MODEL   = "gemini-2.5-flash"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

        /** Throttle client-side: previne spam la API înainte ca cererea să plece. */
        private const val COOLDOWN_MS = 6_000L

        /** Numărul maxim de reîncercări automate după un 429 sau eroare de rețea. */
        private const val MAX_RETRIES = 3

        /** Baza intervalului de așteptare exponențial (ms). */
        private const val BASE_BACKOFF_MS = 1_000L

        /** Plafon maxim backoff — nu așteptăm mai mult de 30s. */
        private const val MAX_BACKOFF_MS  = 30_000L

        /** Jitter maxim adăugat la backoff pentru a evita sincronizarea cererilor. */
        private const val JITTER_MS       = 500L

        /** Dimensiunea maximă a imaginii trimisă la Gemini (px pe latura lungă). */
        private const val MAX_DIM_PX = 1024

        private val PROMPT = """
            Ești asistentul personal al unui nevăzător.
            Analizează imaginea și oferă o descriere a scenei (interior sau exterior) în maxim 50 de cuvinte.
            Prioritizează elementele esențiale: obstacole, persoane, obiecte importante sau contextul general.
            Fii clar, direct și răspunde în limba română.
        """.trimIndent()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /**
     * SupervisorJob: dacă o cerere eșuează, celelelte din scope nu sunt anulate.
     * Dispatchers.IO: operațiile HTTP sunt blocking — IO dispatcher crează thead-uri dedicate.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** @Volatile garantează vizibilitate cross-thread pentru flagul de cooldown. */
    @Volatile private var lastCallAt = 0L

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                val result = tts?.setLanguage(Locale("ro", "RO"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Română indisponibilă — fallback la engleză")
                    tts?.language = Locale.ENGLISH
                }
                tts?.setSpeechRate(1.0f)
            }
        }
    }

    /**
     * Punct de intrare public. Poate fi apelat din:
     *  - [VisionForegroundService.onStartCommand] cu ACTION_DESCRIBE_SCENE (buton în app)
     *  - [PhoneDataLayerService.triggerSceneDescription] (double-tap ceas)
     *
     * @param bitmap Frame curent din camera telefonului (stocat în [VisionForegroundService.lastFrame]).
     */
    fun describe(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastCallAt < COOLDOWN_MS) {
            speak("Vă rugăm să așteptați câteva secunde între analize.")
            return
        }
        lastCallAt = now
        speak("Analizez scena. Vă rog să așteptați.")

        scope.launch {
            val ocrText = ocrTextProvider()
            val result  = callGeminiWithRetry(bitmap, ocrText)
            when (result) {
                is GeminiResult.Success -> {
                    withContext(Dispatchers.Main) { broadcastMessage(result.text, isError = false) }
                    // Încearcă redarea audio pe ceas; fallback pe telefon dacă ceasul nu e conectat
                    val sentToWatch = messenger?.runCatching {
                        sendTextMessage("/tts/speak", result.text)
                    }?.getOrElse { false } ?: false
                    if (!sentToWatch) withContext(Dispatchers.Main) { speak(result.text) }
                }
                is GeminiResult.Error -> {
                    withContext(Dispatchers.Main) {
                        speak("Eroare la analiză. Detalii pe ecranul telefonului.")
                        copyToClipboard(result.message)
                        broadcastMessage("EROARE API:\n${result.message}", isError = true)
                    }
                }
            }
        }
    }

    /**
     * Apelează Gemini cu retry automat pe 429 (Rate Limit) sau erori de rețea.
     *
     * Strategia de retry:
     * 1. Verifică headerul `Retry-After` din răspunsul 429 (Gemini îl include uneori).
     * 2. Dacă lipsește, calculează delay exponențial cu jitter.
     * 3. Max [MAX_RETRIES] tentative; după epuizare returnează ultimul mesaj de eroare.
     *
     * @param bitmap Frame de procesat.
     * @param attempt Numărul tentativei curente (0 = prima încercare).
     * @return [GeminiResult.Success] sau [GeminiResult.Error] după toate reîncercările.
     */
    private suspend fun callGeminiWithRetry(bitmap: Bitmap, ocrText: String = "", attempt: Int = 0): GeminiResult {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNullOrBlank()) {
            return GeminiResult.Error("GEMINI_API_KEY lipsă din local.properties")
        }

        val bodyJson = buildRequestJson(encodeImage(bitmap), ocrText)

        return try {
            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            // http.execute() este blocking — OK pe Dispatchers.IO
            val response = http.newCall(request).execute()

            response.use { resp ->
                when {
                    resp.code == 429 -> {
                        if (attempt >= MAX_RETRIES) {
                            Log.e(TAG, "429 Rate Limit epuizat după $MAX_RETRIES reîncercări")
                            return GeminiResult.Error("Rate limit Gemini (429). Reîncercați mai târziu.")
                        }

                        // Respectăm Retry-After dacă e prezent; altfel backoff exponențial
                        val waitMs = resp.header("Retry-After")
                            ?.toLongOrNull()
                            ?.let { it * 1_000L }
                            ?: computeBackoff(attempt)

                        Log.w(TAG, "429 → aștept ${waitMs}ms (tentativa ${attempt + 1}/$MAX_RETRIES)")
                        delay(waitMs)
                        callGeminiWithRetry(bitmap, ocrText, attempt + 1)
                    }

                    !resp.isSuccessful -> {
                        val body = resp.body?.string()
                        val msg  = "HTTP ${resp.code}: $body"
                        Log.e(TAG, msg)
                        // 5xx = erori de server, merită retry; 4xx (ex. 400, 403) = nu
                        if (resp.code in 500..599 && attempt < MAX_RETRIES) {
                            delay(computeBackoff(attempt))
                            callGeminiWithRetry(bitmap, ocrText, attempt + 1)
                        } else {
                            GeminiResult.Error(msg)
                        }
                    }

                    else -> parseResponse(resp.body?.string())
                }
            }
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Eroare rețea: $msg")
            if (attempt < MAX_RETRIES) {
                delay(computeBackoff(attempt))
                callGeminiWithRetry(bitmap, ocrText, attempt + 1)
            } else {
                GeminiResult.Error(msg)
            }
        }
    }

    /**
     * Calculează intervalul de așteptare pentru tentativa [attempt].
     *
     * Formula: `BASE * 2^attempt + Random(0..JITTER_MS)`
     * Exemplu: attempt=0 → 1000–1500ms, attempt=1 → 2000–2500ms, attempt=2 → 4000–4500ms.
     * Jitter-ul previne situația în care mai mulți clienți reîncep simultan după o pauză.
     */
    private fun computeBackoff(attempt: Int): Long {
        val exponential = BASE_BACKOFF_MS * (1L shl attempt) // 1s, 2s, 4s...
        val jitter      = Random.nextLong(0, JITTER_MS)
        return min(exponential + jitter, MAX_BACKOFF_MS)
    }

    /**
     * Parsează răspunsul JSON de la Gemini și extrage textul generat.
     *
     * Structura așteptată:
     * ```json
     * { "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }
     * ```
     * Dacă `candidates` este gol, conținutul a fost blocat de filtrele de siguranță Gemini.
     */
    private fun parseResponse(body: String?): GeminiResult {
        if (body == null) return GeminiResult.Error("Răspuns gol de la server")

        return runCatching {
            val json       = JsonParser.parseString(body).asJsonObject
            val candidates = json.getAsJsonArray("candidates")

            if (candidates == null || candidates.size() == 0) {
                return GeminiResult.Error("Conținut blocat de filtrele Gemini. Body: $body")
            }

            val text = candidates[0].asJsonObject
                .getAsJsonObject("content")
                ?.getAsJsonArray("parts")
                ?.get(0)?.asJsonObject
                ?.get("text")?.asString

            if (text != null) GeminiResult.Success(text)
            else GeminiResult.Error("Nu s-a putut extrage textul. Body: $body")
        }.getOrElse {
            GeminiResult.Error("Eroare parsare JSON: ${it.message}. Body: $body")
        }
    }

    /**
     * Construiește corpul JSON pentru request-ul multimodal Gemini.
     * Trimitem atât textul prompt-ului cât și imaginea base64 în același `parts` array.
     */
    private fun buildRequestJson(imageB64: String, ocrText: String = ""): String {
        val fullPrompt = if (ocrText.isNotBlank())
            "$PROMPT\nText vizibil în scenă (OCR): $ocrText. Menționează acest text în descriere dacă este relevant (etichete produse, numere autobuze, panouri, indicatoare)."
        else PROMPT
        val partText  = JsonObject().apply { addProperty("text", fullPrompt) }
        val partImage = JsonObject().apply {
            add("inline_data", JsonObject().apply {
                addProperty("mime_type", "image/jpeg")
                addProperty("data", imageB64)
            })
        }
        val partsArray    = JsonArray().apply { add(partText); add(partImage) }
        val contentObj    = JsonObject().apply { add("parts", partsArray) }
        val contentsArray = JsonArray().apply { add(contentObj) }

        return JsonObject().apply {
            add("contents", contentsArray)
            add("generationConfig", JsonObject().apply {
                addProperty("maxOutputTokens", 1024)
                addProperty("temperature", 0.2) // temperatură scăzută = răspunsuri mai precise
            })
        }.toString()
    }

    /**
     * Redimensionează și comprimă bitmap-ul pentru transmitere eficientă.
     * Reduce la max [MAX_DIM_PX] pe latura cea mai mare, JPEG quality 85.
     * Un frame 640×480 la 85% JPEG → ~30–60 KB, rapid de transferat.
     */
    private fun encodeImage(bitmap: Bitmap): String {
        val scale = MAX_DIM_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width  * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /** Difuzează textul/eroarea spre [ro.pub.cs.system.eim.aplicatie_hack.MainActivity] pentru pop-up vizual. */
    private fun broadcastMessage(text: String, isError: Boolean) {
        context.sendBroadcast(
            Intent(ACTION_SHOW_MESSAGE).apply {
                putExtra(EXTRA_MESSAGE, text)
                putExtra(EXTRA_IS_ERROR, isError)
                setPackage(context.packageName)
            }
        )
    }

    private fun copyToClipboard(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Gemini Error Log", text))
    }

    /** QUEUE_FLUSH anulează orice anunț anterior (ex. "Analizez scena") când sosește rezultatul. */
    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guardian")
        else Log.w(TAG, "TTS not ready: $text")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }

    private sealed class GeminiResult {
        data class Success(val text: String)    : GeminiResult()
        data class Error(val message: String)   : GeminiResult()
    }
}
