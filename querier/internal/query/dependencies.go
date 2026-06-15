package query

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"

	"dev.research4jar/querier/internal/depgraph"
	"dev.research4jar/querier/internal/project"
)

type DependencyWhyResult struct {
	Coordinate  string   `json:"coordinate"`
	Artifact    string   `json:"artifact"`
	Version     string   `json:"version"`
	Scope       string   `json:"scope,omitempty"`
	Direct      bool     `json:"direct"`
	Depth       int      `json:"depth"`
	Path        []string `json:"path"`
	Parent      string   `json:"parent,omitempty"`
	MatchedBy   string   `json:"matched_by"`
	SourceJar   string   `json:"source_jar,omitempty"`
	SourceClass string   `json:"source_class,omitempty"`
}

type DependencyWhyResponse struct {
	Query    SymbolRequest         `json:"query"`
	Results  []DependencyWhyResult `json:"results"`
	Total    int                   `json:"total"`
	Coverage Coverage              `json:"coverage"`
}

type manifestSource struct {
	ShardID    string
	Coordinate string
	Filename   string
	Source     string
}

func WhyDependency(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	projectDir string,
	arg string,
) (DependencyWhyResponse, error) {
	graph, err := depgraph.Load(projectDir)
	if err != nil {
		if errors.Is(err, depgraph.ErrUnsupported) {
			return DependencyWhyResponse{}, errors.New(
				"no dependency provenance found; run research4jar index in a Maven project to create .research4jar/dependencies.json",
			)
		}
		return DependencyWhyResponse{}, fmt.Errorf("load dependency provenance: %w", err)
	}
	sources, err := loadManifestSources(ctx, manifestPath)
	if err != nil {
		return DependencyWhyResponse{}, err
	}

	targets, err := dependencyTargets(ctx, pointer, sources, arg)
	if err != nil {
		return DependencyWhyResponse{}, err
	}
	seen := map[string]DependencyWhyResult{}
	for _, target := range targets {
		for _, artifact := range graph.Artifacts {
			if !artifactMatchesTarget(artifact, target.coordinate) {
				continue
			}
			result := DependencyWhyResult{
				Coordinate:  artifact.Coordinate,
				Artifact:    artifact.Artifact,
				Version:     artifact.Version,
				Scope:       artifact.Scope,
				Direct:      artifact.Direct,
				Depth:       artifact.Depth,
				Path:        nonNil(artifact.Path),
				Parent:      artifact.Parent,
				MatchedBy:   target.matchedBy,
				SourceJar:   target.sourceJar,
				SourceClass: target.sourceClass,
			}
			key := result.Coordinate + "\x00" + result.MatchedBy + "\x00" + result.SourceClass
			seen[key] = result
		}
	}
	results := make([]DependencyWhyResult, 0, len(seen))
	for _, result := range seen {
		results = append(results, result)
	}
	sortDependencyWhy(results)
	return DependencyWhyResponse{
		Query:    SymbolRequest{Command: "why-dependency", Arg: arg},
		Results:  results,
		Total:    len(results),
		Coverage: coverageFrom(pointer),
	}, nil
}

type dependencyTarget struct {
	coordinate  string
	matchedBy   string
	sourceJar   string
	sourceClass string
}

func dependencyTargets(
	ctx context.Context,
	pointer project.Pointer,
	sources []manifestSource,
	arg string,
) ([]dependencyTarget, error) {
	targets := []dependencyTarget{}
	if strings.Contains(arg, ".") && !strings.Contains(arg, ":") && !strings.HasSuffix(arg, ".jar") {
		classTargets, err := classDependencyTargets(ctx, pointer, sources, arg)
		if err != nil {
			return nil, err
		}
		targets = append(targets, classTargets...)
	}
	for _, source := range sources {
		switch {
		case source.Coordinate != "" && artifactMatchesArg(source.Coordinate, arg):
			targets = append(targets, dependencyTarget{
				coordinate: source.Coordinate,
				matchedBy:  "coordinate",
				sourceJar:  source.Source,
			})
		case source.Filename == arg || filepath.Base(source.Filename) == arg:
			targets = append(targets, dependencyTarget{
				coordinate: source.Coordinate,
				matchedBy:  "jar_filename",
				sourceJar:  source.Source,
			})
		}
	}
	if len(targets) == 0 && strings.Contains(arg, ":") {
		targets = append(targets, dependencyTarget{coordinate: arg, matchedBy: "coordinate"})
	}
	return dedupeDependencyTargets(targets), nil
}

