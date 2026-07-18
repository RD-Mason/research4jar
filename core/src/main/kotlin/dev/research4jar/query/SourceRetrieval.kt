package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile

/**
 * Dependency source retrieval: get-source reads one class (or one method) of a
 * dependency, search-source substring-searches one jar's sources. Acquisition
 * is local-first:
 *
 *   1. the owning jar and coordinate come from the existing index
 *      (session + manifest, the same machinery dep precise uses);
 *   2. `<artifact>-<version>-sources.jar` is looked up in the local Maven
 *      (`~/.m2/repository`, overridable via RESEARCH4JAR_M2_REPO) and Gradle
 *      (`$GRADLE_USER_HOME/caches/modules-2/files-2.1`) caches, plus next to
 *      the indexed class jar itself;
 *   3. `--fetch` (explicit opt-in, never default) downloads the sources jar
 *      through the project's own Maven (`mvn dependency:get`), so network
 *      access flows only through the user's build-tool configuration;
 *   4. otherwise the class is decompiled with CFR and cached under
 *      `<data home>/sources/decompiled/<jar_sha256>/<fqn>.java`.
 *
 * Responses always state provenance via `source_kind`
 * ("sources-jar" | "decompiled") so agents know the fidelity of what they
 * read. CFR and JavaParser classes load only inside these commands — never on
 * the query hot path.
 */

data class SourceSlice(
    @JsonProperty("signature") val signature: String,
    @JsonProperty("start_line") val startLine: Int,
    @JsonProperty("end_line") val endLine: Int,
    @JsonProperty("source") val source: String,
)

data class SourceResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("fqn") val fqn: String,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("method") val method: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("coordinate") val coordinate: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("jar_filename") val jarFilename: String = "",
    @JsonProperty("source_kind") val sourceKind: String,
    /** Sources jar path, or the cached decompiled file for "decompiled". */
    @JsonProperty("source_path") val sourcePath: String,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("source_entry") val sourceEntry: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("class_jar_path") val classJarPath: String = "",
    @JsonProperty("language") val language: String,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("note") val note: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("slices") val slices: List<SourceSlice> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("source") val source: String = "",
    @JsonProperty("coverage") val coverage: Coverage,
)

data class SourceSearchHit(
    @JsonProperty("file") val file: String,
    @JsonProperty("line") val line: Int,
    @JsonProperty("text") val text: String,
)

data class SourceSearchResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("in") val inTarget: String,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("coordinate") val coordinate: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("jar_filename") val jarFilename: String = "",
    @JsonProperty("source_kind") val sourceKind: String,
    @JsonProperty("source_path") val sourcePath: String,
    @JsonProperty("results") val results: List<SourceSearchHit>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("has_more") val hasMore: Boolean,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

/** One resolved owning jar: index facts plus what could be found on disk. */
internal data class ResolvedJar(
    val fqn: String,
    val shardId: String,
    val coordinate: String,
    val jarFilename: String,
    val jarSha256: String,
    val classJarPath: Path?,
)

private class ObtainedSource(
    val kind: String,
    val path: Path,
    val entry: String,
    val language: String,
    val note: String,
    val text: String,
)

