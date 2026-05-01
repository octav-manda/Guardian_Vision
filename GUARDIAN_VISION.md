# Guardian Vision — Documentație Completă de Proiect

> **Scop document:** Context complet pentru sesiuni de dezvoltare viitoare.
> Conține arhitectură, fișiere sursă, fluxuri de date, decizii tehnice și starea curentă.

---

## 1. Prezentare Generală

**Guardian Vision** este un sistem de asistență pentru persoane cu deficiențe de vedere, implementat ca aplicație Android multi-modul:

- **`:app`** (telefon) — „ochii" sistemului: detecție obstacole prin cameră + ML Kit, descriere scenă prin Gemini AI, trimitere SMS de urgență
- **`:wear`** (Pixel Watch 4, Wear OS 4) — „corpul" sistemului: feedback haptic în timp real, detecție cădere prin accelerometru, interfață minimală

### Principii de design
- **Accesibilitate first**: toate acțiunile principale sunt accesibile fără vedere (gesturi fizice, audio, haptic)
- **Fără interacțiune vizuală obligatorie**: sistemul funcționează cu ecranul oprit
- **Redundanță**: fiecare funcție critică are minim 2 căi de acces

---

## 2. Arhitectura Sistemului

```
┌─────────────────────────────────────────────────────────────────────┐
│                         TELEFON (:app)                              │
│                                                                     │
│  ┌─────────────┐    ┌──────────────────────────────────────────┐   │
│  │ MainActivity│    │       VisionForegroundService            │   │
│  │             │    │  CameraX 640×480 YUV → ML Kit STREAM     │   │
│  │ ActiveScreen│    │  ├─ BoundingBoxFilter → HapticEvent       │   │
│  │ InactiveScr.│    │  ├─ TrafficLightDetector (HSV)            │   │
│  │             │    │  │    └─ TrafficLightAudioPlayer          │   │
│  │ Triple-tap  │    │  ├─ WearableMessenger → MessageClient     │   │
│  │ (PointerEv) │    │  └─ SceneDescriptionManager               │   │
│  └──────┬──────┘    │       └─ Gemini 2.5 Flash REST API        │   │
│         │           └──────────────────────────────────────────┘   │
│         │                          │                               │
│  ┌──────┴────────────────────────┐ │                               │
│  │  SharedPreferences             │ │                               │
│  │  KEY_RUNNING + emergency_nums  │◄┘                               │
│  └───────────────────────────────┘                                 │
│                                                                     │
│  ┌─────────────────────────┐  ┌──────────────────────────────┐    │
│  │ GuardianAccessibility   │  │    PhoneDataLayerService      │    │
│  │ Service                 │  │    WearableListenerService    │    │
│  │ triple-press VOL_DOWN   │  │    DATA: /emergency/fall      │    │
│  └─────────────────────────┘  │    MSG:  /remote/launch       │    │
│                                │    MSG:  /command/*           │    │
│  ┌─────────────────────────┐  └──────────────┬───────────────┘    │
│  │ EmergencyResponseService│                 │                     │
│  │ GPS + SMS la contacte   │◄────────────────┘                    │
│  └─────────────────────────┘                                       │
└──────────────────────────────────────┬──────────────────────────────┘
                                       │
                      Wearable Data Layer API (BLE)
                      ┌────────────────┴────────────────┐
                      │  MessageClient  │   DataClient   │
                      │  fire-and-forget│   persistent   │
                      │  /haptic        │   /emergency/* │
                      │  /remote/*      │   /tracking/*  │
                      │  /command/*     │                │
                      └────────────────┴────────────────┘
                                       │
┌──────────────────────────────────────┴──────────────────────────────┐
│                         CEAS (:wear)                                │
│                                                                     │
│  ┌──────────────────────────┐  ┌───────────────────────────────┐  │
│  │   WatchMainActivity      │  │    WatchDataLayerService       │  │
│  │                          │  │    WearableListenerService     │  │
│  │  Triple-press STEM_1     │  │    MSG /haptic → HapticManager │  │
│  │  └─→ /remote/launch      │  │    DATA /tracking/state        │  │
│  │                          │  │    └─→ TrackingState singleton  │  │
│  │  Double-tap (tracking)   │  └───────────────────────────────┘  │
│  │  └─→ /command/describe   │                                      │
│  │                          │  ┌───────────────────────────────┐  │
│  │  Triple-tap ecran        │  │    FallDetectionService        │  │
│  │  └─→ /command/toggle     │  │    TYPE_LINEAR_ACCELERATION    │  │
│  │                          │  │    50Hz, algoritm 2 faze       │  │
│  │  TrackingState.isActive  │  │    └─→ FallAlertActivity (15s) │  │
│  └──────────────────────────┘  └───────────────────────────────┘  │
│                                                                     │
│  ┌──────────────────────────┐  ┌───────────────────────────────┐  │
│  │   FallAlertActivity      │  │    HapticManager               │  │
│  │   Touch oriunde=anulare  │  │    6 pattern-uri + SOS buclă   │  │
│  │   15s countdown          │  │    Amplitudine max 230/255     │  │
│  └──────────────────────────┘  └───────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Structura Modulelor și Fișiere Sursă

### 3.1 Modul `:app` (Telefon)

```
app/src/main/
├── AndroidManifest.xml
├── java/ro/pub/cs/system/eim/aplicatie_hack/
│   ├── MainActivity.kt
│   ├── accessibility/
│   │   └── GuardianAccessibilityService.kt
│   ├── audio/
│   │   └── TrafficLightAudioPlayer.kt
│   ├── model/
│   │   └── HapticEvent.kt
│   ├── service/
│   │   ├── VisionForegroundService.kt
│   │   ├── PhoneDataLayerService.kt
│   │   └── EmergencyResponseService.kt
│   ├── vision/
│   │   ├── BoundingBoxFilter.kt
│   │   ├── TrafficLightDetector.kt
│   │   └── SceneDescriptionManager.kt
│   ├── wearable/
│   │   └── WearableMessenger.kt
│   └── ui/theme/
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
└── res/
    └── raw/
        ├── red_light.mp3    ← TREBUIE ADĂUGAT MANUAL
        └── green_light.mp3  ← TREBUIE ADĂUGAT MANUAL
