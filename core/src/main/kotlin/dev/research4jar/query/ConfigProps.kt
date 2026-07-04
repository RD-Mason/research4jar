package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonProperty

data class ConfigProperty(
    @JsonProperty("name") val name: String,
    @JsonProperty("type") val type: String?,
    @JsonProperty("default") val default: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("source_jar") val sourceJar: String,
)

data class ConfigPropertiesResponse(
    @JsonProperty("query") val query: Request,
    @JsonProperty("results") val results: List<ConfigProperty>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

fun findConfigProperties(
    pointer: ProjectPointerData,
    manifestPath: String,
    prefix: String,
    page: Int,
    pageSize: Int,
): ConfigPropertiesResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    // Deliberately avoids LIKE: the child range [prefix+'.', prefix+'/') is a
    // straight index range scan on idx_s_cfg_name ('/' is '.'+1 in ASCII).
    val childLowerBound = "$prefix."
    val childUpperBound = "$prefix/"
    val where = "WHERE name = ? OR (name >= ? AND name < ?)"
    val bind = listOf<Any?>(prefix, childLowerBound, childUpperBound)
    val total = session.queryInt("SELECT COUNT(*) FROM config_properties $where", bind)

    data class Pending(val property: ConfigProperty, val shardId: String)
    val pending = session.query(
        """
        SELECT name, type_fqn, default_val, description, source_fqn, source_shard_id
        FROM config_properties
        $where
        ORDER BY name ASC, source_shard_id ASC
        LIMIT ? OFFSET ?
        """.trimIndent(),
        bind + listOf(pageSize, (page - 1) * pageSize),
    ) { rows ->
        rows.mapRows {
            Pending(
                ConfigProperty(
                    name = it.getString(1),
                    type = it.getString(2),
                    default = it.getString(3),
                    description = it.getString(4),
                    source = it.getString(5),
                    sourceJar = "",
                ),
                it.getString(6),
            )
        }
    }

    val sources = if (pending.isNotEmpty()) ManifestCache.loadSourceJars(manifestPath) else null
    ConfigPropertiesResponse(
        query = Request(command = "find-config-properties", prefix = prefix),
        results = pending.map { it.property.copy(sourceJar = sourceJarName(sources, it.shardId)) },
        total = total,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}
