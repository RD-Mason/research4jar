package dev.springdep.indexer

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object Hashing {
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun sha256(path: Path): String =
        Files.newInputStream(path).use(::sha256)

    fun sha256(value: String): String =
        digest(value.toByteArray(StandardCharsets.UTF_8))

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX_DIGITS[value ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(chars)
    }
}
