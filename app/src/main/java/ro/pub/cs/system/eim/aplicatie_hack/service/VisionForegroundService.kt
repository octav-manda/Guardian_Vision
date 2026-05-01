package ro.pub.cs.system.eim.aplicatie_hack.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ro.pub.cs.system.eim.aplicatie_hack.MainActivity
import ro.pub.cs.system.eim.aplicatie_hack.audio.TrafficLightAudioPlayer
import ro.pub.cs.system.eim.aplicatie_hack.model.HapticEvent
import ro.pub.cs.system.eim.aplicatie_hack.vision.BoundingBoxFilter
import ro.pub.cs.system.eim.aplicatie_hack.vision.NightModeDetector
import ro.pub.cs.system.eim.aplicatie_hack.vision.OcrManager
import ro.pub.cs.system.eim.aplicatie_hack.vision.SceneDescriptionManager
import ro.pub.cs.system.eim.aplicatie_hack.vision.TrafficLightColor
import ro.pub.cs.system.eim.aplicatie_hack.vision.TrafficLightDetector
import ro.pub.cs.system.eim.aplicatie_hack.vision.UrgencyLevel
import ro.pub.cs.system.eim.aplicatie_hack.wearable.WearableMessenger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Serviciu foreground principal al telefonului — "ochii" sistemului Guardian.
 *
 * ## Flux de date principal
 * ```
 * Camera (CameraX 640×480 YUV) → analyzeFrame()
 *   ├─→ ML Kit Object Detection (STREAM_MODE)
 *   │     ├─→ BoundingBoxFilter → obstacole upper-body → HapticEvent
 *   │     └─→ TrafficLightDetector (HSV) → TRAFFIC_LIGHT_GREEN / RED
 *   ├─→ debounce 900ms → WearableMessenger.send() → MessageClient → ceas
 *   └─→ lastFrame (AtomicReference) → SceneDescriptionManager la cerere
 * ```
 *
 * ## Frame skipping adaptiv
 * Porneste la `skipEvery=3` (10fps inferență pe 30fps captură). La pericol iminent,
 * crește la `skipEvery=1` (30fps). La scenă liberă, scade treptat la `skipEvery=6`
 * (5fps) pentru economie baterie.
 *
 * ## Extinde [LifecycleService]
 * CameraX necesită un [androidx.lifecycle.LifecycleOwner]. [LifecycleService] implementează
 * această interfață, permițând `bindToLifecycle(this, ...)` direct din serviciu.
 *
 * ## Starea de tracking pe ceas
 * La start și stop, [broadcastTrackingState] publică un DataItem cu `setUrgent()`
 * pe calea `/tracking/state`. [WatchDataLayerService] primește actualizarea și
 * actualizează [ro.pub.cs.system.eim.aplicatie_hack.wear.TrackingState],
 * care activează/dezactivează gesturile din [ro.pub.cs.system.eim.aplicatie_hack.wear.WatchMainActivity].
 */
class VisionForegroundService : LifecycleService() {

    companion object {
        /**
         * Acțiunea trimisă ca intent pentru a declanșa o descriere de scenă fără a
         * reporni serviciul. Handlerat în [onStartCommand].
         * Surse: buton în [MainActivity], mesaj de la ceas via [PhoneDataLayerService].
         */
        const val ACTION_DESCRIBE_SCENE = "action.DESCRIBE_SCENE"

        /** SharedPreferences name — aceeași cheie citită și de [PhoneDataLayerService.toggleTracking]. */
        const val PREFS_NAME  = "guardian_prefs"

        /** Cheia booleanului care indică dacă serviciul rulează activ. */
        const val KEY_RUNNING = "service_running"

        private const val NOTIF_ID   = 1001
        private const val CHANNEL_ID = "vision_channel"
        private const val TAG        = "VisionService"

        /**
         * Ultimul frame capturat — [AtomicReference] pentru acces thread-safe.
         * Scris pe thread-ul cameră (cameraExecutor), citit pe IO din [SceneDescriptionManager].
         */
        val lastFrame = AtomicReference<Bitmap?>(null)
    }

    /** Thread dedicat analizei cameră — izolat de main thread pentru a nu bloca UI. */
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private lateinit var detector         : ObjectDetector
    private lateinit var bboxFilter       : BoundingBoxFilter
    private lateinit var tlDetector       : TrafficLightDetector
    private lateinit var messenger        : WearableMessenger
    private lateinit var sceneDesc        : SceneDescriptionManager
    private lateinit var audioPlayer      : TrafficLightAudioPlayer
    private lateinit var nightModeDetector: NightModeDetector
    private lateinit var ocrManager       : OcrManager

    // Referință la Camera CameraX — necesară pentru controlul torch-ului
    @Volatile private var camera: Camera? = null

    // ── Frame skipping adaptiv ────────────────────────────────────────────────
    private var frameCounter = 0
    private var skipEvery    = 3   // start: 10fps inferență din 30fps captură

