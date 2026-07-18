package dev.research4jar.query

import dev.research4jar.indexer.Hashing
import dev.research4jar.indexer.extract.ExtractedClass
import dev.research4jar.indexer.extract.ExtractedJar
import dev.research4jar.indexer.store.CachedDigest
import dev.research4jar.indexer.store.Manifest
import dev.research4jar.indexer.store.SessionBuilder
import dev.research4jar.indexer.store.SessionShard
import dev.research4jar.indexer.store.ShardWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end acquisition-ladder coverage on real artifacts built at test
 * time: javac-compiled classes packed into jars, a matching sources jar next
 * to one of them, and no sources at all for the other — so both the
 * sources-jar branch and the CFR decompile branch run against genuine files.
 */
class SourceRetrievalTest {
    private lateinit var root: Path
    private lateinit var repo: Path
    private lateinit var manifestPath: Path
    private lateinit var pointer: ProjectPointerData
    private lateinit var withSourcesJar: Path
    private lateinit var decompileOnlyJar: Path
    private lateinit var mixedLanguageJar: Path

    private val widgetScala = """
        package com.example.sc

        class Widget {
          def render(): String = "scala-widget"
        }
    """.trimIndent()

    private val helpersKotlin = """
        package com.example.kt

        fun helperValue(): Int = 42
    """.trimIndent()

    private fun dupSource(tag: String) = """
        package com.example.dup;

        public class Thing {
            public String tag() {
                return "$tag";
            }
        }
    """.trimIndent()

    private val utilSource = """
        package com.example.fixture;

        /** Fixture class shipped in the sources jar. */
        public class Util {
            public static final String MARKER = "sources-jar-marker";

            /** No-arg overload. */
            public int compute() {
                return 41;
            }

            public int compute(int seed) {
                return seed + MARKER.length();
            }

            public static class Inner {
                public String innerCall(String input) {
                    return "inner:" + input;
                }
            }
        }
    """.trimIndent()

    private val decompSource = """
        package com.example.decomp;

        public class Decomp {
            public String greet(String name) {
                return "hello " + name;
            }

            public String greet(String name, int times) {
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < times; i++) {
                    builder.append(name);
                }
                return builder.toString();
            }

            public static class Nested {
                public int nestedValue() {
                    return 7;
                }
            }
        }
    """.trimIndent()

    @BeforeTest
    fun buildFixture() {
        root = Files.createTempDirectory("research4jar-source-retrieval")
        repo = Files.createDirectories(root.resolve("repo"))
        manifestPath = root.resolve("manifest.db")

        withSourcesJar = compileToJar(
            "fixture-1.0.jar",
            mapOf("com/example/fixture/Util.java" to utilSource),
        )
        writeSourcesJar(
            repo.resolve("fixture-1.0-sources.jar"),
            mapOf("com/example/fixture/Util.java" to utilSource),
        )
        decompileOnlyJar = compileToJar(
            "decomp-1.0.jar",
            mapOf("com/example/decomp/Decomp.java" to decompSource),
        )
        // Scala class and Kotlin file class: the class jar's bytecode is never
        // read on the sources-jar path, so a placeholder entry suffices.
        mixedLanguageJar = repo.resolve("mixed-1.0.jar")
        writeSourcesJar(
            mixedLanguageJar,
            mapOf("placeholder.txt" to "not bytecode"),
        )
        writeSourcesJar(
            repo.resolve("mixed-1.0-sources.jar"),
            mapOf(
                "com/example/sc/Widget.scala" to widgetScala,
                "com/example/kt/Helpers.kt" to helpersKotlin,
            ),
        )

        // Two versions of one artifact shipping the same class: the
        // multi-version conflict fixture. package-info is registered in both
        // to prove the conflict audit ignores it.
        val dupV1 = compileToJar(
            "dup-1.0.jar",
            mapOf("com/example/dup/Thing.java" to dupSource("v1")),
        )
        writeSourcesJar(
            repo.resolve("dup-1.0-sources.jar"),
            mapOf("com/example/dup/Thing.java" to dupSource("v1")),
        )
        val dupV2 = compileToJar(
            "dup-2.0.jar",
            mapOf("com/example/dup/Thing.java" to dupSource("v2")),
        )
        writeSourcesJar(
            repo.resolve("dup-2.0-sources.jar"),
            mapOf("com/example/dup/Thing.java" to dupSource("v2")),
        )

        val manifest = Manifest(manifestPath)
        val session = root.resolve("session.db")
        val shards = listOf(
            registerShard(manifest, withSourcesJar, "com.example:fixture:1.0", listOf(
                "com.example.fixture.Util", "com.example.fixture.Util\$Inner",
            )),
            registerShard(manifest, decompileOnlyJar, "com.example:decomp:1.0", listOf(
                "com.example.decomp.Decomp", "com.example.decomp.Decomp\$Nested",
            )),
            registerShard(manifest, mixedLanguageJar, "com.example:mixed:1.0", listOf(
                "com.example.sc.Widget", "com.example.kt.HelpersKt",
            )),
            registerShard(manifest, dupV1, "com.example:dup:1.0", listOf(
                "com.example.dup.Thing", "com.example.dup.package-info",
            )),
            registerShard(manifest, dupV2, "com.example:dup:2.0", listOf(
                "com.example.dup.Thing", "com.example.dup.package-info",
            )),
        )
        SessionBuilder().build(session, shards)
        pointer = ProjectPointerData(
            schemaVersion = 2,
            extractorVersion = 2,
            classpathFingerprint = "test",
            sessionDbPath = session.toString(),
        )
    }

