package query

import (
	"context"
	"database/sql"
	"fmt"
	"strings"

	"dev.springdep/querier/internal/project"
)

type StringConstant struct {
	Value     string  `json:"value"`
	ClassFQN  string  `json:"class_fqn"`
	Method    *string `json:"method"`
	SourceJar string  `json:"source_jar"`
}

type StringSearchResponse struct {
	Query    SymbolRequest    `json:"query"`
	Results  []StringConstant `json:"results"`
	Total    int              `json:"total"`
	Page     int              `json:"page"`
	PageSize int              `json:"page_size"`
	Coverage Coverage         `json:"coverage"`
}

// FindString searches extracted string constants by substring. Useful for
// locating which jar/class owns a property key, header name, or log message.
func FindString(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	text string,
	page int,
	pageSize int,
) (StringSearchResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return StringSearchResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	pattern := "%" + escapeLike(text) + "%"
	var total int
	if err := session.QueryRowContext(
		ctx,
		`SELECT COUNT(*) FROM string_constants WHERE value LIKE ? ESCAPE '\'`,
		pattern,
	).Scan(&total); err != nil {
		return StringSearchResponse{}, fmt.Errorf("count string constants: %w", err)
	}

	rows, err := session.QueryContext(
		ctx,
		`SELECT s.value, c.fqn, m.name, m.descriptor, s.source_shard_id
		 FROM string_constants s
		 JOIN classes c ON c.id = s.class_id
		 LEFT JOIN methods m ON m.id = s.method_id
		 WHERE s.value LIKE ? ESCAPE '\'
		 ORDER BY s.value, c.fqn, s.source_shard_id, COALESCE(m.name, ''), COALESCE(m.descriptor, '')
		 LIMIT ? OFFSET ?`,
		pattern, pageSize, (page-1)*pageSize,
	)
	if err != nil {
		return StringSearchResponse{}, fmt.Errorf("query string constants: %w", err)
	}
	defer rows.Close()

	type pendingConstant struct {
		constant StringConstant
		shardID  string
	}
	pending := []pendingConstant{}
	for rows.Next() {
		var item pendingConstant
		var methodName, methodDescriptor sql.NullString
		if err := rows.Scan(
			&item.constant.Value, &item.constant.ClassFQN,
			&methodName, &methodDescriptor, &item.shardID,
		); err != nil {
			return StringSearchResponse{}, fmt.Errorf("scan string constant: %w", err)
		}
		if methodName.Valid && methodDescriptor.Valid {
			signature := methodName.String + methodDescriptor.String
			item.constant.Method = &signature
		}
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		return StringSearchResponse{}, fmt.Errorf("iterate string constants: %w", err)
	}

	var sources map[string]string
	if len(pending) > 0 {
		if sources, err = loadSourceJars(ctx, manifestPath); err != nil {
			return StringSearchResponse{}, err
		}
	}
	results := make([]StringConstant, 0, len(pending))
	for _, item := range pending {
		item.constant.SourceJar = sourceJarName(sources, item.shardID)
		results = append(results, item.constant)
	}

	return StringSearchResponse{
		Query:    SymbolRequest{Command: "find-string", Arg: text},
		Results:  results,
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}, nil
}

func escapeLike(text string) string {
	replacer := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return replacer.Replace(text)
}