fun getSource(
    pointer: ProjectPointerData,
    manifestPath: String,
    projectDir: String,
    home: String,
    arg: String,
    fetch: Boolean,
): SourceResponse {
    val trimmed = arg.trim()
    val hash = trimmed.indexOf('#')
    val classPart = (if (hash >= 0) trimmed.substring(0, hash) else trimmed).trim()
    val methodPart = (if (hash >= 0) trimmed.substring(hash + 1) else "").trim()
    require(classPart.isNotEmpty()) { "get-source requires a class FQN or Class#method" }

    val resolved = resolveOwningClass(pointer, manifestPath, classPart)
        ?: throw IllegalArgumentException(
            "class not found in the index: $classPart " +
                "(try research4jar find-class ${splitFqn(classPart).second} to locate it)",
        )
    val outerFqn = resolved.fqn.substringBefore('$')
    val sourcesDir = sourcesCacheDir(manifestPath, home)
    val obtained = obtainSource(resolved, outerFqn, sourcesDir, projectDir, fetch)

    var note = obtained.note
    var slices: List<SourceSlice> = emptyList()
    var wholeFile = obtained.text
    if (methodPart.isNotEmpty()) {
        if (obtained.language == "java") {
            val sliced = SourceSlicer.slice(obtained.text, resolved.fqn, methodPart)
            if (sliced.slices.isNotEmpty()) {
                slices = sliced.slices
                wholeFile = ""
            }
            note = joinNotes(note, sliced.note)
        } else {
            note = joinNotes(
                note,
                "method slicing supports Java sources only; returning the whole ${obtained.language} file",
            )
        }
    }

    return SourceResponse(
        query = SymbolRequest(command = "get-source", arg = arg),
        fqn = resolved.fqn,
        method = methodPart,
        coordinate = resolved.coordinate,
        jarFilename = resolved.jarFilename,
        sourceKind = obtained.kind,
        sourcePath = obtained.path.toString(),
        sourceEntry = obtained.entry,
        classJarPath = if (obtained.kind == "decompiled") {
            resolved.classJarPath?.toString() ?: ""
        } else {
            ""
        },
        language = obtained.language,
        note = note,
        slices = slices,
        source = wholeFile,
        coverage = coverageFrom(pointer),
    )
}

fun searchSource(
    pointer: ProjectPointerData,
    manifestPath: String,
    projectDir: String,
    home: String,
    text: String,
    inTarget: String,
    fetch: Boolean,
    page: Int,
    pageSize: Int,
): SourceSearchResponse {
    val window = pageWindow(page, pageSize)
    require(text.isNotEmpty()) { "search-source requires a non-empty search text" }
    require(inTarget.isNotEmpty()) {
        "search-source requires --in <coordinate|jar-filename|class-fqn> to pick one jar"
    }

    val resolved = resolveSearchTarget(pointer, manifestPath, inTarget.trim())
    val sourcesDir = sourcesCacheDir(manifestPath, home)
    val sourcesJar = locateSourcesJar(resolved, sourcesDir, projectDir, fetch)

    val scan = SourceScan(text, window.offset, window.limit)
    val sourceKind: String
    val sourcePath: Path
    if (sourcesJar != null) {
        sourceKind = "sources-jar"
        sourcePath = sourcesJar
        scanSourcesJar(sourcesJar, scan)
    } else {
        sourceKind = "decompiled"
        sourcePath = ensureJarDecompiled(resolved, sourcesDir)
        scanDecompiledDirectory(sourcePath, scan)
    }

    return SourceSearchResponse(
        query = SymbolRequest(command = "search-source", arg = text),
        inTarget = inTarget,
        coordinate = resolved.coordinate,
        jarFilename = resolved.jarFilename,
        sourceKind = sourceKind,
        sourcePath = sourcePath.toString(),
        results = scan.hits,
        total = scan.hits.size,
        hasMore = scan.hasMore,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}

// ------------------------------------------------------- index resolution

/**
 * Progressive `.` to `$` rewrites from the right, so both `a.b.Outer$Inner`
 * and the source-style `a.b.Outer.Inner` resolve to the indexed binary name.
 * Wrong intermediate candidates simply never match a `classes.fqn` row.
 */
internal fun binaryNameCandidates(name: String): List<String> {
    val candidates = mutableListOf(name)
    var current = name
    for (depth in 0 until 4) {
        val index = current.lastIndexOf('.')
        if (index <= 0) break
        current = current.substring(0, index) + "$" + current.substring(index + 1)
        candidates += current
    }
    return candidates
}

internal fun resolveOwningClass(
    pointer: ProjectPointerData,
    manifestPath: String,
    classArg: String,
): ResolvedJar? = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    data class Row(val fqn: String, val shardKey: String)

    val candidates = binaryNameCandidates(classArg)
    val placeholders = List(candidates.size) { "?" }.joinToString(",")
    var row = session.query(
        """
        SELECT fqn, source_shard_id FROM classes
        WHERE fqn IN ($placeholders)
        ORDER BY length(fqn), fqn, source_shard_id
        LIMIT 1
        """.trimIndent(),
        candidates,
    ) { rows -> rows.mapRows { Row(it.getString(1), it.getString(2)) } }.firstOrNull()
    if (row == null && !classArg.contains('.')) {
        row = session.query(
            """
            SELECT fqn, source_shard_id FROM classes
            WHERE simple_name = ?
            ORDER BY fqn, source_shard_id
            LIMIT 1
            """.trimIndent(),
            listOf(classArg),
        ) { rows -> rows.mapRows { Row(it.getString(1), it.getString(2)) } }.firstOrNull()
    }
    if (row == null) return null

    val manifestRows = ManifestCache.loadRows(manifestPath)
    val source = ManifestCache.mapSessionRows(session, manifestRows, listOf(row.shardKey))[row.shardKey]
    resolveJarOnDisk(
        manifestPath,
        fqn = row.fqn,
        shardId = source?.shardId ?: row.shardKey,
        coordinate = source?.coordinate ?: "",
        jarFilename = source?.filename ?: "",
    )
}

