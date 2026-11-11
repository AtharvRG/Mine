package com.anchor.core.sensing

import com.anchor.core.time.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ContextEngineTest {

    private fun FakeClock.advance(ms: Long) { currentTime += ms }

    private val baseNow = 1700000000000L // arbitrary fixed epoch
    private val clock = FakeClock(baseNow)
    private val engine = ContextEngine(clock = clock)

    private val homePlace = Place(
        id = "place_home",
        nodeId = null,
        kind = "home",
        label = "Home",
        updatedAt = baseNow
    )
    private val homeSignal = PlaceSignal(
        placeId = "place_home",
        wifiJson = """{"ssid":"MyHomeWifi","bssids":["aa:bb:cc:dd:ee:ff"]}"""
    )

    private val workPlace = Place(
        id = "place_work",
        nodeId = "node_work_1",
        kind = "work",
        label = "Office",
        updatedAt = baseNow
    )
    private val workSignal = PlaceSignal(
        placeId = "place_work",
        wifiJson = """{"ssid":"CorporateNet","bssids":["11:22:33:44:55:66"]}"""
    )

    private val knownPlaces = listOf(homePlace to homeSignal, workPlace to workSignal)

    @Test
    fun testInitialState_IsUnknown() {
        val state = engine.getState()
        assertEquals(PlaceAxis.Unknown, state.place)
        assertEquals(MotionAxis.UNKNOWN, state.motion)
        assertNull(engine.formatNowContext())
    }

    @Test
    fun testConnectHomeWifi_AfterConfirmation_TransitionsToHome() {
        // Connect to home Wi-Fi
        val triggers1 = engine.onWifiSignal(ssid = "MyHomeWifi", bssids = listOf("aa:bb:cc:dd:ee:ff"), knownPlaces = knownPlaces)
        assertTrue(triggers1.isEmpty()) // Pending confirmation (2 min)

        // Advance 1 minute -> still pending
        clock.advance(60_000L)
        val triggersPending = engine.checkTimeouts()
        assertTrue(triggersPending.isEmpty())
        assertEquals(PlaceAxis.Unknown, engine.getState().place)

        // Advance another minute (total 2 minutes) -> confirmed Home
        clock.advance(60_000L)
        val triggers2 = engine.checkTimeouts()
        assertEquals(PlaceAxis.Home, engine.getState().place)
    }

    @Test
    fun testWalkingInsideHome_MotionChangesWithoutLeavingHome() {
        // Establish Home state
        engine.onWifiSignal(ssid = "MyHomeWifi", bssids = listOf("aa:bb:cc:dd:ee:ff"), knownPlaces = knownPlaces)
        clock.advance(120_000L)
        engine.checkTimeouts()
        assertEquals(PlaceAxis.Home, engine.getState().place)

        // User starts walking inside home
        engine.onMotionSignal(MotionAxis.WALKING)
        clock.advance(45_000L) // motionMinMs = 45s
        engine.checkTimeouts()

        val state = engine.getState()
        assertEquals(PlaceAxis.Home, state.place)
        assertEquals(MotionAxis.WALKING, state.motion)

        // Format now context produces clean prompt line without SSID
        val promptContext = engine.formatNowContext(timeZone = TimeZone.getTimeZone("UTC"))
        assertNotNull(promptContext)
        assertTrue(promptContext!!.contains("at Home, walking"))
        assertFalse(promptContext.contains("MyHomeWifi"))
    }

    @Test
    fun testFlapGuard_WifiDropsWhileStill_StaysHomeFor15Minutes() {
        // At Home and STILL
        engine.onWifiSignal(ssid = "MyHomeWifi", bssids = listOf("aa:bb:cc:dd:ee:ff"), knownPlaces = knownPlaces)
        clock.advance(120_000L)
        engine.onMotionSignal(MotionAxis.STILL)
        clock.advance(45_000L)
        engine.checkTimeouts()
        assertEquals(PlaceAxis.Home, engine.getState().place)

        // Router restarts -> Wi-Fi signal lost (null)
        val triggers = engine.onWifiSignal(ssid = null, bssids = emptyList(), knownPlaces = knownPlaces)
        assertTrue(triggers.isEmpty())

        // 10 minutes pass with phone STILL on desk
        clock.advance(10 * 60_000L)
        engine.checkTimeouts()
        // Flap guard keeps user at HOME!
        assertEquals(PlaceAxis.Home, engine.getState().place)

        // Router comes back online
        engine.onWifiSignal(ssid = "MyHomeWifi", bssids = listOf("aa:bb:cc:dd:ee:ff"), knownPlaces = knownPlaces)
        clock.advance(60_000L)
        engine.checkTimeouts()
        assertEquals(PlaceAxis.Home, engine.getState().place)
    }

    @Test
    fun testFastPath_WifiLostAndWalking_TransitionsToAwayIn90Seconds() {
        // At Home
        engine.onWifiSignal(ssid = "MyHomeWifi", bssids = listOf("aa:bb:cc:dd:ee:ff"), knownPlaces = knownPlaces)
        clock.advance(120_000L)
        engine.checkTimeouts()
        assertEquals(PlaceAxis.Home, engine.getState().place)

        // Wi-Fi lost and user is walking
        engine.onWifiSignal(ssid = null, bssids = emptyList(), knownPlaces = knownPlaces)
        engine.onMotionSignal(MotionAxis.WALKING)

        // 30 seconds -> still pending
        clock.advance(30_000L)
        engine.checkTimeouts()
        assertEquals(PlaceAxis.Home, engine.getState().place)

        // Advance to 90 seconds continuous walking without Wi-Fi -> fast path confirms Away!
        clock.advance(60_000L)
        val triggers = engine.checkTimeouts()

        assertEquals(PlaceAxis.Away, engine.getState().place)
        assertTrue(triggers.contains(ContextTrigger.CONTEXT_LEFT_HOME))
    }

    @Test
    fun testArriveAtWork_TransitionsToSavedPlace() {
        // Start from Away
        engine.onManualSignal(PlaceAxis.Away)
        assertEquals(PlaceAxis.Away, engine.getState().place)

        // Connect to work Wi-Fi
        engine.onWifiSignal(ssid = "CorporateNet", bssids = listOf("11:22:33:44:55:66"), knownPlaces = knownPlaces)
        clock.advance(120_000L) // 2 min confirm
        val triggers = engine.checkTimeouts()

        val state = engine.getState()
        assertTrue(state.place is PlaceAxis.SavedPlace)
        assertEquals("Office", (state.place as PlaceAxis.SavedPlace).label)
        assertTrue(triggers.contains(ContextTrigger.CONTEXT_ARRIVED))

        val prompt = engine.formatNowContext(timeZone = TimeZone.getTimeZone("UTC"))
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("at Office"))
        assertFalse(prompt.contains("CorporateNet"))
    }

    @Test
    fun testVehicleHold_IdentifiesVehicleMotionState() {
        engine.onMotionSignal(MotionAxis.VEHICLE)
        clock.advance(45_000L)
        engine.checkTimeouts()

        assertEquals(MotionAxis.VEHICLE, engine.getState().motion)
        assertTrue(engine.isVehicleHoldActive())
    }

    @Test
    fun testReturnHomeAfter2Hours_TriggersHomeAgain() {
        // Start Home
        engine.onWifiSignal(ssid = "MyHomeWifi", bssids = listOf("aa:bb:cc:dd:ee:ff"), knownPlaces = knownPlaces)
        clock.advance(120_000L)
        engine.checkTimeouts()

        // Fast path leave Home -> Away
        engine.onWifiSignal(ssid = null, bssids = emptyList(), knownPlaces = knownPlaces)
        engine.onMotionSignal(MotionAxis.WALKING)
        clock.advance(90_000L)
        engine.checkTimeouts()
        assertEquals(PlaceAxis.Away, engine.getState().place)

        // 2.5 hours away
        clock.advance(2 * 3600_000L + 1800_000L)

        // Return Home Wi-Fi
        engine.onWifiSignal(ssid = "MyHomeWifi", bssids = listOf("aa:bb:cc:dd:ee:ff"), knownPlaces = knownPlaces)
        clock.advance(120_000L)
        val triggers = engine.checkTimeouts()

        assertEquals(PlaceAxis.Home, engine.getState().place)
        assertTrue(triggers.contains(ContextTrigger.CONTEXT_HOME_AGAIN))
    }
}
