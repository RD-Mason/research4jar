// Package session builds the merged session database from shards in pure Go,
// porting the Kotlin SessionBuilder so a registry-covered classpath needs no
// JVM at all. The output must satisfy the same contract the Go querier reads;
// the layout version stamp keeps the two writers honest.
package session

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"dev.research4jar/querier/internal/versions"
	_ "modernc.org/sqlite"
)

// Shard is one input to a session build.
type Shard struct {
	ShardID string
	Path    string
}

// Fingerprint derives the classpath fingerprint exactly like the Kotlin
// indexer: sha256 over the sorted shard ids joined by newlines, first 16 hex
// characters.
func Fingerprint(shardIDs []string) string {
	sorted := append([]string(nil), shardIDs...)
	sort.Strings(sorted)
	return sha256Hex([]byte(strings.Join(sorted, "\n")))[:16]
}

// BuildIfAbsent reuses an existing structurally-sound session of the current
// layout version (sessions are content-addressed by fingerprint, so it is
// equivalent to a rebuild) and builds otherwise.
func BuildIfAbsent(target string, shards []Shard) error {
	if isReusable(target) {
		return nil
	}
	return Build(target, shards)
}

// Build writes the session database atomically: temp file in the target
// directory, merge, fsync, rename.
func Build(target string, shards []Shard) error {
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(target), "."+filepath.Base(target)+".*.tmp")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	temp.Close()
	os.Remove(tempPath) // SQLite must create the file itself.
	defer os.Remove(tempPath)

	if err := buildInto(tempPath, shards); err != nil {
		return err
	}
	if err := syncFile(tempPath); err != nil {
		return err
	}
	if err := os.Rename(tempPath, target); err != nil {
		return err
	}
	syncDir(filepath.Dir(target))
	return nil
}

func buildInto(path string, shards []Shard) error {
	db, err := openReadWrite(path)
	if err != nil {
		return err
	}
	defer db.Close()
	// The temp file is invisible until the atomic rename, so on-disk
	// journaling and per-statement syncs buy nothing during the build.
	for _, pragma := range []string{
		"PRAGMA journal_mode=MEMORY",
		"PRAGMA synchronous=OFF",
	} {
		if _, err := db.Exec(pragma); err != nil {
			return err
		}
	}
	if err := createTables(db); err != nil {
		return err
	}
	sorted := append([]Shard(nil), shards...)
	sort.Slice(sorted, func(a, b int) bool { return sorted[a].ShardID < sorted[b].ShardID })
	for _, shard := range sorted {
		if err := merge(db, shard); err != nil {
			return fmt.Errorf("merge shard %s: %w", shard.ShardID, err)
		}
	}
	if err := populateDerivedColumns(db); err != nil {
		return err
	}
	if err := createIndexes(db); err != nil {
		return err
	}
	if _, err := db.Exec("ANALYZE"); err != nil {
		return err
	}
	return nil
}

func isReusable(target string) bool {
	info, err := os.Stat(target)
	if err != nil || !info.Mode().IsRegular() {
		return false
	}
	db, err := openReadWrite(target)
	if err != nil {
		return false
	}
	defer db.Close()
	var stamped int
	err = db.QueryRow("SELECT session_schema_version FROM session_meta").Scan(&stamped)
	return err == nil && stamped == versions.Session
}