func classDependencyTargets(
	ctx context.Context,
	pointer project.Pointer,
	sources []manifestSource,
	classFQN string,
) ([]dependencyTarget, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return nil, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()
	rows, err := session.QueryContext(
		ctx,
		`SELECT source_shard_id FROM classes WHERE fqn = ? ORDER BY source_shard_id`,
		classFQN,
	)
	if err != nil {
		return nil, fmt.Errorf("query class source: %w", err)
	}
	defer rows.Close()
	byShard := map[string]manifestSource{}
	for _, source := range sources {
		byShard[source.ShardID] = source
	}
	targets := []dependencyTarget{}
	for rows.Next() {
		var shardID string
		if err := rows.Scan(&shardID); err != nil {
			return nil, fmt.Errorf("scan class source: %w", err)
		}
		source := byShard[shardID]
		if source.Coordinate == "" {
			continue
		}
		targets = append(targets, dependencyTarget{
			coordinate:  source.Coordinate,
			matchedBy:   "class",
			sourceJar:   source.Source,
			sourceClass: classFQN,
		})
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate class sources: %w", err)
	}
	return targets, nil
}

func loadManifestSources(ctx context.Context, manifestPath string) ([]manifestSource, error) {
	if manifestPath == "" {
		return []manifestSource{}, nil
	}
	manifest, err := openReadOnly(manifestPath, false)
	if err != nil {
		return nil, fmt.Errorf("open manifest database: %w", err)
	}
	defer manifest.Close()
	rows, err := manifest.QueryContext(
		ctx,
		`SELECT shard_id, jar_coordinate, jar_filename,
		        COALESCE(NULLIF(jar_coordinate, ''), jar_filename)
		 FROM shards`,
	)
	if err != nil {
		return nil, fmt.Errorf("query manifest sources: %w", err)
	}
	defer rows.Close()
	sources := []manifestSource{}
	for rows.Next() {
		var source manifestSource
		var coordinate sql.NullString
		if err := rows.Scan(
			&source.ShardID, &coordinate, &source.Filename, &source.Source,
		); err != nil {
			return nil, fmt.Errorf("scan manifest source: %w", err)
		}
		if coordinate.Valid {
			source.Coordinate = coordinate.String
		}
		sources = append(sources, source)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate manifest sources: %w", err)
	}
	return sources, nil
}

func artifactMatchesTarget(artifact depgraph.Artifact, target string) bool {
	if target == "" {
		return false
	}
	return artifactMatchesArg(artifact.Coordinate, target) ||
		artifact.Artifact == target ||
		artifact.Coordinate == target
}

func artifactMatchesArg(coordinate, arg string) bool {
	if coordinate == arg {
		return true
	}
	parts := strings.Split(coordinate, ":")
	if len(parts) >= 2 && parts[0]+":"+parts[1] == arg {
		return true
	}
	return false
}

func dedupeDependencyTargets(targets []dependencyTarget) []dependencyTarget {
	seen := map[string]bool{}
	result := []dependencyTarget{}
	for _, target := range targets {
		key := target.coordinate + "\x00" + target.matchedBy + "\x00" + target.sourceClass
		if seen[key] {
			continue
		}
		seen[key] = true
		result = append(result, target)
	}
	return result
}

func sortDependencyWhy(results []DependencyWhyResult) {
	sort.Slice(results, func(i, j int) bool {
		if results[i].Depth != results[j].Depth {
			return results[i].Depth < results[j].Depth
		}
		if results[i].Coordinate != results[j].Coordinate {
			return results[i].Coordinate < results[j].Coordinate
		}
		return results[i].MatchedBy < results[j].MatchedBy
	})
}
