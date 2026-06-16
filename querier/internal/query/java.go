package query

import (
	"context"
	"database/sql"
	"fmt"
	"strings"

	"dev.research4jar/querier/internal/project"
)

type ClassSearchResult struct {
	FQN         string  `json:"fqn"`
	SimpleName  string  `json:"simple_name"`
	Package     string  `json:"package"`
	Kind        *string `json:"kind"`
	SuperFQN    *string `json:"super"`
	SourceJar   string  `json:"source_jar"`
	Score       int     `json:"score"`
	MatchReason string  `json:"match_reason"`
}

type ClassSearchResponse struct {
	Query    SymbolRequest       `json:"query"`
	Results  []ClassSearchResult `json:"results"`
	Total    int                 `json:"total"`
	HasMore  bool                `json:"has_more"`
	Page     int                 `json:"page"`
	PageSize int                 `json:"page_size"`
	Coverage Coverage            `json:"coverage"`
}

type MethodSearchResult struct {
	ClassFQN    string  `json:"class_fqn"`
	Name        string  `json:"name"`
	Descriptor  string  `json:"descriptor"`
	ReturnFQN   *string `json:"return"`
	Modifiers   int     `json:"modifiers"`
	SourceJar   string  `json:"source_jar"`
	Score       int     `json:"score"`
	MatchReason string  `json:"match_reason"`
}

type MethodSearchResponse struct {
	Query    SymbolRequest        `json:"query"`
	Results  []MethodSearchResult `json:"results"`
	Total    int                  `json:"total"`
	HasMore  bool                 `json:"has_more"`
	Page     int                  `json:"page"`
	PageSize int                  `json:"page_size"`
	Coverage Coverage             `json:"coverage"`
}

type PackageSummary struct {
	Package   string `json:"package"`
	SourceJar string `json:"source_jar"`
	Classes   int    `json:"classes"`
}

type PackageListResponse struct {
	Query    SymbolRequest    `json:"query"`
	Results  []PackageSummary `json:"results"`
	Total    int              `json:"total"`
	HasMore  bool             `json:"has_more"`
	Page     int              `json:"page"`
	PageSize int              `json:"page_size"`
	Coverage Coverage         `json:"coverage"`
}

type SearchSymbolResult struct {
	Kind        string  `json:"kind"`
	Name        string  `json:"name"`
	Owner       *string `json:"owner,omitempty"`
	Detail      *string `json:"detail,omitempty"`
	SourceJar   string  `json:"source_jar"`
	Score       int     `json:"score"`
	MatchReason string  `json:"match_reason"`
}

type SearchSymbolResponse struct {
	Query    SymbolRequest        `json:"query"`
	Results  []SearchSymbolResult `json:"results"`
	Total    int                  `json:"total"`
	HasMore  bool                 `json:"has_more"`
	Page     int                  `json:"page"`
	PageSize int                  `json:"page_size"`
	Coverage Coverage             `json:"coverage"`
}

func FindClass(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	term string,
	page int,
	pageSize int,
) (ClassSearchResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return ClassSearchResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	args := matchArgs(term)
	rows, err := session.QueryContext(
		ctx,
		classSearchSQL,
		append(args, pageSize+1, (page-1)*pageSize)...,
	)
	if err != nil {
		return ClassSearchResponse{}, fmt.Errorf("query class results: %w", err)
	}
	defer rows.Close()

	type pendingClass struct {
		result  ClassSearchResult
		shardID string
	}
	pending := []pendingClass{}
	for rows.Next() {
		var item pendingClass
		var simpleName, packageName, kind, super sql.NullString
		if err := rows.Scan(
			&item.result.FQN, &simpleName, &packageName, &kind, &super, &item.shardID,
			&item.result.Score, &item.result.MatchReason,
		); err != nil {
			return ClassSearchResponse{}, fmt.Errorf("scan class result: %w", err)
		}
		item.result.Kind = nullableString(kind)
		item.result.SuperFQN = nullableString(super)
		item.result.Package, item.result.SimpleName = splitFQN(item.result.FQN)
		if packageName.Valid {
			item.result.Package = packageName.String
		}
		if simpleName.Valid {
			item.result.SimpleName = simpleName.String
		}
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		return ClassSearchResponse{}, fmt.Errorf("iterate class results: %w", err)
	}
	hasMore := len(pending) > pageSize
	if hasMore {
		pending = pending[:pageSize]
	}

	sources, err := loadSourcesIfNeeded(ctx, manifestPath, len(pending))
	if err != nil {
		return ClassSearchResponse{}, err
	}
	results := make([]ClassSearchResult, 0, len(pending))
	for _, item := range pending {
		item.result.SourceJar = sourceJarName(sources, item.shardID)
		results = append(results, item.result)
	}
	return ClassSearchResponse{
		Query:    SymbolRequest{Command: "find-class", Arg: term},
		Results:  results,
		Total:    len(results),
		HasMore:  hasMore,
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}, nil
}

