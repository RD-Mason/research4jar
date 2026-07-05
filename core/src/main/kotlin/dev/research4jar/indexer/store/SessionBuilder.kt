package dev.research4jar.indexer.store

import dev.research4jar.indexer.Research4JarVersions
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

data class SessionShard(
    val shardId: String,
    val path: Path,
)

class SessionBuilder {
    /**
     * Sessions are content-addressed by the classpath fingerprint in the file
     * name, so an existing structurally-sound session of the current layout
     * version is byte-equivalent to whatever a rebuild would produce and can
     * be reused as-is. Sessions written by older binaries fail the version
     * check and are rebuilt.
     */
    fun buildIfAbsent(target: Path, shards: List<SessionShard>) {
        if (isReusable(target)) return
        build(target, shards)
    }

    fun build(target: Path, shards: List<SessionShard>) {
        val temporary = AtomicFiles.temporaryTarget(target)
        try {
            Files.deleteIfExists(temporary)
            DriverManager.getConnection("jdbc:sqlite:${temporary.toAbsolutePath()}").use { connection ->
                configure(connection)
                createTables(connection)
                shards.sortedBy(SessionShard::shardId).forEach { merge(connection, it) }
                populateDerivedColumns(connection)
                createIndexes(connection)
                connection.createStatement().use { it.execute("ANALYZE") }
            }
            AtomicFiles.commit(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun isReusable(target: Path): Boolean {
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

    private fun merge(connection: Connection, shard: SessionShard) {
        connection.prepareStatement("ATTACH DATABASE ? AS shard").use { statement ->
            statement.setString(1, shard.path.toAbsolutePath().normalize().toString())
            statement.execute()
        }
        try {
            connection.autoCommit = false
            val classIdOffset = maxId(connection, "classes")
            val methodIdOffset = maxId(connection, "methods")
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
                  fqn, kind, super_fqn, modifiers, is_abstract, source_file, source_shard_id
                )
                SELECT fqn, kind, super_fqn, modifiers, is_abstract, source_file, ?
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
                INSERT INTO methods(class_id, name, descriptor, return_fqn, modifiers, source_shard_id)
                SELECT class_id + ?, name, descriptor, return_fqn, modifiers, ?
                FROM shard.methods
                ORDER BY id
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
            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
            connection.createStatement().use { it.execute("DETACH DATABASE shard") }
        }
    }

    private fun maxId(connection: Connection, table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /**
     * Fills classes.simple_name/package_name and methods.symbol with
     * set-based SQL (was ~620k row-by-row UPDATEs on a large classpath).
     * The rtrim trick is exact lastIndexOf('.') semantics: rtrim's second
     * argument is a character SET, so trimming every non-dot character
     * strips exactly the rightmost run of non-dot characters, leaving the
     * prefix up to and including the last dot. Must stay identical to the
     * single session writer since M6.
     */
    private fun populateDerivedColumns(connection: Connection) {
        withTransaction(connection) {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    UPDATE classes SET
                      simple_name = CASE
                        WHEN instr(fqn, '.') = 0 THEN fqn
                        ELSE substr(fqn, length(rtrim(fqn, replace(fqn, '.', ''))) + 1)
                      END,
                      package_name = CASE
                        WHEN instr(fqn, '.') = 0 THEN ''
                        ELSE substr(fqn, 1, length(rtrim(fqn, replace(fqn, '.', ''))) - 1)
                      END
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    UPDATE methods SET symbol = (
                      SELECT c.fqn FROM classes c WHERE c.id = methods.class_id
                    ) || '#' || name
                    """.trimIndent(),
                )
            }
        }
    }

    private fun withTransaction(connection: Connection, block: () -> Unit) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = previousAutoCommit
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
