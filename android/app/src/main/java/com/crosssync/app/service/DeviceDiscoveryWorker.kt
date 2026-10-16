package com.crosssync.app.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.crosssync.app.protocol.ProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import android.net.wifi.WifiManager

/**
 * WorkManager worker that scans the local network for CrossSync Windows PCs.
 *
 * Sends a UDP broadcast probe ("CROSSSYNC_DISCOVER") to port 45679 on every interface;
 * the PC's [DeviceDiscoveryService.cs] replies with its machine name.
 *
 * Results are returned as work output data, read by [MainViewModel].
 */
class DeviceDiscoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val devices = mutableListOf<DiscoveredDevice>()

        try {
            // 1. UDP broadcast scan
            val udpDevices = udpBroadcastScan()
            devices.addAll(udpDevices)
        } catch (e: Exception) {
            Log.w(TAG, "UDP scan failed: ${e.message}")
        }

        // Publish results via output data
        val ipsJson = devices.distinctBy { it.ip }.joinToString(",") { "${it.ip}|${it.hostname}" }
        val output = workDataOf(KEY_DISCOVERED to ipsJson)
        Result.success(output)
    }

    private fun udpBroadcastScan(): List<DiscoveredDevice> {
        val devices = mutableListOf<DiscoveredDevice>()
        val wifiManager = applicationContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("CrossSyncDiscovery")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 500

                val magic = ProtocolConstants.DISCOVERY_MAGIC.toByteArray()

                // Send to 255.255.255.255 as well as all interface broadcast addresses
                val broadcastTargets = mutableSetOf<InetAddress>()
                runCatching { broadcastTargets.add(InetAddress.getByName("255.255.255.255")) }

                runCatching {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        if (iface.isLoopback || !iface.isUp) continue
                        for (ifaceAddr in iface.interfaceAddresses) {
                            ifaceAddr.broadcast?.let { broadcastTargets.add(it) }
                        }
                    }
                }

                for (target in broadcastTargets) {
                    runCatching {
                        val packet = DatagramPacket(
                            magic, magic.size, target, ProtocolConstants.DISCOVERY_PORT
                        )
                        socket.send(packet)
                    }
                }

                // Collect responses for up to 2 seconds
                val buf = ByteArray(256)
                val responseDeadline = System.currentTimeMillis() + 2_000L
                while (System.currentTimeMillis() < responseDeadline) {
                    try {
                        val response = DatagramPacket(buf, buf.size)
                        socket.receive(response)
                        val hostname = String(response.data, 0, response.length).trim()
                        val ip = response.address.hostAddress ?: ""
                        if (ip.isNotBlank() && hostname.isNotBlank()) {
                            devices.add(DiscoveredDevice(ip, hostname))
                        }
                    } catch (_: SocketTimeoutException) {
                        // Resend probe once more if deadline still has time
                        if (System.currentTimeMillis() < responseDeadline - 500) {
                            for (target in broadcastTargets) {
                                runCatching {
                                    val packet = DatagramPacket(magic, magic.size, target, ProtocolConstants.DISCOVERY_PORT)
                                    socket.send(packet)
                                }
                            }
                        }
                    } catch (_: IOException) {
                        break
                    }
                }
            }
        } finally {
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }
        return devices
    }

    companion object {
        private const val TAG = "DeviceDiscoveryWorker"

        const val KEY_DISCOVERED = "discovered_devices"
        const val WORK_NAME = "device_discovery"

        /** Starts a scan, replacing any running one. Returns the request ID to observe. */
        fun enqueue(context: Context): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<DeviceDiscoveryWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }
    }
}

/** A discovered CrossSync peer on the local network. */
data class DiscoveredDevice(
    val ip: String,
    val hostname: String,
)
