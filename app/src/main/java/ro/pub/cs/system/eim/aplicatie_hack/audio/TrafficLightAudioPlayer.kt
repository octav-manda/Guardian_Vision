package ro.pub.cs.system.eim.aplicatie_hack.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/**
 * Redă sunete de alertă pentru semafoare detectate de camera telefonului.
 *
 * ## De ce SoundPool și nu MediaPlayer?
 * - **Latență**: SoundPool pre-încarcă sunetele în RAM → redare în ~5ms.
 *   MediaPlayer deschide fișierul la fiecare apel → 100–300ms (inacceptabil pentru
 *   feedback real-time la 10fps inferență).
 * - **Anti-overlap**: `maxStreams=1` oprește automat sunetul curent când se
 *   solicită un sunet nou (ex: roșu → verde rapid). MediaPlayer ar necesita
 *   `stop()`+`prepare()` manual.
 * - **Anti-loop**: parametrul `loop=0` în `play()` → un singur redare, fără buclă.
 *
 * ## Resurse necesare
 * Adaugă fișierele audio în `app/src/main/res/raw/`:
 * ```
 * app/src/main/res/raw/
 *   ├── red_light.mp3    (ex. "ROȘU" voce sau beep scurt)
 *   └── green_light.mp3  (ex. "VERDE" voce sau beep diferit)
 * ```
 * Dacă fișierele lipsesc, clasa funcționează silențios (nu crează erori de compilare).
 *
 * ## Integrare în flux
 * ```
 * VisionForegroundService.analyzeFrame()
 *   └─→ TrafficLightColor.RED/GREEN detectat prin HSV
 *         └─→ TrafficLightAudioPlayer.playRed() / playGreen()
 *               └─→ SoundPool.play() → difuzor telefon
 * ```
 *
 * ## Debounce recomandat
 * Apelantul ([ro.pub.cs.system.eim.aplicatie_hack.service.VisionForegroundService])
 * trebuie să mențină un debounce de minim 5 secunde între apeluri pentru aceeași culoare,
 * altfel sunetul se va auzi la fiecare frame cu semafor detectat (~900ms).
 */
class TrafficLightAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "TrafficAudio"

        /** Un singur sunet activ simultan — al doilea îl oprește pe primul. */
        private const val MAX_STREAMS = 1
    }

    private var soundPool: SoundPool? = null
    private var soundIdRed   = 0
    private var soundIdGreen = 0

    @Volatile private var redReady   = false
    @Volatile private var greenReady = false

    init {
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(audioAttrs)
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, sampleId, status ->
                    val loaded = (status == 0)
                    when (sampleId) {
                        soundIdRed   -> { redReady   = loaded; Log.d(TAG, "red_light: ready=$loaded") }
                        soundIdGreen -> { greenReady = loaded; Log.d(TAG, "green_light: ready=$loaded") }
                    }
                }
                soundIdRed   = loadResource(pool, "red_light")
                soundIdGreen = loadResource(pool, "green_light")
            }
    }

    /**
     * Încarcă un fișier din `res/raw/` prin nume, fără referință directă la `R.raw.*`.
     * Returnează 0 dacă resursa nu există — SoundPool ignoră ID-ul 0 la `play()`.
     *
     * Avantaj față de `R.raw.nume`: codul **compilează chiar dacă fișierul MP3 lipsește**,
     * evitând erori de build în medii fără resurse audio.
     *
     * @param pool SoundPool în care se încarcă resursa.
     * @param name Numele fișierului fără extensie (ex. `"red_light"` → `res/raw/red_light.mp3`).
     * @return ID-ul streamului în pool, sau 0 dacă resursa nu a fost găsită.
     */
    private fun loadResource(pool: SoundPool, name: String): Int {
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        if (resId == 0) {
            Log.w(TAG,
                "Fișierul 'app/src/main/res/raw/$name.mp3' lipsește. " +
                "Adaugă fișierul audio pentru feedback la semafor."
            )
            return 0
        }
        return runCatching { pool.load(context, resId, 1) }
            .onFailure { Log.e(TAG, "Eroare la încărcare $name: ${it.message}") }
            .getOrDefault(0)
    }

    /**
     * Redă sunetul pentru semafor ROȘU.
     * Nu face nimic dacă fișierul `red_light.mp3` lipsește din `res/raw/`.
     */
    fun playRed() {
        if (redReady && soundIdRed != 0) {
            // volLeft=1f, volRight=1f, priority=1, loop=0 (un singur ciclu), rate=1f
            soundPool?.play(soundIdRed, 1f, 1f, 1, 0, 1f)
            Log.d(TAG, "▶ red_light")
        }
    }

    /**
     * Redă sunetul pentru semafor VERDE.
     * Nu face nimic dacă fișierul `green_light.mp3` lipsește din `res/raw/`.
     */
    fun playGreen() {
        if (greenReady && soundIdGreen != 0) {
            soundPool?.play(soundIdGreen, 1f, 1f, 1, 0, 1f)
            Log.d(TAG, "▶ green_light")
        }
    }

    /**
     * Eliberează resursele SoundPool. Apelat din [ro.pub.cs.system.eim.aplicatie_hack.service.VisionForegroundService.onDestroy].
     * După `release()`, obiectul nu mai poate fi folosit.
     */
    fun release() {
        soundPool?.release()
        soundPool  = null
        redReady   = false
        greenReady = false
    }
}
