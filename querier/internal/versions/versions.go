// Package versions mirrors the version constants the Kotlin indexer stamps
// into artifacts. The Go side needs the extractor version to form shard ids
// (`<jar_sha256>@<extractor>`) for registry prefetch and cache lifecycle
// decisions. A test guards against drifting from Research4JarVersions.kt.
package versions

// Extractor must equal Research4JarVersions.EXTRACTOR in
// indexer/src/main/kotlin/dev/research4jar/indexer/Research4JarVersions.kt.
const Extractor = 2

// Schema must equal Research4JarVersions.SCHEMA (stamped into project.json).
const Schema = 2

// Session must equal Research4JarVersions.SESSION (the merged session database
// layout version; sessions with a different stamp are rebuilt, not reused).
const Session = 4
