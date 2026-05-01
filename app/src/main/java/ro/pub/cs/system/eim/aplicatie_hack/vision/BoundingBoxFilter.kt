package ro.pub.cs.system.eim.aplicatie_hack.vision

import com.google.mlkit.vision.objects.DetectedObject

data class FilteredObstacle(
    val label: String,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val urgency: UrgencyLevel,
)

enum class UrgencyLevel(val priority: Int) {
    DISTANT(1), APPROACHING(2), IMMINENT(3)
}

class BoundingBoxFilter {

    companion object {
        // Obiectele cu centrul sub această linie Y sunt la nivelul solului → ignorate
        private const val GROUND_CENTER_Y = 0.65f

        // Vârful bbox sub această valoare = obiect la nivelul capului
        private const val HEAD_TOP_Y = 0.35f

        // Filtre dimensiune: evită zgomot (prea mici) sau context (prea mari)
        private const val MIN_H = 0.05f
        private const val MAX_H = 0.90f

        // Coridorul de mers: centrul X trebuie să fie în această zonă
        private const val PATH_LEFT  = 0.20f
        private const val PATH_RIGHT = 0.80f
    }

    /**
     * Returnează lista de obstacole relevante (de la talie în sus), sortate descrescător după urgență.
     *
     * @param tiltDeg Unghiul de înclinare al telefonului față de vertical (din accelerometru).
     *                Valoare pozitivă = telefon coborât → mutăm pragul în sus ca să nu prindă solul.
     */
    fun filter(
        objects: List<DetectedObject>,
        imageH: Float,
        imageW: Float,
        tiltDeg: Float = 0f,
    ): List<FilteredObstacle> {
        val groundThreshold = GROUND_CENTER_Y + (tiltDeg.coerceIn(-20f, 20f) * 0.008f)

        return objects.mapNotNull { obj ->
            val b = obj.boundingBox

            val normTop    = b.top    / imageH
            val normBottom = b.bottom / imageH
            val normLeft   = b.left   / imageW
            val normRight  = b.right  / imageW
            val cy = (normTop + normBottom) / 2f
            val cx = (normLeft + normRight) / 2f
            val h  = normBottom - normTop
            val w  = normRight  - normLeft

            // Filtru rapid — ordinea: cele mai frecvente early-exit primele
            if (h < MIN_H || h > MAX_H) return@mapNotNull null
            if (cy > groundThreshold) return@mapNotNull null
            if (cx !in PATH_LEFT..PATH_RIGHT) return@mapNotNull null

            val bestLabel = obj.labels.maxByOrNull { it.confidence }
            if ((bestLabel?.confidence ?: 0f) < 0.40f) return@mapNotNull null

            FilteredObstacle(
                label      = bestLabel?.text ?: "object",
                confidence = bestLabel?.confidence ?: 0f,
                centerX    = cx,
                centerY    = cy,
                width      = w,
                height     = h,
                urgency    = classifyUrgency(normTop, cx, h, w),
            )
        }.sortedByDescending { it.urgency.priority }
    }

    private fun classifyUrgency(normTop: Float, cx: Float, h: Float, w: Float): UrgencyLevel {
        val isHeadLevel = normTop < HEAD_TOP_Y
        val isLarge     = w > 0.35f && h > 0.25f
        val isCentered  = cx in 0.30f..0.70f

        return when {
            isHeadLevel && isCentered            -> UrgencyLevel.IMMINENT
            isLarge     && isCentered            -> UrgencyLevel.APPROACHING
            isHeadLevel || (isCentered && w > 0.20f) -> UrgencyLevel.APPROACHING
            else                                  -> UrgencyLevel.DISTANT
        }
    }
}