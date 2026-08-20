package com.filebridge.app.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** Best-effort LAN IPv4 address. Prefers Wi-Fi IP on classic APIs. */
    fun getIpv4(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiIp(context)?.let { return it }
        }
        return interfaceIp()
    }

    private fun wifiIp(context: Context): String? {
        return try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager? ?: return null
            val info = wifi.connectionInfo ?: return null
            val ip = info.ipAddress
            if (ip == 0) null else String.format(
                "%d.%d.%d.%d",
                (ip and 0xff), (ip shr 8 and 0xff), (ip shr 16 and 0xff), (ip shr 24 and 0xff)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun interfaceIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.withIndex()
                ?.firstOrNull { (_, ni) ->
                    ni.isUp && !ni.isLoopback && ni.displayName.contains("wlan", ignoreCase = true)
                }
                ?.value
                ?.inetAddresses?.asSequence()
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull()
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}