func FindMethod(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	term string,
	page int,
	pageSize int,
) (MethodSearchResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return MethodSearchResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	args := matchArgs(term)
	rows, err := session.QueryContext(
		ctx,
		methodSearchSQL,
		append(args, pageSize+1, (page-1)*pageSize)...,
	)
	if err != nil {
		return MethodSearchResponse{}, fmt.Errorf("query method results: %w", err)
	}
	defer rows.Close()

	type pendingMethod struct {
		result  MethodSearchResult
		shardID string
	}
	pending := []pendingMethod{}
	for rows.Next() {
		var item pendingMethod
		var returnFQN sql.NullString
		if err := rows.Scan(
			&item.result.ClassFQN, &item.result.Name, &item.result.Descriptor,
			&returnFQN, &item.result.Modifiers, &item.shardID,
			&item.result.Score, &item.result.MatchReason,
		); err != nil {
			return MethodSearchResponse{}, fmt.Errorf("scan method result: %w", err)
		}
		item.result.ReturnFQN = nullableString(returnFQN)
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		return MethodSearchResponse{}, fmt.Errorf("iterate method results: %w", err)
	}
	hasMore := len(pending) > pageSize
	if hasMore {
		pending = pending[:pageSize]
	}

	sources, err := loadSourcesIfNeeded(ctx, manifestPath, len(pending))
	if err != nil {
		return MethodSearchResponse{}, err
	}
	results := make([]MethodSearchResult, 0, len(pending))
	for _, item := range pending {
		item.result.SourceJar = sourceJarName(sources, item.shardID)
		results = append(results, item.result)
	}
	return MethodSearchResponse{
		Query:    SymbolRequest{Command: "find-method", Arg: term},
		Results:  results,
		Total:    len(results),
		HasMore:  hasMore,
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}, nil
}

func ListPackages(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	prefix string,
	page int,
	pageSize int,
) (PackageListResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return PackageListResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	likePrefix := ""
	if prefix != "" {
		likePrefix = escapeLike(prefix) + ".%"
	}
	rows, err := session.QueryContext(
		ctx,
		`SELECT package_name, source_shard_id, COUNT(*) AS classes
		 FROM classes
		 WHERE ? = '' OR package_name = ? OR package_name LIKE ? ESCAPE '\'
		 GROUP BY package_name, source_shard_id
		 ORDER BY package_name, source_shard_id
		 LIMIT ? OFFSET ?`,
		prefix, prefix, likePrefix, pageSize+1, (page-1)*pageSize,
	)
	if err != nil {
		return PackageListResponse{}, fmt.Errorf("query packages: %w", err)
	}
	defer rows.Close()

	type pendingPackage struct {
		result  PackageSummary
		shardID string
	}
	pending := []pendingPackage{}
	for rows.Next() {
		var item pendingPackage
		if err := rows.Scan(&item.result.Package, &item.shardID, &item.result.Classes); err != nil {
			return PackageListResponse{}, fmt.Errorf("scan package row: %w", err)
		}
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		return PackageListResponse{}, fmt.Errorf("iterate packages: %w", err)
	}
	hasMore := len(pending) > pageSize
	if hasMore {
		pending = pending[:pageSize]
	}

	sources, err := loadSourcesIfNeeded(ctx, manifestPath, len(pending))
	if err != nil {
		return PackageListResponse{}, err
	}
	results := make([]PackageSummary, 0, len(pending))
	for _, item := range pending {
		item.result.SourceJar = sourceJarName(sources, item.shardID)
		results = append(results, item.result)
	}
	return PackageListResponse{
		Query:    SymbolRequest{Command: "list-packages", Arg: prefix},
		Results:  results,
		Total:    len(results),
		HasMore:  hasMore,
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}, nil
}