var sessionTables = []string{
	`CREATE TABLE session_meta (
	  session_schema_version INTEGER NOT NULL
	)`,
	`CREATE TABLE config_properties (
	  id INTEGER PRIMARY KEY,
	  prefix TEXT,
	  name TEXT NOT NULL,
	  type_fqn TEXT,
	  default_val TEXT,
	  description TEXT,
	  source_fqn TEXT,
	  source_shard_id TEXT NOT NULL
	)`,
	`CREATE TABLE spi_registrations (
	  id INTEGER PRIMARY KEY,
	  mechanism TEXT NOT NULL,
	  key TEXT,
	  impl_fqn TEXT NOT NULL,
	  source_shard_id TEXT NOT NULL
	)`,
	`CREATE TABLE classes (
	  id INTEGER PRIMARY KEY,
	  fqn TEXT NOT NULL,
	  kind TEXT,
	  super_fqn TEXT,
	  modifiers INTEGER,
	  is_abstract INTEGER,
	  source_file TEXT,
	  simple_name TEXT,
	  package_name TEXT,
	  source_shard_id TEXT NOT NULL
	)`,
	`CREATE TABLE class_interfaces (
	  class_id INTEGER NOT NULL,
	  interface_fqn TEXT NOT NULL,
	  source_shard_id TEXT NOT NULL
	)`,
	`CREATE TABLE annotations (
	  id INTEGER PRIMARY KEY,
	  target_kind TEXT NOT NULL,
	  target_id INTEGER NOT NULL,
	  annotation_fqn TEXT NOT NULL,
	  attributes TEXT,
	  source_shard_id TEXT NOT NULL
	)`,
	`CREATE TABLE methods (
	  id INTEGER PRIMARY KEY,
	  class_id INTEGER NOT NULL,
	  name TEXT NOT NULL,
	  descriptor TEXT NOT NULL,
	  return_fqn TEXT,
	  modifiers INTEGER,
	  symbol TEXT,
	  source_shard_id TEXT NOT NULL
	)`,
	`CREATE TABLE bean_definitions (
	  id INTEGER PRIMARY KEY,
	  config_fqn TEXT NOT NULL,
	  method_id INTEGER,
	  bean_type_fqn TEXT,
	  bean_name TEXT,
	  source_shard_id TEXT NOT NULL
	)`,
	`CREATE TABLE conditions (
	  id INTEGER PRIMARY KEY,
	  target_kind TEXT NOT NULL,
	  target_id INTEGER NOT NULL,
	  type TEXT NOT NULL,
	  ref_value TEXT,
	  source_shard_id TEXT NOT NULL
	)`,
	`CREATE TABLE string_constants (
	  id INTEGER PRIMARY KEY,
	  class_id INTEGER NOT NULL,
	  method_id INTEGER,
	  value TEXT NOT NULL,
	  source_shard_id TEXT NOT NULL
	)`,
	// search_symbols is a view, not a copy: the materialized table plus its
	// four indexes were 65% of the session bytes while the broad-search query
	// could not use those indexes anyway (leading-wildcard LIKE). Search fast
	// paths probe the base tables directly; only the contains-scan fallback
	// reads this view.
	`CREATE VIEW search_symbols AS
	SELECT 'class' AS kind, fqn AS name, NULL AS owner, kind AS detail,
	       source_shard_id, simple_name, package_name, 55 AS score_hint
	FROM classes
	UNION ALL
	SELECT 'method', m.symbol, c.fqn, m.descriptor, m.source_shard_id,
	       m.name, c.package_name, 50
	FROM methods m JOIN classes c ON c.id = m.class_id
	UNION ALL
	SELECT 'annotation', a.annotation_fqn,
	       CASE WHEN a.target_kind = 'class' THEN c.fqn
	            WHEN a.target_kind = 'method' THEN m.symbol
	            ELSE NULL END,
	       a.attributes, a.source_shard_id, NULL,
	       COALESCE(c.package_name, mc.package_name), 45
	FROM annotations a
	LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
	LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
	LEFT JOIN classes mc ON mc.id = m.class_id
	UNION ALL
	SELECT 'spi', impl_fqn, key, mechanism, source_shard_id, NULL, NULL, 44
	FROM spi_registrations
	UNION ALL
	SELECT 'config-property', name, source_fqn, type_fqn, source_shard_id, NULL, NULL, 43
	FROM config_properties
	UNION ALL
	SELECT 'string', sc.value, c.fqn,
	       CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END,
	       sc.source_shard_id, NULL, c.package_name, 30
	FROM string_constants sc
	JOIN classes c ON c.id = sc.class_id
	LEFT JOIN methods m ON m.id = sc.method_id`,
}

func createTables(db *sql.DB) error {
	for _, ddl := range sessionTables {
		if _, err := db.Exec(ddl); err != nil {
			return err
		}
	}
	_, err := db.Exec(
		"INSERT INTO session_meta(session_schema_version) VALUES (?)", versions.Session,
	)
	return err
}

