// Package manifest gives the Go CLI read-write access to the global shard
// manifest so registry prefetch can register downloaded shards and cache GC
// can evict rows. The schema matches the Kotlin Manifest exactly; SQLite
// files remain the only contract between the two halves.
package manifest

import (
	"database/sql"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

type Shard struct {
	ShardID          string
	JarCoordinate    *string
	JarFilename      string
	JarSHA256        string
	ExtractorVersion int
	ShardPath        string
	ShardChecksum    *string
	SizeBytes        int64
	CreatedAt        int64
	LastAccessAt     int64
	Source           string
}

// DigestEntry is one cached jar content hash, valid only while the jar's size
// and mtime are unchanged. It lets repeated indexing/prefetch skip re-hashing
// the (immutable) dependency jars that dominate the warm path.
type DigestEntry struct {
	SizeBytes   int64
	MtimeMillis int64
	SHA256      string
}

type DB struct {
	db *sql.DB
}

// Open opens (creating if needed) the manifest database read-write with the
// same pragmas the Kotlin indexer uses, so concurrent runs stay safe.
func Open(path string) (*DB, error) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(absolute), 0o755); err != nil {
		return nil, err
	}
	slashPath := filepath.ToSlash(absolute)
	if filepath.VolumeName(absolute) != "" && !strings.HasPrefix(slashPath, "/") {
		slashPath = "/" + slashPath
	}
	uri := &url.URL{Scheme: "file", Path: slashPath}
	values := uri.Query()
	values.Add("_pragma", "journal_mode(WAL)")
	values.Add("_pragma", "busy_timeout(5000)")
	values.Add("_pragma", "synchronous(FULL)")
	uri.RawQuery = values.Encode()

	db, err := sql.Open("sqlite", uri.String())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	if err := createSchema(db); err != nil {
		db.Close()
		return nil, err
	}
	return &DB{db: db}, nil
}

func (m *DB) Close() error { return m.db.Close() }

// Find returns the shard row for an id, or nil when absent.
func (m *DB) Find(shardID string) (*Shard, error) {
	rows, err := m.db.Query(selectColumns+" FROM shards WHERE shard_id = ?", shardID)
	if err != nil {
		return nil, fmt.Errorf("query manifest: %w", err)
	}
	defer rows.Close()
	if !rows.Next() {
		return nil, rows.Err()
	}
	shard, err := scanShard(rows)
	if err != nil {
		return nil, err
	}
	return &shard, nil
}

// List returns every shard row ordered by last access (oldest first), the
// order LRU eviction consumes.
func (m *DB) List() ([]Shard, error) {
	rows, err := m.db.Query(
		selectColumns + " FROM shards ORDER BY last_access_at ASC, shard_id ASC",
	)
	if err != nil {
		return nil, fmt.Errorf("query manifest: %w", err)
	}
	defer rows.Close()
	var shards []Shard
	for rows.Next() {
		shard, err := scanShard(rows)
		if err != nil {
			return nil, err
		}
		shards = append(shards, shard)
	}
	return shards, rows.Err()
}

