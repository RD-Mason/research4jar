package dev.springdep.indexer

/**
 * Single source of truth for the extractor and schema versions stamped into
 * every artifact (shard_meta, manifest rows, project.json) and into the
 * content-addressed shard_id (`<jar_sha256>@<extractor>`).
 *
 * Keeping these in one place prevents the values from drifting across the
 * Kotlin write path and the SQLite data contract the Go querier reads. M1 == 2.
 */
object SpringDepVersions {
    const val EXTRACTOR = 2
    const val SCHEMA = 2

    /**
     * Version of the merged session database layout. Bumped when the set of
     * merged tables or their shapes change so that sessions built by older
     * binaries are rebuilt instead of reused (shards are unaffected — their
     * compatibility is governed by EXTRACTOR/SCHEMA through the shard_id).
     * M2 == 2: merges methods, bean_definitions, conditions, string_constants
     * and method-target annotations alongside the M1 tables.
     */
    const val SESSION = 2
}
