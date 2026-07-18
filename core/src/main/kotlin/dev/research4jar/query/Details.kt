package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonRawValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection

data class AnnotationDetail(
    @JsonProperty("fqn") val fqn: String,
    @JsonRawValue
    @JsonProperty("attributes") val attributes: String?,
)

data class MethodDetail(
    @JsonProperty("name") val name: String,
    @JsonProperty("descriptor") val descriptor: String,
    @JsonProperty("return") val returnFqn: String?,
    @JsonProperty("modifiers") val modifiers: Int,
    @JsonProperty("annotations") val annotations: List<AnnotationDetail>,
)

data class ConditionDetail(
    @JsonProperty("target") val target: String,
    @JsonProperty("type") val type: String,
    @JsonRawValue
    @JsonProperty("ref_value") val refValue: String?,
)

data class BeanDetail(
    @JsonProperty("bean_name") val beanName: String,
    @JsonProperty("bean_type") val beanTypeFqn: String?,
    @JsonProperty("config_class") val configFqn: String,
    @JsonProperty("method") val method: String?,
    @JsonProperty("conditions") val conditions: List<ConditionDetail>,
    @JsonProperty("source_jar") val sourceJar: String,
)

data class ClassDetail(
    @JsonProperty("fqn") val fqn: String,
    @JsonProperty("kind") val kind: String?,
    @JsonProperty("super") val superFqn: String?,
    @JsonProperty("interfaces") val interfaces: List<String>,
    @JsonProperty("modifiers") val modifiers: Int,
    @JsonProperty("is_abstract") val isAbstract: Boolean,
    @JsonProperty("source_file") val sourceFile: String?,
    @JsonProperty("source_jar") val sourceJar: String,
    @JsonProperty("annotations") val annotations: List<AnnotationDetail>,
    @JsonProperty("methods") val methods: List<MethodDetail>,
    @JsonProperty("bean_definitions") val beans: List<BeanDetail>,
    @JsonProperty("conditions") val conditions: List<ConditionDetail>,
)

data class ClassResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<ClassDetail>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

data class BeanDefinitionsResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<BeanDetail>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

data class ConditionalTarget(
    @JsonProperty("fqn") val fqn: String,
    @JsonProperty("source_jar") val sourceJar: String,
    @JsonProperty("class_conditions") val classConditions: List<ConditionDetail>,
    @JsonProperty("bean_methods") val beanMethods: List<BeanDetail>,
)

data class ConditionalResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<ConditionalTarget>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

private val rawJsonMapper = jacksonObjectMapper()

private fun validRawJson(text: String?): String? {
    if (text == null) return null
    return try {
        rawJsonMapper.readTree(text)
        text
    } catch (_: com.fasterxml.jackson.core.JacksonException) {
        null
    }
}

/**
 * Full stored facts for every class row matching the FQN (one per shard that
 * contains it). Detail commands are not paginated.
 */
fun getClass(
    pointer: ProjectPointerData,
    manifestPath: String,
    fqn: String,
): ClassResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    data class ClassRow(
        val id: Int,
        val fqn: String,
        val kind: String?,
        val superFqn: String?,
        val modifiers: Int,
        val isAbstract: Boolean,
        val sourceFile: String?,
        val shardId: String,
    )
    val classRows = session.query(
        """
        SELECT id, fqn, kind, super_fqn, modifiers, is_abstract, source_file, source_shard_id
        FROM classes WHERE fqn = ?
        ORDER BY source_shard_id
        """.trimIndent(),
        listOf(fqn),
    ) { rows ->
        rows.mapRows {
            val abstractFlag = it.getInt(6)
            val isAbstract = !it.wasNull() && abstractFlag != 0
            ClassRow(
                id = it.getInt(1),
                fqn = it.getString(2),
                kind = it.getString(3),
                superFqn = it.getString(4),
                modifiers = it.getInt(5),
                isAbstract = isAbstract,
                sourceFile = it.getString(7),
                shardId = it.getString(8),
            )
        }
    }

    val sources = if (classRows.isNotEmpty()) {
        ManifestCache.loadSourceJars(session, manifestPath, classRows.map { it.shardId })
    } else {
        null
    }
    val results = classRows.map { row ->
        ClassDetail(
            fqn = row.fqn,
            kind = row.kind,
            superFqn = row.superFqn,
            interfaces = classInterfaces(session, row.id),
            modifiers = row.modifiers,
            isAbstract = row.isAbstract,
            sourceFile = row.sourceFile,
            sourceJar = sourceJarName(sources, row.shardId),
            annotations = targetAnnotations(session, "class", row.id),
            methods = classMethods(session, row.id),
            beans = beansForConfig(session, sources, row.fqn, row.shardId),
            conditions = targetConditions(session, "class", row.id, "class"),
        )
    }

    ClassResponse(
        query = SymbolRequest(command = "get-class", arg = fqn),
        results = results,
        total = results.size,
        coverage = coverageFrom(pointer),
    )
}

