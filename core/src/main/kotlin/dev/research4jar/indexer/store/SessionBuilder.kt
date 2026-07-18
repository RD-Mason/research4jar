package dev.research4jar.indexer.store

import dev.research4jar.indexer.Research4JarVersions
import dev.research4jar.runtime.SessionFileLease
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager

data class SessionShard(
    val shardId: String,
    val path: Path,
)

/**
 * The derived-column expressions, exposed for the unit test that proves the
 * rtrim/substr SQL is exact lastIndexOf('.') semantics. The rtrim trick: its
 * second argument is a character SET, so trimming every non-dot character
 * strips exactly the rightmost run of non-dot characters, leaving the prefix
 * up to and including the last dot.
 *
 * These are computed inline in the merge INSERT...SELECT (they read the bare
 * `fqn` column of whatever table the enclosing statement scans) rather than
 * by a post-merge UPDATE pass: the UPDATE rewrote every row of the two
 * largest session tables, which cost more than the whole shard merge itself.
 */
internal const val SIMPLE_NAME_EXPR =
    "CASE WHEN instr(fqn, '.') = 0 THEN fqn " +
        "ELSE substr(fqn, length(rtrim(fqn, replace(fqn, '.', ''))) + 1) END"
internal const val PACKAGE_NAME_EXPR =
    "CASE WHEN instr(fqn, '.') = 0 THEN '' " +
        "ELSE substr(fqn, 1, length(rtrim(fqn, replace(fqn, '.', ''))) - 1) END"

/**
 * Every session table with its own id primary key. [SessionBuilder.merge]
 * records each shard's contiguous id range per table in session_shard_ranges,
 * and the delta deletion pass removes exactly those ranges — class_interfaces
 * (no id column) is covered through its shard's classes range. All rows
 * referencing another table's ids (annotations, string_constants, ...) come
 * from the same shard as their referent, so a per-shard delete never leaves
 * dangling references. The external-content FTS5 shadows (string_constants_fts,
 * classes_fts, methods_fts) are each kept in sync at two sites — one statement
 * per shadow before its content table's delete in
 * [SessionBuilder.deleteShards], one after its content table's INSERT in
 * [SessionBuilder.merge].
 */
internal val ID_TABLES = listOf(
    "config_properties",
    "spi_registrations",
    "classes",
    "annotations",
    "methods",
    "bean_definitions",
    "conditions",
    "string_constants",
)

/**
 * The cheap, data-independent part of the session contract. Reuse is a hot
 * path, so this deliberately prepares zero-row projections instead of running
 * quick_check/integrity-check over a potentially multi-GB database. Preparing
 * the statements still proves that every required object and column exists;
 * the sqlite_schema checks below additionally distinguish the view and FTS5
 * virtual tables from ordinary tables with the same names.
 */
private val SESSION_TABLE_COLUMNS = linkedMapOf(
    "session_meta" to listOf("session_schema_version", "delta_depth"),
    "session_shards" to listOf("shard_id", "shard_key"),
    "session_shard_ranges" to listOf("shard_id", "table_name", "min_id", "max_id"),
    "config_properties" to
        listOf("id", "prefix", "name", "type_fqn", "default_val", "description", "source_fqn", "source_shard_id"),
    "spi_registrations" to listOf("id", "mechanism", "key", "impl_fqn", "source_shard_id"),
    "classes" to
        listOf(
            "id", "fqn", "kind", "super_fqn", "modifiers", "is_abstract", "source_file",
            "simple_name", "package_name", "source_shard_id",
        ),
    "class_interfaces" to listOf("class_id", "interface_fqn", "source_shard_id"),
    "annotations" to
        listOf("id", "target_kind", "target_id", "annotation_fqn", "attributes", "source_shard_id"),
    "methods" to
        listOf(
            "id", "class_id", "name", "descriptor", "return_fqn", "modifiers", "owner_resolved",
            "source_shard_id",
        ),
    "bean_definitions" to
        listOf("id", "config_fqn", "method_id", "bean_type_fqn", "bean_name", "source_shard_id"),
    "conditions" to listOf("id", "target_kind", "target_id", "type", "ref_value", "source_shard_id"),
    "string_constants" to listOf("id", "class_id", "method_id", "value", "source_shard_id"),
)

private val SESSION_FTS_COLUMNS = linkedMapOf(
    "string_constants_fts" to listOf("value"),
    "classes_fts" to listOf("fqn", "kind"),
    "methods_fts" to listOf("name", "descriptor"),
)

private val SESSION_INTEGER_COLUMNS = buildMap {
    put("session_shards", setOf("shard_key"))
    listOf(
        "config_properties",
        "spi_registrations",
        "classes",
        "class_interfaces",
        "annotations",
        "methods",
        "bean_definitions",
        "conditions",
        "string_constants",
    ).forEach { table -> put(table, setOf("source_shard_id")) }
    put("methods", setOf("owner_resolved", "source_shard_id"))
}

private val SESSION_FTS_CREATE_SQL = linkedMapOf(
    "string_constants_fts" to
        "CREATE VIRTUAL TABLE string_constants_fts USING fts5(" +
        "value, content='string_constants', content_rowid='id', " +
        "tokenize='trigram', detail='none', columnsize=0)",
    "classes_fts" to
        "CREATE VIRTUAL TABLE classes_fts USING fts5(" +
        "fqn, kind, content='classes', content_rowid='id', " +
        "tokenize='trigram', detail='none', columnsize=0)",
    "methods_fts" to
        "CREATE VIRTUAL TABLE methods_fts USING fts5(" +
        "name, descriptor, content='methods', content_rowid='id', " +
        "tokenize='trigram', detail='none', columnsize=0)",
)

