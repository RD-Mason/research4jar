// Package versions mirrors the version constants the Kotlin indexer stamps
// into artifacts. The Go side needs the extractor version to form shard ids
// (`<jar_sha256>@<extractor>`) for registry prefetch and cache lifecycle
// decisions. A test guards against drifting from SpringDepVersions.kt.
package versions

// Extractor must equal SpringDepVersions.EXTRACTOR in
// indexer/src/main/kotlin/dev/springdep/indexer/SpringDepVersions.kt.
const Extractor = 2

// Schema must equal SpringDepVersions.SCHEMA (stamped into project.json).
const Schema = 2

// Session must equal SpringDepVersions.SESSION (the merged session database
// layout version; sessions with a different stamp are rebuilt, not reused).
const Session = 2