func SearchSymbol(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	term string,
	page int,
	pageSize int,
) (SearchSymbolResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return SearchSymbolResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	args := matchArgs(term)
	rows, err := session.QueryContext(
		ctx,
		searchSymbolSQL,
		append(args, pageSize+1, (page-1)*pageSize)...,
	)
	if err != nil {
		return SearchSymbolResponse{}, fmt.Errorf("query search-symbol results: %w", err)
	}
	defer rows.Close()

	type pendingSearch struct {
		result  SearchSymbolResult
		shardID string
	}
	pending := []pendingSearch{}
	for rows.Next() {
		var item pendingSearch
		var owner, detail sql.NullString
		if err := rows.Scan(
			&item.result.Kind, &item.result.Name, &owner, &detail,
			&item.shardID, &item.result.Score, &item.result.MatchReason,
		); err != nil {
			return SearchSymbolResponse{}, fmt.Errorf("scan search-symbol result: %w", err)
		}
		item.result.Owner = nullableString(owner)
		item.result.Detail = nullableString(detail)
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		return SearchSymbolResponse{}, fmt.Errorf("iterate search-symbol results: %w", err)
	}
	hasMore := len(pending) > pageSize
	if hasMore {
		pending = pending[:pageSize]
	}

	sources, err := loadSourcesIfNeeded(ctx, manifestPath, len(pending))
	if err != nil {
		return SearchSymbolResponse{}, err
	}
	results := make([]SearchSymbolResult, 0, len(pending))
	for _, item := range pending {
		item.result.SourceJar = sourceJarName(sources, item.shardID)
		results = append(results, item.result)
	}
	return SearchSymbolResponse{
		Query:    SymbolRequest{Command: "search-symbol", Arg: term},
		Results:  results,
		Total:    len(results),
		HasMore:  hasMore,
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}, nil
}

func matchArgs(term string) []any {
	escaped := escapeLike(term)
	return []any{term, term, escaped + "%", "%" + escaped + "%"}
}

func splitFQN(fqn string) (string, string) {
	index := strings.LastIndexByte(fqn, '.')
	if index < 0 {
		return "", fqn
	}
	return fqn[:index], fqn[index+1:]
}

func pageBounds(page, pageSize, total int) (int, int) {
	start := (page - 1) * pageSize
	if start > total {
		start = total
	}
	end := start + pageSize
	if end > total {
		end = total
	}
	return start, end
}

func loadSourcesIfNeeded(ctx context.Context, manifestPath string, count int) (map[string]string, error) {
	if count == 0 {
		return map[string]string{}, nil
	}
	return loadSourceJars(ctx, manifestPath)
}

const classSearchBaseSQL = `
WITH params(term, simple, prefix, contains) AS (VALUES (?, ?, ?, ?)),
matches AS (
  SELECT c.fqn, c.simple_name, c.package_name, c.kind, c.super_fqn, c.source_shard_id,
         CASE
           WHEN c.fqn = p.term THEN 100
           WHEN c.simple_name = p.simple THEN 90
           WHEN c.simple_name LIKE p.prefix ESCAPE '\' THEN 82
           WHEN c.fqn LIKE p.prefix ESCAPE '\' THEN 80
           ELSE 50
         END AS score,
         CASE
           WHEN c.fqn = p.term THEN 'exact_fqn'
           WHEN c.simple_name = p.simple THEN 'simple_name'
           WHEN c.simple_name LIKE p.prefix ESCAPE '\' THEN 'simple_prefix'
           WHEN c.fqn LIKE p.prefix ESCAPE '\' THEN 'prefix'
           ELSE 'contains'
         END AS match_reason
  FROM classes c, params p
  WHERE c.fqn = p.term
     OR c.simple_name = p.simple
     OR c.simple_name LIKE p.prefix ESCAPE '\'
     OR c.fqn LIKE p.prefix ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)`

const classSearchSQL = classSearchBaseSQL + `
SELECT fqn, simple_name, package_name, kind, super_fqn, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, fqn, source_shard_id
LIMIT ? OFFSET ?`

