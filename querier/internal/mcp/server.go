// Package mcp implements a minimal Model Context Protocol server over stdio
// (newline-delimited JSON-RPC 2.0), exposing every research4jar command as a
// tool so MCP hosts — Cursor, Claude Code, Windsurf, etc. — can index and
// query Spring dependency jars directly.
package mcp

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"dev.research4jar/querier/internal/classpath"
	"dev.research4jar/querier/internal/depgraph"
	"dev.research4jar/querier/internal/envcheck"
	"dev.research4jar/querier/internal/indexer"
	"dev.research4jar/querier/internal/jarsource"
	"dev.research4jar/querier/internal/manifest"
	"dev.research4jar/querier/internal/paths"
	"dev.research4jar/querier/internal/pointer"
	"dev.research4jar/querier/internal/project"
	"dev.research4jar/querier/internal/query"
	"dev.research4jar/querier/internal/registry"
	"dev.research4jar/querier/internal/session"
	"dev.research4jar/querier/internal/versions"
)

const (
	protocolVersion = "2024-11-05"
	serverName      = "research4jar"
	serverVersion   = "0.2.0"
)

type request struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}

type response struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  any             `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type toolDefinition struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	InputSchema map[string]any `json:"inputSchema"`
}

type toolArguments struct {
	ProjectDir   string `json:"project_dir"`
	Home         string `json:"home"`
	Jars         string `json:"jars"`
	Indexer      string `json:"indexer"`
	Registry     string `json:"registry"`
	RegistryKey  string `json:"registry_pubkey"`
	Prefix       string `json:"prefix"`
	FQN          string `json:"fqn"`
	Text         string `json:"text"`
	Arg          string `json:"arg"`
	Direct       bool   `json:"direct"`
	NoSourceGrep bool   `json:"no_source_grep"`
	Page         int    `json:"page"`
	PageSize     int    `json:"page_size"`
	SourceBuild  bool   `json:"source_build"`
}

// Serve runs the MCP loop until stdin closes.
func Serve(stdin io.Reader, stdout io.Writer) error {
	reader := bufio.NewReaderSize(stdin, 1024*1024)
	encoder := json.NewEncoder(stdout)
	for {
		line, err := reader.ReadBytes('\n')
		if len(bytes.TrimSpace(line)) > 0 {
			handleLine(bytes.TrimSpace(line), encoder)
		}
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
	}
}

func handleLine(line []byte, encoder *json.Encoder) {
	var incoming request
	if err := json.Unmarshal(line, &incoming); err != nil {
		_ = encoder.Encode(response{
			JSONRPC: "2.0",
			Error:   &rpcError{Code: -32700, Message: "parse error: " + err.Error()},
		})
		return
	}
	if incoming.ID == nil {
		// Notification (e.g. notifications/initialized): no response.
		return
	}
	reply := response{JSONRPC: "2.0", ID: incoming.ID}
	switch incoming.Method {
	case "initialize":
		reply.Result = map[string]any{
			"protocolVersion": negotiatedVersion(incoming.Params),
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo": map[string]any{
				"name":    serverName,
				"version": serverVersion,
			},
		}
	case "ping":
		reply.Result = map[string]any{}
	case "tools/list":
		reply.Result = map[string]any{"tools": toolCatalog()}
	case "tools/call":
		reply.Result = callTool(incoming.Params)
	default:
		reply.Error = &rpcError{Code: -32601, Message: "method not found: " + incoming.Method}
	}
	_ = encoder.Encode(reply)
}

func negotiatedVersion(params json.RawMessage) string {
	var requested struct {
		ProtocolVersion string `json:"protocolVersion"`
	}
	if err := json.Unmarshal(params, &requested); err == nil &&
		requested.ProtocolVersion != "" {
		return requested.ProtocolVersion
	}
	return protocolVersion
}

func callTool(params json.RawMessage) map[string]any {
	var call struct {
		Name      string        `json:"name"`
		Arguments toolArguments `json:"arguments"`
	}
	if err := json.Unmarshal(params, &call); err != nil {
		return toolError("invalid tool call arguments: " + err.Error())
	}
	arguments := call.Arguments
	if arguments.Page < 1 {
		arguments.Page = 1
	}
	if arguments.PageSize < 1 {
		arguments.PageSize = 20
	}

	result, err := dispatchTool(call.Name, arguments)
	if errors.Is(err, project.ErrNotFound) {
		return toolError(
			"No Research4Jar index found for this project. " +
				"Run the index_project tool first (it auto-resolves the classpath " +
				"via Maven/Gradle), or pass project_dir explicitly.",
		)
	}
	if err != nil {
		return toolError(err.Error())
	}
	encoded, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		return toolError("encode result: " + err.Error())
	}
	return map[string]any{
		"content": []map[string]any{{"type": "text", "text": string(encoded)}},
	}
}

func dispatchTool(name string, arguments toolArguments) (any, error) {
	ctx := context.Background()
	if name == "check_environment" {
		return envcheck.Run(envcheck.Options{
			ProjectDir:  arguments.ProjectDir,
			SourceBuild: arguments.SourceBuild,
		}), nil
	}
	if name == "index_project" {
		return runIndex(arguments)
	}
	if name == "project_status" {
		return query.ProjectStatus(arguments.ProjectDir, arguments.Home)
	}
	pointer, manifestPath, err := query.ResolveProject(arguments.ProjectDir, arguments.Home)
	if err != nil {
		return nil, err
	}
	switch name {
	case "find_config_properties":
		if arguments.Prefix == "" {
			return nil, errors.New("prefix is required")
		}
		return query.FindConfigProperties(
			ctx, pointer, manifestPath, arguments.Prefix, arguments.Page, arguments.PageSize,
		)
	case "find_implementations":
		if arguments.FQN == "" {
			return nil, errors.New("fqn is required")
		}
		return query.FindImplementations(
			ctx, pointer, manifestPath, arguments.FQN, arguments.Direct,
			arguments.Page, arguments.PageSize,
		)
	case "find_by_annotation":
		if arguments.FQN == "" {
			return nil, errors.New("fqn is required")
		}
		return query.FindByAnnotation(
			ctx, pointer, manifestPath, arguments.FQN, arguments.Direct,
			arguments.Page, arguments.PageSize,
		)
	case "get_class":
		if arguments.FQN == "" {
			return nil, errors.New("fqn is required")
		}
		return query.GetClass(ctx, pointer, manifestPath, arguments.FQN)
	case "get_bean_definitions":
		if arguments.FQN == "" {
			return nil, errors.New("fqn is required")
		}
		return query.GetBeanDefinitions(
			ctx, pointer, manifestPath, arguments.FQN, arguments.Page, arguments.PageSize,
		)
	case "explain_conditional":
		if arguments.FQN == "" {
			return nil, errors.New("fqn is required")
		}
		return query.ExplainConditional(ctx, pointer, manifestPath, arguments.FQN)
	case "find_string":
		if arguments.Text == "" {
			return nil, errors.New("text is required")
		}
		return query.FindString(
			ctx, pointer, manifestPath, arguments.Text, arguments.Page, arguments.PageSize,
		)
	case "list_extension_points":
		return query.ListExtensionPoints(
			ctx, pointer, manifestPath, arguments.Arg, arguments.Page, arguments.PageSize,
		)
	case "find_class":
		if arguments.Text == "" && arguments.FQN == "" && arguments.Arg == "" {
			return nil, errors.New("text, fqn, or arg is required")
		}
		return query.FindClass(
			ctx, pointer, manifestPath, firstNonEmpty(arguments.Text, arguments.FQN, arguments.Arg),
			arguments.Page, arguments.PageSize,
		)
	case "find_method":
		if arguments.Text == "" && arguments.Arg == "" {
			return nil, errors.New("text or arg is required")
		}
		return query.FindMethod(
			ctx, pointer, manifestPath, firstNonEmpty(arguments.Text, arguments.Arg),
			arguments.Page, arguments.PageSize,
		)
	case "list_packages":
		return query.ListPackages(
			ctx, pointer, manifestPath, arguments.Arg, arguments.Page, arguments.PageSize,
		)
	case "search_symbols":
		if arguments.Text == "" {
			return nil, errors.New("text is required")
		}
		return query.SearchSymbol(
			ctx, pointer, manifestPath, arguments.Text, arguments.Page, arguments.PageSize,
		)
	case "open_symbol":
		if arguments.FQN == "" && arguments.Arg == "" {
			return nil, errors.New("fqn or arg is required")
		}
		return query.OpenSymbol(ctx, pointer, manifestPath, firstNonEmpty(arguments.FQN, arguments.Arg))
	case "why_dependency":
		if arguments.FQN == "" && arguments.Arg == "" {
			return nil, errors.New("fqn or arg is required")
		}
		projectRoot, err := project.Root(arguments.ProjectDir)
		if err != nil {
			return nil, err
		}
		return query.WhyDependency(
			ctx, pointer, manifestPath, projectRoot, firstNonEmpty(arguments.FQN, arguments.Arg),
		)
	case "dependency_precise":
		if arguments.Text == "" && arguments.FQN == "" && arguments.Arg == "" {
			return nil, errors.New("text, fqn, or arg is required")
		}
		projectRoot, err := project.Root(arguments.ProjectDir)
		if err != nil {
			return nil, err
		}
		return query.DependencyPrecise(
			ctx,
			pointer,
			manifestPath,
			projectRoot,
			firstNonEmpty(arguments.Text, arguments.FQN, arguments.Arg),
			arguments.PageSize,
			!arguments.NoSourceGrep,
		)
	case "class_origin":
		if arguments.Text == "" && arguments.FQN == "" && arguments.Arg == "" {
			return nil, errors.New("text, fqn, or arg is required")
		}
		projectRoot, err := project.Root(arguments.ProjectDir)
		if err != nil {
			return nil, err
		}
		return query.ClassPrecise(
			ctx,
			pointer,
			manifestPath,
			projectRoot,
			firstNonEmpty(arguments.Text, arguments.FQN, arguments.Arg),
			arguments.PageSize,
			!arguments.NoSourceGrep,
		)
	case "find_artifact":
		if arguments.Text == "" && arguments.Arg == "" {
			return nil, errors.New("text or arg is required")
		}
		projectRoot, err := project.Root(arguments.ProjectDir)
		if err != nil {
			return nil, err
		}
		return query.ArtifactPrecise(
			ctx,
			pointer,
			manifestPath,
			projectRoot,
			firstNonEmpty(arguments.Text, arguments.Arg),
			arguments.PageSize,
			!arguments.NoSourceGrep,
		)
	default:
		return nil, fmt.Errorf("unknown tool: %s", name)
	}
}

func runIndex(arguments toolArguments) (any, error) {
	projectDir := arguments.ProjectDir
	var err error
	if projectDir == "" {
		if projectDir, err = os.Getwd(); err != nil {
			return nil, err
		}
	}
	jars := arguments.Jars
	result := map[string]any{
		"status":      "indexed",
		"project_dir": projectDir,
		"index_mode":  "jvm",
	}
	registryURL := firstNonEmpty(arguments.Registry, os.Getenv("RESEARCH4JAR_REGISTRY"))
	registryPubkey := firstNonEmpty(arguments.RegistryKey, os.Getenv("RESEARCH4JAR_REGISTRY_PUBKEY"))
	if registryURL != "" {
		resolvedJars, stats, dataPaths, warnings, err := prefetchFromRegistry(
			registryURL, registryPubkey, jars, projectDir, arguments.Home,
		)
		if err != nil {
			return nil, err
		}
		jars = resolvedJars
		result["registry_prefetch"] = registryPrefetchSummary(stats, warnings)
		if stats.Complete {
			if err := finishIndexFromRegistry(stats, dataPaths, projectDir); err != nil {
				return nil, err
			}
			result["index_mode"] = "registry"
			result["dependency_provenance"] = captureDependencyProvenance(projectDir)
			result["note"] = "Session built from cached/registry shards; query tools are ready without launching the JVM indexer."
			return result, nil
		}
	}

	indexerBin, err := indexer.Locate(arguments.Indexer)
	if err != nil {
		return nil, fmt.Errorf("%w. Call check_environment for installation guidance", err)
	}
	if err := indexer.Run(indexerBin, jars, projectDir, arguments.Home); err != nil {
		return nil, fmt.Errorf("indexing failed: %w. Call check_environment for installation guidance", err)
	}
	result["dependency_provenance"] = captureDependencyProvenance(projectDir)
	result["note"] = "Project pointer written to .research4jar/project.json; query tools are ready."
	return result, nil
}

func prefetchFromRegistry(
	registryURL, registryPubkey, jars, projectDir, home string,
) (string, registry.PrefetchStats, paths.DataPaths, string, error) {
	client, err := registry.NewClient(registryURL, registryPubkey)
	if err != nil {
		return "", registry.PrefetchStats{}, paths.DataPaths{}, "", err
	}
	var jarList []string
	if jars == "" {
		jarList, err = classpath.Discover(projectDir)
		if err != nil {
			return "", registry.PrefetchStats{}, paths.DataPaths{}, "", err
		}
		if len(jarList) == 0 {
			return "", registry.PrefetchStats{}, paths.DataPaths{}, "",
				errors.New("build tool resolved an empty runtime classpath")
		}
		jars = strings.Join(jarList, ",")
	} else {
		jarList, err = jarsource.Resolve(jars)
		if err != nil {
			return "", registry.PrefetchStats{}, paths.DataPaths{}, "", err
		}
	}
	dataPaths, err := paths.Resolve(home)
	if err != nil {
		return "", registry.PrefetchStats{}, paths.DataPaths{}, "", err
	}
	manifestDB, err := manifest.Open(dataPaths.Manifest)
	if err != nil {
		return "", registry.PrefetchStats{}, paths.DataPaths{}, "", err
	}
	defer manifestDB.Close()
	var warnings strings.Builder
	stats := registry.Prefetch(
		context.Background(), client, manifestDB, dataPaths.Shards,
		versions.Extractor, jarList, &warnings,
	)
	return jars, stats, dataPaths, strings.TrimSpace(warnings.String()), nil
}

func finishIndexFromRegistry(
	stats registry.PrefetchStats, dataPaths paths.DataPaths, projectDir string,
) error {
	shards := make([]session.Shard, len(stats.Shards))
	shardIDs := make([]string, len(stats.Shards))
	for index, shard := range stats.Shards {
		shards[index] = session.Shard{ShardID: shard.ShardID, Path: shard.Path}
		shardIDs[index] = shard.ShardID
	}
	fingerprint := session.Fingerprint(shardIDs)
	sessionPath := filepath.Join(dataPaths.Sessions, fingerprint+".db")
	if err := session.BuildIfAbsent(sessionPath, shards); err != nil {
		return fmt.Errorf("build session: %w", err)
	}
	coverage := project.Coverage{
		JarsTotal:   len(shards),
		JarsIndexed: len(shards),
		JarsMissing: []string{},
	}
	if err := pointer.Write(projectDir, fingerprint, sessionPath, coverage); err != nil {
		return fmt.Errorf("write project pointer: %w", err)
	}
	if err := pointer.EnsureClaudeInstructions(projectDir); err != nil {
		return fmt.Errorf("write CLAUDE.md guidance: %w", err)
	}
	return nil
}

func registryPrefetchSummary(stats registry.PrefetchStats, warnings string) map[string]any {
	summary := map[string]any{
		"jars_total":    stats.JarsTotal,
		"jars_unique":   stats.JarsUnique,
		"cache_hits":    stats.CacheHits,
		"downloaded":    stats.Downloaded,
		"misses":        stats.Misses,
		"failures":      stats.Failures,
		"hash_failures": stats.HashFailures,
		"complete":      stats.Complete,
	}
	if warnings != "" {
		summary["warnings"] = warnings
	}
	return summary
}

func captureDependencyProvenance(projectDir string) string {
	if graph, err := depgraph.Capture(projectDir); err == nil {
		if err := depgraph.Write(projectDir, graph); err != nil {
			return "capture succeeded but write failed: " + err.Error()
		}
		return "captured"
	} else if errors.Is(err, depgraph.ErrUnsupported) {
		return "unsupported build tool"
	} else {
		return "capture failed: " + err.Error()
	}
}

func toolError(message string) map[string]any {
	return map[string]any{
		"content": []map[string]any{{"type": "text", "text": message}},
		"isError": true,
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func toolCatalog() []toolDefinition {
	projectDir := map[string]any{
		"type": "string",
		"description": "Spring project root (directory containing .research4jar). " +
			"Defaults to searching upward from the server's working directory.",
	}
	paging := map[string]any{
		"page":      map[string]any{"type": "integer", "description": "Result page, 1-based (default 1)."},
		"page_size": map[string]any{"type": "integer", "description": "Results per page (default 20)."},
	}
	withPaging := func(props map[string]any) map[string]any {
		for key, value := range paging {
			props[key] = value
		}
		props["project_dir"] = projectDir
		return props
	}
	schema := func(props map[string]any, required ...string) map[string]any {
		s := map[string]any{"type": "object", "properties": props}
		if len(required) > 0 {
			s["required"] = required
		}
		return s
	}
	return []toolDefinition{
		{
			Name: "check_environment",
			Description: "List the local tools Research4Jar needs, report missing runtime/build " +
				"requirements, and include user-facing and agent-executable install guidance.",
			InputSchema: schema(map[string]any{
				"project_dir": projectDir,
				"source_build": map[string]any{
					"type":        "boolean",
					"description": "Also check tools needed to build Research4Jar from source.",
				},
			}),
		},
		{
			Name: "index_project",
			Description: "Index a Spring Boot project's dependency jars into the local fact " +
				"database. Resolves the runtime classpath automatically via Maven/Gradle " +
				"when jars is omitted. Run once per project (re-runs are incremental and fast).",
			InputSchema: schema(map[string]any{
				"project_dir": projectDir,
				"jars": map[string]any{
					"type":        "string",
					"description": "Optional jar directory, glob, or comma-separated jar list. Omit to auto-resolve via the build tool.",
				},
				"registry": map[string]any{
					"type":        "string",
					"description": "Optional shard registry base URL. When fully covered, indexing finishes without launching the JVM indexer.",
				},
				"registry_pubkey": map[string]any{
					"type":        "string",
					"description": "Optional hex ed25519 public key; downloaded registry shards must have valid signatures.",
				},
			}),
		},
		{
			Name: "project_status",
			Description: "Report whether the current project has a Research4Jar index and show " +
				"project pointer, session database, manifest, coverage, and dependency provenance state. " +
				"Use before dependency/class queries to decide whether index_project is needed.",
			InputSchema: schema(map[string]any{
				"project_dir": projectDir,
				"home": map[string]any{
					"type":        "string",
					"description": "Optional Research4Jar data home; defaults to the standard user data directory.",
				},
			}),
		},
		{
			Name: "find_config_properties",
			Description: "Find Spring configuration properties by prefix (e.g. spring.datasource). " +
				"Returns name, type, default value, description, and the source jar.",
			InputSchema: schema(withPaging(map[string]any{
				"prefix": map[string]any{"type": "string", "description": "Configuration property prefix or exact name."},
			}), "prefix"),
		},
		{
			Name: "find_implementations",
			Description: "Find classes implementing an interface or extending a class across all " +
				"indexed jars. Transitive by default (walks subinterfaces and superclass chains); " +
				"set direct=true for declared-only matches.",
			InputSchema: schema(withPaging(map[string]any{
				"fqn":    map[string]any{"type": "string", "description": "Interface or class fully-qualified name."},
				"direct": map[string]any{"type": "boolean", "description": "Only directly declared implements/extends."},
			}), "fqn"),
		},
		{
			Name: "find_by_annotation",
			Description: "Find classes annotated with an annotation across all indexed jars. " +
				"Expands meta-annotations by default (querying @Component also returns @Service " +
				"classes); set direct=true for directly-present annotations only.",
			InputSchema: schema(withPaging(map[string]any{
				"fqn":    map[string]any{"type": "string", "description": "Annotation fully-qualified name."},
				"direct": map[string]any{"type": "boolean", "description": "Only directly present annotations."},
			}), "fqn"),
		},
		{
			Name: "get_class",
			Description: "Get everything indexed about a class: kind, superclass, interfaces, " +
				"annotations, methods, @Bean definitions, and Spring conditions.",
			InputSchema: schema(map[string]any{
				"fqn":         map[string]any{"type": "string", "description": "Class fully-qualified name."},
				"project_dir": projectDir,
			}, "fqn"),
		},
		{
			Name: "get_bean_definitions",
			Description: "Find @Bean definitions by bean type or by declaring configuration class, " +
				"including each bean method's @ConditionalOn* conditions.",
			InputSchema: schema(withPaging(map[string]any{
				"fqn": map[string]any{"type": "string", "description": "Bean type FQN or configuration class FQN."},
			}), "fqn"),
		},
		{
			Name: "explain_conditional",
			Description: "Explain when an auto-configuration class activates: its class-level " +
				"@ConditionalOn* conditions plus every @Bean method's conditions.",
			InputSchema: schema(map[string]any{
				"fqn":         map[string]any{"type": "string", "description": "Configuration class fully-qualified name."},
				"project_dir": projectDir,
			}, "fqn"),
		},
		{
			Name: "find_string",
			Description: "Search string constants extracted from bytecode by substring — find which " +
				"jar/class owns a property key, HTTP header, or log message.",
			InputSchema: schema(withPaging(map[string]any{
				"text": map[string]any{"type": "string", "description": "Substring to search for."},
			}), "text"),
		},
		{
			Name: "list_extension_points",
			Description: "List SPI extension points (spring.factories keys, auto-configuration " +
				"imports, java.util.ServiceLoader services) with registration counts; pass arg " +
				"to list the registrations for one key or mechanism.",
			InputSchema: schema(withPaging(map[string]any{
				"arg": map[string]any{"type": "string", "description": "Optional SPI key or mechanism (autoconfig.imports | spring.factories | services)."},
			})),
		},
		{
			Name: "find_class",
			Description: "Find Java classes by simple name, fully-qualified name, package prefix, " +
				"or substring across indexed dependency jars.",
			InputSchema: schema(withPaging(map[string]any{
				"text": map[string]any{"type": "string", "description": "Class simple name, FQN, package prefix, or substring."},
			}), "text"),
		},
		{
			Name:        "find_method",
			Description: "Find Java methods by method name, substring, or Class#method shape.",
			InputSchema: schema(withPaging(map[string]any{
				"text": map[string]any{"type": "string", "description": "Method name, substring, or Class#method."},
			}), "text"),
		},
		{
			Name:        "list_packages",
			Description: "List Java packages grouped by source jar. Pass arg to restrict to a package prefix.",
			InputSchema: schema(withPaging(map[string]any{
				"arg": map[string]any{"type": "string", "description": "Optional package prefix."},
			})),
		},
		{
			Name: "search_symbols",
			Description: "Broad retrieval entrypoint for agents: search classes, methods, annotations, " +
				"SPI registrations, Spring config properties, and string constants before opening a result.",
			InputSchema: schema(withPaging(map[string]any{
				"text": map[string]any{"type": "string", "description": "Text to search for."},
			}), "text"),
		},
		{
			Name:        "open_symbol",
			Description: "Open a symbol returned by search_symbols. Use a class FQN or Class#method.",
			InputSchema: schema(map[string]any{
				"fqn":         map[string]any{"type": "string", "description": "Class FQN or Class#method symbol."},
				"project_dir": projectDir,
			}, "fqn"),
		},
		{
			Name: "dependency_precise",
			Description: "Resolve an import, class, Class#method, Maven coordinate, artifact id, " +
				"or jar filename to the owning jar; include Maven dependency path/provenance and " +
				"bounded source/build-file usages so agents can confirm both dependency presence " +
				"and project consumption.",
			InputSchema: schema(map[string]any{
				"text": map[string]any{
					"type":        "string",
					"description": "Import line, class FQN/simple name, Class#method, group:artifact, artifact id, or jar filename.",
				},
				"project_dir": projectDir,
				"page_size": map[string]any{
					"type":        "integer",
					"description": "Max origins and source usage rows to return (default 20).",
				},
				"no_source_grep": map[string]any{
					"type":        "boolean",
					"description": "Skip bounded source/build-file usage search.",
				},
			}, "text"),
		},
		{
			Name: "class_origin",
			Description: "Resolve a class simple name or FQN to the owning jar; include Maven " +
				"dependency provenance and bounded source/build-file usages. Use find_class " +
				"for fuzzy class search.",
			InputSchema: schema(map[string]any{
				"text": map[string]any{
					"type":        "string",
					"description": "Class simple name, class FQN, or import line.",
				},
				"project_dir": projectDir,
				"page_size": map[string]any{
					"type":        "integer",
					"description": "Max origins and source usage rows to return (default 20).",
				},
				"no_source_grep": map[string]any{
					"type":        "boolean",
					"description": "Skip bounded source/build-file usage search.",
				},
			}, "text"),
		},
		{
			Name: "find_artifact",
			Description: "Find a dependency artifact or jar by group:artifact, group:artifact:version, " +
				"artifact id, or jar filename; returns the indexed jar plus dependency provenance.",
			InputSchema: schema(map[string]any{
				"text": map[string]any{
					"type":        "string",
					"description": "group:artifact, group:artifact:version, artifact id, or jar filename.",
				},
				"project_dir": projectDir,
				"page_size": map[string]any{
					"type":        "integer",
					"description": "Max origins and source usage rows to return (default 20).",
				},
				"no_source_grep": map[string]any{
					"type":        "boolean",
					"description": "Skip bounded source/build-file usage search.",
				},
			}, "text"),
		},
		{
			Name: "why_dependency",
			Description: "Explain why a Maven dependency jar is present. Accepts group:artifact, " +
				"group:artifact:version, jar filename, or a class FQN.",
			InputSchema: schema(map[string]any{
				"fqn":         map[string]any{"type": "string", "description": "Coordinate, jar filename, or class FQN."},
				"project_dir": projectDir,
			}, "fqn"),
		},
	}
}
