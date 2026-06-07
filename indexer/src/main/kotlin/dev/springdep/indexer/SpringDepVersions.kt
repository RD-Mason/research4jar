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
}
