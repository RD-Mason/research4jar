package dev.research4jar.indexer

/**
 * Single source of truth for the extractor and schema versions stamped into
 * every artifact (shard_meta, manifest rows, project.json) and into the
 * content-addressed shard_id (`<jar_sha256>@<extractor>`).
 *
 * Keeping these in one place prevents the values from drifting across the
 * Kotlin write path and the SQLite data contract the Go querier reads. M1 == 2.
 */
object Research4JarVersions {
    const val EXTRACTOR = 2
    const val SCHEMA = 2

    /**
     * Version of the merged session database layout. Bumped when the set of
     * merged tables or their shapes change so that sessions built by older
     * binaries are rebuilt instead of reused (shards are unaffected — their
     * compatibility is governed by EXTRACTOR/SCHEMA through the shard_id).
     * M2 == 2: merges methods, bean_definitions, conditions, string_constants
     * and method-target annotations alongside the M1 tables.
     * M3 == 3: adds retrieval-oriented derived columns and search_symbols.
     * M6 == 4: search_symbols becomes a view over the base tables (the
     * materialized copy and its four indexes were 65% of the session bytes)
     * and derived columns are computed set-based in SQL.
     * M6 == 5: adds shard/range bookkeeping plus string_constants_fts for
     * incremental session updates and indexed string substring search.
     * M6 == 6: adds classes_fts and methods_fts; old v5 sessions remain
     * query-compatible through scan fallbacks, but must rebuild to receive
     * the new search latency.
     * M6 == 7: stores the repeated per-row source shard reference as a compact
     * ordered INTEGER key; session_shards retains the canonical text id and
     * query responses resolve the key back through that table.
     * M6 == 8: removes the repeated owner#name method symbol. Queries derive
     * it from the owning class and compact method name; owner_resolved keeps
     * the legacy NULL-symbol semantics for malformed/orphan shard rows.
     * M6 == 9: replaces the per-row methods_fts and string_constants_fts
     * shadows with deduplicated, packed value domains (method_names,
     * method_descriptors, string_values plus pack/FTS structures) and adds
     * idx_s_methods_descriptor as the descriptor reverse lookup. The per-row
     * shadows re-tokenized every merged row (~25s of a 33s 1000-jar session
     * build) while the distinct value sets saturate near 350k entries.
     * Still 9: the class/method accelerators are size-gated at build time
     * (SessionBuilder.ftsMinMethods) — a session carries either all of them
     * or none, and BOTH shapes are valid v9 layouts (queries fall back
     * per-tier below the gate). No bump: v9 was never released.
     */
    const val SESSION = 9
}
