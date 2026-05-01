# Guardian Vision

> **Proiect realizat în cadrul Google Hackathon 2026**

**Guardian Vision** este un sistem de asistență pentru persoane cu deficiențe de vedere, implementat ca aplicație Android multi-modul: un modul pentru telefon (ochii sistemului) și unul pentru smartwatch Wear OS (corpul sistemului). Împreună, cele două componente oferă navigație asistată în timp real, feedback haptic și audio, descriere AI a scenei și alertă automată de urgență la cădere.

---

## Funcționalități principale

- **Detecție obstacole în timp real** — camera telefonului procesează imagini prin ML Kit Object Detection și clasifică obstacolele în trei niveluri de urgență: *distant*, *approaching* și *imminent*
- **Feedback haptic pe ceas** — la fiecare obstacol detectat, ceasul vibrează cu unul din 6 pattern-uri diferențiate, transmise via Wearable Data Layer API prin BLE (~50ms latență)
- **Detecție semafoare** — algoritmul HSV identifică culorile roșu și verde și redă audio pe telefon, cu debounce de 5 secunde pentru a nu suprasolicita utilizatorul
- **Descriere scenă cu Gemini AI** — la cerere (sau de pe ceas), Gemini 2.5 Flash analizează scena din fața utilizatorului și o descrie prin sinteză vocală în limba română
- **Detecție cădere** — accelerometrul ceasului (50Hz) rulează un algoritm în două faze: detectează impactul (>2.8G) urmat de imobilitate (2.5s), cu o fereastră de 15 secunde pentru anulare manuală
- **SMS de urgență cu GPS** — la cădere confirmată, telefonul trimite automat SMS cu locația (link Google Maps) la contactele de urgență configurate
- **Control total fără vedere** — toate funcțiile sunt accesibile prin gesturi fizice, fără niciun element vizual obligatoriu

---

## Arhitectura sistemului

Proiectul este structurat în două module Android:

### `:app` — Telefon
Principalul procesor al sistemului. Rulează un serviciu foreground (`VisionForegroundService`) cu acces la cameră și ML Kit, gestionează comunicarea cu Gemini API și trimite SMS-uri de urgență.

```
app/src/main/java/.../
├── MainActivity.kt                  # UI cu două ecrane (activ/inactiv)
├── accessibility/
│   └── GuardianAccessibilityService.kt   # Triple-press Volum Jos → toggle
├── audio/
│   └── TrafficLightAudioPlayer.kt        # Audio semafor via SoundPool
├── model/
│   └── HapticEvent.kt                    # Enum evenimente haptic
├── service/
│   ├── VisionForegroundService.kt        # Camera + ML Kit + frame skipping adaptiv
│   ├── PhoneDataLayerService.kt          # Receptor comenzi de la ceas
│   └── EmergencyResponseService.kt       # GPS + SMS urgență
├── vision/
│   ├── BoundingBoxFilter.kt              # Filtrare și clasificare obstacole
│   ├── TrafficLightDetector.kt           # Detecție culoare semafor (HSV)
│   ├── SceneDescriptionManager.kt        # Gemini 2.5 Flash REST + TTS
│   ├── OcrManager.kt                     # Recunoaștere text (ML Kit OCR)
│   └── NightModeDetector.kt              # Detecție condiții de lumină slabă
└── wearable/
    └── WearableMessenger.kt              # Trimitere mesaje la ceas (cache nodeId)
```

### `:wear` — Ceas (Pixel Watch 4 / Wear OS 4)
Componenta de feedback și senzori. Recepționează pattern-uri haptic de la telefon, monitorizează accelerometrul pentru detecție cădere și permite controlul telefonului prin gesturi pe ceas.

```
wear/src/main/java/.../
├── WatchMainActivity.kt             # UI ceas + gesturi
├── FallAlertActivity.kt             # Alertă cădere cu countdown 15s
├── TrackingState.kt                 # Singleton stare tracking (StateFlow)
├── model/
│   └── HapticEvent.kt
└── wear/
    ├── haptic/
    │   └── HapticManager.kt         # 6 pattern-uri + SOS Morse în buclă
    └── service/
        ├── FallDetectionService.kt  # Algoritm 2 faze, 50Hz
        └── WatchDataLayerService.kt # Receptor mesaje de la telefon
```

---

## Gesturi disponibile

### Pe telefon
| Gest | Efect |
|---|---|
| Triple-tap oriunde pe ecran | Pornire / Oprire sistem |
| Triple-press Volum Jos | Pornire / Oprire sistem (necesită Serviciu Accesibilitate activ) |
| Buton „Descrie Scena" | Analiză AI a scenei cu Gemini |

### Pe ceas
| Gест | Efect |
|---|---|
| Triple-press buton coroană (STEM_1) | Aduce aplicația telefonului în prim-plan |
| Double-tap ecran (tracking activ) | Cerere descriere scenă |
| Triple-tap ecran | Pornire / Oprire tracking pe telefon |
| Atingere ecran în alerta de cădere | Anulare alertă (fereastră 15s) |