// Register inserts a shard row; an existing row for the same id is left
// untouched (mirrors the Kotlin INSERT OR IGNORE).
func (m *DB) Register(shard Shard) error {
	now := time.Now().Unix()
	_, err := m.db.Exec(
		`INSERT OR IGNORE INTO shards(
		   shard_id, jar_coordinate, jar_filename, jar_sha256, extractor_version,
		   shard_path, shard_checksum, size_bytes, created_at, last_access_at, source
		 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		shard.ShardID,
		shard.JarCoordinate,
		shard.JarFilename,
		shard.JarSHA256,
		shard.ExtractorVersion,
		shard.ShardPath,
		shard.ShardChecksum,
		shard.SizeBytes,
		now,
		now,
		shard.Source,
	)
	if err != nil {
		return fmt.Errorf("register shard: %w", err)
	}
	return nil
}

// Remove deletes a shard row.
func (m *DB) Remove(shardID string) error {
	if _, err := m.db.Exec("DELETE FROM shards WHERE shard_id = ?", shardID); err != nil {
		return fmt.Errorf("remove shard: %w", err)
	}
	return nil
}

// Touch refreshes last_access_at for the given shard ids.
func (m *DB) Touch(shardIDs []string) error {
	if len(shardIDs) == 0 {
		return nil
	}
	now := time.Now().Unix()
	statement, err := m.db.Prepare("UPDATE shards SET last_access_at = ? WHERE shard_id = ?")
	if err != nil {
		return err
	}
	defer statement.Close()
	for _, shardID := range shardIDs {
		if _, err := statement.Exec(now, shardID); err != nil {
			return fmt.Errorf("touch shard %s: %w", shardID, err)
		}
	}
	return nil
}

// SetLastAccess pins last_access_at to an explicit epoch (used by tests and
// tooling that needs deterministic LRU order).
func (m *DB) SetLastAccess(shardID string, epoch int64) error {
	_, err := m.db.Exec(
		"UPDATE shards SET last_access_at = ? WHERE shard_id = ?", epoch, shardID,
	)
	return err
}

// LoadJarDigests returns the whole jar-digest cache keyed by absolute path.
// Callers validate each entry against the file's current size+mtime before
// trusting the hash; a stale or absent entry simply means "re-hash".
func (m *DB) LoadJarDigests() (map[string]DigestEntry, error) {
	rows, err := m.db.Query(
		"SELECT abs_path, size_bytes, mtime_millis, sha256 FROM jar_digest_cache",
	)
	if err != nil {
		return nil, fmt.Errorf("query jar digests: %w", err)
	}
	defer rows.Close()
	result := map[string]DigestEntry{}
	for rows.Next() {
		var path string
		var entry DigestEntry
		if err := rows.Scan(
			&path, &entry.SizeBytes, &entry.MtimeMillis, &entry.SHA256,
		); err != nil {
			return nil, fmt.Errorf("scan jar digest: %w", err)
		}
		result[path] = entry
	}
	return result, rows.Err()
}

// PutJarDigests upserts cache rows in one transaction. Keys are absolute paths.
func (m *DB) PutJarDigests(entries map[string]DigestEntry) error {
	if len(entries) == 0 {
		return nil
	}
	transaction, err := m.db.Begin()
	if err != nil {
		return err
	}
	statement, err := transaction.Prepare(
		`INSERT OR REPLACE INTO jar_digest_cache(abs_path, size_bytes, mtime_millis, sha256)
		 VALUES (?, ?, ?, ?)`,
	)
	if err != nil {
		transaction.Rollback()
		return err
	}
	defer statement.Close()
	for path, entry := range entries {
		if _, err := statement.Exec(
			path, entry.SizeBytes, entry.MtimeMillis, entry.SHA256,
		); err != nil {
			transaction.Rollback()
			return fmt.Errorf("put jar digest %s: %w", path, err)
		}
	}
	return transaction.Commit()
}

const selectColumns = `SELECT shard_id, jar_coordinate, jar_filename, jar_sha256,
       extractor_version, shard_path, shard_checksum, size_bytes,
       created_at, last_access_at, source`

func scanShard(rows *sql.Rows) (Shard, error) {
	var shard Shard
	var coordinate, checksum, source sql.NullString
	var size, created, accessed sql.NullInt64
	if err := rows.Scan(
		&shard.ShardID, &coordinate, &shard.JarFilename, &shard.JarSHA256,
		&shard.ExtractorVersion, &shard.ShardPath, &checksum, &size,
		&created, &accessed, &source,
	); err != nil {
		return Shard{}, fmt.Errorf("scan manifest row: %w", err)
	}
	if coordinate.Valid {
		shard.JarCoordinate = &coordinate.String
	}
	if checksum.Valid {
		shard.ShardChecksum = &checksum.String
	}
	shard.SizeBytes = size.Int64
	shard.CreatedAt = created.Int64
	shard.LastAccessAt = accessed.Int64
	shard.Source = source.String
	return shard, nil
}

// createSchema mirrors the Kotlin Manifest DDL verbatim.
func createSchema(db *sql.DB) error {
	statements := []string{
		`CREATE TABLE IF NOT EXISTS shards (
		  shard_id TEXT PRIMARY KEY,
		  jar_coordinate TEXT,
		  jar_filename TEXT NOT NULL,
		  jar_sha256 TEXT NOT NULL,
		  extractor_version INTEGER NOT NULL,
		  shard_path TEXT NOT NULL,
		  shard_checksum TEXT,
		  size_bytes INTEGER,
		  created_at INTEGER,
		  last_access_at INTEGER,
		  source TEXT
		)`,
		"CREATE INDEX IF NOT EXISTS idx_shards_sha ON shards(jar_sha256)",
		"CREATE INDEX IF NOT EXISTS idx_shards_coord ON shards(jar_coordinate)",
		// Additive jar-digest cache; keyed by absolute path, validated against
		// size+mtime so unchanged jars skip re-hashing on warm runs.
		`CREATE TABLE IF NOT EXISTS jar_digest_cache (
		  abs_path TEXT PRIMARY KEY,
		  size_bytes INTEGER NOT NULL,
		  mtime_millis INTEGER NOT NULL,
		  sha256 TEXT NOT NULL
		)`,
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			return fmt.Errorf("create manifest schema: %w", err)
		}
	}
	return nil
}
