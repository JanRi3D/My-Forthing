package to.axolotl.cam.recorder

import java.nio.ByteBuffer

/**
 * One control frame. [body] is plain JSON for the session start and Base64 ciphertext afterwards.
 * Equality compares the body content; [toString] never prints the body (it may hold session material).
 */
data class Frame(val seq: Int, val body: ByteArray) {
    override fun equals(other: Any?) = other is Frame && other.seq == seq && other.body.contentEquals(body)
    override fun hashCode() = 31 * seq + body.contentHashCode()
    override fun toString() = "Frame(seq=$seq, bodyLength=${body.size})"
}

/**
 * TCP framing as traced (DashcamApi / SessionApi): `"FAAB"` + u32 sequence (big-endian) + u32 body length
 * (big-endian) + body. After session setup the length counts the Base64 text, not the ciphertext or the JSON.
 */
object FrameCodec {
    const val HEADER_SIZE = 12
    private val MAGIC = byteArrayOf(0x46, 0x41, 0x41, 0x42) // "FAAB"

    // ponytail: sanity cap so a corrupt length field cannot stall the stream for long; real bodies stay below
    // (decrypted replies are capped at 16384 bytes by the vendor's native code, about 22 KiB as Base64).
    const val MAX_BODY = 64 * 1024

    fun encode(seq: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + body.size).put(MAGIC).putInt(seq).putInt(body.size).put(body).array()

    /**
     * Streaming decoder for one connection. A socket read may hold part of a frame, several frames, or bytes
     * that are not a frame at all: those are skipped up to the next `"FAAB"` and handed to [onGarbage].
     */
    class Decoder(private val onGarbage: (ByteArray) -> Unit = {}) {
        private var buf = ByteArray(4096)
        private var size = 0

        fun feed(bytes: ByteArray, off: Int = 0, len: Int = bytes.size): List<Frame> {
            if (size + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + len))
            System.arraycopy(bytes, off, buf, size, len)
            size += len
            val frames = ArrayList<Frame>(1)
            while (true) {
                val start = indexOfMagic()
                if (start < 0) {
                    skip(maxOf(0, size - (MAGIC.size - 1))) // keep a possible "FAA" prefix of the next marker
                    break
                }
                if (start > 0) skip(start)
                if (size < HEADER_SIZE) break
                val seq = readInt(4)
                val length = readInt(8)
                if (length < 0 || length > MAX_BODY) { // not a real header: resynchronise on the next marker
                    skip(1)
                    continue
                }
                if (size < HEADER_SIZE + length) break
                frames += Frame(seq, buf.copyOfRange(HEADER_SIZE, HEADER_SIZE + length))
                drop(HEADER_SIZE + length)
            }
            return frames
        }

        private fun indexOfMagic(): Int {
            outer@ for (i in 0..size - MAGIC.size) {
                for (j in MAGIC.indices) if (buf[i + j] != MAGIC[j]) continue@outer
                return i
            }
            return -1
        }

        private fun readInt(pos: Int) = ByteBuffer.wrap(buf, pos, 4).int

        private fun skip(n: Int) {
            if (n == 0) return
            onGarbage(buf.copyOf(n))
            drop(n)
        }

        private fun drop(n: Int) {
            System.arraycopy(buf, n, buf, 0, size - n)
            size -= n
        }
    }
}
