package jarsource

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func touch(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestResolveDirectoryWalksRecursively(t *testing.T) {
	root := t.TempDir()
	touch(t, filepath.Join(root, "a.jar"))
	touch(t, filepath.Join(root, "nested", "b.jar"))
	touch(t, filepath.Join(root, "nested", "notes.txt"))

	jars, err := Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(jars) != 2 {
		t.Fatalf("jars = %v, want 2 entries", jars)
	}
}

func TestResolveCommaListDeduplicates(t *testing.T) {
	root := t.TempDir()
	first := filepath.Join(root, "a.jar")
	second := filepath.Join(root, "b.jar")
	touch(t, first)
	touch(t, second)

	jars, err := Resolve(first + "," + second + ", " + first)
	if err != nil {
		t.Fatal(err)
	}
	if len(jars) != 2 {
		t.Fatalf("jars = %v, want 2 deduplicated entries", jars)
	}
}

func TestResolveGlobs(t *testing.T) {
	root := t.TempDir()
	touch(t, filepath.Join(root, "lib", "a.jar"))
	touch(t, filepath.Join(root, "lib", "deep", "b.jar"))
	touch(t, filepath.Join(root, "lib", "deep", "c.war"))

	flat, err := Resolve(filepath.Join(root, "lib", "*.jar"))
	if err != nil {
		t.Fatal(err)
	}
	if len(flat) != 1 || !strings.HasSuffix(flat[0], "a.jar") {
		t.Fatalf("flat glob = %v, want only a.jar", flat)
	}

	recursive, err := Resolve(filepath.Join(root, "lib", "**", "*.jar"))
	if err != nil {
		t.Fatal(err)
	}
	if len(recursive) != 2 {
		t.Fatalf("recursive glob = %v, want a.jar and b.jar", recursive)
	}
}

func TestResolveEmpty(t *testing.T) {
	jars, err := Resolve("  ")
	if err != nil || jars != nil {
		t.Fatalf("empty spec -> %v, %v", jars, err)
	}
}
