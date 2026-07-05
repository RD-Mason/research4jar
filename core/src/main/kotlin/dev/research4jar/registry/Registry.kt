package dev.research4jar.registry

import dev.research4jar.indexer.store.AtomicFiles
import dev.research4jar.query.Db
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/**
 * The shard distribution layout, ported from querier/internal/registry
 * (registry.go): a static file tree any HTTP host (object storage, GitHub
 * Pages, an internal server) can serve, plus the client that downloads,
 * verifies, and installs shards into the local cache. Layout, all relative to
 * a base URL:
 *
 *     registry.json                       metadata: extractor version, shard count
 *     v<extractor>/<jar_sha256>.db        the shard, byte-identical to a local build
 *     v<extractor>/<jar_sha256>.db.sha256 hex digest of the shard file (required)
 *     v<extractor>/<jar_sha256>.db.sig    base64 ed25519 signature of the shard bytes
 *                                         (required when the client has a public key)
 *
 * Every research4jar-authored error and warning string stays byte-identical
 * to the Go implementation; messages that embed an underlying error keep the
 * Go wrapper text around a JVM-specific cause.
 */

/** Marks a registry miss (HTTP 404) as opposed to a failure (Go ErrShardNotFound). */
class ShardNotFoundException : IOException("shard not in registry")

/** Describes one installed shard (Go registry.FetchResult). */
data class FetchResult(
    val shardId: String,
    val shardPath: Path,
    val checksum: String,
    val sizeBytes: Long,
    val jarCoordinate: String?,
)

/**
 * Downloads shards from one registry (Go registry.Client / NewClient).
 * Parses an optional hex-encoded ed25519 public key; an empty [publicKeyHex]
 * disables signature verification.
 */
class RegistryClient(baseUrl: String, publicKeyHex: String = "") {
    val baseUrl: String = baseUrl.trimEnd('/')

    /** 32-byte ed25519 public key A; null disables signature verification. */
    val publicKey: ByteArray? = if (publicKeyHex.isEmpty()) {
        null
    } else {
        val key = hexToBytesOrNull(publicKeyHex.trim())
        require(key != null && key.size == ED25519_PUBLIC_KEY_SIZE) {
            "registry public key must be a hex-encoded 32-byte ed25519 key"
        }
        key
    }

