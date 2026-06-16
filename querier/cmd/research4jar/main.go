package main

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"text/tabwriter"
	"time"

	"dev.research4jar/querier/internal/cache"
	"dev.research4jar/querier/internal/classpath"
	"dev.research4jar/querier/internal/depgraph"
	"dev.research4jar/querier/internal/envcheck"
	"dev.research4jar/querier/internal/indexer"
	"dev.research4jar/querier/internal/jarsource"
	"dev.research4jar/querier/internal/manifest"
	"dev.research4jar/querier/internal/mcp"
	"dev.research4jar/querier/internal/paths"
	"dev.research4jar/querier/internal/pointer"
	"dev.research4jar/querier/internal/project"
	"dev.research4jar/querier/internal/query"
	"dev.research4jar/querier/internal/registry"
	"dev.research4jar/querier/internal/session"
	"dev.research4jar/querier/internal/versions"
)

type options struct {
	arg        string
	projectDir string
	format     string
	page       int
	pageSize   int
	home       string
	direct     bool
	sourceGrep bool
	jars       string
	indexer    string
}

type errorResponse struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

// version is overridden at release time via -ldflags "-X main.version=...".
var version = "dev"

var queryCommands = map[string]bool{
	"find-config-properties": true,
	"find-implementations":   true,
	"find-by-annotation":     true,
	"get-class":              true,
	"get-bean-definitions":   true,
	"explain-conditional":    true,
	"find-string":            true,
	"list-extension-points":  true,
	"find-class":             true,
	"find-method":            true,
	"list-packages":          true,
	"search-symbol":          true,
	"open-symbol":            true,
	"why-dependency":         true,
}

func main() {
	if len(os.Args) == 1 || os.Args[1] == "--help" || os.Args[1] == "-h" {
		printHelp()
		return
	}
	command := os.Args[1]
	switch {
	case command == "version" || command == "--version":
		fmt.Println("research4jar " + version)
		return
	case command == "mcp":
		if err := mcp.Serve(os.Stdin, os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, "research4jar mcp:", err)
			os.Exit(1)
		}
		return
	case command == "index":
		runIndexCommand(os.Args[2:])
		return
	case command == "doctor":
		runDoctorCommand(os.Args[2:])
		return
	case command == "status":
		runStatusCommand(os.Args[2:])
		return
	case command == "cache":
		runCacheCommand(os.Args[2:])
		return
	case command == "registry":
		runRegistryCommand(os.Args[2:])
		return
	case command == "dep":
		runDepCommand(os.Args[2:])
		return
	case command == "artifact":
		runArtifactCommand(os.Args[2:])
		return
	case command == "class":
		runClassAliasCommand(os.Args[2:])
		return
	case command == "method":
		runMethodAliasCommand(os.Args[2:])
		return
	case !queryCommands[command]:
		fail("invalid_command", "unknown command: "+command, 2)
	}

	opts, err := parseOptions(os.Args[2:], command == "list-extension-points" || command == "list-packages")
	if err != nil {
		fail("invalid_arguments", err.Error(), 2)
	}
	pointer, manifestPath, err := query.ResolveProject(opts.projectDir, opts.home)
	if errors.Is(err, project.ErrNotFound) {
		fail(
			"no_project_index",
			"no .research4jar/project.json found; run research4jar index --project-dir <path>",
			1,
		)
	}
	if err != nil {
		fail("project_index_error", err.Error(), 1)
	}

	ctx := context.Background()
	var response any
	switch command {
	case "find-config-properties":
		response, err = query.FindConfigProperties(
			ctx, pointer, manifestPath, opts.arg, opts.page, opts.pageSize,
		)
	case "find-implementations":
		response, err = query.FindImplementations(
			ctx, pointer, manifestPath, opts.arg, opts.direct, opts.page, opts.pageSize,
		)
	case "find-by-annotation":
		response, err = query.FindByAnnotation(
			ctx, pointer, manifestPath, opts.arg, opts.direct, opts.page, opts.pageSize,
		)
	case "get-class":
		response, err = query.GetClass(ctx, pointer, manifestPath, opts.arg)
	case "get-bean-definitions":
		response, err = query.GetBeanDefinitions(
			ctx, pointer, manifestPath, opts.arg, opts.page, opts.pageSize,
		)
	case "explain-conditional":
		response, err = query.ExplainConditional(ctx, pointer, manifestPath, opts.arg)
	case "find-string":
		response, err = query.FindString(
			ctx, pointer, manifestPath, opts.arg, opts.page, opts.pageSize,
		)
	case "list-extension-points":
		response, err = query.ListExtensionPoints(
			ctx, pointer, manifestPath, opts.arg, opts.page, opts.pageSize,
		)
	case "find-class":
		response, err = query.FindClass(
			ctx, pointer, manifestPath, opts.arg, opts.page, opts.pageSize,
		)
	case "find-method":
		response, err = query.FindMethod(
			ctx, pointer, manifestPath, opts.arg, opts.page, opts.pageSize,
		)
	case "list-packages":
		response, err = query.ListPackages(
			ctx, pointer, manifestPath, opts.arg, opts.page, opts.pageSize,
		)
	case "search-symbol":
		response, err = query.SearchSymbol(
			ctx, pointer, manifestPath, opts.arg, opts.page, opts.pageSize,
		)
	case "open-symbol":
		response, err = query.OpenSymbol(ctx, pointer, manifestPath, opts.arg)
	case "why-dependency":
		var projectRoot string
		projectRoot, err = project.Root(opts.projectDir)
		if err == nil {
			response, err = query.WhyDependency(ctx, pointer, manifestPath, projectRoot, opts.arg)
		}
	}
	if err != nil {
		message := err.Error()
		if strings.Contains(message, "no such table") {
			message += " (session database was generated by an older version; rerun research4jar index to upgrade)"
		}
		fail("query_error", message, 1)
	}

	if opts.format == "text" {
		printText(response)
		return
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(response); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func runIndexCommand(args []string) {
	opts := options{projectDir: "."}
	registryURL := os.Getenv("RESEARCH4JAR_REGISTRY")
	registryPubkey := os.Getenv("RESEARCH4JAR_REGISTRY_PUBKEY")
	for index := 0; index < len(args); index++ {
		argument := args[index]
		switch argument {
		case "--jars", "--project-dir", "--home", "--indexer",
			"--registry", "--registry-pubkey":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				fail("invalid_arguments", err.Error(), 2)
			}
			switch argument {
			case "--jars":
				opts.jars = value
			case "--project-dir":
				opts.projectDir = value
			case "--home":
				opts.home = value
			case "--indexer":
				opts.indexer = value
			case "--registry":
				registryURL = value
			case "--registry-pubkey":
				registryPubkey = value
			}
			index = next
		default:
			fail("invalid_arguments", "unknown option: "+argument, 2)
		}
	}
	startedAt := time.Now()
	jars := opts.jars
	if registryURL != "" {
		var stats registry.PrefetchStats
		var dataPaths paths.DataPaths
		jars, stats, dataPaths = prefetchFromRegistry(registryURL, registryPubkey, opts)
		if stats.Complete {
			finishIndexFromRegistry(stats, dataPaths, opts.projectDir, startedAt)
			return
		}
	}
	indexerBin, err := indexer.Locate(opts.indexer)
	if err != nil {
		fail("indexer_not_found", err.Error()+"\n\n"+doctorHint(opts.projectDir, false), 1)
	}
	if err := indexer.Run(indexerBin, jars, opts.projectDir, opts.home); err != nil {
		fail("index_error", err.Error()+"\n\n"+doctorHint(opts.projectDir, false), 1)
	}
	captureDependencyProvenance(opts.projectDir)
}