```

### 3.2 Modul `:wear` (Ceas)

```
wear/src/main/
├── AndroidManifest.xml
└── java/ro/pub/cs/system/eim/aplicatie_hack/
    ├── WatchMainActivity.kt
    ├── FallAlertActivity.kt
    ├── TrackingState.kt
    ├── model/
    │   └── HapticEvent.kt
    └── wear/
        ├── haptic/
        │   └── HapticManager.kt
        └── service/
            ├── FallDetectionService.kt
            └── WatchDataLayerService.kt
```

---

## 4. Descrierea Fișierelor Cheie

### 4.1 Telefon

#### `MainActivity.kt`
- **UI**: două ecrane Compose complet distincte pe baza stării `isActive`
  - `InactiveScreen`: fundal `#0A0A0A`, un singur buton PORNEȘTE, hint triple-tap
  - `ActiveScreen`: fundal `#050F05`, indicator puls animat, buton DESCRIE SCENA, OPREȘTE + CONTACTE pe rând
- **Triple-tap oriunde**: `PointerEventPass.Initial` pe root Box — vede tapuri ÎNAINTE ca butoanele copil să le consume; nu consumă evenimentul deci butoanele funcționează normal
- **Sincronizare stare**: `SharedPreferences.OnSharedPreferenceChangeListener` pe `KEY_RUNNING` — UI-ul se actualizează în timp real din orice sursă (buton, ceas, accessibility service)
- **BroadcastReceiver**: ascultă `ACTION_SHOW_MESSAGE` de la `SceneDescriptionManager` → dialog pop-up cu auto-close 60s

#### `VisionForegroundService.kt`
- Extinde `LifecycleService` (necesar pentru CameraX `bindToLifecycle`)
- **Camera**: CameraX, rezoluție 640×480 YUV, `STRATEGY_KEEP_ONLY_LATEST` (aruncă frame-uri intermediare)
- **ML Kit**: `STREAM_MODE` — reutilizează context între frame-uri pentru latență mică
- **Frame skipping adaptiv**:
  - Normal: `skipEvery=3` → ~10fps inferență din 30fps captură
  - Pericol IMINENT: `skipEvery=1` → 30fps inferență
  - Scenă liberă: `skipEvery++` până la max 6 → ~5fps (economie baterie)
