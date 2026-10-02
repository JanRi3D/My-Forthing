package me.ri3d.dashcam.recorder

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class SocketTransportTest {
    @Test
    fun suppliedSocketIsUsed_bytesFlowBothWays() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val echo = thread { server.accept().use { s -> s.getOutputStream().write(s.getInputStream().readNBytes(5)) } }
            var supplied: Socket? = null
            // On Android this lambda returns a socket bound to the recorder Wi-Fi Network.
            val transport = SocketTransport { Socket().also { supplied = it } }
            transport.connect(server.inetAddress.hostAddress, server.localPort, 3000)
            assertThat(supplied!!.isConnected).isTrue()
            assertThat(supplied!!.keepAlive).isTrue()

            transport.write("hello".toByteArray())
            val got = ByteArrayOutputStream()
            val buf = ByteArray(16)
            while (got.size() < 5) {
                val n = transport.read(buf)
                if (n < 0) break
                got.write(buf, 0, n)
            }
            assertThat(got.toString(Charsets.US_ASCII)).isEqualTo("hello")
            echo.join()
            assertThat(transport.read(buf)).isEqualTo(-1) // peer closed
            transport.close()
        }
    }
}
