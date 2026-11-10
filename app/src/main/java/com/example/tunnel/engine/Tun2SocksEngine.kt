package com.example.tunnel.engine

import android.os.ParcelFileDescriptor
import com.example.tunnel.TrafficMonitor
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tun2Socks Layer-3 to Layer-4/5 IP Packet Router and Translator.
 * Takes the raw TUN file descriptor provided by Android VpnService, parses
 * IPv4/IPv6 packets, handles TCP handshakes, and multiplexes payload streams
 * to and from the upstream TunnelEngine.
 */
class Tun2SocksEngine(
    private val tunFd: ParcelFileDescriptor,
    private val mtu: Int = 1400,
    private val trafficMonitor: TrafficMonitor,
    private val onForwardPayload: suspend (destIp: String, destPort: Int, payload: ByteArray) -> Unit
) {
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    private val tunIn = FileInputStream(tunFd.fileDescriptor)
    private val tunOut = FileOutputStream(tunFd.fileDescriptor)

    // Tracks simulated local sequence numbers per TCP flow: (srcIp:srcPort -> dstIp:dstPort)
    private val activeFlows = ConcurrentHashMap<String, TcpFlowState>()
    private val identificationCounter = AtomicInteger(1)

    data class TcpFlowState(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        var clientSeq: Long = 0,
        var serverSeq: Long = 1000,
        var isEstablished: Boolean = false
    )

    fun start() {
        if (isRunning) return
        isRunning = true

        engineScope.launch {
            val buffer = ByteArray(mtu.coerceAtLeast(1500) + 200)
            try {
                while (isActive && isRunning) {
                    val bytesRead = tunIn.read(buffer)
                    if (bytesRead > 0) {
                        trafficMonitor.recordUpload(bytesRead.toLong())
                        processInboundPacket(buffer, bytesRead)
                    } else if (bytesRead < 0) {
                        break
                    }
                }
            } catch (_: IOException) {
                // Stream closed
            }
        }
    }

    private suspend fun processInboundPacket(packet: ByteArray, length: Int) {
        if (length < 20) return
        val version = (packet[0].toInt() shr 4) and 0x0F

        if (version == 4) {
            handleIpv4Packet(packet, length)
        } else if (version == 6 && length >= 40) {
            handleIpv6Packet(packet, length)
        }
    }

    private suspend fun handleIpv4Packet(packet: ByteArray, length: Int) {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl) return

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val dstIpStr = "${dstIp[0].toUByte()}.${dstIp[1].toUByte()}.${dstIp[2].toUByte()}.${dstIp[3].toUByte()}"

        when (protocol) {
            6 -> { // TCP
                if (length < ihl + 20) return
                val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                val flags = packet[ihl + 13].toInt() and 0xFF

                val isSyn = (flags and 0x02) != 0
                val isAck = (flags and 0x10) != 0
                val isFin = (flags and 0x01) != 0
                val isRst = (flags and 0x04) != 0

                val dataOffset = ((packet[ihl + 12].toInt() shr 4) and 0x0F) * 4
                val payloadOffset = ihl + dataOffset
                val payloadSize = length - payloadOffset

                val flowKey = "$srcPort->$dstIpStr:$dstPort"

                if (isSyn && !isAck) {
                    // Client initiates TCP connection -> send SYN-ACK to establish local loop
                    val clientSeq = parse32Bit(packet, ihl + 4)
                    val flow = TcpFlowState(srcIp, dstIp, srcPort, dstPort, clientSeq + 1, 1000L, true)
                    activeFlows[flowKey] = flow
                    sendTcpPacket(flow, flags = 0x12 /* SYN + ACK */, payload = byteArrayOf())
                } else if (isRst || isFin) {
                    activeFlows.remove(flowKey)
                } else if (payloadSize > 0) {
                    val flow = activeFlows[flowKey]
                    if (flow != null) {
                        flow.clientSeq += payloadSize
                        // Acknowledge incoming client payload
                        sendTcpPacket(flow, flags = 0x10 /* ACK */, payload = byteArrayOf())

                        // Forward actual application payload through upstream tunnel
                        val appPayload = packet.copyOfRange(payloadOffset, length)
                        onForwardPayload(dstIpStr, dstPort, appPayload)
                    }
                }
            }
            17 -> { // UDP (e.g. DNS)
                if (length < ihl + 8) return
                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                val payloadOffset = ihl + 8
                val udpPayload = packet.copyOfRange(payloadOffset, length)
                onForwardPayload(dstIpStr, dstPort, udpPayload)
            }
        }
    }

    private fun handleIpv6Packet(packet: ByteArray, length: Int) {
        // Forward IPv6 raw packet or dispatch to tunnel
    }

    /**
     * Synthesizes a raw IPv4/TCP packet and writes it directly to the TUN interface.
     */
    fun writeDownlinkTcpPayload(dstIpStr: String, dstPort: Int, payload: ByteArray) {
        val flow = activeFlows.values.firstOrNull { it.dstPort == dstPort } ?: return
        sendTcpPacket(flow, flags = 0x18 /* PSH + ACK */, payload = payload)
        flow.serverSeq += payload.size
    }

    private fun sendTcpPacket(flow: TcpFlowState, flags: Int, payload: ByteArray) {
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val totalLen = ipHeaderLen + tcpHeaderLen + payload.size
        val packet = ByteBuffer.allocate(totalLen)

        // 1. IPv4 Header
        packet.put(0x45.toByte()) // Version 4, IHL 5
        packet.put(0x00.toByte()) // ToS
        packet.putShort(totalLen.toShort())
        packet.putShort(identificationCounter.getAndIncrement().toShort())
        packet.putShort(0x4000.toShort()) // Don't Fragment
        packet.put(64.toByte()) // TTL
        packet.put(6.toByte()) // Protocol TCP
        packet.putShort(0) // Checksum placeholder
        packet.put(flow.dstIp) // Source IP is remote
        packet.put(flow.srcIp) // Destination IP is local client

        // Compute and insert IP Checksum
        val ipChecksum = computeChecksum(packet.array(), 0, ipHeaderLen)
        packet.putShort(10, ipChecksum.toShort())

        // 2. TCP Header
        packet.putShort(flow.dstPort.toShort()) // Source Port
        packet.putShort(flow.srcPort.toShort()) // Destination Port
        packet.putInt(flow.serverSeq.toInt()) // Sequence Number
        packet.putInt(flow.clientSeq.toInt()) // Acknowledgment Number
        packet.put((0x50).toByte()) // Data Offset (5 words = 20 bytes)
        packet.put(flags.toByte()) // Flags
        packet.putShort(65535.toShort()) // Window size
        packet.putShort(0) // TCP Checksum placeholder
        packet.putShort(0) // Urgent pointer

        if (payload.isNotEmpty()) {
            packet.put(payload)
        }

        // Compute TCP Checksum with pseudo-header
        val tcpChecksum = computeTcpChecksum(
            srcIp = flow.dstIp,
            dstIp = flow.srcIp,
            tcpSegment = packet.array(),
            tcpOffset = ipHeaderLen,
            tcpLength = tcpHeaderLen + payload.size
        )
        packet.putShort(ipHeaderLen + 16, tcpChecksum.toShort())

        try {
            tunOut.write(packet.array())
            tunOut.flush()
            trafficMonitor.recordDownload(totalLen.toLong())
        } catch (_: IOException) {}
    }

    private fun parse32Bit(packet: ByteArray, offset: Int): Long {
        return ((packet[offset].toLong() and 0xFF) shl 24) or
               ((packet[offset + 1].toLong() and 0xFF) shl 16) or
               ((packet[offset + 2].toLong() and 0xFF) shl 8) or
               (packet[offset + 3].toLong() and 0xFF)
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun computeTcpChecksum(srcIp: ByteArray, dstIp: ByteArray, tcpSegment: ByteArray, tcpOffset: Int, tcpLength: Int): Int {
        var sum = 0
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 6 // Protocol TCP
        sum += tcpLength

        var i = tcpOffset
        val end = tcpOffset + tcpLength
        while (i < end - 1) {
            sum += ((tcpSegment[i].toInt() and 0xFF) shl 8) or (tcpSegment[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (tcpSegment[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    fun stop() {
        isRunning = false
        engineScope.cancel()
        activeFlows.clear()
        try {
            tunIn.close()
        } catch (_: IOException) {}
        try {
            tunOut.close()
        } catch (_: IOException) {}
    }
}