func runDoctorCommand(args []string) {
	options := envcheck.Options{}
	format := "text"
	for index := 0; index < len(args); index++ {
		argument := args[index]
		switch argument {
		case "--project-dir":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				fail("invalid_arguments", err.Error(), 2)
			}
			options.ProjectDir = value
			index = next
		case "--format":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				fail("invalid_arguments", err.Error(), 2)
			}
			if value != "json" && value != "text" {
				fail("invalid_arguments", "--format must be json or text", 2)
			}
			format = value
			index = next
		case "--source-build":
			options.SourceBuild = true
		default:
			fail("invalid_arguments", "unknown option: "+argument, 2)
		}
	}

	report := envcheck.Run(options)
	if format == "json" {
		printJSON(report)
	} else {
		printDoctorText(report)
	}
	if !report.OK {
		os.Exit(1)
	}
}

// finishIndexFromRegistry completes an index run whose every shard came from
// the cache or the registry: the session merge, project pointer, and
// CLAUDE.md guidance are produced in pure Go and the JVM never starts.
func finishIndexFromRegistry(
	stats registry.PrefetchStats, dataPaths paths.DataPaths, projectDir string, startedAt time.Time,
) {
	shards := make([]session.Shard, len(stats.Shards))
	shardIDs := make([]string, len(stats.Shards))
	for index, shard := range stats.Shards {
		shards[index] = session.Shard{ShardID: shard.ShardID, Path: shard.Path}
		shardIDs[index] = shard.ShardID
	}
	fingerprint := session.Fingerprint(shardIDs)
	sessionPath := filepath.Join(dataPaths.Sessions, fingerprint+".db")
	if err := session.BuildIfAbsent(sessionPath, shards); err != nil {
		fail("index_error", "build session: "+err.Error(), 1)
	}
	coverage := project.Coverage{
		JarsTotal:   len(shards),
		JarsIndexed: len(shards),
		JarsMissing: []string{},
	}
	if err := pointer.Write(projectDir, fingerprint, sessionPath, coverage); err != nil {
		fail("index_error", "write project pointer: "+err.Error(), 1)
	}
	if err := pointer.EnsureClaudeInstructions(projectDir); err != nil {
		fail("index_error", "write CLAUDE.md guidance: "+err.Error(), 1)
	}
	captureDependencyProvenance(projectDir)
	fmt.Fprintln(
		os.Stderr,
		"research4jar: session built from cached/registry shards; no local extraction needed",
	)
	printJSON(struct {
		JarsTotal        int      `json:"jars_total"`
		JarsIndexed      int      `json:"jars_indexed"`
		JarsNewlyIndexed int      `json:"jars_newly_indexed"`
		JarsSkipped      int      `json:"jars_skipped"`
		JarsMissing      []string `json:"jars_missing"`
		DurationMS       int64    `json:"duration_ms"`
	}{
		JarsTotal:        len(shards),
		JarsIndexed:      len(shards),
		JarsNewlyIndexed: 0,
		JarsSkipped:      len(shards),
		JarsMissing:      []string{},
		DurationMS:       time.Since(startedAt).Milliseconds(),
	})
}

// prefetchFromRegistry downloads missing shards before the JVM indexer runs
// so it hits the local cache instead of extracting. Returns the (possibly
// discovered) jar specification, the prefetch outcome, and the resolved data
// paths. Prefetch problems degrade to local extraction and never abort the
// index run.
func prefetchFromRegistry(
	registryURL, registryPubkey string, opts options,
) (string, registry.PrefetchStats, paths.DataPaths) {
	client, err := registry.NewClient(registryURL, registryPubkey)
	if err != nil {
		fail("invalid_arguments", err.Error(), 2)
	}
	jars := opts.jars
	var jarList []string
	if jars == "" {
		discovered, err := classpath.Discover(opts.projectDir)
		if err != nil {
			fail("index_error", err.Error(), 1)
		}
		if len(discovered) == 0 {
			fail("index_error", "build tool resolved an empty runtime classpath", 1)
		}
		fmt.Fprintf(
			os.Stderr,
			"research4jar: resolved %d dependency jars from the build tool\n",
			len(discovered),
		)
		jarList = discovered
		jars = strings.Join(discovered, ",")
	} else {
		jarList, err = jarsource.Resolve(jars)
		if err != nil {
			fail("index_error", err.Error(), 1)
		}
	}
	dataPaths, err := paths.Resolve(opts.home)
	if err != nil {
		fail("index_error", err.Error(), 1)
	}
	manifestDB, err := manifest.Open(dataPaths.Manifest)
	if err != nil {
		fail("index_error", err.Error(), 1)
	}
	defer manifestDB.Close()
	stats := registry.Prefetch(
		context.Background(), client, manifestDB, dataPaths.Shards,
		versions.Extractor, jarList, os.Stderr,
	)
	fmt.Fprintf(
		os.Stderr,
		"research4jar: registry prefetch: %d already cached, %d downloaded, %d not in registry, %d failed\n",
		stats.CacheHits, stats.Downloaded, stats.Misses, stats.Failures,
	)
	return jars, stats, dataPaths
}

