package ro.pub.cs.system.eim.aplicatie_hack.wear.onboarding

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gestionează fluxul de onboarding vocal la primul rulaj.
 *
 * Pașii care cer confirmare gestuală (ex. triple-tap, long-press) folosesc
 * [confirmGesture] apelat din WatchMainActivity.
 * Dacă utilizatorul nu confirmă în 30s, pasul este repetat o dată.
 */
class OnboardingManager(private val context: Context) {

    companion object {
        private const val TAG = "Onboarding"
        private const val PREFS      = "guardian_prefs"
        private const val KEY_DONE   = "onboarding_done"
        private const val CONFIRM_TIMEOUT_MS = 30_000L
    }

    enum class WaitingFor { NOTHING, TRIPLE_TAP, LONG_PRESS }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _waitingFor = MutableStateFlow(WaitingFor.NOTHING)
    val waitingFor: StateFlow<WaitingFor> = _waitingFor

    val isFirstRun: Boolean
        get() = !prefs.getBoolean(KEY_DONE, false)

    private var gestureDeferred: CompletableDeferred<Unit>? = null

    /**
     * Apelat din WatchMainActivity când un gest specific este detectat.
     * Dacă onboardingul așteaptă acel gest, îl confirmă.
     */
    fun confirmGesture(type: WaitingFor) {
        if (_waitingFor.value == type) {
            gestureDeferred?.complete(Unit)
        }
    }

    /**
     * Rulează întregul flux de onboarding.
     * [speakFn] este furnizat de VoiceAssistantManager.speak().
     */
    suspend fun runOnboarding(speakFn: suspend (String) -> Unit) {
        Log.i(TAG, "Onboarding pornit")

        speakFn(
            "Bun venit la Guardian Vision. " +
            "Aceasta este o aplicație de asistență pentru persoanele cu deficiențe de vedere. " +
            "Vă voi ghida prin comenzile disponibile."
        )

        // Pasul 1 — Triple-tap pe ecran
        speakFn(
            "Prima comandă: atingerea triplă pe ecran pornește sau oprește detectarea obstacolelor. " +
            "Acum vă rog să atingeți ecranul de trei ori pentru a confirma că ați înțeles."
        )
        if (!awaitGesture(WaitingFor.TRIPLE_TAP)) {
            speakFn("Nu am detectat atingerea triplă. Vă rog să atingeți ecranul de trei ori rapid.")
            awaitGesture(WaitingFor.TRIPLE_TAP)
        }
        speakFn("Excelent. Triple-tap confirmat.")

        // Pasul 2 — Double-tap pe ecran (fără confirmare — nu vrem să declanșeze descrierea)
        speakFn(
            "A doua comandă: atingerea dublă pe ecran, când urmărirea este activă, " +
            "descrie vocal scena din jurul vostru."
        )

        // Pasul 3 — Long-press pe ecran
        speakFn(
            "A treia comandă: apăsați lung ecranul timp de două secunde pentru a activa asistentul vocal. " +
            "Asistentul spune Ascult și vă ascultă comanda. " +
            "Când ați terminat de vorbit, spuneți gata pentru a trimite comanda imediat. " +
            "De exemplu: Vreau să ajung la Piața Victoriei, gata. " +
            "Acum vă rog să apăsați lung ecranul pentru a confirma."
        )
        if (!awaitGesture(WaitingFor.LONG_PRESS)) {
            speakFn("Nu am detectat apăsarea lungă. Vă rog să țineți degetul pe ecran timp de două secunde.")
            awaitGesture(WaitingFor.LONG_PRESS)
        }
        speakFn("Asistent vocal confirmat.")

        // Pasul 4 — Triple-press buton fizic
        speakFn(
            "A patra comandă: apăsând de trei ori rapid butonul lateral al ceasului, " +
            "aplicația Guardian Vision se deschide pe telefonul asociat."
        )

        // Pasul 5 — Cerere permisiuni
        speakFn(
            "Pentru funcționarea completă, aplicația are nevoie de acces la microfon și locație. " +
            "Dacă apare o cerere de permisiune pe ecran, vă rog să o acceptați."
        )

        // Final
        speakFn(
            "Configurarea s-a finalizat. " +
            "Guardian Vision este acum activ și vă protejează. " +
            "Apăsați lung oricând pentru a activa asistentul vocal."
        )

        markCompleted()
        Log.i(TAG, "Onboarding finalizat")
    }

    private suspend fun awaitGesture(type: WaitingFor): Boolean {
        gestureDeferred = CompletableDeferred()
        _waitingFor.value = type
        val confirmed = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            gestureDeferred!!.await()
            true
        } ?: false
        _waitingFor.value = WaitingFor.NOTHING
        gestureDeferred = null
        return confirmed
    }

    private fun markCompleted() {
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }
}