package query

import (
	"bytes"
	"context"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"

	"dev.research4jar/querier/internal/depgraph"
	"dev.research4jar/querier/internal/project"
)

type DependencyOrigin struct {
	FQN         string `json:"fqn,omitempty"`
	Package     string `json:"package,omitempty"`
	Coordinate  string `json:"coordinate,omitempty"`
	JarFilename string `json:"jar_filename,omitempty"`
	SourceJar   string `json:"source_jar"`
	ShardID     string `json:"shard_id,omitempty"`
	MatchReason string `json:"match_reason"`
}

type SourceUsage struct {
	Path  string `json:"path"`
	Line  int    `json:"line"`
	Match string `json:"match"`
	Text  string `json:"text"`
}

type DependencyPreciseResponse struct {
	Query                       SymbolRequest         `json:"query"`
	InputKind                   string                `json:"input_kind"`
	Normalized                  string                `json:"normalized"`
	Origins                     []DependencyOrigin    `json:"origins"`
	Total                       int                   `json:"total"`
	Dependencies                []DependencyWhyResult `json:"dependencies"`
	DependenciesTotal           int                   `json:"dependencies_total"`
	DependencyGraphAvailable    bool                  `json:"dependency_graph_available"`
	DependencyGraphError        string                `json:"dependency_graph_error,omitempty"`
	SourceUsageTerms            []string              `json:"source_usage_terms,omitempty"`
	SourceUsages                []SourceUsage         `json:"source_usages,omitempty"`
	SourceUsagesHasMore         bool                  `json:"source_usages_has_more,omitempty"`
	SourceUsagesTruncatedReason string                `json:"source_usages_truncated_reason,omitempty"`
	SourceUsageError            string                `json:"source_usage_error,omitempty"`
	Coverage                    Coverage              `json:"coverage"`
}

type dependencyLookup struct {
	original         string
	command          string
	kind             string
	normalized       string
	classTerm        string
	packageTerm      string
	artifactTerm     string
	fallbackArtifact bool
	usageTerms       []string
}

// DependencyPrecise resolves a user-facing dependency question, such as
// "which jar owns this import?", "which dependency brought this jar in?", and
// "where does this project consume it?". It combines the jar fact index,
// dependency provenance, and a bounded source/build-file grep.
func DependencyPrecise(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	projectDir string,
	arg string,
	pageSize int,
	includeSourceUsages bool,
) (DependencyPreciseResponse, error) {
	lookup := parseDependencyLookup(arg)
	return dependencyPrecise(ctx, pointer, manifestPath, projectDir, lookup, pageSize, includeSourceUsages)
}

func ArtifactPrecise(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	projectDir string,
	arg string,
	pageSize int,
	includeSourceUsages bool,
) (DependencyPreciseResponse, error) {
	lookup := parseDependencyLookup(arg)
	lookup.command = "artifact"
	lookup.kind = "artifact"
	lookup.artifactTerm = lookup.normalized
	lookup.classTerm = ""
	lookup.packageTerm = ""
	lookup.fallbackArtifact = false
	return dependencyPrecise(ctx, pointer, manifestPath, projectDir, lookup, pageSize, includeSourceUsages)
}

func ClassPrecise(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	projectDir string,
	arg string,
	pageSize int,
	includeSourceUsages bool,
) (DependencyPreciseResponse, error) {
	lookup := parseDependencyLookup(arg)
	lookup.command = "class"
	lookup.kind = "class"
	if lookup.classTerm == "" {
		lookup.classTerm = lookup.normalized
	}
	lookup.artifactTerm = ""
	lookup.packageTerm = ""
	lookup.fallbackArtifact = false
	return dependencyPrecise(ctx, pointer, manifestPath, projectDir, lookup, pageSize, includeSourceUsages)
}