/** Name plus the performance-critical part of each CREATE INDEX statement. */
private val SESSION_INDEX_SQL_FRAGMENTS = linkedMapOf(
    "idx_session_shards_key" to "on session_shards(shard_key)",
    "idx_s_cfg_prefix" to "on config_properties(prefix)",
    "idx_s_cfg_name" to "on config_properties(name)",
    "idx_s_spi_mech" to "on spi_registrations(mechanism)",
    "idx_s_spi_key" to "on spi_registrations(key)",
    "idx_s_classes_fqn" to "on classes(fqn)",
    "idx_s_classes_simple" to "on classes(simple_name)",
    "idx_s_classes_package" to "on classes(package_name)",
    "idx_s_classes_super" to "on classes(super_fqn)",
    "idx_s_ci_iface" to "on class_interfaces(interface_fqn)",
    "idx_s_ci_class" to "on class_interfaces(class_id)",
    "idx_s_ann_fqn" to "on annotations(annotation_fqn)",
    "idx_s_ann_target" to "on annotations(target_kind, target_id)",
    "idx_s_methods_class" to "on methods(class_id)",
    "idx_s_methods_name" to "on methods(name)",
    "idx_s_methods_orphan" to "on methods(id) where owner_resolved = 0",
    "idx_s_bean_type" to "on bean_definitions(bean_type_fqn)",
    "idx_s_bean_cfg" to "on bean_definitions(config_fqn)",
    "idx_s_cond_target" to "on conditions(target_kind, target_id)",
    "idx_s_strconst_value" to "on string_constants(value)",
    "idx_s_strconst_class" to "on string_constants(class_id)",
    "idx_s_strconst_method" to
        "on string_constants(method_id, class_id) where method_id is not null",
    "idx_s_spi_impl" to "on spi_registrations(impl_fqn)",
)

private val SEARCH_SYMBOL_COLUMNS =
    listOf("kind", "name", "owner", "detail", "source_shard_id", "simple_name", "package_name", "score_hint")
private val SEARCH_SYMBOL_VIEW_SQL =
    """
    CREATE VIEW search_symbols AS
    SELECT 'class' AS kind, fqn AS name, NULL AS owner, kind AS detail,
           source_shard_id, simple_name, package_name, 55 AS score_hint
    FROM classes
    UNION ALL
    SELECT 'method',
           CASE WHEN m.owner_resolved = 0 THEN NULL ELSE c.fqn || '#' || m.name END,
           c.fqn, m.descriptor, m.source_shard_id,
           m.name, c.package_name, 50
    FROM methods m JOIN classes c ON c.id = m.class_id
    UNION ALL
    SELECT 'annotation', a.annotation_fqn,
           CASE WHEN a.target_kind = 'class' THEN c.fqn
                WHEN a.target_kind = 'method' AND m.owner_resolved <> 0
                  THEN mc.fqn || '#' || m.name
                ELSE NULL END,
           a.attributes, a.source_shard_id, NULL,
           COALESCE(c.package_name, mc.package_name), 45
    FROM annotations a
    LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
    LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
    LEFT JOIN classes mc ON mc.id = m.class_id
    UNION ALL
    SELECT 'spi', impl_fqn, key, mechanism, source_shard_id, NULL, NULL, 44
    FROM spi_registrations
    UNION ALL
    SELECT 'config-property', name, source_fqn, type_fqn, source_shard_id, NULL, NULL, 43
    FROM config_properties
    UNION ALL
    SELECT 'string', sc.value, c.fqn,
           CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END,
           sc.source_shard_id, NULL, c.package_name, 30
    FROM string_constants sc
    JOIN classes c ON c.id = sc.class_id
    LEFT JOIN methods m ON m.id = sc.method_id
    """.trimIndent()
/**
 * Session fact tables repeat a source reference on every row. A 64-character
 * hash costs close to 100 MiB in a medium real-world session, so v7 stores a
 * compact INTEGER surrogate and keeps the canonical hash once in
 * session_shards. Keys are strictly increasing in shard-id lexical order;
 * therefore every existing ORDER BY source_shard_id keeps exactly the same
 * ordering and pagination semantics without joining the mapping table.
 */
// 2^16 leaves eight repeated midpoint generations with 256 slots still in
// the original gap, while keeping ordinary keys in SQLite's short varint
// encodings (and still allowing over 10^14 monotonically appended shards).
private const val SHARD_KEY_STRIDE = 1L shl 16
private val LONG_MIN_BIG = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX_BIG = BigInteger.valueOf(Long.MAX_VALUE)

internal class ShardKeySpaceExhaustedException(message: String) : IllegalStateException(message)

internal data class ReusableSessionState(
    val shardIds: Set<String>,
    val deltaDepth: Int,
)

class SessionBuilder {
    /**
     * Sessions are content-addressed by the classpath fingerprint in the file
     * name, so an existing structurally-sound session of the current layout
     * version is LOGICALLY equivalent to whatever a rebuild would produce —
     * same rows and relationships — and can be reused as-is. It is not
     * necessarily byte-identical: [buildDelta] derives a session from a
     * previous one, which leaves different row ids and physical order than a
     * from-scratch merge. [isReusable] semantics are unchanged by that
     * relaxation. Sessions written by older binaries fail the version check
     * and are rebuilt.
     */
    fun buildIfAbsent(target: Path, shards: List<SessionShard>) {
        if (reuseAndTouch(target)) return
        build(target, shards)
    }

    /** Keep validation and the last-use refresh atomic with respect to GC. */
    private fun reuseAndTouch(target: Path): Boolean {
        if (!Files.isRegularFile(target)) return false
        val lease = SessionFileLease.acquireSharedIfPresent(target) ?: return false
        lease.use {
            if (!isReusableWithoutLease(target)) return false
            // Session mtime doubles as the last-use marker for the automatic
            // stale-session sweep; refresh it so an actively indexed
            // classpath never ages out.
            try {
                Files.setLastModifiedTime(
                    target,
                    java.nio.file.attribute.FileTime.from(java.time.Instant.now()),
                )
            } catch (_: Exception) {
                // best-effort: a read-only cache directory must not fail the run
            }
            return true
        }
    }

    fun build(target: Path, shards: List<SessionShard>) {
        openStream(target.parent).use { stream ->
            shards.sortedBy(SessionShard::shardId).forEach(stream::merge)
            stream.commit(target)
        }
    }

