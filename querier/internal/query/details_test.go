package query

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"
)

// detailSession models one configuration class with a conditional @Bean
// method, a string constant, and SPI registrations across two mechanisms.
func createDetailSession(t *testing.T, path string) {
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
		`CREATE TABLE methods (
		  id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, name TEXT NOT NULL,
		  descriptor TEXT NOT NULL, return_fqn TEXT, modifiers INTEGER,
		  symbol TEXT,
		  source_shard_id TEXT NOT NULL
		)`,
		`CREATE TABLE bean_definitions (
		  id INTEGER PRIMARY KEY, config_fqn TEXT NOT NULL, method_id INTEGER,
		  bean_type_fqn TEXT, bean_name TEXT, source_shard_id TEXT NOT NULL
		)`,
		`CREATE TABLE conditions (
		  id INTEGER PRIMARY KEY, target_kind TEXT NOT NULL, target_id INTEGER NOT NULL,
		  type TEXT NOT NULL, ref_value TEXT, source_shard_id TEXT NOT NULL
		)`,
		`CREATE TABLE string_constants (
		  id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, method_id INTEGER,
		  value TEXT NOT NULL, source_shard_id TEXT NOT NULL
		)`,
		`CREATE TABLE spi_registrations (
		  id INTEGER PRIMARY KEY, mechanism TEXT NOT NULL, key TEXT,
		  impl_fqn TEXT NOT NULL, source_shard_id TEXT NOT NULL
		)`,
		`CREATE TABLE search_symbols (
		  id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
		  owner TEXT, detail TEXT, source_shard_id TEXT NOT NULL,
		  simple_name TEXT, package_name TEXT, score_hint INTEGER NOT NULL
		)`,
		`INSERT INTO classes
		  (id, fqn, kind, super_fqn, modifiers, is_abstract, source_file,
		   simple_name, package_name, source_shard_id)
		  VALUES (1, 'example.AutoConfig', 'class', NULL, 1, 0, 'AutoConfig.java',
		          'AutoConfig', 'example', 'api@2')`,
		`INSERT INTO class_interfaces VALUES (1, 'example.Aware', 'api@2')`,
		`INSERT INTO annotations
		  (target_kind, target_id, annotation_fqn, attributes, source_shard_id) VALUES
		  ('class', 1, 'org.springframework.context.annotation.Configuration', '{}', 'api@2'),
		  ('method', 1, 'org.springframework.context.annotation.Bean', '{}', 'api@2')`,
		`INSERT INTO methods
		  (id, class_id, name, descriptor, return_fqn, modifiers, symbol, source_shard_id)
		  VALUES (1, 1, 'dataSource', '()Ljavax/sql/DataSource;', 'javax.sql.DataSource',
		          1, 'example.AutoConfig#dataSource', 'api@2')`,
		`INSERT INTO bean_definitions
		  (config_fqn, method_id, bean_type_fqn, bean_name, source_shard_id)
		  VALUES ('example.AutoConfig', 1, 'javax.sql.DataSource', 'dataSource', 'api@2')`,
		`INSERT INTO conditions
		  (target_kind, target_id, type, ref_value, source_shard_id) VALUES
		  ('class', 1, 'OnClass', '{"value":["javax.sql.DataSource"]}', 'api@2'),
		  ('bean_method', 1, 'OnMissingBean', '{}', 'api@2')`,
		`INSERT INTO string_constants (class_id, method_id, value, source_shard_id) VALUES
		  (1, 1, 'spring.datasource.url', 'api@2'),
		  (1, NULL, 'X-Trace-Id', 'api@2')`,
		`INSERT INTO spi_registrations (mechanism, key, impl_fqn, source_shard_id) VALUES
		  ('autoconfig.imports', NULL, 'example.AutoConfig', 'api@2'),
		  ('services', 'java.sql.Driver', 'example.FakeDriver', 'api@2'),
		  ('services', 'java.sql.Driver', 'example.OtherDriver', 'api@2')`,
		`INSERT INTO search_symbols
		  (kind, name, owner, detail, source_shard_id, simple_name, package_name, score_hint)
		  VALUES
		  ('class', 'example.AutoConfig', NULL, 'class', 'api@2', 'AutoConfig', 'example', 55),
		  ('method', 'example.AutoConfig#dataSource', 'example.AutoConfig',
		   '()Ljavax/sql/DataSource;', 'api@2', 'dataSource', 'example', 50),
		  ('annotation', 'org.springframework.context.annotation.Configuration',
		   'example.AutoConfig', '{}', 'api@2', NULL, 'example', 45),
		  ('annotation', 'org.springframework.context.annotation.Bean',
		   'example.AutoConfig#dataSource', '{}', 'api@2', NULL, 'example', 45),
		  ('spi', 'example.AutoConfig', NULL, 'autoconfig.imports', 'api@2', NULL, NULL, 44),
		  ('spi', 'example.FakeDriver', 'java.sql.Driver', 'services', 'api@2', NULL, NULL, 44),
		  ('spi', 'example.OtherDriver', 'java.sql.Driver', 'services', 'api@2', NULL, NULL, 44),
		  ('string', 'spring.datasource.url', 'example.AutoConfig',
		   'dataSource()Ljavax/sql/DataSource;', 'api@2', NULL, 'example', 30),
		  ('string', 'X-Trace-Id', 'example.AutoConfig', NULL, 'api@2', NULL, 'example', 30)`,
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
}

