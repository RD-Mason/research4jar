package dev.research4jar.indexer.store

import dev.research4jar.indexer.Research4JarVersions
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
 * dangling references. The string_constants FTS5 shadow is kept in sync at
 * two sites — one statement before the string_constants delete in
 * [SessionBuilder.deleteShards], one after the string_constants INSERT in
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
        if (isReusable(target)) {
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
            return
        }
        build(target, shards)
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
            cloneOrCopy(previous, temporary)
            DriverManager.getConnection("jdbc:sqlite:${temporary.toAbsolutePath()}")
                .use { connection ->
                    configure(connection)
                    deleteShards(connection, removedShardIds)
                    addedShards.sortedBy(SessionShard::shardId)
                        .forEach { shard -> merge(connection, shard, syncFts = true) }
                    connection.createStatement().use { statement ->
                        statement.execute("PRAGMA analysis_limit=400")
                        statement.execute("ANALYZE")
                    }
                }
            AtomicFiles.commit(temporary, target)
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
                // one rebuild scans the fully merged string_constants.
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
            AtomicFiles.commit(temporary, target)
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
            DriverManager.getConnection("jdbc:sqlite:${target.toAbsolutePath()}")
                .use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT session_schema_version FROM session_meta",
                        ).use { rows ->
                            rows.next() && rows.getInt(1) == Research4JarVersions.SESSION
                        }
                    }
                }
        } catch (_: Exception) {
            false
        }
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
        if (!Files.isRegularFile(target)) return null
        return try {
            DriverManager.getConnection("jdbc:sqlite:${target.toAbsolutePath()}")
                .use { connection ->
                    connection.createStatement().use { statement ->
                        val current = statement.executeQuery(
                            "SELECT session_schema_version FROM session_meta",
                        ).use { rows ->
                            rows.next() && rows.getInt(1) == Research4JarVersions.SESSION
                        }
                        if (!current) return null
                        statement.executeQuery("SELECT shard_id FROM session_shards")
                            .use { rows ->
                                buildSet {
                                    while (rows.next()) add(rows.getString(1))
                                }
                            }
                    }
                }
        } catch (_: Exception) {
            null
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
                  session_schema_version INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                "INSERT INTO session_meta(session_schema_version) VALUES (${Research4JarVersions.SESSION})",
            )
            // One row per merged shard (~80 bytes each): the delta path diffs
            // this against the new shard set in O(shard count) instead of
            // scanning DISTINCT source_shard_id over every table. A shard is
            // recorded even when it contributed rows to no other table.
            statement.execute(
                """
                CREATE TABLE session_shards (
                  shard_id TEXT PRIMARY KEY
                )
                """.trimIndent(),
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
                  source_shard_id TEXT NOT NULL
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
                  source_shard_id TEXT NOT NULL
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
                  source_shard_id TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE class_interfaces (
                  class_id INTEGER NOT NULL,
                  interface_fqn TEXT NOT NULL,
                  source_shard_id TEXT NOT NULL
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
                  source_shard_id TEXT NOT NULL
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
                  symbol TEXT,
                  source_shard_id TEXT NOT NULL
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
                  source_shard_id TEXT NOT NULL
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
                  source_shard_id TEXT NOT NULL
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
                  source_shard_id TEXT NOT NULL
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
            statement.execute(
                "CREATE VIRTUAL TABLE string_constants_fts USING fts5(" +
                    "value, content='string_constants', content_rowid='id', " +
                    "tokenize='trigram', detail='none', columnsize=0)",
            )
            // Trigram indexes for the symbol-search contains fallbacks
            // (find-class/find-method/search-symbol), same mechanics as
            // string_constants_fts above. DDL is the frozen contract shared
            // with the build-side work; keep it verbatim.
            statement.execute(
                "CREATE VIRTUAL TABLE classes_fts USING fts5(fqn, kind, " +
                    "content='classes', content_rowid='id', " +
                    "tokenize='trigram', detail='none', columnsize=0)",
            )
            statement.execute(
                "CREATE VIRTUAL TABLE methods_fts USING fts5(symbol, descriptor, " +
                    "content='methods', content_rowid='id', " +
                    "tokenize='trigram', detail='none', columnsize=0)",
            )
            // search_symbols is a view, not a copy: the materialized table
            // plus its four indexes were 65% of the session bytes while the
            // broad-search query could not use those indexes anyway
            // (leading-wildcard LIKE). The single source of truth for the
            // session layout since M6.
            statement.execute(
                """
                CREATE VIEW search_symbols AS
                SELECT 'class' AS kind, fqn AS name, NULL AS owner, kind AS detail,
                       source_shard_id, simple_name, package_name, 55 AS score_hint
                FROM classes
                UNION ALL
                SELECT 'method', m.symbol, c.fqn, m.descriptor, m.source_shard_id,
                       m.name, c.package_name, 50
                FROM methods m JOIN classes c ON c.id = m.class_id
                UNION ALL
                SELECT 'annotation', a.annotation_fqn,
                       CASE WHEN a.target_kind = 'class' THEN c.fqn
                            WHEN a.target_kind = 'method' THEN m.symbol
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
                """.trimIndent(),
            )
        }
    }

    private fun merge(connection: Connection, shard: SessionShard, syncFts: Boolean = false) {
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
                "INSERT INTO session_shards(shard_id) VALUES (?)",
            ).use { statement ->
                statement.setString(1, shard.shardId)
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
                statement.setString(1, shard.shardId)
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
                statement.setString(1, shard.shardId)
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
                statement.setString(1, shard.shardId)
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
                statement.setString(2, shard.shardId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO methods(class_id, name, descriptor, return_fqn, modifiers, symbol, source_shard_id)
                SELECT m.class_id + ?, m.name, m.descriptor, m.return_fqn, m.modifiers,
                  c.fqn || '#' || m.name, ?
                FROM shard.methods m
                LEFT JOIN shard.classes c ON c.id = m.class_id
                ORDER BY m.id
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, classIdOffset)
                statement.setString(2, shard.shardId)
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
                statement.setString(3, shard.shardId)
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
                statement.setString(2, shard.shardId)
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
                statement.setString(3, shard.shardId)
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
                statement.setString(3, shard.shardId)
                statement.executeUpdate()
            }
            // Full builds skip this: Stream.commit rebuilds the whole shadow
            // in one pass over the merged content, which costs the same
            // tokenize work once instead of per shard. The delta path has no
            // rebuild, so it mirrors each addition here (deletions are
            // mirrored in deleteShards).
            if (syncFts) {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO string_constants_fts(rowid, value) " +
                            "SELECT id, value FROM string_constants WHERE id > $stringIdOffset",
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
            statement.execute("CREATE INDEX idx_s_methods_symbol ON methods(symbol)")
            // ~Empty partial index: real merges never leave symbol NULL, but
            // the FTS-served fallbacks re-run their legacy arms over exactly
            // these rows (methods_fts indexes a NULL symbol as '').
            statement.execute(
                "CREATE INDEX idx_s_methods_orphan ON methods(id) WHERE symbol IS NULL",
            )
            statement.execute("CREATE INDEX idx_s_bean_type ON bean_definitions(bean_type_fqn)")
            statement.execute("CREATE INDEX idx_s_bean_cfg ON bean_definitions(config_fqn)")
            statement.execute(
                "CREATE INDEX idx_s_cond_target ON conditions(target_kind, target_id)",
            )
            statement.execute("CREATE INDEX idx_s_strconst_value ON string_constants(value)")
            statement.execute("CREATE INDEX idx_s_strconst_class ON string_constants(class_id)")
            statement.execute("CREATE INDEX idx_s_spi_impl ON spi_registrations(impl_fqn)")
        }
    }
}