- **Debounce haptic**: 900ms între trimiteri la ceas
- **Debounce audio semafor**: 5000ms (separat de haptic — audio repetat la 900ms ar fi deranjant)
- **Tracking state**: publică `/tracking/state` via `DataClient.setUrgent()` la start/stop

#### `PhoneDataLayerService.kt`
- Extinde `WearableListenerService`
- **3 intent-filter-e în manifest**: `DATA_CHANGED /emergency`, `MESSAGE_RECEIVED /remote`, `MESSAGE_RECEIVED /command`
- `toggleTracking()`: citește `KEY_RUNNING` din SharedPreferences → start/stop `VisionForegroundService` — actualizarea prefs declanșează listener-ul din `MainActivity`

#### `SceneDescriptionManager.kt`
- **Model**: `gemini-2.5-flash` via REST API (`generativelanguage.googleapis.com/v1beta`)
- **API Key**: `BuildConfig.GEMINI_API_KEY` — citit din `local.properties` la build time
- **Backoff exponențial**: `delay = BASE(1s) * 2^attempt + Random(0..500ms)`, max 3 retry
- **Gestionare 429**: citește header `Retry-After` dacă prezent, altfel backoff
- **Gestionare 5xx**: retry cu backoff; 4xx (400, 403) → eșec imediat
- **Client-side throttle**: `COOLDOWN_MS = 8000` ms între cereri consecutive
- **Image encoding**: redimensionare la max 1024px, JPEG quality 85 → ~30–60KB per cerere
- **Output**: TTS română + broadcast spre `MainActivity`

#### `WearableMessenger.kt`
- **Cache nod**: `cachedNodeId` volatile — evită `getConnectedNodes()` la fiecare mesaj (50–200ms saved)
- **Retry**: la eșec send → invalidează cache → redescopăr nod → retry o singură dată
- **Preferință nod**: `isNearby` (BLE direct) > orice alt nod conectat

#### `BoundingBoxFilter.kt`
- Filtrare bbox ML Kit: elimină obiecte la sol (cy > 0.65), în afara coridorului de mers (cx < 0.20 sau > 0.80), prea mici (h < 0.05) sau prea mari (h > 0.90)
- Clasificare urgență: `IMMINENT` (la nivelul capului + centrat), `APPROACHING` (mare sau la cap), `DISTANT` (altfel)
- Suportă corecție tilt (unghiul de înclinare al telefonului)

#### `TrafficLightDetector.kt`
- Detecție culoare semafor prin **HSV** (robust la variații de luminozitate)
- **Roșu**: Hue 0–10° sau 160–180°, Sat ≥70%, Val ≥70% — în jumătatea superioară a bbox
- **Verde**: Hue 40–100°, Sat ≥60%, Val ≥60% — în jumătatea inferioară a bbox
- Confirmă culoarea dacă ≥15% din pixeli corespund

#### `TrafficLightAudioPlayer.kt`
- **SoundPool** cu `maxStreams=1` (al doilea sunet îl oprește automat pe primul)
- `loop=0` în `play()` — un singur ciclu, fără buclă
- `getIdentifier()` runtime lookup — codul compilează chiar dacă fișierele MP3 lipsesc
- Fișiere necesare: `res/raw/red_light.mp3`, `res/raw/green_light.mp3`
- Fără fișiere: funcționează silențios + warning în Logcat

#### `GuardianAccessibilityService.kt`
- Triple-press `KEYCODE_VOLUME_DOWN` în 1.4s → toggle `VisionForegroundService`
- Consumă evenimentul (`return true`) → volumul nu se schimbă
- `FLAG_REQUEST_FILTER_KEY_EVENTS` necesar în `serviceInfo`
- Necesită activare manuală din Setări → Accesibilitate

#### `EmergencyResponseService.kt`
- `FusedLocationProviderClient.lastLocation` → fallback `getCurrentLocation` (10s timeout, HIGH_ACCURACY)
- SMS format: `"URGENTA: ... Ora: HH:mm, dd MMM yyyy. Locatie: https://maps.google.com/?q=lat,lon"`
- Suportă SMS multipart (>160 caractere)
- `START_NOT_STICKY` — nu repornește dacă e omorât de sistem

---

### 4.2 Ceas

