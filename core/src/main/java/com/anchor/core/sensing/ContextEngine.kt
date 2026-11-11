package com.anchor.core.sensing

import com.anchor.core.time.Clock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Pure Kotlin Context Sensing Engine implementing Blueprint 43 §3.
 * Manages two independent axes: Place and Motion.
 */
class ContextEngine(
    private val clock: Clock,
    val config: ContextConfig = ContextConfig(),
    private val eventLogger: ((SensingEvent) -> Unit)? = null
) {

    data class ContextConfig(
        val awayConfirmMs: Long = 4 * 60_000L,
        val homeConfirmMs: Long = 2 * 60_000L,
        val fastPathMotionMs: Long = 90_000L,
        val flapGuardStillMs: Long = 15 * 60_000L,
        val motionMinMs: Long = 45_000L,
        val unknownTimeoutMs: Long = 30 * 60_000L,
        val triggerCooldownMs: Long = 30 * 60_000L,
        val homeReturnMinAwayMs: Long = 2 * 3600_000L
    )

    private var currentPlace: PlaceAxis = PlaceAxis.Unknown
    private var currentMotion: MotionAxis = MotionAxis.UNKNOWN
    private var placeSinceUtc: Long = clock.now()
    private var motionSinceUtc: Long = clock.now()

    // Pending candidates and timers
    private var pendingPlace: PlaceAxis? = null
    private var pendingPlaceSinceUtc: Long = 0L

    private var pendingMotion: MotionAxis? = null
    private var pendingMotionSinceUtc: Long = 0L

    // Flap guard & fast path tracking
    private var wifiLostSinceUtc: Long? = null
    private var motionInLeaveSinceUtc: Long? = null

    // For home-again trigger
    private var lastLeftHomeUtc: Long? = null
    private var lastTriggerTime = mutableMapOf<ContextTrigger, Long>()

    // Last signal timestamp for unknown timeout
    private var lastSignalUtc: Long = clock.now()

    @Synchronized
    fun getState(): ContextState {
        return ContextState(
            place = currentPlace,
            motion = currentMotion,
            placeSinceUtc = placeSinceUtc,
            motionSinceUtc = motionSinceUtc
        )
    }

    @Synchronized
    fun isVehicleHoldActive(): Boolean = currentMotion == MotionAxis.VEHICLE

    @Synchronized
    fun onWifiSignal(
        ssid: String?,
        bssids: List<String>,
        knownPlaces: List<Pair<Place, PlaceSignal>>,
        tsUtc: Long = clock.now()
    ): List<ContextTrigger> {
        lastSignalUtc = tsUtc

        val matchedPlace = if (!ssid.isNullOrBlank()) {
            knownPlaces.firstOrNull { (_, signal) ->
                val wifi = signal.wifiJson
                if (wifi != null) {
                    val ssidMatch = wifi.contains("\"ssid\":\"$ssid\"", ignoreCase = true)
                    val bssidMatch = bssids.isNotEmpty() && bssids.any { bssid ->
                        wifi.contains(bssid, ignoreCase = true)
                    }
                    ssidMatch || bssidMatch
                } else false
            }?.first
        } else null

        val targetPlace = when {
            matchedPlace != null && matchedPlace.kind == "home" -> PlaceAxis.Home
            matchedPlace != null -> PlaceAxis.SavedPlace(matchedPlace.id, matchedPlace.label)
            ssid.isNullOrBlank() -> PlaceAxis.Away
            else -> PlaceAxis.Away
        }

        if (targetPlace == PlaceAxis.Away) {
            if (wifiLostSinceUtc == null) wifiLostSinceUtc = tsUtc
        } else {
            wifiLostSinceUtc = null
        }

        return evaluatePlaceCandidate(targetPlace, tsUtc, source = "wifi")
    }

    @Synchronized
    fun onMotionSignal(
        motion: MotionAxis,
        confidence: Float = 1.0f,
        tsUtc: Long = clock.now()
    ): List<ContextTrigger> {
        lastSignalUtc = tsUtc

        if (motion == MotionAxis.WALKING || motion == MotionAxis.VEHICLE) {
            if (motionInLeaveSinceUtc == null) motionInLeaveSinceUtc = tsUtc
        } else {
            motionInLeaveSinceUtc = null
        }

        if (motion == currentMotion) {
            pendingMotion = null
            return emptyList()
        }

        if (pendingMotion != motion) {
            pendingMotion = motion
            pendingMotionSinceUtc = tsUtc
        }

        return checkTimeouts(tsUtc)
    }

    @Synchronized
    fun onManualSignal(place: PlaceAxis, tsUtc: Long = clock.now()): List<ContextTrigger> {
        lastSignalUtc = tsUtc
        val triggers = mutableListOf<ContextTrigger>()
        if (currentPlace != place) {
            val fromState = currentPlace.toString()
            val toState = place.toString()
            currentPlace = place
            placeSinceUtc = tsUtc
            pendingPlace = null

            recordEvent(
                axis = "place",
                fromState = fromState,
                toState = toState,
                source = "manual",
                tsUtc = tsUtc
            )
        }
        return triggers
    }

    @Synchronized
    fun checkTimeouts(tsUtc: Long = clock.now()): List<ContextTrigger> {
        val triggers = mutableListOf<ContextTrigger>()

        // 1. Check motion debounce
        pendingMotion?.let { pMotion ->
            if (tsUtc - pendingMotionSinceUtc >= config.motionMinMs) {
                val from = currentMotion.name
                currentMotion = pMotion
                motionSinceUtc = tsUtc
                pendingMotion = null
                recordEvent(
                    axis = "motion",
                    fromState = from,
                    toState = pMotion.name,
                    source = "sensor",
                    tsUtc = tsUtc
                )
            }
        }

        // 2. Fast-path check: At Home + Wi-Fi lost + continuous walking/vehicle for 90s
        if (currentPlace == PlaceAxis.Home && wifiLostSinceUtc != null) {
            val walkingOrVehicle = currentMotion == MotionAxis.WALKING || currentMotion == MotionAxis.VEHICLE
            val motionElapsed = if (walkingOrVehicle && motionInLeaveSinceUtc != null) {
                tsUtc - motionInLeaveSinceUtc!!
            } else 0L

            val wifiLostElapsed = tsUtc - wifiLostSinceUtc!!
            if (wifiLostElapsed >= config.fastPathMotionMs && motionElapsed >= config.fastPathMotionMs) {
                // Fast path confirmed Away!
                triggers.addAll(applyPlaceTransition(PlaceAxis.Away, tsUtc, source = "fast_path"))
                pendingPlace = null
                return triggers
            }
        }

        // 3. Flap guard check: if still and Wi-Fi lost at Home, hold for 15 minutes
        if (currentPlace == PlaceAxis.Home && pendingPlace == PlaceAxis.Away && currentMotion == MotionAxis.STILL) {
            val wifiLostElapsed = if (wifiLostSinceUtc != null) tsUtc - wifiLostSinceUtc!! else 0L
            if (wifiLostElapsed < config.flapGuardStillMs) {
                // Held by flap guard
                return triggers
            }
        }

        // 4. Normal place confirmation debounce
        pendingPlace?.let { pPlace ->
            val requiredWait = when (pPlace) {
                PlaceAxis.Home, is PlaceAxis.SavedPlace -> config.homeConfirmMs
                PlaceAxis.Away -> config.awayConfirmMs
                PlaceAxis.Unknown -> config.awayConfirmMs
            }

            if (tsUtc - pendingPlaceSinceUtc >= requiredWait) {
                triggers.addAll(applyPlaceTransition(pPlace, tsUtc, source = "wifi_confirmed"))
                pendingPlace = null
            }
        }

        // 5. Unknown timeout: no signal for 30 minutes
        if (tsUtc - lastSignalUtc >= config.unknownTimeoutMs) {
            if (currentPlace != PlaceAxis.Unknown) {
                triggers.addAll(applyPlaceTransition(PlaceAxis.Unknown, tsUtc, source = "timeout"))
            }
            if (currentMotion != MotionAxis.UNKNOWN) {
                val from = currentMotion.name
                currentMotion = MotionAxis.UNKNOWN
                motionSinceUtc = tsUtc
                recordEvent(
                    axis = "motion",
                    fromState = from,
                    toState = MotionAxis.UNKNOWN.name,
                    source = "timeout",
                    tsUtc = tsUtc
                )
            }
        }

        return triggers
    }

    private fun evaluatePlaceCandidate(candidate: PlaceAxis, tsUtc: Long, source: String): List<ContextTrigger> {
        if (candidate == currentPlace) {
            pendingPlace = null
            return emptyList()
        }

        if (pendingPlace != candidate) {
            pendingPlace = candidate
            pendingPlaceSinceUtc = tsUtc
        }

        return checkTimeouts(tsUtc)
    }

    private fun applyPlaceTransition(target: PlaceAxis, tsUtc: Long, source: String): List<ContextTrigger> {
        val fromState = currentPlace.toString()
        val toState = target.toString()
        val oldPlace = currentPlace
        currentPlace = target
        placeSinceUtc = tsUtc

        recordEvent(
            axis = "place",
            fromState = fromState,
            toState = toState,
            source = source,
            tsUtc = tsUtc
        )

        val triggers = mutableListOf<ContextTrigger>()

        // Detect triggers
        if (oldPlace == PlaceAxis.Home && target == PlaceAxis.Away) {
            lastLeftHomeUtc = tsUtc
            if (isCooldownPassed(ContextTrigger.CONTEXT_LEFT_HOME, tsUtc)) {
                triggers.add(ContextTrigger.CONTEXT_LEFT_HOME)
                lastTriggerTime[ContextTrigger.CONTEXT_LEFT_HOME] = tsUtc
            }
        } else if (target is PlaceAxis.SavedPlace) {
            if (isCooldownPassed(ContextTrigger.CONTEXT_ARRIVED, tsUtc)) {
                triggers.add(ContextTrigger.CONTEXT_ARRIVED)
                lastTriggerTime[ContextTrigger.CONTEXT_ARRIVED] = tsUtc
            }
        } else if (oldPlace == PlaceAxis.Away && target == PlaceAxis.Home) {
            val awayDuration = if (lastLeftHomeUtc != null) tsUtc - lastLeftHomeUtc!! else 0L
            if (awayDuration >= config.homeReturnMinAwayMs) {
                if (isCooldownPassed(ContextTrigger.CONTEXT_HOME_AGAIN, tsUtc)) {
                    triggers.add(ContextTrigger.CONTEXT_HOME_AGAIN)
                    lastTriggerTime[ContextTrigger.CONTEXT_HOME_AGAIN] = tsUtc
                }
            }
        }

        return triggers
    }

    private fun isCooldownPassed(trigger: ContextTrigger, tsUtc: Long): Boolean {
        val last = lastTriggerTime[trigger] ?: return true
        return tsUtc - last >= config.triggerCooldownMs
    }

    private fun recordEvent(
        axis: String,
        fromState: String?,
        toState: String,
        source: String,
        tsUtc: Long
    ) {
        val event = SensingEvent(
            id = UUID.randomUUID().toString(),
            tsUtc = tsUtc,
            axis = axis,
            fromState = fromState,
            toState = toState,
            source = source
        )
        eventLogger?.invoke(event)
    }

    /**
     * Formats concise context line for ContextBuilder Now block per Blueprint 43 §3.4.
     * E.g. "Context: at Home, walking (since 14:05)"
     * Zero SSID or coordinates are ever emitted.
     */
    fun formatNowContext(timeZone: TimeZone = TimeZone.getDefault(), tsUtc: Long = clock.now()): String? {
        val placePart = when (val p = currentPlace) {
            PlaceAxis.Unknown -> null
            PlaceAxis.Home -> "at Home"
            PlaceAxis.Away -> "away"
            is PlaceAxis.SavedPlace -> "at ${p.label}"
        }

        val motionPart = when (currentMotion) {
            MotionAxis.UNKNOWN -> null
            MotionAxis.STILL -> "still"
            MotionAxis.WALKING -> "walking"
            MotionAxis.VEHICLE -> "in vehicle"
        }

        if (placePart == null && motionPart == null) return null

        val sinceUtc = maxOf(placeSinceUtc, motionSinceUtc)
        val sdf = SimpleDateFormat("HH:mm", Locale.US).apply {
            this.timeZone = timeZone
        }
        val sinceStr = sdf.format(Date(sinceUtc))

        val combined = listOfNotNull(placePart, motionPart).joinToString(", ")
        return "Context: $combined (since $sinceStr)"
    }
}