    /**
     * Derives the session for a slightly-changed shard set from an existing
     * one instead of re-merging every shard: copy the previous session to a
     * temporary file, delete the removed shards' rows set-based, merge the
     * added shards with the same per-shard INSERT...SELECT the full build
     * uses, then sampled-ANALYZE and atomically rename into place. The result
     * is logically equivalent to a from-scratch build of the new set (same
     * rows and relationships) but not byte-identical: deleted id ranges leave
     * holes and added rows land at the end. Throws on any failure — callers
     * fall back to a full build; the temporary file never survives a failure.
     */
    fun buildDelta(
        previous: Path,
        target: Path,
        removedShardIds: Collection<String>,
        addedShards: List<SessionShard>,
    ) {
        val temporary = AtomicFiles.temporaryIn(target.parent, "session")
        try {
            // GC may unlink an expired base session; keep it leased until the
            // reflink/copy has a complete private inode to continue from.
            SessionFileLease.acquireShared(previous).use {
                cloneOrCopy(previous, temporary)
            }
            DriverManager.getConnection("jdbc:sqlite:${temporary.toAbsolutePath()}")
                .use { connection ->
                    configure(connection)
                    deleteShards(connection, removedShardIds)
                    val sortedAdded = addedShards.sortedBy(SessionShard::shardId)
                    val shardKeys = allocateDeltaShardKeys(
                        connection,
                        sortedAdded.map(SessionShard::shardId),
                    )
                    sortedAdded.forEach { shard ->
                        merge(
                            connection,
                            shard,
                            syncFts = true,
                            shardKey = shardKeys.getValue(shard.shardId),
                        )
                    }
                    connection.createStatement().use { statement ->
                        // Increment in SQL rather than read/modify/write in the
                        // JVM. This single SQLite transaction, followed by the
                        // atomic file publish below, guarantees that a visible
                        // delta session carries exactly its new chain depth.
                        check(
                            statement.executeUpdate(
                                "UPDATE session_meta SET delta_depth = delta_depth + 1",
                            ) == 1,
                        ) { "session_meta must contain exactly one row" }
                        statement.execute("PRAGMA analysis_limit=400")
                        statement.execute("ANALYZE")
                    }
                }
            SessionFileLease.acquireExclusive(target).use {
                AtomicFiles.commit(temporary, target)
            }
        } finally {
            // No-op after a successful commit (the rename moved the file).
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * Seeds the delta temp file from the previous session. Copy-on-write
     * cloning matters twice at multi-GB sessions: a 20GB session takes ~10s
     * to copy byte-by-byte but clones in milliseconds, and a clone shares
     * every unmodified block with its base instead of doubling disk use.
     * NIO exposes no reflink, so this shells out to cp (`-c` = APFS
     * clonefile on macOS, `--reflink=auto` on GNU coreutils) and falls back
     * to a plain copy anywhere that fails — Windows, exotic filesystems, or
     * a missing cp.
     */
    private fun cloneOrCopy(source: Path, destination: Path) {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("mac") || os.contains("darwin") ->
                listOf("cp", "-c", source.toString(), destination.toString())
            os.contains("linux") ->
                listOf("cp", "--reflink=auto", source.toString(), destination.toString())
            else -> null
        }
        if (command != null) {
            try {
                Files.deleteIfExists(destination)
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                    .start()
                if (process.waitFor() == 0 && Files.isRegularFile(destination)) {
                    return
                }
            } catch (_: Exception) {
                // fall through to the byte copy
            }
        }
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Starts an incremental session build whose final name is not yet known:
     * the streaming index path merges shards as extraction completes and only
     * derives the fingerprint — hence the target file — from the shards that
     * actually merged. Callers must merge in sorted shardId order; that
     * ordering keeps the two full-build paths (streaming and [build])
     * byte-equivalent to each other for a given shard set. [buildDelta]
     * produces the same logical content with different row ids and physical
     * order. Always close the stream (close() discards the temporary file
     * unless commit() already renamed it into place).
     */
    fun openStream(sessionsDir: Path): Stream {
        val temporary = AtomicFiles.temporaryIn(sessionsDir, "session")
        try {
            Files.deleteIfExists(temporary)
            val connection = DriverManager.getConnection("jdbc:sqlite:${temporary.toAbsolutePath()}")
            try {
                configure(connection)
                createTables(connection)
                return Stream(temporary, connection)
            } catch (exception: Exception) {
                connection.close()
                throw exception
            }
        } catch (exception: Exception) {
            Files.deleteIfExists(temporary)
            throw exception
        }
    }

    inner class Stream internal constructor(
        private val temporary: Path,
        private val connection: Connection,
    ) : AutoCloseable {
        fun merge(shard: SessionShard) {
            this@SessionBuilder.merge(connection, shard)
        }

        fun commit(target: Path) {
            connection.createStatement().use { statement ->
                // External-content fts5 tables never see the merge inserts;
                // one rebuild per shadow scans its fully merged content table.
                statement.execute(
                    "INSERT INTO string_constants_fts(string_constants_fts) VALUES('rebuild')",
                )
                statement.execute(
                    "INSERT INTO classes_fts(classes_fts) VALUES('rebuild')",
                )
                statement.execute(
                    "INSERT INTO methods_fts(methods_fts) VALUES('rebuild')",
                )
            }
            createIndexes(connection)
            connection.createStatement().use { statement ->
                // Sampled ANALYZE: sqlite_stat1 selectivity estimates from a
                // few hundred rows per index steer the planner just as well
                // as a full scan of a multi-hundred-MB session.
                statement.execute("PRAGMA analysis_limit=400")
                statement.execute("ANALYZE")
            }
            connection.close()
            SessionFileLease.acquireExclusive(target).use {
                AtomicFiles.commit(temporary, target)
            }
        }

        override fun close() {
            try {
                connection.close()
            } finally {
                // No-op after a successful commit (the rename moved the file).
                Files.deleteIfExists(temporary)
            }
        }
    }

    internal fun isReusable(target: Path): Boolean {
        if (!Files.isRegularFile(target)) return false
        return try {
            SessionFileLease.acquireShared(target).use {
                isReusableWithoutLease(target)
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (_: Exception) {
            false
        }
    }

    private fun isReusableWithoutLease(target: Path): Boolean = try {
        DriverManager.getConnection("jdbc:sqlite:${target.toAbsolutePath()}")
            .use { connection -> validatedDeltaDepth(connection) != null }
    } catch (_: Exception) {
        false
    }

    /**
     * The authoritative shard set of a session, read from session_shards.
     * Null when the file is missing, unreadable, of another schema version,
     * or lacks the session_shards table — callers treat null as
     * delta-ineligible and rebuild fully. (The alternative source, a DISTINCT
     * source_shard_id union over every table, full-scans a multi-GB session;
     * session_shards makes this O(shard count).)
     */
    internal fun reusableShardSet(target: Path): Set<String>? {
        return reusableSessionState(target)?.shardIds
    }

    /**
     * The delta planner needs the shard set and chain depth from the same
     * validated snapshot. Reading them together avoids opening and checking a
     * multi-GB session twice on every changed-classpath run.
     */
    internal fun reusableSessionState(target: Path): ReusableSessionState? {
        if (!Files.isRegularFile(target)) return null
        return try {
            SessionFileLease.acquireShared(target).use {
                DriverManager.getConnection("jdbc:sqlite:${target.toAbsolutePath()}")
                    .use { connection ->
                        val deltaDepth = validatedDeltaDepth(connection) ?: return null
                        val shardIds = connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT shard_id FROM session_shards")
                                .use { rows ->
                                    buildSet {
                                        while (rows.next()) add(rows.getString(1))
                                    }
                                }
                        }
                        ReusableSessionState(shardIds, deltaDepth)
                    }
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (_: Exception) {
            null
        }
    }

    /** Returns the validated non-negative delta depth, or null on any contract miss. */
    private fun validatedDeltaDepth(connection: Connection): Int? {
        var deltaDepth = -1
        connection.createStatement().use { statement ->
            val currentVersion = statement.executeQuery(
                "SELECT session_schema_version, delta_depth FROM session_meta",
            ).use { rows ->
                if (!rows.next()) {
                    false
                } else {
                    val versionMatches = rows.getInt(1) == Research4JarVersions.SESSION
                    deltaDepth = rows.getInt(2)
                    val depthIsPresent = !rows.wasNull()
                    versionMatches && depthIsPresent && deltaDepth >= 0 && !rows.next()
                }
            }
            if (!currentVersion) return null

            val schema = statement.executeQuery(
                "SELECT name, type, sql FROM sqlite_schema",
            ).use { rows ->
                buildMap<String, Pair<String, String?>> {
                    while (rows.next()) {
                        put(rows.getString(1), rows.getString(2) to rows.getString(3))
                    }
                }
            }
            if (SESSION_TABLE_COLUMNS.keys.any { schema[it]?.first != "table" }) return null
            val searchSymbolView = schema["search_symbols"] ?: return null
            if (
                searchSymbolView.first != "view" ||
                normalizeSchemaSql(searchSymbolView.second ?: return null) !=
                normalizeSchemaSql(SEARCH_SYMBOL_VIEW_SQL)
            ) return null
            for ((name, expectedSql) in SESSION_FTS_CREATE_SQL) {
                val entry = schema[name] ?: return null
                if (entry.first != "table") return null
                val createSql = normalizeSchemaSql(entry.second ?: return null)
                if (createSql != normalizeSchemaSql(expectedSql)) return null
            }
            for ((name, fragment) in SESSION_INDEX_SQL_FRAGMENTS) {
                val entry = schema[name] ?: return null
                if (entry.first != "index") return null
                val createSql = normalizeSchemaSql(entry.second ?: return null)
                val qualifier = if (name == "idx_session_shards_key") "unique " else ""
                val expectedSql = normalizeSchemaSql(
                    "create ${qualifier}index $name $fragment",
                )
                if (createSql != expectedSql) return null
            }

            for ((table, columns) in SESSION_TABLE_COLUMNS) {
                val declaredColumns = statement.executeQuery("PRAGMA table_info('$table')")
                    .use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getString("name"))
                        }
                    }
                if (declaredColumns != columns) return null
                statement.executeQuery(
                    "SELECT ${columns.joinToString(", ")} FROM $table WHERE 0",
                ).use { rows ->
                    if (rows.next()) return null
                }
            }
            for ((table, requiredIntegerColumns) in SESSION_INTEGER_COLUMNS) {
                val declared = statement.executeQuery("PRAGMA table_info('$table')").use { rows ->
                    buildMap<String, Pair<String, Boolean>> {
                        while (rows.next()) {
                            put(
                                rows.getString("name"),
                                rows.getString("type").uppercase() to
                                    (rows.getInt("notnull") != 0),
                            )
                        }
                    }
                }
                if (
                    requiredIntegerColumns.any { column ->
                        declared[column] != ("INTEGER" to true)
                    }
                ) return null
            }
            var previousShardKey: Long? = null
            statement.executeQuery(
                "SELECT shard_key FROM session_shards ORDER BY shard_id",
            ).use { rows ->
                while (rows.next()) {
                    val key = rows.getLong(1)
                    if (rows.wasNull() || (previousShardKey != null && key <= previousShardKey!!)) {
                        return null
                    }
                    previousShardKey = key
                }
            }
            for ((table, columns) in SESSION_FTS_COLUMNS) {
                val declaredColumns = statement.executeQuery("PRAGMA table_info('$table')")
                    .use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getString("name"))
                        }
                    }
                if (declaredColumns != columns) return null
                statement.executeQuery(
                    "SELECT rowid, ${columns.joinToString(", ")} FROM $table WHERE 0",
                ).use { rows ->
                    if (rows.next()) return null
                }
            }
            statement.executeQuery(
                "SELECT ${SEARCH_SYMBOL_COLUMNS.joinToString(", ")} FROM search_symbols WHERE 0",
            ).use { rows ->
                if (rows.next()) return null
            }
        }
        return deltaDepth
    }

    /**
     * Normalizes formatting and unquoted SQL tokens without changing quoted
     * content. Applying lowercase()/a whitespace regex to the whole CREATE
     * statement would also rewrite string literals, allowing a semantically
     * different view (for example, `'CLASS'` instead of `'class'`) to pass the
     * reuse contract.
     */
    private fun normalizeSchemaSql(sql: String): String = buildString(sql.length) {
        var quoteEnd: Char? = null
        var pendingSpace = false
        var index = 0
        while (index < sql.length) {
            val character = sql[index]
            val activeQuoteEnd = quoteEnd
            if (activeQuoteEnd != null) {
                append(character)
                if (character == activeQuoteEnd) {
                    if (
                        activeQuoteEnd != ']' &&
                        index + 1 < sql.length &&
                        sql[index + 1] == activeQuoteEnd
                    ) {
                        append(sql[++index])
                    } else {
                        quoteEnd = null
                    }
                }
                index++
                continue
            }

            if (character.isWhitespace()) {
                pendingSpace = isNotEmpty()
                index++
                continue
            }
            if (pendingSpace) append(' ')
            pendingSpace = false
            when (character) {
                '\'', '"', '`' -> {
                    quoteEnd = character
                    append(character)
                }
                '[' -> {
                    quoteEnd = ']'
                    append(character)
                }
                else -> append(character.lowercaseChar())
            }
            index++
        }
    }

    private fun configure(connection: Connection) {
        connection.createStatement().use { statement ->
            // The temporary file is discarded on any failure and only becomes
            // visible through AtomicFiles.commit (fsync + atomic rename), so an
            // on-disk journal and per-statement syncs buy nothing during the
            // build. MEMORY keeps ROLLBACK working for the merge error path.
            statement.execute("PRAGMA journal_mode=MEMORY")
            statement.execute("PRAGMA synchronous=OFF")
        }
    }

    private fun createTables(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE session_meta (
                  session_schema_version INTEGER NOT NULL,
                  delta_depth INTEGER NOT NULL CHECK (delta_depth >= 0)
                )
                """.trimIndent(),
            )
            statement.execute(
                "INSERT INTO session_meta(session_schema_version, delta_depth) " +
                    "VALUES (${Research4JarVersions.SESSION}, 0)",
            )
            // One row per merged shard (~80 bytes each): the delta path diffs
            // this against the new shard set in O(shard count) instead of
            // scanning DISTINCT source_shard_id over every table. A shard is
            // recorded even when it contributed rows to no other table.
            statement.execute(
                """
                CREATE TABLE session_shards (
                  shard_id TEXT PRIMARY KEY,
                  shard_key INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // Created before any merge (rather than in createIndexes) so a
            // duplicate allocator result aborts the same shard transaction.
            statement.execute(
                "CREATE UNIQUE INDEX idx_session_shards_key ON session_shards(shard_key)",
            )
            // A shard's rows occupy a contiguous id range per table (the
            // single writer inserts each shard in one transaction, and SQLite
            // assigns max(rowid)+1). Recording the ranges at merge time turns
            // the delta deletion pass into indexed range deletes — the
            // alternative, DELETE ... WHERE source_shard_id IN (...), full-
            // scans every table and cost ~13s on a 20GB session. Ranges are
            // internal bookkeeping: they legitimately differ between a full
            // build and a delta build of the same shard set.
            statement.execute(
                """
                CREATE TABLE session_shard_ranges (
                  shard_id TEXT NOT NULL,
                  table_name TEXT NOT NULL,
                  min_id INTEGER NOT NULL,
                  max_id INTEGER NOT NULL,
                  PRIMARY KEY (shard_id, table_name)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE config_properties (
                  id INTEGER PRIMARY KEY,
                  prefix TEXT,
                  name TEXT NOT NULL,
                  type_fqn TEXT,
                  default_val TEXT,
                  description TEXT,
                  source_fqn TEXT,
                  source_shard_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE spi_registrations (
                  id INTEGER PRIMARY KEY,
                  mechanism TEXT NOT NULL,
                  key TEXT,
                  impl_fqn TEXT NOT NULL,
                  source_shard_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE classes (
                  id INTEGER PRIMARY KEY,
                  fqn TEXT NOT NULL,
                  kind TEXT,
                  super_fqn TEXT,
                  modifiers INTEGER,
                  is_abstract INTEGER,
                  source_file TEXT,
                  simple_name TEXT,
                  package_name TEXT,
                  source_shard_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE class_interfaces (
                  class_id INTEGER NOT NULL,
                  interface_fqn TEXT NOT NULL,
                  source_shard_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE annotations (
                  id INTEGER PRIMARY KEY,
                  target_kind TEXT NOT NULL,
                  target_id INTEGER NOT NULL,
                  annotation_fqn TEXT NOT NULL,
                  attributes TEXT,
                  source_shard_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE methods (
                  id INTEGER PRIMARY KEY,
                  class_id INTEGER NOT NULL,
                  name TEXT NOT NULL,
                  descriptor TEXT NOT NULL,
                  return_fqn TEXT,
                  modifiers INTEGER,
                  owner_resolved INTEGER NOT NULL,
                  source_shard_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE bean_definitions (
                  id INTEGER PRIMARY KEY,
                  config_fqn TEXT NOT NULL,
                  method_id INTEGER,
                  bean_type_fqn TEXT,
                  bean_name TEXT,
                  source_shard_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE conditions (
                  id INTEGER PRIMARY KEY,
                  target_kind TEXT NOT NULL,
                  target_id INTEGER NOT NULL,
                  type TEXT NOT NULL,
                  ref_value TEXT,
                  source_shard_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE string_constants (
                  id INTEGER PRIMARY KEY,
                  class_id INTEGER NOT NULL,
                  method_id INTEGER,
                  value TEXT NOT NULL,
                  source_shard_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // Trigram index for find-string's substring LIKE: fts5 pushes a
            // plain `value LIKE ?` down to the trigram doclists. External
            // content avoids duplicating the values; detail='none' plus
            // columnsize=0 drop positions and the docsize shadow table, and
            // both are verified to keep the LIKE pushdown on this SQLite
            // build (EXPLAIN QUERY PLAN shows INDEX 0:L0). The table indexes
            // nothing by itself — commit() runs the 'rebuild' command once
            // every shard has merged.
            statement.execute(SESSION_FTS_CREATE_SQL.getValue("string_constants_fts"))
            // The same trigram pushdown for search-symbol's contains fallback
            // over classes and methods: fts5 serves each column's own
            // `LIKE '%...%'` from the doclists (EXPLAIN QUERY PLAN shows
            // INDEX 0:L0 for the first column, 0:L1 for the second — verified
            // for all four columns on this SQLite build), so the cascade stops
            // scanning the two largest session tables. Indexing the compact
            // method name rather than its repeated owner#name symbol nearly
            // halves this shadow's build time and bytes; owner substrings use
            // classes_fts. Nullable class kinds index as absent. Like
            // string_constants_fts these index nothing until commit() runs
            // their 'rebuild'.
            statement.execute(SESSION_FTS_CREATE_SQL.getValue("classes_fts"))
            statement.execute(SESSION_FTS_CREATE_SQL.getValue("methods_fts"))
            // search_symbols is a view, not a copy: the materialized table
            // plus its four indexes were 65% of the session bytes while the
            // broad-search query could not use those indexes anyway
            // (leading-wildcard LIKE). The single source of truth for the
            // session layout since M6.
            statement.execute(SEARCH_SYMBOL_VIEW_SQL)
        }
    }

    private fun merge(
        connection: Connection,
        shard: SessionShard,
        syncFts: Boolean = false,
        shardKey: Long = allocateStreamingShardKey(connection, shard.shardId),
    ) {
        connection.prepareStatement("ATTACH DATABASE ? AS shard").use { statement ->
            statement.setString(1, shard.path.toAbsolutePath().normalize().toString())
            statement.execute()
        }
        try {
            connection.autoCommit = false
            val idOffsets = ID_TABLES.associateWith { table -> maxId(connection, table) }
            val classIdOffset = idOffsets.getValue("classes")
            val methodIdOffset = idOffsets.getValue("methods")
            val stringIdOffset = idOffsets.getValue("string_constants")
            // PRIMARY KEY doubles as the guard: merging a shard already in
            // the session aborts the transaction instead of duplicating rows.
            connection.prepareStatement(
                "INSERT INTO session_shards(shard_id, shard_key) VALUES (?, ?)",
            ).use { statement ->
                statement.setString(1, shard.shardId)
                statement.setLong(2, shardKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO config_properties(
                  prefix, name, type_fqn, default_val, description, source_fqn, source_shard_id
                )
                SELECT prefix, name, type_fqn, default_val, description, source_fqn, ?
                FROM shard.config_properties
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, shardKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO spi_registrations(mechanism, key, impl_fqn, source_shard_id)
                SELECT mechanism, key, impl_fqn, ?
                FROM shard.spi_registrations
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, shardKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO classes(
                  fqn, kind, super_fqn, modifiers, is_abstract, source_file,
                  simple_name, package_name, source_shard_id
                )
                SELECT fqn, kind, super_fqn, modifiers, is_abstract, source_file,
                  $SIMPLE_NAME_EXPR, $PACKAGE_NAME_EXPR, ?
                FROM shard.classes
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, shardKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO class_interfaces(class_id, interface_fqn, source_shard_id)
                SELECT class_id + ?, interface_fqn, ?
                FROM shard.class_interfaces
                ORDER BY class_id, interface_fqn
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, classIdOffset)
                statement.setLong(2, shardKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO methods(
                  class_id, name, descriptor, return_fqn, modifiers,
                  owner_resolved, source_shard_id
                )
                SELECT m.class_id + ?, m.name, m.descriptor, m.return_fqn, m.modifiers,
                  CASE WHEN c.id IS NULL THEN 0 ELSE 1 END, ?
                FROM shard.methods m
                LEFT JOIN shard.classes c ON c.id = m.class_id
                ORDER BY m.id
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, classIdOffset)
                statement.setLong(2, shardKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO annotations(
                  target_kind, target_id, annotation_fqn, attributes, source_shard_id
                )
                SELECT
                  target_kind,
                  target_id + CASE target_kind WHEN 'class' THEN ? ELSE ? END,
                  annotation_fqn, attributes, ?
                FROM shard.annotations
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, classIdOffset)
                statement.setInt(2, methodIdOffset)
                statement.setLong(3, shardKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO bean_definitions(
                  config_fqn, method_id, bean_type_fqn, bean_name, source_shard_id
                )
                SELECT config_fqn, method_id + ?, bean_type_fqn, bean_name, ?
                FROM shard.bean_definitions
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, methodIdOffset)
                statement.setLong(2, shardKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO conditions(target_kind, target_id, type, ref_value, source_shard_id)
                SELECT
                  target_kind,
                  target_id + CASE target_kind WHEN 'class' THEN ? ELSE ? END,
                  type, ref_value, ?
                FROM shard.conditions
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, classIdOffset)
                statement.setInt(2, methodIdOffset)
                statement.setLong(3, shardKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO string_constants(class_id, method_id, value, source_shard_id)
                SELECT class_id + ?, method_id + ?, value, ?
                FROM shard.string_constants
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, classIdOffset)
                statement.setInt(2, methodIdOffset)
                statement.setLong(3, shardKey)
                statement.executeUpdate()
            }
            // Full builds skip this: Stream.commit rebuilds each whole shadow
            // in one pass over the merged content, which costs the same
            // tokenize work once instead of per shard. The delta path has no
            // rebuild, so it mirrors each addition here (deletions are
            // mirrored in deleteShards). Each shard's rows are exactly the
            // contiguous id range above the offset captured before its merge.
            if (syncFts) {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO string_constants_fts(rowid, value) " +
                            "SELECT id, value FROM string_constants WHERE id > $stringIdOffset",
                    )
                    statement.executeUpdate(
                        "INSERT INTO classes_fts(rowid, fqn, kind) " +
                            "SELECT id, fqn, kind FROM classes WHERE id > $classIdOffset",
                    )
                    statement.executeUpdate(
                        "INSERT INTO methods_fts(rowid, name, descriptor) " +
                            "SELECT id, name, descriptor FROM methods WHERE id > $methodIdOffset",
                    )
                }
            }
            connection.prepareStatement(
                "INSERT INTO session_shard_ranges(shard_id, table_name, min_id, max_id) " +
                    "VALUES (?, ?, ?, ?)",
            ).use { statement ->
                for (table in ID_TABLES) {
                    val newMax = maxId(connection, table)
                    val offset = idOffsets.getValue(table)
                    if (newMax > offset) {
                        statement.setString(1, shard.shardId)
                        statement.setString(2, table)
                        statement.setInt(3, offset + 1)
                        statement.setInt(4, newMax)
                        statement.executeUpdate()
                    }
                }
            }
            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
            connection.createStatement().use { it.execute("DETACH DATABASE shard") }
        }
    }

    /**
     * The streaming full-build path merges hashes in lexical order, so the
     * common append case uses a wide fixed stride. The midpoint branches are
     * defensive for direct Stream users that insert out of order; they retain
     * the same order invariant or fail before any shard row is written.
     */
    private fun allocateStreamingShardKey(connection: Connection, shardId: String): Long {
        var lower: Long? = null
        var upper: Long? = null
        connection.prepareStatement(
            "SELECT shard_key FROM session_shards WHERE shard_id < ? " +
                "ORDER BY shard_id DESC LIMIT 1",
        ).use { statement ->
            statement.setString(1, shardId)
            statement.executeQuery().use { rows ->
                if (rows.next()) lower = rows.getLong(1)
            }
        }
        connection.prepareStatement(
            "SELECT shard_key FROM session_shards WHERE shard_id > ? " +
                "ORDER BY shard_id LIMIT 1",
        ).use { statement ->
            statement.setString(1, shardId)
            statement.executeQuery().use { rows ->
                if (rows.next()) upper = rows.getLong(1)
            }
        }
        return when {
            lower == null && upper == null -> 0L
            lower != null && upper == null && lower!! <= Long.MAX_VALUE - SHARD_KEY_STRIDE ->
                lower!! + SHARD_KEY_STRIDE
            lower == null && upper != null && upper!! >= Long.MIN_VALUE + SHARD_KEY_STRIDE ->
                upper!! - SHARD_KEY_STRIDE
            else -> strictMidpoint(lower ?: Long.MIN_VALUE, upper ?: Long.MAX_VALUE, shardId)
        }
    }

    /**
     * Allocate every shard added by one delta as a batch. For additions that
     * share the same two surviving neighbours this divides the available key
     * interval evenly, instead of repeatedly halving one side and exhausting
     * a gap after only log2(stride) inserts. A later generation can still
     * midpoint any resulting gap; the eight-generation delta cap leaves a
     * large safety margin. Failure is deliberate: the caller discards this
     * delta and performs a full rebuild with fresh wide gaps.
     */
    private fun allocateDeltaShardKeys(
        connection: Connection,
        addedShardIds: List<String>,
    ): Map<String, Long> {
        if (addedShardIds.isEmpty()) return emptyMap()
        val added = addedShardIds.sorted()
        require(added.zipWithNext().none { (left, right) -> left == right }) {
            "delta contains a duplicate shard id"
        }
        val existing = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT shard_id, shard_key FROM session_shards ORDER BY shard_id",
            ).use { rows ->
                buildList {
                    var previousKey: Long? = null
                    while (rows.next()) {
                        val id = rows.getString(1)
                        val key = rows.getLong(2)
                        if (rows.wasNull() || (previousKey != null && key <= previousKey!!)) {
                            throw ShardKeySpaceExhaustedException(
                                "session shard keys are not strictly ordered",
                            )
                        }
                        add(id to key)
                        previousKey = key
                    }
                }
            }
        }
        val allocated = LinkedHashMap<String, Long>(added.size)
        var existingIndex = 0
        var addedIndex = 0
        while (addedIndex < added.size) {
            val first = added[addedIndex]
            while (existingIndex < existing.size && existing[existingIndex].first < first) {
                existingIndex++
            }
            if (existingIndex < existing.size && existing[existingIndex].first == first) {
                throw IllegalArgumentException("shard already exists in session: $first")
            }
            val upperId = existing.getOrNull(existingIndex)?.first
            var end = addedIndex + 1
            while (end < added.size && (upperId == null || added[end] < upperId)) {
                end++
            }
            val group = added.subList(addedIndex, end)
            val keys = evenlySpacedKeys(
                lower = existing.getOrNull(existingIndex - 1)?.second,
                upper = existing.getOrNull(existingIndex)?.second,
                count = group.size,
                label = "${group.first()}..${group.last()}",
            )
            group.forEachIndexed { index, id -> allocated[id] = keys[index] }
            addedIndex = end
        }
        return allocated
    }

    private fun evenlySpacedKeys(
        lower: Long?,
        upper: Long?,
        count: Int,
        label: String,
    ): List<Long> {
        // Unbounded head/tail groups do not need to consume the entire signed
        // range. Keep the ordinary full-build stride whenever it fits: a tail
        // key near Long.MAX takes nine bytes in every fact row, versus three
        // or four bytes for the compact continuation used by a full build.
        if (upper == null) {
            val first = lower?.let {
                BigInteger.valueOf(it).add(BigInteger.valueOf(SHARD_KEY_STRIDE))
            } ?: BigInteger.ZERO
            val last = first.add(
                BigInteger.valueOf(SHARD_KEY_STRIDE)
                    .multiply(BigInteger.valueOf(count.toLong() - 1L)),
            )
            if (last <= LONG_MAX_BIG) {
                return List(count) { index ->
                    first.add(
                        BigInteger.valueOf(SHARD_KEY_STRIDE)
                            .multiply(BigInteger.valueOf(index.toLong())),
                    ).longValueExact()
                }
            }
        } else if (lower == null) {
            val first = BigInteger.valueOf(upper)
                .subtract(
                    BigInteger.valueOf(SHARD_KEY_STRIDE)
                        .multiply(BigInteger.valueOf(count.toLong())),
                )
            if (first >= LONG_MIN_BIG) {
                return List(count) { index ->
                    first.add(
                        BigInteger.valueOf(SHARD_KEY_STRIDE)
                            .multiply(BigInteger.valueOf(index.toLong())),
                    ).longValueExact()
                }
            }
        }

        val lowerExclusive = lower?.let { BigInteger.valueOf(it) }
            ?: LONG_MIN_BIG.subtract(BigInteger.ONE)
        val upperExclusive = upper?.let { BigInteger.valueOf(it) }
            ?: LONG_MAX_BIG.add(BigInteger.ONE)
        val distance = upperExclusive.subtract(lowerExclusive)
        val capacity = distance.subtract(BigInteger.ONE)
        if (capacity < BigInteger.valueOf(count.toLong())) {
            throw ShardKeySpaceExhaustedException(
                "no ordered shard-key gap for $label ($count additions)",
            )
        }
        val divisor = BigInteger.valueOf(count.toLong() + 1L)
        var previous = lowerExclusive
        return List(count) { index ->
            val key = lowerExclusive.add(
                distance.multiply(BigInteger.valueOf(index.toLong() + 1L)).divide(divisor),
            )
            if (key <= previous || key >= upperExclusive) {
                throw ShardKeySpaceExhaustedException("no ordered shard-key gap for $label")
            }
            previous = key
            key.longValueExact()
        }
    }

    private fun strictMidpoint(lower: Long, upper: Long, shardId: String): Long {
        val midpoint = (lower and upper) + ((lower xor upper) shr 1)
        if (midpoint <= lower || midpoint >= upper) {
            throw ShardKeySpaceExhaustedException(
                "no ordered shard-key gap around $shardId",
            )
        }
        return midpoint
    }

    /**
     * Range-based deletion for the delta path: each removed shard's rows are
     * deleted through the contiguous id ranges recorded at merge time —
     * indexed range deletes instead of DELETE...WHERE source_shard_id IN,
     * which full-scanned every table (~13s on a 20GB session for a 3-shard
     * removal). A shard absent from session_shard_ranges contributed no rows
     * (session_shards records it regardless). The id holes deletions leave
     * are why delta sessions are logically — not byte — equivalent to full
     * builds.
     */
    private fun deleteShards(connection: Connection, shardIds: Collection<String>) {
        if (shardIds.isEmpty()) return
        try {
            connection.autoCommit = false
            val rangesByShard = HashMap<String, MutableMap<String, IntRange>>()
            connection.prepareStatement(
                "SELECT table_name, min_id, max_id FROM session_shard_ranges WHERE shard_id = ?",
            ).use { statement ->
                for (shardId in shardIds) {
                    statement.setString(1, shardId)
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            rangesByShard.getOrPut(shardId) { HashMap() }[rows.getString(1)] =
                                rows.getInt(2)..rows.getInt(3)
                        }
                    }
                }
            }
            connection.createStatement().use { statement ->
                for (shardId in shardIds) {
                    val ranges = rangesByShard[shardId] ?: continue
                    // External-content FTS5 rows must be removed while the
                    // doomed content rows are still readable — the 'delete'
                    // command needs the exact indexed values.
                    ranges["string_constants"]?.let { range ->
                        statement.executeUpdate(
                            "INSERT INTO string_constants_fts(string_constants_fts, rowid, value) " +
                                "SELECT 'delete', id, value FROM string_constants " +
                                "WHERE id BETWEEN ${range.first} AND ${range.last}",
                        )
                    }
                    ranges["classes"]?.let { range ->
                        statement.executeUpdate(
                            "INSERT INTO classes_fts(classes_fts, rowid, fqn, kind) " +
                                "SELECT 'delete', id, fqn, kind FROM classes " +
                                "WHERE id BETWEEN ${range.first} AND ${range.last}",
                        )
                    }
                    ranges["methods"]?.let { range ->
                        statement.executeUpdate(
                            "INSERT INTO methods_fts(methods_fts, rowid, name, descriptor) " +
                                "SELECT 'delete', id, name, descriptor FROM methods " +
                                "WHERE id BETWEEN ${range.first} AND ${range.last}",
                        )
                    }
                    // class_interfaces has no id of its own; its rows follow
                    // the shard's classes range via the class_id index.
                    ranges["classes"]?.let { range ->
                        statement.executeUpdate(
                            "DELETE FROM class_interfaces " +
                                "WHERE class_id BETWEEN ${range.first} AND ${range.last}",
                        )
                    }
                    for (table in ID_TABLES) {
                        ranges[table]?.let { range ->
                            statement.executeUpdate(
                                "DELETE FROM $table WHERE id BETWEEN ${range.first} AND ${range.last}",
                            )
                        }
                    }
                }
            }
            connection.prepareStatement("DELETE FROM session_shards WHERE shard_id = ?")
                .use { statement ->
                    shardIds.forEach { shardId ->
                        statement.setString(1, shardId)
                        statement.executeUpdate()
                    }
                }
            connection.prepareStatement("DELETE FROM session_shard_ranges WHERE shard_id = ?")
                .use { statement ->
                    shardIds.forEach { shardId ->
                        statement.setString(1, shardId)
                        statement.executeUpdate()
                    }
                }
            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    private fun maxId(connection: Connection, table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun createIndexes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE INDEX idx_s_cfg_prefix ON config_properties(prefix)")
            statement.execute("CREATE INDEX idx_s_cfg_name ON config_properties(name)")
            statement.execute("CREATE INDEX idx_s_spi_mech ON spi_registrations(mechanism)")
            statement.execute("CREATE INDEX idx_s_spi_key ON spi_registrations(key)")
            statement.execute("CREATE INDEX idx_s_classes_fqn ON classes(fqn)")
            statement.execute("CREATE INDEX idx_s_classes_simple ON classes(simple_name)")
            statement.execute("CREATE INDEX idx_s_classes_package ON classes(package_name)")
            statement.execute("CREATE INDEX idx_s_classes_super ON classes(super_fqn)")
            statement.execute("CREATE INDEX idx_s_ci_iface ON class_interfaces(interface_fqn)")
            statement.execute("CREATE INDEX idx_s_ci_class ON class_interfaces(class_id)")
            statement.execute(
                "CREATE INDEX idx_s_ann_fqn ON annotations(annotation_fqn)",
            )
            statement.execute(
                "CREATE INDEX idx_s_ann_target ON annotations(target_kind, target_id)",
            )
            statement.execute("CREATE INDEX idx_s_methods_class ON methods(class_id)")
            statement.execute("CREATE INDEX idx_s_methods_name ON methods(name)")
            // Corrupt shards can carry a method whose class is absent, hence
            // an unresolved (formerly NULL-symbol) owner. The index keeps the tiny compatibility
            // branch for its original name-prefix semantics O(orphan count).
            statement.execute(
                "CREATE INDEX idx_s_methods_orphan ON methods(id) WHERE owner_resolved = 0",
            )
            statement.execute("CREATE INDEX idx_s_bean_type ON bean_definitions(bean_type_fqn)")
            statement.execute("CREATE INDEX idx_s_bean_cfg ON bean_definitions(config_fqn)")
            statement.execute(
                "CREATE INDEX idx_s_cond_target ON conditions(target_kind, target_id)",
            )
            statement.execute("CREATE INDEX idx_s_strconst_value ON string_constants(value)")
            statement.execute("CREATE INDEX idx_s_strconst_class ON string_constants(class_id)")
            // search-symbol's string-detail FTS arms start from a matching
            // method and need the strings attached to that exact method. A
            // class_id-only probe rescans every string in a large class once
            // per matching method (quadratic for generated mega-classes).
            // Keep class-level constants out of this index and include the
            // class id so the invariant-checking join is covered as well.
            statement.execute(
                "CREATE INDEX idx_s_strconst_method ON " +
                    "string_constants(method_id, class_id) WHERE method_id IS NOT NULL",
            )
            statement.execute("CREATE INDEX idx_s_spi_impl ON spi_registrations(impl_fqn)")
        }
    }
}
