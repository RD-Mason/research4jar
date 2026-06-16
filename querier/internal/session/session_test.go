package session

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"dev.research4jar/querier/internal/versions"
	_ "modernc.org/sqlite"
)

// writeShard creates a shard with the tables the merge reads, one class with
// one method, one annotation on each, and one string constant, so id
// rebasing across shards is observable.
func writeShard(t *testing.T, path, fqn string) {
	t.Helper()
	db, err := sql.Open("sqlite", "file:"+filepath.ToSlash(path))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	statements := []string{
		`CREATE TABLE config_properties (
		   id INTEGER PRIMARY KEY, prefix TEXT, name TEXT NOT NULL, type_fqn TEXT,
		   default_val TEXT, description TEXT, source_fqn TEXT
		 )`,
		`CREATE TABLE spi_registrations (
		   id INTEGER PRIMARY KEY, mechanism TEXT NOT NULL, key TEXT, impl_fqn TEXT NOT NULL
		 )`,
		`CREATE TABLE classes (
		   id INTEGER PRIMARY KEY, fqn TEXT NOT NULL, kind TEXT, super_fqn TEXT,
		   modifiers INTEGER, is_abstract INTEGER, source_file TEXT
		 )`,
		`CREATE TABLE class_interfaces (class_id INTEGER NOT NULL, interface_fqn TEXT NOT NULL)`,
		`CREATE TABLE methods (
		   id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, name TEXT NOT NULL,
		   descriptor TEXT NOT NULL, return_fqn TEXT, modifiers INTEGER
		 )`,
		`CREATE TABLE annotations (
		   id INTEGER PRIMARY KEY, target_kind TEXT NOT NULL, target_id INTEGER NOT NULL,
		   annotation_fqn TEXT NOT NULL, attributes TEXT
		 )`,
		`CREATE TABLE bean_definitions (
		   id INTEGER PRIMARY KEY, config_fqn TEXT NOT NULL, method_id INTEGER,
		   bean_type_fqn TEXT, bean_name TEXT
		 )`,
		`CREATE TABLE conditions (
		   id INTEGER PRIMARY KEY, target_kind TEXT NOT NULL, target_id INTEGER NOT NULL,
		   type TEXT NOT NULL, ref_value TEXT
		 )`,
		`CREATE TABLE string_constants (
		   id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, method_id INTEGER, value TEXT NOT NULL
		 )`,
		fmt.Sprintf(
			`INSERT INTO classes(id, fqn, kind, super_fqn, modifiers, is_abstract, source_file)
			 VALUES (1, '%s', 'class', 'java.lang.Object', 1, 0, NULL)`, fqn,
		),
		`INSERT INTO class_interfaces(class_id, interface_fqn) VALUES (1, 'example.Contract')`,
		`INSERT INTO methods(id, class_id, name, descriptor, return_fqn, modifiers)
		 VALUES (1, 1, 'value', '()Ljava/lang/String;', 'java.lang.String', 1)`,
		fmt.Sprintf(
			`INSERT INTO annotations(target_kind, target_id, annotation_fqn, attributes)
			 VALUES ('class', 1, 'example.Marker', '{"value":"%s"}')`, fqn,
		),
		`INSERT INTO annotations(target_kind, target_id, annotation_fqn, attributes)
		 VALUES ('method', 1, 'example.MethodMarker', NULL)`,
		`INSERT INTO string_constants(class_id, method_id, value) VALUES (1, 1, 'constant')`,
		fmt.Sprintf(
			`INSERT INTO config_properties(prefix, name) VALUES ('app', 'app.%s.enabled')`, fqn,
		),
		`INSERT INTO spi_registrations(mechanism, key, impl_fqn)
		 VALUES ('java_services', 'example.Contract', 'impl')`,
		fmt.Sprintf(
			`INSERT INTO bean_definitions(config_fqn, method_id, bean_type_fqn, bean_name)
			 VALUES ('%s', 1, 'javax.sql.DataSource', 'dataSource')`, fqn,
		),
		`INSERT INTO conditions(target_kind, target_id, type, ref_value)
		 VALUES ('class', 1, 'ConditionalOnClass', 'javax.sql.DataSource')`,
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			t.Fatalf("%v\n%s", err, statement)
		}
	}
}

func openSession(t *testing.T, path string) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", "file:"+filepath.ToSlash(path)+"?mode=ro")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func count(t *testing.T, db *sql.DB, query string) int {
	t.Helper()
	var value int
	if err := db.QueryRow(query).Scan(&value); err != nil {
		t.Fatalf("%v: %s", err, query)
	}
	return value
}

