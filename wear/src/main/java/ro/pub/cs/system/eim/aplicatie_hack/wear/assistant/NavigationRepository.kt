package ro.pub.cs.system.eim.aplicatie_hack.wear.assistant

import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class NavStep(val instruction: String, val distance: String)

data class DirectionsResult(
    val steps: List<NavStep>,
    val totalDuration: String,
    val totalDistance: String,
)

data class TransitStopInfo(
    val name: String,
    val vicinity: String,
    val lat: Double,
    val lng: Double,
)

data class TransitArrival(
    val lineNumber: String,
    val vehicleType: String,
    val direction: String,
    val departureTime: String,
)

object NavigationRepository {

    private const val TAG = "NavRepository"
    private const val DIRECTIONS_URL = "https://maps.googleapis.com/maps/api/directions/json"
    private const val PLACES_URL     = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
    private const val TIMEOUT_MS     = 8_000

    // ── Walking directions + duration ─────────────────────────────────────────

    suspend fun getWalkingDirections(
        originLat: Double,
        originLng: Double,
        destination: String,
        apiKey: String,
    ): Result<DirectionsResult> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = URLEncoder.encode(destination, "UTF-8")
            val url  = "$DIRECTIONS_URL?origin=$originLat,$originLng" +
                       "&destination=$dest&mode=walking&language=ro&key=$apiKey"

            val json   = fetchJson(url)
            val status = json.optString("status")
            if (status != "OK") {
                Log.w(TAG, "Directions API status: $status")
                return@runCatching DirectionsResult(emptyList(), "", "")
            }

            val leg = json.getJSONArray("routes")
                         .getJSONObject(0)
                         .getJSONArray("legs")
                         .getJSONObject(0)

            val totalDuration = leg.getJSONObject("duration").getString("text")
            val totalDistance = leg.getJSONObject("distance").getString("text")
            val stepsJson     = leg.getJSONArray("steps")

            val steps = (0 until stepsJson.length()).map { i ->
                val step  = stepsJson.getJSONObject(i)
                val plain = Html.fromHtml(
                    step.getString("html_instructions"),
                    Html.FROM_HTML_MODE_LEGACY
                ).toString()
                NavStep(plain, step.getJSONObject("distance").getString("text"))
            }

            DirectionsResult(steps, totalDuration, totalDistance)
        }.onFailure { Log.e(TAG, "getWalkingDirections eșuat", it) }
    }

    // ── Nearest transit stop (cu coordonate pentru arrivals) ──────────────────

    suspend fun getNearestTransitStop(
        lat: Double,
        lng: Double,
        apiKey: String,
    ): Result<TransitStopInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$PLACES_URL?location=$lat,$lng&radius=500" +
                      "&type=transit_station&language=ro&key=$apiKey"

            val json    = fetchJson(url)
            val results = json.getJSONArray("results")

            if (results.length() == 0) {
                return@runCatching TransitStopInfo(
                    "Nicio stație găsită", "", lat, lng
                )
            }

            val place    = results.getJSONObject(0)
            val name     = place.getString("name")
            val vicinity = place.optString("vicinity", "")
            val loc      = place.getJSONObject("geometry").getJSONObject("location")

            TransitStopInfo(name, vicinity, loc.getDouble("lat"), loc.getDouble("lng"))
        }.onFailure { Log.e(TAG, "getNearestTransitStop eșuat", it) }
    }

    // ── Next transit arrivals at stop (via Directions API transit mode) ────────

    /**
     * Returnează până la 3 curse care pleacă din stația dată.
     *
     * Strategia: stația = origin, un punct ~6km NE = destinație fictivă.
     * Motivul: dacă origin ≈ destination (userul lângă stație), API-ul returnează
     * doar mers pe jos și zero pași TRANSIT. Plasând stația ca origin și o destinație
     * suficient de departe, forțăm API-ul să returneze liniile ce PLEACĂ din stație.
     *
     * departure_time = Unix timestamp (mai fiabil decât string-ul "now" pe unele versiuni API).
     * alternatives = true → rute diferite = linii diferite.
     *
     * Nu necesită API suplimentar — folosește același Directions API cu mode=transit.
     */
    suspend fun getTransitArrivals(
        stopLat: Double,
        stopLng: Double,
        apiKey: String,
    ): Result<List<TransitArrival>> = withContext(Dispatchers.IO) {
        runCatching {
            // Offset ~6.5km NE față de stație — suficient pentru o călătorie cu tranzit
            val destLat        = stopLat + 0.05
            val destLng        = stopLng + 0.045
            val departureTime  = System.currentTimeMillis() / 1000   // Unix timestamp

            val url = "$DIRECTIONS_URL?origin=$stopLat,$stopLng" +
                      "&destination=$destLat,$destLng" +
                      "&mode=transit&alternatives=true" +
                      "&departure_time=$departureTime" +
                      "&language=ro&key=$apiKey"

            val json   = fetchJson(url)
            val status = json.optString("status")
            if (status != "OK") {
                Log.w(TAG, "Transit API status: $status — orig=$stopLat,$stopLng dest=$destLat,$destLng")
                return@runCatching emptyList()
            }

            val routes  = json.getJSONArray("routes")
            val results = mutableListOf<TransitArrival>()
            val seenLines = mutableSetOf<String>()  // evităm duplicate (aceeași linie în alt alternativ)

            for (i in 0 until routes.length()) {
                if (results.size >= 3) break

                val legs  = routes.getJSONObject(i).getJSONArray("legs")
                val steps = legs.getJSONObject(0).getJSONArray("steps")

                // Primul pas TRANSIT din rută = linia care pleacă din stație
                for (j in 0 until steps.length()) {
                    val step = steps.getJSONObject(j)
                    if (step.optString("travel_mode") != "TRANSIT") continue

                    val td      = step.optJSONObject("transit_details") ?: continue
                    val line    = td.optJSONObject("line") ?: continue
                    val lineNum = line.optString("short_name").ifBlank { line.optString("name") }

                    if (lineNum.isBlank() || seenLines.contains(lineNum)) {
                        break  // skip duplicate sau linie fără număr
                    }

                    val vehicleType = line.optJSONObject("vehicle")?.let { v ->
                        mapVehicleType(v.optString("type"))
                    } ?: "Vehicul"
                    val direction = td.optString("headsign", "Destinație necunoscută")
                    val depTime   = td.optJSONObject("departure_time")?.optString("text") ?: "--:--"

                    results.add(TransitArrival(lineNum, vehicleType, direction, depTime))
                    seenLines.add(lineNum)
                    break  // o singură linie per alternativă
                }
            }
            results
        }.onFailure { Log.e(TAG, "getTransitArrivals eșuat", it) }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun mapVehicleType(type: String): String = when (type.uppercase()) {
        "BUS"        -> "Autobuz"
        "TRAM"       -> "Tramvai"
        "TROLLEYBUS" -> "Troleibuz"
        "SUBWAY"     -> "Metrou"
        "RAIL"       -> "Tren"
        "FERRY"      -> "Feribot"
        else         -> "Vehicul"
    }

    private fun fetchJson(urlString: String): JSONObject {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout    = TIMEOUT_MS
        conn.requestMethod  = "GET"
        return try {
            JSONObject(InputStreamReader(conn.inputStream).readText())
        } finally {
            conn.disconnect()
        }
    }
}