func captureDependencyProvenance(projectDir string) {
	graph, err := depgraph.Capture(projectDir)
	if errors.Is(err, depgraph.ErrUnsupported) {
		return
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "warning: dependency provenance unavailable: %v\n", err)
		return
	}
	if err := depgraph.Write(projectDir, graph); err != nil {
		fmt.Fprintf(os.Stderr, "warning: write dependency provenance: %v\n", err)
	}
}

func runStatusCommand(args []string) {
	if helpRequested(args) {
		printStatusHelp()
		return
	}
	opts := options{format: "text"}
	for index := 0; index < len(args); index++ {
		argument := args[index]
		switch argument {
		case "--project-dir", "--home", "--format":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				fail("invalid_arguments", err.Error(), 2)
			}
			switch argument {
			case "--project-dir":
				opts.projectDir = value
			case "--home":
				opts.home = value
			case "--format":
				if value != "json" && value != "text" {
					fail("invalid_arguments", "--format must be json or text", 2)
				}
				opts.format = value
			}
			index = next
		default:
			fail("invalid_arguments", "unknown option: "+argument, 2)
		}
	}
	response, err := query.ProjectStatus(opts.projectDir, opts.home)
	if err != nil {
		fail("status_error", err.Error(), 1)
	}
	emitResponse(response, opts.format)
}

func runDepCommand(args []string) {
	if len(args) == 0 || helpRequested(args[:1]) {
		printDepHelp()
		return
	}
	subcommand := args[0]
	if len(args) > 1 && helpRequested(args[1:]) {
		printDepSubcommandHelp(subcommand)
		return
	}
	if subcommand != "precise" && subcommand != "origin" && subcommand != "resolve" && subcommand != "why" {
		fail(
			"invalid_arguments",
			"usage: research4jar dep precise <IMPORT|CLASS|COORD|JAR> [--no-source-grep] | research4jar dep why <COORD|JAR|CLASS>",
			2,
		)
	}
	opts, err := parseOptions(args[1:], false)
	if err != nil {
		fail("invalid_arguments", err.Error(), 2)
	}
	switch subcommand {
	case "precise", "origin", "resolve":
		runDependencyPrecise(opts)
	case "why":
		runWhyDependency(opts)
	default:
		fail("invalid_arguments", "unknown dep subcommand: "+subcommand, 2)
	}
}

func runArtifactCommand(args []string) {
	if helpRequested(args) {
		printArtifactHelp()
		return
	}
	opts, err := parseOptions(args, false)
	if err != nil {
		fail("invalid_arguments", err.Error(), 2)
	}
	runArtifactPrecise(opts)
}

func runClassAliasCommand(args []string) {
	if helpRequested(args) {
		printClassHelp()
		return
	}
	opts, err := parseOptions(args, false)
	if err != nil {
		fail("invalid_arguments", err.Error(), 2)
	}
	pointer, manifestPath := resolveProjectOrFail(opts)
	projectRoot := projectRootOrFail(opts)
	response, err := query.ClassPrecise(
		context.Background(), pointer, manifestPath, projectRoot, opts.arg, opts.pageSize, opts.sourceGrep,
	)
	if err != nil {
		failQueryError(err)
	}
	emitResponse(response, opts.format)
}

func runMethodAliasCommand(args []string) {
	if helpRequested(args) {
		printMethodHelp()
		return
	}
	opts, err := parseOptions(args, false)
	if err != nil {
		fail("invalid_arguments", err.Error(), 2)
	}
	pointer, manifestPath := resolveProjectOrFail(opts)
	response, err := query.FindMethod(
		context.Background(), pointer, manifestPath, opts.arg, opts.page, opts.pageSize,
	)
	if err != nil {
		failQueryError(err)
	}
	emitResponse(response, opts.format)
}

func runDependencyPrecise(opts options) {
	pointer, manifestPath := resolveProjectOrFail(opts)
	projectRoot := projectRootOrFail(opts)
	response, err := query.DependencyPrecise(
		context.Background(), pointer, manifestPath, projectRoot, opts.arg, opts.pageSize, opts.sourceGrep,
	)
	if err != nil {
		failQueryError(err)
	}
	emitResponse(response, opts.format)
}

func runArtifactPrecise(opts options) {
	pointer, manifestPath := resolveProjectOrFail(opts)
	projectRoot := projectRootOrFail(opts)
	response, err := query.ArtifactPrecise(
		context.Background(), pointer, manifestPath, projectRoot, opts.arg, opts.pageSize, opts.sourceGrep,
	)
	if err != nil {
		failQueryError(err)
	}
	emitResponse(response, opts.format)
}

func runWhyDependency(opts options) {
	pointer, manifestPath := resolveProjectOrFail(opts)
	projectRoot := projectRootOrFail(opts)
	response, err := query.WhyDependency(context.Background(), pointer, manifestPath, projectRoot, opts.arg)
	if err != nil {
		failQueryError(err)
	}
	emitResponse(response, opts.format)
}

func resolveProjectOrFail(opts options) (project.Pointer, string) {
	pointer, manifestPath, err := query.ResolveProject(opts.projectDir, opts.home)
	if errors.Is(err, project.ErrNotFound) {
		fail(
			"no_project_index",
			"no .research4jar/project.json found; run research4jar index --project-dir <path>",
			1,
		)
	}
	if err != nil {
		fail("project_index_error", err.Error(), 1)
	}
	return pointer, manifestPath
}

