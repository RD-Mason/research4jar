package dev.springdep.indexer.store

import dev.springdep.indexer.SpringDepVersions
import dev.springdep.indexer.extract.ExtractedJar
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

private data class MethodKey(
    val classFqn: String,
    val name: String,
    val descriptor: String,
)

class ShardWriter {
    fun write(target: Path, jarSha256: String, extracted: ExtractedJar) {
        val temporary = AtomicFiles.temporaryTarget(target)
        try {
            Files.deleteIfExists(temporary)
            DriverManager.getConnection("jdbc:sqlite:${temporary.toAbsolutePath()}").use { connection ->
                configure(connection)
                createSchema(connection)
                connection.autoCommit = false
                try {
                    val classIds = insertClasses(connection, extracted)
                    val methodIds = insertMethods(connection, extracted, classIds)
                    insertMetadata(connection, jarSha256, extracted)
                    insertSpiRegistrations(connection, extracted)
                    insertConfigProperties(connection, extracted)
                    insertAnnotations(connection, extracted, classIds, methodIds)
                    insertBeanDefinitions(connection, extracted, methodIds)
                    insertConditions(connection, extracted, classIds, methodIds)
                    insertStringConstants(connection, extracted, classIds, methodIds)
                    connection.commit()
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                }
            }
            AtomicFiles.commit(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun configure(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA page_size=4096")
            statement.execute("PRAGMA journal_mode=DELETE")
            statement.execute("PRAGMA synchronous=FULL")
            statement.execute("PRAGMA foreign_keys=ON")
        }
    }

    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE shard_meta (
                  jar_coordinate TEXT,
                  jar_sha256 TEXT NOT NULL,
                  extractor_version INTEGER NOT NULL,
                  schema_version INTEGER NOT NULL,
                  created_at INTEGER,
                  class_count INTEGER
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE spi_registrations (
                  id INTEGER PRIMARY KEY,
                  mechanism TEXT NOT NULL,
                  key TEXT,
                  impl_fqn TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX idx_spi_key ON spi_registrations(key)")
            statement.execute("CREATE INDEX idx_spi_mech ON spi_registrations(mechanism)")
            statement.execute(
                """
                CREATE TABLE config_properties (
                  id INTEGER PRIMARY KEY,
                  prefix TEXT,
                  name TEXT NOT NULL,
                  type_fqn TEXT,
                  default_val TEXT,
                  description TEXT,
                  source_fqn TEXT
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX idx_cfg_prefix ON config_properties(prefix)")
            statement.execute("CREATE INDEX idx_cfg_name ON config_properties(name)")
            statement.execute(
                """
                CREATE TABLE classes (
                  id INTEGER PRIMARY KEY,
                  fqn TEXT NOT NULL,
                  kind TEXT,
                  super_fqn TEXT,
                  modifiers INTEGER,
                  is_abstract INTEGER,
                  source_file TEXT
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX idx_classes_fqn ON classes(fqn)")
            statement.execute("CREATE INDEX idx_classes_super ON classes(super_fqn)")
            statement.execute(
                """
                CREATE TABLE class_interfaces (
                  class_id INTEGER NOT NULL,
                  interface_fqn TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX idx_ci_iface ON class_interfaces(interface_fqn)")
            statement.execute("CREATE INDEX idx_ci_class ON class_interfaces(class_id)")
            statement.execute(
                """
                CREATE TABLE methods (
                  id INTEGER PRIMARY KEY,
                  class_id INTEGER NOT NULL,
                  name TEXT NOT NULL,
                  descriptor TEXT NOT NULL,
                  return_fqn TEXT,
                  modifiers INTEGER
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX idx_methods_class ON methods(class_id)")
            statement.execute(
                """
                CREATE TABLE annotations (
                  id INTEGER PRIMARY KEY,
                  target_kind TEXT NOT NULL,
                  target_id INTEGER NOT NULL,
                  annotation_fqn TEXT NOT NULL,
                  attributes TEXT
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX idx_ann_fqn ON annotations(annotation_fqn)")
            statement.execute(
                "CREATE INDEX idx_ann_target ON annotations(target_kind, target_id)",
            )
            statement.execute(
                """
                CREATE TABLE bean_definitions (
                  id INTEGER PRIMARY KEY,
                  config_fqn TEXT NOT NULL,
                  method_id INTEGER,
                  bean_type_fqn TEXT,
                  bean_name TEXT
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX idx_bean_type ON bean_definitions(bean_type_fqn)")
            statement.execute("CREATE INDEX idx_bean_cfg ON bean_definitions(config_fqn)")
            statement.execute(
                """
                CREATE TABLE conditions (
                  id INTEGER PRIMARY KEY,
                  target_kind TEXT NOT NULL,
                  target_id INTEGER NOT NULL,
                  type TEXT NOT NULL,
                  ref_value TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX idx_cond_target ON conditions(target_kind, target_id)",
            )
            statement.execute(
                """
                CREATE TABLE string_constants (
                  id INTEGER PRIMARY KEY,
                  class_id INTEGER NOT NULL,
                  method_id INTEGER,
                  value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX idx_strconst_class ON string_constants(class_id)")
        }
    }

    private fun insertMetadata(connection: Connection, jarSha256: String, extracted: ExtractedJar) {
        connection.prepareStatement(
            """
            INSERT INTO shard_meta(
              jar_coordinate, jar_sha256, extractor_version, schema_version, created_at, class_count
            ) VALUES (?, ?, ?, ?, 0, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setNullableString(1, extracted.coordinate)
            statement.setString(2, jarSha256)
            statement.setInt(3, SpringDepVersions.EXTRACTOR)
            statement.setInt(4, SpringDepVersions.SCHEMA)
            statement.setInt(5, extracted.classes.size)
            statement.executeUpdate()
        }
    }

    private fun insertClasses(
        connection: Connection,
        extracted: ExtractedJar,
    ): Map<String, Int> {
        val sorted = extracted.classes.sortedBy { it.fqn }
        connection.prepareStatement(
            """
            INSERT INTO classes(id, fqn, kind, super_fqn, modifiers, is_abstract, source_file)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            sorted.forEachIndexed { index, item ->
                statement.setInt(1, index + 1)
                statement.setString(2, item.fqn)
                statement.setString(3, item.kind)
                statement.setNullableString(4, item.superFqn)
                statement.setInt(5, item.modifiers)
                statement.setInt(6, if (item.isAbstract) 1 else 0)
                statement.setNullableString(7, item.sourceFile)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        val classIds = sorted.mapIndexed { index, item -> item.fqn to index + 1 }.toMap()
        connection.prepareStatement(
            "INSERT INTO class_interfaces(class_id, interface_fqn) VALUES (?, ?)",
        ).use { statement ->
            sorted.forEach { item ->
                val classId = classIds.getValue(item.fqn)
                item.interfaces.sorted().forEach { interfaceFqn ->
                    statement.setInt(1, classId)
                    statement.setString(2, interfaceFqn)
                    statement.addBatch()
                }
            }
            statement.executeBatch()
        }
        return classIds
    }

    private fun insertMethods(
        connection: Connection,
        extracted: ExtractedJar,
        classIds: Map<String, Int>,
    ): Map<MethodKey, Int> {
        val sorted = extracted.methods.sortedWith(
            compareBy({ classIds.getValue(it.classFqn) }, { it.name }, { it.descriptor }),
        )
        connection.prepareStatement(
            """
            INSERT INTO methods(id, class_id, name, descriptor, return_fqn, modifiers)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            sorted.forEachIndexed { index, item ->
                statement.setInt(1, index + 1)
                statement.setInt(2, classIds.getValue(item.classFqn))
                statement.setString(3, item.name)
                statement.setString(4, item.descriptor)
                statement.setNullableString(5, item.returnFqn)
                statement.setInt(6, item.modifiers)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        return sorted.mapIndexed { index, item ->
            MethodKey(item.classFqn, item.name, item.descriptor) to index + 1
        }.toMap()
    }

    private fun insertSpiRegistrations(connection: Connection, extracted: ExtractedJar) {
        connection.prepareStatement(
            "INSERT INTO spi_registrations(id, mechanism, key, impl_fqn) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            extracted.spiRegistrations.sortedWith(
                compareBy({ it.mechanism }, { it.key ?: "" }, { it.implFqn }),
            ).forEachIndexed { index, item ->
                statement.setInt(1, index + 1)
                statement.setString(2, item.mechanism)
                statement.setNullableString(3, item.key)
                statement.setString(4, item.implFqn)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertConfigProperties(connection: Connection, extracted: ExtractedJar) {
        connection.prepareStatement(
            """
            INSERT INTO config_properties(
              id, prefix, name, type_fqn, default_val, description, source_fqn
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            extracted.configProperties.sortedWith(
                // Nullable selectors compare via null-aware compareValues (null sorts
                // before any non-null), so SQL NULL and "" stay distinct — a stable
                // total order that does not depend on upstream input order.
                compareBy(
                    { it.name },
                    { it.prefix },
                    { it.typeFqn },
                    { it.defaultValue },
                    { it.sourceFqn },
                    { it.description },
                ),
            ).forEachIndexed { index, property ->
                statement.setInt(1, index + 1)
                statement.setNullableString(2, property.prefix)
                statement.setString(3, property.name)
                statement.setNullableString(4, property.typeFqn)
                statement.setNullableString(5, property.defaultValue)
                statement.setNullableString(6, property.description)
                statement.setNullableString(7, property.sourceFqn)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertAnnotations(
        connection: Connection,
        extracted: ExtractedJar,
        classIds: Map<String, Int>,
        methodIds: Map<MethodKey, Int>,
    ) {
        val resolved = extracted.annotations.map { item ->
            val targetId = if (item.targetKind == "class") {
                classIds.getValue(item.classFqn)
            } else {
                methodIds.getValue(item.methodKey())
            }
            item to targetId
        }.sortedWith(
            compareBy({ it.first.targetKind }, { it.second }, { it.first.annotationFqn }),
        )
        connection.prepareStatement(
            """
            INSERT INTO annotations(id, target_kind, target_id, annotation_fqn, attributes)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            resolved.forEachIndexed { index, (item, targetId) ->
                statement.setInt(1, index + 1)
                statement.setString(2, item.targetKind)
                statement.setInt(3, targetId)
                statement.setString(4, item.annotationFqn)
                statement.setString(5, item.attributes)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertBeanDefinitions(
        connection: Connection,
        extracted: ExtractedJar,
        methodIds: Map<MethodKey, Int>,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO bean_definitions(
              id, config_fqn, method_id, bean_type_fqn, bean_name
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            extracted.beanDefinitions.forEachIndexed { index, item ->
                statement.setInt(1, index + 1)
                statement.setString(2, item.configFqn)
                statement.setInt(
                    3,
                    methodIds.getValue(
                        MethodKey(item.configFqn, item.methodName, item.methodDescriptor),
                    ),
                )
                statement.setNullableString(4, item.beanTypeFqn)
                statement.setString(5, item.beanName)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertConditions(
        connection: Connection,
        extracted: ExtractedJar,
        classIds: Map<String, Int>,
        methodIds: Map<MethodKey, Int>,
    ) {
        val resolved = extracted.conditions.map { item ->
            val targetId = if (item.targetKind == "class") {
                classIds.getValue(item.classFqn)
            } else {
                methodIds.getValue(item.methodKey())
            }
            item to targetId
        }.sortedWith(compareBy({ it.first.targetKind }, { it.second }, { it.first.type }))
        connection.prepareStatement(
            "INSERT INTO conditions(id, target_kind, target_id, type, ref_value) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            resolved.forEachIndexed { index, (item, targetId) ->
                statement.setInt(1, index + 1)
                statement.setString(2, item.targetKind)
                statement.setInt(3, targetId)
                statement.setString(4, item.type)
                statement.setString(5, item.refValue)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertStringConstants(
        connection: Connection,
        extracted: ExtractedJar,
        classIds: Map<String, Int>,
        methodIds: Map<MethodKey, Int>,
    ) {
        val sorted = extracted.stringConstants.sortedWith(
            compareBy(
                { classIds.getValue(it.classFqn) },
                { it.value },
                { it.methodName ?: "" },
                { it.methodDescriptor ?: "" },
            ),
        )
        connection.prepareStatement(
            "INSERT INTO string_constants(id, class_id, method_id, value) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            sorted.forEachIndexed { index, item ->
                statement.setInt(1, index + 1)
                statement.setInt(2, classIds.getValue(item.classFqn))
                if (item.methodName == null || item.methodDescriptor == null) {
                    statement.setNull(3, Types.INTEGER)
                } else {
                    statement.setInt(3, methodIds.getValue(item.methodKey()))
                }
                statement.setString(4, item.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun dev.springdep.indexer.extract.ExtractedAnnotation.methodKey() =
        MethodKey(classFqn, requireNotNull(methodName), requireNotNull(methodDescriptor))

    private fun dev.springdep.indexer.extract.ExtractedCondition.methodKey() =
        MethodKey(classFqn, requireNotNull(methodName), requireNotNull(methodDescriptor))

    private fun dev.springdep.indexer.extract.ExtractedStringConstant.methodKey() =
        MethodKey(classFqn, requireNotNull(methodName), requireNotNull(methodDescriptor))

    private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }
}
