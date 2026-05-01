package ro.pub.cs.system.eim.aplicatie_hack.wear.service

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import ro.pub.cs.system.eim.aplicatie_hack.model.HapticEvent
import ro.pub.cs.system.eim.aplicatie_hack.wear.TrackingState
import ro.pub.cs.system.eim.aplicatie_hack.wear.haptic.HapticManager
import java.util.Locale

/**
 * Ascultă trei tipuri de evenimente de la telefon:
 *  1. MESSAGE_RECEIVED pe /haptic → redă imediat pattern haptic
 *  2. MESSAGE_RECEIVED pe /tts/speak → redă textul descrierii scenei pe difuzorul ceasului
 *  3. DATA_CHANGED pe /tracking/state → actualizează TrackingState
 *
 * ## Lifecycle pentru TTS
 * WearableListenerService este oprit automat de sistem imediat ce nu mai are mesaje de procesat.
 * Pentru a preveni întreruperea TTS la mijlocul frazei, la primirea unui mesaj /tts/speak
 * serviciul se auto-pornește ca "started service" (startService). Oprirea se face explicit
 * din UtteranceProgressListener.onDone, după ce TTS a terminat de vorbit.
 */
class WatchDataLayerService : WearableListenerService() {

    private companion object {
        const val TAG = "WatchDataLayer"
        const val TTS_UTTERANCE_ID = "scene_desc"
    }

    private val haptic by lazy { HapticManager(this) }

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    // Text primit înainte ca TTS să fie inițializat (onMessageReceived poate sosi
    // imediat după onCreate, înaintea callback-ului asincron onInit)
    @Volatile private var pendingText: String? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(Locale("ro", "RO"))
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Română indisponibilă pe ceas — TTS fallback la ${Locale.getDefault()}")
                    tts?.language = Locale.getDefault()
                }
                ttsReady = true
                Log.d(TAG, "TTS ceas inițializat")
                // Redă textul care a sosit cât TTS se inițializa
                pendingText?.also { queued ->
                    pendingText = null
                    doSpeak(queued)
                }
            } else {
                Log.e(TAG, "TTS ceas init eșuat: $status")
                stopSelf()  // nu mai avem ce face fără TTS
            }
        }
    }

    /**
     * Necesar pentru ca startService() să nu repornească serviciul cu intent null.
     * START_NOT_STICKY: nu reporni dacă sistemul îl omoară înainte de stopSelf().
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            "/haptic" -> {
                val eventName = String(event.data, Charsets.UTF_8)
                val hapticEvent = runCatching { HapticEvent.valueOf(eventName) }.getOrElse {
                    Log.w(TAG, "Eveniment haptic necunoscut: $eventName")
                    return
                }
                Log.d(TAG, "Haptic: $hapticEvent")
                haptic.play(hapticEvent)
            }
            "/tts/speak" -> {
                val text = String(event.data, Charsets.UTF_8)
                Log.d(TAG, "TTS ceas primit: \"${text.take(80)}…\"")

                // Menținem serviciul viu cât TTS vorbește — stopSelf() vine din UtteranceProgressListener
                startService(Intent(this, WatchDataLayerService::class.java))

                if (ttsReady) {
                    doSpeak(text)
                } else {
                    pendingText = text  // onInit îl va reda
                    Log.d(TAG, "TTS nu e gata — text pus în așteptare")
                }
            }
        }
    }

    private fun doSpeak(text: String) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS finalizat — opresc serviciul")
                stopSelf()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS eroare — opresc serviciul")
                stopSelf()
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            buffer.filter { it.type == DataEvent.TYPE_CHANGED }
                .forEach { event ->
                    val path = event.dataItem.uri.path ?: return@forEach
                    if (path == "/tracking/state") {
                        val active = DataMapItem.fromDataItem(event.dataItem)
                            .dataMap.getBoolean("active", false)
                        TrackingState.update(active)
                        Log.d(TAG, "Tracking state actualizat: $active")
                    }
                }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