func projectRootOrFail(opts options) string {
	projectRoot, err := project.Root(opts.projectDir)
	if errors.Is(err, project.ErrNotFound) {
		fail(
			"no_project_index",
			"no .research4jar/project.json found; run research4jar index --project-dir <path>",
			1,
		)
	}
	if err != nil {
		fail("project_index_error", err.Error(), 1)
	}
	return projectRoot
}

func failQueryError(err error) {
	message := err.Error()
	if strings.Contains(message, "no such table") {
		message += " (session database was generated by an older version; rerun research4jar index to upgrade)"
	}
	fail("query_error", message, 1)
}

func helpRequested(args []string) bool {
	for _, arg := range args {
		if arg == "--help" || arg == "-h" {
			return true
		}
	}
	return false
}

func runCacheCommand(args []string) {
	if len(args) == 0 || (args[0] != "stats" && args[0] != "gc") {
		fail("invalid_arguments", "usage: research4jar cache <stats|gc> [options]", 2)
	}
	subcommand := args[0]
	var home string
	gcOptions := cache.GCOptions{}
	for index := 1; index < len(args); index++ {
		argument := args[index]
		switch argument {
		case "--home", "--max-size", "--max-age":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				fail("invalid_arguments", err.Error(), 2)
			}
			switch argument {
			case "--home":
				home = value
			case "--max-size":
				gcOptions.MaxShardBytes, err = cache.ParseSize(value)
			case "--max-age":
				gcOptions.MaxSessionAge, err = cache.ParseAge(value)
			}
			if err != nil {
				fail("invalid_arguments", err.Error(), 2)
			}
			index = next
		case "--dry-run":
			gcOptions.DryRun = true
		default:
			fail("invalid_arguments", "unknown option: "+argument, 2)
		}
	}
	dataPaths, err := paths.Resolve(home)
	if err != nil {
		fail("cache_error", err.Error(), 1)
	}
	var response any
	switch subcommand {
	case "stats":
		response, err = cache.CollectStats(dataPaths, versions.Extractor)
	case "gc":
		response, err = cache.GC(dataPaths, versions.Extractor, gcOptions)
	}
	if err != nil {
		fail("cache_error", err.Error(), 1)
	}
	printJSON(response)
}

func runRegistryCommand(args []string) {
	if len(args) == 0 || (args[0] != "export" && args[0] != "keygen" && args[0] != "seed") {
		fail(
			"invalid_arguments",
			"usage: research4jar registry export <DIR> [--sign-key PATH] [--home DIR] | "+
				"research4jar registry seed <DIR> --coordinates <FILE> [--repo URL] "+
				"[--sign-key PATH] [--home DIR] [--indexer PATH] | "+
				"research4jar registry keygen <PATH>",
			2,
		)
	}
	subcommand := args[0]
	var target, signKey, home, coordinatesPath, repo, indexerPath string
	for index := 1; index < len(args); index++ {
		argument := args[index]
		switch argument {
		case "--sign-key", "--home", "--coordinates", "--repo", "--indexer":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				fail("invalid_arguments", err.Error(), 2)
			}
			switch argument {
			case "--sign-key":
				signKey = value
			case "--home":
				home = value
			case "--coordinates":
				coordinatesPath = value
			case "--repo":
				repo = value
			case "--indexer":
				indexerPath = value
			}
			index = next
		default:
			if strings.HasPrefix(argument, "-") {
				fail("invalid_arguments", "unknown option: "+argument, 2)
			}
			if target != "" {
				fail("invalid_arguments", subcommand+" accepts exactly one argument", 2)
			}
			target = argument
		}
	}
	if target == "" {
		fail("invalid_arguments", subcommand+" requires a target path argument", 2)
	}

	if subcommand == "keygen" {
		publicKey, err := registry.GenerateKey(target)
		if err != nil {
			fail("registry_error", err.Error(), 1)
		}
		printJSON(map[string]string{
			"private_key_path": target,
			"public_key":       publicKey,
		})
		return
	}

	if subcommand == "seed" {
		if coordinatesPath == "" {
			fail("invalid_arguments", "seed requires --coordinates <FILE>", 2)
		}
		seedRegistry(target, coordinatesPath, repo, signKey, home, indexerPath)
		return
	}

	dataPaths, err := paths.Resolve(home)
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	manifestDB, err := manifest.Open(dataPaths.Manifest)
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	shards, err := manifestDB.List()
	manifestDB.Close()
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	var signingKey ed25519.PrivateKey
	if signKey != "" {
		signingKey, err = registry.LoadSigningKey(signKey)
		if err != nil {
			fail("registry_error", err.Error(), 1)
		}
	}
	result, err := registry.Export(
		shards, versions.Extractor, target, signingKey, "research4jar "+version, os.Stderr,
	)
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	printJSON(result)
}

// seedRegistry downloads jars for a coordinates list, indexes them into the
// (usually fresh) home, and exports the resulting shards as a registry tree.
// This is how the official public registry — and any enterprise's private
// one — is produced in CI.
func seedRegistry(outputDir, coordinatesPath, repo, signKey, home, indexerPath string) {
	if repo == "" {
		repo = registry.DefaultMavenRepo
	}
	coordinatesFile, err := os.Open(coordinatesPath)
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	coordinates, err := registry.ParseCoordinates(coordinatesFile)
	coordinatesFile.Close()
	if err != nil {
		fail("invalid_arguments", "parse coordinates: "+err.Error(), 2)
	}
	if len(coordinates) == 0 {
		fail("invalid_arguments", "coordinates file lists no artifacts", 2)
	}

	jarDir, err := os.MkdirTemp("", "research4jar-seed-jars-")
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	defer os.RemoveAll(jarDir)
	fmt.Fprintf(
		os.Stderr, "research4jar: downloading %d artifacts from %s\n", len(coordinates), repo,
	)
	jars, downloadFailures := registry.DownloadJars(
		context.Background(), repo, coordinates, jarDir, os.Stderr,
	)
	if len(jars) == 0 {
		fail("registry_error", "no artifacts could be downloaded", 1)
	}

	indexerBin, err := indexer.Locate(indexerPath)
	if err != nil {
		fail("indexer_not_found", err.Error()+"\n\n"+doctorHint("", true), 1)
	}
	scratchProject, err := os.MkdirTemp("", "research4jar-seed-project-")
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	defer os.RemoveAll(scratchProject)
	if err := indexer.Run(indexerBin, strings.Join(jars, ","), scratchProject, home); err != nil {
		fail("index_error", err.Error()+"\n\n"+doctorHint("", true), 1)
	}

	dataPaths, err := paths.Resolve(home)
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	manifestDB, err := manifest.Open(dataPaths.Manifest)
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	shards, err := manifestDB.List()
	manifestDB.Close()
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	var signingKey ed25519.PrivateKey
	if signKey != "" {
		signingKey, err = registry.LoadSigningKey(signKey)
		if err != nil {
			fail("registry_error", err.Error(), 1)
		}
	}
	result, err := registry.Export(
		shards, versions.Extractor, outputDir, signingKey, "research4jar "+version, os.Stderr,
	)
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	printJSON(struct {
		registry.ExportResult
		Coordinates    int `json:"coordinates"`
		DownloadFailed int `json:"download_failed"`
		JarsDownloaded int `json:"jars_downloaded"`
	}{
		ExportResult:   result,
		Coordinates:    len(coordinates),
		DownloadFailed: downloadFailures,
		JarsDownloaded: len(jars),
	})
}