    /**
     * Downloads the shard for a jar hash, verifies the sha256 sidecar (and
     * the ed25519 signature when a public key is configured), confirms the
     * shard embeds the expected jar hash, and atomically installs it into
     * [shardsDir]. Throws [ShardNotFoundException] on a registry miss and
     * [IOException] with Go-identical messages on verification failures.
     */
    fun fetch(jarSha256: String, extractorVersion: Int, shardsDir: Path): FetchResult {
        val shardName = "$jarSha256.db"
        val shardBytes = download(extractorVersion, shardName)

        val checksum = sha256Hex(shardBytes)

        val sidecar = try {
            download(extractorVersion, "$shardName.sha256")
        } catch (exception: ShardNotFoundException) {
            throw IOException(
                "registry has $shardName but no .sha256 sidecar; refusing unverifiable shard",
            )
        }
        val expected = String(sidecar, Charsets.UTF_8)
            .split(WHITESPACE)
            .filter(String::isNotEmpty)
        if (expected.isEmpty() || !expected[0].equals(checksum, ignoreCase = true)) {
            throw IOException(
                "checksum mismatch for $shardName: sidecar " +
                    "${goQuote(expected.joinToString(" "))}, downloaded $checksum",
            )
        }

        if (publicKey != null) {
            val signature = try {
                download(extractorVersion, "$shardName.sig")
            } catch (exception: ShardNotFoundException) {
                throw IOException(
                    "public key configured but registry has no signature for $shardName",
                )
            }
            val raw = try {
                Base64.getDecoder().decode(String(signature, Charsets.UTF_8).trim())
            } catch (exception: IllegalArgumentException) {
                throw IOException("malformed signature for $shardName: ${errorText(exception)}")
            }
            if (!ed25519Verify(publicKey, shardBytes, raw)) {
                throw IOException("signature verification failed for $shardName")
            }
        }

        val shardId = "$jarSha256@$extractorVersion"
        val destination = shardsDir.resolve("$shardId.db")
        Files.createDirectories(shardsDir)
        val temporary = Files.createTempFile(shardsDir, "$shardId.download-", "")
        try {
            Files.write(temporary, shardBytes)
            val coordinate = try {
                validateShardMeta(temporary, jarSha256, extractorVersion)
            } catch (exception: IOException) {
                throw IOException("downloaded shard $shardName: ${exception.message}")
            }
            // fsync + atomic rename, mirroring the Go temp+Sync+Rename install.
            AtomicFiles.commit(temporary, destination)
            return FetchResult(
                shardId = shardId,
                shardPath = destination,
                checksum = checksum,
                sizeBytes = shardBytes.size.toLong(),
                jarCoordinate = coordinate,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun download(extractorVersion: Int, name: String): ByteArray {
        val endpoint = "$baseUrl/v$extractorVersion/${pathEscape(name)}"
        val connection: HttpURLConnection
        val status: Int
        try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            status = connection.responseCode
        } catch (exception: IOException) {
            throw IOException("registry request $endpoint: ${errorText(exception)}")
        }
        try {
            when {
                status == 404 -> throw ShardNotFoundException()
                status != 200 -> throw IOException(
                    "registry returned ${statusText(connection, status)} for $endpoint",
                )
            }
            return try {
                connection.inputStream.use { it.readBytes() }
            } catch (exception: IOException) {
                throw IOException("registry read $endpoint: ${errorText(exception)}")
            }
        } finally {
            try {
                connection.errorStream?.close()
            } catch (_: IOException) {
                // Nothing to do; the connection is finished either way.
            }
        }
    }

    /** Mirrors Go http.Response.Status: "<code> <reason phrase>". */
    private fun statusText(connection: HttpURLConnection, status: Int): String {
        val message = try {
            connection.responseMessage
        } catch (_: IOException) {
            null
        }
        return if (message.isNullOrEmpty()) "$status" else "$status $message"
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Confirms the shard's embedded identity matches what was requested and
 * returns the embedded jar coordinate for the manifest row (Go
 * validateShardMeta). Opens the file read-only through SQLite.
 */
private fun validateShardMeta(path: Path, jarSha256: String, extractorVersion: Int): String? {
    val row = try {
        Db.openReadOnly(path.toString(), immutable = false).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT jar_sha256, extractor_version, jar_coordinate FROM shard_meta",
                ).use { result ->
                    if (!result.next()) {
                        null
                    } else {
                        Triple(result.getString(1), result.getInt(2), result.getString(3))
                    }
                }
            }
        }
    } catch (exception: Exception) {
        throw IOException("not a readable shard: ${errorText(exception)}")
    } ?: throw IOException("not a readable shard: sql: no rows in result set")

    val (embeddedSha, embeddedVersion, coordinate) = row
    if (embeddedSha != jarSha256) {
        throw IOException("embeds jar sha $embeddedSha, expected $jarSha256")
    }
    if (embeddedVersion != extractorVersion) {
        throw IOException("embeds extractor version $embeddedVersion, expected $extractorVersion")
    }
    return coordinate?.takeIf(String::isNotEmpty)
}

// --- shared helpers for the registry/cache port ---

internal const val ED25519_PUBLIC_KEY_SIZE = 32

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

internal fun bytesToHex(bytes: ByteArray): String {
    val chars = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = HEX_DIGITS[value ushr 4]
        chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(chars)
}

/** Strict hex decoding matching Go's hex.DecodeString: null on any malformation. */
internal fun hexToBytesOrNull(text: String): ByteArray? {
    if (text.length % 2 != 0) return null
    val bytes = ByteArray(text.length / 2)
    for (index in bytes.indices) {
        val high = Character.digit(text[index * 2], 16)
        val low = Character.digit(text[index * 2 + 1], 16)
        if (high < 0 || low < 0) return null
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return bytes
}

internal fun sha256Hex(bytes: ByteArray): String =
    bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Renders an exception the way Go's %v renders an error. */
internal fun errorText(exception: Throwable): String =
    exception.message ?: exception.toString()

/** Best-effort warning write; Go's fmt.Fprintf return value is ignored too. */
internal fun writeWarning(warnings: Appendable, line: String) {
    try {
        warnings.append(line)
    } catch (_: IOException) {
        // A broken warnings sink must not fail the operation.
    }
}

/** Go strconv.Quote for the strings that appear in error messages. */
internal fun goQuote(value: String): String {
    val quoted = StringBuilder("\"")
    for (character in value) {
        when (character) {
            '"' -> quoted.append("\\\"")
            '\\' -> quoted.append("\\\\")
            '\n' -> quoted.append("\\n")
            '\r' -> quoted.append("\\r")
            '\t' -> quoted.append("\\t")
            else -> if (character < ' ') {
                quoted.append(String.format("\\x%02x", character.code))
            } else {
                quoted.append(character)
            }
        }
    }
    return quoted.append('"').toString()
}

/**
 * Go url.PathEscape: escapes a string so it can appear in a URL path segment.
 * Unreserved characters and the sub-delims Go leaves alone stay literal;
 * everything else (including '/', ';', ',', '?') becomes %XX on UTF-8 bytes.
 */
internal fun pathEscape(segment: String): String {
    val bytes = segment.toByteArray(Charsets.UTF_8)
    val escaped = StringBuilder(bytes.size)
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        val character = value.toChar()
        val literal = character in 'a'..'z' || character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '-' || character == '_' || character == '.' || character == '~' ||
            character == '$' || character == '&' || character == '+' ||
            character == ':' || character == '=' || character == '@'
        if (literal) {
            escaped.append(character)
        } else {
            escaped.append('%')
            escaped.append(UPPER_HEX[value ushr 4])
            escaped.append(UPPER_HEX[value and 0x0f])
        }
    }
    return escaped.toString()
}

private val UPPER_HEX = "0123456789ABCDEF".toCharArray()
