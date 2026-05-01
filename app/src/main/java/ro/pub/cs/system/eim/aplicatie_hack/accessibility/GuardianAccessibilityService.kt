package ro.pub.cs.system.eim.aplicatie_hack.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import ro.pub.cs.system.eim.aplicatie_hack.service.VisionForegroundService

/**
 * Permite pornirea/oprirea sistemului cu **triple-press pe volum jos**,
 * chiar dacă ecranul este blocat sau aplicația nu este deschisă.
 *
 * De ce AccessibilityService și nu un TileService/QuickSettings?
 * AccessibilityService este singura metodă care interceptează key events globale
 * (inclusiv cu ecranul stins în unele configurații). TileService necesită gesture
 * manual în panoul de notificări — nu accesibil pentru nevăzători.
 *
 * Utilizatorul trebuie să activeze serviciul manual din Setări → Accesibilitate.
 */
class GuardianAccessibilityService : AccessibilityService() {

    private val pressTimes = mutableListOf<Long>()
    private val WINDOW_MS  = 1_400L   // 3 prese în 1.4s
    private val REQUIRED   = 3

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.action  != KeyEvent.ACTION_DOWN) return false

        val now = System.currentTimeMillis()
        pressTimes.add(now)
        pressTimes.removeAll { now - it > WINDOW_MS }

        if (pressTimes.size >= REQUIRED) {
            pressTimes.clear()
            toggleVisionService()
            return true // consumăm evenimentul — volumul nu se schimbă
        }
        return false
    }

    private fun toggleVisionService() {
        val prefs = getSharedPreferences(VisionForegroundService.PREFS_NAME, MODE_PRIVATE)
        val isRunning = prefs.getBoolean(VisionForegroundService.KEY_RUNNING, false)

        val intent = Intent(this, VisionForegroundService::class.java)
        if (isRunning) {
            stopService(intent)
        } else {
            startForegroundService(intent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        // FLAG_REQUEST_FILTER_KEY_EVENTS necesar pentru onKeyEvent
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }
}