func printJSON(response any) {
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(response); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func emitResponse(response any, format string) {
	if format == "text" {
		printText(response)
		return
	}
	printJSON(response)
}

func printDoctorText(report envcheck.Report) {
	fmt.Println("Research4Jar environment")
	if report.ProjectDir != "" {
		fmt.Println("Project:", report.ProjectDir)
	}
	fmt.Println()
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "STATUS\tCHECK\tFOUND\tVERSION\tREQUIRED FOR")
	for _, check := range report.Checks {
		fmt.Fprintf(
			writer,
			"%s\t%s\t%s\t%s\t%s\n",
			doctorStatus(check.Status),
			check.Name,
			valueOrText(check.Found, "-"),
			valueOrText(check.Version, "-"),
			strings.Join(check.RequiredFor, ", "),
		)
	}
	writer.Flush()

	for _, check := range report.Checks {
		if check.Status == envcheck.StatusOK {
			continue
		}
		fmt.Printf("\n%s: %s\n", doctorStatus(check.Status), check.Name)
		fmt.Println("  " + check.Message)
		if check.UserInstall != "" {
			fmt.Println("  User install: " + check.UserInstall)
		}
		if len(check.AgentInstall) > 0 {
			fmt.Println("  Agent install:")
			for _, command := range check.AgentInstall {
				fmt.Println("    " + command)
			}
		}
		if len(check.Verify) > 0 {
			fmt.Println("  Verify:")
			for _, command := range check.Verify {
				fmt.Println("    " + command)
			}
		}
	}
	if report.OK {
		fmt.Println("\nOK: required environment checks passed.")
	} else {
		fmt.Println("\nMissing requirements found. Install the missing tools above, then rerun research4jar doctor.")
	}
}

func doctorStatus(status envcheck.Status) string {
	switch status {
	case envcheck.StatusOK:
		return "OK"
	case envcheck.StatusWarning:
		return "WARN"
	default:
		return "MISSING"
	}
}

func valueOrText(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func doctorHint(projectDir string, sourceBuild bool) string {
	args := []string{"research4jar doctor"}
	if projectDir != "" && projectDir != "." {
		args = append(args, "--project-dir "+strconv.Quote(projectDir))
	}
	if sourceBuild {
		args = append(args, "--source-build")
	}
	return "Run " + strings.Join(args, " ") + " for environment checks and installation guidance."
}

func parseOptions(args []string, argOptional bool) (options, error) {
	result := options{format: "json", page: 1, pageSize: 20, sourceGrep: true}
	for index := 0; index < len(args); index++ {
		argument := args[index]
		switch argument {
		case "--project-dir":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				return options{}, err
			}
			result.projectDir = value
			index = next
		case "--format":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				return options{}, err
			}
			if value != "json" && value != "text" {
				return options{}, errors.New("--format must be json or text")
			}
			result.format = value
			index = next
		case "--page":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				return options{}, err
			}
			result.page, err = positiveInteger(argument, value)
			if err != nil {
				return options{}, err
			}
			index = next
		case "--page-size":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				return options{}, err
			}
			result.pageSize, err = positiveInteger(argument, value)
			if err != nil {
				return options{}, err
			}
			index = next
		case "--home":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				return options{}, err
			}
			result.home = value
			index = next
		case "--direct":
			result.direct = true
		case "--source-grep":
			result.sourceGrep = true
		case "--no-source-grep":
			result.sourceGrep = false
		default:
			if len(argument) > 0 && argument[0] == '-' {
				return options{}, fmt.Errorf("unknown option: %s", argument)
			}
			if result.arg != "" {
				return options{}, errors.New("command accepts exactly one argument")
			}
			result.arg = argument
		}
	}
	if result.arg == "" && !argOptional {
		return options{}, errors.New("query argument is required")
	}
	return result, nil
}

func optionValue(args []string, index int, option string) (string, int, error) {
	if index+1 >= len(args) {
		return "", index, fmt.Errorf("%s requires a value", option)
	}
	return args[index+1], index + 1, nil
}

func positiveInteger(option, value string) (int, error) {
	number, err := strconv.Atoi(value)
	if err != nil || number < 1 {
		return 0, fmt.Errorf("%s must be a positive integer", option)
	}
	return number, nil
}

func printText(response any) {
	switch typed := response.(type) {
	case query.ConfigPropertiesResponse:
		printConfigPropertiesText(typed)
	case query.SymbolResponse:
		printSymbolsText(typed)
	case query.BeanDefinitionsResponse:
		printBeansText(typed)
	case query.StringSearchResponse:
		printStringsText(typed)
	case query.ExtensionPointsResponse:
		printExtensionsText(typed)
	case query.ClassSearchResponse:
		printClassSearchText(typed)
	case query.MethodSearchResponse:
		printMethodSearchText(typed)
	case query.PackageListResponse:
		printPackagesText(typed)
	case query.SearchSymbolResponse:
		printSearchSymbolsText(typed)
	case query.DependencyWhyResponse:
		printWhyDependencyText(typed)
	case query.DependencyPreciseResponse:
		printDependencyPreciseText(typed)
	case query.ProjectStatusResponse:
		printProjectStatusText(typed)
	default:
		// Nested detail responses (get-class, explain-conditional) read best
		// as structured JSON even in text mode.
		encoder := json.NewEncoder(os.Stdout)
		encoder.SetIndent("", "  ")
		_ = encoder.Encode(response)
	}
}