    // ── Debounce haptic (ceas) ────────────────────────────────────────────────
    private var lastEvent     : HapticEvent? = null
    private var lastEventTime = 0L
    private val DEBOUNCE_MS   = 900L

    /**
     * Debounce separat pentru audio semafor (mai lung decât cel haptic).
     * Motivul separării: haptic-ul repetit la 900ms e acceptabil pe ceas,
     * dar audio la 900ms pe difuzorul telefonului ar fi extrem de deranjant.
     * La 5s: utilizatorul aude o dată "roșu" cât stă la semafor, nu de 30 de ori.
     */
    private var lastAudioEvent     : HapticEvent? = null
    private var lastAudioEventTime = 0L
    private val AUDIO_DEBOUNCE_MS  = 5_000L

    override fun onCreate() {
        super.onCreate()
        bboxFilter          = BoundingBoxFilter()
        tlDetector          = TrafficLightDetector()
        messenger           = WearableMessenger(this)
        ocrManager          = OcrManager()
        sceneDesc           = SceneDescriptionManager(this, messenger) { ocrManager.lastOcrText }
        audioPlayer         = TrafficLightAudioPlayer(this)
        nightModeDetector   = NightModeDetector(this) { isNight ->
            // Apelat de pe cameraExecutor — enableTorch e thread-safe
            camera?.cameraControl?.enableTorch(isNight)
            // În modul noapte: procesăm mai des (skipEvery ≤ 2) pentru a compensa scăderea calității
            if (isNight && skipEvery > 2) skipEvery = 2
        }
        initDetector()
    }

    /**
     * Handlerat două scenarii de start:
     *  1. Start normal (intent null sau fără acțiune specială): pornește foreground service,
     *     marchează starea în SharedPreferences, notifică ceasul, inițializează camera.
     *  2. [ACTION_DESCRIBE_SCENE]: serviciul e deja pornit — doar declanșează descrierea
     *     scenei fără a reinițializa camera sau a crea notificare nouă.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_DESCRIBE_SCENE) {
            lastFrame.get()?.let { sceneDesc.describe(it) }
                ?: Log.w(TAG, "Describe scene: niciun frame disponibil")
            return START_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, true).apply()
        broadcastTrackingState(active = true)
        startCamera()
        return START_STICKY
    }

    /**
     * Inițializează detectorul ML Kit în modul stream (STREAM_MODE).
     * STREAM_MODE este optimizat pentru video continuu: reutilizează context-ul
     * dintre frame-uri pentru a reduce latența per-frame.
     */
    private fun initDetector() {
        val opts = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        detector = ObjectDetection.getClient(opts)
    }

