package com.phoneel

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Auto-discovery: finds phoneel PC servers on the local Wi-Fi network.
 * Sends broadcast UDP pings to both 255.255.255.255 and subnet broadcast addresses
 * (e.g. 192.168.1.255) so routers that block global broadcast still respond immediately.
 */
data class DiscoveredServer(
    val ip: String,
    val hostname: String
)

object Discovery {

    fun scan(timeoutMs: Int = 1800): List<DiscoveredServer> {
        val servers = mutableListOf<DiscoveredServer>()
        val seenIps = mutableSetOf<String>()

        try {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 450

            val pingData = Protocol.buildDiscoveryPing(Protocol.DEFAULT_PORT)
            val targets = mutableSetOf<InetAddress>()
            try {
                targets.add(InetAddress.getByName("255.255.255.255"))
            } catch (_: Exception) {}

            // Collect subnet broadcast addresses for all active IPv4 interfaces
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        val bcast = ia.broadcast
                        if (bcast != null && bcast is Inet4Address) {
                            targets.add(bcast)
                        }
                    }
                }
            } catch (_: Exception) {}

            // Send two bursts for reliability against UDP loss
            for (burst in 0..1) {
                for (addr in targets) {
                    try {
                        socket.send(DatagramPacket(pingData, pingData.size, addr, Protocol.DISCOVERY_PORT))
                    } catch (_: Exception) {}
                }
            }

            val buf = ByteArray(256)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                try {
                    val response = DatagramPacket(buf, buf.size)
                    socket.receive(response)

                    val data = response.data.copyOf(response.length)
                    val hostname = Protocol.parseDiscoveryPong(data)
                    val ip = response.address.hostAddress ?: continue

                    if (hostname != null && seenIps.add(ip)) {
                        val server = DiscoveredServer(ip = ip, hostname = hostname)
                        servers.add(server)
                        Log.i("phoneel", "Discovery: found ${server.hostname} at ${server.ip}")
                    }
                } catch (_: SocketTimeoutException) {
                    if (servers.isNotEmpty()) break
                }
            }

            socket.close()
        } catch (e: Exception) {
            Log.e("phoneel", "Discovery error: ${e.message}")
        }

        return servers
    }
}