func printClassSearchText(response query.ClassSearchResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "FQN\tKIND\tSCORE\tREASON\tSOURCE JAR")
	for _, result := range response.Results {
		fmt.Fprintf(
			writer, "%s\t%s\t%d\t%s\t%s\n",
			result.FQN, valueOrDash(result.Kind), result.Score, result.MatchReason, result.SourceJar,
		)
	}
	writer.Flush()
	printSearchSummary(response.Page, response.PageSize, response.Total, response.HasMore, response.Coverage)
}

func printMethodSearchText(response query.MethodSearchResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "METHOD\tRETURN\tSCORE\tREASON\tSOURCE JAR")
	for _, result := range response.Results {
		fmt.Fprintf(
			writer, "%s#%s%s\t%s\t%d\t%s\t%s\n",
			result.ClassFQN, result.Name, result.Descriptor,
			valueOrDash(result.ReturnFQN), result.Score, result.MatchReason, result.SourceJar,
		)
	}
	writer.Flush()
	printSearchSummary(response.Page, response.PageSize, response.Total, response.HasMore, response.Coverage)
}

func printPackagesText(response query.PackageListResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "PACKAGE\tCLASSES\tSOURCE JAR")
	for _, result := range response.Results {
		fmt.Fprintf(writer, "%s\t%d\t%s\n", result.Package, result.Classes, result.SourceJar)
	}
	writer.Flush()
	printSearchSummary(response.Page, response.PageSize, response.Total, response.HasMore, response.Coverage)
}

func printSearchSymbolsText(response query.SearchSymbolResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "KIND\tNAME\tOWNER\tSCORE\tREASON\tSOURCE JAR")
	for _, result := range response.Results {
		fmt.Fprintf(
			writer, "%s\t%s\t%s\t%d\t%s\t%s\n",
			result.Kind, result.Name, valueOrDash(result.Owner),
			result.Score, result.MatchReason, result.SourceJar,
		)
	}
	writer.Flush()
	printSearchSummary(response.Page, response.PageSize, response.Total, response.HasMore, response.Coverage)
}

func printWhyDependencyText(response query.DependencyWhyResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "COORDINATE\tDIRECT\tDEPTH\tDIRECT DEPENDENCY\tPARENT\tPATH")
	for _, result := range response.Results {
		fmt.Fprintf(
			writer, "%s\t%t\t%d\t%s\t%s\t%s\n",
			result.Coordinate,
			result.Direct,
			result.Depth,
			valueOrText(result.DirectDependency, "-"),
			valueOrText(result.Parent, "-"),
			strings.Join(result.Path, " -> "),
		)
	}
	writer.Flush()
	fmt.Printf(
		"\ntotal %d; coverage %d/%d jars, %d missing\n",
		response.Total,
		response.Coverage.JarsIndexed,
		response.Coverage.JarsTotal,
		len(response.Coverage.JarsMissing),
	)
}

func printDependencyPreciseText(response query.DependencyPreciseResponse) {
	fmt.Printf("input: %s (%s)\n\n", response.Normalized, response.InputKind)
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "SYMBOL/PACKAGE\tCOORDINATE\tJAR\tMATCH")
	for _, origin := range response.Origins {
		name := origin.FQN
		if name == "" {
			name = origin.Package
		}
		fmt.Fprintf(
			writer,
			"%s\t%s\t%s\t%s\n",
			valueOrText(name, "-"),
			valueOrText(origin.Coordinate, "-"),
			valueOrText(origin.JarFilename, origin.SourceJar),
			origin.MatchReason,
		)
	}
	writer.Flush()

	if len(response.Dependencies) > 0 {
		fmt.Println()
		dependencies := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
		fmt.Fprintln(dependencies, "COORDINATE\tDIRECT\tDIRECT DEPENDENCY\tPARENT\tPATH")
		for _, result := range response.Dependencies {
			fmt.Fprintf(
				dependencies,
				"%s\t%t\t%s\t%s\t%s\n",
				result.Coordinate,
				result.Direct,
				valueOrText(result.DirectDependency, "-"),
				valueOrText(result.Parent, "-"),
				strings.Join(result.Path, " -> "),
			)
		}
		dependencies.Flush()
	} else if !response.DependencyGraphAvailable {
		fmt.Println("\ndependency graph: " + response.DependencyGraphError)
	}

	if len(response.SourceUsages) > 0 {
		fmt.Println()
		usages := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
		fmt.Fprintln(usages, "LOCATION\tMATCH\tLINE")
		for _, usage := range response.SourceUsages {
			fmt.Fprintf(usages, "%s:%d\t%s\t%s\n", usage.Path, usage.Line, usage.Match, usage.Text)
		}
		usages.Flush()
	}

	fmt.Printf(
		"\norigins %d, dependencies %d, source_usages %d, source_usages_has_more %t; coverage %d/%d jars, %d missing\n",
		response.Total,
		response.DependenciesTotal,
		len(response.SourceUsages),
		response.SourceUsagesHasMore,
		response.Coverage.JarsIndexed,
		response.Coverage.JarsTotal,
		len(response.Coverage.JarsMissing),
	)
}