// mergeStatements mirrors the Kotlin SessionBuilder merge SQL verbatim.
// Placeholders: ? marks the source shard id; classOffset/methodOffset mark
// the id-rebasing offsets substituted per shard.
var mergeStatements = []struct {
	sql  string
	args func(shardID string, classOffset, methodOffset int) []any
}{
	{
		sql: `INSERT INTO config_properties(
		        prefix, name, type_fqn, default_val, description, source_fqn, source_shard_id
		      )
		      SELECT prefix, name, type_fqn, default_val, description, source_fqn, ?
		      FROM shard.config_properties
		      ORDER BY id`,
		args: func(shardID string, _, _ int) []any { return []any{shardID} },
	},
	{
		sql: `INSERT INTO spi_registrations(mechanism, key, impl_fqn, source_shard_id)
		      SELECT mechanism, key, impl_fqn, ?
		      FROM shard.spi_registrations
		      ORDER BY id`,
		args: func(shardID string, _, _ int) []any { return []any{shardID} },
	},
	{
		sql: `INSERT INTO classes(
		        fqn, kind, super_fqn, modifiers, is_abstract, source_file, source_shard_id
		      )
		      SELECT fqn, kind, super_fqn, modifiers, is_abstract, source_file, ?
		      FROM shard.classes
		      ORDER BY id`,
		args: func(shardID string, _, _ int) []any { return []any{shardID} },
	},
	{
		sql: `INSERT INTO class_interfaces(class_id, interface_fqn, source_shard_id)
		      SELECT class_id + ?, interface_fqn, ?
		      FROM shard.class_interfaces
		      ORDER BY class_id, interface_fqn`,
		args: func(shardID string, classOffset, _ int) []any {
			return []any{classOffset, shardID}
		},
	},
	{
		sql: `INSERT INTO methods(class_id, name, descriptor, return_fqn, modifiers, source_shard_id)
		      SELECT class_id + ?, name, descriptor, return_fqn, modifiers, ?
		      FROM shard.methods
		      ORDER BY id`,
		args: func(shardID string, classOffset, _ int) []any {
			return []any{classOffset, shardID}
		},
	},
	{
		sql: `INSERT INTO annotations(
		        target_kind, target_id, annotation_fqn, attributes, source_shard_id
		      )
		      SELECT
		        target_kind,
		        target_id + CASE target_kind WHEN 'class' THEN ? ELSE ? END,
		        annotation_fqn, attributes, ?
		      FROM shard.annotations
		      ORDER BY id`,
		args: func(shardID string, classOffset, methodOffset int) []any {
			return []any{classOffset, methodOffset, shardID}
		},
	},
	{
		sql: `INSERT INTO bean_definitions(
		        config_fqn, method_id, bean_type_fqn, bean_name, source_shard_id
		      )
		      SELECT config_fqn, method_id + ?, bean_type_fqn, bean_name, ?
		      FROM shard.bean_definitions
		      ORDER BY id`,
		args: func(shardID string, _, methodOffset int) []any {
			return []any{methodOffset, shardID}
		},
	},
	{
		sql: `INSERT INTO conditions(target_kind, target_id, type, ref_value, source_shard_id)
		      SELECT
		        target_kind,
		        target_id + CASE target_kind WHEN 'class' THEN ? ELSE ? END,
		        type, ref_value, ?
		      FROM shard.conditions
		      ORDER BY id`,
		args: func(shardID string, classOffset, methodOffset int) []any {
			return []any{classOffset, methodOffset, shardID}
		},
	},
	{
		sql: `INSERT INTO string_constants(class_id, method_id, value, source_shard_id)
		      SELECT class_id + ?, method_id + ?, value, ?
		      FROM shard.string_constants
		      ORDER BY id`,
		args: func(shardID string, classOffset, methodOffset int) []any {
			return []any{classOffset, methodOffset, shardID}
		},
	},
}

// populateDerivedColumns fills classes.simple_name/package_name and
// methods.symbol with set-based SQL. The rtrim trick is exact lastIndexOf('.')
// semantics: rtrim's second argument is a character SET, so trimming every
// non-dot character strips exactly the rightmost run of non-dot characters,
// leaving the prefix up to and including the last dot.
func populateDerivedColumns(db *sql.DB) error {
	transaction, err := db.Begin()
	if err != nil {
		return err
	}
	statements := []string{
		`UPDATE classes SET
		  simple_name = CASE
		    WHEN instr(fqn, '.') = 0 THEN fqn
		    ELSE substr(fqn, length(rtrim(fqn, replace(fqn, '.', ''))) + 1)
		  END,
		  package_name = CASE
		    WHEN instr(fqn, '.') = 0 THEN ''
		    ELSE substr(fqn, 1, length(rtrim(fqn, replace(fqn, '.', ''))) - 1)
		  END`,
		`UPDATE methods SET symbol = (
		  SELECT c.fqn FROM classes c WHERE c.id = methods.class_id
		) || '#' || name`,
	}
	for _, statement := range statements {
		if _, err := transaction.Exec(statement); err != nil {
			transaction.Rollback()
			return err
		}
	}
	return transaction.Commit()
}

