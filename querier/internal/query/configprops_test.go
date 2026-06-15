package query

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"

	"dev.research4jar/querier/internal/project"
)

func TestFindConfigProperties(t *testing.T) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "sessions", "fingerprint.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSession(t, sessionPath)
	createManifest(t, manifestPath)

	pointer := project.Pointer{
		SchemaVersion:    1,
		ExtractorVersion: 1,
		SessionDBPath:    sessionPath,
		Coverage: project.Coverage{
			JarsTotal:   3,
			JarsIndexed: 2,
			JarsMissing: []string{"broken.jar"},
		},
	}
	response, err := FindConfigProperties(
		context.Background(),
		pointer,
		manifestPath,
		"spring.datasource",
		1,
		1,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 2 || len(response.Results) != 1 {
		t.Fatalf("unexpected pagination: total=%d results=%d", response.Total, len(response.Results))
	}
	if response.Results[0].Name != "spring.datasource.hikari.maximum-pool-size" {
		t.Fatalf("unexpected first result: %#v", response.Results[0])
	}
	if response.Results[0].SourceJar != "com.example:config:1.0" {
		t.Fatalf("source jar = %q", response.Results[0].SourceJar)
	}
	if response.Coverage.ExtractorVersion != 1 || len(response.Coverage.JarsMissing) != 1 {
		t.Fatalf("unexpected coverage: %#v", response.Coverage)
	}

	secondPage, err := FindConfigProperties(
		context.Background(),
		pointer,
		manifestPath,
		"spring.datasource",
		2,
		1,
	)
	if err != nil {
		t.Fatal(err)
	}
	if secondPage.Results[0].SourceJar != "private-config.jar" {
		t.Fatalf("filename fallback = %q", secondPage.Results[0].SourceJar)
	}
}

func TestFindConfigPropertiesTreatsWildcardCharactersLiterally(t *testing.T) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "session.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSession(t, sessionPath)
	createManifest(t, manifestPath)

	pointer := project.Pointer{
		SchemaVersion:    1,
		ExtractorVersion: 1,
		SessionDBPath:    sessionPath,
	}
	response, err := FindConfigProperties(
		context.Background(),
		pointer,
		manifestPath,
		"app_foo",
		1,
		20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 1 || response.Results[0].Name != "app_foo.value" {
		t.Fatalf("wildcard was not treated literally: %#v", response.Results)
	}
}

func TestFindConfigPropertiesIncludesExactName(t *testing.T) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "session.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSession(t, sessionPath)
	createManifest(t, manifestPath)

	pointer := project.Pointer{
		SchemaVersion:    1,
		ExtractorVersion: 1,
		SessionDBPath:    sessionPath,
	}
	response, err := FindConfigProperties(
		context.Background(),
		pointer,
		manifestPath,
		"standalone",
		1,
		20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 1 || response.Results[0].Name != "standalone" {
		t.Fatalf("exact property was not returned: %#v", response.Results)
	}
}

func createSession(t *testing.T, path string) {
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
		`CREATE TABLE config_properties (
		  id INTEGER PRIMARY KEY, prefix TEXT, name TEXT NOT NULL, type_fqn TEXT,
		  default_val TEXT, description TEXT, source_fqn TEXT, source_shard_id TEXT NOT NULL
		)`,
		`INSERT INTO config_properties
		  (name, type_fqn, default_val, description, source_fqn, source_shard_id)
		  VALUES
		  ('spring.datasource.url', 'java.lang.String', NULL, 'JDBC URL', 'example.Props', 'b@1'),
		  ('spring.datasource.hikari.maximum-pool-size', 'java.lang.Integer', '10', NULL, NULL, 'a@1'),
		  ('app_foo.value', NULL, NULL, NULL, NULL, 'a@1'),
		  ('appXfoo.value', NULL, NULL, NULL, NULL, 'a@1'),
		  ('standalone', NULL, NULL, NULL, NULL, 'a@1')`,
		`CREATE INDEX idx_s_cfg_name ON config_properties(name)`,
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
}

func createManifest(t *testing.T, path string) {
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
		  ('a@1', 'com.example:config:1.0', 'config.jar'),
		  ('b@1', NULL, 'private-config.jar')`,
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
}