func dependencyPrecise(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	projectDir string,
	lookup dependencyLookup,
	pageSize int,
	includeSourceUsages bool,
) (DependencyPreciseResponse, error) {
	sources, err := loadManifestSources(ctx, manifestPath)
	if err != nil {
		return DependencyPreciseResponse{}, err
	}
	origins, err := dependencyOrigins(ctx, pointer, sources, lookup, pageSize)
	if err != nil {
		return DependencyPreciseResponse{}, err
	}

	response := DependencyPreciseResponse{
		Query:                    SymbolRequest{Command: lookup.command, Arg: lookup.original},
		InputKind:                lookup.kind,
		Normalized:               lookup.normalized,
		Origins:                  origins,
		Total:                    len(origins),
		DependencyGraphAvailable: true,
		Coverage:                 coverageFrom(pointer),
	}

	graph, err := depgraph.Load(projectDir)
	if err != nil {
		response.DependencyGraphAvailable = false
		response.DependencyGraphError = "no dependency provenance found; run research4jar index in a Maven project to create .research4jar/dependencies.json"
		if !strings.Contains(err.Error(), depgraph.ErrUnsupported.Error()) {
			response.DependencyGraphError = err.Error()
		}
	} else {
		targets := dependencyTargetsFromOrigins(origins)
		if len(targets) == 0 && (lookup.fallbackArtifact || lookup.artifactTerm != "") {
			targets, err = dependencyTargets(ctx, pointer, sources, lookup.normalized)
			if err != nil {
				return DependencyPreciseResponse{}, err
			}
		}
		response.Dependencies = dependencyWhyResults(graph, targets)
		response.DependenciesTotal = len(response.Dependencies)
	}

	usageQuery := sourceUsageQueryFor(lookup, origins)
	if includeSourceUsages && len(usageQuery.all) > 0 {
		usages, hasMore, truncatedReason, err := findSourceUsages(projectDir, usageQuery, pageSize)
		if err != nil {
			response.SourceUsageError = err.Error()
		} else {
			response.SourceUsageTerms = usageQuery.all
			response.SourceUsages = usages
			response.SourceUsagesHasMore = hasMore
			response.SourceUsagesTruncatedReason = truncatedReason
		}
	}
	return response, nil
}

func parseDependencyLookup(arg string) dependencyLookup {
	original := strings.TrimSpace(arg)
	normalized := strings.TrimSpace(strings.TrimSuffix(original, ";"))
	normalized = strings.TrimPrefix(normalized, "import ")
	normalized = strings.TrimSpace(normalized)

	staticImport := false
	if strings.HasPrefix(normalized, "static ") {
		staticImport = true
		normalized = strings.TrimSpace(strings.TrimPrefix(normalized, "static "))
	}
	if strings.HasPrefix(normalized, "import static ") {
		staticImport = true
		normalized = strings.TrimSpace(strings.TrimPrefix(normalized, "import static "))
	}

	lookup := dependencyLookup{
		original:         original,
		command:          "dep-precise",
		kind:             "class",
		normalized:       normalized,
		fallbackArtifact: true,
		usageTerms:       []string{original, normalized},
	}
	switch {
	case normalized == "":
		lookup.kind = "unknown"
	case strings.HasSuffix(normalized, ".*"):
		lookup.kind = "package_import"
		lookup.packageTerm = strings.TrimSuffix(normalized, ".*")
	case staticImport:
		lookup.kind = "static_import"
		lookup.classTerm = strings.TrimSuffix(beforeLastDot(normalized), ".*")
	case strings.Contains(normalized, "#"):
		lookup.kind = "method"
		lookup.classTerm = strings.SplitN(normalized, "#", 2)[0]
	case strings.Contains(normalized, ":") || strings.HasSuffix(normalized, ".jar"):
		lookup.kind = "artifact"
		lookup.artifactTerm = normalized
	case strings.Contains(normalized, "."):
		lookup.kind = "import"
		lookup.classTerm = normalized
	default:
		lookup.kind = "class"
		lookup.classTerm = normalized
	}
	if lookup.classTerm != "" {
		lookup.usageTerms = append(lookup.usageTerms, lookup.classTerm, "import "+lookup.classTerm)
		if _, simple := splitFQN(lookup.classTerm); simple != "" {
			lookup.usageTerms = append(lookup.usageTerms, simple)
		}
	}
	if lookup.packageTerm != "" {
		lookup.usageTerms = append(lookup.usageTerms, lookup.packageTerm, "import "+lookup.packageTerm+".*")
	}
	if lookup.artifactTerm != "" {
		lookup.usageTerms = append(lookup.usageTerms, lookup.artifactTerm)
		if artifactID := coordinateArtifactID(lookup.artifactTerm); artifactID != "" {
			lookup.usageTerms = append(lookup.usageTerms, artifactID)
		}
	}
	return lookup
}

func dependencyOrigins(
	ctx context.Context,
	pointer project.Pointer,
	sources []manifestSource,
	lookup dependencyLookup,
	limit int,
) ([]DependencyOrigin, error) {
	switch {
	case lookup.packageTerm != "":
		return packageOrigins(ctx, pointer, sources, lookup.packageTerm, limit)
	case lookup.artifactTerm != "":
		return artifactOrigins(sources, lookup.artifactTerm), nil
	case lookup.classTerm != "":
		origins, err := classOrigins(ctx, pointer, sources, lookup.classTerm, limit)
		if err != nil || len(origins) > 0 ||
			strings.Contains(lookup.classTerm, ".") || !lookup.fallbackArtifact {
			return origins, err
		}
		return artifactOrigins(sources, lookup.classTerm), nil
	default:
		return []DependencyOrigin{}, nil
	}
}

