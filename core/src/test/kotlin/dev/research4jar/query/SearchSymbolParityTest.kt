package dev.research4jar.query

import dev.research4jar.indexer.store.SessionBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the per-kind contains-tier cascade — including its trigram-served
 * rewrites — returns exactly the pages the retired single-query
 * SEARCH_SYMBOL_SQL (kept as the oracle) would return, and that the
 * find-class/find-method contains fallbacks return exactly the pages of
 * their legacy scans. The session mixes all six symbol kinds with
 * adversarial rows: methods with unresolved owners / NULL projected names
 * matching battery terms through name/descriptor/owner/detail arms), case
 * variants (ranges are case-sensitive, LIKE is not), CJK names (empty range
 * upper bound), kind values, descriptor
 * fragments, '('-carrying terms, and LIKE wildcard literals, across a
 * battery of terms and pages. The fixture keeps the two invariants the
 * rewrites rely on and real merges guarantee: a resolved method projects as
 * owner FQN || '#' || name, and a string constant's method belongs to the
 * string's class.
 *
 * The fixture also includes overloaded methods with the same kind/name/shard
 * but different descriptors. They exercise the payload-complete ORDER BY
 * that keeps adjacent pages deterministic across the oracle, fast path, and
 * FTS tiers.
 */
class SearchSymbolParityTest {
    // The fixtures are far below the accelerator size gate but exercise the
    // trigram-served tiers; force the accelerated shape and restore after.
    private var savedFtsGate = SessionBuilder.ftsMinMethods

    @BeforeTest
    fun forceAcceleratedSessions() {
        savedFtsGate = SessionBuilder.ftsMinMethods
        SessionBuilder.ftsMinMethods = 0
    }

    @AfterTest
    fun restoreSizeGate() {
        SessionBuilder.ftsMinMethods = savedFtsGate
    }

    private data class Row(
        val kind: String,
        val name: String?,
        val owner: String?,
        val detail: String?,
        val shard: String,
        val score: Int,
        val matchReason: String,
    )

    private data class ClassRow(
        val fqn: String,
        val simpleName: String?,
        val packageName: String?,
        val kind: String?,
        val superFqn: String?,
        val shard: String,
        val score: Int,
        val matchReason: String,
    )

    private data class MethodRow(
        val classFqn: String,
        val name: String,
        val descriptor: String,
        val returnFqn: String?,
        val modifiers: Int,
        val shard: String,
        val score: Int,
        val matchReason: String,
    )

    private val kinds = listOf("class", "interface", "annotation", "enum")
    private fun kindFor(fqn: String) = kinds[fqn.length % kinds.size]

    private fun buildSession(seed: Int = 42): Path {
        val dir = Files.createTempDirectory("r4j-parity")
        val session = dir.resolve("session.db")
        SessionBuilder().build(session, emptyList())
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.autoCommit = false
            val random = Random(seed)
            val words = listOf(
                "Widget", "widget", "WIDGET", "Gadget", "Servlet", "Template",
                "RestTemplate", "Handler", "工具", "Wid%get", "Wid_get", "wid",
            )
            fun word() = words[random.nextInt(words.size)]
            fun exec(sql: String, vararg args: Any?) =
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeUpdate()
                }

