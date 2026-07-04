package versions

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"testing"
)

// TestExtractorMatchesKotlin parses Research4JarVersions.kt so the Go and Kotlin
// halves cannot drift apart silently. Skips when the Kotlin tree is absent
// (e.g. the querier module is vendored elsewhere).
func TestExtractorMatchesKotlin(t *testing.T) {
	kotlinPath := filepath.Join(
		"..", "..", "..",
		"core", "src", "main", "kotlin", "dev", "research4jar", "indexer",
		"Research4JarVersions.kt",
	)
	content, err := os.ReadFile(kotlinPath)
	if err != nil {
		t.Skipf("Kotlin sources not available: %v", err)
	}
	constants := map[string]int{
		"EXTRACTOR": Extractor,
		"SCHEMA":    Schema,
		"SESSION":   Session,
	}
	for name, goValue := range constants {
		pattern := regexp.MustCompile(`const val ` + name + ` = (\d+)`)
		match := pattern.FindSubmatch(content)
		if match == nil {
			t.Fatalf("%s constant not found in Research4JarVersions.kt", name)
		}
		kotlinValue, err := strconv.Atoi(string(match[1]))
		if err != nil {
			t.Fatal(err)
		}
		if kotlinValue != goValue {
			t.Fatalf(
				"version drift: Go versions.%s=%d, Kotlin Research4JarVersions.%s=%d",
				name, goValue, name, kotlinValue,
			)
		}
	}
}
