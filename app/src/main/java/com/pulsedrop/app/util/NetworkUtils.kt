package com.pulsedrop.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

object NetworkUtils {

    fun getDefaultDeviceName(): String {
        val model = Build.MODEL
        val brand = Build.BRAND.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        return if (model.startsWith(brand, ignoreCase = true)) {
            model
        } else {
            "$brand $model"
        }
    }

    /**
     * Resolves the primary local IPv4 address.
     * Prioritizes wlan0 (Wi-Fi client) or ap0 / tethering interfaces.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val ipList = mutableListOf<Pair<String, String>>() // interfaceName, ip

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress ?: continue
                        if (!hostAddress.startsWith("127.")) {
                            ipList.add(Pair(networkInterface.name.lowercase(Locale.ROOT), hostAddress))
                        }
                    }
                }
            }

            // Priority: wlan (Wi-Fi), ap/softap (Hotspot), eth (Ethernet), then others
            val wlanIp = ipList.firstOrNull { it.first.contains("wlan") }
            if (wlanIp != null) return wlanIp.second

            val apIp = ipList.firstOrNull { it.first.contains("ap") || it.first.contains("softap") || it.first.contains("rndis") }
            if (apIp != null) return apIp.second

            val ethIp = ipList.firstOrNull { it.first.contains("eth") }
            if (ethIp != null) return ethIp.second

            return ipList.firstOrNull()?.second ?: "127.0.0.1"
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }

    fun isWifiOrHotspotConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 KB/s"
        val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
        return if (mbPerSec >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mbPerSec)
        } else {
            val kbPerSec = bytesPerSec / 1024.0
            String.format(Locale.US, "%.1f KB/s", kbPerSec)
        }
    }
}
