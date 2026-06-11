// Package cache implements shard and session lifecycle: usage statistics and
// garbage collection (stale extractor versions, orphan files, LRU eviction
// over a size budget, and session expiry).
package cache

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"dev.springdep/querier/internal/manifest"
	"dev.springdep/querier/internal/paths"
)

// Stats summarizes the global cache.
type Stats struct {
	Home               string `json:"home"`
	ShardCount         int    `json:"shard_count"`
	ShardBytes         int64  `json:"shard_bytes"`
	ShardsLocal        int    `json:"shards_local"`
	ShardsRemote       int    `json:"shards_remote"`
	ShardsStaleVersion int    `json:"shards_stale_version"`
	OrphanFiles        int    `json:"orphan_files"`
	OrphanBytes        int64  `json:"orphan_bytes"`
	SessionCount       int    `json:"session_count"`
	SessionBytes       int64  `json:"session_bytes"`
}

// GCOptions bounds a collection run. Zero values disable that policy:
// MaxShardBytes==0 keeps every current-version shard, MaxSessionAge==0 keeps
// every session. Stale-version shards and orphan files are always collected.
type GCOptions struct {
	MaxShardBytes int64
	MaxSessionAge time.Duration
	DryRun        bool
}

// GCResult reports what a collection run removed (or would remove).
type GCResult struct {
	DryRun                bool     `json:"dry_run"`
	RemovedStaleVersion   int      `json:"removed_stale_version"`
	RemovedOrphans        int      `json:"removed_orphans"`
	RemovedLRU            int      `json:"removed_lru"`
	RemovedSessions       int      `json:"removed_sessions"`
	ReclaimedBytes        int64    `json:"reclaimed_bytes"`
	RemainingShards       int      `json:"remaining_shards"`
	RemainingShardBytes   int64    `json:"remaining_shard_bytes"`
	RemainingSessions     int      `json:"remaining_sessions"`
	RemainingSessionBytes int64    `json:"remaining_session_bytes"`
	Removed               []string `json:"removed,omitempty"`
}

// CollectStats walks the manifest, shards directory, and sessions directory.
func CollectStats(dataPaths paths.DataPaths, currentExtractorVersion int) (Stats, error) {
	stats := Stats{Home: dataPaths.Home}
	manifestDB, err := manifest.Open(dataPaths.Manifest)
	if err != nil {
		return Stats{}, err
	}
	defer manifestDB.Close()
	shards, err := manifestDB.List()
	if err != nil {
		return Stats{}, err
	}
	tracked := map[string]bool{}
	for _, shard := range shards {
		stats.ShardCount++
		stats.ShardBytes += shard.SizeBytes
		tracked[filepath.Clean(shard.ShardPath)] = true
		switch shard.Source {
		case "remote":
			stats.ShardsRemote++
		default:
			stats.ShardsLocal++
		}
		if shard.ExtractorVersion != currentExtractorVersion {
			stats.ShardsStaleVersion++
		}
	}
	for _, file := range listFiles(dataPaths.Shards, ".db") {
		if !tracked[filepath.Clean(file.path)] {
			stats.OrphanFiles++
			stats.OrphanBytes += file.size
		}
	}
	for _, file := range listFiles(dataPaths.Sessions, ".db") {
		stats.SessionCount++
		stats.SessionBytes += file.size
	}
	return stats, nil
}

