package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"text/tabwriter"

	"dev.springdep/querier/internal/indexer"
	"dev.springdep/querier/internal/mcp"
	"dev.springdep/querier/internal/project"
	"dev.springdep/querier/internal/query"
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
	case command == "mcp":
		if err := mcp.Serve(os.Stdin, os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, "springdep mcp:", err)
			os.Exit(1)
		}
		return
	case command == "index":
		runIndexCommand(os.Args[2:])
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
	for index := 0; index < len(args); index++ {
		argument := args[index]
		switch argument {
		case "--jars", "--project-dir", "--home", "--indexer":
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
	if err := indexer.Run(indexerBin, opts.jars, opts.projectDir, opts.home); err != nil {
		fail("index_error", err.Error(), 1)
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
  -h, --help            Show this help.

Notes:
  find-by-annotation expands meta-annotations (e.g. @Component finds @Service classes)
  but does not merge @AliasFor attribute aliases. find-implementations walks declared
  extends/implements chains across all indexed jars.`)
}