/**
 * The home's manifest spans every project (and can hold several versions of
 * one artifact); --in must bind within THIS session's jars, so artifact
 * matching is restricted to the session's shard set when it is available.
 */
private fun sessionShardIds(pointer: ProjectPointerData): Set<String>? =
    try {
        Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
            session.query("SELECT shard_id FROM session_shards", emptyList()) { rows ->
                rows.mapRows { it.getString(1) }
            }.toSet()
        }
    } catch (_: java.sql.SQLException) {
        null // pre-v7/hand-written sessions: fall back to the whole manifest.
    }

private fun resolveSearchTarget(
    pointer: ProjectPointerData,
    manifestPath: String,
    target: String,
): ResolvedJar {
    val looksLikeClass = !target.contains(':') && !target.endsWith(".jar") && target.contains('.')
    if (looksLikeClass) {
        resolveOwningClass(pointer, manifestPath, target)?.let { return it }
    }
    val shardIds = sessionShardIds(pointer)
    val matches = ManifestCache.loadRows(manifestPath)
        .filter { shardIds == null || it.shardId in shardIds }
        .filter { sourceMatchesArtifact(it, target) }
        .distinctBy { it.shardId }
    when {
        matches.size == 1 -> {
            val match = matches.single()
            return resolveJarOnDisk(
                manifestPath,
                fqn = "",
                shardId = match.shardId,
                coordinate = match.coordinate,
                jarFilename = match.filename,
            )
        }

        matches.size > 1 -> throw IllegalArgumentException(
            "--in matches ${matches.size} jars: " +
                matches.take(5).joinToString(", ") { it.source.ifEmpty { it.filename } } +
                (if (matches.size > 5) ", ..." else "") +
                "; use a full coordinate or jar filename",
        )
    }
    if (!looksLikeClass) {
        resolveOwningClass(pointer, manifestPath, target)?.let { return it }
    }
    throw IllegalArgumentException(
        "--in matches no indexed jar or class: $target " +
            "(try research4jar artifact or research4jar find-class first)",
    )
}