// GC collects garbage in four passes: shards from older extractor versions,
// shard files the manifest does not track, least-recently-used shards beyond
// the size budget, and sessions older than the age limit. Sessions are always
// rebuildable from shards by the next `springdep index` run.
func GC(
	dataPaths paths.DataPaths, currentExtractorVersion int, options GCOptions,
) (GCResult, error) {
	result := GCResult{DryRun: options.DryRun}
	manifestDB, err := manifest.Open(dataPaths.Manifest)
	if err != nil {
		return GCResult{}, err
	}
	defer manifestDB.Close()
	shards, err := manifestDB.List()
	if err != nil {
		return GCResult{}, err
	}

	removeShard := func(shard manifest.Shard) error {
		result.Removed = append(result.Removed, shard.ShardID)
		result.ReclaimedBytes += shard.SizeBytes
		if options.DryRun {
			return nil
		}
		if err := os.Remove(shard.ShardPath); err != nil && !os.IsNotExist(err) {
			return err
		}
		return manifestDB.Remove(shard.ShardID)
	}

	var current []manifest.Shard
	for _, shard := range shards {
		if shard.ExtractorVersion != currentExtractorVersion {
			if err := removeShard(shard); err != nil {
				return GCResult{}, err
			}
			result.RemovedStaleVersion++
			continue
		}
		current = append(current, shard)
	}

	// Orphans: shard files the manifest does not reference. Track every
	// manifest path (stale rows included) so a dry run does not double-count
	// stale-version files as orphans.
	tracked := map[string]bool{}
	for _, shard := range shards {
		tracked[filepath.Clean(shard.ShardPath)] = true
	}
	for _, file := range listFiles(dataPaths.Shards, ".db") {
		if tracked[filepath.Clean(file.path)] {
			continue
		}
		result.Removed = append(result.Removed, filepath.Base(file.path))
		result.ReclaimedBytes += file.size
		result.RemovedOrphans++
		if !options.DryRun {
			if err := os.Remove(file.path); err != nil && !os.IsNotExist(err) {
				return GCResult{}, err
			}
		}
	}

	// LRU eviction over the size budget. List() orders oldest access first.
	if options.MaxShardBytes > 0 {
		var totalBytes int64
		for _, shard := range current {
			totalBytes += shard.SizeBytes
		}
		index := 0
		for totalBytes > options.MaxShardBytes && index < len(current) {
			shard := current[index]
			index++
			if err := removeShard(shard); err != nil {
				return GCResult{}, err
			}
			result.RemovedLRU++
			totalBytes -= shard.SizeBytes
		}
		current = current[index:]
	}

	for _, shard := range current {
		result.RemainingShards++
		result.RemainingShardBytes += shard.SizeBytes
	}

	cutoff := time.Time{}
	if options.MaxSessionAge > 0 {
		cutoff = time.Now().Add(-options.MaxSessionAge)
	}
	for _, file := range listFiles(dataPaths.Sessions, ".db") {
		if !cutoff.IsZero() && file.modTime.Before(cutoff) {
			result.Removed = append(result.Removed, filepath.Base(file.path))
			result.ReclaimedBytes += file.size
			result.RemovedSessions++
			if !options.DryRun {
				if err := os.Remove(file.path); err != nil && !os.IsNotExist(err) {
					return GCResult{}, err
				}
			}
			continue
		}
		result.RemainingSessions++
		result.RemainingSessionBytes += file.size
	}
	if result.Removed == nil {
		result.Removed = []string{}
	}
	return result, nil
}

// ParseSize parses human-friendly byte sizes: 500M, 2G, 1024K, 123 (bytes).
func ParseSize(value string) (int64, error) {
	text := strings.TrimSpace(strings.ToUpper(value))
	multiplier := int64(1)
	switch {
	case strings.HasSuffix(text, "K"):
		multiplier, text = 1<<10, strings.TrimSuffix(text, "K")
	case strings.HasSuffix(text, "M"):
		multiplier, text = 1<<20, strings.TrimSuffix(text, "M")
	case strings.HasSuffix(text, "G"):
		multiplier, text = 1<<30, strings.TrimSuffix(text, "G")
	}
	number, err := strconv.ParseInt(strings.TrimSpace(text), 10, 64)
	if err != nil || number < 0 {
		return 0, fmt.Errorf("invalid size %q (use e.g. 500M, 2G)", value)
	}
	return number * multiplier, nil
}

// ParseAge parses durations in days or hours: 30d, 12h.
func ParseAge(value string) (time.Duration, error) {
	text := strings.TrimSpace(strings.ToLower(value))
	switch {
	case strings.HasSuffix(text, "d"):
		days, err := strconv.Atoi(strings.TrimSuffix(text, "d"))
		if err != nil || days < 0 {
			return 0, fmt.Errorf("invalid age %q (use e.g. 30d, 12h)", value)
		}
		return time.Duration(days) * 24 * time.Hour, nil
	case strings.HasSuffix(text, "h"):
		hours, err := strconv.Atoi(strings.TrimSuffix(text, "h"))
		if err != nil || hours < 0 {
			return 0, fmt.Errorf("invalid age %q (use e.g. 30d, 12h)", value)
		}
		return time.Duration(hours) * time.Hour, nil
	}
	return 0, fmt.Errorf("invalid age %q (use e.g. 30d, 12h)", value)
}

type fileInfo struct {
	path    string
	size    int64
	modTime time.Time
}

func listFiles(dir, suffix string) []fileInfo {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var files []fileInfo
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), suffix) {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		files = append(files, fileInfo{
			path:    filepath.Join(dir, entry.Name()),
			size:    info.Size(),
			modTime: info.ModTime(),
		})
	}
	sort.Slice(files, func(a, b int) bool { return files[a].path < files[b].path })
	return files
}
