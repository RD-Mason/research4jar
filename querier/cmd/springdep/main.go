package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"
	"text/tabwriter"

	springpaths "dev.springdep/querier/internal/paths"
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
}

type errorResponse struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

func main() {
	if len(os.Args) == 1 || os.Args[1] == "--help" || os.Args[1] == "-h" {
		printHelp()
		return
	}
	command := os.Args[1]
	if command != "find-config-properties" &&
		command != "find-implementations" &&
		command != "find-by-annotation" {
		fail("invalid_command", "未知命令："+command, 2)
	}

	opts, err := parseOptions(os.Args[2:])
	if err != nil {
		fail("invalid_arguments", err.Error(), 2)
	}
	projectPath, err := project.Locate(opts.projectDir)
	if errors.Is(err, project.ErrNotFound) {
		fail(
			"no_project_index",
			"未找到 .springdep/project.json；请先运行 springdep-index --project-dir <path>",
			1,
		)
	}
	if err != nil {
		fail("project_index_error", err.Error(), 1)
	}
	pointer, err := project.Load(projectPath)
	if err != nil {
		fail("project_index_error", err.Error(), 1)
	}

	manifestPath := query.InferManifestPath(pointer.SessionDBPath)
	if opts.home != "" {
		dataPaths, resolveErr := springpaths.Resolve(opts.home)
		if resolveErr != nil {
			fail("path_error", resolveErr.Error(), 1)
		}
		manifestPath = dataPaths.Manifest
	}

	var response any
	switch command {
	case "find-config-properties":
		response, err = query.FindConfigProperties(
			context.Background(),
			pointer,
			manifestPath,
			opts.arg,
			opts.page,
			opts.pageSize,
		)
	case "find-implementations":
		response, err = query.FindImplementations(
			context.Background(),
			pointer,
			manifestPath,
			opts.arg,
			opts.page,
			opts.pageSize,
		)
	case "find-by-annotation":
		response, err = query.FindByAnnotation(
			context.Background(),
			pointer,
			manifestPath,
			opts.arg,
			opts.page,
			opts.pageSize,
		)
	}
	if err != nil {
		fail("query_error", err.Error(), 1)
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

func parseOptions(args []string) (options, error) {
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
	if result.arg == "" {
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
	fmt.Fprintln(writer, "FQN\tSOURCE JAR\tATTRIBUTES")
	for _, result := range response.Results {
		attributes := "-"
		if len(result.Attributes) > 0 {
			attributes = string(result.Attributes)
		}
		fmt.Fprintf(writer, "%s\t%s\t%s\n", result.FQN, result.SourceJar, attributes)
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
  springdep find-config-properties <PREFIX> [options]
  springdep find-implementations <INTERFACE-OR-CLASS-FQN> [options]
  springdep find-by-annotation <ANNOTATION-FQN> [options]

Options:
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --format json|text    Output format (default: json).
  --page <N>            Result page (default: 1).
  --page-size <M>       Results per page (default: 20).
  --home <DIR>          Override SpringDep home for manifest lookup.
  -h, --help            Show this help.

M1 limitations:
  find-implementations matches only directly declared implements/extends relationships.
  find-by-annotation matches only annotations directly present on a class. It does not
  expand meta-annotations, so querying @Component does not include @Service,
  @Repository, or @Controller classes. Transitive and meta-annotation traversal is M2.`)
}
