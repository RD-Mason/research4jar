package query

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"sync"
)

// cachedManifestRow is one shards-table row, holding just enough to satisfy
// both loadSourceJars and loadManifestSources without reopening and rescanning
// the manifest on every query.
type cachedManifestRow struct {
	shardID    string
	coordinate string // "" when the jar has no recorded Maven coordinate
	filename   string
	source     string // COALESCE(NULLIF(coordinate, ''), filename)
}

// The manifest changes only when an indexer/prefetch run rewrites it, so its
// shard rows are cached process-wide and keyed by path+mtime. A long-lived MCP
// server reuses the parsed rows across tool calls; a concurrent index run that
// rewrites the manifest bumps the mtime and is picked up on the next call. We
// cache the parsed data, never a live mutable *sql.DB handle.
var (
	manifestCacheMu    sync.Mutex
	manifestCachePath  string
	manifestCacheMtime int64
	manifestCacheRows  []cachedManifestRow
)

// loadManifestRows returns the manifest's shard rows, served from the process
// cache when the file's mtime is unchanged. Returns (nil, nil) when there is no
// manifest path. A stat failure disables the cache for the call but still reads
// the manifest, so correctness never depends on the cache.
func loadManifestRows(ctx context.Context, manifestPath string) ([]cachedManifestRow, error) {
	if manifestPath == "" {
		return nil, nil
	}
	info, statErr := os.Stat(manifestPath)
	if statErr == nil {
		mtime := info.ModTime().UnixNano()
		manifestCacheMu.Lock()
		if manifestCachePath == manifestPath &&
			manifestCacheMtime == mtime && manifestCacheRows != nil {
			rows := manifestCacheRows
			manifestCacheMu.Unlock()
			return rows, nil
		}
		manifestCacheMu.Unlock()
	}

	rows, err := scanManifestRows(ctx, manifestPath)
	if err != nil {
		return nil, err
	}
	if statErr == nil {
		manifestCacheMu.Lock()
		manifestCachePath = manifestPath
		manifestCacheMtime = info.ModTime().UnixNano()
		manifestCacheRows = rows
		manifestCacheMu.Unlock()
	}
	return rows, nil
}

func scanManifestRows(ctx context.Context, manifestPath string) ([]cachedManifestRow, error) {
	manifest, err := openReadOnly(manifestPath, false)
	if err != nil {
		return nil, fmt.Errorf("open manifest database: %w", err)
	}
	defer manifest.Close()
	rows, err := manifest.QueryContext(
		ctx,
		`SELECT shard_id, jar_coordinate, jar_filename,
		        COALESCE(NULLIF(jar_coordinate, ''), jar_filename)
		 FROM shards`,
	)
	if err != nil {
		return nil, fmt.Errorf("query manifest sources: %w", err)
	}
	defer rows.Close()
	result := []cachedManifestRow{}
	for rows.Next() {
		var row cachedManifestRow
		var coordinate sql.NullString
		if err := rows.Scan(&row.shardID, &coordinate, &row.filename, &row.source); err != nil {
			return nil, fmt.Errorf("scan manifest source: %w", err)
		}
		if coordinate.Valid {
			row.coordinate = coordinate.String
		}
		result = append(result, row)
	}
	return result, rows.Err()
}
