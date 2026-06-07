package query

import (
	"context"
	"database/sql"
	"encoding/json"
	"path/filepath"
	"testing"

	"dev.springdep/querier/internal/project"
)

func TestFindImplementationsUsesSymbolicInterfaceReference(t *testing.T) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "sessions", "fingerprint.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSymbolSession(t, sessionPath)
	createSymbolManifest(t, manifestPath)

	pointer := symbolPointer(sessionPath)
	response, err := FindImplementations(
		context.Background(),
		pointer,
		manifestPath,
		"example.Contract",
		1,
		20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 1 || len(response.Results) != 1 {
		t.Fatalf("unexpected response: %#v", response)
	}
	result := response.Results[0]
	if result.FQN != "other.DirectImplementation" {
		t.Fatalf("implementation = %q", result.FQN)
	}
	if result.SourceJar != "com.example:implementation:1.0" {
		t.Fatalf("source jar = %q", result.SourceJar)
	}
	if result.Attributes != nil {
		t.Fatalf("implementation attributes = %s, want null", result.Attributes)
	}
}

func TestFindByAnnotationReturnsStructuredAttributes(t *testing.T) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "sessions", "fingerprint.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSymbolSession(t, sessionPath)
	createSymbolManifest(t, manifestPath)

	response, err := FindByAnnotation(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		"example.Marker",
		1,
		20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 1 || response.Results[0].FQN != "other.Marked" {
		t.Fatalf("unexpected response: %#v", response)
	}
	var attributes map[string]any
	if err := json.Unmarshal(response.Results[0].Attributes, &attributes); err != nil {
		t.Fatal(err)
	}
	if attributes["value"] != "direct" {
		t.Fatalf("attributes = %#v", attributes)
	}
	if response.Query.Command != "find-by-annotation" ||
		response.Coverage.ExtractorVersion != 2 {
		t.Fatalf("unexpected contract: %#v", response)
	}
}

func symbolPointer(sessionPath string) project.Pointer {
	return project.Pointer{
		SchemaVersion:    2,
		ExtractorVersion: 2,
		SessionDBPath:    sessionPath,
		Coverage: project.Coverage{
			JarsTotal:   3,
			JarsIndexed: 3,
		},
	}
}

func createSymbolSession(t *testing.T, path string) {
	t.Helper()
	if err := mkdirParent(path); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	statements := []string{
		`CREATE TABLE classes (
		  id INTEGER PRIMARY KEY, fqn TEXT NOT NULL, kind TEXT, super_fqn TEXT,
		  modifiers INTEGER, is_abstract INTEGER, source_file TEXT,
		  source_shard_id TEXT NOT NULL
		)`,
		`CREATE TABLE class_interfaces (
		  class_id INTEGER NOT NULL, interface_fqn TEXT NOT NULL,
		  source_shard_id TEXT NOT NULL
		)`,
		`CREATE TABLE annotations (
		  id INTEGER PRIMARY KEY, target_kind TEXT NOT NULL, target_id INTEGER NOT NULL,
		  annotation_fqn TEXT NOT NULL, attributes TEXT, source_shard_id TEXT NOT NULL
		)`,
		`INSERT INTO classes (id, fqn, kind, source_shard_id) VALUES
		  (1, 'example.Contract', 'interface', 'api@2'),
		  (2, 'other.DirectImplementation', 'class', 'impl@2'),
		  (3, 'other.Marked', 'class', 'marked@2')`,
		`INSERT INTO class_interfaces VALUES
		  (2, 'example.Contract', 'impl@2')`,
		`INSERT INTO annotations
		  (target_kind, target_id, annotation_fqn, attributes, source_shard_id)
		  VALUES ('class', 3, 'example.Marker', '{"value":"direct"}', 'marked@2')`,
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
}

func createSymbolManifest(t *testing.T, path string) {
	t.Helper()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	statements := []string{
		`CREATE TABLE shards (
		  shard_id TEXT PRIMARY KEY, jar_coordinate TEXT, jar_filename TEXT NOT NULL
		)`,
		`INSERT INTO shards VALUES
		  ('api@2', 'com.example:api:1.0', 'api.jar'),
		  ('impl@2', 'com.example:implementation:1.0', 'implementation.jar'),
		  ('marked@2', NULL, 'marked.jar')`,
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
}
