// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.provider

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Blueprint 41 §3.1 `no_network`: "network validated and the provider answers". This answers the
 * first half — whether the device has a network that can reach the internet right now.
 *
 * It is deliberately conservative in one direction only: `VALIDATED` means the OS has confirmed
 * the network actually reaches the internet, so a captive-portal Wi-Fi or a connected-but-dead
 * mobile link reads as offline and the message is queued instead of hanging for the read timeout.
 * The other direction costs one failed request, which the transport already maps to
 * `OfflineException` and the controller already queues on.
 */
class ConnectivityOracle(private val context: Context) {
    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // No manager is not evidence of being offline; let the request decide.
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
