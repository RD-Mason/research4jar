package registry

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"dev.springdep/querier/internal/manifest"
	_ "modernc.org/sqlite"
)

const testExtractorVersion = 2

// writeFakeShard creates a minimal but structurally valid shard file: a
// SQLite database with a shard_meta row carrying the expected identity.
func writeFakeShard(t *testing.T, path, jarSHA string, extractorVersion int, coordinate string) {
	t.Helper()
	db, err := sql.Open("sqlite", "file:"+filepath.ToSlash(path))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	statements := []string{
		`CREATE TABLE shard_meta (
		   jar_coordinate TEXT, jar_sha256 TEXT NOT NULL,
		   extractor_version INTEGER NOT NULL, schema_version INTEGER NOT NULL,
		   created_at INTEGER, class_count INTEGER
		 )`,
		fmt.Sprintf(
			`INSERT INTO shard_meta VALUES ('%s', '%s', %d, 2, 0, 0)`,
			coordinate, jarSHA, extractorVersion,
		),
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
}

func sha256Hex(data []byte) string {
	digest := sha256.Sum256(data)
	return hex.EncodeToString(digest[:])
}

// buildRegistry exports one fake shard through Export and serves the tree,
// returning the server, the jar hash, and the export directory.
func buildRegistry(t *testing.T, signKeyPath string) (*httptest.Server, string, string) {
	t.Helper()
	workDir := t.TempDir()
	jarSHA := strings.Repeat("ab", 32)
	shardPath := filepath.Join(workDir, "shard.db")
	writeFakeShard(t, shardPath, jarSHA, testExtractorVersion, "org.example:demo:1.0")
	shardBytes, err := os.ReadFile(shardPath)
	if err != nil {
		t.Fatal(err)
	}
	checksum := sha256Hex(shardBytes)

	var signingKey []byte
	if signKeyPath != "" {
		if _, err := GenerateKey(signKeyPath); err != nil {
			t.Fatal(err)
		}
		key, err := LoadSigningKey(signKeyPath)
		if err != nil {
			t.Fatal(err)
		}
		signingKey = key
	}

	exportDir := filepath.Join(workDir, "registry")
	result, err := Export(
		[]manifest.Shard{{
			ShardID:          jarSHA + "@2",
			JarFilename:      "demo.jar",
			JarSHA256:        jarSHA,
			ExtractorVersion: testExtractorVersion,
			ShardPath:        shardPath,
			ShardChecksum:    &checksum,
			SizeBytes:        int64(len(shardBytes)),
			Source:           "local",
		}},
		testExtractorVersion, exportDir, signingKey, "test", os.Stderr,
	)
	if err != nil {
		t.Fatal(err)
	}
	if result.Exported != 1 {
		t.Fatalf("exported %d shards, want 1", result.Exported)
	}
	server := httptest.NewServer(http.FileServer(http.Dir(exportDir)))
	t.Cleanup(server.Close)
	return server, jarSHA, exportDir
}

func TestExportFetchRoundTrip(t *testing.T) {
	server, jarSHA, _ := buildRegistry(t, "")
	client, err := NewClient(server.URL, "")
	if err != nil {
		t.Fatal(err)
	}
	shardsDir := t.TempDir()
	result, err := client.Fetch(context.Background(), jarSHA, testExtractorVersion, shardsDir)
	if err != nil {
		t.Fatal(err)
	}
	if result.ShardID != jarSHA+"@2" {
		t.Fatalf("shard id %s", result.ShardID)
	}
	if result.JarCoordinate == nil || *result.JarCoordinate != "org.example:demo:1.0" {
		t.Fatalf("coordinate %v, want org.example:demo:1.0", result.JarCoordinate)
	}
	if _, err := os.Stat(result.ShardPath); err != nil {
		t.Fatalf("installed shard missing: %v", err)
	}
	installed, err := os.ReadFile(result.ShardPath)
	if err != nil {
		t.Fatal(err)
	}
	if sha256Hex(installed) != result.Checksum {
		t.Fatal("installed bytes do not match reported checksum")
	}
}

func TestFetchMissReturnsNotFound(t *testing.T) {
	server, _, _ := buildRegistry(t, "")
	client, err := NewClient(server.URL, "")
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.Fetch(
		context.Background(), strings.Repeat("cd", 32), testExtractorVersion, t.TempDir(),
	)
	if !errors.Is(err, ErrShardNotFound) {
		t.Fatalf("err = %v, want ErrShardNotFound", err)
	}
}

func TestFetchRejectsTamperedShard(t *testing.T) {
	server, jarSHA, exportDir := buildRegistry(t, "")
	shardFile := filepath.Join(exportDir, "v2", jarSHA+".db")
	content, err := os.ReadFile(shardFile)
	if err != nil {
		t.Fatal(err)
	}
	content[len(content)-1] ^= 0xFF
	if err := os.WriteFile(shardFile, content, 0o644); err != nil {
		t.Fatal(err)
	}
	client, err := NewClient(server.URL, "")
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.Fetch(context.Background(), jarSHA, testExtractorVersion, t.TempDir())
	if err == nil || !strings.Contains(err.Error(), "checksum mismatch") {
		t.Fatalf("err = %v, want checksum mismatch", err)
	}
}

func TestFetchVerifiesSignature(t *testing.T) {
	keyPath := filepath.Join(t.TempDir(), "signing.key")
	server, jarSHA, exportDir := buildRegistry(t, keyPath)

	metadata, err := os.ReadFile(filepath.Join(exportDir, "registry.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(metadata), `"signed": true`) {
		t.Fatalf("registry.json should record signing: %s", metadata)
	}

	publicKey, err := GenerateKey(filepath.Join(t.TempDir(), "other.key"))
	if err != nil {
		t.Fatal(err)
	}
	// Wrong key: signature must be rejected.
	wrongClient, err := NewClient(server.URL, publicKey)
	if err != nil {
		t.Fatal(err)
	}
	_, err = wrongClient.Fetch(context.Background(), jarSHA, testExtractorVersion, t.TempDir())
	if err == nil || !strings.Contains(err.Error(), "signature verification failed") {
		t.Fatalf("err = %v, want signature verification failure", err)
	}

	// Matching key: load the signer, derive its public half, fetch succeeds.
	signingKey, err := LoadSigningKey(keyPath)
	if err != nil {
		t.Fatal(err)
	}
	publicHex := hex.EncodeToString(signingKey.Public().(ed25519.PublicKey))
	rightClient, err := NewClient(server.URL, publicHex)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := rightClient.Fetch(
		context.Background(), jarSHA, testExtractorVersion, t.TempDir(),
	); err != nil {
		t.Fatalf("fetch with correct public key failed: %v", err)
	}
}

func TestFetchRejectsWrongEmbeddedIdentity(t *testing.T) {
	workDir := t.TempDir()
	jarSHA := strings.Repeat("ef", 32)
	otherSHA := strings.Repeat("12", 32)
	shardPath := filepath.Join(workDir, "shard.db")
	// Shard embeds otherSHA but is published under jarSHA.
	writeFakeShard(t, shardPath, otherSHA, testExtractorVersion, "")
	shardBytes, err := os.ReadFile(shardPath)
	if err != nil {
		t.Fatal(err)
	}
	versionDir := filepath.Join(workDir, "registry", "v2")
	if err := os.MkdirAll(versionDir, 0o755); err != nil {
		t.Fatal(err)
	}
	name := jarSHA + ".db"
	if err := os.WriteFile(filepath.Join(versionDir, name), shardBytes, 0o644); err != nil {
		t.Fatal(err)
	}
	sidecar := sha256Hex(shardBytes) + "  " + name + "\n"
	if err := os.WriteFile(filepath.Join(versionDir, name+".sha256"), []byte(sidecar), 0o644); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.FileServer(http.Dir(filepath.Join(workDir, "registry"))))
	defer server.Close()

	client, err := NewClient(server.URL, "")
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.Fetch(context.Background(), jarSHA, testExtractorVersion, t.TempDir())
	if err == nil || !strings.Contains(err.Error(), "embeds jar sha") {
		t.Fatalf("err = %v, want embedded identity rejection", err)
	}
}

func TestPrefetchInstallsAndRegisters(t *testing.T) {
	// The registry is keyed by the jar's hash, so write the jar first and
	// publish a shard under its real hash.
	workDir := t.TempDir()
	jarPath := filepath.Join(workDir, "demo.jar")
	if err := os.WriteFile(jarPath, []byte("fake jar bytes"), 0o644); err != nil {
		t.Fatal(err)
	}
	jarContent, err := os.ReadFile(jarPath)
	if err != nil {
		t.Fatal(err)
	}
	realSHA := sha256Hex(jarContent)

	registryDir := t.TempDir()
	shardPath := filepath.Join(workDir, "shard.db")
	writeFakeShard(t, shardPath, realSHA, testExtractorVersion, "org.example:demo:1.0")
	shardBytes, err := os.ReadFile(shardPath)
	if err != nil {
		t.Fatal(err)
	}
	checksum := sha256Hex(shardBytes)
	_, err = Export(
		[]manifest.Shard{{
			ShardID:          realSHA + "@2",
			JarFilename:      "demo.jar",
			JarSHA256:        realSHA,
			ExtractorVersion: testExtractorVersion,
			ShardPath:        shardPath,
			ShardChecksum:    &checksum,
			SizeBytes:        int64(len(shardBytes)),
		}},
		testExtractorVersion, registryDir, nil, "test", os.Stderr,
	)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.FileServer(http.Dir(registryDir)))
	defer server.Close()
	client, err := NewClient(server.URL, "")
	if err != nil {
		t.Fatal(err)
	}

	homeDir := t.TempDir()
	manifestDB, err := manifest.Open(filepath.Join(homeDir, "manifest.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer manifestDB.Close()
	shardsDir := filepath.Join(homeDir, "shards")

	stats := Prefetch(
		context.Background(), client, manifestDB, shardsDir,
		testExtractorVersion, []string{jarPath}, os.Stderr,
	)
	if stats.Downloaded != 1 || stats.Failures != 0 {
		t.Fatalf("stats = %+v, want 1 download", stats)
	}
	row, err := manifestDB.Find(realSHA + "@2")
	if err != nil {
		t.Fatal(err)
	}
	if row == nil || row.Source != "remote" {
		t.Fatalf("manifest row = %+v, want source=remote", row)
	}
	if row.JarCoordinate == nil || *row.JarCoordinate != "org.example:demo:1.0" {
		t.Fatalf("coordinate = %v", row.JarCoordinate)
	}

	// Second run: pure cache hit, no download.
	stats = Prefetch(
		context.Background(), client, manifestDB, shardsDir,
		testExtractorVersion, []string{jarPath}, os.Stderr,
	)
	if stats.CacheHits != 1 || stats.Downloaded != 0 {
		t.Fatalf("stats = %+v, want 1 cache hit", stats)
	}
}
