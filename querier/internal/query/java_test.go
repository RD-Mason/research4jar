package query

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"
)

func javaFixture(t *testing.T) (string, string) {
	t.Helper()
	sessionPath, manifestPath := detailFixture(t)
	db, err := sql.Open("sqlite", sessionPath)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	_, err = db.Exec(`CREATE TABLE config_properties (
	  id INTEGER PRIMARY KEY, prefix TEXT, name TEXT NOT NULL, type_fqn TEXT,
	  default_val TEXT, description TEXT, source_fqn TEXT, source_shard_id TEXT NOT NULL
	)`)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO config_properties
	  (name, type_fqn, source_fqn, source_shard_id)
	  VALUES ('spring.datasource.url', 'java.lang.String', 'example.AutoConfig', 'api@2')`)
	if err != nil {
		t.Fatal(err)
	}
	return sessionPath, manifestPath
}

func TestFindClassFindMethodAndPackages(t *testing.T) {
	sessionPath, manifestPath := javaFixture(t)
	pointer := symbolPointer(sessionPath)

	classes, err := FindClass(context.Background(), pointer, manifestPath, "AutoConfig", 1, 20)
	if err != nil {
		t.Fatal(err)
	}
	if classes.Total != 1 || classes.Results[0].FQN != "example.AutoConfig" ||
		classes.Results[0].MatchReason != "simple_name" {
		t.Fatalf("unexpected class results: %#v", classes)
	}

	methods, err := FindMethod(context.Background(), pointer, manifestPath, "dataSource", 1, 20)
	if err != nil {
		t.Fatal(err)
	}
	if methods.Total != 1 || methods.Results[0].ClassFQN != "example.AutoConfig" ||
		methods.Results[0].SourceJar != "com.example:api:1.0" {
		t.Fatalf("unexpected method results: %#v", methods)
	}

	packages, err := ListPackages(context.Background(), pointer, manifestPath, "example", 1, 20)
	if err != nil {
		t.Fatal(err)
	}
	if packages.Total != 1 || packages.Results[0].Package != "example" ||
		packages.Results[0].Classes != 1 {
		t.Fatalf("unexpected package results: %#v", packages)
	}
}

func TestSearchSymbolAndOpenSymbol(t *testing.T) {
	sessionPath, manifestPath := javaFixture(t)
	pointer := symbolPointer(sessionPath)

	search, err := SearchSymbol(context.Background(), pointer, manifestPath, "spring.datasource", 1, 20)
	if err != nil {
		t.Fatal(err)
	}
	kinds := map[string]bool{}
	for _, result := range search.Results {
		kinds[result.Kind] = true
	}
	if !kinds["config-property"] || !kinds["string"] {
		t.Fatalf("expected config-property and string matches: %#v", search.Results)
	}

	opened, err := OpenSymbol(context.Background(), pointer, manifestPath, "example.AutoConfig#dataSource")
	if err != nil {
		t.Fatal(err)
	}
	if opened.Total != 1 || opened.Results[0].Method == nil ||
		opened.Results[0].Method.Name != "dataSource" {
		t.Fatalf("unexpected open symbol response: %#v", opened)
	}
}

func TestFindClassPaginationPastEnd(t *testing.T) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "sessions", "fingerprint.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSymbolSession(t, sessionPath)
	createSymbolManifest(t, manifestPath)
	response, err := FindClass(
		context.Background(), symbolPointer(sessionPath), manifestPath, "example", 99, 20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(response.Results) != 0 {
		t.Fatalf("expected empty out-of-range page: %#v", response)
	}
}
