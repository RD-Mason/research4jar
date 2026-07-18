package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

data class ExtensionPoint(
    @JsonProperty("mechanism") val mechanism: String,
    @JsonProperty("key") val key: String?,
    @JsonProperty("implementations") val implementations: Int,
)

data class ExtensionRegistration(
    @JsonProperty("mechanism") val mechanism: String,
    @JsonProperty("key") val key: String?,
    @JsonProperty("impl_fqn") val implFqn: String,
    @JsonProperty("source_jar") val sourceJar: String,
)

data class ExtensionPointsResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("extension_points") val points: List<ExtensionPoint>? = null,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("results") val results: List<ExtensionRegistration>? = null,
    @JsonProperty("total") val total: Int,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

/**
 * Without an argument, summarizes every SPI mechanism/key pair with its
 * registration count. With an argument, lists the concrete registrations
 * whose key or mechanism matches.
 */
fun listExtensionPoints(
    pointer: ProjectPointerData,
    manifestPath: String,
    arg: String,
    page: Int,
    pageSize: Int,
): ExtensionPointsResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    val window = pageWindow(page, pageSize)
    if (arg.isEmpty()) {
        val total = session.queryInt(
            """SELECT COUNT(*) FROM (
               SELECT 1 FROM spi_registrations GROUP BY mechanism, key
             )""",
            emptyList(),
        )
        val points = session.query(
            """
            SELECT mechanism, key, COUNT(*)
            FROM spi_registrations
            GROUP BY mechanism, key
            ORDER BY mechanism, COALESCE(key, '')
            LIMIT ? OFFSET ?
            """.trimIndent(),
            listOf(window.limit, window.offset),
        ) { rows ->
            rows.mapRows {
                ExtensionPoint(
                    mechanism = it.getString(1),
                    key = it.getString(2),
                    implementations = it.getInt(3),
                )
            }
        }
        return@use ExtensionPointsResponse(
            query = SymbolRequest(command = "list-extension-points", arg = arg),
            points = points,
            total = total,
            page = page,
            pageSize = pageSize,
            coverage = coverageFrom(pointer),
        )
    }

    val total = session.queryInt(
        "SELECT COUNT(*) FROM spi_registrations WHERE key = ? OR mechanism = ?",
        listOf(arg, arg),
    )
    data class Pending(val registration: ExtensionRegistration, val shardId: String)
    val pending = session.query(
        """
        SELECT mechanism, key, impl_fqn, source_shard_id
        FROM spi_registrations
        WHERE key = ? OR mechanism = ?
        ORDER BY mechanism, COALESCE(key, ''), impl_fqn, source_shard_id
        LIMIT ? OFFSET ?
        """.trimIndent(),
        listOf(arg, arg, window.limit, window.offset),
    ) { rows ->
        rows.mapRows {
            Pending(
                ExtensionRegistration(
                    mechanism = it.getString(1),
                    key = it.getString(2),
                    implFqn = it.getString(3),
                    sourceJar = "",
                ),
                it.getString(4),
            )
        }
    }

    val sources = if (pending.isNotEmpty()) {
        ManifestCache.loadSourceJars(session, manifestPath, pending.map { it.shardId })
    } else {
        null
    }
    ExtensionPointsResponse(
        query = SymbolRequest(command = "list-extension-points", arg = arg),
        results = pending.map {
            it.registration.copy(sourceJar = sourceJarName(sources, it.shardId))
        },
        total = total,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}
