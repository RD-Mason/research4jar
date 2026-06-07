package project

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLocateSearchesParentsAndLoadValidatesPointer(t *testing.T) {
	root := t.TempDir()
	nested := filepath.Join(root, "a", "b")
	if err := os.MkdirAll(filepath.Join(root, ".springdep"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(nested, 0o755); err != nil {
		t.Fatal(err)
	}
	pointerPath := filepath.Join(root, ".springdep", "project.json")
	body := `{
	  "schema_version": 1,
	  "extractor_version": 1,
	  "classpath_fingerprint": "0123456789abcdef",
	  "session_db_path": "/tmp/session.db",
	  "built_at": 1,
	  "coverage": {"jars_total": 2, "jars_indexed": 1, "jars_missing": ["broken.jar"]}
	}`
	if err := os.WriteFile(pointerPath, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}

	previous, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(nested); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(previous) })

	located, err := Locate("")
	if err != nil {
		t.Fatal(err)
	}
	locatedInfo, err := os.Stat(located)
	if err != nil {
		t.Fatal(err)
	}
	expectedInfo, err := os.Stat(pointerPath)
	if err != nil {
		t.Fatal(err)
	}
	if !os.SameFile(locatedInfo, expectedInfo) {
		t.Fatalf("Locate() = %q, want %q", located, pointerPath)
	}
	pointer, err := Load(located)
	if err != nil {
		t.Fatal(err)
	}
	if pointer.Coverage.JarsMissing[0] != "broken.jar" {
		t.Fatalf("unexpected pointer: %#v", pointer)
	}
}

func TestLocateExplicitMissing(t *testing.T) {
	if _, err := Locate(t.TempDir()); err != ErrNotFound {
		t.Fatalf("Locate() error = %v, want ErrNotFound", err)
	}
}