/** Adds jar_sha256 and the local class jar location to the index facts. */
private fun resolveJarOnDisk(
    manifestPath: String,
    fqn: String,
    shardId: String,
    coordinate: String,
    jarFilename: String,
): ResolvedJar {
    var jarSha256 = ""
    val digestPaths = mutableListOf<String>()
    if (manifestPath.isNotEmpty() && Files.isRegularFile(Paths.get(manifestPath))) {
        Db.openReadOnly(manifestPath, immutable = false).use { manifest ->
            jarSha256 = manifest.query(
                "SELECT jar_sha256 FROM shards WHERE shard_id = ?",
                listOf(shardId),
            ) { rows -> rows.mapRows { it.getString(1) } }.firstOrNull() ?: ""
            if (jarSha256.isNotEmpty()) {
                // The digest cache records where classpath jars were last seen;
                // reversing it turns a content hash back into a local jar path.
                digestPaths += manifest.query(
                    "SELECT abs_path FROM jar_digest_cache WHERE sha256 = ? ORDER BY abs_path",
                    listOf(jarSha256),
                ) { rows -> rows.mapRows { it.getString(1) } }
            }
        }
    }
    var classJar = digestPaths.asSequence()
        .map { Paths.get(it) }
        .firstOrNull { Files.isRegularFile(it) }
    if (classJar == null && jarFilename.isNotEmpty()) {
        classJar = probeCacheRoots(coordinate, jarFilename)
    }
    return ResolvedJar(
        fqn = fqn,
        shardId = shardId,
        coordinate = coordinate,
        jarFilename = jarFilename,
        jarSha256 = jarSha256,
        classJarPath = classJar,
    )
}

// ---------------------------------------------------- sources jar location

/** `<data home>/sources`: located-jar path records plus decompiled output. */
internal fun sourcesCacheDir(manifestPath: String, home: String): Path =
    if (manifestPath.isNotEmpty()) {
        Paths.get(manifestPath).toAbsolutePath().normalize().parent.resolve("sources")
    } else {
        dev.research4jar.indexer.Research4JarPaths.resolve(home.ifEmpty { null })
            .home.resolve("sources")
    }

private fun mavenRepoRoot(): Path {
    val explicit = System.getenv("RESEARCH4JAR_M2_REPO")
    if (!explicit.isNullOrBlank()) return Paths.get(explicit)
    return Paths.get(System.getProperty("user.home"), ".m2", "repository")
}

private fun gradleCacheRoot(): Path {
    val gradleHome = System.getenv("GRADLE_USER_HOME")
    val base = if (gradleHome.isNullOrBlank()) {
        Paths.get(System.getProperty("user.home"), ".gradle")
    } else {
        Paths.get(gradleHome)
    }
    return base.resolve("caches").resolve("modules-2").resolve("files-2.1")
}

/** g:a:v triple, or null when the coordinate is missing or partial. */
private fun coordinateParts(coordinate: String): Triple<String, String, String>? {
    val parts = coordinate.split(":")
    if (parts.size < 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) return null
    return Triple(parts[0], parts[1], parts[2])
}

/** Finds [filename] under the two standard cache layouts for [coordinate]. */
private fun probeCacheRoots(coordinate: String, filename: String): Path? {
    val (group, artifact, version) = coordinateParts(coordinate) ?: return null
    val m2 = mavenRepoRoot()
        .resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(filename)
    if (Files.isRegularFile(m2)) return m2
    val versionDir = gradleCacheRoot().resolve(group).resolve(artifact).resolve(version)
    return findInHashDirs(versionDir, filename)
}

/** Gradle interposes a content-hash directory between version and file. */
private fun findInHashDirs(versionDir: Path, filename: String): Path? {
    if (!Files.isDirectory(versionDir)) return null
    return try {
        Files.newDirectoryStream(versionDir).use { children ->
            children.asSequence()
                .filter { Files.isDirectory(it) }
                .sortedBy { it.fileName.toString() }
                .map { it.resolve(filename) }
                .firstOrNull { Files.isRegularFile(it) }
        }
    } catch (_: java.io.IOException) {
        null
    }
}

/**
 * The local-first lookup ladder for `<artifact>-<version>-sources.jar`:
 * cached path record, siblings of the indexed class jar (which covers custom
 * repository locations automatically), then the two standard cache roots.
 * With [fetch], a miss triggers one `mvn dependency:get` download through the
 * user's own Maven configuration and the ladder retries.
 */
