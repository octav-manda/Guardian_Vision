package ro.pub.cs.system.eim.aplicatie_hack.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import ro.pub.cs.system.eim.aplicatie_hack.MainActivity

/**
 * Primește toate evenimentele de la ceas prin Wearable Data Layer și Message Layer.
 *
 * ## Arhitectura de transport (DataClient vs MessageClient)
 *
 * | Canal        | Path                          | Direcție     | Motiv alegere              |
 * |--------------|-------------------------------|--------------|----------------------------|
 * | DataClient   | /emergency/fall               | Watch→Phone  | Persistent, supraviețuiește BLE drop |
 * | DataClient   | /emergency/fall_cancel        | Watch→Phone  | Persistent (anulare sigură) |
 * | MessageClient| /remote/launch                | Watch→Phone  | Fire-and-forget, nu persistăm |
 * | MessageClient| /command/describe_scene       | Watch→Phone  | Fire-and-forget, real-time |
 * | MessageClient| /command/toggle_tracking      | Watch→Phone  | Fire-and-forget, toggle imediat |
 *
 * ## Ciclul de viață al serviciului
 * [WearableListenerService] este pornit automat de sistemul Android când sosesc date
 * pe path-urile declarate în manifest. Procesul este adus din background, evenimentul
 * este procesat, iar serviciul se oprește singur — nu consumă resurse permanent.
 *
 * ## Manifest
 * PhoneDataLayerService are 3 `intent-filter` în AndroidManifest:
 *  1. `DATA_CHANGED` pe `/emergency` (căderi)
 *  2. `MESSAGE_RECEIVED` pe `/remote` (lansare app)
 *  3. `MESSAGE_RECEIVED` pe `/command` (comenzi: describe, toggle tracking)
 */
class PhoneDataLayerService : WearableListenerService() {

    companion object {
        const val PATH_FALL_EVENT     = "/emergency/fall"
        const val PATH_FALL_CANCEL    = "/emergency/fall_cancel"
        const val PATH_REMOTE_LAUNCH  = "/remote/launch"
        const val PATH_DESCRIBE_SCENE = "/command/describe_scene"
        const val PATH_TOGGLE_TRACKING = "/command/toggle_tracking"

        private const val TAG = "PhoneDataLayer"
    }

    /**
     * Recepționează modificările de date publicate prin [com.google.android.gms.wearable.DataClient].
     *
     * Apelat pentru:
     *  - [PATH_FALL_EVENT]: cădere confirmată de senzori după fereastra de 15s
     *  - [PATH_FALL_CANCEL]: utilizatorul a atins ecranul ceasului → anulare
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            buffer.filter { it.type == DataEvent.TYPE_CHANGED }
                .forEach { event ->
                    val path = event.dataItem.uri.path ?: return@forEach
                    Log.d(TAG, "DataChanged: $path")

                    when (path) {
                        PATH_FALL_EVENT  -> handleFallConfirmed(event)
                        PATH_FALL_CANCEL -> Log.i(TAG, "Cădere anulată de utilizator pe ceas")
                    }
                }
        }
    }

    /**
     * Recepționează mesajele fire-and-forget de la ceas prin [com.google.android.gms.wearable.MessageClient].
     *
     * Apelat pentru:
     *  - [PATH_REMOTE_LAUNCH]: triple-press buton fizic ceas → aducem MainActivity în prim-plan
     *  - [PATH_DESCRIBE_SCENE]: double-tap ecran ceas (tracking activ) → descriere scenă vocală
     *  - [PATH_TOGGLE_TRACKING]: triple-tap ecran ceas → pornire/oprire VisionForegroundService
     */
    override fun onMessageReceived(event: MessageEvent) {
        Log.d(TAG, "Message: ${event.path}")
        when (event.path) {
            PATH_REMOTE_LAUNCH   -> launchMainActivity()
            PATH_DESCRIBE_SCENE  -> triggerSceneDescription()
            PATH_TOGGLE_TRACKING -> toggleTracking()
        }
    }

    /**
     * Pornește [EmergencyResponseService] (GPS + SMS) dacă datele din DataItem
     * conțin `confirmed = true`. Câmpul `confirmed` protejează împotriva
     * redeclanșărilor datorate sincronizării DataClient la reconectare BLE.
     */
    private fun handleFallConfirmed(event: DataEvent) {
        val data = DataMapItem.fromDataItem(event.dataItem).dataMap
        if (!data.getBoolean("confirmed", false)) return

        Log.i(TAG, "Cădere confirmată de ceas → pornesc EmergencyResponseService")
        startService(
            Intent(this, EmergencyResponseService::class.java).apply {
                action = EmergencyResponseService.ACTION_FALL_CONFIRMED
                putExtra("fall_ts", data.getLong("ts"))
            }
        )
    }

    /**
     * Aduce [MainActivity] în prim-plan fără să o recreeze dacă deja rulează.
     *
     * [Intent.FLAG_ACTIVITY_REORDER_TO_FRONT]: dacă task-ul există în stack, îl mută
     * la suprafață fără a crea o instanță nouă.
     * [Intent.FLAG_ACTIVITY_NEW_TASK]: necesar când lansăm din non-Activity context (Service).
     */
    private fun launchMainActivity() {
        Log.i(TAG, "Remote launch → MainActivity")
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
        )
    }

    /**
     * Declanșează [SceneDescriptionManager.describe] pe telefonul curent.
     *
     * Trimitem acțiunea [VisionForegroundService.ACTION_DESCRIBE_SCENE] ca intent la
     * serviciu. Serviciul verifică dacă există un frame recent în
     * [VisionForegroundService.lastFrame] și îl trimite la Gemini dacă da.
     * Dacă serviciul nu rulează (tracking oprit), comanda este ignorată silențios.
     */
    private fun triggerSceneDescription() {
        Log.i(TAG, "Describe scene din ceas → VisionForegroundService")
        startService(
            Intent(this, VisionForegroundService::class.java).apply {
                action = VisionForegroundService.ACTION_DESCRIBE_SCENE
            }
        )
    }

    /**
     * Comutare pornit/oprit pentru [VisionForegroundService] (camera + ML Kit).
     *
     * Citim starea curentă din [SharedPreferences] folosind aceeași cheie
     * [VisionForegroundService.KEY_RUNNING] pe care o scrie serviciul la start/stop.
     * Dacă serviciul rulează → oprim. Dacă nu → pornim ca foreground service.
     *
     * Aceasta implementează funcționalitatea "triple-tap ecran ceas → toggle tracking"
     * cerută de [ro.pub.cs.system.eim.aplicatie_hack.wear.WatchMainActivity].
     */
    private fun toggleTracking() {
        val isRunning = getSharedPreferences(VisionForegroundService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(VisionForegroundService.KEY_RUNNING, false)

        if (isRunning) {
            stopService(Intent(this, VisionForegroundService::class.java))
            Log.i(TAG, "Tracking OPRIT de pe ceas")
        } else {
            startForegroundService(Intent(this, VisionForegroundService::class.java))
            Log.i(TAG, "Tracking PORNIT de pe ceas")
        }
    }
}