    private fun registerShard(
        manifest: Manifest,
        jar: Path,
        coordinate: String,
        fqns: List<String>,
    ): SessionShard {
        val sha = Hashing.sha256(jar)
        val shardId = "$sha@2"
        val shardPath = root.resolve("${coordinate.split(":")[1]}.shard.db")
        ShardWriter().write(
            shardPath,
            shardId,
            ExtractedJar(
                coordinate = coordinate,
                classes = fqns.map { fqn ->
                    ExtractedClass(
                        fqn = fqn,
                        kind = "class",
                        superFqn = null,
                        modifiers = 1,
                        isAbstract = false,
                        sourceFile = null,
                        interfaces = emptyList(),
                    )
                },
            ),
        )
        manifest.register(
            shardId = shardId,
            coordinate = coordinate,
            jarFilename = jar.fileName.toString(),
            jarSha256 = sha,
            shardPath = shardPath,
            shardChecksum = "checksum-$shardId",
            sizeBytes = Files.size(shardPath),
        )
        manifest.putJarDigests(
            mapOf(
                jar.toAbsolutePath().toString() to CachedDigest(
                    sizeBytes = Files.size(jar),
                    mtimeMillis = Files.getLastModifiedTime(jar).toMillis(),
                    sha256 = sha,
                ),
            ),
        )
        return SessionShard(shardId, shardPath)
    }

