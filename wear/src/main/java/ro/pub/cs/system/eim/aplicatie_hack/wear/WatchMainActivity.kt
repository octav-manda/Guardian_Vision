package ro.pub.cs.system.eim.aplicatie_hack.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ro.pub.cs.system.eim.aplicatie_hack.wear.assistant.VoiceAssistantManager
import ro.pub.cs.system.eim.aplicatie_hack.wear.onboarding.OnboardingManager
import ro.pub.cs.system.eim.aplicatie_hack.wear.service.FallDetectionService

class WatchMainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WatchMain"
        private const val TRIPLE_PRESS_WINDOW_MS = 1_400L
        private const val RC_PERMISSIONS = 42

        const val PATH_REMOTE_LAUNCH   = "/remote/launch"
        const val PATH_DESCRIBE_SCENE  = "/command/describe_scene"
        const val PATH_TOGGLE_TRACKING = "/command/toggle_tracking"
    }

    private val activityScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Triple-press buton fizic
    private var stemPressCount = 0
    private val stemHandler       = Handler(Looper.getMainLooper())
    private val stemResetRunnable = Runnable { stemPressCount = 0 }

    @Volatile private var cachedPhoneNodeId: String? = null

    // Asistent vocal + onboarding
    private lateinit var voiceAssistant: VoiceAssistantManager
    private lateinit var onboardingManager: OnboardingManager
    private var assistantState by mutableStateOf(VoiceAssistantManager.AssistantState.IDLE)

    private val vib: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)!!
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startFallDetection()

        voiceAssistant = VoiceAssistantManager(
            context       = this,
            apiKey        = BuildConfig.MAPS_API_KEY,
            onStateChange = { state -> assistantState = state },
        )
        onboardingManager = OnboardingManager(this)

        setContent {
            val isTrackingActive by TrackingState.isActive.collectAsState()
            val waitingFor by onboardingManager.waitingFor.collectAsState()

            WatchMainScreen(
                isTrackingActive = isTrackingActive,
                assistantState   = assistantState,
                onDoubleTap      = {
                    if (isTrackingActive) sendMessage(PATH_DESCRIBE_SCENE)
                },
                onTripleTap      = {
                    if (waitingFor == OnboardingManager.WaitingFor.TRIPLE_TAP) {
                        onboardingManager.confirmGesture(OnboardingManager.WaitingFor.TRIPLE_TAP)
                    } else {
                        sendMessage(PATH_TOGGLE_TRACKING)
                    }
                },
                onLongPress      = {
                    if (waitingFor == OnboardingManager.WaitingFor.LONG_PRESS) {
                        onboardingManager.confirmGesture(OnboardingManager.WaitingFor.LONG_PRESS)
                    } else {
                        activateAssistant()
                    }
                },
            )
        }

        checkPermissionsAndOnboard()
    }

    // ── Permisiuni ────────────────────────────────────────────────────────────

    private fun checkPermissionsAndOnboard() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (needed.isEmpty()) {
            startOnboardingIfNeeded()
        } else {
            ActivityCompat.requestPermissions(this, needed, RC_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_PERMISSIONS) {
            startOnboardingIfNeeded()
        }
    }

    private fun startOnboardingIfNeeded() {
        if (!onboardingManager.isFirstRun) return
        lifecycleScope.launch {
            voiceAssistant.acquireFocus()
            try {
                onboardingManager.runOnboarding { text -> voiceAssistant.speak(text) }
            } finally {
                voiceAssistant.releaseFocus()
            }
        }
    }

    // ── Asistent Vocal ────────────────────────────────────────────────────────

    private fun activateAssistant() {
        vib.vibrate(VibrationEffect.createOneShot(80, 150))
        voiceAssistant.activate()
    }

    // ── Triple-press buton fizic (KEYCODE_STEM_1) ──────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STEM_1) {
            stemPressCount++
            stemHandler.removeCallbacks(stemResetRunnable)

            if (stemPressCount >= 3) {
                stemPressCount = 0
                Log.d(TAG, "Triple-press fizic → lansare telefon")
                sendMessage(PATH_REMOTE_LAUNCH)
                return true
            }

            stemHandler.postDelayed(stemResetRunnable, TRIPLE_PRESS_WINDOW_MS)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startFallDetection() {
        startForegroundService(Intent(this, FallDetectionService::class.java))
    }

    private fun sendMessage(path: String) {
        activityScope.launch {
            runCatching {
                val nodeId = cachedPhoneNodeId ?: discoverPhoneNode()
                    ?.also { cachedPhoneNodeId = it }
                    ?: run { Log.w(TAG, "Niciun telefon conectat"); return@runCatching }

                val result = runCatching {
                    Wearable.getMessageClient(this@WatchMainActivity)
                        .sendMessage(nodeId, path, ByteArray(0))
                        .await()
                }

                if (result.isFailure) {
                    cachedPhoneNodeId = null
                    val fresh = discoverPhoneNode()?.also { cachedPhoneNodeId = it }
                        ?: return@runCatching
                    Wearable.getMessageClient(this@WatchMainActivity)
                        .sendMessage(fresh, path, ByteArray(0))
                        .await()
                }
                Log.d(TAG, "Mesaj trimis: $path")
            }.onFailure { Log.w(TAG, "sendMessage($path) eșuat: ${it.message}") }
        }
    }

    private suspend fun discoverPhoneNode(): String? {
        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        return (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    }

    override fun onDestroy() {
        stemHandler.removeCallbacks(stemResetRunnable)
        voiceAssistant.destroy()
        activityScope.cancel()
        super.onDestroy()
    }
}