/**
 * Lists @Bean registrations whose bean type or declaring configuration class
 * matches the FQN.
 */
fun getBeanDefinitions(
    pointer: ProjectPointerData,
    manifestPath: String,
    fqn: String,
    page: Int,
    pageSize: Int,
): BeanDefinitionsResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    val window = pageWindow(page, pageSize)
    val total = session.queryInt(
        "SELECT COUNT(*) FROM bean_definitions WHERE bean_type_fqn = ? OR config_fqn = ?",
        listOf(fqn, fqn),
    )

    val beans = scanBeans(
        session,
        """
        SELECT b.bean_name, b.bean_type_fqn, b.config_fqn, b.method_id,
               m.name, m.descriptor, b.source_shard_id
        FROM bean_definitions b
        LEFT JOIN methods m ON m.id = b.method_id
        WHERE b.bean_type_fqn = ? OR b.config_fqn = ?
        ORDER BY b.config_fqn, b.bean_name, b.source_shard_id, b.id
        LIMIT ? OFFSET ?
        """.trimIndent(),
        listOf(fqn, fqn, window.limit, window.offset),
    )

    val sources = if (beans.isNotEmpty()) {
        ManifestCache.loadSourceJars(session, manifestPath, beans.map { it.sourceJar })
    } else {
        null
    }
    val results = beans.map { it.copy(sourceJar = sourceJarName(sources, it.sourceJar)) }

    BeanDefinitionsResponse(
        query = SymbolRequest(command = "get-bean-definitions", arg = fqn),
        results = results,
        total = total,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}

/**
 * Reports class-level and @Bean-method conditions for every class row
 * matching the FQN.
 */
fun explainConditional(
    pointer: ProjectPointerData,
    manifestPath: String,
    fqn: String,
): ConditionalResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    data class ClassRef(val id: Int, val shardId: String)
    val refs = session.query(
        "SELECT id, source_shard_id FROM classes WHERE fqn = ? ORDER BY source_shard_id",
        listOf(fqn),
    ) { rows -> rows.mapRows { ClassRef(it.getInt(1), it.getString(2)) } }

    val sources = if (refs.isNotEmpty()) {
        ManifestCache.loadSourceJars(session, manifestPath, refs.map { it.shardId })
    } else {
        null
    }
    val results = refs.map { ref ->
        ConditionalTarget(
            fqn = fqn,
            sourceJar = sourceJarName(sources, ref.shardId),
            classConditions = targetConditions(session, "class", ref.id, "class"),
            beanMethods = beansForConfig(session, sources, fqn, ref.shardId),
        )
    }

    ConditionalResponse(
        query = SymbolRequest(command = "explain-conditional", arg = fqn),
        results = results,
        total = results.size,
        coverage = coverageFrom(pointer),
    )
}

private fun classInterfaces(session: Connection, classId: Int): List<String> =
    session.query(
        "SELECT interface_fqn FROM class_interfaces WHERE class_id = ? ORDER BY interface_fqn",
        listOf(classId),
    ) { rows -> rows.mapRows { it.getString(1) } }

