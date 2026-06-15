package query

import (
	"context"
	"database/sql"
	"fmt"
	"strings"

	"dev.springdep/querier/internal/project"
)

type OpenSymbolResult struct {
	Kind   string              `json:"kind"`
	Class  *ClassDetail        `json:"class,omitempty"`
	Method *MethodSearchResult `json:"method,omitempty"`
}

type OpenSymbolResponse struct {
	Query    SymbolRequest      `json:"query"`
	Results  []OpenSymbolResult `json:"results"`
	Total    int                `json:"total"`
	Coverage Coverage           `json:"coverage"`
}

// OpenSymbol expands a symbol returned by search-symbol. Class names return
// full class detail; method names use the "class#method" shape emitted by
// search-symbol and return the matching method overloads.
func OpenSymbol(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	arg string,
) (OpenSymbolResponse, error) {
	if classFQN, methodName, ok := strings.Cut(arg, "#"); ok {
		return openMethodSymbol(ctx, pointer, manifestPath, classFQN, methodName, arg)
	}
	classResponse, err := GetClass(ctx, pointer, manifestPath, arg)
	if err != nil {
		return OpenSymbolResponse{}, err
	}
	if classResponse.Total > 0 {
		results := make([]OpenSymbolResult, 0, len(classResponse.Results))
		for index := range classResponse.Results {
			class := classResponse.Results[index]
			results = append(results, OpenSymbolResult{Kind: "class", Class: &class})
		}
		return OpenSymbolResponse{
			Query:    SymbolRequest{Command: "open-symbol", Arg: arg},
			Results:  results,
			Total:    len(results),
			Coverage: coverageFrom(pointer),
		}, nil
	}
	methodResponse, err := FindMethod(ctx, pointer, manifestPath, arg, 1, 20)
	if err != nil {
		return OpenSymbolResponse{}, err
	}
	results := make([]OpenSymbolResult, 0, len(methodResponse.Results))
	for index := range methodResponse.Results {
		method := methodResponse.Results[index]
		results = append(results, OpenSymbolResult{Kind: "method", Method: &method})
	}
	return OpenSymbolResponse{
		Query:    SymbolRequest{Command: "open-symbol", Arg: arg},
		Results:  results,
		Total:    methodResponse.Total,
		Coverage: coverageFrom(pointer),
	}, nil
}

func openMethodSymbol(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	classFQN string,
	methodName string,
	arg string,
) (OpenSymbolResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return OpenSymbolResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()
	rows, err := session.QueryContext(
		ctx,
		`SELECT c.fqn, m.name, m.descriptor, m.return_fqn, m.modifiers, m.source_shard_id
		 FROM methods m
		 JOIN classes c ON c.id = m.class_id
		 WHERE c.fqn = ? AND m.name = ?
		 ORDER BY m.descriptor, m.source_shard_id`,
		classFQN, methodName,
	)
	if err != nil {
		return OpenSymbolResponse{}, fmt.Errorf("query method symbol: %w", err)
	}
	defer rows.Close()
	type pendingMethod struct {
		method  MethodSearchResult
		shardID string
	}
	pending := []pendingMethod{}
	for rows.Next() {
		var item pendingMethod
		var returnFQN sql.NullString
		if err := rows.Scan(
			&item.method.ClassFQN, &item.method.Name, &item.method.Descriptor,
			&returnFQN, &item.method.Modifiers, &item.shardID,
		); err != nil {
			return OpenSymbolResponse{}, fmt.Errorf("scan method symbol: %w", err)
		}
		item.method.ReturnFQN = nullableString(returnFQN)
		item.method.Score = 100
		item.method.MatchReason = "exact_method"
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		return OpenSymbolResponse{}, fmt.Errorf("iterate method symbol: %w", err)
	}
	sources, err := loadSourcesIfNeeded(ctx, manifestPath, len(pending))
	if err != nil {
		return OpenSymbolResponse{}, err
	}
	results := make([]OpenSymbolResult, 0, len(pending))
	for _, item := range pending {
		item.method.SourceJar = sourceJarName(sources, item.shardID)
		method := item.method
		results = append(results, OpenSymbolResult{Kind: "method", Method: &method})
	}
	return OpenSymbolResponse{
		Query:    SymbolRequest{Command: "open-symbol", Arg: arg},
		Results:  results,
		Total:    len(results),
		Coverage: coverageFrom(pointer),
	}, nil
}