internal fun locateSourcesJar(
    resolved: ResolvedJar,
    sourcesDir: Path,
    projectDir: String,
    fetch: Boolean,
): Path? {
    readSourcesJarRecord(sourcesDir, resolved.jarSha256)?.let { return it }
    locateSourcesJarLocally(resolved)?.let {
        writeSourcesJarRecord(sourcesDir, resolved.jarSha256, it)
        return it
    }
    if (!fetch) return null
    fetchSourcesJar(projectDir, resolved.coordinate)
    val fetched = locateSourcesJarLocally(resolved) ?: return null
    writeSourcesJarRecord(sourcesDir, resolved.jarSha256, fetched)
    return fetched
}

private fun locateSourcesJarLocally(resolved: ResolvedJar): Path? {
    val sourcesNames = mutableListOf<String>()
    if (resolved.jarFilename.isNotEmpty()) {
        sourcesNames += filepathBase(resolved.jarFilename).removeSuffix(".jar") + "-sources.jar"
    }
    coordinateParts(resolved.coordinate)?.let { (_, artifact, version) ->
        sourcesNames += "$artifact-$version-sources.jar"
    }
    val names = sourcesNames.distinct()

    val classJar = resolved.classJarPath
    if (classJar != null) {
        val directory = classJar.parent
        for (name in names) {
            val sibling = directory?.resolve(name)
            if (sibling != null && Files.isRegularFile(sibling)) return sibling
        }
        // Gradle keeps each artifact in its own hash directory; sources live
        // in a sibling hash directory under the same version.
        val versionDir = directory?.parent
        if (versionDir != null) {
            for (name in names) {
                findInHashDirs(versionDir, name)?.let { return it }
            }
        }
    }
    for (name in names) {
        probeCacheRoots(resolved.coordinate, name)?.let { return it }
    }
    return null
}

private fun sourcesJarRecordPath(sourcesDir: Path, jarSha256: String): Path =
    sourcesDir.resolve("locations").resolve("$jarSha256.path")

private fun readSourcesJarRecord(sourcesDir: Path, jarSha256: String): Path? {
    if (jarSha256.isEmpty()) return null
    return try {
        val record = sourcesJarRecordPath(sourcesDir, jarSha256)
        if (!Files.isRegularFile(record)) return null
        val recorded = Paths.get(
            String(Files.readAllBytes(record), StandardCharsets.UTF_8).trim(),
        )
        if (Files.isRegularFile(recorded)) recorded else null
    } catch (_: Exception) {
        null
    }
}

private fun writeSourcesJarRecord(sourcesDir: Path, jarSha256: String, sourcesJar: Path) {
    if (jarSha256.isEmpty()) return
    try {
        val record = sourcesJarRecordPath(sourcesDir, jarSha256)
        Files.createDirectories(record.parent)
        Files.write(record, sourcesJar.toString().toByteArray(StandardCharsets.UTF_8))
    } catch (_: Exception) {
        // The record is an optimization; the sibling/cache-root ladder re-finds it.
    }
}

private fun fetchSourcesJar(projectDir: String, coordinate: String) {
    val (group, artifact, version) = coordinateParts(coordinate)
        ?: throw IllegalStateException(
            "--fetch needs a full group:artifact:version coordinate; the index has \"$coordinate\"",
        )
    val root = Paths.get(projectDir.ifEmpty { "." }).toAbsolutePath().normalize()
    val result = try {
        Classpath.runBuildCommand(
            root, "mvnw", "mvn",
            listOf(
                "-q",
                "dependency:get",
                "-Dartifact=$group:$artifact:$version:jar:sources",
                "-Dtransitive=false",
            ),
        )
    } catch (exception: java.io.IOException) {
        throw IllegalStateException(
            "--fetch needs ./mvnw or mvn on PATH to download sources: ${exception.message}",
        )
    }
    if (result.exitCode != 0) {
        throw IllegalStateException(
            "mvn dependency:get failed for $group:$artifact:$version:jar:sources " +
                "(exit ${result.exitCode})\n" + Classpath.tail(result.output),
        )
    }
}

