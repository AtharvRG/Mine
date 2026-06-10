package com.mine.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NetworkMonitor(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline

    private val _networkType = MutableStateFlow("Offline")
    val networkType: StateFlow<String> = _networkType

    init {
        updateNetworkState(connectivityManager.activeNetwork)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkState(network)
            }

            override fun onLost(network: Network) {
                _isOnline.value = false
                _networkType.value = "Offline"
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateNetworkState(network)
            }
        })
    }

    private fun updateNetworkState(network: Network?) {
        if (network == null) {
            _isOnline.value = false
            _networkType.value = "Offline"
            return
        }
        val caps = connectivityManager.getNetworkCapabilities(network)
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            _isOnline.value = true

            // Trigger the QueueWorker to drain any offline backlog
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<com.mine.app.worker.QueueWorker>().build())

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                _networkType.value = "Wi-Fi (Likely Indoors/Home)"
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                _networkType.value = "Cellular (Likely Outside/Transit)"
            } else {
                _networkType.value = "Unknown Connection"
            }
        }
    }
}
