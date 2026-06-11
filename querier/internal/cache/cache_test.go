package cache

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"dev.springdep/querier/internal/manifest"
	"dev.springdep/querier/internal/paths"
)

const currentVersion = 2

func testHome(t *testing.T) (paths.DataPaths, *manifest.DB) {
	t.Helper()
	home := t.TempDir()
	dataPaths := paths.DataPaths{
		Home:     home,
		Manifest: filepath.Join(home, "manifest.db"),
		Shards:   filepath.Join(home, "shards"),
		Sessions: filepath.Join(home, "sessions"),
	}
	for _, dir := range []string{dataPaths.Shards, dataPaths.Sessions} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	manifestDB, err := manifest.Open(dataPaths.Manifest)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { manifestDB.Close() })
	return dataPaths, manifestDB
}

// addShard creates a shard file of the given size and registers it with a
// controlled last_access_at so LRU order is deterministic.
func addShard(
	t *testing.T, dataPaths paths.DataPaths, manifestDB *manifest.DB,
	name string, version int, size int, accessedAt time.Time,
) string {
	t.Helper()
	sha := strings.Repeat(fmt.Sprintf("%02x", len(name)%256), 32)[:62] + fmt.Sprintf("%02d", len(name)%100)
	shardID := fmt.Sprintf("%s-%s@%d", sha[:8], name, version)
	path := filepath.Join(dataPaths.Shards, shardID+".db")
	if err := os.WriteFile(path, make([]byte, size), 0o644); err != nil {
		t.Fatal(err)
	}
	checksum := "irrelevant"
	if err := manifestDB.Register(manifest.Shard{
		ShardID:          shardID,
		JarFilename:      name + ".jar",
		JarSHA256:        sha,
		ExtractorVersion: version,
		ShardPath:        path,
		ShardChecksum:    &checksum,
		SizeBytes:        int64(size),
		Source:           "local",
	}); err != nil {
		t.Fatal(err)
	}
	// Register stamps "now"; rewrite to the requested access time.
	if !accessedAt.IsZero() {
		setAccess(t, dataPaths.Manifest, shardID, accessedAt.Unix())
	}
	return shardID
}