            val insertClass =
                """INSERT INTO classes(id, fqn, kind, super_fqn, modifiers, is_abstract,
                   source_file, simple_name, package_name, source_shard_id)
                   VALUES (?, ?, ?, NULL, 1, 0, NULL, ?, ?, ?)"""
            var classId = 0
            repeat(60) {
                classId++
                val simple = "${word()}${if (random.nextBoolean()) random.nextInt(10).toString() else ""}"
                val pkg = listOf("com.example", "org.acme", "io.测试").random(random)
                val fqn = "$pkg.$simple"
                exec(insertClass, classId, fqn, kindFor(fqn), simple, pkg, "shard-${random.nextInt(3)}")
            }
            // Fixed classes: a CJK fqn (>= 3-codepoint trigram terms), an
            // orphan host whose fqn matches battery terms (owner arms must
            // surface its NULL-symbol method), and an owner-only string
            // holder whose strings match no term by value or detail.
            exec(insertClass, 901, "io.测试.工具类Box", kindFor("io.测试.工具类Box"), "工具类Box", "io.测试", "shard-0")
            exec(
                insertClass, 902, "com.acme.OrphanHostWidgetzz",
                kindFor("com.acme.OrphanHostWidgetzz"), "OrphanHostWidgetzz", "com.acme", "shard-1",
            )
            exec(
                insertClass, 903, "org.acme.StrOwnerOnlyXy",
                kindFor("org.acme.StrOwnerOnlyXy"), "StrOwnerOnlyXy", "org.acme", "shard-2",
            )
            exec(
                insertClass, 904, "io.测试.工具类Box#do",
                kindFor("io.测试.工具类Box#do"), "工具类Box#do", "io.测试", "shard-1",
            )
            exec(
                insertClass, 905, "prefix.NulOwner\u0000Hidden",
                kindFor("prefix.NulOwner\u0000Hidden"), "NulOwner\u0000Hidden", "prefix", "shard-2",
            )
            // Term-free host for the shielded orphans below (they match
            // battery terms only through their own name/descriptor arms).
            exec(insertClass, 999, "qq.safe.Qz", kindFor("qq.safe.Qz"), "Qz", "qq.safe", "shard-0")

            val insertMethod =
                """INSERT INTO methods(id, class_id, name, descriptor, return_fqn,
                   modifiers, owner_resolved, source_shard_id)
                   VALUES (?, ?, ?, ?, NULL, 1, ?, ?)"""
            val descriptors = listOf("()V", "(Ljava/lang/String;)V", "(I)Z")
            val methodsByClass = HashMap<Int, MutableList<Int>>()
            var methodId = 0
            repeat(80) {
                methodId++
                if (methodId % 10 == 0) {
                    // Shielded orphans: unique names/descriptors, so battery
                    // terms match at most one of them at a time.
                    exec(
                        insertMethod, methodId, 999, "qqq$methodId",
                        "(Lqq/safe/P$methodId;)V", 0, "shard-${random.nextInt(3)}",
                    )
                } else {
                    val owner = random.nextInt(1, 61)
                    val name = "do${word()}"
                    // Keep the production merge invariant: the owner resolves,
                    // so the query projection is owner FQN plus '#' plus name.
                    exec(
                        insertMethod, methodId, owner, name,
                        descriptors[(owner + name.length) % descriptors.size],
                        1, "shard-${random.nextInt(3)}",
                    )
                    methodsByClass.getOrPut(owner) { mutableListOf() }.add(methodId)
                }
            }
            // Orphans that DO match battery terms: on the term-matching host
            // (owner arms), by unique name (exact/prefix/case arms) and by
            // unique descriptor (descriptor arms).
            exec(insertMethod, 901, 902, "zzOrphanSave", "(I)Lzz/uniq/Beta;", 0, "shard-0")
            exec(insertMethod, 902, 999, "qqOrphanRun", "(Lzz/uniq/Alpha;)V", 0, "shard-1")
            // A fully consistent method on the CJK class for exact-symbol and
            // detail-arm terms.
            exec(
                insertMethod, 904, 901, "doDetailProbe", "(Ljava/lang/String;)V",
                1, "shard-0",
            )
            exec(
                insertMethod, 905, 901, "doOverloadProbe", "(I)V",
                1, "shard-overload",
            )
            exec(
                insertMethod, 906, 901, "doOverloadProbe", "(Ljava/lang/String;)V",
                1, "shard-overload",
            )
            // More than one '#' is ambiguous to the fast parser. It must use
            // the computed-expression fallback and still preserve exact score.
            exec(
                insertMethod, 907, 901, "do#HashProbe", "()V",
                1, "shard-0",
            )
            exec(
                insertMethod, 908, 904, "HashProbe", "(I)V",
                1, "shard-1",
            )
            exec(
                insertMethod, 909, 905, "probeBoundary", "()V",
                1, "shard-2",
            )
            exec(
                insertMethod, 910, 901, "nulName\u0000Hidden", "(Lzz/uniq/NulDescriptor;)V",
                1, "shard-0",
            )

