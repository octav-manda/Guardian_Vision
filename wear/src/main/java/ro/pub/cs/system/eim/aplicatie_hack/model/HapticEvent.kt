package ro.pub.cs.system.eim.aplicatie_hack.model

/**
 * Copie identică cu cea din modulul :app.
 * Serializată ca String pentru transport prin Wearable MessageClient.
 * Ambele module trebuie să fie sincronizate manual dacă se adaugă noi valori.
 */
enum class HapticEvent {
    OBSTACLE_DISTANT,
    OBSTACLE_APPROACHING,
    DANGER_IMMINENT,
    TRAFFIC_LIGHT_GREEN,
    TRAFFIC_LIGHT_RED,
    ROUTE_DEVIATION,
}