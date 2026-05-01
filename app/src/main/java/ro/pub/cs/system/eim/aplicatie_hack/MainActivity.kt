package ro.pub.cs.system.eim.aplicatie_hack

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ro.pub.cs.system.eim.aplicatie_hack.service.VisionForegroundService
import ro.pub.cs.system.eim.aplicatie_hack.ui.theme.Aplicatie_hackTheme
import ro.pub.cs.system.eim.aplicatie_hack.vision.SceneDescriptionManager

/**
 * Activity principală a telefonului — punct de intrare vizibil pentru utilizator.
 *
 * ## Funcționalități cheie
 *  - **Triple-tap oriunde pe ecran** → toggle pornire/oprire sistem (la fel ca butonul principal)
 *  - **Două ecrane distincte**: [ActiveScreen] (sistem pornit) / [InactiveScreen] (sistem oprit)
 *  - **BroadcastReceiver**: primește descrierile de scenă de la [SceneDescriptionManager]
 *    și le afișează ca dialog pop-up
 *
 * ## Triple-tap implementare (`PointerEventPass.Initial`)
 * Problema cu `pointerInput` standard: dacă un buton copil consumă tap-ul,
 * părintele nu mai vede evenimentul. Soluția: `PointerEventPass.Initial` procesează
 * evenimentele *înainte* ca copiii să le primească — tapurile pe butoane SE NUMĂRĂ
 * și butoanele FUNCȚIONEAZĂ normal în același timp (evenimentul nu e consumat).
 *
 * ## Separare debounce triple-tap
 * Fereastra de 600ms: după al 3-lea tap, contorul se resetează. Dacă nu vine
 * al treilea tap în 600ms de la primul, contorul se resetează automat.
 */
class MainActivity : ComponentActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(VisionForegroundService.PREFS_NAME, MODE_PRIVATE)
    }

    private var isActive           = mutableStateOf(false)
    private var showContactsDialog = mutableStateOf(false)
    private var popupMessage       = mutableStateOf<String?>(null)
    private var isErrorMessage     = mutableStateOf(false)

    /**
     * Ascultă orice scriere pe KEY_RUNNING în SharedPreferences.
     * Surse posibile: buton în MainActivity, triple-tap ceas (PhoneDataLayerService),
     * triple-press volum jos (GuardianAccessibilityService).
     * Toate actualizează aceeași cheie → UI-ul se sincronizează indiferent de sursă.
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == VisionForegroundService.KEY_RUNNING) {
            isActive.value = prefs.getBoolean(key, false)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startVisionService()
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SceneDescriptionManager.ACTION_SHOW_MESSAGE) {
                popupMessage.value   = intent.getStringExtra(SceneDescriptionManager.EXTRA_MESSAGE)
                isErrorMessage.value = intent.getBooleanExtra(SceneDescriptionManager.EXTRA_IS_ERROR, false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActive.value = prefs.getBoolean(VisionForegroundService.KEY_RUNNING, false)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        enableEdgeToEdge()

        val filter = IntentFilter(SceneDescriptionManager.ACTION_SHOW_MESSAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(messageReceiver, filter)
        }

        setContent {
            Aplicatie_hackTheme {
                MainScreen(
                    isActive          = isActive.value,
                    showContactsDlg   = showContactsDialog.value,
                    popupMsg          = popupMessage.value,
                    isErrorMsg        = isErrorMessage.value,
                    onToggle          = { handleToggle() },
                    onDescribeScene   = { triggerDescription() },
                    onContactsOpen    = { showContactsDialog.value = true },
                    onContactsSave    = { numbers ->
                        prefs.edit()
                            .putStringSet("emergency_numbers", numbers.toSet())
                            .apply()
                        showContactsDialog.value = false
                    },
                    onContactsDismiss = { showContactsDialog.value = false },
                    onDismissPopup    = { popupMessage.value = null },
                    savedNumbers      = prefs.getStringSet("emergency_numbers", emptySet())
                        ?.joinToString("\n") ?: "",
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Recitim starea în caz că serviciul s-a schimbat cât activitatea era în background
        isActive.value = prefs.getBoolean(VisionForegroundService.KEY_RUNNING, false)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        unregisterReceiver(messageReceiver)
        super.onDestroy()
    }

    private fun handleToggle() {
        if (isActive.value) {
            stopVisionService()
            return
        }
        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val allGranted = needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (allGranted) startVisionService() else permissionLauncher.launch(needed)
    }

    private fun startVisionService() {
        startForegroundService(Intent(this, VisionForegroundService::class.java))
        isActive.value = true
    }

    private fun stopVisionService() {
        stopService(Intent(this, VisionForegroundService::class.java))
        isActive.value = false
    }

    private fun triggerDescription() {
        startService(Intent(this, VisionForegroundService::class.java).apply {
            action = VisionForegroundService.ACTION_DESCRIBE_SCENE
        })
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMPOSABLE ROOT — Triple-tap overlay + routing spre ecranul corect
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Container root care adaugă triple-tap pe întregul ecran și rutează spre
 * [ActiveScreen] sau [InactiveScreen] pe baza stării [isActive].
 *
 * ## Strategia `PointerEventPass.Initial`
 * `awaitPointerEvent(PointerEventPass.Initial)` observă evenimentele *înainte*
 * ca orice copil să le proceseze. Nu consumăm evenimentul (nu apelăm
 * `event.changes.forEach { it.consume() }`), deci:
 *  - Butoanele din [ActiveScreen]/[InactiveScreen] primesc click-ul și reacționează normal.
 *  - Contorul de triple-tap incrementează la fiecare DOWN, inclusiv pe butoane.
 *
 * Rezultat: triple-tap funcționează oriunde pe ecran, indiferent dacă tapurile
 * ating butoane, text sau fundal.
 */