            repeat(40) {
                val onClass = random.nextBoolean()
                exec(
                    """INSERT INTO annotations(target_kind, target_id, annotation_fqn,
                       attributes, source_shard_id)
                       VALUES (?, ?, ?, ?, ?)""",
                    if (onClass) "class" else "method",
                    if (onClass) random.nextInt(1, classId + 1) else random.nextInt(1, methodId + 1),
                    "org.anno.${word()}Marker",
                    if (random.nextBoolean()) """{"value":"${word()}"}""" else null,
                    "shard-${random.nextInt(3)}",
                )
            }
            repeat(20) {
                exec(
                    """INSERT INTO spi_registrations(mechanism, key, impl_fqn, source_shard_id)
                       VALUES ('services', ?, ?, ?)""",
                    "org.spi.${word()}Provider", "com.impl.${word()}Impl", "shard-${random.nextInt(3)}",
                )
            }
            repeat(20) {
                exec(
                    """INSERT INTO config_properties(prefix, name, type_fqn, default_val,
                       description, source_fqn, source_shard_id)
                       VALUES (?, ?, ?, NULL, NULL, ?, ?)""",
                    "app", "app.${word().lowercase()}.enabled", "java.lang.Boolean",
                    "com.example.${word()}Properties", "shard-${random.nextInt(3)}",
                )
            }
            val insertString =
                """INSERT INTO string_constants(class_id, method_id, value, source_shard_id)
                   VALUES (?, ?, ?, ?)"""
            var counter = 0
            repeat(50) {
                counter++
                val cls = random.nextInt(1, 61)
                val mids = methodsByClass[cls]
                exec(
                    insertString, cls,
                    if (mids != null && random.nextBoolean()) mids[random.nextInt(mids.size)] else null,
                    "the ${word()} constant $counter",
                    "shard-${random.nextInt(3)}",
                )
            }
            // Detail arms through orphaned (NULL-symbol) methods, owner-only
            // matches, a CJK value, a consistent detail probe, and an exact
            // duplicated value (identical rows, so tie order cannot matter).
            exec(insertString, 902, 901, "orphan detail probe zzz", "shard-0")
            exec(insertString, 999, 902, "orphan run probe zzz", "shard-0")
            exec(insertString, 903, null, "plain constant alpha", "shard-1")
            exec(insertString, 903, null, "plain constant beta", "shard-2")
            exec(insertString, 901, null, "配置中心加载完成", "shard-0")
            exec(insertString, 901, 904, "detail probe owner one", "shard-0")
            exec(insertString, 901, 910, "nul detail must stay hidden", "shard-0")
            repeat(2) { exec(insertString, 1, null, "the Widget constant dup", "shard-0") }

