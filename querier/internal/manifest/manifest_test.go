package manifest

import (
	"path/filepath"
	"testing"
)

func TestJarDigestCacheRoundTrip(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "manifest.db"))
	if err != nil {
		t.Fatalf("open manifest: %v", err)
	}
	defer db.Close()

	empty, err := db.LoadJarDigests()
	if err != nil {
		t.Fatalf("load empty: %v", err)
	}
	if len(empty) != 0 {
		t.Fatalf("fresh cache should be empty, got %d", len(empty))
	}

	entries := map[string]DigestEntry{
		"/jars/foo.jar": {SizeBytes: 10, MtimeMillis: 1000, SHA256: "aaa"},
		"/jars/bar.jar": {SizeBytes: 20, MtimeMillis: 2000, SHA256: "bbb"},
	}
	if err := db.PutJarDigests(entries); err != nil {
		t.Fatalf("put: %v", err)
	}
	got, err := db.LoadJarDigests()
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if len(got) != 2 || got["/jars/foo.jar"] != entries["/jars/foo.jar"] {
		t.Fatalf("round-trip mismatch: %+v", got)
	}

	// Upsert replaces the row for an existing path and keeps the count stable.
	if err := db.PutJarDigests(map[string]DigestEntry{
		"/jars/foo.jar": {SizeBytes: 11, MtimeMillis: 1100, SHA256: "ccc"},
	}); err != nil {
		t.Fatalf("upsert: %v", err)
	}
	got, _ = db.LoadJarDigests()
	if len(got) != 2 {
		t.Fatalf("upsert changed count: %d", len(got))
	}
	if got["/jars/foo.jar"] != (DigestEntry{SizeBytes: 11, MtimeMillis: 1100, SHA256: "ccc"}) {
		t.Fatalf("upsert did not replace: %+v", got["/jars/foo.jar"])
	}

	if err := db.PutJarDigests(nil); err != nil {
		t.Fatalf("empty put should be a no-op: %v", err)
	}
}
