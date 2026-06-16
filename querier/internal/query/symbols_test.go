package query

import (
	"context"
	"database/sql"
	"encoding/json"
	"path/filepath"
	"testing"

	"dev.research4jar/querier/internal/project"
)

func TestFindImplementationsTransitiveClosure(t *testing.T) {
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
		false,
		1,
		20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 4 || len(response.Results) != 4 {
		t.Fatalf("unexpected response: %#v", response)
	}
	expected := []string{
		"example.SubContract", // subinterface joins the closure
		"other.DirectImplementation",
		"other.GrandChild",   // extends DirectImplementation
		"other.IndirectImpl", // implements the subinterface
	}
	for index, fqn := range expected {
		if response.Results[index].FQN != fqn {
			t.Fatalf("result[%d] = %q, want %q", index, response.Results[index].FQN, fqn)
		}
	}
	if response.Results[1].SourceJar != "com.example:implementation:1.0" {
		t.Fatalf("source jar = %q", response.Results[1].SourceJar)
	}
}

func TestFindImplementationsDirectOnly(t *testing.T) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "sessions", "fingerprint.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSymbolSession(t, sessionPath)
	createSymbolManifest(t, manifestPath)

	response, err := FindImplementations(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		"example.Contract",
		true,
		1,
		20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 2 {
		t.Fatalf("unexpected response: %#v", response)
	}
	if response.Results[0].FQN != "example.SubContract" ||
		response.Results[1].FQN != "other.DirectImplementation" {
		t.Fatalf("unexpected direct results: %#v", response.Results)
	}
	if !response.Query.Direct {
		t.Fatalf("query echo should mark direct: %#v", response.Query)
	}
}

func TestFindImplementationsMatchesDirectSuperclass(t *testing.T) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "sessions", "fingerprint.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSymbolSession(t, sessionPath)
	createSymbolManifest(t, manifestPath)

	response, err := FindImplementations(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		"example.BaseGateway",
		true,
		1,
		20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 1 || len(response.Results) != 1 {
		t.Fatalf("unexpected response: %#v", response)
	}
	if response.Results[0].FQN != "other.DirectSubclass" {
		t.Fatalf("subclass = %q, want other.DirectSubclass", response.Results[0].FQN)
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
		false,
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
	if response.Results[0].MatchedAnnotation != "example.Marker" {
		t.Fatalf("matched annotation = %q", response.Results[0].MatchedAnnotation)
	}
	if response.Query.Command != "find-by-annotation" ||
		response.Coverage.ExtractorVersion != 2 {
		t.Fatalf("unexpected contract: %#v", response)
	}
}

func TestFindByAnnotationExpandsMetaAnnotations(t *testing.T) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "sessions", "fingerprint.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSymbolSession(t, sessionPath)
	createSymbolManifest(t, manifestPath)

	response, err := FindByAnnotation(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		"example.MetaMarker",
		false,
		1,
		20,
	)
	if err != nil {
		t.Fatal(err)
	}
	// example.Marker is annotated by @MetaMarker directly; other.Marked is
	// annotated by @Marker, which the meta-closure folds into the match set.
	if response.Total != 2 || len(response.Results) != 2 {
		t.Fatalf("unexpected response: %#v", response)
	}
	if response.Results[0].FQN != "example.Marker" ||
		response.Results[0].MatchedAnnotation != "example.MetaMarker" {
		t.Fatalf("unexpected first result: %#v", response.Results[0])
	}
	if response.Results[1].FQN != "other.Marked" ||
		response.Results[1].MatchedAnnotation != "example.Marker" {
		t.Fatalf("unexpected second result: %#v", response.Results[1])
	}

	direct, err := FindByAnnotation(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		"example.MetaMarker",
		true,
		1,
		20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if direct.Total != 1 || direct.Results[0].FQN != "example.Marker" {
		t.Fatalf("unexpected direct response: %#v", direct)
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
		  simple_name TEXT, package_name TEXT,
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
		`INSERT INTO classes (id, fqn, kind, simple_name, package_name, source_shard_id) VALUES
		  (1, 'example.Contract', 'interface', 'Contract', 'example', 'api@2'),
		  (2, 'other.DirectImplementation', 'class', 'DirectImplementation', 'other', 'impl@2'),
		  (3, 'other.Marked', 'class', 'Marked', 'other', 'marked@2'),
		  (6, 'example.SubContract', 'interface', 'SubContract', 'example', 'api@2'),
		  (7, 'other.IndirectImpl', 'class', 'IndirectImpl', 'other', 'impl@2'),
		  (9, 'example.Marker', 'annotation', 'Marker', 'example', 'api@2'),
		  (10, 'example.MetaMarker', 'annotation', 'MetaMarker', 'example', 'api@2')`,
		`INSERT INTO classes (id, fqn, kind, super_fqn, simple_name, package_name, source_shard_id) VALUES
		  (4, 'example.BaseGateway', 'class', NULL, 'BaseGateway', 'example', 'api@2'),
		  (5, 'other.DirectSubclass', 'class', 'example.BaseGateway', 'DirectSubclass', 'other', 'impl@2'),
		  (8, 'other.GrandChild', 'class', 'other.DirectImplementation', 'GrandChild', 'other', 'impl@2')`,
		`INSERT INTO class_interfaces VALUES
		  (2, 'example.Contract', 'impl@2'),
		  (6, 'example.Contract', 'api@2'),
		  (7, 'example.SubContract', 'impl@2')`,
		`INSERT INTO annotations
		  (target_kind, target_id, annotation_fqn, attributes, source_shard_id)
		  VALUES
		  ('class', 3, 'example.Marker', '{"value":"direct"}', 'marked@2'),
		  ('class', 9, 'example.MetaMarker', '{"value":"meta"}', 'api@2')`,
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