// ── UI Composable ─────────────────────────────────────────────────────────────

@Composable
private fun WatchMainScreen(
    isTrackingActive: Boolean,
    assistantState: VoiceAssistantManager.AssistantState,
    onDoubleTap: () -> Unit,
    onTripleTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tapCounter = remember { TapCounter() }
    val tapScope   = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(12.dp)
            .pointerInput(isTrackingActive) {
                detectTapGestures(
                    onTap = {
                        tapCounter.handle(
                            scope       = tapScope,
                            onDoubleTap = onDoubleTap,
                            onTripleTap = onTripleTap,
                        )
                    },
                    onLongPress = { onLongPress() },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text       = "Guardian",
            color      = Color(0xFF00E676),
            fontSize   = 16.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(4.dp))

        // Stare asistent vocal (vizibil, util ca feedback vizual secundar)
        val (statusText, statusColor) = when (assistantState) {
            VoiceAssistantManager.AssistantState.LISTENING   ->
                Pair("Ascult...",    Color(0xFF40C4FF))
            VoiceAssistantManager.AssistantState.PROCESSING  ->
                Pair("Procesez...", Color(0xFFFFD740))
            VoiceAssistantManager.AssistantState.SPEAKING    ->
                Pair("Vorbesc...",  Color(0xFFFFAB40))
            VoiceAssistantManager.AssistantState.IDLE        ->
                Pair(
                    if (isTrackingActive) "Urmărire activă" else "Protecție activă",
                    if (isTrackingActive) Color(0xFF00E676) else Color(0xFF888888)
                )
        }

        Text(
            text      = statusText,
            color     = statusColor,
            fontSize  = 11.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(10.dp))

        if (assistantState == VoiceAssistantManager.AssistantState.IDLE) {
            if (isTrackingActive) {
                Text(
                    text      = "2× tap → descriere scenă",
                    color     = Color(0xFF444444),
                    fontSize  = 9.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(3.dp))
            }

            Text(
                text      = "3× tap → pornire/oprire",
                color     = Color(0xFF444444),
                fontSize  = 9.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(3.dp))

            Text(
                text      = "Apasă lung → asistent vocal",
                color     = Color(0xFF444444),
                fontSize  = 9.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(3.dp))

            Text(
                text      = "3× buton → lansare telefon",
                color     = Color(0xFF333333),
                fontSize  = 9.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── Tap Counter (neschimbat) ──────────────────────────────────────────────────

private class TapCounter {
    private var count = 0
    private var job: Job? = null

    fun handle(
        scope: CoroutineScope,
        onDoubleTap: () -> Unit,
        onTripleTap: () -> Unit,
    ) {
        count++
        job?.cancel()

        when {
            count >= 3 -> {
                count = 0
                onTripleTap()
            }
            count == 2 -> {
                job = scope.launch {
                    delay(400L)
                    if (count == 2) { count = 0; onDoubleTap() }
                }
            }
            else -> {
                job = scope.launch {
                    delay(600L)
                    count = 0
                }
            }
        }
    }
}