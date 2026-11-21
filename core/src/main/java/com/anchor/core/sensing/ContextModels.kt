package com.anchor.core.sensing

/**
 * Coarse motion axis per Blueprint 43 §3.1.
 * Deliberately coarse to respect user privacy and avoid battery drain.
 */
enum class MotionAxis {
    UNKNOWN,
    STILL,
    WALKING,
    VEHICLE
}

/**
 * Place axis per Blueprint 43 §3.1.
 * Life terms, not GPS coordinates.
 */
sealed class PlaceAxis {
    object Unknown : PlaceAxis() {
        override fun toString(): String = "Unknown"
    }

    object Home : PlaceAxis() {
        override fun toString(): String = "Home"
    }

    object Away : PlaceAxis() {
        override fun toString(): String = "Away"
    }

    data class SavedPlace(val id: String, val label: String) : PlaceAxis() {
        override fun toString(): String = label
    }
}

/**
 * Place definition stored in SQLite (synced, label and kind only).
 */
data class Place(
    val id: String,
    val nodeId: String? = null,
    val kind: String, // home | work | other
    val label: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val hlc: String? = null
)

/**
 * Device-local matching signals for a place.
 * NEVER exported in backups or synced across devices (Blueprint 43 §4).
 */
data class PlaceSignal(
    val placeId: String,
    val wifiJson: String? = null, // {"ssid": "...", "bssids": ["..."]}
    val geoLat: Double? = null,
    val geoLon: Double? = null,
    val geoRadiusM: Int? = null
)

/**
 * Device-local sensing event log entry, pruned after 30 days.
 */
data class SensingEvent(
    val id: String,
    val tsUtc: Long,
    val axis: String, // place | motion
    val fromState: String?,
    val toState: String,
    val placeId: String? = null,
    val confidence: Float? = null,
    val source: String? = null, // wifi | geofence | activity | sensor | manual
    val attrs: String? = null
)

/**
 * Active context snapshot.
 */
data class ContextState(
    val place: PlaceAxis = PlaceAxis.Unknown,
    val motion: MotionAxis = MotionAxis.UNKNOWN,
    val placeSinceUtc: Long = 0L,
    val motionSinceUtc: Long = 0L
)

/**
 * Context-driven triggers fed to the NudgeEngine (Blueprint 43 §3.5).
 */
enum class ContextTrigger {
    CONTEXT_LEFT_HOME,
    CONTEXT_ARRIVED,
    CONTEXT_HOME_AGAIN,
    CONTEXT_WALKING_STARTED
}