#### `WatchMainActivity.kt`
- **Triple-press STEM_1** (buton fizic coroană) → `/remote/launch` → telefon aduce `MainActivity` în prim-plan
  - Consumă toate presele butonului în fereastra de 1.4s (trade-off acceptabil pentru app de accesibilitate)
- **Double-tap ecran** (când tracking activ) → `/command/describe_scene`
- **Triple-tap ecran** → `/command/toggle_tracking`
- **TapCounter**: custom class cu `rememberCoroutineScope()` — tap 1: reset 600ms, tap 2: dacă nu vine tap 3 în 400ms → double-tap, tap 3: triple-tap imediat
- **Cache nod telefon**: `cachedPhoneNodeId` volatile, invalidat la eșec send

#### `WatchDataLayerService.kt`
- Extinde `WearableListenerService`
- `onMessageReceived("/haptic")` → `HapticManager.play(HapticEvent)`
- `onDataChanged("/tracking/state")` → `TrackingState.update(active)` → recompunere Compose în `WatchMainActivity`

#### `TrackingState.kt`
- Singleton `object` cu `MutableStateFlow<Boolean>`
- Bridge reactiv între `WatchDataLayerService` (writer) și `WatchMainActivity` (Compose collector)

#### `FallDetectionService.kt`
- **Senzor**: `TYPE_LINEAR_ACCELERATION` (exclude gravitația) → spike-uri de impact mai clare; fallback la `TYPE_ACCELEROMETER`
- **Rata**: `SENSOR_DELAY_GAME` (~50Hz)
- **Algoritm 2 faze**:
  1. **Faza 1 — Impact**: `mag > 2.8G` → marchează `impactAt`
  2. **Faza 2 — Stillness**: după 2.5s de la impact, verifică ultimele 25 de sample-uri: `avg ≤ 1.5G` și `stdDev < 0.18G` → cădere confirmată
  3. **Timeout**: dacă 8s trec fără stillness → fals pozitiv, reset
- La confirmare: `HapticManager.playSOS()` + `FallAlertActivity` + timer 15s
- `CANCEL_WINDOW_MS = 15_000L`

#### `FallAlertActivity.kt`
- `setShowWhenLocked(true)` + `setTurnScreenOn(true)` — apare pe ecranul de blocare
- **Touch oriunde pe ecran** → anulare (`clickable` cu `indication=null` pe Box root)
- Countdown 15s vizibil, la 0 `FallDetectionService` trimite fall event
- Design: `#1A0000` (roșu închis urgent), text alb, instrucțiune verde "Atinge ecranul"

#### `HapticManager.kt`
- 6 pattern-uri `VibrationEffect.createWaveform` + SOS în buclă
- Amplitudini: `A_SOFT=80`, `A_MEDIUM=160`, `A_STRONG=230` (nu 255 → longevitate motor Pixel Watch 4)
- `playSOS()`: Morse SOS (`···−−−···`), `repeat=0` → buclă infinită până la `stop()`

---

## 5. Fluxuri de Date

### 5.1 Detecție Obstacol → Feedback Haptic Ceas

```
Camera (30fps)
  │ skipEvery=3 → ~10fps efective
  ▼
ML Kit Object Detection (STREAM_MODE)
  │
  ├─[Traffic light detectat]──► TrafficLightDetector.detectColor()
  │                                  ├─ RED  → HapticEvent.TRAFFIC_LIGHT_RED
  │                                  │         + TrafficLightAudioPlayer.playRed() [5s debounce]
  │                                  └─ GREEN → HapticEvent.TRAFFIC_LIGHT_GREEN
  │                                             + TrafficLightAudioPlayer.playGreen() [5s debounce]
  │
  └─[Alte obiecte]──► BoundingBoxFilter.filter()
                           ├─ IMMINENT    → HapticEvent.DANGER_IMMINENT + skipEvery=1
                           ├─ APPROACHING → HapticEvent.OBSTACLE_APPROACHING
                           └─ DISTANT     → HapticEvent.OBSTACLE_DISTANT
                                │
                                │ debounce 900ms
                                ▼
                     WearableMessenger.send(event)
                           │ cache nodeId
                           ▼
                     MessageClient.sendMessage("/haptic", event.name)
                           │ BLE ~50ms
                           ▼
                     WatchDataLayerService.onMessageReceived()
                           ▼
                     HapticManager.play(event)
                           ▼
                     VibrationEffect pe motor ceas
```

