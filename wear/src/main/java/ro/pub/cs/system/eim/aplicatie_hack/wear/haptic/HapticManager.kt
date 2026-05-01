package ro.pub.cs.system.eim.aplicatie_hack.wear.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import ro.pub.cs.system.eim.aplicatie_hack.model.HapticEvent

/**
 * Vocabular haptic complet pentru ceas.
 *
 * Principii de design:
 *  - Urgența crește proporțional cu frecvența și intensitatea (mapping natural)
 *  - Fiecare pattern e ritmic distinct — imposibil de confundat fără vedere
 *  - Amplitudini limitate la 230 (nu 255) pentru longevitate motor pe Pixel Watch 4
 *  - createWaveform(timings, amplitudes, repeat): controlul cel mai fin disponibil
 */
class HapticManager(context: Context) {

    private val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)!!
    }

    private companion object {
        const val A_SOFT   = 80
        const val A_MEDIUM = 160
        const val A_STRONG = 230
    }

    /**
     * Vocabular:
     *
     *  DISTANT      ·          50ms          — un tap ușor, "ceva e acolo"
     *  APPROACHING  · ·        50·100·50ms   — "se apropie"
     *  IMMINENT     ▓▓▓▓▓▓▓   30ms on/off×7 — buzz rapid, imposibil de ignorat
     *  GREEN        —··        300·100·80·80 — lung-scurt-scurt = "GO"
     *  RED          ——         300·150·300   — două lungi = "STOP STOP"
     *  DEVIATION    · · ·      100·200×3     — 3 pulsuri lente = "ajustează"
     */
    fun play(event: HapticEvent) {
        if (!vib.hasVibrator()) return
        vib.cancel()
        vib.vibrate(effectFor(event))
    }

    private fun effectFor(event: HapticEvent): VibrationEffect =
        when (event) {
            HapticEvent.OBSTACLE_DISTANT ->
                waveform(longs(0, 50), ints(0, A_SOFT))

            HapticEvent.OBSTACLE_APPROACHING ->
                waveform(longs(0, 50, 100, 50), ints(0, A_MEDIUM, 0, A_MEDIUM))

            HapticEvent.DANGER_IMMINENT ->
                // Rapid alternating — 7 cicluri de 30ms ON/OFF
                waveform(
                    longs(0, 30, 25, 30, 25, 30, 25, 30, 25, 30, 25, 30, 25, 30),
                    ints(0, A_STRONG, 0, A_STRONG, 0, A_STRONG, 0, A_STRONG, 0, A_STRONG, 0, A_STRONG, 0, A_STRONG)
                )

            HapticEvent.TRAFFIC_LIGHT_GREEN ->
                waveform(longs(0, 300, 100, 80, 80, 80), ints(0, A_STRONG, 0, A_MEDIUM, 0, A_MEDIUM))

            HapticEvent.TRAFFIC_LIGHT_RED ->
                waveform(longs(0, 300, 150, 300), ints(0, A_STRONG, 0, A_STRONG))

            HapticEvent.ROUTE_DEVIATION ->
                waveform(longs(0, 100, 200, 100, 200, 100), ints(0, A_MEDIUM, 0, A_MEDIUM, 0, A_MEDIUM))
        }

    /**
     * Pattern SOS pentru alertă cădere — se repetă în buclă până la cancel().
     */
    fun playSOS() {
        if (!vib.hasVibrator()) return
        vib.cancel()
        // Morse SOS: · · ·  — — —  · · ·
        vib.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(
                    0,   80, 80,  80, 80,  80, 180,
                    200, 200, 150, 200, 150, 200, 250,
                    150, 80, 80,  80, 80,  80, 900
                ),
                intArrayOf(
                    0, A_STRONG, 0, A_STRONG, 0, A_STRONG, 0,
                    0, A_STRONG, 0, A_STRONG, 0, A_STRONG, 0,
                    0, A_STRONG, 0, A_STRONG, 0, A_STRONG, 0
                ),
                0 // repeat de la index 0 = buclă infinită
            )
        )
    }

    fun stop() = vib.cancel()

    // Helpers pentru sintaxă mai curată
    private fun waveform(timings: LongArray, amplitudes: IntArray) =
        VibrationEffect.createWaveform(timings, amplitudes, -1)

    private fun longs(vararg v: Long) = v
    private fun ints(vararg v: Int) = v
}