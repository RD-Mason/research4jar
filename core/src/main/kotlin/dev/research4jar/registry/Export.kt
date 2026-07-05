package dev.research4jar.registry

import com.fasterxml.jackson.annotation.JsonProperty
import dev.research4jar.indexer.store.ManifestRow
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.util.Base64

/**
 * Registry export, ported from querier/internal/registry/export.go: copies
 * every current-version shard from the local cache into the static registry
 * layout, writing sha256 sidecars and, when a signing key is provided,
 * ed25519 signatures.
 */

/**
 * Written to registry.json at the export root so tooling can discover what a
 * registry serves without listing it (Go registry.Metadata).
 */
data class RegistryMetadata(
    @JsonProperty("extractor_version") val extractorVersion: Int,
    @JsonProperty("shard_count") val shardCount: Int,
    @JsonProperty("signed") val signed: Boolean,
    @JsonProperty("generated_by") val generatedBy: String,
)

/** Summarizes an export run (Go registry.ExportResult). */
data class ExportResult(
    @JsonProperty("output_dir") val outputDir: String,
    @JsonProperty("exported") val exported: Int,
    @JsonProperty("skipped") val skipped: Int,
    @JsonProperty("total_bytes") val totalBytes: Long,
    @JsonProperty("signed") val signed: Boolean,
)

/**
 * Copies every current-version shard into the static registry layout under
 * [outputDir] (kept verbatim in the result, as Go reports the argument it was
 * given), writing sha256 sidecars and, when [signingKey] is non-null, .sig
 * files. Shards whose files are missing or whose checksum no longer matches
 * the manifest are skipped with a warning on [warnings].
 */
fun export(
    shards: List<ManifestRow>,
    extractorVersion: Int,
    outputDir: String,
    signingKey: SigningKey?,
    generatedBy: String,
    warnings: Appendable,
): ExportResult {
    val outputPath = Paths.get(outputDir)
    val versionDir = outputPath.resolve("v$extractorVersion")
    Files.createDirectories(versionDir)

    val signed = signingKey != null
    var exported = 0
    var skipped = 0
    var totalBytes = 0L
    for (shard in shards) {
        if (shard.extractorVersion != extractorVersion) {
            skipped++
            continue
        }
        val shardBytes = try {
            Files.readAllBytes(Paths.get(shard.shardPath))
        } catch (exception: Exception) {
            writeWarning(
                warnings,
                "warning: ${shard.shardId}: ${readErrorText(shard.shardPath, exception)}; skipping\n",
            )
            skipped++
            continue
        }
        val checksum = sha256Hex(shardBytes)
        if (shard.shardChecksum != null && shard.shardChecksum != checksum) {
            writeWarning(
                warnings,
                "warning: ${shard.shardId}: on-disk shard does not match manifest checksum; skipping\n",
            )
            skipped++
            continue
        }
        val name = "${shard.jarSha256}.db"
        Files.write(versionDir.resolve(name), shardBytes)
        val sidecar = "$checksum  $name\n"
        Files.write(versionDir.resolve("$name.sha256"), sidecar.toByteArray(Charsets.UTF_8))
        if (signingKey != null) {
            val signature = Base64.getEncoder().encodeToString(signingKey.sign(shardBytes))
            Files.write(
                versionDir.resolve("$name.sig"),
                (signature + "\n").toByteArray(Charsets.UTF_8),
            )
        }
        exported++
        totalBytes += shardBytes.size.toLong()
    }

    val metadata = GoJson.marshalIndent(
        RegistryMetadata(
            extractorVersion = extractorVersion,
            shardCount = exported,
            signed = signed,
            generatedBy = generatedBy,
        ),
    )
    Files.write(
        outputPath.resolve("registry.json"),
        (metadata + "\n").toByteArray(Charsets.UTF_8),
    )
    return ExportResult(
        outputDir = outputDir,
        exported = exported,
        skipped = skipped,
        totalBytes = totalBytes,
        signed = signed,
    )
}

/** Mirrors Go's os.ReadFile error text for the failure modes that matter. */
private fun readErrorText(path: String, exception: Exception): String = when (exception) {
    is NoSuchFileException -> "open $path: no such file or directory"
    is AccessDeniedException -> "open $path: permission denied"
    else -> errorText(exception)
}