func printProjectStatusText(response query.ProjectStatusResponse) {
	fmt.Println("Research4Jar project status")
	fmt.Println("Project:", valueOrText(response.ProjectDir, "-"))
	fmt.Println("Indexed:", response.Indexed)
	if response.ProjectIndexPath != "" {
		fmt.Println("Project index:", response.ProjectIndexPath)
	}
	if response.ClasspathFingerprint != "" {
		fmt.Println("Classpath fingerprint:", response.ClasspathFingerprint)
	}
	if response.BuiltAtUTC != "" {
		fmt.Println("Built at:", response.BuiltAtUTC)
	}
	if response.SessionDBPath != "" {
		fmt.Println("Session DB:", response.SessionDBPath)
		fmt.Println("Session DB exists:", response.SessionDBExists)
	}
	if response.ManifestPath != "" {
		fmt.Println("Manifest:", response.ManifestPath)
		fmt.Println("Manifest exists:", response.ManifestExists)
	}
	if response.Indexed {
		fmt.Printf(
			"Coverage: %d/%d jars indexed, %d missing\n",
			response.Coverage.JarsIndexed,
			response.Coverage.JarsTotal,
			len(response.Coverage.JarsMissing),
		)
	}
	provenance := response.DependencyProvenance
	fmt.Println("Dependency provenance:", provenance.Available)
	if provenance.Available {
		fmt.Println("Dependency build tool:", valueOrText(provenance.BuildTool, "-"))
		fmt.Println("Dependency artifacts:", provenance.Artifacts)
	}
	if provenance.Path != "" {
		fmt.Println("Dependency provenance path:", provenance.Path)
	}
}

func printConfigPropertiesText(response query.ConfigPropertiesResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "NAME\tTYPE\tDEFAULT\tSOURCE JAR")
	for _, property := range response.Results {
		fmt.Fprintf(
			writer,
			"%s\t%s\t%s\t%s\n",
			property.Name,
			valueOrDash(property.Type),
			valueOrDash(property.Default),
			property.SourceJar,
		)
	}
	writer.Flush()
	printSummary(response.Page, response.PageSize, response.Total, response.Coverage)
}

func printSymbolsText(response query.SymbolResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "FQN\tSOURCE JAR\tMATCHED\tATTRIBUTES")
	for _, result := range response.Results {
		attributes := "-"
		if len(result.Attributes) > 0 {
			attributes = string(result.Attributes)
		}
		matched := result.MatchedAnnotation
		if matched == "" {
			matched = "-"
		}
		fmt.Fprintf(writer, "%s\t%s\t%s\t%s\n", result.FQN, result.SourceJar, matched, attributes)
	}
	writer.Flush()
	printSummary(response.Page, response.PageSize, response.Total, response.Coverage)
}

func printBeansText(response query.BeanDefinitionsResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "BEAN\tTYPE\tCONFIG CLASS\tCONDITIONS\tSOURCE JAR")
	for _, bean := range response.Results {
		fmt.Fprintf(
			writer,
			"%s\t%s\t%s\t%d\t%s\n",
			bean.BeanName,
			valueOrDash(bean.BeanTypeFQN),
			bean.ConfigFQN,
			len(bean.Conditions),
			bean.SourceJar,
		)
	}
	writer.Flush()
	printSummary(response.Page, response.PageSize, response.Total, response.Coverage)
}

func printStringsText(response query.StringSearchResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "VALUE\tCLASS\tMETHOD\tSOURCE JAR")
	for _, constant := range response.Results {
		fmt.Fprintf(
			writer,
			"%s\t%s\t%s\t%s\n",
			constant.Value,
			constant.ClassFQN,
			valueOrDash(constant.Method),
			constant.SourceJar,
		)
	}
	writer.Flush()
	printSummary(response.Page, response.PageSize, response.Total, response.Coverage)
}

func printExtensionsText(response query.ExtensionPointsResponse) {
	writer := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	if response.Results != nil {
		fmt.Fprintln(writer, "MECHANISM\tKEY\tIMPLEMENTATION\tSOURCE JAR")
		for _, registration := range response.Results {
			fmt.Fprintf(
				writer,
				"%s\t%s\t%s\t%s\n",
				registration.Mechanism,
				valueOrDash(registration.Key),
				registration.ImplFQN,
				registration.SourceJar,
			)
		}
	} else {
		fmt.Fprintln(writer, "MECHANISM\tKEY\tIMPLEMENTATIONS")
		for _, point := range response.Points {
			fmt.Fprintf(
				writer,
				"%s\t%s\t%d\n",
				point.Mechanism,
				valueOrDash(point.Key),
				point.Implementations,
			)
		}
	}
	writer.Flush()
	printSummary(response.Page, response.PageSize, response.Total, response.Coverage)
}

func printSummary(page, pageSize, total int, coverage query.Coverage) {
	fmt.Printf(
		"\npage %d, page size %d, total %d; coverage %d/%d jars, %d missing\n",
		page,
		pageSize,
		total,
		coverage.JarsIndexed,
		coverage.JarsTotal,
		len(coverage.JarsMissing),
	)
}

func printSearchSummary(page, pageSize, returned int, hasMore bool, coverage query.Coverage) {
	fmt.Printf(
		"\npage %d, page size %d, returned %d, has_more %t; coverage %d/%d jars, %d missing\n",
		page,
		pageSize,
		returned,
		hasMore,
		coverage.JarsIndexed,
		coverage.JarsTotal,
		len(coverage.JarsMissing),
	)
}

func valueOrDash(value *string) string {
	if value == nil {
		return "-"
	}
	return *value
}

func fail(code, message string, exitCode int) {
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(errorResponse{Error: code, Message: message})
	os.Exit(exitCode)
}

func printStatusHelp() {
	fmt.Println(`Usage:
  research4jar status [--format json|text] [--project-dir <PATH>] [--home <DIR>]

Report whether the current project is indexed, where the project pointer and
session database are, jar coverage, manifest availability, and Maven dependency
provenance status.

Options:
  --format json|text    Output format (default: text).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.`)
}

func printDepHelp() {
	fmt.Println(`Usage:
  research4jar dep precise <IMPORT|CLASS|COORD|JAR> [options]
  research4jar dep why <COORD|JAR|CLASS> [options]

Dependency tools answer where a dependency symbol comes from and why the jar is
present. Use "dep precise" for import/class/jar origin plus source usages; use
"dep why" for Maven dependency paths.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page-size <M>       Max origins/source usage rows for dep precise (default 20).
  --no-source-grep      Skip bounded source/build-file usage search.`)
}

