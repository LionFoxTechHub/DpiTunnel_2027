package com.example.tunnel

import java.net.DatagramSocket
import java.net.Socket

/**
 * Interface implemented by DpiVpnService to protect outbound sockets
 * from being looped back into the Android TUN interface.
 */
interface SocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean
    fun protect(socketFd: Int): Boolean
}