---

## Tehnologii utilizate

| Tehnologie | Rol |
|---|---|
| **Kotlin + Jetpack Compose** | UI telefon și ceas |
| **CameraX** | Captură video 640×480 YUV la 30fps |
| **ML Kit Object Detection** | Detecție obstacole în timp real (STREAM_MODE) |
| **ML Kit OCR** | Recunoaștere text din scenă |
| **Gemini 2.5 Flash** | Descriere scenă prin AI (REST API) |
| **Wearable Data Layer API** | Comunicare BLE telefon ↔ ceas |
| **FusedLocationProvider** | GPS pentru SMS urgență |
| **SoundPool** | Audio semafor fără overlap |
| **VibrationEffect** | Pattern-uri haptic diferențiate pe ceas |
| **Wear OS Compose** | UI rotund pentru Pixel Watch 4 |

---

## Instalare și configurare

### Cerințe
- Android Studio Ladybug sau mai recent
- Telefon Android cu API 26+ (Android 8.0)
- Smartwatch cu Wear OS 3+ (recomandat Pixel Watch 4 cu Wear OS 4)
- Cont Google AI Studio pentru cheia Gemini

### 1. Clonare repository

```bash
git clone https://github.com/octav-manda/Guardian_Vision.git
cd Guardian_Vision
```

### 2. Configurare API Key Gemini

Creează (sau editează) fișierul `local.properties` din rădăcina proiectului și adaugă:

```properties
GEMINI_API_KEY=AIza...cheia_ta_de_la_aistudio.google.com
```

> Obții cheia gratuit de la [aistudio.google.com](https://aistudio.google.com). Fișierul `local.properties` este exclus din git prin `.gitignore`.

### 3. Fișiere audio semafor (opțional)

Adaugă în `app/src/main/res/raw/` cele două fișiere:
```
red_light.mp3    # semnal audio pentru roșu
green_light.mp3  # semnal audio pentru verde
```
Fără aceste fișiere, aplicația funcționează normal — doar feedback-ul audio pentru semafor va fi dezactivat.

### 4. Build și instalare

```bash
./gradlew :app:installDebug       # Instalare pe telefon
./gradlew :wear:installDebug      # Instalare pe ceas (sau din Android Studio)
```

### 5. Permisiuni și activare accesibilitate

La primul start, acordă toate permisiunile solicitate (cameră, locație, SMS). Apoi activează serviciul de accesibilitate pentru gestul cu volumul:

> **Setări → Accesibilitate → Guardian Vision → Activează**

### 6. Configurare contacte de urgență

Din interfața aplicației, apasă **CONTACTE URGENȚĂ** și adaugă numerele de telefon care vor primi SMS-ul automat la cădere confirmată.

---

## Flux de funcționare

### Detecție obstacol → feedback ceas

```
Cameră (30fps)
  → ML Kit Object Detection (~10fps cu frame skipping adaptiv)
  → BoundingBoxFilter (clasificare DISTANT / APPROACHING / IMMINENT)
  → WearableMessenger (debounce 900ms, cache nodeId)
  → MessageClient BLE (~50ms)
  → HapticManager pe ceas (pattern specific urgenței)
```

### Detecție cădere → SMS urgență

```
Accelerometru ceas (50Hz)
  → Faza 1: spike > 2.8G → marcat impact
  → Faza 2: după 2.5s, verificare imobilitate (avg ≤ 1.5G, stdDev < 0.18G)
  → FallAlertActivity: countdown 15s (atingere ecran = anulare)
  → DataClient BLE (persistent, supraviețuiește deconectare)
  → EmergencyResponseService: GPS + SMS cu link Maps
```

---

## Permisiuni

### Telefon
| Permisiune | Utilizare |
|---|---|
| `CAMERA` | Detecție obstacole |
| `FOREGROUND_SERVICE_CAMERA` | Serviciu activ cu cameră |
| `SEND_SMS` | SMS urgență |
| `ACCESS_FINE_LOCATION` | GPS pentru mesajul de urgență |
| `INTERNET` | Apeluri Gemini API |
| `BIND_ACCESSIBILITY_SERVICE` | Triple-press Volum Jos |

### Ceas
| Permisiune | Utilizare |
|---|---|
| `BODY_SENSORS` | Accelerometru fall detection |
| `HIGH_SAMPLING_RATE_SENSORS` | 50Hz pentru precizie |
| `VIBRATE` | Feedback haptic |
| `WAKE_LOCK` | Ecran pornit la alertă cădere |

---

## Versiuni

- `versionName`: 1.0
- `minSdk`: 26 (Android 8.0) / 30 (Wear OS 3)
- `targetSdk` / `compileSdk`: 36

---

