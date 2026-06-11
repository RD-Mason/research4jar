// Package versions mirrors the version constants the Kotlin indexer stamps
// into artifacts. The Go side needs the extractor version to form shard ids
// (`<jar_sha256>@<extractor>`) for registry prefetch and cache lifecycle
// decisions. A test guards against drifting from SpringDepVersions.kt.
package versions

// Extractor must equal SpringDepVersions.EXTRACTOR in
// indexer/src/main/kotlin/dev/springdep/indexer/SpringDepVersions.kt.
const Extractor = 2
