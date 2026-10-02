package me.ri3d.dashcam.recorder

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Test fixture: the recorder's media HTTP server (port 80 on the real device, no authentication as traced) for
 * [SimulatedFiles]. GET/HEAD of a listed path; `Range: bytes=<start>-[<end>]` → 206 with Content-Range, a range
 * starting past the end → 416; anything else → 200. [bytesPerSecond] > 0 throttles bodies (resume tests).
 * Whether the real recorder supports ranges at all is unverified.
 */
class SimulatorHttpServer(
    private val files: SimulatedFiles,
    port: Int = 0,
    @Volatile var bytesPerSecond: Long = 0,
    host: InetAddress = InetAddress.getLoopbackAddress(),
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "sim-http").apply { isDaemon = true } }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        createContext("/") { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
        executor = this@SimulatorHttpServer.executor
        start()
    }

    val port: Int get() = server.address.port

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val head = exchange.requestMethod == "HEAD"
        if (!head && exchange.requestMethod != "GET") return exchange.sendResponseHeaders(405, -1)
        val body = files.content(path) ?: return exchange.sendResponseHeaders(404, -1).also { log("${exchange.requestMethod} $path -> 404") }
        val size = body.size.toLong()
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        val range = rangeHeader?.let(::parseRange)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Content-Type", contentType(path))
        if (range != null && range.first >= size) {
            exchange.responseHeaders.add("Content-Range", "bytes */$size")
            log("GET $path $rangeHeader -> 416")
            return exchange.sendResponseHeaders(416, -1)
        }
        val start = range?.first ?: 0L
        val end = minOf(range?.second ?: (size - 1), size - 1)
        val length = end - start + 1
        if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
        val code = if (range != null) 206 else 200
        log("${exchange.requestMethod} $path ${rangeHeader.orEmpty()} -> $code ($length bytes)")
        exchange.sendResponseHeaders(code, if (head) -1 else length)
        if (head) return
        try {
            exchange.responseBody.use { out ->
                var pos = start
                while (pos <= end) {
                    val n = minOf(CHUNK.toLong(), end - pos + 1).toInt()
                    out.write(body, pos.toInt(), n)
                    out.flush()
                    pos += n
                    val bps = bytesPerSecond
                    if (bps > 0) Thread.sleep(n * 1000L / bps)
                }
            }
        } catch (e: IOException) {
            log("$path: client gone")
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        private const val CHUNK = 16 * 1024

        /** `.thm` (a JPEG by content) goes out as a generic type: the real recorder's header is unverified. */
        fun contentType(path: String) = when {
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".jpg") -> "image/jpeg"
            else -> "application/octet-stream"
        }

        /** `bytes=<start>-` or `bytes=<start>-<end>` (single range); null for anything else. */
        fun parseRange(header: String): Pair<Long, Long?>? {
            val m = Regex("""bytes=(\d+)-(\d*)""").matchEntire(header.trim()) ?: return null
            return m.groupValues[1].toLong() to m.groupValues[2].toLongOrNull()
        }
    }
}
