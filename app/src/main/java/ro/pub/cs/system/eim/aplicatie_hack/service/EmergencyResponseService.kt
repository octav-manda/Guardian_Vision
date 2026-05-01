package ro.pub.cs.system.eim.aplicatie_hack.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Trimite SMS de urgență cu locația GPS după confirmarea unei căderi.
 * Pornit de PhoneDataLayerService când ceasul raportează o cădere neconfirmată.
 */
class EmergencyResponseService : Service() {

    companion object {
        const val ACTION_FALL_CONFIRMED = "action.FALL_CONFIRMED"
        private const val TAG = "EmergencyService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FALL_CONFIRMED) {
            scope.launch { executeProtocol() }
        }
        return START_NOT_STICKY
    }

    private suspend fun executeProtocol() {
        val location = fetchLocation()
        val contacts = loadContacts()

        if (contacts.isEmpty()) {
            Log.w(TAG, "Nu există contacte de urgență configurate!")
            stopSelf(); return
        }

        val locationText = location?.let {
            "https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "Locație indisponibilă"

        val message = buildMessage(locationText)

        contacts.forEach { number -> sendSms(number, message) }

        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchLocation(): Location? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            fusedLocation.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        cont.resume(loc)
                    } else {
                        // Ultimă locație necunoscută → cerere fresh cu timeout 10s
                        fusedLocation.getCurrentLocation(
                            CurrentLocationRequest.Builder()
                                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                                .setDurationMillis(10_000L)
                                .build(),
                            null
                        )
                            .addOnSuccessListener { cont.resume(it) }
                            .addOnFailureListener { cont.resume(null) }
                    }
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    private fun sendSms(number: String, message: String) {
        runCatching {
            val sms = getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(message)
            if (parts.size == 1) {
                sms.sendTextMessage(number, null, message, null, null)
            } else {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            }
            Log.i(TAG, "SMS trimis la $number")
        }.onFailure { Log.e(TAG, "SMS eșuat la $number: ${it.message}") }
    }

    private fun buildMessage(locationUrl: String): String {
        val time = SimpleDateFormat("HH:mm, dd MMM yyyy", Locale("ro", "RO")).format(Date())
        return "URGENTA: Utilizatorul aplicatiei Guardian Vision ar putea fi cazut. " +
               "Ora: $time. Locatie: $locationUrl"
    }

    private fun loadContacts(): List<String> {
        val prefs = getSharedPreferences(VisionForegroundService.PREFS_NAME, MODE_PRIVATE)
        return prefs.getStringSet("emergency_numbers", emptySet())
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}