func classOrigins(
	ctx context.Context,
	pointer project.Pointer,
	sources []manifestSource,
	term string,
	limit int,
) ([]DependencyOrigin, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return nil, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()
	_, simple := splitFQN(term)
	if simple == "" {
		simple = term
	}
	rows, err := session.QueryContext(
		ctx,
		`SELECT fqn, source_shard_id,
		        CASE
		          WHEN fqn = ? THEN 'exact_fqn'
		          WHEN simple_name = ? THEN 'simple_name'
		        END AS match_reason
		 FROM classes
		 WHERE fqn = ? OR simple_name = ?
		 ORDER BY
		   CASE
		     WHEN fqn = ? THEN 0
		     WHEN simple_name = ? THEN 1
		   END,
		   fqn, source_shard_id
		 LIMIT ?`,
		term, simple,
		term, simple,
		term, simple,
		limitOrDefault(limit),
	)
	if err != nil {
		return nil, fmt.Errorf("query class origin: %w", err)
	}
	defer rows.Close()
	byShard := manifestByShard(sources)
	origins := []DependencyOrigin{}
	for rows.Next() {
		var fqn, shardID, matchReason string
		if err := rows.Scan(&fqn, &shardID, &matchReason); err != nil {
			return nil, fmt.Errorf("scan class origin: %w", err)
		}
		source := byShard[shardID]
		origin := originFromSource(source, matchReason)
		origin.FQN = fqn
		origin.ShardID = shardID
		origins = append(origins, origin)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate class origins: %w", err)
	}
	return dedupeOrigins(origins), nil
}

func packageOrigins(
	ctx context.Context,
	pointer project.Pointer,
	sources []manifestSource,
	packageName string,
	limit int,
) ([]DependencyOrigin, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return nil, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()
	rows, err := session.QueryContext(
		ctx,
		`SELECT package_name, source_shard_id
		 FROM classes
		 WHERE package_name = ?
		 GROUP BY package_name, source_shard_id
		 ORDER BY package_name, source_shard_id
		 LIMIT ?`,
		packageName,
		limitOrDefault(limit),
	)
	if err != nil {
		return nil, fmt.Errorf("query package origin: %w", err)
	}
	defer rows.Close()
	byShard := manifestByShard(sources)
	origins := []DependencyOrigin{}
	for rows.Next() {
		var matchedPackage, shardID string
		if err := rows.Scan(&matchedPackage, &shardID); err != nil {
			return nil, fmt.Errorf("scan package origin: %w", err)
		}
		source := byShard[shardID]
		origin := originFromSource(source, "package_import")
		origin.Package = matchedPackage
		origin.ShardID = shardID
		origins = append(origins, origin)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate package origins: %w", err)
	}
	return dedupeOrigins(origins), nil
}

func artifactOrigins(sources []manifestSource, term string) []DependencyOrigin {
	origins := []DependencyOrigin{}
	for _, source := range sources {
		if !sourceMatchesArtifact(source, term) {
			continue
		}
		origins = append(origins, originFromSource(source, artifactOriginReason(source, term)))
	}
	return dedupeOrigins(origins)
}

func sourceMatchesArtifact(source manifestSource, term string) bool {
	if source.Coordinate != "" &&
		(artifactMatchesArg(source.Coordinate, term) || coordinateArtifactID(source.Coordinate) == term) {
		return true
	}
	filename := filepath.Base(source.Filename)
	stem := strings.TrimSuffix(filename, ".jar")
	return source.Filename == term ||
		filename == term ||
		stem == term ||
		(term != "" && strings.Contains(stem, term))
}

func artifactOriginReason(source manifestSource, term string) string {
	switch {
	case source.Coordinate != "" && artifactMatchesArg(source.Coordinate, term):
		return "coordinate"
	case source.Coordinate != "" && coordinateArtifactID(source.Coordinate) == term:
		return "artifact"
	default:
		return "jar_filename"
	}
}

func originFromSource(source manifestSource, matchReason string) DependencyOrigin {
	origin := DependencyOrigin{
		Coordinate:  source.Coordinate,
		JarFilename: source.Filename,
		SourceJar:   source.Source,
		ShardID:     source.ShardID,
		MatchReason: matchReason,
	}
	if origin.SourceJar == "" {
		origin.SourceJar = source.ShardID
	}
	return origin
}

