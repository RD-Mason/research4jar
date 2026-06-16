package query

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

func writeShardsTable(t *testing.T, path string, coords map[string]string) {
	t.Helper()
	db, err := sql.Open("sqlite", "file:"+filepath.ToSlash(path))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(
		`CREATE TABLE IF NOT EXISTS shards (
		   shard_id TEXT PRIMARY KEY, jar_coordinate TEXT, jar_filename TEXT NOT NULL,
		   jar_sha256 TEXT, extractor_version INTEGER, shard_path TEXT, shard_checksum TEXT,
		   size_bytes INTEGER, created_at INTEGER, last_access_at INTEGER, source TEXT)`,
	); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec("DELETE FROM shards"); err != nil {
		t.Fatal(err)
	}
	for id, coord := range coords {
		if _, err := db.Exec(
			"INSERT INTO shards(shard_id, jar_coordinate, jar_filename) VALUES (?, ?, ?)",
			id, coord, "x.jar",
		); err != nil {
			t.Fatal(err)
		}
	}
}

// TestManifestRowsCacheByMtime verifies the cache serves stale rows while the
// mtime is unchanged and reloads once the mtime moves.
func TestManifestRowsCacheByMtime(t *testing.T) {
	manifestCacheMu.Lock()
	manifestCachePath, manifestCacheMtime, manifestCacheRows = "", 0, nil
	manifestCacheMu.Unlock()

	path := filepath.Join(t.TempDir(), "manifest.db")
	writeShardsTable(t, path, map[string]string{"sha1@2": "org.example:a:1"})
	fixed := time.Unix(1_600_000_000, 0)
	if err := os.Chtimes(path, fixed, fixed); err != nil {
		t.Fatal(err)
	}

	rows, err := loadManifestRows(context.Background(), path)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].source != "org.example:a:1" {
		t.Fatalf("first load = %+v", rows)
	}

	// Rewrite content but restore the same mtime: the cache must serve the
	// previously parsed rows, ignoring the on-disk change.
	writeShardsTable(t, path, map[string]string{"sha2@2": "org.example:b:2"})
	if err := os.Chtimes(path, fixed, fixed); err != nil {
		t.Fatal(err)
	}
	rows, _ = loadManifestRows(context.Background(), path)
	if len(rows) != 1 || rows[0].source != "org.example:a:1" {
		t.Fatalf("expected cached rows on unchanged mtime, got %+v", rows)
	}

	// Bump the mtime: the cache invalidates and reloads the new content.
	bumped := fixed.Add(time.Hour)
	if err := os.Chtimes(path, bumped, bumped); err != nil {
		t.Fatal(err)
	}
	rows, _ = loadManifestRows(context.Background(), path)
	if len(rows) != 1 || rows[0].source != "org.example:b:2" {
		t.Fatalf("expected reload after mtime bump, got %+v", rows)
	}
}
