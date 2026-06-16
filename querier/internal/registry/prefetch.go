package registry

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sync"

	"dev.research4jar/querier/internal/manifest"
)

// ShardRef names one valid local shard after a prefetch.
type ShardRef struct {
	ShardID string
	Path    string
}

// PrefetchStats reports what a registry prefetch accomplished. When Complete
// is true every unique readable jar has a valid local shard (listed in
// Shards) and indexing needs no local extraction at all.
type PrefetchStats struct {
	JarsTotal    int
	JarsUnique   int
	CacheHits    int
	Downloaded   int
	Misses       int
	Failures     int
	HashFailures int
	Shards       []ShardRef
	Complete     bool
}

// Prefetch installs registry shards for every jar that has no valid local
// shard, so the JVM indexer that runs next hits the cache instead of
// extracting. Failures degrade to local extraction: they are warned about on
// the warnings writer and never abort the run.
func Prefetch(
	ctx context.Context,
	client *Client,
	manifestDB *manifest.DB,
	shardsDir string,
	extractorVersion int,
	jarPaths []string,
	warnings io.Writer,
) PrefetchStats {
	stats := PrefetchStats{JarsTotal: len(jarPaths)}
	if len(jarPaths) == 0 {
		return stats
	}

	type hashedJar struct {
		path   string
		sha256 string
	}
	hashed := make([]hashedJar, len(jarPaths))

	// Jar-digest cache: jars in the local Maven/Gradle caches are immutable,
	// so a row matching the current size+mtime lets us skip re-hashing — which
	// otherwise dominates the warm path. A load error just disables the cache.
	digestCache, _ := manifestDB.LoadJarDigests()
	type jarStat struct {
		abs   string
		size  int64
		mtime int64
		ok    bool
	}
	stats2 := make([]jarStat, len(jarPaths))
	var needHash []int
	for index, path := range jarPaths {
		abs, err := filepath.Abs(path)
		if err != nil {
			continue // unreadable; counted as a hash failure downstream
		}
		info, err := os.Stat(path)
		if err != nil {
			continue
		}
		stat := jarStat{abs: abs, size: info.Size(), mtime: info.ModTime().UnixMilli(), ok: true}
		stats2[index] = stat
		if entry, hit := digestCache[abs]; hit &&
			entry.SizeBytes == stat.size && entry.MtimeMillis == stat.mtime {
			hashed[index] = hashedJar{path: path, sha256: entry.SHA256}
			continue
		}
		needHash = append(needHash, index)
	}

	var wg sync.WaitGroup
	if len(needHash) > 0 {
		hashWorkers := min(runtime.NumCPU(), len(needHash))
		jobs := make(chan int)
		wg.Add(hashWorkers)
		for worker := 0; worker < hashWorkers; worker++ {
			go func() {
				defer wg.Done()
				for index := range jobs {
					digest, err := hashFile(jarPaths[index])
					if err != nil {
						// The indexer reports unreadable jars itself.
						continue
					}
					hashed[index] = hashedJar{path: jarPaths[index], sha256: digest}
				}
			}()
		}
		for _, index := range needHash {
			jobs <- index
		}
		close(jobs)
		wg.Wait()

		fresh := map[string]manifest.DigestEntry{}
		for _, index := range needHash {
			if hashed[index].sha256 == "" || !stats2[index].ok {
				continue
			}
			fresh[stats2[index].abs] = manifest.DigestEntry{
				SizeBytes:   stats2[index].size,
				MtimeMillis: stats2[index].mtime,
				SHA256:      hashed[index].sha256,
			}
		}
		if err := manifestDB.PutJarDigests(fresh); err != nil {
			fmt.Fprintf(warnings, "warning: registry prefetch: cache jar digests: %v\n", err)
		}
	}

	// Manifest decisions and registration stay on this goroutine; only the
	// network downloads fan out.
	type pending struct {
		jar hashedJar
	}
	var toFetch []pending
	var hitShardIDs []string
	seen := map[string]bool{}
	for _, jar := range hashed {
		if jar.sha256 == "" {
			stats.HashFailures++
			continue
		}
		if seen[jar.sha256] {
			continue
		}
		seen[jar.sha256] = true
		stats.JarsUnique++
		shardID := fmt.Sprintf("%s@%d", jar.sha256, extractorVersion)
		row, err := manifestDB.Find(shardID)
		if err != nil {
			fmt.Fprintf(warnings, "warning: registry prefetch: %v\n", err)
			stats.Failures++
			continue
		}
		if row != nil {
			if fileExists(row.ShardPath) {
				stats.CacheHits++
				hitShardIDs = append(hitShardIDs, shardID)
				stats.Shards = append(stats.Shards, ShardRef{ShardID: shardID, Path: row.ShardPath})
				continue
			}
			// Row without its file: drop it so the download re-registers.
			if err := manifestDB.Remove(shardID); err != nil {
				fmt.Fprintf(warnings, "warning: registry prefetch: %v\n", err)
				stats.Failures++
				continue
			}
		}
		toFetch = append(toFetch, pending{jar: jar})
	}

	type outcome struct {
		jar    hashedJar
		result FetchResult
		err    error
	}
	outcomes := make([]outcome, len(toFetch))
	downloadWorkers := min(6, len(toFetch))
	if downloadWorkers > 0 {
		fetchJobs := make(chan int)
		wg.Add(downloadWorkers)
		for worker := 0; worker < downloadWorkers; worker++ {
			go func() {
				defer wg.Done()
				for index := range fetchJobs {
					jar := toFetch[index].jar
					result, err := client.Fetch(ctx, jar.sha256, extractorVersion, shardsDir)
					outcomes[index] = outcome{jar: jar, result: result, err: err}
				}
			}()
		}
		for index := range toFetch {
			fetchJobs <- index
		}
		close(fetchJobs)
		wg.Wait()
	}

	for _, entry := range outcomes {
		switch {
		case errors.Is(entry.err, ErrShardNotFound):
			stats.Misses++
		case entry.err != nil:
			fmt.Fprintf(warnings, "warning: registry prefetch: %v\n", entry.err)
			stats.Failures++
		default:
			checksum := entry.result.Checksum
			err := manifestDB.Register(manifest.Shard{
				ShardID:          entry.result.ShardID,
				JarCoordinate:    entry.result.JarCoordinate,
				JarFilename:      filepath.Base(entry.jar.path),
				JarSHA256:        entry.jar.sha256,
				ExtractorVersion: extractorVersion,
				ShardPath:        entry.result.ShardPath,
				ShardChecksum:    &checksum,
				SizeBytes:        entry.result.SizeBytes,
				Source:           "remote",
			})
			if err != nil {
				fmt.Fprintf(warnings, "warning: registry prefetch: %v\n", err)
				stats.Failures++
				continue
			}
			stats.Downloaded++
			stats.Shards = append(stats.Shards, ShardRef{
				ShardID: entry.result.ShardID,
				Path:    entry.result.ShardPath,
			})
		}
	}

	// The JVM indexer refreshes last_access_at itself; on a fully covered
	// classpath it never runs, so keep LRU order accurate here.
	if err := manifestDB.Touch(hitShardIDs); err != nil {
		fmt.Fprintf(warnings, "warning: registry prefetch: %v\n", err)
	}

	stats.Complete = stats.HashFailures == 0 &&
		stats.Misses == 0 &&
		stats.Failures == 0 &&
		len(stats.Shards) == stats.JarsUnique &&
		stats.JarsUnique > 0
	return stats
}

func hashFile(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	digest := sha256.New()
	if _, err := io.Copy(digest, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}