func dependencyTargetsFromOrigins(origins []DependencyOrigin) []dependencyTarget {
	targets := []dependencyTarget{}
	for _, origin := range origins {
		if origin.Coordinate == "" {
			continue
		}
		targets = append(targets, dependencyTarget{
			coordinate:  origin.Coordinate,
			matchedBy:   origin.MatchReason,
			sourceJar:   origin.SourceJar,
			sourceClass: origin.FQN,
		})
	}
	return dedupeDependencyTargets(targets)
}

type sourceUsageQuery struct {
	highSignal []string
	broad      []string
	all        []string
}

func sourceUsageQueryFor(lookup dependencyLookup, origins []DependencyOrigin) sourceUsageQuery {
	highSignal := highSignalSourceUsageTerms(lookup, origins)
	broad := broadSourceUsageTerms(lookup, origins)
	return sourceUsageQuery{
		highSignal: highSignal,
		broad:      broad,
		all:        dedupeNonEmptyStrings(append(append([]string{}, highSignal...), broad...)),
	}
}

func highSignalSourceUsageTerms(lookup dependencyLookup, origins []DependencyOrigin) []string {
	terms := []string{}
	if strings.HasPrefix(lookup.original, "import ") {
		terms = append(terms, lookup.original)
	}
	if lookup.classTerm != "" {
		terms = append(terms, "import "+lookup.classTerm, "import static "+lookup.classTerm+".")
	}
	if lookup.packageTerm != "" {
		terms = append(terms, "import "+lookup.packageTerm+".*")
	}
	if lookup.artifactTerm != "" {
		terms = append(terms, lookup.artifactTerm)
		if artifactID := coordinateArtifactID(lookup.artifactTerm); artifactID != "" {
			terms = append(terms, artifactID)
		}
	}
	for _, origin := range origins {
		if origin.FQN != "" {
			terms = append(terms, "import "+origin.FQN, "import static "+origin.FQN+".")
		}
		terms = append(terms, origin.Coordinate, filepath.Base(origin.JarFilename))
		if artifactID := coordinateArtifactID(origin.Coordinate); artifactID != "" {
			terms = append(terms, artifactID)
		}
	}
	return dedupeNonEmptyStrings(terms)
}

func broadSourceUsageTerms(lookup dependencyLookup, origins []DependencyOrigin) []string {
	terms := append([]string{}, lookup.usageTerms...)
	for _, origin := range origins {
		terms = append(terms, origin.FQN, origin.Coordinate, filepath.Base(origin.JarFilename))
		if origin.FQN != "" {
			_, simple := splitFQN(origin.FQN)
			terms = append(terms, simple, "import "+origin.FQN)
		}
		if artifactID := coordinateArtifactID(origin.Coordinate); artifactID != "" {
			terms = append(terms, artifactID)
		}
	}
	return dedupeNonEmptyStrings(terms)
}

const (
	maxSourceUsageFileBytes = 2 * 1024 * 1024
	sourceUsageFileBudget   = 2000
	sourceUsageTimeBudget   = 1500 * time.Millisecond
)

func findSourceUsages(
	projectDir string,
	query sourceUsageQuery,
	limit int,
) ([]SourceUsage, bool, string, error) {
	if projectDir == "" {
		return nil, false, "", nil
	}
	limit = limitOrDefault(limit)
	state := sourceUsageScanState{
		projectDir: projectDir,
		limit:      limit,
		deadline:   time.Now().Add(sourceUsageTimeBudget),
		seen:       map[string]bool{},
	}
	for _, phase := range []sourceUsagePhase{
		{name: "high_signal", terms: query.highSignal},
		{name: "broad", terms: query.broad},
	} {
		if len(state.usages) > limit || state.truncatedReason != "" {
			break
		}
		if len(phase.terms) == 0 {
			continue
		}
		if err := scanSourceUsagePhase(&state, phase); err != nil {
			return nil, false, "", err
		}
	}
	hasMore := len(state.usages) > limit
	if hasMore {
		state.usages = state.usages[:limit]
	}
	return state.usages, hasMore, state.truncatedReason, nil
}

type sourceUsagePhase struct {
	name  string
	terms []string
}

type sourceUsageScanState struct {
	projectDir      string
	limit           int
	filesVisited    int
	deadline        time.Time
	seen            map[string]bool
	usages          []SourceUsage
	truncatedReason string
}

