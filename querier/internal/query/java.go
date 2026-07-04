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

	type pendingClass struct {
		result  ClassSearchResult
		shardID string
	}
	scan := func(sqlText string) ([]pendingClass, error) {
		args := matchArgs(term)
		rows, err := session.QueryContext(
			ctx, sqlText, append(args, pageSize+1, (page-1)*pageSize)...,
		)
		if err != nil {
			return nil, fmt.Errorf("query class results: %w", err)
		}
		defer rows.Close()
		pending := []pendingClass{}
		for rows.Next() {
			var item pendingClass
			var simpleName, packageName, kind, super sql.NullString
			if err := rows.Scan(
				&item.result.FQN, &simpleName, &packageName, &kind, &super, &item.shardID,
				&item.result.Score, &item.result.MatchReason,
			); err != nil {
				return nil, fmt.Errorf("scan class result: %w", err)
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
		return pending, rows.Err()
	}

	// Indexed-branches-first: the fast query probes only equality and
	// range-prefix predicates (all index-backed). Its every match outscores
	// every contains-tier match, so a full fast page equals the legacy page.
	// Underfilled pages fall back to the legacy contains scan.
	pending, err := scan(classSearchFastSQL)
	if err != nil {
		return ClassSearchResponse{}, err
	}
	if len(pending) <= pageSize {
		pending, err = scan(classSearchSQL)
		if err != nil {
			return ClassSearchResponse{}, err
		}
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

	type pendingMethod struct {
		result  MethodSearchResult
		shardID string
	}
	scan := func(sqlText string) ([]pendingMethod, error) {
		args := methodMatchArgs(term)
		rows, err := session.QueryContext(
			ctx, sqlText, append(args, pageSize+1, (page-1)*pageSize)...,
		)
		if err != nil {
			return nil, fmt.Errorf("query method results: %w", err)
		}
		defer rows.Close()
		pending := []pendingMethod{}
		for rows.Next() {
			var item pendingMethod
			var returnFQN sql.NullString
			if err := rows.Scan(
				&item.result.ClassFQN, &item.result.Name, &item.result.Descriptor,
				&returnFQN, &item.result.Modifiers, &item.shardID,
				&item.result.Score, &item.result.MatchReason,
			); err != nil {
				return nil, fmt.Errorf("scan method result: %w", err)
			}
			item.result.ReturnFQN = nullableString(returnFQN)
			pending = append(pending, item)
		}
		return pending, rows.Err()
	}

	pending, err := scan(methodSearchFastSQL)
	if err != nil {
		return MethodSearchResponse{}, err
	}
	if len(pending) <= pageSize {
		pending, err = scan(methodSearchSQL)
		if err != nil {
			return MethodSearchResponse{}, err
		}
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

	type pendingSearch struct {
		result  SearchSymbolResult
		shardID string
	}
	scan := func(sqlText string) ([]pendingSearch, error) {
		args := matchArgs(term)
		rows, err := session.QueryContext(
			ctx, sqlText, append(args, pageSize+1, (page-1)*pageSize)...,
		)
		if err != nil {
			return nil, fmt.Errorf("query search-symbol results: %w", err)
		}
		defer rows.Close()
		pending := []pendingSearch{}
		for rows.Next() {
			var item pendingSearch
			var owner, detail sql.NullString
			if err := rows.Scan(
				&item.result.Kind, &item.result.Name, &owner, &detail,
				&item.shardID, &item.result.Score, &item.result.MatchReason,
			); err != nil {
				return nil, fmt.Errorf("scan search-symbol result: %w", err)
			}
			item.result.Owner = nullableString(owner)
			item.result.Detail = nullableString(detail)
			pending = append(pending, item)
		}
		return pending, rows.Err()
	}

	pending, err := scan(searchSymbolFastSQL)
	if err != nil {
		return SearchSymbolResponse{}, err
	}
	if len(pending) <= pageSize {
		pending, err = scan(searchSymbolSQL)
		if err != nil {
			return SearchSymbolResponse{}, err
		}
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

// matchArgs binds the shared params row (term, simple, prefix, contains, hi).
// hi is the exclusive upper bound that turns "starts with term" into an
// index-usable range: default-collation LIKE is case-insensitive and cannot
// use the session's BINARY indexes, so prefix tiers match case-sensitively
// via ranges instead. Case-insensitive prefix hits still surface through the
// contains tier of the legacy query.
func matchArgs(term string) []any {
	escaped := escapeLike(term)
	return []any{term, term, escaped + "%", "%" + escaped + "%", prefixUpperBound(term)}
}

// methodMatchArgs extends matchArgs with the dotted split (cls, mname) so
// `a.b.C.method` exact lookups probe the classes/methods indexes instead of
// evaluating `c.fqn || '.' || m.name = term` per row.
func methodMatchArgs(term string) []any {
	cls, mname := splitFQN(term)
	return append(matchArgs(term), cls, mname)
}

// prefixUpperBound returns the smallest string greater than every string that
// starts with term ("" when no finite bound exists). Range predicates treat
// "" bounds as empty via `col < ''` being false for all real values.
func prefixUpperBound(term string) string {
	bytes := []byte(term)
	for i := len(bytes) - 1; i >= 0; i-- {
		if bytes[i] < 0xFF {
			bytes[i]++
			return string(bytes[:i+1])
		}
	}
	return ""
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

// The scoring CASE is shared verbatim between the fast and legacy variants of
// each search so a page served by either path ranks identically: every
// fast-path tier scores >= 60 while contains-tier rows score <= 55, so a full
// fast page is exactly the legacy page.

const classSearchScoreSQL = `
         CASE
           WHEN c.fqn = p.term THEN 100
           WHEN c.simple_name = p.simple THEN 90
           WHEN c.simple_name >= p.simple AND c.simple_name < p.hi THEN 82
           WHEN c.fqn >= p.term AND c.fqn < p.hi THEN 80
           ELSE 50
         END AS score,
         CASE
           WHEN c.fqn = p.term THEN 'exact_fqn'
           WHEN c.simple_name = p.simple THEN 'simple_name'
           WHEN c.simple_name >= p.simple AND c.simple_name < p.hi THEN 'simple_prefix'
           WHEN c.fqn >= p.term AND c.fqn < p.hi THEN 'prefix'
           ELSE 'contains'
         END AS match_reason`

const classSearchSelectSQL = `
SELECT fqn, simple_name, package_name, kind, super_fqn, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, fqn, source_shard_id
LIMIT ? OFFSET ?`

const classSearchSQL = `
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
matches AS (
  SELECT c.fqn, c.simple_name, c.package_name, c.kind, c.super_fqn, c.source_shard_id,` +
	classSearchScoreSQL + `
  FROM classes c, params p
  WHERE c.fqn = p.term
     OR c.simple_name = p.simple
     OR c.simple_name LIKE p.prefix ESCAPE '\'
     OR c.fqn LIKE p.prefix ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)` + classSearchSelectSQL

const classSearchFastSQL = `
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
matches AS (
  SELECT c.fqn, c.simple_name, c.package_name, c.kind, c.super_fqn, c.source_shard_id,` +
	classSearchScoreSQL + `
  FROM classes c, params p
  WHERE c.fqn = p.term
     OR c.simple_name = p.simple
     OR (p.hi <> '' AND c.simple_name >= p.simple AND c.simple_name < p.hi)
     OR (p.hi <> '' AND c.fqn >= p.term AND c.fqn < p.hi)
)` + classSearchSelectSQL

const methodSearchScoreSQL = `
         CASE
           WHEN m.symbol = p.term THEN 100
           WHEN c.fqn = p.cls AND m.name = p.mname THEN 100
           WHEN m.name = p.term THEN 95
           WHEN m.name >= p.simple AND m.name < p.hi THEN 75
           ELSE 50
         END AS score,
         CASE
           WHEN m.symbol = p.term THEN 'exact_method'
           WHEN c.fqn = p.cls AND m.name = p.mname THEN 'exact_method'
           WHEN m.name = p.term THEN 'method_name'
           WHEN m.name >= p.simple AND m.name < p.hi THEN 'method_prefix'
           ELSE 'contains'
         END AS match_reason`

const methodSearchSelectSQL = `
SELECT class_fqn, name, descriptor, return_fqn, modifiers, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, class_fqn, name, descriptor, source_shard_id
LIMIT ? OFFSET ?`

const methodSearchSQL = `
WITH params(term, simple, prefix, contains, hi, cls, mname) AS (VALUES (?, ?, ?, ?, ?, ?, ?)),
matches AS (
  SELECT c.fqn AS class_fqn, m.name, m.descriptor, m.return_fqn, m.modifiers,
         m.source_shard_id,` + methodSearchScoreSQL + `
  FROM methods m
  JOIN classes c ON c.id = m.class_id
  , params p
  WHERE m.symbol = p.term
     OR (c.fqn = p.cls AND m.name = p.mname)
     OR m.name = p.term
     OR m.name LIKE p.prefix ESCAPE '\'
     OR m.name LIKE p.contains ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)` + methodSearchSelectSQL

// methodSearchFastSQL gathers candidate method ids per indexed probe first
// (UNION dedupes ids), then scores only those rows; OR-ing predicates across
// the methods/classes join would defeat the per-index probes.
const methodSearchFastSQL = `
WITH params(term, simple, prefix, contains, hi, cls, mname) AS (VALUES (?, ?, ?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT m.id FROM methods m, params p WHERE m.symbol = p.term
  UNION
  SELECT m.id FROM methods m JOIN classes c ON c.id = m.class_id, params p
  WHERE p.cls <> '' AND c.fqn = p.cls AND m.name = p.mname
  UNION
  SELECT m.id FROM methods m, params p WHERE m.name = p.term
  UNION
  SELECT m.id FROM methods m, params p
  WHERE p.hi <> '' AND m.name >= p.simple AND m.name < p.hi
),
matches AS (
  SELECT c.fqn AS class_fqn, m.name, m.descriptor, m.return_fqn, m.modifiers,
         m.source_shard_id,` + methodSearchScoreSQL + `
  FROM methods m
  JOIN hits h ON h.id = m.id
  JOIN classes c ON c.id = m.class_id
  , params p
)` + methodSearchSelectSQL

const searchSymbolScoreSQL = `
         CASE
           WHEN s.kind = 'class' AND s.name = p.term THEN 100
           WHEN s.kind = 'method' AND s.name = p.term THEN 98
           WHEN s.kind = 'method' AND s.simple_name = p.simple THEN 92
           WHEN s.kind = 'class' AND s.simple_name = p.simple THEN 90
           WHEN s.kind = 'annotation' AND s.name = p.term THEN 88
           WHEN s.kind = 'spi' AND (s.name = p.term OR s.owner = p.term) THEN 86
           WHEN s.kind = 'config-property' AND s.name = p.term THEN 84
           WHEN s.kind = 'class' AND s.simple_name >= p.simple AND s.simple_name < p.hi THEN 82
           WHEN s.kind = 'class' AND s.name >= p.term AND s.name < p.hi THEN 80
           WHEN s.kind = 'annotation' AND s.name >= p.term AND s.name < p.hi THEN 78
           WHEN s.kind = 'spi' AND s.name >= p.term AND s.name < p.hi THEN 76
           WHEN s.kind = 'method' AND s.simple_name >= p.simple AND s.simple_name < p.hi THEN 75
           WHEN s.kind = 'config-property' AND s.name >= p.term AND s.name < p.hi THEN 70
           WHEN s.kind = 'string' AND s.name = p.term THEN 65
           WHEN s.kind = 'string' AND s.name >= p.term AND s.name < p.hi THEN 60
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
           WHEN s.kind = 'class' AND s.simple_name >= p.simple AND s.simple_name < p.hi THEN 'simple_prefix'
           WHEN s.kind = 'class' AND s.name >= p.term AND s.name < p.hi THEN 'prefix'
           WHEN s.kind = 'annotation' AND s.name >= p.term AND s.name < p.hi THEN 'annotation_prefix'
           WHEN s.kind = 'spi' AND s.name >= p.term AND s.name < p.hi THEN 'spi_prefix'
           WHEN s.kind = 'method' AND s.simple_name >= p.simple AND s.simple_name < p.hi THEN 'method_prefix'
           WHEN s.kind = 'config-property' AND s.name >= p.term AND s.name < p.hi THEN 'config_prefix'
           WHEN s.kind = 'string' AND s.name = p.term THEN 'string_constant'
           WHEN s.kind = 'string' AND s.name >= p.term AND s.name < p.hi THEN 'string_prefix'
           ELSE s.kind || '_contains'
         END AS match_reason`

const searchSymbolSQL = `
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
matches AS (
  SELECT s.kind, s.name, s.owner, s.detail, s.source_shard_id,` +
	searchSymbolScoreSQL + `
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
)
SELECT kind, name, owner, detail, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, kind, name, source_shard_id
LIMIT ? OFFSET ?`

// searchSymbolFastSQL probes each base table with equality and range-prefix
// predicates only, bypassing the search_symbols view so every branch uses its
// own index. Branch scoring mirrors searchSymbolScoreSQL for the predicates a
// branch can match; anything else is contains-tier and legacy-only.
const searchSymbolFastSQL = `
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT kind, name, owner, detail, source_shard_id, score, match_reason FROM (
  SELECT 'class' AS kind, c.fqn AS name, NULL AS owner, c.kind AS detail,
         c.source_shard_id,
         CASE
           WHEN c.fqn = p.term THEN 100
           WHEN c.simple_name = p.simple THEN 90
           WHEN c.simple_name >= p.simple AND c.simple_name < p.hi THEN 82
           ELSE 80
         END AS score,
         CASE
           WHEN c.fqn = p.term THEN 'exact_fqn'
           WHEN c.simple_name = p.simple THEN 'simple_name'
           WHEN c.simple_name >= p.simple AND c.simple_name < p.hi THEN 'simple_prefix'
           ELSE 'prefix'
         END AS match_reason
  FROM classes c, params p
  WHERE c.fqn = p.term
     OR c.simple_name = p.simple
     OR (p.hi <> '' AND c.simple_name >= p.simple AND c.simple_name < p.hi)
     OR (p.hi <> '' AND c.fqn >= p.term AND c.fqn < p.hi)
  UNION ALL
  SELECT 'method', m.symbol, c.fqn, m.descriptor, m.source_shard_id,
         CASE
           WHEN m.symbol = p.term THEN 98
           WHEN m.name = p.simple THEN 92
           ELSE 75
         END,
         CASE
           WHEN m.symbol = p.term THEN 'exact_method'
           WHEN m.name = p.simple THEN 'method_name'
           ELSE 'method_prefix'
         END
  FROM methods m JOIN classes c ON c.id = m.class_id, params p
  WHERE m.symbol = p.term
     OR m.name = p.simple
     OR (p.hi <> '' AND m.name >= p.simple AND m.name < p.hi)
  UNION ALL
  SELECT 'annotation', a.annotation_fqn,
         CASE WHEN a.target_kind = 'class' THEN c.fqn
              WHEN a.target_kind = 'method' THEN m.symbol
              ELSE NULL END,
         a.attributes, a.source_shard_id,
         CASE WHEN a.annotation_fqn = p.term THEN 88 ELSE 78 END,
         CASE WHEN a.annotation_fqn = p.term THEN 'annotation_fqn' ELSE 'annotation_prefix' END
  FROM annotations a
  LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
  LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
  LEFT JOIN classes mc ON mc.id = m.class_id
  , params p
  WHERE a.annotation_fqn = p.term
     OR (p.hi <> '' AND a.annotation_fqn >= p.term AND a.annotation_fqn < p.hi)
  UNION ALL
  SELECT 'spi', s.impl_fqn, s.key, s.mechanism, s.source_shard_id,
         CASE WHEN s.impl_fqn = p.term OR s.key = p.term THEN 86 ELSE 76 END,
         CASE WHEN s.impl_fqn = p.term THEN 'spi_impl'
              WHEN s.key = p.term THEN 'spi_key'
              ELSE 'spi_prefix' END
  FROM spi_registrations s, params p
  WHERE s.impl_fqn = p.term
     OR s.key = p.term
     OR (p.hi <> '' AND s.impl_fqn >= p.term AND s.impl_fqn < p.hi)
  UNION ALL
  SELECT 'config-property', cp.name, cp.source_fqn, cp.type_fqn, cp.source_shard_id,
         CASE WHEN cp.name = p.term THEN 84 ELSE 70 END,
         CASE WHEN cp.name = p.term THEN 'config_property' ELSE 'config_prefix' END
  FROM config_properties cp, params p
  WHERE cp.name = p.term
     OR (p.hi <> '' AND cp.name >= p.term AND cp.name < p.hi)
  UNION ALL
  SELECT 'string', sc.value, c.fqn,
         CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END,
         sc.source_shard_id,
         CASE WHEN sc.value = p.term THEN 65 ELSE 60 END,
         CASE WHEN sc.value = p.term THEN 'string_constant' ELSE 'string_prefix' END
  FROM string_constants sc
  JOIN classes c ON c.id = sc.class_id
  LEFT JOIN methods m ON m.id = sc.method_id
  , params p
  WHERE sc.value = p.term
     OR (p.hi <> '' AND sc.value >= p.term AND sc.value < p.hi)
)
ORDER BY score DESC, kind, name, source_shard_id
LIMIT ? OFFSET ?`
