package ro.pub.cs.system.eim.aplicatie_hack.wearable

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ro.pub.cs.system.eim.aplicatie_hack.model.HapticEvent

/**
 * Trimite comenzi haptic de la telefon la ceas prin Wearable [MessageClient].
 *
 * ## De ce MessageClient și nu DataClient?
 * MessageClient = fire-and-forget, fără persistență. Un pattern haptic de obstacol
 * expirat (detectat acum 2s) nu trebuie redat după o reconectare BLE — ar fi
 * informație înșelătoare. DataClient persistă și redă la reconectare (greșit pentru
 * haptic real-time). DataClient rămâne corect **doar** pentru fall events.
 *
 * ## Optimizare latență — cache nod
 * Problema: [NodeClient.getConnectedNodes] face un round-trip BLE la fiecare apel
 * (~50–200ms). La 10fps inferență cu debounce 900ms, asta adaugă ~100–200ms per mesaj.
 *
 * Soluție: cache `nodeId` după prima descoperire. La eșecul trimiterii (nod deconectat),
 * invalidăm cache-ul și refacem descoperirea o singură dată.
 *
 * ```
 * Prima trimitere : getConnectedNodes() → cache nodeId → sendMessage  [200ms]
 * Trimiteri ulterioare : sendMessage direct din cache               [<50ms]
 * La deconectare/reconectare: sendMessage eșuează → cache = null
 *                              → getConnectedNodes() din nou → sendMessage [200ms]
 * ```
 */
class WearableMessenger(private val context: Context) {

    companion object {
        const val PATH_HAPTIC = "/haptic"
        private const val TAG = "WearableMessenger"
    }

    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient    = Wearable.getNodeClient(context)

    /** SupervisorJob: un send eșuat nu anulează celelelte. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * ID-ul nodului ceas, descoperit la primul mesaj și reutilizat ulterior.
     * @Volatile asigură că invalidarea (null) în thread-ul de retry
     * este vizibilă imediat în alte coroutine.
     */
    @Volatile private var cachedNodeId: String? = null

    /**
     * Trimite un eveniment haptic la ceas.
     *
     * Apelat din [ro.pub.cs.system.eim.aplicatie_hack.service.VisionForegroundService]
     * la fiecare decizie de feedback (obstacol, semafor, etc.) cu debounce de 900ms.
     *
     * @param event Tipul de feedback haptic definit în [HapticEvent].
     */
    fun send(event: HapticEvent) {
        scope.launch {
            runCatching {
                val nodeId = cachedNodeId ?: discoverNearbyNode()
                    ?.also { cachedNodeId = it }
                    ?: return@runCatching

                // Trimitem cu node-ul din cache
                val sendResult = runCatching {
                    messageClient.sendMessage(
                        nodeId, PATH_HAPTIC, event.name.toByteArray(Charsets.UTF_8)
                    ).await()
                }

                if (sendResult.isFailure) {
                    // Node-ul din cache s-a deconectat — refacem descoperirea o singură dată
                    Log.d(TAG, "Send eșuat cu nod cacheuit, invalidez și redescopăr")
                    cachedNodeId = null
                    val freshNode = discoverNearbyNode()?.also { cachedNodeId = it }
                        ?: return@runCatching

                    messageClient.sendMessage(
                        freshNode, PATH_HAPTIC, event.name.toByteArray(Charsets.UTF_8)
                    ).await()
                }

                Log.v(TAG, "Haptic trimis: $event → $cachedNodeId")
            }.onFailure {
                Log.w(TAG, "Send failed definitiv: ${it.message}")
            }
        }
    }

    /**
     * Interogează Wear OS pentru nodurile conectate și returnează cel mai apropiat.
     * Fallback la primul nod disponibil dacă niciunul nu e marcat `isNearby`.
     *
     * @return Node ID (String) sau null dacă nu există niciun ceas conectat.
     */
    private suspend fun discoverNearbyNode(): String? {
        val nodes = nodeClient.connectedNodes.await()
        return (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
            .also { if (it == null) Log.w(TAG, "Niciun ceas conectat") }
    }

    /**
     * Trimite text arbitrar la ceas pe [path]-ul specificat.
     * Același mecanism de retry ca [send]: la eșec cu nodul cacheuit, redescopăr o singură dată.
     *
     * @return `true` dacă mesajul a fost livrat, `false` dacă ceasul nu e conectat sau trimiterea a eșuat.
     */
    suspend fun sendTextMessage(path: String, text: String): Boolean =
        runCatching {
            val nodeId = cachedNodeId ?: discoverNearbyNode()
                ?.also { cachedNodeId = it }
                ?: return false

            val result = runCatching {
                messageClient.sendMessage(nodeId, path, text.toByteArray(Charsets.UTF_8)).await()
            }

            if (result.isFailure) {
                Log.d(TAG, "sendTextMessage eșuat cu nod cacheuit, invalidez și redescopăr")
                cachedNodeId = null
                val freshNode = discoverNearbyNode()?.also { cachedNodeId = it }
                    ?: return false
                messageClient.sendMessage(freshNode, path, text.toByteArray(Charsets.UTF_8)).await()
            }

            Log.v(TAG, "Text trimis pe $path → $cachedNodeId (${text.length} chars)")
            true
        }.getOrElse {
            Log.w(TAG, "sendTextMessage eșuat definitiv pe $path: ${it.message}")
            false
        }

    /**
     * Invalidează cache-ul nodului. Apelabil extern dacă se detectează o
     * deconectare explicită (ex. din [ro.pub.cs.system.eim.aplicatie_hack.service.PhoneDataLayerService]).
     */
    fun invalidateNodeCache() {
        cachedNodeId = null
    }
}
