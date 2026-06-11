package main

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"text/tabwriter"

	"dev.springdep/querier/internal/cache"
	"dev.springdep/querier/internal/classpath"
	"dev.springdep/querier/internal/indexer"
	"dev.springdep/querier/internal/jarsource"
	"dev.springdep/querier/internal/manifest"
	"dev.springdep/querier/internal/mcp"
	"dev.springdep/querier/internal/paths"
	"dev.springdep/querier/internal/project"
	"dev.springdep/querier/internal/query"
	"dev.springdep/querier/internal/registry"
	"dev.springdep/querier/internal/versions"
)

type options struct {
	arg        string
	projectDir string
	format     string
	page       int
	pageSize   int
	home       string
	direct     bool
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
}

func main() {
	if len(os.Args) == 1 || os.Args[1] == "--help" || os.Args[1] == "-h" {
		printHelp()
		return
	}
	command := os.Args[1]
	switch {
	case command == "version" || command == "--version":
		fmt.Println("springdep " + version)
		return
	case command == "mcp":
		if err := mcp.Serve(os.Stdin, os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, "springdep mcp:", err)
			os.Exit(1)
		}
		return
	case command == "index":
		runIndexCommand(os.Args[2:])
		return
	case command == "cache":
		runCacheCommand(os.Args[2:])
		return
	case command == "registry":
		runRegistryCommand(os.Args[2:])
		return
	case !queryCommands[command]:
		fail("invalid_command", "未知命令："+command, 2)
	}

	opts, err := parseOptions(os.Args[2:], command == "list-extension-points")
	if err != nil {
		fail("invalid_arguments", err.Error(), 2)
	}
	pointer, manifestPath, err := query.ResolveProject(opts.projectDir, opts.home)
	if errors.Is(err, project.ErrNotFound) {
		fail(
			"no_project_index",
			"未找到 .springdep/project.json；请先运行 springdep index --project-dir <path>",
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
	}
	if err != nil {
		message := err.Error()
		if strings.Contains(message, "no such table") {
			message += "（会话库由旧版本生成；请重新运行 springdep index 以升级）"
		}
		fail("query_error", message, 1)
	}

	if opts.format == "text" {
		printText(response)
		return
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(response); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func runIndexCommand(args []string) {
	opts := options{projectDir: "."}
	registryURL := os.Getenv("SPRINGDEP_REGISTRY")
	registryPubkey := os.Getenv("SPRINGDEP_REGISTRY_PUBKEY")
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
	indexerBin, err := indexer.Locate(opts.indexer)
	if err != nil {
		fail("indexer_not_found", err.Error(), 1)
	}
	jars := opts.jars
	if registryURL != "" {
		jars = prefetchFromRegistry(registryURL, registryPubkey, opts)
	}
	if err := indexer.Run(indexerBin, jars, opts.projectDir, opts.home); err != nil {
		fail("index_error", err.Error(), 1)
	}
}

// prefetchFromRegistry downloads missing shards before the JVM indexer runs
// so it hits the local cache instead of extracting. Returns the (possibly
// discovered) jar specification the indexer should use. Prefetch problems
// degrade to local extraction and never abort the index run.
func prefetchFromRegistry(registryURL, registryPubkey string, opts options) string {
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
			"springdep: resolved %d dependency jars from the build tool\n",
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
		"springdep: registry prefetch: %d already cached, %d downloaded, %d not in registry, %d failed\n",
		stats.CacheHits, stats.Downloaded, stats.Misses, stats.Failures,
	)
	return jars
}

func runCacheCommand(args []string) {
	if len(args) == 0 || (args[0] != "stats" && args[0] != "gc") {
		fail("invalid_arguments", "usage: springdep cache <stats|gc> [options]", 2)
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
	if len(args) == 0 || (args[0] != "export" && args[0] != "keygen") {
		fail(
			"invalid_arguments",
			"usage: springdep registry export <DIR> [--sign-key PATH] [--home DIR] | "+
				"springdep registry keygen <PATH>",
			2,
		)
	}
	subcommand := args[0]
	var target, signKey, home string
	for index := 1; index < len(args); index++ {
		argument := args[index]
		switch argument {
		case "--sign-key", "--home":
			value, next, err := optionValue(args, index, argument)
			if err != nil {
				fail("invalid_arguments", err.Error(), 2)
			}
			if argument == "--sign-key" {
				signKey = value
			} else {
				home = value
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
		shards, versions.Extractor, target, signingKey, "springdep "+version, os.Stderr,
	)
	if err != nil {
		fail("registry_error", err.Error(), 1)
	}
	printJSON(result)
}

func printJSON(response any) {
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(response); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func parseOptions(args []string, argOptional bool) (options, error) {
	result := options{format: "json", page: 1, pageSize: 20}
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
	default:
		// Nested detail responses (get-class, explain-conditional) read best
		// as structured JSON even in text mode.
		encoder := json.NewEncoder(os.Stdout)
		encoder.SetIndent("", "  ")
		_ = encoder.Encode(response)
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

func valueOrDash(value *string) string {
	if value == nil {
		return "-"
	}
	return *value
}

func fail(code, message string, exitCode int) {
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(errorResponse{Error: code, Message: message})
	os.Exit(exitCode)
}

func printHelp() {
	fmt.Println(`Usage:
  springdep index [--jars <DIR|GLOB|LIST>] [options]   Index a project's dependency jars.
                                                       Without --jars the classpath is resolved
                                                       via Maven/Gradle (wrapper preferred).
  springdep mcp                                        Run as an MCP stdio server (for Cursor,
                                                       Claude Code, and other MCP hosts).
  springdep cache stats                                Cache usage: shards, sessions, orphans.
  springdep cache gc [--max-size <N[K|M|G]>]           Collect garbage: stale extractor versions
                     [--max-age <N[d|h]>] [--dry-run]  and orphans always; LRU shards over the
                                                       size budget; sessions past the age limit.
  springdep registry export <DIR> [--sign-key <PATH>]  Export local shards as a static registry
                                                       tree (any HTTP host can serve it).
  springdep registry keygen <PATH>                     Generate an ed25519 signing keypair.

  springdep find-config-properties <PREFIX>            Config properties under a prefix.
  springdep find-implementations <FQN> [--direct]      Classes implementing/extending FQN.
                                                       Transitive by default.
  springdep find-by-annotation <FQN> [--direct]        Classes annotated by FQN. Expands
                                                       meta-annotations by default.
  springdep get-class <FQN>                            Full stored facts for one class.
  springdep get-bean-definitions <FQN>                 @Bean definitions by type or config class.
  springdep explain-conditional <FQN>                  Class- and method-level conditions.
  springdep find-string <TEXT>                         Substring search over string constants.
  springdep list-extension-points [KEY|MECHANISM]      SPI registrations summary or detail.

Options:
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --format json|text    Output format (default: json).
  --page <N>            Result page (default: 1).
  --page-size <M>       Results per page (default: 20).
  --home <DIR>          Override SpringDep home (manifest lookup; index target).
  --direct              Disable transitive/meta-annotation expansion.
  --indexer <PATH>      (index) Path to springdep-index; auto-located otherwise.
  --registry <URL>      (index) Shard registry base URL; missing shards download
                        instead of extracting locally. Env: SPRINGDEP_REGISTRY.
  --registry-pubkey <H> (index) Hex ed25519 key; downloaded shards must carry a
                        valid signature. Env: SPRINGDEP_REGISTRY_PUBKEY.
  --version             Print the springdep version.
  -h, --help            Show this help.

Notes:
  find-by-annotation expands meta-annotations (e.g. @Component finds @Service classes)
  but does not merge @AliasFor attribute aliases. find-implementations walks declared
  extends/implements chains across all indexed jars.`)
}