func setAccess(t *testing.T, manifestPath, shardID string, epoch int64) {
	t.Helper()
	db, err := manifest.Open(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.SetLastAccess(shardID, epoch); err != nil {
		t.Fatal(err)
	}
}

func TestGCRemovesStaleVersionsAndOrphans(t *testing.T) {
	dataPaths, manifestDB := testHome(t)
	stale := addShard(t, dataPaths, manifestDB, "old", 1, 100, time.Time{})
	kept := addShard(t, dataPaths, manifestDB, "new", currentVersion, 100, time.Time{})
	orphan := filepath.Join(dataPaths.Shards, "orphan@2.db")
	if err := os.WriteFile(orphan, make([]byte, 50), 0o644); err != nil {
		t.Fatal(err)
	}

	result, err := GC(dataPaths, currentVersion, GCOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if result.RemovedStaleVersion != 1 || result.RemovedOrphans != 1 {
		t.Fatalf("result = %+v, want 1 stale + 1 orphan removed", result)
	}
	if result.RemainingShards != 1 {
		t.Fatalf("remaining = %d, want 1", result.RemainingShards)
	}
	if _, err := os.Stat(orphan); !os.IsNotExist(err) {
		t.Fatal("orphan file should be deleted")
	}
	row, err := manifestDB.Find(stale)
	if err != nil || row != nil {
		t.Fatalf("stale row should be gone, got %+v err %v", row, err)
	}
	row, err = manifestDB.Find(kept)
	if err != nil || row == nil {
		t.Fatalf("current row should remain, err %v", err)
	}
}

func TestGCEvictsLRUOverBudget(t *testing.T) {
	dataPaths, manifestDB := testHome(t)
	oldest := addShard(
		t, dataPaths, manifestDB, "oldest", currentVersion, 100,
		time.Now().Add(-72*time.Hour),
	)
	middle := addShard(
		t, dataPaths, manifestDB, "middle", currentVersion, 100,
		time.Now().Add(-48*time.Hour),
	)
	newest := addShard(
		t, dataPaths, manifestDB, "newest", currentVersion, 100,
		time.Now().Add(-24*time.Hour),
	)

	result, err := GC(dataPaths, currentVersion, GCOptions{MaxShardBytes: 250})
	if err != nil {
		t.Fatal(err)
	}
	if result.RemovedLRU != 1 {
		t.Fatalf("result = %+v, want 1 LRU eviction", result)
	}
	if row, _ := manifestDB.Find(oldest); row != nil {
		t.Fatal("oldest shard should be evicted first")
	}
	for _, id := range []string{middle, newest} {
		if row, _ := manifestDB.Find(id); row == nil {
			t.Fatalf("%s should survive", id)
		}
	}
}

func TestGCDryRunRemovesNothing(t *testing.T) {
	dataPaths, manifestDB := testHome(t)
	stale := addShard(t, dataPaths, manifestDB, "old", 1, 100, time.Time{})

	result, err := GC(dataPaths, currentVersion, GCOptions{DryRun: true})
	if err != nil {
		t.Fatal(err)
	}
	if result.RemovedStaleVersion != 1 || !result.DryRun {
		t.Fatalf("result = %+v, want dry-run reporting 1 stale", result)
	}
	if row, _ := manifestDB.Find(stale); row == nil {
		t.Fatal("dry run must keep manifest rows")
	}
	if len(result.Removed) != 1 {
		t.Fatalf("removed list = %v, stale files must not double-count as orphans", result.Removed)
	}
}

func TestGCRemovesExpiredSessions(t *testing.T) {
	dataPaths, _ := testHome(t)
	expired := filepath.Join(dataPaths.Sessions, "expired.db")
	fresh := filepath.Join(dataPaths.Sessions, "fresh.db")
	for _, path := range []string{expired, fresh} {
		if err := os.WriteFile(path, make([]byte, 10), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	oldTime := time.Now().Add(-40 * 24 * time.Hour)
	if err := os.Chtimes(expired, oldTime, oldTime); err != nil {
		t.Fatal(err)
	}

	result, err := GC(dataPaths, currentVersion, GCOptions{MaxSessionAge: 30 * 24 * time.Hour})
	if err != nil {
		t.Fatal(err)
	}
	if result.RemovedSessions != 1 || result.RemainingSessions != 1 {
		t.Fatalf("result = %+v, want 1 removed + 1 remaining session", result)
	}
	if _, err := os.Stat(expired); !os.IsNotExist(err) {
		t.Fatal("expired session should be deleted")
	}
	if _, err := os.Stat(fresh); err != nil {
		t.Fatal("fresh session should remain")
	}
}

func TestCollectStats(t *testing.T) {
	dataPaths, manifestDB := testHome(t)
	addShard(t, dataPaths, manifestDB, "one", currentVersion, 100, time.Time{})
	addShard(t, dataPaths, manifestDB, "two", 1, 50, time.Time{})
	session := filepath.Join(dataPaths.Sessions, "abc.db")
	if err := os.WriteFile(session, make([]byte, 25), 0o644); err != nil {
		t.Fatal(err)
	}

	stats, err := CollectStats(dataPaths, currentVersion)
	if err != nil {
		t.Fatal(err)
	}
	if stats.ShardCount != 2 || stats.ShardBytes != 150 {
		t.Fatalf("stats = %+v, want 2 shards / 150 bytes", stats)
	}
	if stats.ShardsStaleVersion != 1 {
		t.Fatalf("stats = %+v, want 1 stale-version shard", stats)
	}
	if stats.SessionCount != 1 || stats.SessionBytes != 25 {
		t.Fatalf("stats = %+v, want 1 session / 25 bytes", stats)
	}
}

func TestParseSizeAndAge(t *testing.T) {
	size, err := ParseSize("2G")
	if err != nil || size != 2<<30 {
		t.Fatalf("2G -> %d, %v", size, err)
	}
	if _, err := ParseSize("abc"); err == nil {
		t.Fatal("abc should not parse as a size")
	}
	age, err := ParseAge("30d")
	if err != nil || age != 30*24*time.Hour {
		t.Fatalf("30d -> %v, %v", age, err)
	}
	if _, err := ParseAge("30"); err == nil {
		t.Fatal("30 without a unit should not parse as an age")
	}
}