    private fun compileToJar(jarName: String, sources: Map<String, String>): Path {
        val sourceDir = Files.createDirectories(root.resolve("$jarName-src"))
        val classesDir = Files.createDirectories(root.resolve("$jarName-classes"))
        val files = sources.map { (path, text) ->
            val file = sourceDir.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, text.toByteArray(Charsets.UTF_8))
            file.toString()
        }
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "javac unavailable" }
        val exit = compiler.run(null, null, null, "-d", classesDir.toString(), *files.toTypedArray())
        assertEquals(0, exit, "javac failed")
        val jar = repo.resolve(jarName)
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classesDir).use { paths ->
                paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    // Zip entry names are '/'-separated by spec; Path.relativize
                    // yields backslashes on Windows and CFR would see no classes.
                    output.putNextEntry(JarEntry(classesDir.relativize(file).toString().replace('\\', '/')))
                    output.write(Files.readAllBytes(file))
                    output.closeEntry()
                }
            }
        }
        return jar
    }

    private fun writeSourcesJar(target: Path, entries: Map<String, String>) {
        JarOutputStream(Files.newOutputStream(target)).use { output ->
            for ((name, text) in entries) {
                output.putNextEntry(JarEntry(name))
                output.write(text.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
    }

    private fun getSource(arg: String, inTarget: String = "") =
        getSource(pointer, manifestPath.toString(), root.toString(), "", arg, false, inTarget)

    private fun searchSource(text: String, target: String, page: Int = 1, pageSize: Int = 20) =
        searchSource(
            pointer, manifestPath.toString(), root.toString(), "",
            text, target, false, page, pageSize,
        )

    @Test
    fun `sources jar hit returns the original source with provenance`() {
        val response = getSource("com.example.fixture.Util")
        assertEquals("sources-jar", response.sourceKind)
        assertEquals("com.example.fixture.Util", response.fqn)
        assertEquals("com.example:fixture:1.0", response.coordinate)
        assertEquals("com/example/fixture/Util.java", response.sourceEntry)
        assertTrue(response.sourcePath.endsWith("fixture-1.0-sources.jar"))
        assertTrue(response.source.contains("sources-jar-marker"))
        assertEquals("java", response.language)
    }

    @Test
    fun `inner classes map to the outer source file in both spellings`() {
        for (spelling in listOf("com.example.fixture.Util\$Inner", "com.example.fixture.Util.Inner")) {
            val response = getSource(spelling)
            assertEquals("com.example.fixture.Util\$Inner", response.fqn, spelling)
            assertEquals("com/example/fixture/Util.java", response.sourceEntry, spelling)
            assertTrue(response.source.contains("innerCall"), spelling)
        }
        val sliced = getSource("com.example.fixture.Util\$Inner#innerCall")
        assertEquals(1, sliced.slices.size)
        assertTrue(sliced.slices.single().source.contains("\"inner:\""))
    }

    @Test
    fun `method slicing from the sources jar returns every overload`() {
        val response = getSource("com.example.fixture.Util#compute")
        assertEquals("sources-jar", response.sourceKind)
        assertEquals("", response.source)
        assertEquals(2, response.slices.size)
        assertTrue(response.slices[0].source.contains("return 41"))
        assertTrue(response.slices[1].source.contains("MARKER.length()"))
        assertTrue(response.slices.all { it.startLine in 1..it.endLine })
    }

    @Test
    fun `sources jar miss decompiles with cfr and caches by content hash`() {
        val response = getSource("com.example.decomp.Decomp")
        assertEquals("decompiled", response.sourceKind)
        assertTrue(response.source.contains("greet"), response.source.take(400))
        assertTrue(response.classJarPath.endsWith("decomp-1.0.jar"))

        val cached = root.resolve("sources").resolve("decompiled")
            .resolve(Hashing.sha256(decompileOnlyJar)).resolve("com.example.decomp.Decomp.java")
        assertTrue(Files.isRegularFile(cached), "expected decompile cache at $cached")
        assertEquals(cached.toString(), response.sourcePath)

        // A cache hit must not need the class jar (or CFR) at all.
        val hidden = decompileOnlyJar.resolveSibling("decomp-1.0.jar.hidden")
        Files.move(decompileOnlyJar, hidden)
        try {
            val warm = getSource("com.example.decomp.Decomp#greet")
            assertEquals("decompiled", warm.sourceKind)
            assertEquals(2, warm.slices.size)
            assertTrue(warm.slices.any { it.source.contains("StringBuilder") })
        } finally {
            Files.move(hidden, decompileOnlyJar)
        }
    }

    @Test
    fun `decompiled inner classes slice through the outer class file`() {
        val response = getSource("com.example.decomp.Decomp\$Nested#nestedValue")
        assertEquals("decompiled", response.sourceKind)
        assertEquals(1, response.slices.size)
        assertTrue(response.slices.single().source.contains("7"))
    }

    @Test
    fun `unknown method falls back to the whole file with a note`() {
        val response = getSource("com.example.fixture.Util#doesNotExist")
        assertEquals(0, response.slices.size)
        assertTrue(response.source.contains("sources-jar-marker"))
        assertTrue(response.note.contains("doesNotExist"), response.note)
    }

    @Test
    fun `unindexed class fails with a pointer to find-class`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            getSource("com.example.missing.Nope")
        }
        assertTrue(failure.message!!.contains("find-class"), failure.message)
    }

    @Test
    fun `search-source scans the sources jar with pagination`() {
        val all = searchSource("compute", "com.example:fixture:1.0")
        assertEquals("sources-jar", all.sourceKind)
        assertTrue(all.total >= 2, "expected multiple hits, got ${all.total}")
        assertTrue(all.results.all { it.file == "com/example/fixture/Util.java" })
        assertTrue(all.results.all { it.line >= 1 })
        assertTrue(!all.hasMore)

        val paged = searchSource("compute", "fixture-1.0.jar", page = 1, pageSize = 1)
        assertEquals(1, paged.total)
        assertTrue(paged.hasMore)
        val second = searchSource("compute", "fixture", page = 2, pageSize = 1)
        assertEquals(1, second.total)
        assertTrue(second.results.single().line != paged.results.single().line)
    }

    @Test
    fun `search-source over a decompile-only jar decompiles the jar once`() {
        val response = searchSource("greet", "com.example.decomp.Decomp")
        assertEquals("decompiled", response.sourceKind)
        assertTrue(response.total >= 1)
        assertTrue(response.results.all { it.file.endsWith(".java") })

        val marker = root.resolve("sources").resolve("decompiled")
            .resolve(Hashing.sha256(decompileOnlyJar)).resolve(".complete")
        assertTrue(Files.isRegularFile(marker), "whole-jar decompile must leave $marker")

        // The cached tree also serves get-source without re-decompilation.
        val cachedClass = getSource("com.example.decomp.Decomp")
        assertEquals("decompiled", cachedClass.sourceKind)
    }

    @Test
    fun `search-source rejects ambiguous and unknown targets`() {
        // "1.0" substring-matches every -1.0 fixture jar's filename stem.
        val ambiguous = assertFailsWith<IllegalArgumentException> {
            searchSource("x", "1.0")
        }
        assertTrue(ambiguous.message!!.contains("matches 4 jars"), ambiguous.message)

        val unknown = assertFailsWith<IllegalArgumentException> {
            searchSource("x", "no.such:artifact:9")
        }
        assertTrue(unknown.message!!.contains("no indexed jar"), unknown.message)
    }

    @Test
    fun `scala sources are served from the sources jar instead of decompiling`() {
        val response = getSource("com.example.sc.Widget")
        assertEquals("sources-jar", response.sourceKind)
        assertEquals("scala", response.language)
        assertEquals("com/example/sc/Widget.scala", response.sourceEntry)
        assertTrue(response.source.contains("scala-widget"))

        // Method slicing is Java-only; the whole file plus a note comes back.
        val sliced = getSource("com.example.sc.Widget#render")
        assertEquals(0, sliced.slices.size)
        assertTrue(sliced.source.contains("scala-widget"))
        assertTrue(sliced.note.contains("scala"), sliced.note)
    }

    @Test
    fun `kotlin file classes map FooKt to the Foo kt source entry`() {
        val response = getSource("com.example.kt.HelpersKt")
        assertEquals("sources-jar", response.sourceKind)
        assertEquals("kotlin", response.language)
        assertEquals("com/example/kt/Helpers.kt", response.sourceEntry)
        assertTrue(response.source.contains("helperValue"))
    }

    @Test
    fun `search-source notes skipped oversize and binary files`() {
        // Rewrite the fixture sources jar with an oversize file and a
        // NUL-carrying file next to the real source.
        writeSourcesJar(
            repo.resolve("fixture-1.0-sources.jar"),
            mapOf(
                "com/example/fixture/Util.java" to utilSource,
                "com/example/fixture/Huge.java" to buildString {
                    append("// compute\n")
                    val filler = "// filler compute line\n"
                    while (length <= 5 * 1024 * 1024) append(filler)
                },
                "com/example/fixture/Weird.java" to "class Weird { String s = \"compute\u0000\"; }",
            ),
        )
        val response = searchSource("compute", "com.example:fixture:1.0")
        assertTrue(response.results.isNotEmpty())
        assertTrue(response.note.contains("1 source file(s) over 5 MiB skipped"), response.note)
        assertTrue(response.note.contains("1 binary-looking file(s) skipped"), response.note)
        assertTrue(response.results.all { it.file == "com/example/fixture/Util.java" })
    }

    @Test
    fun `search-source rejects multi-line text instead of silently missing`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            searchSource("compute\nint", "com.example:fixture:1.0")
        }
        assertTrue(failure.message!!.contains("single source lines"), failure.message)
    }

    @Test
    fun `multi-version classes are warned about and pinnable via --in`() {
        val unpinned = getSource("com.example.dup.Thing")
        assertTrue(unpinned.note.contains("MULTI-VERSION WARNING"), unpinned.note)
        assertTrue(unpinned.note.contains("com.example:dup:1.0"), unpinned.note)
        assertTrue(unpinned.note.contains("com.example:dup:2.0"), unpinned.note)

        val v1 = getSource("com.example.dup.Thing", inTarget = "com.example:dup:1.0")
        assertEquals("com.example:dup:1.0", v1.coordinate)
        assertTrue(v1.source.contains("\"v1\""), v1.source.take(200))
        assertTrue(v1.note.contains("pinned"), v1.note)

        val v2 = getSource("com.example.dup.Thing", inTarget = "dup-2.0.jar")
        assertEquals("com.example:dup:2.0", v2.coordinate)
        assertTrue(v2.source.contains("\"v2\""), v2.source.take(200))

        val badPin = assertFailsWith<IllegalArgumentException> {
            getSource("com.example.dup.Thing", inTarget = "com.example:nope:9")
        }
        assertTrue(badPin.message!!.contains("matches none"), badPin.message)

        // Single-owner classes carry no conflict note.
        assertTrue(!getSource("com.example.fixture.Util").note.contains("MULTI-VERSION"))

        // search-source resolving a conflicted class warns the same way.
        val search = searchSource("tag", "com.example.dup.Thing")
        assertTrue(search.note.contains("MULTI-VERSION WARNING"), search.note)
    }

    @Test
    fun `the build-resolved version is served by default when captured`() {
        val research4jarDir = Files.createDirectories(root.resolve(".research4jar"))
        Files.write(
            research4jarDir.resolve("dependencies.json"),
            """
            {"schema_version":1,"build_tool":"maven","generated_at":0,
             "artifacts":[{"coordinate":"com.example:dup:2.0","artifact":"dup",
                           "group":"com.example","name":"dup","version":"2.0"}]}
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        val response = getSource("com.example.dup.Thing")
        assertEquals("com.example:dup:2.0", response.coordinate)
        assertTrue(response.source.contains("\"v2\""), response.source.take(200))
        assertTrue(response.note.contains("Maven resolution"), response.note)
        assertTrue(response.note.contains("MULTI-VERSION WARNING"), response.note)
    }

    @Test
    fun `method slices are cached by source hash and invalidate with the text`() {
        val first = getSource("com.example.fixture.Util#compute")
        assertEquals(2, first.slices.size)
        val cacheFile = root.resolve("sources").resolve("slices").resolve("v1")
            .resolve(Hashing.sha256(utilSource))
            .resolve(
                Hashing.sha256("com.example.fixture.Util#compute").substring(0, 32) + ".json",
            )
        assertTrue(Files.isRegularFile(cacheFile), "expected slice cache at $cacheFile")

        // A tampered entry coming back proves the second call is a cache hit.
        Files.write(
            cacheFile,
            Files.readString(cacheFile).replace("return 41", "return TAMPERED")
                .toByteArray(Charsets.UTF_8),
        )
        val hit = getSource("com.example.fixture.Util#compute")
        assertTrue(hit.slices.any { it.source.contains("TAMPERED") })

        // A corrupted entry is recomputed and rewritten, never served.
        Files.write(cacheFile, byteArrayOf(0x7b, 0x00, 0x01))
        val recomputed = getSource("com.example.fixture.Util#compute")
        assertEquals(2, recomputed.slices.size)
        assertTrue(recomputed.slices.any { it.source.contains("return 41") })
    }

    @Test
    fun `duplicate classes across jars are detected and package-info is ignored`() {
        val conflicts = dev.research4jar.indexer.ClassConflicts.detect(
            root.resolve("session.db"),
            manifestPath,
        )
        assertEquals(1, conflicts.size, conflicts.toString())
        val pair = conflicts.single()
        assertEquals(1, pair.shared_classes, "package-info must not count")
        assertEquals(
            setOf("com.example:dup:1.0", "com.example:dup:2.0"),
            setOf(pair.jar_a, pair.jar_b),
        )
        // The audit result is cached beside the session for warm re-runs.
        assertTrue(
            Files.isRegularFile(
                dev.research4jar.indexer.ClassConflicts.cachePath(root.resolve("session.db")),
            ),
        )
        assertTrue(dev.research4jar.indexer.ClassConflicts.warningLines(conflicts).size >= 3)
    }
}
