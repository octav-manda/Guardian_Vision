package ro.pub.cs.system.eim.aplicatie_hack.wear.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Gestionează ciclul de viață al asistentului vocal:
 *   STT → parsare comandă → apel API → TTS
 *
 * Audio focus: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK — alertele haptic de siguranță
 * continuă să funcționeze (haptic este independent de audio).
 *
 * Trebuie creat și distrus pe main thread (SpeechRecognizer constraint).
 */
class VoiceAssistantManager(
    private val context: Context,
    private val apiKey: String,
    private val onStateChange: (AssistantState) -> Unit,
) : TextToSpeech.OnInitListener {

    enum class AssistantState { IDLE, LISTENING, PROCESSING, SPEAKING }

    companion object {
        private const val TAG = "VoiceAssistant"

        private val NAV_REGEX = Regex(
            "(vreau să ajung|du-mă|navighează|traseu|cum ajung)\\s+(?:la|spre|până la|la)?\\s*(.+)",
            RegexOption.IGNORE_CASE
        )
        private val TRANSIT_REGEX = Regex(
            "(stație|autobuz|tramvai|metrou|transport în comun|când vine|program transport)",
            RegexOption.IGNORE_CASE
        )
        private val NEXT_STEP_REGEX = Regex(
            "(următor|pasul următor|continuă|mai departe)",
            RegexOption.IGNORE_CASE
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var processingJob: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    // Folosit de onPartialResults pentru a opri ascultarea la cuvântul "gata"
    @Volatile private var stoppedByKeyword = false

    var currentState = AssistantState.IDLE
        private set(value) {
            field = value
            onStateChange(value)
        }

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ro", "RO")
            ttsReady = true
            Log.d(TAG, "TTS inițializat")
        } else {
            Log.e(TAG, "TTS eșuat: $status")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun activate() {
        if (currentState != AssistantState.IDLE) return
        if (!acquireAudioFocus()) {
            Log.w(TAG, "Nu s-a putut obține audio focus")
            return
        }
        // Scurt prompt verbal → STT pornit după ce TTS se termină
        scope.launch {
            speakAndAwait("Ascult. Spuneți gata la final.")
            startListening()
        }
    }

    /** Obține audio focus pentru fluxuri externe (ex. onboarding). */
    fun acquireFocus(): Boolean = acquireAudioFocus()

    /** Eliberează audio focus după un flux extern. */
    fun releaseFocus() = releaseAudioFocus()

    fun interrupt() {
        processingJob?.cancel()
        recognizer?.stopListening()
        tts?.stop()
        releaseAudioFocus()
        currentState = AssistantState.IDLE
    }

    /** Vorbire publică — folosit de OnboardingManager și pentru ghidaj general. */
    suspend fun speak(text: String) = speakAndAwait(text)

    fun destroy() {
        interrupt()
        tts?.shutdown()
        tts = null
        recognizer?.destroy()
        recognizer = null
        scope.cancel()
    }

    // ── Audio Focus ────────────────────────────────────────────────────────────

    private fun acquireAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS) interrupt()
                }
                .build()
            audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) interrupt() },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    // ── STT ───────────────────────────────────────────────────────────────────

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            scope.launch {
                speakAndAwait("Recunoașterea vocală nu este disponibilă pe acest dispozitiv.")
                releaseAudioFocus()
                currentState = AssistantState.IDLE
            }
            return
        }

        stoppedByKeyword = false
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(buildRecognitionListener())
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Partial results active — detectăm "gata" din mers
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // Reducem silențiul de finalizare: 800ms (default ~1500ms)
                    putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 800L)
                    putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 500L)
                    putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 800L)
                }
            )
        }
    }

    private fun buildRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            currentState = AssistantState.LISTENING
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (stoppedByKeyword) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return

            // Oprire imediată la cuvântul "gata" — declanșează onResults cu tot ce s-a spus
            if (partial.contains("gata", ignoreCase = true)) {
                stoppedByKeyword = true
                recognizer?.stopListening()
                Log.d(TAG, "Keyword 'gata' detectat în partial: \"$partial\"")
            }
        }

        override fun onResults(results: Bundle?) {
            var text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""

            // Elimină cuvântul stop și orice semne de punctuație din final
            text = text
                .replace(Regex("[,.]?\\s*gata[.!]?\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()

            stoppedByKeyword = false
            Log.d(TAG, "STT final: \"$text\"")

            if (text.isBlank()) {
                scope.launch {
                    speakAndAwait("Nu am înțeles comanda. Apăsați lung și spuneți comanda urmată de gata.")
                    releaseAudioFocus()
                    currentState = AssistantState.IDLE
                }
            } else {
                processCommand(text)
            }
        }

        override fun onError(error: Int) {
            stoppedByKeyword = false
            Log.w(TAG, "STT eroare cod=$error")
            scope.launch {
                speakAndAwait("Nu am înțeles. Apăsați lung pentru a încerca din nou.")
                releaseAudioFocus()
                currentState = AssistantState.IDLE
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Command Processing ────────────────────────────────────────────────────

    private fun processCommand(text: String) {
        currentState = AssistantState.PROCESSING
        processingJob = scope.launch {
            try {
                when {
                    NAV_REGEX.containsMatchIn(text)        -> handleNavigation(text)
                    TRANSIT_REGEX.containsMatchIn(text)    -> handleTransitQuery()
                    NEXT_STEP_REGEX.containsMatchIn(text)  -> handleNextStep()
                    else -> speakAndAwait(
                        "Am auzit: $text. " +
                        "Puteți spune: Vreau să ajung la o destinație, " +
                        "Unde este cea mai apropiată stație, " +
                        "sau: Pasul următor."
                    )
                }
            } catch (e: CancellationException) {
                // Interrupted — no action needed
            } finally {
                releaseAudioFocus()
                currentState = AssistantState.IDLE
            }
        }
    }

    private suspend fun handleNavigation(text: String) {
        val destination = NAV_REGEX.find(text)?.groupValues?.lastOrNull()?.trim()
            ?: run {
                speakAndAwait("Nu am înțeles destinația. Încercați: Vreau să ajung la Piața Victoriei, gata.")
                return
            }

        speakAndAwait("Calculez traseul pietonal spre $destination.")

        val location = getLocation() ?: run {
            speakAndAwait("Nu am putut obține locația. Activați GPS-ul și încercați din nou.")
            return
        }

        NavigationRepository.getWalkingDirections(
            originLat   = location.first,
            originLng   = location.second,
            destination = destination,
            apiKey      = apiKey,
        ).fold(
            onSuccess = { result ->
                if (result.steps.isEmpty()) {
                    speakAndAwait("Nu am găsit un traseu pietonal spre $destination.")
                } else {
                    NavigationState.setRoute(result)
                    val first    = result.steps.first()
                    val duration = if (result.totalDuration.isNotBlank())
                        "Durată estimată: ${result.totalDuration}. " else ""
                    speakAndAwait(
                        "Traseu găsit. $duration" +
                        "Distanță totală: ${result.totalDistance}. " +
                        "Primul pas: ${first.instruction}, distanță ${first.distance}. " +
                        "Spuneți pasul următor pentru instrucțiunea următoare."
                    )
                }
            },
            onFailure = {
                speakAndAwait("Eroare la obținerea traseului. Verificați conexiunea la internet.")
            }
        )
    }

    private suspend fun handleTransitQuery() {
        speakAndAwait("Caut stații de transport în apropiere.")

        val location = getLocation() ?: run {
            speakAndAwait("Nu am putut obține locația. Activați GPS-ul.")
            return
        }

        val stopResult = NavigationRepository.getNearestTransitStop(
            lat    = location.first,
            lng    = location.second,
            apiKey = apiKey,
        )

        val stop = stopResult.getOrNull() ?: run {
            speakAndAwait("Nu am putut obține informații despre transport.")
            return
        }

        if (stop.name == "Nicio stație găsită") {
            speakAndAwait("Nu am găsit stații de transport în raza de 500 de metri.")
            return
        }

        speakAndAwait("Cea mai apropiată stație este ${stop.name}. ${stop.vicinity}.")

        // Preluăm cursele următoare care trec prin stație
        NavigationRepository.getTransitArrivals(
            stopLat = stop.lat,
            stopLng = stop.lng,
            apiKey  = apiKey,
        ).fold(
            onSuccess = { arrivals ->
                if (arrivals.isEmpty()) {
                    speakAndAwait("Nu am găsit informații despre cursele din această stație.")
                } else {
                    val sb = StringBuilder("Următoarele curse disponibile: ")
                    arrivals.forEachIndexed { idx, a ->
                        sb.append("${idx + 1}. ${a.vehicleType} ${a.lineNumber} " +
                                  "direcție ${a.direction}, pleacă la ${a.departureTime}. ")
                    }
                    speakAndAwait(sb.toString())
                }
            },
            onFailure = {
                speakAndAwait("Nu am putut obține orarul pentru această stație.")
            }
        )
    }

    private suspend fun handleNextStep() {
        val step = NavigationState.advance()
        if (step == null) {
            speakAndAwait("Ați ajuns la destinație. Navigarea s-a încheiat.")
        } else {
            speakAndAwait("Pasul următor: ${step.instruction}, distanță ${step.distance}.")
        }
    }

    // ── TTS Helper ─────────────────────────────────────────────────────────────

    private suspend fun speakAndAwait(text: String) {
        if (!ttsReady || tts == null) return
        currentState = AssistantState.SPEAKING
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { tts?.stop() }
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun getLocation(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        runCatching {
            val cts = CancellationTokenSource()
            val loc = LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .await()
            loc?.let { Pair(it.latitude, it.longitude) }
        }.getOrNull()
    }
}