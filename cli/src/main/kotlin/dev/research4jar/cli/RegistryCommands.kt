package dev.research4jar.cli

import dev.research4jar.indexer.Research4JarPaths
import dev.research4jar.indexer.Research4JarVersions
import dev.research4jar.indexer.store.Manifest
import dev.research4jar.registry.Coordinate
import dev.research4jar.registry.DEFAULT_MAVEN_REPO
import dev.research4jar.registry.KeygenResult
import dev.research4jar.registry.SeedResult
import dev.research4jar.registry.SigningKey
import dev.research4jar.registry.downloadJars
import dev.research4jar.registry.export
import dev.research4jar.registry.generateKey
import dev.research4jar.registry.loadSigningKey
import dev.research4jar.registry.parseCoordinates
import dev.research4jar.registry.seedIndex
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator

/**
 * The `registry` command family, ported from querier/cmd/research4jar/main.go
 * (runRegistryCommand + seedRegistry). Where Go's `registry seed` shells out
 * to the JVM indexer with a scratch project dir, this CLI indexes the
 * downloaded jars in-process via registry.seedIndex — the --indexer flag is
 * accepted but ignored, mirroring the `index` command's deliberate
 * difference.
 */
internal fun runRegistryCommand(args: Array<String>, io: CliIO) {
    if (helpRequested(args)) {
        printRegistryHelp(io.out)
        return
    }
    if (args.isEmpty() || (args[0] != "export" && args[0] != "keygen" && args[0] != "seed")) {
        fail(
            "invalid_arguments",
            "usage: research4jar registry export <DIR> [--sign-key PATH] [--home DIR] | " +
                "research4jar registry seed <DIR> --coordinates <FILE> [--repo URL] " +
                "[--sign-key PATH] [--home DIR] [--indexer PATH] | " +
                "research4jar registry keygen <PATH>",
            2,
        )
    }
    val subcommand = args[0]
    var target = ""
    var signKey = ""
    var home = ""
    var coordinatesPath = ""
    var repo = ""
    var indexerPath = ""
    var index = 1
    while (index < args.size) {
        when (val argument = args[index]) {
            "--sign-key", "--home", "--coordinates", "--repo", "--indexer" -> {
                val (value, next) = optionValue(args, index, argument)
                when (argument) {
                    "--sign-key" -> signKey = value
                    "--home" -> home = value
                    "--coordinates" -> coordinatesPath = value
                    "--repo" -> repo = value
                    "--indexer" -> indexerPath = value // accepted, ignored (in-process seed)
                }
                index = next
            }

            else -> {
                if (argument.startsWith("-")) {
                    fail("invalid_arguments", "unknown option: $argument", 2)
                }
                if (target.isNotEmpty()) {
                    fail("invalid_arguments", "$subcommand accepts exactly one argument", 2)
                }
                target = argument
            }
        }
        index++
    }
    if (target.isEmpty()) {
        fail("invalid_arguments", "$subcommand requires a target path argument", 2)
    }

    if (subcommand == "keygen") {
        val publicKey = try {
            generateKey(Paths.get(target))
        } catch (exception: Exception) {
            fail("registry_error", errMessage(exception), 1)
        }
        printJson(io.out, KeygenResult(privateKeyPath = target, publicKey = publicKey))
        return
    }

    if (subcommand == "seed") {
        if (coordinatesPath.isEmpty()) {
            fail("invalid_arguments", "seed requires --coordinates <FILE>", 2)
        }
        seedRegistry(target, coordinatesPath, repo, signKey, home, indexerPath, io)
        return
    }

    val dataPaths = try {
        Research4JarPaths.resolve(home)
    } catch (exception: Exception) {
        fail("registry_error", errMessage(exception), 1)
    }
    val shards = try {
        Manifest(dataPaths.manifest).list()
    } catch (exception: Exception) {
        fail("registry_error", errMessage(exception), 1)
    }
    val signingKey = loadSigningKeyOrFail(signKey)
    val result = try {
        export(
            shards, Research4JarVersions.EXTRACTOR, target, signingKey,
            "research4jar $CLI_VERSION", io.err,
        )
    } catch (exception: Exception) {
        fail("registry_error", errMessage(exception), 1)
    }
    printJson(io.out, result)
}

