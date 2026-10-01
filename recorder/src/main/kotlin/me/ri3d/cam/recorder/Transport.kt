package me.ri3d.cam.recorder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Byte stream to the recorder. One instance per connection attempt. */
interface RecorderTransport {
    suspend fun connect(host: String, port: Int, timeoutMs: Int)
    suspend fun write(bytes: ByteArray)

    /** Reads up to `buf.size` bytes into [buf]; returns the count, or -1 at end of stream. */
    suspend fun read(buf: ByteArray): Int
    fun close()
}

fun interface RecorderTransportFactory {
    fun create(): RecorderTransport
}

/**
 * Plain TCP. [newSocket] lets the Android layer hand in an unconnected socket bound to the recorder Wi-Fi
 * (`network.socketFactory.createSocket()` or `Socket().also(network::bindSocket)`), so mobile data stays usable.
 */
class SocketTransport(newSocket: () -> Socket = ::Socket) : RecorderTransport {
    private val socket = newSocket()

    override suspend fun connect(host: String, port: Int, timeoutMs: Int) = withContext(Dispatchers.IO) {
        socket.keepAlive = true // SO_KEEPALIVE, as the SDK configures it
        socket.connect(InetSocketAddress(host, port), timeoutMs)
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = socket.getOutputStream()
        out.write(bytes)
        out.flush()
    }

    override suspend fun read(buf: ByteArray): Int = withContext(Dispatchers.IO) { socket.getInputStream().read(buf) }

    override fun close() = socket.close()
}