### 5.2 Detecție Cădere → SMS Urgență

```
TYPE_LINEAR_ACCELERATION @50Hz
  │
  ├─[mag > 2.8G] → impactAt = now, pendingCheck = true
  │
  └─[pendingCheck=true, elapsed ≥ 2.5s]
       │
       ├─[avg≤1.5G && stdDev<0.18G] → CĂDERE CONFIRMATĂ
       │     │
       │     ├─ HapticManager.playSOS() (buclă)
       │     ├─ FallAlertActivity (15s countdown, touch oriunde = anulare)
       │     └─ timer 15s
       │           │
       │           ├─[utilizator atinge ecranul] → cancelAlert()
       │           │     └─ DataClient PUT /emergency/fall_cancel
       │           │
       │           └─[timeout 15s fără atingere] → notifyPhone()
       │                 └─ DataClient PUT /emergency/fall {confirmed=true, ts}
       │                       │ retry ×3, delay 5s între
       │                       │ BLE persistent (supraviețuiește deconectare)
       │                       ▼
       │               PhoneDataLayerService.onDataChanged()
       │                     └─ handleFallConfirmed()
       │                           └─ EmergencyResponseService
       │                                 ├─ FusedLocation GPS
       │                                 └─ SmsManager → contacte de urgență
       │
       └─[elapsed > 8s fără stillness] → fals pozitiv, reset
```

### 5.3 Comenzi de la Ceas la Telefon

```
WatchMainActivity
  │
  ├─[Triple-press KEYCODE_STEM_1, fereastra 1.4s]
  │     └─ MessageClient "/remote/launch"
  │           └─ PhoneDataLayerService.launchMainActivity()
  │                 └─ FLAG_ACTIVITY_REORDER_TO_FRONT
  │
  ├─[Double-tap ecran, tracking activ]
  │     └─ MessageClient "/command/describe_scene"
  │           └─ PhoneDataLayerService.triggerSceneDescription()
  │                 └─ startService(ACTION_DESCRIBE_SCENE)
  │                       └─ SceneDescriptionManager.describe(lastFrame)
  │
  └─[Triple-tap ecran]
        └─ MessageClient "/command/toggle_tracking"
              └─ PhoneDataLayerService.toggleTracking()
                    ├─ startForegroundService(VisionForegroundService) sau
                    └─ stopService(VisionForegroundService)
                          └─ SharedPreferences KEY_RUNNING schimbat
                                └─ MainActivity.prefsListener
                                      └─ isActive.value actualizat
                                            └─ Compose recompune UI
```

### 5.4 Sincronizare Stare Tracking Ceas ↔ Telefon

```
VisionForegroundService.onStartCommand()  ──► DataClient PUT /tracking/state {active=true}
                                                    │ .setUrgent() → BLE imediat ~50ms
                                                    ▼
VisionForegroundService.onDestroy()       WatchDataLayerService.onDataChanged()
  └─► DataClient PUT /tracking/state           └─ TrackingState.update(active)
         {active=false}                               └─ MutableStateFlow emite
                                                            └─ WatchMainActivity collectAsState()
                                                                  └─ isTrackingActive actualizat
                                                                        ├─ double-tap activ/inactiv
                                                                        └─ text UI actualizat
```

---

## 6. Comunicare Wearable Data Layer

### Alegerea protocolului

| Canal | Path | Direcție | Motiv |
|---|---|---|---|
| `MessageClient` | `/haptic` | Phone→Watch | Fire-and-forget; mesaj haptic expirat nu trebuie redat |
| `MessageClient` | `/remote/launch` | Watch→Phone | Fire-and-forget; urgență redusă |
| `MessageClient` | `/command/describe_scene` | Watch→Phone | Fire-and-forget real-time |
| `MessageClient` | `/command/toggle_tracking` | Watch→Phone | Fire-and-forget toggle |
| `DataClient` + `setUrgent()` | `/tracking/state` | Phone→Watch | Persistent (supraviețuiește BLE drop) + rapid |
| `DataClient` + `setUrgent()` | `/emergency/fall` | Watch→Phone | Persistent CRITIC — căderea nu trebuie pierdută |
| `DataClient` | `/emergency/fall_cancel` | Watch→Phone | Persistent — anularea trebuie și ea sincronizată |