/**
 * Downloads jars for a coordinates list, indexes them into the (usually
 * fresh) home, and exports the resulting shards as a registry tree. This is
 * how the official public registry — and any enterprise's private one — is
 * produced in CI (Go seedRegistry).
 */
private fun seedRegistry(
    outputDir: String,
    coordinatesPath: String,
    repoArg: String,
    signKey: String,
    home: String,
    @Suppress("UNUSED_PARAMETER") indexerPath: String,
    io: CliIO,
) {
    val repo = repoArg.ifEmpty { DEFAULT_MAVEN_REPO }
    val coordinates: List<Coordinate> = run {
        val reader = try {
            Files.newBufferedReader(Paths.get(coordinatesPath), Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            // Mirror the Go os.Open error text for the common miss.
            fail("registry_error", "open $coordinatesPath: no such file or directory", 1)
        } catch (exception: IOException) {
            fail("registry_error", errMessage(exception), 1)
        }
        try {
            reader.use(::parseCoordinates)
        } catch (exception: Exception) {
            fail("invalid_arguments", "parse coordinates: ${errMessage(exception)}", 2)
        }
    }
    if (coordinates.isEmpty()) {
        fail("invalid_arguments", "coordinates file lists no artifacts", 2)
    }

    val jarDir = try {
        Files.createTempDirectory("research4jar-seed-jars-")
    } catch (exception: Exception) {
        fail("registry_error", errMessage(exception), 1)
    }
    try {
        io.err.println("research4jar: downloading ${coordinates.size} artifacts from $repo")
        val downloaded = downloadJars(repo, coordinates, jarDir, io.err)
        if (downloaded.jarPaths.isEmpty()) {
            fail("registry_error", "no artifacts could be downloaded", 1)
        }

        val dataPaths = try {
            Research4JarPaths.resolve(home)
        } catch (exception: Exception) {
            fail("registry_error", errMessage(exception), 1)
        }
        // In-process indexing replaces Go's indexer.Locate + indexer.Run into
        // a scratch project directory; only the throwaway project pointer and
        // CLAUDE.md are skipped. Failures keep Go's index_error shape.
        try {
            seedIndex(downloaded.jarPaths, dataPaths, io.err)
        } catch (failure: CliFailure) {
            throw failure
        } catch (exception: Exception) {
            fail("index_error", errMessage(exception) + "\n\n" + doctorHint("", true), 1)
        }

        val shards = try {
            Manifest(dataPaths.manifest).list()
        } catch (exception: Exception) {
            fail("registry_error", errMessage(exception), 1)
        }
        val signingKey = loadSigningKeyOrFail(signKey)
        val result = try {
            export(
                shards, Research4JarVersions.EXTRACTOR, outputDir, signingKey,
                "research4jar $CLI_VERSION", io.err,
            )
        } catch (exception: Exception) {
            fail("registry_error", errMessage(exception), 1)
        }
        printJson(
            io.out,
            SeedResult(
                outputDir = result.outputDir,
                exported = result.exported,
                skipped = result.skipped,
                totalBytes = result.totalBytes,
                signed = result.signed,
                coordinates = coordinates.size,
                downloadFailed = downloaded.failures,
                jarsDownloaded = downloaded.jarPaths.size,
            ),
        )
    } finally {
        deleteRecursively(jarDir)
    }
}

private fun loadSigningKeyOrFail(signKey: String): SigningKey? {
    if (signKey.isEmpty()) return null
    return try {
        loadSigningKey(Paths.get(signKey))
    } catch (exception: Exception) {
        fail("registry_error", errMessage(exception), 1)
    }
}

/** Go defer os.RemoveAll: best-effort recursive removal of the temp jar dir. */
private fun deleteRecursively(root: Path) {
    try {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: Exception) {
                    // Best effort, like Go's ignored RemoveAll error.
                }
            }
        }
    } catch (_: Exception) {
        // Best effort.
    }
}