func merge(db *sql.DB, shard Shard) error {
	absolute, err := filepath.Abs(shard.Path)
	if err != nil {
		return err
	}
	if _, err := db.Exec("ATTACH DATABASE ? AS shard", absolute); err != nil {
		return err
	}
	defer db.Exec("DETACH DATABASE shard")

	transaction, err := db.Begin()
	if err != nil {
		return err
	}
	classOffset, err := maxID(transaction, "classes")
	if err != nil {
		transaction.Rollback()
		return err
	}
	methodOffset, err := maxID(transaction, "methods")
	if err != nil {
		transaction.Rollback()
		return err
	}
	for _, statement := range mergeStatements {
		_, err := transaction.Exec(
			statement.sql, statement.args(shard.ShardID, classOffset, methodOffset)...,
		)
		if err != nil {
			transaction.Rollback()
			return err
		}
	}
	return transaction.Commit()
}

func maxID(transaction *sql.Tx, table string) (int, error) {
	var max int
	err := transaction.QueryRow("SELECT COALESCE(MAX(id), 0) FROM " + table).Scan(&max)
	return max, err
}

var sessionIndexes = []string{
	"CREATE INDEX idx_s_cfg_prefix ON config_properties(prefix)",
	"CREATE INDEX idx_s_cfg_name ON config_properties(name)",
	"CREATE INDEX idx_s_spi_mech ON spi_registrations(mechanism)",
	"CREATE INDEX idx_s_spi_key ON spi_registrations(key)",
	"CREATE INDEX idx_s_classes_fqn ON classes(fqn)",
	"CREATE INDEX idx_s_classes_simple ON classes(simple_name)",
	"CREATE INDEX idx_s_classes_package ON classes(package_name)",
	"CREATE INDEX idx_s_classes_super ON classes(super_fqn)",
	"CREATE INDEX idx_s_ci_iface ON class_interfaces(interface_fqn)",
	"CREATE INDEX idx_s_ci_class ON class_interfaces(class_id)",
	"CREATE INDEX idx_s_ann_fqn ON annotations(annotation_fqn)",
	"CREATE INDEX idx_s_ann_target ON annotations(target_kind, target_id)",
	"CREATE INDEX idx_s_methods_class ON methods(class_id)",
	"CREATE INDEX idx_s_methods_name ON methods(name)",
	"CREATE INDEX idx_s_methods_symbol ON methods(symbol)",
	"CREATE INDEX idx_s_bean_type ON bean_definitions(bean_type_fqn)",
	"CREATE INDEX idx_s_bean_cfg ON bean_definitions(config_fqn)",
	"CREATE INDEX idx_s_cond_target ON conditions(target_kind, target_id)",
	"CREATE INDEX idx_s_strconst_value ON string_constants(value)",
	"CREATE INDEX idx_s_strconst_class ON string_constants(class_id)",
	"CREATE INDEX idx_s_spi_impl ON spi_registrations(impl_fqn)",
}

func createIndexes(db *sql.DB) error {
	for _, ddl := range sessionIndexes {
		if _, err := db.Exec(ddl); err != nil {
			return err
		}
	}
	return nil
}

func openReadWrite(path string) (*sql.DB, error) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	slashPath := filepath.ToSlash(absolute)
	if filepath.VolumeName(absolute) != "" && !strings.HasPrefix(slashPath, "/") {
		slashPath = "/" + slashPath
	}
	uri := &url.URL{Scheme: "file", Path: slashPath}
	db, err := sql.Open("sqlite", uri.String())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func sha256Hex(data []byte) string {
	digest := sha256.Sum256(data)
	return hex.EncodeToString(digest[:])
}

func syncFile(path string) error {
	file, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer file.Close()
	return file.Sync()
}

func syncDir(dir string) {
	directory, err := os.Open(dir)
	if err != nil {
		return
	}
	defer directory.Close()
	// Directory fsync is unavailable on some platforms; mirror the Kotlin
	// best-effort behavior.
	_ = directory.Sync()
}