@Composable
private fun MainScreen(
    isActive: Boolean,
    showContactsDlg: Boolean,
    popupMsg: String?,
    isErrorMsg: Boolean,
    onToggle: () -> Unit,
    onDescribeScene: () -> Unit,
    onContactsOpen: () -> Unit,
    onContactsSave: (List<String>) -> Unit,
    onContactsDismiss: () -> Unit,
    onDismissPopup: () -> Unit,
    savedNumbers: String,
) {
    val tapCounter = remember { ScreenTapCounter() }
    val tapScope   = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Initial pass: vedem DOWN-urile înainte ca copiii să le proceseze.
                // NU consumăm — butoanele funcționează normal.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDown() }) {
                            tapCounter.handle(scope = tapScope, onTripleTap = onToggle)
                        }
                    }
                }
            }
    ) {
        if (isActive) {
            ActiveScreen(
                onDescribeScene   = onDescribeScene,
                onStop            = onToggle,
                onContactsOpen    = onContactsOpen,
            )
        } else {
            InactiveScreen(
                onStart        = onToggle,
                onContactsOpen = onContactsOpen,
            )
        }

        if (showContactsDlg) {
            EmergencyContactsDialog(
                initial   = savedNumbers,
                onSave    = { text ->
                    val numbers = text.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    onContactsSave(numbers)
                },
                onDismiss = onContactsDismiss,
            )
        }

        if (popupMsg != null) {
            InfoDialog(
                message   = popupMsg,
                isError   = isErrorMsg,
                onDismiss = onDismissPopup,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ECRAN ACTIV — sistemul detectează obstacole și semafor
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Ecranul principal când [VisionForegroundService] rulează.
 *
 * Afișează starea "SISTEM ACTIV" cu indicator de puls vizual,
 * butonul de descriere scenă și acțiunile secundare (oprire, contacte).
 */
@Composable
private fun ActiveScreen(
    onDescribeScene: () -> Unit,
    onStop: () -> Unit,
    onContactsOpen: () -> Unit,
) {
    val green = Color(0xFF00E676)
    val red   = Color(0xFFFF1744)
    val blue  = Color(0xFF1565C0)

    // Indicator de puls animat
    var pulseAlpha by remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            pulseAlpha = 0.3f; delay(600L)
            pulseAlpha = 1.0f; delay(600L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050F05))
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ── Header cu status ─────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text       = "SISTEM ACTIV",
                color      = green,
                fontSize   = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.semantics {
                    contentDescription = "Sistemul este activ"
                }
            )
            Spacer(Modifier.size(10.dp))
            // Indicator de puls
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(green.copy(alpha = pulseAlpha))
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text     = "Detectare obstacole · Semafor · Ceas",
            color    = Color(0xFF558855),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(48.dp))

        // ── Buton principal: Descrie Scena ───────────────────────────────────
        Button(
            onClick  = onDescribeScene,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .semantics { contentDescription = "Buton descriere scenă curentă prin voce" },
            shape  = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = blue),
        ) {
            Text(
                text       = "DESCRIE SCENA",
                color      = Color.White,
                fontSize   = 20.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Acțiuni secundare ────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick  = onStop,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .semantics { contentDescription = "Buton oprire sistem" },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(text = "OPREȘTE", color = red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick  = onContactsOpen,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .semantics { contentDescription = "Configurare contacte de urgență" },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(text = "CONTACTE", color = Color(0xFFCCCCCC), fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text      = "Triple tap oriunde → oprire rapidă",
            color     = Color(0xFF446644),
            fontSize  = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ECRAN INACTIV — sistemul este oprit
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Ecranul afișat când sistemul este oprit.
 * Design minimal, focusat pe acțiunea de pornire.
 */
@Composable
private fun InactiveScreen(
    onStart: () -> Unit,
    onContactsOpen: () -> Unit,
) {
    val green  = Color(0xFF00E676)
    val bgColor = Color(0xFF0A0A0A)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text       = "Guardian Vision",
            color      = Color(0xFF444444),
            fontSize   = 16.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text      = "SISTEM OPRIT",
            color     = Color(0xFF666666),
            fontSize  = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier  = Modifier.semantics {
                contentDescription = "Sistemul este oprit"
            }
        )

        Spacer(Modifier.height(60.dp))

        // ── Buton principal: Pornește ─────────────────────────────────────────
        Button(
            onClick  = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .semantics { contentDescription = "Buton pornire sistem" },
            shape  = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = green),
        ) {
            Text(
                text       = "PORNEȘTE",
                color      = Color.Black,
                fontSize   = 20.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        Spacer(Modifier.height(20.dp))

        OutlinedButton(
            onClick  = onContactsOpen,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = "Configurare contacte de urgență" },
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text = "CONTACTE URGENȚĂ", color = Color(0xFFCCCCCC), fontSize = 14.sp)
        }

        Spacer(Modifier.height(36.dp))

        Text(
            text      = "Triple tap oriunde pe ecran\nsau triple-press volum jos\npentru pornire rapidă",
            color     = Color(0xFF555555),
            fontSize  = 11.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TRIPLE-TAP COUNTER
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Contor de tap-uri pentru detectarea triple-tap pe ecranul telefonului.
 *
 * Diferență față de [ro.pub.cs.system.eim.aplicatie_hack.wear.WatchMainActivity.TapCounter]:
 * Acesta operează la nivel de DOWN event (nu tap complet up+down), deci se
 * declanșează mai rapid — potrivit pentru toggle urgent al unui sistem de accesibilitate.
 *
 * Fereastra: 600ms după primul tap. Dacă nu vin 3 tapuri în 600ms → resetare.
 * Dacă al 3-lea tap vine înainte de expirarea ferestrei → acțiune imediată.
 */
private class ScreenTapCounter {
    private var count = 0
    private var job: Job? = null

    /**
     * Procesează un DOWN event.
     *
     * @param scope [rememberCoroutineScope] al composable-ului — se anulează la dispărut din UI.
     * @param onTripleTap Acțiunea declanșată la al 3-lea tap (ex. toggle tracking).
     */
    fun handle(scope: CoroutineScope, onTripleTap: () -> Unit) {
        count++
        job?.cancel()

        if (count >= 3) {
            count = 0
            onTripleTap()
            return
        }

        // Reset dacă nu vin suficiente tapuri în fereastră
        job = scope.launch {
            delay(600L)
            count = 0
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DIALOGURI
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun InfoDialog(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(message) {
        delay(60_000L)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text       = if (isError) "EROARE" else "DESCRIERE SCENĂ",
                color      = if (isError) Color.Red else Color.Blue,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(text = message, fontSize = 16.sp)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("ÎNCHIDE (X)", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun EmergencyContactsDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Contacte de urgență") },
        text    = {
            Column {
                Text(
                    "Introdu numerele de telefon (unul per linie).\n" +
                    "Un SMS cu locația GPS va fi trimis dacă se detectează o cădere.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    label         = { Text("Numere telefon") },
                    placeholder   = { Text("+40712345678\n+40798765432") },
                    modifier      = Modifier.fillMaxWidth(),
                    minLines      = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("SALVEAZĂ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ANULEAZĂ") }
        }
    )
}