    /**
     * Pornește captura cameră și leagă analiza de lifecycleul acestui serviciu.
     * Rezoluția 640×480 este optimă: suficient pentru ML Kit, minimă pentru latență.
     * [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST]: dacă analiza e lentă, frame-urile
     * intermediare sunt aruncate (nu se acumulează o coadă care crește latența).
     */
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)

            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }.onFailure { Log.e(TAG, "Camera bind eșuat: ${it.message}") }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Analizează un singur frame și trimite feedback haptic la ceas dacă e necesar.
     *
     * Pipeline:
     * 1. Frame skipping: procesăm 1 din `skipEvery` frame-uri.
     * 2. ML Kit: detectăm obiectele din frame.
     * 3. [BoundingBoxFilter]: reținem doar obstacolele la nivel de corp (upper-body).
     * 4. [TrafficLightDetector]: verificăm culoarea semaforului dacă ML Kit detectează unul.
     * 5. Debounce: nu trimitem același eveniment mai des de 900ms.
     * 6. [WearableMessenger.send]: trimitem [HapticEvent] la ceas.
     * 7. Frame skipping adaptiv: ajustăm `skipEvery` în funcție de urgența detecției.
     *
     * @param proxy Frame-ul curent de la CameraX. **Trebuie** închis (proxy.close()) în toate
     *              căile de execuție pentru a elibera buffer-ul cameră.
     */
    private fun analyzeFrame(proxy: ImageProxy) {
        frameCounter++

        // Analiza luminozității se face pe FIECARE frame (overhead mic = sampling 1/64 pixeli)
        // skipEvery poate fi ajustat de nightModeDetector via callback
        val isNight = nightModeDetector.analyze(proxy)

        if (frameCounter % skipEvery != 0) { proxy.close(); return }

        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)

        detector.process(input)
            .addOnSuccessListener { objects ->
                val imgH = proxy.height.toFloat()
                val imgW = proxy.width.toFloat()

                runCatching {
                    val bmp = proxy.toBitmap()
                    lastFrame.set(bmp)
                    ocrManager.processFrame(bmp)
                }

                val threats = bboxFilter.filter(objects, imgH, imgW)

                // Prag mai scăzut noaptea: 0.50f (vs 0.65f ziua) — compensăm calitatea redusă
                val tlThreshold = if (isNight) 0.50f else 0.65f
                val trafficEvent = objects
                    .filter { o -> o.labels.any { it.text == "Traffic light" && it.confidence > tlThreshold } }
                    .firstNotNullOfOrNull { o ->
                        val bmp = lastFrame.get() ?: return@firstNotNullOfOrNull null
                        when (tlDetector.detectColor(bmp, o.boundingBox)) {
                            TrafficLightColor.GREEN -> HapticEvent.TRAFFIC_LIGHT_GREEN
                            TrafficLightColor.RED   -> HapticEvent.TRAFFIC_LIGHT_RED
                            else -> null
                        }
                    }

                val finalEvent = trafficEvent ?: when {
                    threats.any { it.urgency == UrgencyLevel.IMMINENT }    -> HapticEvent.DANGER_IMMINENT
                    threats.any { it.urgency == UrgencyLevel.APPROACHING } -> HapticEvent.OBSTACLE_APPROACHING
                    threats.any { it.urgency == UrgencyLevel.DISTANT }     -> HapticEvent.OBSTACLE_DISTANT
                    else -> null
                }

                finalEvent?.let { event ->
                    val now = System.currentTimeMillis()

                    // ── Haptic la ceas (debounce 900ms) ──────────────────────
                    if (event != lastEvent || now - lastEventTime > DEBOUNCE_MS) {
                        messenger.send(event)
                        lastEvent     = event
                        lastEventTime = now
                        skipEvery = if (event == HapticEvent.DANGER_IMMINENT) 1 else 3
                    }

                    // ── Audio semafor pe telefon (debounce 5s) ────────────────
                    // Debounce mai lung: audio repetat la 900ms ar fi deranjant.
                    // Se declanșează imediat la schimbarea culorii (RED↔GREEN),
                    // sau la prima detectare după 5s de la ultimul sunet.
                    val isTrafficAudio = event == HapticEvent.TRAFFIC_LIGHT_RED ||
                                         event == HapticEvent.TRAFFIC_LIGHT_GREEN
                    if (isTrafficAudio &&
                        (event != lastAudioEvent || now - lastAudioEventTime > AUDIO_DEBOUNCE_MS)) {
                        when (event) {
                            HapticEvent.TRAFFIC_LIGHT_RED   -> audioPlayer.playRed()
                            HapticEvent.TRAFFIC_LIGHT_GREEN -> audioPlayer.playGreen()
                            else -> Unit
                        }
                        lastAudioEvent     = event
                        lastAudioEventTime = now
                    }
                } ?: run {
                    if (skipEvery < 6) skipEvery++
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Guardian Vision", NotificationManager.IMPORTANCE_LOW)
                .also { it.description = "Asistentul vizual este activ" }
        )
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian Vision activ")
            .setContentText("Detectare obstacole în timp real")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    override fun onDestroy() {
        broadcastTrackingState(active = false)
        camera?.cameraControl?.enableTorch(false)  // stinge torch la oprire
        cameraExecutor.shutdown()
        detector.close()
        ocrManager.close()
        sceneDesc.shutdown()
        audioPlayer.release()
        nightModeDetector.destroy()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, false).apply()
        super.onDestroy()
    }

    /**
     * Publică starea de urmărire pe [com.google.android.gms.wearable.DataClient] pentru
     * a o sincroniza pe ceas, unde [ro.pub.cs.system.eim.aplicatie_hack.wear.service.WatchDataLayerService]
     * actualizează [ro.pub.cs.system.eim.aplicatie_hack.wear.TrackingState].
     *
     * ## De ce DataClient (și nu MessageClient) pentru starea de tracking?
     * DataClient este **persistent** — dacă ceasul și telefonul sunt deconectate BLE când
     * serviciul pornește, starea se sincronizează automat la reconectare. MessageClient
     * s-ar pierde dacă ceasul nu e conectat în acel moment.
     *
     * ## De ce setUrgent()?
     * DataClient implicit folosește sync batched (latență 100–500ms). `setUrgent()` instruiește
     * Data Layer să trimită prin BLE imediat, reducând latența la ~50ms — comparabil cu
     * MessageClient, dar cu avantajul persistenței.
     *
     * @param active `true` la start, `false` la [onDestroy].
     */
    private fun broadcastTrackingState(active: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                Wearable.getDataClient(this@VisionForegroundService).putDataItem(
                    PutDataMapRequest.create("/tracking/state").also {
                        it.dataMap.putBoolean("active", active)
                        it.dataMap.putLong("ts", System.currentTimeMillis())
                    }.asPutDataRequest().setUrgent()   // livrare BLE prioritară < 100ms
                ).await()
            }.onFailure { Log.w(TAG, "Tracking state broadcast eșuat: ${it.message}") }
        }
    }
}
