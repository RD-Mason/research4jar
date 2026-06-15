package query

import (
	"context"
	"database/sql"
	"fmt"
	"sort"
	"strings"

	"dev.springdep/querier/internal/project"
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
	var total int
	if err := session.QueryRowContext(ctx, classSearchCountSQL, args...).Scan(&total); err != nil {
		return ClassSearchResponse{}, fmt.Errorf("count class results: %w", err)
	}
	selectArgs := append(append([]any{}, args...), pageSize, (page-1)*pageSize)
	rows, err := session.QueryContext(ctx, classSearchSQL, selectArgs...)
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
		var kind, super sql.NullString
		if err := rows.Scan(
			&item.result.FQN, &kind, &super, &item.shardID,
			&item.result.Score, &item.result.MatchReason,
		); err != nil {
			return ClassSearchResponse{}, fmt.Errorf("scan class result: %w", err)
		}
		item.result.Kind = nullableString(kind)
		item.result.SuperFQN = nullableString(super)
		item.result.Package, item.result.SimpleName = splitFQN(item.result.FQN)
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		return ClassSearchResponse{}, fmt.Errorf("iterate class results: %w", err)
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
		Total:    total,
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
	var total int
	if err := session.QueryRowContext(ctx, methodSearchCountSQL, args...).Scan(&total); err != nil {
		return MethodSearchResponse{}, fmt.Errorf("count method results: %w", err)
	}
	selectArgs := append(append([]any{}, args...), pageSize, (page-1)*pageSize)
	rows, err := session.QueryContext(ctx, methodSearchSQL, selectArgs...)
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
		Total:    total,
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
		`SELECT fqn, source_shard_id
		 FROM classes
		 WHERE ? = '' OR fqn = ? OR fqn LIKE ? ESCAPE '\'
		 ORDER BY fqn, source_shard_id`,
		prefix, prefix, likePrefix,
	)
	if err != nil {
		return PackageListResponse{}, fmt.Errorf("query packages: %w", err)
	}
	defer rows.Close()

	counts := map[string]int{}
	for rows.Next() {
		var fqn, shardID string
		if err := rows.Scan(&fqn, &shardID); err != nil {
			return PackageListResponse{}, fmt.Errorf("scan package row: %w", err)
		}
		pkg, _ := splitFQN(fqn)
		counts[pkg+"\x00"+shardID]++
	}
	if err := rows.Err(); err != nil {
		return PackageListResponse{}, fmt.Errorf("iterate packages: %w", err)
	}

	sources, err := loadSourcesIfNeeded(ctx, manifestPath, len(counts))
	if err != nil {
		return PackageListResponse{}, err
	}
	all := make([]PackageSummary, 0, len(counts))
	for key, count := range counts {
		parts := strings.SplitN(key, "\x00", 2)
		all = append(all, PackageSummary{
			Package:   parts[0],
			SourceJar: sourceJarName(sources, parts[1]),
			Classes:   count,
		})
	}
	sort.Slice(all, func(i, j int) bool {
		if all[i].Package != all[j].Package {
			return all[i].Package < all[j].Package
		}
		return all[i].SourceJar < all[j].SourceJar
	})
	start, end := pageBounds(page, pageSize, len(all))
	return PackageListResponse{
		Query:    SymbolRequest{Command: "list-packages", Arg: prefix},
		Results:  all[start:end],
		Total:    len(all),
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
	var total int
	if err := session.QueryRowContext(ctx, searchSymbolCountSQL, args...).Scan(&total); err != nil {
		return SearchSymbolResponse{}, fmt.Errorf("count search-symbol results: %w", err)
	}
	selectArgs := append(append([]any{}, args...), pageSize, (page-1)*pageSize)
	rows, err := session.QueryContext(ctx, searchSymbolSQL, selectArgs...)
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
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}, nil
}