### Latențe tipice
- `MessageClient`: ~50ms pe BLE direct
- `DataClient` fără `setUrgent()`: 100–500ms (sync batched)
- `DataClient` cu `setUrgent()`: ~50–100ms (BLE prioritar)
- `getConnectedNodes()` round-trip: 50–200ms → de aceea cacheăm `nodeId`

---

## 7. Gesturi și Comenzi Disponibile

### Telefon
| Acțiune | Efect |
|---|---|
| Triple-tap oriunde pe ecran (`PointerEventPass.Initial`) | Toggle pornire/oprire sistem |
| Triple-press Volum Jos (`GuardianAccessibilityService`) | Toggle pornire/oprire sistem |
| Buton PORNEȘTE / OPREȘTE în UI | Toggle pornire/oprire sistem |
| Buton DESCRIE SCENA | Analizează scena curentă cu Gemini |

### Ceas
| Acțiune | Efect |
|---|---|
| Triple-press buton fizic coroană (`KEYCODE_STEM_1`) | Aduce `MainActivity` în prim-plan pe telefon |
| Double-tap ecran (când tracking activ) | Trimite cerere descriere scenă la telefon |
| Triple-tap ecran | Toggle pornire/oprire tracking pe telefon |
| Atingere ecran în `FallAlertActivity` | Anulare alertă cădere |

---

## 8. Configurare și Setup

### 8.1 API Key Gemini

```properties
# local.properties (NU se comite în git)
GEMINI_API_KEY=AIza...
```