const methodSearchBaseSQL = `
WITH params(term, simple, prefix, contains) AS (VALUES (?, ?, ?, ?)),
matches AS (
  SELECT c.fqn AS class_fqn, m.name, m.descriptor, m.return_fqn, m.modifiers,
         m.source_shard_id,
         CASE
           WHEN m.symbol = p.term THEN 100
           WHEN c.fqn || '.' || m.name = p.term THEN 100
           WHEN m.name = p.term THEN 95
           WHEN m.name LIKE p.prefix ESCAPE '\' THEN 75
           ELSE 50
         END AS score,
         CASE
           WHEN m.symbol = p.term THEN 'exact_method'
           WHEN c.fqn || '.' || m.name = p.term THEN 'exact_method'
           WHEN m.name = p.term THEN 'method_name'
           WHEN m.name LIKE p.prefix ESCAPE '\' THEN 'method_prefix'
           ELSE 'contains'
         END AS match_reason
  FROM methods m
  JOIN classes c ON c.id = m.class_id
  , params p
  WHERE m.symbol = p.term
     OR c.fqn || '.' || m.name = p.term
     OR m.name = p.term
     OR m.name LIKE p.prefix ESCAPE '\'
     OR m.name LIKE p.contains ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)`

const methodSearchSQL = methodSearchBaseSQL + `
SELECT class_fqn, name, descriptor, return_fqn, modifiers, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, class_fqn, name, descriptor, source_shard_id
LIMIT ? OFFSET ?`

const searchSymbolBaseSQL = `
WITH params(term, simple, prefix, contains) AS (VALUES (?, ?, ?, ?)),
matches AS (
  SELECT s.kind, s.name, s.owner, s.detail, s.source_shard_id,
         CASE
           WHEN s.kind = 'class' AND s.name = p.term THEN 100
           WHEN s.kind = 'method' AND s.name = p.term THEN 98
           WHEN s.kind = 'method' AND s.simple_name = p.simple THEN 92
           WHEN s.kind = 'class' AND s.simple_name = p.simple THEN 90
           WHEN s.kind = 'annotation' AND s.name = p.term THEN 88
           WHEN s.kind = 'spi' AND (s.name = p.term OR s.owner = p.term) THEN 86
           WHEN s.kind = 'config-property' AND s.name = p.term THEN 84
           WHEN s.kind = 'method' AND s.simple_name LIKE p.prefix ESCAPE '\' THEN 75
           WHEN s.kind = 'config-property' AND s.name LIKE p.prefix ESCAPE '\' THEN 70
           WHEN s.kind = 'string' AND s.name = p.term THEN 65
           ELSE s.score_hint
         END AS score,
         CASE
           WHEN s.kind = 'class' AND s.name = p.term THEN 'exact_fqn'
           WHEN s.kind = 'method' AND s.name = p.term THEN 'exact_method'
           WHEN s.kind = 'method' AND s.simple_name = p.simple THEN 'method_name'
           WHEN s.kind = 'class' AND s.simple_name = p.simple THEN 'simple_name'
           WHEN s.kind = 'annotation' AND s.name = p.term THEN 'annotation_fqn'
           WHEN s.kind = 'spi' AND s.name = p.term THEN 'spi_impl'
           WHEN s.kind = 'spi' AND s.owner = p.term THEN 'spi_key'
           WHEN s.kind = 'config-property' AND s.name = p.term THEN 'config_property'
           WHEN s.kind = 'method' AND s.simple_name LIKE p.prefix ESCAPE '\' THEN 'method_prefix'
           WHEN s.kind = 'config-property' AND s.name LIKE p.prefix ESCAPE '\' THEN 'config_prefix'
           WHEN s.kind = 'string' AND s.name = p.term THEN 'string_constant'
           ELSE s.kind || '_contains'
         END AS match_reason
  FROM search_symbols s, params p
  WHERE s.name = p.term
     OR s.simple_name = p.simple
     OR s.owner = p.term
     OR s.detail = p.term
     OR s.name LIKE p.prefix ESCAPE '\'
     OR s.simple_name LIKE p.prefix ESCAPE '\'
     OR s.name LIKE p.contains ESCAPE '\'
     OR s.owner LIKE p.contains ESCAPE '\'
     OR s.detail LIKE p.contains ESCAPE '\'
)`

const searchSymbolSQL = searchSymbolBaseSQL + `
SELECT kind, name, owner, detail, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, kind, name, source_shard_id
LIMIT ? OFFSET ?`