func matchArgs(term string) []any {
	escaped := escapeLike(term)
	return []any{term, "%." + escaped, escaped + "%", "%" + escaped + "%"}
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
  SELECT c.fqn, c.kind, c.super_fqn, c.source_shard_id,
         CASE
           WHEN c.fqn = p.term THEN 100
           WHEN c.fqn LIKE p.simple ESCAPE '\' THEN 90
           WHEN c.fqn LIKE p.prefix ESCAPE '\' THEN 80
           ELSE 50
         END AS score,
         CASE
           WHEN c.fqn = p.term THEN 'exact_fqn'
           WHEN c.fqn LIKE p.simple ESCAPE '\' THEN 'simple_name'
           WHEN c.fqn LIKE p.prefix ESCAPE '\' THEN 'prefix'
           ELSE 'contains'
         END AS match_reason
  FROM classes c, params p
  WHERE c.fqn = p.term
     OR c.fqn LIKE p.simple ESCAPE '\'
     OR c.fqn LIKE p.prefix ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)`

const classSearchCountSQL = classSearchBaseSQL + `
SELECT COUNT(*) FROM matches`

const classSearchSQL = classSearchBaseSQL + `
SELECT fqn, kind, super_fqn, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, fqn, source_shard_id
LIMIT ? OFFSET ?`

const methodSearchBaseSQL = `
WITH params(term, simple, prefix, contains) AS (VALUES (?, ?, ?, ?)),
matches AS (
  SELECT c.fqn AS class_fqn, m.name, m.descriptor, m.return_fqn, m.modifiers,
         m.source_shard_id,
         CASE
           WHEN c.fqn || '#' || m.name = p.term THEN 100
           WHEN c.fqn || '.' || m.name = p.term THEN 100
           WHEN m.name = p.term THEN 95
           WHEN m.name LIKE p.prefix ESCAPE '\' THEN 75
           ELSE 50
         END AS score,
         CASE
           WHEN c.fqn || '#' || m.name = p.term THEN 'exact_method'
           WHEN c.fqn || '.' || m.name = p.term THEN 'exact_method'
           WHEN m.name = p.term THEN 'method_name'
           WHEN m.name LIKE p.prefix ESCAPE '\' THEN 'method_prefix'
           ELSE 'contains'
         END AS match_reason
  FROM methods m
  JOIN classes c ON c.id = m.class_id
  , params p
  WHERE c.fqn || '#' || m.name = p.term
     OR c.fqn || '.' || m.name = p.term
     OR m.name = p.term
     OR m.name LIKE p.prefix ESCAPE '\'
     OR m.name LIKE p.contains ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)`

const methodSearchCountSQL = methodSearchBaseSQL + `
SELECT COUNT(*) FROM matches`

const methodSearchSQL = methodSearchBaseSQL + `
SELECT class_fqn, name, descriptor, return_fqn, modifiers, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, class_fqn, name, descriptor, source_shard_id
LIMIT ? OFFSET ?`

const searchSymbolBaseSQL = `
WITH params(term, simple, prefix, contains) AS (VALUES (?, ?, ?, ?)),
matches AS (
  SELECT 'class' AS kind, c.fqn AS name, NULL AS owner, c.kind AS detail,
         c.source_shard_id,
         CASE WHEN c.fqn = p.term THEN 100 WHEN c.fqn LIKE p.simple ESCAPE '\' THEN 90 ELSE 55 END AS score,
         CASE WHEN c.fqn = p.term THEN 'exact_fqn' WHEN c.fqn LIKE p.simple ESCAPE '\' THEN 'simple_name' ELSE 'class_contains' END AS match_reason
  FROM classes c, params p
  WHERE c.fqn = p.term OR c.fqn LIKE p.simple ESCAPE '\' OR c.fqn LIKE p.contains ESCAPE '\'

  UNION ALL
  SELECT 'method', c.fqn || '#' || m.name, c.fqn, m.descriptor,
         m.source_shard_id,
         CASE WHEN c.fqn || '#' || m.name = p.term THEN 98 WHEN m.name = p.term THEN 92 ELSE 50 END,
         CASE WHEN c.fqn || '#' || m.name = p.term THEN 'exact_method' WHEN m.name = p.term THEN 'method_name' ELSE 'method_contains' END
  FROM methods m JOIN classes c ON c.id = m.class_id, params p
  WHERE c.fqn || '#' || m.name = p.term OR m.name = p.term
     OR m.name LIKE p.contains ESCAPE '\' OR c.fqn LIKE p.contains ESCAPE '\'

  UNION ALL
  SELECT 'annotation', a.annotation_fqn, c.fqn, a.attributes,
         a.source_shard_id,
         CASE WHEN a.annotation_fqn = p.term THEN 88 ELSE 45 END,
         CASE WHEN a.annotation_fqn = p.term THEN 'annotation_fqn' ELSE 'annotation_contains' END
  FROM annotations a
  LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id,
       params p
  WHERE a.annotation_fqn = p.term OR a.annotation_fqn LIKE p.contains ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'

  UNION ALL
  SELECT 'spi', s.impl_fqn, s.key, s.mechanism,
         s.source_shard_id,
         CASE WHEN s.impl_fqn = p.term OR s.key = p.term THEN 86 ELSE 44 END,
         CASE WHEN s.impl_fqn = p.term THEN 'spi_impl' WHEN s.key = p.term THEN 'spi_key' ELSE 'spi_contains' END
  FROM spi_registrations s, params p
  WHERE s.impl_fqn = p.term OR s.key = p.term OR s.mechanism = p.term
     OR s.impl_fqn LIKE p.contains ESCAPE '\'
     OR s.key LIKE p.contains ESCAPE '\'
     OR s.mechanism LIKE p.contains ESCAPE '\'

  UNION ALL
  SELECT 'config-property', cp.name, cp.source_fqn, cp.type_fqn,
         cp.source_shard_id,
         CASE WHEN cp.name = p.term THEN 84 WHEN cp.name LIKE p.prefix ESCAPE '\' THEN 70 ELSE 43 END,
         CASE WHEN cp.name = p.term THEN 'config_property' WHEN cp.name LIKE p.prefix ESCAPE '\' THEN 'config_prefix' ELSE 'config_contains' END
  FROM config_properties cp, params p
  WHERE cp.name = p.term OR cp.name LIKE p.prefix ESCAPE '\' OR cp.name LIKE p.contains ESCAPE '\'
     OR cp.source_fqn LIKE p.contains ESCAPE '\'

  UNION ALL
  SELECT 'string', sc.value, c.fqn,
         CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END,
         sc.source_shard_id,
         CASE WHEN sc.value = p.term THEN 65 ELSE 30 END,
         CASE WHEN sc.value = p.term THEN 'string_constant' ELSE 'string_contains' END
  FROM string_constants sc
  JOIN classes c ON c.id = sc.class_id
  LEFT JOIN methods m ON m.id = sc.method_id,
       params p
  WHERE sc.value = p.term OR sc.value LIKE p.contains ESCAPE '\'
)`

const searchSymbolCountSQL = searchSymbolBaseSQL + `
SELECT COUNT(*) FROM matches`

const searchSymbolSQL = searchSymbolBaseSQL + `
SELECT kind, name, owner, detail, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, kind, name, source_shard_id
LIMIT ? OFFSET ?`