            // Rows were inserted directly (not through the shard merge), so
            // re-run the classes_fts rebuild and the search-domain fill the
            // merge commit performs.
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO classes_fts(classes_fts) VALUES('rebuild')")
            }
            SessionBuilder().syncSearchDomains(connection, methodIdOffset = 0, stringIdOffset = 0)
            connection.commit()
        }
        return session
    }

    /**
     * Builds one controlled score-82 prefix followed by score-55 contains
     * tail. This makes the deep-page fast/contains boundary independent of
     * the randomized parity fixture above.
     */
    private fun buildDeepBoundarySession(fastCount: Int, containsCount: Int): Path {
        val dir = Files.createTempDirectory("r4j-deep-boundary")
        val session = dir.resolve("session.db")
        SessionBuilder().build(session, emptyList())
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO classes(
                  fqn, kind, super_fqn, modifiers, is_abstract, source_file,
                  simple_name, package_name, source_shard_id
                ) VALUES (?, 'class', NULL, 1, 0, NULL, ?, ?, 1)
                """.trimIndent(),
            ).use { insert ->
                fun add(packageName: String, simpleName: String) {
                    insert.setString(1, "$packageName.$simpleName")
                    insert.setString(2, simpleName)
                    insert.setString(3, packageName)
                    insert.executeUpdate()
                }
                repeat(fastCount) { index ->
                    add("fast.boundary", "Needle%04d".format(index))
                }
                repeat(containsCount) { index ->
                    add("contains.boundary", "BoxNeedle%04d".format(index))
                }
            }
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO classes_fts(classes_fts) VALUES('rebuild')")
            }
        }
        return session
    }

    private fun pointer(session: Path) = ProjectPointerData(
        schemaVersion = 2,
        extractorVersion = 2,
        classpathFingerprint = "test",
        sessionDbPath = session.toString(),
    )

    // One term per routing class: legacy-only (short, wildcards, 2-codepoint
    // CJK), trigram-served (ASCII, mixed case, CJK, kind values, descriptor
    // fragments, owner-only, orphan arms), '('-carriers (string tier legacy,
    // class/method tiers trigram-served), exact matches that the exclusion
    // predicates must keep out of the contains tiers, and zero-hit probes.
    private val terms = listOf(
        "Widget", "widget", "WIDGET", "wid", "doGadget", "Template",
        "com.example.Widget", "工具", "Wid\\%get", "%", "Provider",
        "app.widget.enabled", "constant", "zzz-no-match", "services",
        "class", "interface", "nterfa", "enum",
        "restTEMPLATE", "ab",
        "run(", "(I)Z", "()V", ")V",
        "Ljava", "lang/String",
        "工具类", "试.工", "配置中心",
        "qqOrphanRun", "qqOrphanR", "qqorphanrun", "qqq3", "qqq30",
        "zz/uniq/Alpha", "zz/uniq/Beta", "OrphanHostWidget", "rphanRu", "OrphanSav",
        "StrOwnerOnly", "DetailProb", "doDetailProbe", "etailProb",
        "io.测试.工具类Box#doDetailProbe", "具类Box#doDet", "Box#do", "do#Hash",
        "io.测试.工具类Box#do#HashProbe", "NulOwner#probe", "NulDescriptor",
        "prefix.NulOwner\u0000Hidden#probeBoundary",
        "the Widget constant dup",
        "zzqqxx-nothing",
    )

    private fun oraclePage(
        session: Connection,
        term: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<Row>, Boolean> {
        val rows = session.query(
            SEARCH_SYMBOL_SQL,
            matchArgs(term) + listOf(pageSize + 1, (page - 1) * pageSize),
        ) { result ->
            result.mapRows {
                Row(
                    kind = it.getString(1),
                    name = it.getString(2),
                    owner = it.getString(3),
                    detail = it.getString(4),
                    shard = it.getString(5),
                    score = it.getInt(6),
                    matchReason = it.getString(7),
                )
            }
        }
        val hasMore = rows.size > pageSize
        return (if (hasMore) rows.subList(0, pageSize) else rows) to hasMore
    }

    private fun classOraclePage(
        session: Connection,
        term: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<ClassRow>, Boolean> {
        val rows = session.query(
            CLASS_SEARCH_SQL,
            matchArgs(term) + listOf(pageSize + 1, (page - 1) * pageSize),
        ) { result ->
            result.mapRows {
                ClassRow(
                    fqn = it.getString(1),
                    simpleName = it.getString(2),
                    packageName = it.getString(3),
                    kind = it.getString(4),
                    superFqn = it.getString(5),
                    shard = it.getString(6),
                    score = it.getInt(7),
                    matchReason = it.getString(8),
                )
            }
        }
        val hasMore = rows.size > pageSize
        return (if (hasMore) rows.subList(0, pageSize) else rows) to hasMore
    }

    private fun methodOraclePage(
        session: Connection,
        term: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<MethodRow>, Boolean> {
        val rows = session.query(
            routeMethodExactMatch(METHOD_SEARCH_SQL, term),
            methodMatchArgs(term) + listOf(pageSize + 1, (page - 1) * pageSize),
        ) { result ->
            result.mapRows {
                MethodRow(
                    classFqn = it.getString(1),
                    name = it.getString(2),
                    descriptor = it.getString(3),
                    returnFqn = it.getString(4),
                    modifiers = it.getInt(5),
                    shard = it.getString(6),
                    score = it.getInt(7),
                    matchReason = it.getString(8),
                )
            }
        }
        val hasMore = rows.size > pageSize
        return (if (hasMore) rows.subList(0, pageSize) else rows) to hasMore
    }

    private fun assertSearchSymbolParity(session: Path): Pair<Int, Int> {
        val pointer = pointer(session)
        var comparisons = 0
        var orphanPages = 0
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            for (term in terms) {
                for (pageSize in listOf(5, 20)) {
                    var page = 1
                    while (page <= 6) {
                        val (expected, expectedHasMore) = oraclePage(db, term, page, pageSize)
                        val (rows, hasMore) = searchSymbolRows(db, term, page, pageSize)
                        val actual = rows.map {
                            Row(it.kind, it.name, it.owner, it.detail, it.shardId, it.score, it.matchReason)
                        }
                        assertEquals(expected, actual, "term=$term page=$page pageSize=$pageSize")
                        assertEquals(
                            expectedHasMore,
                            hasMore,
                            "hasMore for term=$term page=$page pageSize=$pageSize",
                        )
                        if (expected.any { it.name == null }) {
                            // NULL-name rows (matching orphans) are compared
                            // at the raw-cascade level only: the response
                            // mapping rejects them by design.
                            orphanPages++
                        } else {
                            val response = searchSymbol(pointer, "", term, page, pageSize)
                            val served = response.results.map {
                                Row(it.kind, it.name, it.owner, it.detail, it.sourceJar, it.score, it.matchReason)
                            }
                            assertEquals(expected, served, "response for term=$term page=$page pageSize=$pageSize")
                            assertEquals(expectedHasMore, response.hasMore)
                        }
                        comparisons++
                        if (!expectedHasMore) break
                        page++
                    }
                }
            }
        }
        return comparisons to orphanPages
    }

    @Test
    fun `cascade pages equal the single-query oracle for every term and page`() {
        // Several seeds: randomized rows land differently while the explicit
        // payload keys keep every page deterministic.
        for (seed in listOf(42, 7, 1234)) {
            val session = buildSession(seed)
            val (comparisons, orphanPages) = assertSearchSymbolParity(session)
            // Guard against the battery silently degenerating to empty results.
            assertTrue(comparisons > 150, "seed=$seed ran $comparisons page comparisons")
            assertTrue(orphanPages > 0, "seed=$seed must reach NULL-symbol orphan rows")
        }
    }

    @Test
    fun `overloaded methods keep a stable payload order across page boundaries`() {
        val session = buildSession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            for (term in listOf("doOverloadProbe", "verloadProb")) {
                val details = mutableListOf<String?>()
                for (page in 1..3) {
                    val (expected, expectedHasMore) = oraclePage(db, term, page, pageSize = 1)
                    val (actual, actualHasMore) = searchSymbolRows(db, term, page, pageSize = 1)
                    assertEquals(
                        expected,
                        actual.map {
                            Row(it.kind, it.name, it.owner, it.detail, it.shardId, it.score, it.matchReason)
                        },
                        "overload term=$term page=$page",
                    )
                    assertEquals(expectedHasMore, actualHasMore)
                    details += actual.singleOrNull()?.detail
                }
                assertEquals(listOf("(I)V", "(Ljava/lang/String;)V", null), details)
            }
        }
    }

    @Test
    fun `deep pages stay exact on both sides of the fast prefix boundary`() {
        val term = "Needle"
        val page = 3
        val pageSize = 4
        val offset = (page - 1) * pageSize
        for (delta in -1..1) {
            val fastCount = offset + pageSize + delta
            val session = buildDeepBoundarySession(fastCount, containsCount = 3)
            DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
                val observedFastCount = db.query(
                    SEARCH_SYMBOL_FAST_SQL,
                    matchArgs(term) + listOf(100, 0),
                ) { rows -> rows.mapRows { _ -> 1 }.size }
                assertEquals(fastCount, observedFastCount, "fixture fast count for delta=$delta")

                val (expected, expectedHasMore) = oraclePage(db, term, page, pageSize)
                val (actual, actualHasMore) = searchSymbolRows(db, term, page, pageSize)
                val actualRows = actual.map {
                    Row(it.kind, it.name, it.owner, it.detail, it.shardId, it.score, it.matchReason)
                }
                assertEquals(expected, actualRows, "deep boundary delta=$delta")
                assertEquals(expectedHasMore, actualHasMore, "deep hasMore delta=$delta")
                assertTrue(actualHasMore, "the contains tail keeps every boundary page open")

                // -1 must fill the final slot from contains; = must fall back
                // solely to discover has_more; +1 is served by the direct
                // pageSize+1 fast slice.
                val fastOnPage = minOf(pageSize, fastCount - offset)
                assertEquals(
                    List(fastOnPage) { "simple_prefix" } +
                        List(pageSize - fastOnPage) { "class_contains" },
                    actualRows.map(Row::matchReason),
                    "score-tier transition for delta=$delta",
                )
            }
        }
    }

    @Test
    fun `trigram-served tiers hit rows on their own`() {
        // findX's fallback would otherwise let a broken fts path pass every
        // battery through the legacy scans (same guard as FindStringParityTest).
        val session = buildSession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            fun count(sql: String, args: List<Any?>): Int =
                db.query(sql, args) { it.mapRows { _ -> 1 }.size }
            val pattern = "%Widget%"
            assertTrue(
                count(SEARCH_SYMBOL_CLASS_FTS_SQL, matchArgs("Widget") + listOf(pattern, pattern, 100)) > 0,
                "class tier fts must match",
            )
            val mPattern = "%oGadget%"
            assertTrue(
                count(
                    SEARCH_SYMBOL_METHOD_FTS_SQL,
                    matchArgs("oGadget") + listOf(mPattern, mPattern, mPattern, 100),
                ) > 0,
                "method tier fts must match",
            )
            val sPattern = "%constant%"
            assertTrue(
                count(
                    SEARCH_SYMBOL_STRING_FTS_SQL,
                    matchArgs("constant") + listOf(sPattern, sPattern, sPattern, sPattern, 100),
                ) > 0,
                "string tier fts must match",
            )
            assertTrue(
                count(CLASS_SEARCH_FTS_SQL, matchArgs("Widget") + listOf(pattern, 100, 0)) > 0,
                "find-class fts must match",
            )
            assertTrue(
                count(METHOD_SEARCH_FTS_SQL, methodMatchArgs("oGadget") + listOf(mPattern, mPattern, 100, 0)) > 0,
                "find-method fts must match",
            )
        }
    }

    @Test
    fun `single hash terms use the indexed boundary decomposition when safe`() {
        val term = "具类Box#doDet"
        assertEquals(2, methodContainsQueries(term).size)
        assertNotNull(symbolContainsTiers(term)[1].ftsSql)
        assertNull(symbolContainsTiers("a#b")[1].ftsSql)
        assertNull(symbolContainsTiers("owner#method#tail")[1].ftsSql)
        assertNull(symbolContainsTiers("owner\u0000#method")[1].ftsSql)
    }

    @Test
    fun `exact method symbols preserve embedded NUL on either side of the delimiter`() {
        val session = buildSession()
        val cases = listOf(
            Triple(
                "prefix.NulOwner\u0000Hidden#probeBoundary",
                "prefix.NulOwner\u0000Hidden",
                "probeBoundary",
            ),
            Triple(
                "io.测试.工具类Box#nulName\u0000Hidden",
                "io.测试.工具类Box",
                "nulName\u0000Hidden",
            ),
        )

        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            for ((term, owner, name) in cases) {
                val method = findMethod(pointer(session), "", term, page = 1, pageSize = 1)
                assertEquals(1, method.results.size, "find-method exact term=$term")
                assertEquals(owner, method.results.single().classFqn)
                assertEquals(name, method.results.single().name)
                assertEquals(100, method.results.single().score)
                assertEquals("exact_method", method.results.single().matchReason)

                val (symbols, _) = searchSymbolRows(db, term, page = 1, pageSize = 1)
                assertEquals(1, symbols.size, "search-symbol exact term=$term")
                assertEquals("method", symbols.single().kind)
                assertEquals(term, symbols.single().name)
                assertEquals(98, symbols.single().score)
                assertEquals("exact_method", symbols.single().matchReason)
            }
        }
    }

    @Test
    fun `open-symbol probes owner and name while preserving unresolved owners`() {
        val session = buildSession()
        val resolved = openSymbol(
            pointer(session), "", "io.测试.工具类Box#do#HashProbe",
        )
        assertEquals(2, resolved.total)
        assertEquals(setOf("do#HashProbe", "HashProbe"), resolved.results.map { it.method?.name }.toSet())
        assertTrue(resolved.results.all { it.method?.matchReason == "exact_method" })

        val unresolved = openSymbol(pointer(session), "", "qq.safe.Qz#qqOrphanRun")
        assertEquals(0, unresolved.total)
    }

    @Test
    fun `strict fast score tiers equal the mega union without duplicates`() {
        val session = buildSession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            fun rows(sql: String, args: List<Any?>): List<Row> = db.query(sql, args) { result ->
                result.mapRows {
                    Row(
                        it.getString(1), it.getString(2), it.getString(3), it.getString(4),
                        it.getString(5), it.getInt(6), it.getString(7),
                    )
                }
            }
            for (term in terms) {
                val expected = rows(
                    routeMethodExactMatch(SEARCH_SYMBOL_FAST_SQL, term),
                    matchArgs(term) + listOf(10_000, 0),
                )
                val actual = searchSymbolFastTiers(term).flatMap { sql ->
                    rows(sql, matchArgs(term) + listOf(10_000))
                }
                assertEquals(expected, actual, "fast tiers for term=$term")
            }
        }
    }

    @Test
    fun `string detail arms use the method lookup index`() {
        val session = buildSession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            val pattern = "%DetailProb%"
            val details = db.query(
                "EXPLAIN QUERY PLAN $SEARCH_SYMBOL_STRING_FTS_SQL",
                matchArgs("DetailProb") + listOf(pattern, pattern, pattern, pattern, 20),
            ) { rows -> rows.mapRows { it.getString(4) } }
            assertTrue(
                details.count { "idx_s_strconst_method" in it } >= 2,
                "method-name and descriptor arms must probe strings by method: $details",
            )
        }
    }

    private fun assertFindClassParity(session: Path) {
        val pointer = pointer(session)
        var comparisons = 0
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            for (term in terms) {
                for (pageSize in listOf(5, 20)) {
                    var page = 1
                    while (page <= 6) {
                        val (expected, expectedHasMore) = classOraclePage(db, term, page, pageSize)
                        val response = findClass(pointer, "", term, page, pageSize)
                        val actual = response.results.map {
                            ClassRow(
                                it.fqn, it.simpleName, it.packageName, it.kind, it.superFqn,
                                it.sourceJar, it.score, it.matchReason,
                            )
                        }
                        assertEquals(expected, actual, "find-class term=$term page=$page pageSize=$pageSize")
                        assertEquals(expectedHasMore, response.hasMore)
                        comparisons++
                        if (!expectedHasMore) break
                        page++
                    }
                }
            }
        }
        assertTrue(comparisons > 100, "ran $comparisons find-class comparisons")
    }

    private fun assertFindMethodParity(session: Path) {
        val pointer = pointer(session)
        var comparisons = 0
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            for (term in terms) {
                for (pageSize in listOf(5, 20)) {
                    var page = 1
                    while (page <= 6) {
                        val (expected, expectedHasMore) = methodOraclePage(db, term, page, pageSize)
                        val response = findMethod(pointer, "", term, page, pageSize)
                        val actual = response.results.map {
                            MethodRow(
                                it.classFqn, it.name, it.descriptor, it.returnFqn, it.modifiers,
                                it.sourceJar, it.score, it.matchReason,
                            )
                        }
                        assertEquals(expected, actual, "find-method term=$term page=$page pageSize=$pageSize")
                        assertEquals(expectedHasMore, response.hasMore)
                        comparisons++
                        if (!expectedHasMore) break
                        page++
                    }
                }
            }
        }
        assertTrue(comparisons > 100, "ran $comparisons find-method comparisons")
    }

    @Test
    fun `find-class pages equal the legacy oracle for every term and page`() {
        for (seed in listOf(42, 7)) assertFindClassParity(buildSession(seed))
    }

    @Test
    fun `find-method pages equal the legacy oracle for every term and page`() {
        for (seed in listOf(42, 7)) assertFindMethodParity(buildSession(seed))
    }

    @Test
    fun `sessions without the trigram tables fall back to the legacy scans`() {
        val session = buildSession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use {
                it.execute("DROP TABLE classes_fts")
                it.execute("DROP TABLE method_names_fts")
                it.execute("DROP TABLE method_descriptors_fts")
                it.execute("DROP TABLE string_values_fts")
            }
        }
        val (comparisons, _) = assertSearchSymbolParity(session)
        assertTrue(comparisons > 150, "ran $comparisons page comparisons")
        assertFindClassParity(session)
        assertFindMethodParity(session)
    }

    @Test
    fun `sessions with the old symbol methods fts schema fall back to legacy`() {
        // Simulates a pre-v9 session: the per-row methods_fts exists but the
        // packed domain structures do not — the new queries must retry their
        // legacy SQL atomically instead of failing or reading the old shadow.
        val session = buildSession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE method_names_fts")
                statement.execute("DROP TABLE method_descriptors_fts")
                statement.execute("DROP TABLE method_name_packs")
                statement.execute("DROP TABLE method_descriptor_packs")
                statement.execute("DROP TABLE method_names")
                statement.execute("DROP TABLE method_descriptors")
                statement.execute(
                    """
                    CREATE VIRTUAL TABLE methods_fts USING fts5(
                      symbol, descriptor,
                      tokenize='trigram', detail='none', columnsize=0
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "INSERT INTO methods_fts(rowid, symbol, descriptor) " +
                        "SELECT m.id, CASE WHEN m.owner_resolved = 0 THEN NULL " +
                        "ELSE c.fqn || '#' || m.name END, m.descriptor " +
                        "FROM methods m JOIN classes c ON c.id = m.class_id",
                )
            }
        }

        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            // The first term forces the method-name tier; the second also
            // reaches the string-detail tier. Both new queries reference the
            // absent `name` column and must retry their legacy SQL atomically.
            for (term in listOf("rphanRu", "etailProb")) {
                val (expected, expectedHasMore) = oraclePage(db, term, page = 1, pageSize = 100)
                val (actual, actualHasMore) = searchSymbolRows(db, term, page = 1, pageSize = 100)
                val actualRows = actual.map {
                    Row(it.kind, it.name, it.owner, it.detail, it.shardId, it.score, it.matchReason)
                }
                assertTrue(expected.isNotEmpty(), "old-schema probe must match for term=$term")
                if (term == "etailProb") {
                    assertTrue(expected.any { it.kind == "method" })
                    assertTrue(expected.any { it.kind == "string" })
                }
                assertEquals(expected, actualRows, "search-symbol old methods_fts term=$term")
                assertEquals(expectedHasMore, actualHasMore)
            }

            val term = "rphanRu"
            val (expected, expectedHasMore) = methodOraclePage(db, term, page = 1, pageSize = 100)
            val response = findMethod(pointer(session), "", term, page = 1, pageSize = 100)
            val actual = response.results.map {
                MethodRow(
                    it.classFqn, it.name, it.descriptor, it.returnFqn, it.modifiers,
                    it.sourceJar, it.score, it.matchReason,
                )
            }
            assertTrue(expected.isNotEmpty(), "find-method old-schema probe must match")
            assertEquals(expected, actual)
            assertEquals(expectedHasMore, response.hasMore)
        }
    }

    @Test
    fun `term routing matches the trigram contract`() {
        // Class/method tiers follow find-string's routing rule.
        assertTrue(classContainsQueries("Widget").size == 2)
        assertTrue(classContainsQueries("工具类").size == 2)
        assertTrue(classContainsQueries("run(").size == 2)
        assertTrue(classContainsQueries("ab").size == 1)
        assertTrue(classContainsQueries("Wid%get").size == 1)
        assertTrue(classContainsQueries("Wid_get").size == 1)
        assertTrue(classContainsQueries("back\\slash").size == 1)
        assertTrue(methodContainsQueries("()V").size == 2)
        assertTrue(methodContainsQueries("owner#method").size == 2)
        assertTrue(methodContainsQueries(")V").size == 1)
        // search-symbol: the string tier alone rejects '(' terms (its detail
        // column is a name||descriptor concatenation); annotation/spi/config
        // tiers never route to fts.
        fun ftsTiers(term: String) = symbolContainsTiers(term).map { it.ftsSql != null }
        assertEquals(listOf(true, true, false, false, false, true), ftsTiers("Widget"))
        assertEquals(listOf(true, true, false, false, false, false), ftsTiers("run("))
        assertEquals(listOf(false, false, false, false, false, false), ftsTiers("ab"))
        assertEquals(listOf(false, false, false, false, false, false), ftsTiers("Wid%get"))
        assertEquals(listOf(true, true, false, false, false, true), ftsTiers("工具类"))
        assertEquals(listOf(false, false, false, false, false, false), ftsTiers("工具"))
    }
}
