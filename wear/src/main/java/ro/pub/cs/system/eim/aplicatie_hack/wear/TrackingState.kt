package ro.pub.cs.system.eim.aplicatie_hack.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton care sincronizează starea de urmărire (tracking) între
 * WatchDataLayerService (care primește actualizarea de pe telefon) și
 * WatchMainActivity (care decide dacă double-tap declanșează descrierea scenei).
 */
object TrackingState {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    fun update(active: Boolean) {
        _isActive.value = active
    }
}
