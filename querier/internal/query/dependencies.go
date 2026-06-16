package query

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"

	"dev.research4jar/querier/internal/depgraph"
	"dev.research4jar/querier/internal/project"
)

type DependencyWhyResult struct {
	Coordinate       string   `json:"coordinate"`
	Artifact         string   `json:"artifact"`
	Version          string   `json:"version"`
	Scope            string   `json:"scope,omitempty"`
	Direct           bool     `json:"direct"`
	Depth            int      `json:"depth"`
	Path             []string `json:"path"`
	Parent           string   `json:"parent,omitempty"`
	DirectDependency string   `json:"direct_dependency,omitempty"`
	MatchedBy        string   `json:"matched_by"`
	SourceJar        string   `json:"source_jar,omitempty"`
	SourceClass      string   `json:"source_class,omitempty"`
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
	results := dependencyWhyResults(graph, targets)
	return DependencyWhyResponse{
		Query:    SymbolRequest{Command: "why-dependency", Arg: arg},
		Results:  results,
		Total:    len(results),
		Coverage: coverageFrom(pointer),
	}, nil
}

func dependencyWhyResults(
	graph depgraph.Graph,
	targets []dependencyTarget,
) []DependencyWhyResult {
	seen := map[string]DependencyWhyResult{}
	for _, target := range targets {
		for _, artifact := range graph.Artifacts {
			if !artifactMatchesTarget(artifact, target.coordinate) {
				continue
			}
			result := DependencyWhyResult{
				Coordinate:       artifact.Coordinate,
				Artifact:         artifact.Artifact,
				Version:          artifact.Version,
				Scope:            artifact.Scope,
				Direct:           artifact.Direct,
				Depth:            artifact.Depth,
				Path:             nonNil(artifact.Path),
				Parent:           artifact.Parent,
				DirectDependency: directDependencyFor(artifact),
				MatchedBy:        target.matchedBy,
				SourceJar:        target.sourceJar,
				SourceClass:      target.sourceClass,
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
	return results
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
		case source.Coordinate != "" &&
			(artifactMatchesArg(source.Coordinate, arg) || coordinateArtifactID(source.Coordinate) == arg):
			targets = append(targets, dependencyTarget{
				coordinate: source.Coordinate,
				matchedBy:  coordinateMatchReason(source.Coordinate, arg),
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
	rows, err := loadManifestRows(ctx, manifestPath)
	if err != nil {
		return nil, err
	}
	sources := make([]manifestSource, 0, len(rows))
	for _, row := range rows {
		sources = append(sources, manifestSource{
			ShardID:    row.shardID,
			Coordinate: row.coordinate,
			Filename:   row.filename,
			Source:     row.source,
		})
	}
	return sources, nil
}

func artifactMatchesTarget(artifact depgraph.Artifact, target string) bool {
	if target == "" {
		return false
	}
	return artifactMatchesArg(artifact.Coordinate, target) ||
		artifact.Artifact == target ||
		artifact.Name == target ||
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

func coordinateArtifactID(coordinate string) string {
	parts := strings.Split(coordinate, ":")
	if len(parts) < 2 {
		return ""
	}
	return parts[1]
}

func coordinateMatchReason(coordinate, arg string) string {
	if coordinateArtifactID(coordinate) == arg {
		return "artifact"
	}
	return "coordinate"
}

func directDependencyFor(artifact depgraph.Artifact) string {
	if len(artifact.Path) > 0 {
		return artifact.Path[0]
	}
	if artifact.Direct {
		return artifact.Coordinate
	}
	return ""
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
