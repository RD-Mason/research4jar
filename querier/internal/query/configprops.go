package query

import (
	"context"
	"database/sql"
	"fmt"
	"net/url"
	"path/filepath"
	"strings"

	"dev.springdep/querier/internal/project"
	_ "modernc.org/sqlite"
)

type ConfigProperty struct {
	Name        string  `json:"name"`
	Type        *string `json:"type"`
	Default     *string `json:"default"`
	Description *string `json:"description"`
	Source      *string `json:"source"`
	SourceJar   string  `json:"source_jar"`
}

type Request struct {
	Command string `json:"command"`
	Prefix  string `json:"prefix"`
}

type Coverage struct {
	JarsTotal        int      `json:"jars_total"`
	JarsIndexed      int      `json:"jars_indexed"`
	JarsMissing      []string `json:"jars_missing"`
	ExtractorVersion int      `json:"extractor_version"`
}

type ConfigPropertiesResponse struct {
	Query    Request          `json:"query"`
	Results  []ConfigProperty `json:"results"`
	Total    int              `json:"total"`
	Page     int              `json:"page"`
	PageSize int              `json:"page_size"`
	Coverage Coverage         `json:"coverage"`
}

func FindConfigProperties(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	prefix string,
	page int,
	pageSize int,
) (ConfigPropertiesResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return ConfigPropertiesResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	childLowerBound := prefix + "."
	childUpperBound := prefix + "/"
	var total int
	if err := session.QueryRowContext(
		ctx,
		`SELECT COUNT(*) FROM config_properties
		 WHERE name = ? OR (name >= ? AND name < ?)`,
		prefix,
		childLowerBound,
		childUpperBound,
	).Scan(&total); err != nil {
		return ConfigPropertiesResponse{}, fmt.Errorf("count config properties: %w", err)
	}

	rows, err := session.QueryContext(
		ctx,
		`SELECT name, type_fqn, default_val, description, source_fqn, source_shard_id
		 FROM config_properties
		 WHERE name = ? OR (name >= ? AND name < ?)
		 ORDER BY name ASC, source_shard_id ASC
		 LIMIT ? OFFSET ?`,
		prefix,
		childLowerBound,
		childUpperBound,
		pageSize,
		(page-1)*pageSize,
	)
	if err != nil {
		return ConfigPropertiesResponse{}, fmt.Errorf("query config properties: %w", err)
	}
	defer rows.Close()

	type pendingProperty struct {
		property ConfigProperty
		shardID  string
	}
	pending := make([]pendingProperty, 0)
	for rows.Next() {
		var property ConfigProperty
		var propertyType, defaultValue, description, source sql.NullString
		var shardID string
		if err := rows.Scan(
			&property.Name,
			&propertyType,
			&defaultValue,
			&description,
			&source,
			&shardID,
		); err != nil {
			return ConfigPropertiesResponse{}, fmt.Errorf("scan config property: %w", err)
		}
		property.Type = nullableString(propertyType)
		property.Default = nullableString(defaultValue)
		property.Description = nullableString(description)
		property.Source = nullableString(source)
		pending = append(pending, pendingProperty{property: property, shardID: shardID})
	}
	if err := rows.Err(); err != nil {
		return ConfigPropertiesResponse{}, fmt.Errorf("iterate config properties: %w", err)
	}

	var sources map[string]string
	if len(pending) > 0 {
		sources, err = loadSourceJars(ctx, manifestPath)
		if err != nil {
			return ConfigPropertiesResponse{}, err
		}
	}
	results := make([]ConfigProperty, 0, len(pending))
	for _, item := range pending {
		item.property.SourceJar = sources[item.shardID]
		if item.property.SourceJar == "" {
			item.property.SourceJar = item.shardID
		}
		results = append(results, item.property)
	}

	return ConfigPropertiesResponse{
		Query: Request{
			Command: "find-config-properties",
			Prefix:  prefix,
		},
		Results:  results,
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}, nil
}

func InferManifestPath(sessionDBPath string) string {
	sessionsDir := filepath.Dir(sessionDBPath)
	if filepath.Base(sessionsDir) == "sessions" {
		return filepath.Join(filepath.Dir(sessionsDir), "manifest.db")
	}
	return ""
}

func loadSourceJars(ctx context.Context, manifestPath string) (map[string]string, error) {
	if manifestPath == "" {
		return map[string]string{}, nil
	}
	manifest, err := openReadOnly(manifestPath, false)
	if err != nil {
		return nil, fmt.Errorf("open manifest database: %w", err)
	}
	defer manifest.Close()

	rows, err := manifest.QueryContext(
		ctx,
		`SELECT shard_id, COALESCE(NULLIF(jar_coordinate, ''), jar_filename) FROM shards`,
	)
	if err != nil {
		return nil, fmt.Errorf("query manifest sources: %w", err)
	}
	defer rows.Close()

	sources := make(map[string]string)
	for rows.Next() {
		var shardID, source string
		if err := rows.Scan(&shardID, &source); err != nil {
			return nil, fmt.Errorf("scan manifest source: %w", err)
		}
		sources[shardID] = source
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate manifest sources: %w", err)
	}
	return sources, nil
}

func openReadOnly(path string, immutable bool) (*sql.DB, error) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	slashPath := filepath.ToSlash(absolute)
	if filepath.VolumeName(absolute) != "" && !strings.HasPrefix(slashPath, "/") {
		slashPath = "/" + slashPath
	}
	uri := &url.URL{Scheme: "file", Path: slashPath}
	values := uri.Query()
	values.Set("mode", "ro")
	if immutable {
		values.Set("immutable", "1")
	} else {
		// Mutable databases (the manifest) may be written by a concurrent
		// indexer run; wait out short write locks instead of failing.
		values.Add("_pragma", "busy_timeout(5000)")
	}
	uri.RawQuery = values.Encode()

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

func nullableString(value sql.NullString) *string {
	if !value.Valid {
		return nil
	}
	return &value.String
}

func nonNil(values []string) []string {
	if values == nil {
		return []string{}
	}
	return values
}