// -------------------------------------------------------- source acquisition

private fun obtainSource(
    resolved: ResolvedJar,
    outerFqn: String,
    sourcesDir: Path,
    projectDir: String,
    fetch: Boolean,
): ObtainedSource {
    var note = ""
    val sourcesJar = locateSourcesJar(resolved, sourcesDir, projectDir, fetch)
    if (sourcesJar != null) {
        val entry = readSourcesJarEntry(sourcesJar, outerFqn)
        if (entry != null) {
            return ObtainedSource(
                kind = "sources-jar",
                path = sourcesJar,
                entry = entry.first,
                language = if (entry.first.endsWith(".kt")) "kotlin" else "java",
                note = "",
                text = entry.second,
            )
        }
        note = "sources jar ${sourcesJar.fileName} has no entry for $outerFqn; decompiling instead"
    }
    val decompiled = decompiledClassFile(resolved, outerFqn, sourcesDir)
    return ObtainedSource(
        kind = "decompiled",
        path = decompiled,
        entry = "",
        language = "java",
        note = note,
        text = String(Files.readAllBytes(decompiled), StandardCharsets.UTF_8),
    )
}

/** `a.b.Outer$Inner` lives in `a/b/Outer.java` (or `.kt`) inside a sources jar. */
private fun readSourcesJarEntry(sourcesJar: Path, outerFqn: String): Pair<String, String>? =
    ZipFile(sourcesJar.toFile()).use { zip ->
        val base = outerFqn.replace('.', '/')
        for (name in listOf("$base.java", "$base.kt")) {
            val entry = zip.getEntry(name) ?: continue
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            return@use name to String(bytes, StandardCharsets.UTF_8)
        }
        null
    }

private fun decompileCacheDir(sourcesDir: Path, jarSha256: String): Path =
    sourcesDir.resolve("decompiled").resolve(jarSha256)

/**
 * One cached decompiled file per outer class, keyed by the jar's content
 * hash: `sources/decompiled/<jar_sha256>/<outer fqn>.java`. A cache hit needs
 * neither the class jar nor CFR.
 */
private fun decompiledClassFile(resolved: ResolvedJar, outerFqn: String, sourcesDir: Path): Path {
    check(resolved.jarSha256.isNotEmpty()) {
        "no sources jar found and the manifest has no content hash for " +
            "${resolved.jarFilename.ifEmpty { resolved.shardId }}; cannot decompile"
    }
    val cached = decompileCacheDir(sourcesDir, resolved.jarSha256).resolve("$outerFqn.java")
    if (Files.isRegularFile(cached)) return cached
    val classJar = resolved.classJarPath ?: throw IllegalStateException(
        "no sources jar found and the class jar for " +
            "${resolved.jarFilename.ifEmpty { resolved.shardId }} is not in the local " +
            "Maven/Gradle caches; cannot decompile " +
            "(re-run research4jar index, or pass --fetch to download sources)",
    )
    val decompiled = SourceDecompiler.decompileClass(classJar, outerFqn)
        ?: throw IllegalStateException(
            "CFR produced no output for $outerFqn in $classJar",
        )
    Files.createDirectories(cached.parent)
    Files.write(cached, decompiled.toByteArray(StandardCharsets.UTF_8))
    return cached
}

/**
 * Decompiles every class of the jar once (progress on stderr, like the
 * indexer's lines), so search-source over a decompile-only jar becomes a
 * plain directory scan afterwards. `.complete` marks a finished run; a
 * partial directory from a killed process is redone.
 */
