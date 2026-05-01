package ro.pub.cs.system.eim.aplicatie_hack.model

/**
 * Evenimentele haptic partajate între telefon și ceas.
 * Serializate ca String pentru transport prin MessageClient (BLE).
 */
enum class HapticEvent {
    OBSTACLE_DISTANT,    // obstacol ~3m — 1 puls ușor
    OBSTACLE_APPROACHING, // obstacol ~1.5m — 2 pulsuri medii
    DANGER_IMMINENT,     // pericol cap — buzz rapid continuu
    TRAFFIC_LIGHT_GREEN, // semafor verde — lung·scurt·scurt
    TRAFFIC_LIGHT_RED,   // semafor roșu — două lungi
    ROUTE_DEVIATION,     // deviere traseu — 3 pulsuri lente
}