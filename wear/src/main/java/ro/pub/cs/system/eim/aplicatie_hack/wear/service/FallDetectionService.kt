package ro.pub.cs.system.eim.aplicatie_hack.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ro.pub.cs.system.eim.aplicatie_hack.wear.FallAlertActivity
import ro.pub.cs.system.eim.aplicatie_hack.wear.haptic.HapticManager
import kotlin.math.sqrt

/**
 * Detecție cădere prin accelerometru la 50Hz.
 *
 * Algoritm în 2 faze:
 *  Faza 1 — Impact: spike de accelerație > 3G (lovitură de sol)
 *  Faza 2 — Stillness: media ultimelor 0.6s ≈ 1G (gravitație, culcat pe sol)
 *             + deviația standard mică (nu se mișcă)
 *
 * De ce TYPE_LINEAR_ACCELERATION și nu TYPE_ACCELEROMETER?
 * LINEAR_ACCELERATION exclude gravitația din calcul → spikele de impact sunt mult
 * mai clare. ACCELEROMETER include 9.8 m/s² permanent — threshold-ul devine ambiguu.
 * Fallback la ACCELEROMETER dacă LINEAR_ACCELERATION indisponibil (device vechi).
 */
class FallDetectionService : Service(), SensorEventListener {

    companion object {
        const val ACTION_CANCEL = "action.CANCEL_FALL"
        const val PATH_FALL  = "/emergency/fall"
        const val PATH_CANCEL = "/emergency/fall_cancel"

        private const val TAG = "FallDetection"
        private const val NOTIF_ID   = 2001
        private const val CHANNEL_ID = "fall_channel"

        private const val IMPACT_G          = 2.8f   // spike → impact detectat
        private const val STILL_WINDOW      = 25     // 25 samples ≈ 0.5s la 50Hz
        private const val STILL_AVG_MAX     = 1.5f   // mediu la nivelul gravitației ± mică mișcare
        private const val STILL_STDDEV_MAX  = 0.18f  // mișcări mici = în continuare pe sol
        private const val STILLNESS_WAIT_MS = 2_500L // așteptăm 2.5s după impact
        private const val CANCEL_WINDOW_MS  = 15_000L
        private const val G = 9.81f
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var haptic: HapticManager

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val accelWindow = ArrayDeque<Float>(150)

    private var impactAt         = 0L
    private var pendingCheck     = false
    private var alertActive      = false
    private var cancelJob: Job?  = null

    override fun onCreate() {
        super.onCreate()
        haptic = HapticManager(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Log.e(TAG, "Accelerometru indisponibil — fall detection dezactivat")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelAlert()
            return START_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val mag = sqrt(x * x + y * y + z * z) / G

        accelWindow.addLast(mag)
        if (accelWindow.size > 150) accelWindow.removeFirst()

        // Faza 1: detectare impact
        if (!pendingCheck && !alertActive && mag > IMPACT_G) {
            impactAt     = System.currentTimeMillis()
            pendingCheck = true
            Log.d(TAG, "Impact detectat: ${mag}G")
        }

        // Faza 2: confirmare stillness după impact
        if (pendingCheck && !alertActive) {
            val elapsed = System.currentTimeMillis() - impactAt

            if (elapsed >= STILLNESS_WAIT_MS && accelWindow.size >= STILL_WINDOW) {
                val recent = accelWindow.takeLast(STILL_WINDOW)
                val avg    = recent.average().toFloat()
                val stdDev = recent.stdDev()

                when {
                    avg <= STILL_AVG_MAX && stdDev < STILL_STDDEV_MAX -> {
                        Log.i(TAG, "Cădere confirmată! avg=$avg stdDev=$stdDev")
                        triggerAlert()
                    }
                    elapsed > 8_000L -> {
                        // Timeout: utilizatorul s-a ridicat, fals pozitiv
                        pendingCheck = false
                        Log.d(TAG, "Fals pozitiv resetat")
                    }
                }
            }
        }
    }

    private fun triggerAlert() {
        pendingCheck = false
        alertActive  = true

        haptic.playSOS()

        // Activăm activity de anulare pe ecranul ceasului
        startActivity(
            Intent(this, FallAlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("countdown_ms", CANCEL_WINDOW_MS)
            }
        )

        // Timer 30s — dacă utilizatorul nu anulează, trimitem la telefon
        cancelJob = scope.launch {
            delay(CANCEL_WINDOW_MS)
            if (alertActive) notifyPhone()
        }
    }

    fun cancelAlert() {
        if (!alertActive) return
        alertActive = false
        cancelJob?.cancel()
        haptic.stop()
        Log.i(TAG, "Alertă anulată de utilizator")

        scope.launch {
            runCatching {
                Wearable.getDataClient(this@FallDetectionService).putDataItem(
                    PutDataMapRequest.create(PATH_CANCEL).also {
                        it.dataMap.putLong("ts", System.currentTimeMillis())
                    }.asPutDataRequest()
                ).await()
            }
        }
    }

    private suspend fun notifyPhone() {
        var attempt = 0
        while (attempt < 3) {
            val result = runCatching {
                Wearable.getDataClient(this).putDataItem(
                    PutDataMapRequest.create(PATH_FALL).also {
                        it.dataMap.putLong("ts", System.currentTimeMillis())
                        it.dataMap.putBoolean("confirmed", true)
                    }.asPutDataRequest().setUrgent() // .setUrgent() = livrare BLE prioritară < 100ms
                ).await()
            }
            if (result.isSuccess) {
                Log.i(TAG, "Cădere raportată la telefon")
                return
            }
            attempt++
            delay(5_000L) // retry după 5s dacă BLE temporar deconectat
        }
        Log.e(TAG, "Nu s-a putut notifica telefonul după 3 încercări")
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fall Detection", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian activ")
            .setContentText("Protecție cădere activă")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .build()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun List<Float>.stdDev(): Float {
        val mean = average().toFloat()
        val variance = fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / size
        return sqrt(variance)
    }
}