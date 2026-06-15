package query

import (
	"context"
	"database/sql"
	"fmt"

	"dev.research4jar/querier/internal/project"
)

type ExtensionPoint struct {
	Mechanism       string  `json:"mechanism"`
	Key             *string `json:"key"`
	Implementations int     `json:"implementations"`
}

type ExtensionRegistration struct {
	Mechanism string  `json:"mechanism"`
	Key       *string `json:"key"`
	ImplFQN   string  `json:"impl_fqn"`
	SourceJar string  `json:"source_jar"`
}

type ExtensionPointsResponse struct {
	Query    SymbolRequest           `json:"query"`
	Points   []ExtensionPoint        `json:"extension_points,omitempty"`
	Results  []ExtensionRegistration `json:"results,omitempty"`
	Total    int                     `json:"total"`
	Page     int                     `json:"page"`
	PageSize int                     `json:"page_size"`
	Coverage Coverage                `json:"coverage"`
}

// ListExtensionPoints without an argument summarizes every SPI mechanism/key
// pair with its registration count. With an argument it lists the concrete
// registrations whose key or mechanism matches.
func ListExtensionPoints(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	arg string,
	page int,
	pageSize int,
) (ExtensionPointsResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return ExtensionPointsResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	response := ExtensionPointsResponse{
		Query:    SymbolRequest{Command: "list-extension-points", Arg: arg},
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}

	if arg == "" {
		if err := session.QueryRowContext(
			ctx,
			`SELECT COUNT(*) FROM (
			   SELECT 1 FROM spi_registrations GROUP BY mechanism, key
			 )`,
		).Scan(&response.Total); err != nil {
			return ExtensionPointsResponse{}, fmt.Errorf("count extension points: %w", err)
		}
		rows, err := session.QueryContext(
			ctx,
			`SELECT mechanism, key, COUNT(*)
			 FROM spi_registrations
			 GROUP BY mechanism, key
			 ORDER BY mechanism, COALESCE(key, '')
			 LIMIT ? OFFSET ?`,
			pageSize, (page-1)*pageSize,
		)
		if err != nil {
			return ExtensionPointsResponse{}, fmt.Errorf("query extension points: %w", err)
		}
		defer rows.Close()
		response.Points = []ExtensionPoint{}
		for rows.Next() {
			var point ExtensionPoint
			var key sql.NullString
			if err := rows.Scan(&point.Mechanism, &key, &point.Implementations); err != nil {
				return ExtensionPointsResponse{}, fmt.Errorf("scan extension point: %w", err)
			}
			point.Key = nullableString(key)
			response.Points = append(response.Points, point)
		}
		if err := rows.Err(); err != nil {
			return ExtensionPointsResponse{}, fmt.Errorf("iterate extension points: %w", err)
		}
		return response, nil
	}

	if err := session.QueryRowContext(
		ctx,
		`SELECT COUNT(*) FROM spi_registrations WHERE key = ? OR mechanism = ?`,
		arg, arg,
	).Scan(&response.Total); err != nil {
		return ExtensionPointsResponse{}, fmt.Errorf("count registrations: %w", err)
	}
	rows, err := session.QueryContext(
		ctx,
		`SELECT mechanism, key, impl_fqn, source_shard_id
		 FROM spi_registrations
		 WHERE key = ? OR mechanism = ?
		 ORDER BY mechanism, COALESCE(key, ''), impl_fqn, source_shard_id
		 LIMIT ? OFFSET ?`,
		arg, arg, pageSize, (page-1)*pageSize,
	)
	if err != nil {
		return ExtensionPointsResponse{}, fmt.Errorf("query registrations: %w", err)
	}
	defer rows.Close()

	type pendingRegistration struct {
		registration ExtensionRegistration
		shardID      string
	}
	pending := []pendingRegistration{}
	for rows.Next() {
		var item pendingRegistration
		var key sql.NullString
		if err := rows.Scan(
			&item.registration.Mechanism, &key, &item.registration.ImplFQN, &item.shardID,
		); err != nil {
			return ExtensionPointsResponse{}, fmt.Errorf("scan registration: %w", err)
		}
		item.registration.Key = nullableString(key)
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		return ExtensionPointsResponse{}, fmt.Errorf("iterate registrations: %w", err)
	}

	var sources map[string]string
	if len(pending) > 0 {
		if sources, err = loadSourceJars(ctx, manifestPath); err != nil {
			return ExtensionPointsResponse{}, err
		}
	}
	response.Results = []ExtensionRegistration{}
	for _, item := range pending {
		item.registration.SourceJar = sourceJarName(sources, item.shardID)
		response.Results = append(response.Results, item.registration)
	}
	return response, nil
}
