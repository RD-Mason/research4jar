package mcp

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

func roundTrip(t *testing.T, requests ...string) []map[string]any {
	t.Helper()
	input := strings.Join(requests, "\n") + "\n"
	var output bytes.Buffer
	if err := Serve(strings.NewReader(input), &output); err != nil {
		t.Fatal(err)
	}
	responses := []map[string]any{}
	for _, line := range strings.Split(strings.TrimSpace(output.String()), "\n") {
		if line == "" {
			continue
		}
		var decoded map[string]any
		if err := json.Unmarshal([]byte(line), &decoded); err != nil {
			t.Fatalf("invalid response line %q: %v", line, err)
		}
		responses = append(responses, decoded)
	}
	return responses
}

func TestInitializeAndToolsList(t *testing.T) {
	responses := roundTrip(
		t,
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}`,
		`{"jsonrpc":"2.0","method":"notifications/initialized"}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/list"}`,
	)
	if len(responses) != 2 {
		t.Fatalf("expected 2 responses (notification ignored), got %d", len(responses))
	}
	initResult := responses[0]["result"].(map[string]any)
	if initResult["protocolVersion"] != "2025-03-26" {
		t.Fatalf("protocol version not negotiated: %#v", initResult)
	}
	serverInfo := initResult["serverInfo"].(map[string]any)
	if serverInfo["name"] != "springdep" {
		t.Fatalf("unexpected server info: %#v", serverInfo)
	}
	tools := responses[1]["result"].(map[string]any)["tools"].([]any)
	names := map[string]bool{}
	for _, tool := range tools {
		names[tool.(map[string]any)["name"].(string)] = true
	}
	for _, required := range []string{
		"index_project", "find_config_properties", "find_implementations",
		"find_by_annotation", "get_class", "get_bean_definitions",
		"explain_conditional", "find_string", "list_extension_points",
		"find_class", "find_method", "list_packages", "search_symbols",
		"open_symbol", "why_dependency",
	} {
		if !names[required] {
			t.Fatalf("missing tool %q in %v", required, names)
		}
	}
}

func TestToolCallWithoutIndexReturnsGuidance(t *testing.T) {
	dir := t.TempDir()
	responses := roundTrip(
		t,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"find_config_properties","arguments":{"prefix":"spring.datasource","project_dir":"`+
			strings.ReplaceAll(dir, `\`, `\\`)+`"}}}`,
	)
	result := responses[0]["result"].(map[string]any)
	if result["isError"] != true {
		t.Fatalf("expected isError result: %#v", result)
	}
	text := result["content"].([]any)[0].(map[string]any)["text"].(string)
	if !strings.Contains(text, "index_project") {
		t.Fatalf("error should point at index_project: %q", text)
	}
}

func TestUnknownMethodReturnsError(t *testing.T) {
	responses := roundTrip(
		t,
		`{"jsonrpc":"2.0","id":9,"method":"resources/list"}`,
	)
	if responses[0]["error"] == nil {
		t.Fatalf("expected JSON-RPC error: %#v", responses[0])
	}
}