private fun ensureJarDecompiled(resolved: ResolvedJar, sourcesDir: Path): Path {
    check(resolved.jarSha256.isNotEmpty()) {
        "no sources jar found and the manifest has no content hash for " +
            "${resolved.jarFilename.ifEmpty { resolved.shardId }}; cannot decompile"
    }
    val directory = decompileCacheDir(sourcesDir, resolved.jarSha256)
    val marker = directory.resolve(".complete")
    if (Files.isRegularFile(marker)) return directory
    val classJar = resolved.classJarPath ?: throw IllegalStateException(
        "no sources jar found and the class jar for " +
            "${resolved.jarFilename.ifEmpty { resolved.shardId }} is not in the local " +
            "Maven/Gradle caches; cannot decompile " +
            "(re-run research4jar index, or pass --fetch to download sources)",
    )
    System.err.println(
        "research4jar: decompiling ${filepathBase(resolved.jarFilename.ifEmpty { classJar.toString() })} " +
            "(no local sources jar; one-time, cached)",
    )
    Files.createDirectories(directory)
    val written = SourceDecompiler.decompileJar(classJar) { fqn, java ->
        Files.write(
            directory.resolve("$fqn.java"),
            java.toByteArray(StandardCharsets.UTF_8),
        )
    }
    if (written == 0) {
        throw IllegalStateException("CFR produced no output for $classJar")
    }
    Files.write(marker, ByteArray(0))
    return directory
}

// ------------------------------------------------------------------ search

private const val MAX_SOURCE_FILE_BYTES = 5L * 1024 * 1024
private const val MAX_HIT_LINE_BYTES = 240

private val sourceFileExtensions = setOf(".java", ".kt", ".kts", ".groovy", ".scala")

private class SourceScan(val text: String, val offset: Long, val limit: Int) {
    val hits = mutableListOf<SourceSearchHit>()
    var skipped = 0L
    var hasMore = false

    /** Returns true once the page window plus the has_more probe is filled. */
    fun scanFile(file: String, content: String): Boolean {
        if (!content.contains(text)) return false
        for ((index, line) in content.split("\n").withIndex()) {
            if (!line.contains(text)) continue
            if (skipped < offset) {
                skipped++
                continue
            }
            if (hits.size >= limit) {
                hasMore = true
                return true
            }
            hits += SourceSearchHit(file = file, line = index + 1, text = trimHitLine(line))
        }
        return false
    }
}

// The same 240-byte cut dep precise applies to usage lines, so snippet size
// stays bounded regardless of minified or generated source lines.
private fun trimHitLine(line: String): String {
    val text = line.trim()
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= MAX_HIT_LINE_BYTES) return text
    return String(bytes, 0, MAX_HIT_LINE_BYTES, StandardCharsets.UTF_8)
}

private fun isSourceFileName(name: String): Boolean {
    val index = name.lastIndexOf('.')
    return index >= 0 && name.substring(index) in sourceFileExtensions
}

private fun scanSourcesJar(sourcesJar: Path, scan: SourceScan) {
    ZipFile(sourcesJar.toFile()).use { zip ->
        // Sorted names give deterministic pagination across invocations.
        val names = zip.entries().asSequence()
            .filter { !it.isDirectory && isSourceFileName(it.name) && it.size <= MAX_SOURCE_FILE_BYTES }
            .map { it.name }
            .sorted()
            .toList()
        for (name in names) {
            val entry = zip.getEntry(name) ?: continue
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            if (bytes.contains(0.toByte())) continue
            if (scan.scanFile(name, String(bytes, StandardCharsets.UTF_8))) return
        }
    }
}

private fun scanDecompiledDirectory(directory: Path, scan: SourceScan) {
    val files = try {
        Files.newDirectoryStream(directory).use { children ->
            children.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .sortedBy { it.fileName.toString() }
        }
    } catch (_: java.io.IOException) {
        emptyList<Path>()
    }
    for (file in files) {
        if (Files.size(file) > MAX_SOURCE_FILE_BYTES) continue
        val content = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        if (scan.scanFile(file.fileName.toString(), content)) return
    }
}

private fun joinNotes(first: String, second: String): String = when {
    first.isEmpty() -> second
    second.isEmpty() -> first
    else -> "$first; $second"
}