private fun targetAnnotations(
    session: Connection,
    targetKind: String,
    targetId: Int,
): List<AnnotationDetail> =
    session.query(
        """
        SELECT annotation_fqn, attributes FROM annotations
        WHERE target_kind = ? AND target_id = ?
        ORDER BY annotation_fqn, COALESCE(attributes, '')
        """.trimIndent(),
        listOf(targetKind, targetId),
    ) { rows ->
        rows.mapRows {
            AnnotationDetail(
                fqn = it.getString(1),
                attributes = validRawJson(it.getString(2)),
            )
        }
    }

private fun targetConditions(
    session: Connection,
    targetKind: String,
    targetId: Int,
    label: String,
): List<ConditionDetail> =
    session.query(
        """
        SELECT type, ref_value FROM conditions
        WHERE target_kind = ? AND target_id = ?
        ORDER BY type, COALESCE(ref_value, '')
        """.trimIndent(),
        listOf(targetKind, targetId),
    ) { rows ->
        rows.mapRows {
            ConditionDetail(
                target = label,
                type = it.getString(1),
                refValue = validRawJson(it.getString(2)),
            )
        }
    }

private fun classMethods(session: Connection, classId: Int): List<MethodDetail> {
    data class MethodRow(
        val id: Int,
        val name: String,
        val descriptor: String,
        val returnFqn: String?,
        val modifiers: Int,
    )
    val methodRows = session.query(
        """
        SELECT id, name, descriptor, return_fqn, modifiers FROM methods
        WHERE class_id = ?
        ORDER BY name, descriptor
        """.trimIndent(),
        listOf(classId),
    ) { rows ->
        rows.mapRows {
            MethodRow(
                id = it.getInt(1),
                name = it.getString(2),
                descriptor = it.getString(3),
                returnFqn = it.getString(4),
                modifiers = it.getInt(5),
            )
        }
    }
    return methodRows.map { row ->
        MethodDetail(
            name = row.name,
            descriptor = row.descriptor,
            returnFqn = row.returnFqn,
            modifiers = row.modifiers,
            annotations = targetAnnotations(session, "method", row.id),
        )
    }
}

private fun beansForConfig(
    session: Connection,
    sources: Map<String, String>?,
    configFqn: String,
    shardId: String,
): List<BeanDetail> =
    scanBeans(
        session,
        """
        SELECT b.bean_name, b.bean_type_fqn, b.config_fqn, b.method_id,
               m.name, m.descriptor, b.source_shard_id
        FROM bean_definitions b
        LEFT JOIN methods m ON m.id = b.method_id
        WHERE b.config_fqn = ? AND b.source_shard_id = ?
        ORDER BY b.bean_name, b.id
        """.trimIndent(),
        // Production v7 rows carry an INTEGER key. Bind it as Long so the
        // equality keeps integer affinity; retain text for hand-written and
        // pre-v7 compatibility fixtures.
        listOf(configFqn, shardId.toLongOrNull() ?: shardId),
    ).map { it.copy(sourceJar = sourceJarName(sources, it.sourceJar)) }

// scanBeans runs a bean query (with sourceJar temporarily holding the shard
// id) and attaches each bean method's conditions.
private fun scanBeans(session: Connection, sql: String, args: List<Any?>): List<BeanDetail> {
    data class BeanRow(val detail: BeanDetail, val methodId: Int?)
    val beanRows = session.query(sql, args) { rows ->
        rows.mapRows {
            val methodIdValue = it.getInt(4)
            val methodId = if (it.wasNull()) null else methodIdValue
            val methodName: String? = it.getString(5)
            val methodDescriptor: String? = it.getString(6)
            BeanRow(
                BeanDetail(
                    beanName = it.getString(1),
                    beanTypeFqn = it.getString(2),
                    configFqn = it.getString(3),
                    method = if (methodName != null && methodDescriptor != null) {
                        methodName + methodDescriptor
                    } else {
                        null
                    },
                    conditions = emptyList(),
                    sourceJar = it.getString(7),
                ),
                methodId,
            )
        }
    }
    return beanRows.map { row ->
        if (row.methodId == null) {
            row.detail
        } else {
            row.detail.copy(
                conditions = targetConditions(session, "bean_method", row.methodId, "bean_method"),
            )
        }
    }
}