func TestBuildMergesShardsWithIDRebasing(t *testing.T) {
	dir := t.TempDir()
	shardA := filepath.Join(dir, "aaaa@2.db")
	shardB := filepath.Join(dir, "bbbb@2.db")
	writeShard(t, shardA, "alpha.One")
	writeShard(t, shardB, "beta.Two")

	target := filepath.Join(dir, "sessions", "fp.db")
	err := Build(target, []Shard{
		{ShardID: "bbbb@2", Path: shardB},
		{ShardID: "aaaa@2", Path: shardA},
	})
	if err != nil {
		t.Fatal(err)
	}

	db := openSession(t, target)
	if got := count(t, db, "SELECT session_schema_version FROM session_meta"); got != versions.Session {
		t.Fatalf("session_schema_version = %d", got)
	}
	if got := count(t, db, "SELECT COUNT(*) FROM classes"); got != 2 {
		t.Fatalf("classes = %d", got)
	}
	if got := count(t, db, "SELECT COUNT(DISTINCT id) FROM classes"); got != 2 {
		t.Fatal("class ids must be rebased, not collide")
	}
	// Class-target annotations must point at the rebased class row.
	matched := count(t, db, `
		SELECT COUNT(*) FROM annotations a
		JOIN classes c ON c.id = a.target_id
		WHERE a.target_kind = 'class'
		  AND json_extract(a.attributes, '$.value') = c.fqn`)
	if matched != 2 {
		t.Fatalf("rebased class annotations = %d, want 2", matched)
	}
	// Method-target annotations must rebase by the method offset instead.
	if got := count(t, db, `
		SELECT COUNT(*) FROM annotations a
		JOIN methods m ON m.id = a.target_id
		WHERE a.target_kind = 'method'`); got != 2 {
		t.Fatalf("rebased method annotations = %d, want 2", got)
	}
	if got := count(t, db, `
		SELECT COUNT(*) FROM string_constants s
		JOIN classes c ON c.id = s.class_id
		JOIN methods m ON m.id = s.method_id`); got != 2 {
		t.Fatalf("rebased string constants = %d, want 2", got)
	}
	// Shards merge in shard-id order: alpha.One (aaaa) gets the lower ids.
	var firstFQN string
	if err := db.QueryRow("SELECT fqn FROM classes WHERE id = 1").Scan(&firstFQN); err != nil {
		t.Fatal(err)
	}
	if firstFQN != "alpha.One" {
		t.Fatalf("first class = %s, want alpha.One (sorted shard order)", firstFQN)
	}
	if got := count(t, db, "SELECT COUNT(*) FROM bean_definitions"); got != 2 {
		t.Fatalf("bean_definitions = %d", got)
	}
	if got := count(t, db, `
		SELECT COUNT(*) FROM classes
		WHERE (fqn = 'alpha.One' AND simple_name = 'One' AND package_name = 'alpha')
		   OR (fqn = 'beta.Two' AND simple_name = 'Two' AND package_name = 'beta')`); got != 2 {
		t.Fatalf("derived class names = %d, want 2", got)
	}
	if got := count(t, db, `
		SELECT COUNT(*) FROM methods
		WHERE symbol IN ('alpha.One#value', 'beta.Two#value')`); got != 2 {
		t.Fatalf("derived method symbols = %d, want 2", got)
	}
	if got := count(t, db, "SELECT COUNT(*) FROM search_symbols"); got != 14 {
		t.Fatalf("search_symbols = %d, want 14", got)
	}
	if got := count(t, db, `
		SELECT COUNT(*) FROM search_symbols
		WHERE kind = 'annotation' AND owner = 'beta.Two#value'`); got != 1 {
		t.Fatalf("method annotation search owner = %d, want 1", got)
	}
	if got := count(t, db, `
		SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name LIKE 'idx_s_%'`); got != 24 {
		t.Fatalf("indexes = %d, want 24", got)
	}
	// Provenance must carry the shard id for source-jar attribution.
	if got := count(t, db,
		"SELECT COUNT(*) FROM classes WHERE source_shard_id = 'aaaa@2'"); got != 1 {
		t.Fatalf("source_shard_id attribution = %d, want 1", got)
	}
}

func TestBuildIfAbsentReusesCurrentLayout(t *testing.T) {
	dir := t.TempDir()
	shard := filepath.Join(dir, "aaaa@2.db")
	writeShard(t, shard, "alpha.One")
	target := filepath.Join(dir, "fp.db")
	shards := []Shard{{ShardID: "aaaa@2", Path: shard}}

	if err := Build(target, shards); err != nil {
		t.Fatal(err)
	}
	before, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	if err := BuildIfAbsent(target, shards); err != nil {
		t.Fatal(err)
	}
	after, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	if !after.ModTime().Equal(before.ModTime()) || after.Size() != before.Size() {
		t.Fatal("current-layout session should be reused, not rebuilt")
	}

	// A stale layout stamp forces a rebuild.
	db, err := sql.Open("sqlite", "file:"+filepath.ToSlash(target))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec("UPDATE session_meta SET session_schema_version = 1"); err != nil {
		t.Fatal(err)
	}
	db.Close()
	if err := BuildIfAbsent(target, shards); err != nil {
		t.Fatal(err)
	}
	rebuilt := openSession(t, target)
	if got := count(t, rebuilt, "SELECT session_schema_version FROM session_meta"); got != versions.Session {
		t.Fatalf("session_schema_version after rebuild = %d", got)
	}
}

func TestFingerprintIsOrderInvariant(t *testing.T) {
	a := Fingerprint([]string{"b@2", "a@2"})
	b := Fingerprint([]string{"a@2", "b@2"})
	if a != b {
		t.Fatalf("fingerprint depends on order: %s vs %s", a, b)
	}
	if len(a) != 16 {
		t.Fatalf("fingerprint length = %d, want 16", len(a))
	}
	if a == Fingerprint([]string{"a@2"}) {
		t.Fatal("different shard sets must fingerprint differently")
	}
}