func printDepSubcommandHelp(subcommand string) {
	switch subcommand {
	case "precise", "origin", "resolve":
		fmt.Println(`Usage:
  research4jar dep precise <IMPORT|CLASS|COORD|JAR> [options]

Resolve an import line, class FQN/simple name, Class#method, Maven coordinate,
artifact id, or jar filename to the owning jar. The result includes dependency
provenance when available and bounded source/build-file usages.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page-size <M>       Max origins/source usage rows to return (default 20).
  --no-source-grep      Skip bounded source/build-file usage search.`)
	case "why":
		fmt.Println(`Usage:
  research4jar dep why <COORD|JAR|CLASS> [options]

Explain why a Maven dependency jar is present. Accepts group:artifact,
group:artifact:version, artifact id, jar filename, or a class FQN.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.`)
	default:
		printDepHelp()
	}
}

func printArtifactHelp() {
	fmt.Println(`Usage:
  research4jar artifact <COORD|ARTIFACT|JAR> [options]

Resolve a Maven coordinate, artifact id, or jar filename to the indexed jar and
dependency provenance. Source/build-file usages are included by default.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page-size <M>       Max origins/source usage rows to return (default 20).
  --no-source-grep      Skip bounded source/build-file usage search.`)
}

func printClassHelp() {
	fmt.Println(`Usage:
  research4jar class <NAME|FQN> [options]

Resolve a class simple name or fully-qualified name to the owning jar,
dependency provenance, and bounded source/build-file usages. Use find-class for
fuzzy class search.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page-size <M>       Max origins/source usage rows to return (default 20).
  --no-source-grep      Skip bounded source/build-file usage search.`)
}

func printMethodHelp() {
	fmt.Println(`Usage:
  research4jar method <NAME|CLASS#METHOD> [options]

Find methods by method name, substring, or Class#method. This is a short alias
for find-method.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page <N>            Result page (default 1).
  --page-size <M>       Results per page (default 20).`)
}

func printHelp() {
	fmt.Println(`Usage:
  research4jar index [--jars <DIR|GLOB|LIST>] [options]   Index a project's dependency jars.
                                                       Without --jars the classpath is resolved
                                                       via Maven/Gradle (wrapper preferred).
  research4jar mcp                                        Run as an MCP stdio server (for Cursor,
                                                       Claude Code, and other MCP hosts).
  research4jar doctor [--source-build]                    Check required runtime/build tools and
                     [--format json|text]              print install guidance for users/agents.
  research4jar status [--format json|text]                Show project index, coverage, session,
                                                       manifest, and dependency provenance status.
  research4jar cache stats                                Cache usage: shards, sessions, orphans.
  research4jar cache gc [--max-size <N[K|M|G]>]           Collect garbage: stale extractor versions
                     [--max-age <N[d|h]>] [--dry-run]  and orphans always; LRU shards over the
                                                       size budget; sessions past the age limit.
  research4jar registry export <DIR> [--sign-key <PATH>]  Export local shards as a static registry
                                                       tree (any HTTP host can serve it).
  research4jar registry seed <DIR> --coordinates <FILE>   Download Maven artifacts, index them, and
                     [--repo <URL>] [--sign-key <PATH>] export the registry tree in one step.
  research4jar registry keygen <PATH>                     Generate an ed25519 signing keypair.

  research4jar dep precise <IMPORT|CLASS|COORD|JAR>       Resolve an import/class/jar to source jar,
                                                       dependency path, and source usages.
  research4jar dep why <COORD|JAR|CLASS>                  Explain why a Maven dependency is present.
  research4jar artifact <COORD|ARTIFACT|JAR>              Explicit artifact/jar lookup.
  research4jar class <NAME|FQN>                           Resolve class to source jar, dependency
                                                       path, and source usages.
  research4jar method <NAME|CLASS#METHOD>                 Explicit method lookup alias.

  research4jar find-config-properties <PREFIX>            Config properties under a prefix.
  research4jar find-implementations <FQN> [--direct]      Classes implementing/extending FQN.
                                                       Transitive by default.
  research4jar find-by-annotation <FQN> [--direct]        Classes annotated by FQN. Expands
                                                       meta-annotations by default.
  research4jar get-class <FQN>                            Full stored facts for one class.
  research4jar get-bean-definitions <FQN>                 @Bean definitions by type or config class.
  research4jar explain-conditional <FQN>                  Class- and method-level conditions.
  research4jar find-string <TEXT>                         Substring search over string constants.
  research4jar list-extension-points [KEY|MECHANISM]      SPI registrations summary or detail.
  research4jar find-class <NAME|PATTERN>                  Find classes by simple name, FQN, prefix,
                                                       or substring.
  research4jar find-method <NAME|PATTERN>                 Find methods by name or Class#method.
  research4jar list-packages [PREFIX]                     List packages grouped by source jar.
  research4jar search-symbol <TEXT>                       Search classes, methods, annotations,
                                                       SPI, config properties, and strings.
  research4jar open-symbol <FQN|CLASS#METHOD>             Expand a class or method search result.
  research4jar why-dependency <COORD|JAR|CLASS>           Explain why a Maven dependency is present.

Options:
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --format json|text    Output format (default: json).
  --page <N>            Result page (default: 1).
  --page-size <M>       Results per page (default: 20).
  --home <DIR>          Override Research4Jar home (manifest lookup; index target).
  --direct              Disable transitive/meta-annotation expansion.
  --no-source-grep      (dep precise/artifact/class) Skip bounded source/build-file usage search.
  --indexer <PATH>      (index) Path to research4jar-index; auto-located otherwise.
  --registry <URL>      (index) Shard registry base URL; missing shards download
                        instead of extracting locally. Env: RESEARCH4JAR_REGISTRY.
  --registry-pubkey <H> (index) Hex ed25519 key; downloaded shards must carry a
                        valid signature. Env: RESEARCH4JAR_REGISTRY_PUBKEY.
  --version             Print the research4jar version.
  -h, --help            Show this help.

Notes:
  find-by-annotation expands meta-annotations (e.g. @Component finds @Service classes)
  but does not merge @AliasFor attribute aliases. find-implementations walks declared
  extends/implements chains across all indexed jars.`)
}
