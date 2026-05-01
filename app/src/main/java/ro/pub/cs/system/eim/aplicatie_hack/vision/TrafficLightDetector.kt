package ro.pub.cs.system.eim.aplicatie_hack.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

enum class TrafficLightColor { RED, GREEN, UNKNOWN }

/**
 * Detecție culoare semafor prin analiza spațiului de culoare HSV.
 *
 * De ce HSV în loc de RGB?
 * HSV separă nuanța (hue) de luminozitate — mult mai robust la variații de iluminare
 * (umbră, noapte, soare direct). RGB confundă "roșu întunecat" cu "maro deschis".
 *
 * Strategie: detectorul ML Kit localizează semaforul → noi analizăm culoarea din bbox.
 * Jumătatea superioară a bbox = lumina roșie, jumătatea inferioară = lumina verde.
 */
class TrafficLightDetector {

    companion object {
        // Roșu: nuanța HSV face wrap în jurul cercului cromatic (0° și 360°≡0°)
        private val RED_H1 = 0f..10f
        private val RED_H2 = 160f..180f
        private const val RED_S_MIN  = 70f
        private const val RED_V_MIN  = 70f

        // Verde pietonal: galben-verde tipic pentru semafoarele românești
        private val GREEN_H = 40f..100f
        private const val GREEN_S_MIN = 60f
        private const val GREEN_V_MIN = 60f

        // Pragul minim de pixeli colorați pentru a confirma culoarea (15% din suprafață)
        private const val DOMINANCE_THRESHOLD = 0.15f
    }

    fun detectColor(bitmap: Bitmap, bbox: android.graphics.Rect): TrafficLightColor {
        val x = bbox.left.coerceIn(0, bitmap.width - 1)
        val y = bbox.top.coerceIn(0, bitmap.height - 1)
        val w = (bbox.width()).coerceIn(1, bitmap.width - x)
        val h = (bbox.height()).coerceIn(1, bitmap.height - y)

        // Semaforul vertical: roșu sus, verde jos — analizăm fiecare jumătate separat
        val topHalf = Bitmap.createBitmap(bitmap, x, y, w, (h / 2).coerceAtLeast(1))
        val botHalf = Bitmap.createBitmap(bitmap, x, y + h / 2, w, (h - h / 2).coerceAtLeast(1))

        val redScore   = colorScore(topHalf, TrafficLightColor.RED)
        val greenScore = colorScore(botHalf, TrafficLightColor.GREEN)

        return when {
            redScore   > DOMINANCE_THRESHOLD -> TrafficLightColor.RED
            greenScore > DOMINANCE_THRESHOLD -> TrafficLightColor.GREEN
            else -> TrafficLightColor.UNKNOWN
        }
    }

    private fun colorScore(bmp: Bitmap, target: TrafficLightColor): Float {
        val total  = bmp.width * bmp.height
        val pixels = IntArray(total)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        val hsv = FloatArray(3)
        var matches = 0
        for (pixel in pixels) {
            Color.colorToHSV(pixel, hsv)
            if (matchesColor(hsv, target)) matches++
        }
        return matches.toFloat() / total
    }

    private fun matchesColor(hsv: FloatArray, target: TrafficLightColor): Boolean {
        val h = hsv[0]; val s = hsv[1] * 255f; val v = hsv[2] * 255f
        return when (target) {
            TrafficLightColor.RED -> {
                (h in RED_H1 || h in RED_H2) && s >= RED_S_MIN && v >= RED_V_MIN
            }
            TrafficLightColor.GREEN -> {
                h in GREEN_H && s >= GREEN_S_MIN && v >= GREEN_V_MIN
            }
            TrafficLightColor.UNKNOWN -> false
        }
    }
}