func detailFixture(t *testing.T) (string, string) {
	root := t.TempDir()
	sessionPath := filepath.Join(root, "sessions", "fingerprint.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createDetailSession(t, sessionPath)
	createSymbolManifest(t, manifestPath)
	return sessionPath, manifestPath
}

func TestGetClassReturnsFullDetail(t *testing.T) {
	sessionPath, manifestPath := detailFixture(t)
	response, err := GetClass(
		context.Background(), symbolPointer(sessionPath), manifestPath, "example.AutoConfig",
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 1 {
		t.Fatalf("unexpected response: %#v", response)
	}
	detail := response.Results[0]
	if detail.SourceJar != "com.example:api:1.0" ||
		len(detail.Interfaces) != 1 || detail.Interfaces[0] != "example.Aware" {
		t.Fatalf("unexpected detail: %#v", detail)
	}
	if len(detail.Annotations) != 1 ||
		detail.Annotations[0].FQN != "org.springframework.context.annotation.Configuration" {
		t.Fatalf("unexpected annotations: %#v", detail.Annotations)
	}
	if len(detail.Methods) != 1 || detail.Methods[0].Name != "dataSource" ||
		len(detail.Methods[0].Annotations) != 1 {
		t.Fatalf("unexpected methods: %#v", detail.Methods)
	}
	if len(detail.Conditions) != 1 || detail.Conditions[0].Type != "OnClass" {
		t.Fatalf("unexpected conditions: %#v", detail.Conditions)
	}
	if len(detail.Beans) != 1 || detail.Beans[0].BeanName != "dataSource" ||
		len(detail.Beans[0].Conditions) != 1 ||
		detail.Beans[0].Conditions[0].Type != "OnMissingBean" {
		t.Fatalf("unexpected beans: %#v", detail.Beans)
	}
}

func TestGetBeanDefinitionsByTypeAndConfig(t *testing.T) {
	sessionPath, manifestPath := detailFixture(t)
	byType, err := GetBeanDefinitions(
		context.Background(), symbolPointer(sessionPath), manifestPath,
		"javax.sql.DataSource", 1, 20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if byType.Total != 1 || byType.Results[0].BeanName != "dataSource" ||
		byType.Results[0].SourceJar != "com.example:api:1.0" {
		t.Fatalf("unexpected by-type response: %#v", byType)
	}
	byConfig, err := GetBeanDefinitions(
		context.Background(), symbolPointer(sessionPath), manifestPath,
		"example.AutoConfig", 1, 20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if byConfig.Total != 1 {
		t.Fatalf("unexpected by-config response: %#v", byConfig)
	}
}

func TestExplainConditionalCoversClassAndBeanMethods(t *testing.T) {
	sessionPath, manifestPath := detailFixture(t)
	response, err := ExplainConditional(
		context.Background(), symbolPointer(sessionPath), manifestPath, "example.AutoConfig",
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 1 {
		t.Fatalf("unexpected response: %#v", response)
	}
	target := response.Results[0]
	if len(target.ClassConditions) != 1 || target.ClassConditions[0].Type != "OnClass" {
		t.Fatalf("unexpected class conditions: %#v", target.ClassConditions)
	}
	if len(target.BeanMethods) != 1 ||
		len(target.BeanMethods[0].Conditions) != 1 ||
		target.BeanMethods[0].Conditions[0].Type != "OnMissingBean" {
		t.Fatalf("unexpected bean methods: %#v", target.BeanMethods)
	}
}

func TestFindStringMatchesSubstringWithEscapes(t *testing.T) {
	sessionPath, manifestPath := detailFixture(t)
	response, err := FindString(
		context.Background(), symbolPointer(sessionPath), manifestPath,
		"datasource.url", 1, 20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 1 || response.Results[0].Value != "spring.datasource.url" ||
		response.Results[0].Method == nil {
		t.Fatalf("unexpected response: %#v", response)
	}
	// `_` must be treated literally, not as a LIKE wildcard.
	noMatch, err := FindString(
		context.Background(), symbolPointer(sessionPath), manifestPath, "X_Trace", 1, 20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if noMatch.Total != 0 {
		t.Fatalf("LIKE wildcard leaked: %#v", noMatch)
	}
}

func TestListExtensionPointsSummaryAndDetail(t *testing.T) {
	sessionPath, manifestPath := detailFixture(t)
	summary, err := ListExtensionPoints(
		context.Background(), symbolPointer(sessionPath), manifestPath, "", 1, 20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if summary.Total != 2 || len(summary.Points) != 2 {
		t.Fatalf("unexpected summary: %#v", summary)
	}
	if summary.Points[1].Implementations != 2 || *summary.Points[1].Key != "java.sql.Driver" {
		t.Fatalf("unexpected point: %#v", summary.Points[1])
	}
	detail, err := ListExtensionPoints(
		context.Background(), symbolPointer(sessionPath), manifestPath,
		"java.sql.Driver", 1, 20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Total != 2 || len(detail.Results) != 2 ||
		detail.Results[0].ImplFQN != "example.FakeDriver" {
		t.Fatalf("unexpected detail: %#v", detail)
	}
}
