package ro.pub.cs.system.eim.aplicatie_hack.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import ro.pub.cs.system.eim.aplicatie_hack.wear.service.FallDetectionService

/**
 * Apare automat pe ecranul ceasului (și pe ecranul de blocare) când se detectează o cădere.
 *
 * Utilizatorul are 15 secunde să anuleze prin ORICE atingere a ecranului.
 * Dacă nu reacționează în 15s, FallDetectionService trimite evenimentul la telefon → SMS.
 *
 * Design accesibil:
 *  - Ecranul ÎNTREG este zona de atingere (maximizează șansa de a anula)
 *  - Countdown proeminent în centru
 *  - Contrast maxim: roșu/alb pe negru
 */
class FallAlertActivity : ComponentActivity() {

    private val countdownMs get() =
        intent.getLongExtra("countdown_ms", 15_000L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            FallAlertScreen(
                totalSeconds = (countdownMs / 1000).toInt(),
                onCancel     = {
                    startService(
                        Intent(this, FallDetectionService::class.java).apply {
                            action = FallDetectionService.ACTION_CANCEL
                        }
                    )
                    finish()
                }
            )
        }
    }
}

@Composable
private fun FallAlertScreen(totalSeconds: Int, onCancel: () -> Unit) {
    var remaining by remember { mutableIntStateOf(totalSeconds) }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000L)
            remaining--
        }
        // La 0: FallDetectionService se ocupă de notificare
    }

    // Întregul ecran este zona de atingere pentru anulare
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A0000))
            .clickable(
                indication        = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick           = onCancel
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text       = "ALERTĂ CĂDERE",
                color      = Color(0xFFFF4444),
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text       = "${remaining}s",
                color      = Color.White,
                fontSize   = 36.sp,
                fontWeight = FontWeight.ExtraBold,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text      = "Atinge ecranul\npentru a anula",
                color     = Color(0xFF00C853),
                fontSize  = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text      = "Alertă automată la 0s",
                color     = Color(0xFF666666),
                fontSize  = 9.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
