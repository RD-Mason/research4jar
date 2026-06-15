package query

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"

	"dev.research4jar/querier/internal/project"
)

type SymbolRequest struct {
	Command string `json:"command"`
	Arg     string `json:"arg"`
	Direct  bool   `json:"direct,omitempty"`
}

type SymbolResult struct {
	FQN               string          `json:"fqn"`
	SourceJar         string          `json:"source_jar"`
	Attributes        json.RawMessage `json:"attributes"`
	MatchedAnnotation string          `json:"matched_annotation,omitempty"`
}

type SymbolResponse struct {
	Query    SymbolRequest  `json:"query"`
	Results  []SymbolResult `json:"results"`
	Total    int            `json:"total"`
	Page     int            `json:"page"`
	PageSize int            `json:"page_size"`
	Coverage Coverage       `json:"coverage"`
}

// Transitive closure over declared supertypes: the seed FQN expands through
// super_fqn edges and class_interfaces edges, both stored as symbolic
// references, so the walk crosses jar boundaries inside the merged session.
const transitiveImplCTE = `
WITH RECURSIVE impl(fqn) AS (
  VALUES(?)
  UNION
  SELECT c.fqn FROM classes c JOIN impl ON c.super_fqn = impl.fqn
  UNION
  SELECT c.fqn FROM classes c
    JOIN class_interfaces ci ON ci.class_id = c.id
    JOIN impl ON ci.interface_fqn = impl.fqn
)`

// Meta-annotation closure: an annotation type (kind='annotation') annotated by
// a member of the set joins the set. Plain UNION deduplicates, which also
// terminates self-referential annotations such as @Documented.
const metaAnnotationCTE = `
WITH RECURSIVE meta(fqn) AS (
  VALUES(?)
  UNION
  SELECT c.fqn
  FROM classes c
  JOIN annotations a ON a.target_kind = 'class' AND a.target_id = c.id
  JOIN meta ON a.annotation_fqn = meta.fqn
  WHERE c.kind = 'annotation'
)`

func FindImplementations(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	targetFQN string,
	direct bool,
	page int,
	pageSize int,
) (SymbolResponse, error) {
	countSQL := transitiveImplCTE + `
		SELECT COUNT(*) FROM classes c
		WHERE c.fqn IN (SELECT fqn FROM impl) AND c.fqn <> ?`
	selectSQL := transitiveImplCTE + `
		SELECT c.fqn, c.source_shard_id
		FROM classes c
		WHERE c.fqn IN (SELECT fqn FROM impl) AND c.fqn <> ?
		ORDER BY c.fqn, c.source_shard_id
		LIMIT ? OFFSET ?`
	if direct {
		countSQL = `SELECT COUNT(*) FROM (
			SELECT class_id FROM class_interfaces WHERE interface_fqn = ?
			UNION
			SELECT id FROM classes WHERE super_fqn = ?
		)`
		selectSQL = `SELECT c.fqn, c.source_shard_id
			FROM classes c
			WHERE c.id IN (
			  SELECT class_id FROM class_interfaces WHERE interface_fqn = ?
			  UNION
			  SELECT id FROM classes WHERE super_fqn = ?
			)
			ORDER BY c.fqn, c.source_shard_id
			LIMIT ? OFFSET ?`
	}
	return findSymbols(
		ctx,
		pointer,
		manifestPath,
		SymbolRequest{Command: "find-implementations", Arg: targetFQN, Direct: direct},
		[]any{targetFQN, targetFQN},
		page,
		pageSize,
		countSQL,
		selectSQL,
		func(rows *sql.Rows) (SymbolResult, string, error) {
			var result SymbolResult
			var shardID string
			err := rows.Scan(&result.FQN, &shardID)
			return result, shardID, err
		},
	)
}

