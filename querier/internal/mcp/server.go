// Package mcp implements a minimal Model Context Protocol server over stdio
// (newline-delimited JSON-RPC 2.0), exposing every springdep command as a
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

	"dev.springdep/querier/internal/indexer"
	"dev.springdep/querier/internal/project"
	"dev.springdep/querier/internal/query"
)

const (
	protocolVersion = "2024-11-05"
	serverName      = "springdep"
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
	ProjectDir string `json:"project_dir"`
	Home       string `json:"home"`
	Jars       string `json:"jars"`
	Indexer    string `json:"indexer"`
	Prefix     string `json:"prefix"`
	FQN        string `json:"fqn"`
	Text       string `json:"text"`
	Arg        string `json:"arg"`
	Direct     bool   `json:"direct"`
	Page       int    `json:"page"`
	PageSize   int    `json:"page_size"`
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
			"No SpringDep index found for this project. " +
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
	if name == "index_project" {
		return runIndex(arguments)
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
	default:
		return nil, fmt.Errorf("unknown tool: %s", name)
	}
}

func runIndex(arguments toolArguments) (any, error) {
	indexerBin, err := indexer.Locate(arguments.Indexer)
	if err != nil {
		return nil, err
	}
	projectDir := arguments.ProjectDir
	if projectDir == "" {
		if projectDir, err = os.Getwd(); err != nil {
			return nil, err
		}
	}
	if err := indexer.Run(indexerBin, arguments.Jars, projectDir, arguments.Home); err != nil {
		return nil, fmt.Errorf("indexing failed: %w", err)
	}
	return map[string]any{
		"status":      "indexed",
		"project_dir": projectDir,
		"note":        "Project pointer written to .springdep/project.json; query tools are ready.",
	}, nil
}

func toolError(message string) map[string]any {
	return map[string]any{
		"content": []map[string]any{{"type": "text", "text": message}},
		"isError": true,
	}
}

func toolCatalog() []toolDefinition {
	projectDir := map[string]any{
		"type": "string",
		"description": "Spring project root (directory containing .springdep). " +
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
	}
}
