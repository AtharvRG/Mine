package com.anchor.mine.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

class CallStateObserver(
    private val context: Context,
    private val onCallEnded: () -> Unit // The trigger for our Nudge!
) {
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var wasInCall = false

    // Android 12+ requires the new TelephonyCallback API
    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    Log.d("CallObserver", "Phone is ringing...")
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    Log.d("CallObserver", "In a call...")
                    wasInCall = true
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    Log.d("CallObserver", "Phone is idle.")
                    if (wasInCall) {
                        Log.d("CallObserver", "Call just ended! Triggering Nudge...")
                        onCallEnded()
                        wasInCall = false
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // We request this in HomeScreen!
    fun startObserving() {
        Log.d("CallObserver", "Starting Call Observer...")
        telephonyManager.registerTelephonyCallback(
            context.mainExecutor,
            telephonyCallback
        )
    }

    fun stopObserving() {
        Log.d("CallObserver", "Stopping Call Observer...")
        telephonyManager.unregisterTelephonyCallback(telephonyCallback)
    }
}