package query

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"

	"dev.springdep/querier/internal/project"
)

type SymbolRequest struct {
	Command string `json:"command"`
	Arg     string `json:"arg"`
}

type SymbolResult struct {
	FQN        string          `json:"fqn"`
	SourceJar  string          `json:"source_jar"`
	Attributes json.RawMessage `json:"attributes"`
}

type SymbolResponse struct {
	Query    SymbolRequest  `json:"query"`
	Results  []SymbolResult `json:"results"`
	Total    int            `json:"total"`
	Page     int            `json:"page"`
	PageSize int            `json:"page_size"`
	Coverage Coverage       `json:"coverage"`
}

func FindImplementations(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	targetFQN string,
	page int,
	pageSize int,
) (SymbolResponse, error) {
	return findSymbols(
		ctx,
		pointer,
		manifestPath,
		"find-implementations",
		targetFQN,
		page,
		pageSize,
		`SELECT COUNT(*)
		 FROM classes c
		 JOIN class_interfaces ci ON ci.class_id = c.id
		 WHERE ci.interface_fqn = ?`,
		`SELECT c.fqn, c.source_shard_id
		 FROM classes c
		 JOIN class_interfaces ci ON ci.class_id = c.id
		 WHERE ci.interface_fqn = ?
		 ORDER BY c.fqn, c.source_shard_id
		 LIMIT ? OFFSET ?`,
		false,
	)
}

func FindByAnnotation(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	annotationFQN string,
	page int,
	pageSize int,
) (SymbolResponse, error) {
	return findSymbols(
		ctx,
		pointer,
		manifestPath,
		"find-by-annotation",
		annotationFQN,
		page,
		pageSize,
		`SELECT COUNT(*)
		 FROM annotations a
		 JOIN classes c ON c.id = a.target_id
		 WHERE a.target_kind = 'class' AND a.annotation_fqn = ?`,
		`SELECT c.fqn, c.source_shard_id, a.attributes
		 FROM annotations a
		 JOIN classes c ON c.id = a.target_id
		 WHERE a.target_kind = 'class' AND a.annotation_fqn = ?
		 ORDER BY c.fqn, c.source_shard_id
		 LIMIT ? OFFSET ?`,
		true,
	)
}

func findSymbols(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	command string,
	arg string,
	page int,
	pageSize int,
	countSQL string,
	selectSQL string,
	withAttributes bool,
) (SymbolResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return SymbolResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	var total int
	if err := session.QueryRowContext(ctx, countSQL, arg).Scan(&total); err != nil {
		return SymbolResponse{}, fmt.Errorf("count %s results: %w", command, err)
	}
	rows, err := session.QueryContext(
		ctx,
		selectSQL,
		arg,
		pageSize,
		(page-1)*pageSize,
	)
	if err != nil {
		return SymbolResponse{}, fmt.Errorf("query %s results: %w", command, err)
	}
	defer rows.Close()

	type pendingResult struct {
		result  SymbolResult
		shardID string
	}
	pending := make([]pendingResult, 0)
	for rows.Next() {
		var result SymbolResult
		var shardID string
		if withAttributes {
			var attributes sql.NullString
			if err := rows.Scan(&result.FQN, &shardID, &attributes); err != nil {
				return SymbolResponse{}, fmt.Errorf("scan %s result: %w", command, err)
			}
			if attributes.Valid && json.Valid([]byte(attributes.String)) {
				result.Attributes = json.RawMessage(attributes.String)
			}
		} else if err := rows.Scan(&result.FQN, &shardID); err != nil {
			return SymbolResponse{}, fmt.Errorf("scan %s result: %w", command, err)
		}
		pending = append(pending, pendingResult{result: result, shardID: shardID})
	}
	if err := rows.Err(); err != nil {
		return SymbolResponse{}, fmt.Errorf("iterate %s results: %w", command, err)
	}

	sources, err := loadSourceJars(ctx, manifestPath)
	if err != nil {
		return SymbolResponse{}, err
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
		Query:    SymbolRequest{Command: command, Arg: arg},
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