Obținut de la [aistudio.google.com](https://aistudio.google.com). Injectat în build ca `BuildConfig.GEMINI_API_KEY`.

### 8.2 Fișiere Audio Semafor

```
app/src/main/res/raw/
├── red_light.mp3    # voce "Roșu" sau beep scurt distinctive
└── green_light.mp3  # voce "Verde" sau beep diferit
```

Fișierele sunt opționale — aplicația funcționează fără ele (warning în Logcat, fără audio).

### 8.3 Permisiuni (telefon)

| Permisiune | Utilizare |
|---|---|
| `CAMERA` | Captură video pentru detecție obstacole |
| `FOREGROUND_SERVICE_CAMERA` | Serviciu foreground cu acces cameră |
| `SEND_SMS` | SMS de urgență la cădere confirmată |
| `ACCESS_FINE_LOCATION` | GPS pentru mesajul de urgență |
| `INTERNET` | Apeluri REST la Gemini API |
| `FOREGROUND_SERVICE` | `VisionForegroundService` |
| `BIND_ACCESSIBILITY_SERVICE` | `GuardianAccessibilityService` |

### 8.4 Permisiuni (ceas)

| Permisiune | Utilizare |
|---|---|
| `BODY_SENSORS` | Accelerometru pentru fall detection |
| `HIGH_SAMPLING_RATE_SENSORS` | 50Hz pe `TYPE_LINEAR_ACCELERATION` |
| `FOREGROUND_SERVICE_HEALTH` | API 34+: tip explicit pentru senzori în foreground |
| `VIBRATE` | Feedback haptic |
| `WAKE_LOCK` | Ecran pornit în `FallAlertActivity` |

### 8.5 Activare Accesibilitate

`GuardianAccessibilityService` necesită activare manuală:
> Setări → Accesibilitate → Guardian Vision → Activare

---

## 9. SDK și Dependențe

### `:app`
| Dependență | Versiune catalogată | Rol |
|---|---|---|
| `compileSdk` / `targetSdk` | 36 | |
| `minSdk` | 26 (Android 8) | |
| `androidx.lifecycle:lifecycle-service` | BOM | `LifecycleService` pentru CameraX |
| `androidx.camera:*` | BOM (CameraX) | Captură video |
| `com.google.mlkit:object-detection` | catalog | Detecție obiecte în timp real |
| `com.google.android.gms:play-services-wearable` | catalog | Wearable Data Layer |
| `com.google.android.gms:play-services-location` | catalog | FusedLocationProvider GPS |
| `com.squareup.okhttp3:okhttp` | catalog | HTTP client pentru Gemini REST API |
| `com.google.code.gson:gson` | catalog | Parsing JSON răspuns Gemini |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | catalog | `await()` pe Google Tasks |
| `androidx.compose.material3` | BOM | UI Material 3 |

### `:wear`
| Dependență | Versiune catalogată | Rol |
|---|---|---|
| `compileSdk` / `targetSdk` | 36 | |
| `minSdk` | 30 (Wear OS 3) | Pixel Watch 4 = Wear OS 4 |
| `androidx.wear.compose:compose-material` | catalog | UI rotundă Wear OS |
| `androidx.wear.compose:compose-foundation` | catalog | Layout Wear |
| `com.google.android.gms:play-services-wearable` | catalog | Wearable Data/Message Layer |

### Namespace partajat
Ambele module au `applicationId = "ro.pub.cs.system.eim.aplicatie_hack"` — **obligatoriu** pentru Wearable Data Layer pairing (Google Play Services identifică perechi phone–watch prin applicationId identic).

---

## 10. Detalii Tehnice Algoritmi

### 10.1 Fall Detection — Algoritm 2 Faze

```
Parametri:
  IMPACT_G         = 2.8f   // prag spike în G (linear acceleration)
  STILL_WINDOW     = 25     // 25 sample-uri ≈ 0.5s la 50Hz
  STILL_AVG_MAX    = 1.5f   // media ≤ 1.5G (gravitație ± mică mișcare)
  STILL_STDDEV_MAX = 0.18f  // deviație standard mică = nemișcat
  STILLNESS_WAIT   = 2500ms // așteptăm 2.5s după impact
  FALSE_POS_TIMEOUT= 8000ms // dacă utilizatorul se ridică în 8s → fals pozitiv
  CANCEL_WINDOW    = 15000ms// utilizatorul are 15s să anuleze pe ceas
```

### 10.2 Backoff Exponențial Gemini (429 Rate Limit)

```
attempt 0: delay = 1000 * 2^0 + Random(0..500) = 1000–1500ms
attempt 1: delay = 1000 * 2^1 + Random(0..500) = 2000–2500ms
attempt 2: delay = 1000 * 2^2 + Random(0..500) = 4000–4500ms
max delay: 30000ms (plafon)
max retries: 3

Prioritate: header Retry-After din răspuns 429 (dacă prezent) > calcul local
5xx: retry cu backoff; 4xx: eșec imediat fără retry
```

### 10.3 Frame Skipping Adaptiv

```
Stare inițială:  skipEvery = 3  (~10fps inferență din 30fps captură)
DANGER_IMMINENT: skipEvery = 1  (~30fps — răspuns maxim)
Scenă liberă:    skipEvery++   (max 6, ~5fps — economie baterie)
La orice obiect: skipEvery = 3  (revenire la normal)
```

### 10.4 BoundingBoxFilter — Zone de Interes

```
Coordonate normalizate (0.0–1.0):

     0.0 ────────── 1.0  (X)
  0.0 ┌──────────────────┐
      │                  │
      │   ┌──────────┐   │  ← HEAD_TOP_Y = 0.35 (IMMINENT dacă bbox vârf < 0.35)
      │   │  PATH    │   │
      │   │  0.20-   │   │
      │   │  0.80    │   │
      │   └──────────┘   │  ← GROUND_CENTER_Y = 0.65 (ignor dacă centru Y > 0.65)
  1.0 └──────────────────┘
```

---

## 11. Starea Curentă a Proiectului (Implementat)

### ✅ Funcționalități Complete

- [x] Detecție obstacole real-time cu ML Kit (STREAM_MODE)
- [x] Clasificare urgență 3 niveluri (DISTANT / APPROACHING / IMMINENT)
- [x] Feedback haptic diferențiat pe ceas (6 pattern-uri + SOS)
- [x] Detecție semafor prin HSV (roșu / verde)
- [x] Audio feedback semafor pe telefon (`SoundPool`, anti-overlap, anti-loop)
- [x] Descriere scenă Gemini 2.5 Flash + TTS română
- [x] Backoff exponențial cu jitter pentru gestionare 429 Rate Limit
- [x] Fall detection 2 faze pe accelerometru (50Hz)
- [x] Alertă cădere cu countdown 15s + touch oriunde pentru anulare
- [x] SMS urgență cu locație GPS (Maps link)
- [x] Triple-press volum jos pe telefon → toggle tracking
- [x] Triple-press buton fizic ceas → lansare app telefon
- [x] Double-tap ecran ceas (tracking activ) → descriere scenă
- [x] Triple-tap ecran ceas → toggle tracking
- [x] Triple-tap oriunde pe ecranul telefonului → toggle tracking
- [x] UI cu două ecrane distincte (Active / Inactive) + `PointerEventPass.Initial`
- [x] Sincronizare stare tracking ceas ↔ telefon (DataClient + `setUrgent()`)
- [x] Cache node ID în `WearableMessenger` și `WatchMainActivity` (reducere latență)
- [x] UI telefon actualizat în timp real la toggle din ceas (`SharedPreferences` listener)

### ⚠️ Necesită Acțiune Manuală

- [ ] Adăugare fișiere `res/raw/red_light.mp3` și `res/raw/green_light.mp3`
- [ ] Activare `GuardianAccessibilityService` în Setări → Accesibilitate
- [ ] Configurare contacte de urgență din UI (dialog CONTACTE URGENȚĂ)
- [ ] Obținere și configurare `GEMINI_API_KEY` în `local.properties`

---

## 12. Decizii Arhitecturale Importante

### MessageClient vs DataClient
- **MessageClient** pentru haptic și comenzi: fire-and-forget — un mesaj haptic expirat nu trebuie redat la reconectare
- **DataClient** pentru stări persistente (fall events, tracking state): supraviețuiesc deconectărilor BLE temporare
- **DataClient + setUrgent()** pentru tracking state: persistent AND rapid (~50ms vs 500ms batched)

### LifecycleService pentru VisionForegroundService
CameraX necesită un `LifecycleOwner` pentru `bindToLifecycle()`. `LifecycleService` implementează această interfață direct din Service, eliminând nevoia de a crea un `LifecycleOwner` custom.

### HapticEvent enum duplicat în ambele module
`:app` și `:wear` sunt module separate cu `applicationId` identic dar fără cod partajat. `HapticEvent` este serializat ca String pe DataLayer (`event.name`) și deserializat cu `valueOf()`. Orice modificare la enum trebuie făcută în **ambele** module.

### Debounce separat pentru haptic (900ms) și audio (5000ms)
Haptic repetat la 900ms pe ceas este tolerabil (feedback continuu util). Audio repetat la 900ms pe difuzorul telefonului ar fi extrem de deranjant la un semafor roșu staționat.

### PointerEventPass.Initial pentru triple-tap în MainActivity
`detectTapGestures` standard cu `onTap` pe un parent Box nu vede tapurile consumate de butoanele copil. `PointerEventPass.Initial` procesează evenimentele înainte ca copiii să le primească, fără să le consume — butoanele funcționează normal ȘI tapurile se numără simultan.

### SharedPreferences listener pentru sincronizare UI
`VisionForegroundService` scrie `KEY_RUNNING` la start/stop. Orice sursă de toggle (buton UI, ceas, accessibility service) modifică aceeași cheie. `MainActivity` cu `OnSharedPreferenceChangeListener` pe această cheie primește notificarea automat pe main thread — elimină `onResume()` polling.

---

## 13. Structura Pachetelor

```
ro.pub.cs.system.eim.aplicatie_hack          ← :app
├── MainActivity.kt
├── accessibility/
│   └── GuardianAccessibilityService.kt
├── audio/
│   └── TrafficLightAudioPlayer.kt
├── model/
│   └── HapticEvent.kt                       ← DUPLICAT în :wear
├── service/
│   ├── VisionForegroundService.kt
│   ├── PhoneDataLayerService.kt
│   └── EmergencyResponseService.kt
├── vision/
│   ├── BoundingBoxFilter.kt
│   ├── TrafficLightDetector.kt
│   └── SceneDescriptionManager.kt
└── wearable/
    └── WearableMessenger.kt

ro.pub.cs.system.eim.aplicatie_hack          ← :wear (namespace diferit în build.gradle)
├── WatchMainActivity.kt
├── FallAlertActivity.kt
├── TrackingState.kt
├── model/
│   └── HapticEvent.kt                       ← DUPLICAT din :app
└── wear/
    ├── haptic/
    │   └── HapticManager.kt
    └── service/
        ├── FallDetectionService.kt
        └── WatchDataLayerService.kt
```

---

*Ultima actualizare: sesiune de dezvoltare 2026-04-25 — implementare completă Guardian Vision v1.0*
