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
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces == null) return null
            var pick: Inet4Address? = null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                if (!ni.displayName.contains("wlan", ignoreCase = true)) continue
                val e = ni.inetAddresses
                while (e.hasMoreElements()) {
                    val a = e.nextElement()
                    if (a is Inet4Address) {
                        pick = a
                        break
                    }
                }
                if (pick != null) break
            }
            pick?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}