func scanSourceUsagePhase(state *sourceUsageScanState, phase sourceUsagePhase) error {
	terms := dedupeNonEmptyStrings(phase.terms)
	if len(terms) == 0 {
		return nil
	}
	termBytes := sourceUsageTermBytes(terms)
	err := filepath.WalkDir(state.projectDir, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if state.truncatedReason != "" || len(state.usages) > state.limit {
			return fs.SkipAll
		}
		if time.Now().After(state.deadline) {
			state.truncatedReason = "time_budget"
			return fs.SkipAll
		}
		if entry.IsDir() {
			if shouldSkipSourceUsageDir(entry.Name()) {
				return filepath.SkipDir
			}
			return nil
		}
		if !sourceUsageFile(path) {
			return nil
		}
		state.filesVisited++
		if state.filesVisited > sourceUsageFileBudget {
			state.truncatedReason = "file_budget"
			return fs.SkipAll
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if info.Size() > maxSourceUsageFileBytes {
			return nil
		}
		fileUsages, err := scanSourceUsageFile(
			state.projectDir,
			path,
			terms,
			termBytes,
			state.limit+1-len(state.usages),
			state.seen,
		)
		if err != nil {
			return err
		}
		state.usages = append(state.usages, fileUsages...)
		if len(state.usages) > state.limit {
			return fs.SkipAll
		}
		return nil
	})
	return err
}

func sourceUsageTermBytes(terms []string) [][]byte {
	result := make([][]byte, 0, len(terms))
	for _, term := range terms {
		if term != "" {
			result = append(result, []byte(term))
		}
	}
	return result
}

func scanSourceUsageFile(
	projectDir string,
	path string,
	terms []string,
	termBytes [][]byte,
	limit int,
	seen map[string]bool,
) ([]SourceUsage, error) {
	if limit <= 0 {
		return nil, nil
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if bytes.IndexByte(content, 0) >= 0 || !contentContainsAny(content, termBytes) {
		return nil, nil
	}
	lines := strings.Split(string(content), "\n")
	relative, err := filepath.Rel(projectDir, path)
	if err != nil {
		relative = path
	}
	relative = filepath.ToSlash(relative)
	usages := []SourceUsage{}
	for index, line := range lines {
		match := firstLineMatch(line, terms)
		if match == "" {
			continue
		}
		key := relative + "\x00" + fmt.Sprint(index+1)
		if seen[key] {
			continue
		}
		seen[key] = true
		usages = append(usages, SourceUsage{
			Path:  relative,
			Line:  index + 1,
			Match: match,
			Text:  trimUsageLine(line),
		})
		if len(usages) >= limit {
			break
		}
	}
	return usages, nil
}

func contentContainsAny(content []byte, terms [][]byte) bool {
	for _, term := range terms {
		if len(term) > 0 && bytes.Contains(content, term) {
			return true
		}
	}
	return false
}

func firstLineMatch(line string, terms []string) string {
	for _, term := range terms {
		if term != "" && strings.Contains(line, term) {
			return term
		}
	}
	return ""
}

func trimUsageLine(line string) string {
	text := strings.TrimSpace(line)
	const maxLine = 240
	if len(text) <= maxLine {
		return text
	}
	return text[:maxLine]
}

func shouldSkipSourceUsageDir(name string) bool {
	switch name {
	case ".git", ".gradle", ".idea", ".mvn", ".research4jar", ".settings", ".vscode",
		"build", "coverage", "dist", "generated", "generated-sources",
		"generated-test-sources", "node_modules", "out", "target":
		return true
	default:
		return false
	}
}

func sourceUsageFile(path string) bool {
	switch filepath.Ext(path) {
	case ".java", ".kt", ".kts", ".groovy", ".scala", ".xml", ".gradle", ".properties", ".yml", ".yaml":
		return true
	default:
		return false
	}
}

func manifestByShard(sources []manifestSource) map[string]manifestSource {
	byShard := map[string]manifestSource{}
	for _, source := range sources {
		byShard[source.ShardID] = source
	}
	return byShard
}

func dedupeOrigins(origins []DependencyOrigin) []DependencyOrigin {
	seen := map[string]bool{}
	result := []DependencyOrigin{}
	for _, origin := range origins {
		key := origin.FQN + "\x00" + origin.Package + "\x00" + origin.Coordinate +
			"\x00" + origin.JarFilename + "\x00" + origin.ShardID
		if seen[key] {
			continue
		}
		seen[key] = true
		result = append(result, origin)
	}
	return result
}

func dedupeNonEmptyStrings(values []string) []string {
	seen := map[string]bool{}
	result := []string{}
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" || seen[value] {
			continue
		}
		seen[value] = true
		result = append(result, value)
	}
	return result
}

func beforeLastDot(value string) string {
	index := strings.LastIndexByte(value, '.')
	if index < 0 {
		return value
	}
	return value[:index]
}

func limitOrDefault(limit int) int {
	if limit < 1 {
		return 20
	}
	return limit
}