func FindByAnnotation(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	annotationFQN string,
	direct bool,
	page int,
	pageSize int,
) (SymbolResponse, error) {
	countSQL := metaAnnotationCTE + `
		SELECT COUNT(*)
		FROM annotations a
		JOIN classes c ON c.id = a.target_id
		WHERE a.target_kind = 'class' AND a.annotation_fqn IN (SELECT fqn FROM meta)`
	selectSQL := metaAnnotationCTE + `
		SELECT c.fqn, c.source_shard_id, a.attributes, a.annotation_fqn
		FROM annotations a
		JOIN classes c ON c.id = a.target_id
		WHERE a.target_kind = 'class' AND a.annotation_fqn IN (SELECT fqn FROM meta)
		ORDER BY c.fqn, c.source_shard_id, a.annotation_fqn, COALESCE(a.attributes, '')
		LIMIT ? OFFSET ?`
	if direct {
		countSQL = `SELECT COUNT(*)
			FROM annotations a
			JOIN classes c ON c.id = a.target_id
			WHERE a.target_kind = 'class' AND a.annotation_fqn = ?`
		selectSQL = `SELECT c.fqn, c.source_shard_id, a.attributes, a.annotation_fqn
			FROM annotations a
			JOIN classes c ON c.id = a.target_id
			WHERE a.target_kind = 'class' AND a.annotation_fqn = ?
			ORDER BY c.fqn, c.source_shard_id
			LIMIT ? OFFSET ?`
	}
	return findSymbols(
		ctx,
		pointer,
		manifestPath,
		SymbolRequest{Command: "find-by-annotation", Arg: annotationFQN, Direct: direct},
		[]any{annotationFQN},
		page,
		pageSize,
		countSQL,
		selectSQL,
		func(rows *sql.Rows) (SymbolResult, string, error) {
			var result SymbolResult
			var shardID string
			var attributes sql.NullString
			if err := rows.Scan(
				&result.FQN, &shardID, &attributes, &result.MatchedAnnotation,
			); err != nil {
				return result, shardID, err
			}
			if attributes.Valid && json.Valid([]byte(attributes.String)) {
				result.Attributes = json.RawMessage(attributes.String)
			}
			return result, shardID, nil
		},
	)
}

func findSymbols(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	request SymbolRequest,
	bindArgs []any,
	page int,
	pageSize int,
	countSQL string,
	selectSQL string,
	scan func(rows *sql.Rows) (SymbolResult, string, error),
) (SymbolResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return SymbolResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	var total int
	if err := session.QueryRowContext(ctx, countSQL, bindArgs...).Scan(&total); err != nil {
		return SymbolResponse{}, fmt.Errorf("count %s results: %w", request.Command, err)
	}
	selectArgs := append(append([]any{}, bindArgs...), pageSize, (page-1)*pageSize)
	rows, err := session.QueryContext(ctx, selectSQL, selectArgs...)
	if err != nil {
		return SymbolResponse{}, fmt.Errorf("query %s results: %w", request.Command, err)
	}
	defer rows.Close()

	type pendingResult struct {
		result  SymbolResult
		shardID string
	}
	pending := make([]pendingResult, 0)
	for rows.Next() {
		result, shardID, err := scan(rows)
		if err != nil {
			return SymbolResponse{}, fmt.Errorf("scan %s result: %w", request.Command, err)
		}
		pending = append(pending, pendingResult{result: result, shardID: shardID})
	}
	if err := rows.Err(); err != nil {
		return SymbolResponse{}, fmt.Errorf("iterate %s results: %w", request.Command, err)
	}

	var sources map[string]string
	if len(pending) > 0 {
		sources, err = loadSourceJars(ctx, manifestPath)
		if err != nil {
			return SymbolResponse{}, err
		}
	}
	results := make([]SymbolResult, 0, len(pending))
	for _, item := range pending {
		item.result.SourceJar = sources[item.shardID]
		if item.result.SourceJar == "" {
			item.result.SourceJar = item.shardID
		}
		results = append(results, item.result)
	}

	return SymbolResponse{
		Query:    request,
		Results:  results,
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}, nil
}

func coverageFrom(pointer project.Pointer) Coverage {
	return Coverage{
		JarsTotal:        pointer.Coverage.JarsTotal,
		JarsIndexed:      pointer.Coverage.JarsIndexed,
		JarsMissing:      nonNil(pointer.Coverage.JarsMissing),
		ExtractorVersion: pointer.ExtractorVersion,
	}
}
