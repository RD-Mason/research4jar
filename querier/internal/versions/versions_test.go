package versions

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"testing"
)

// TestExtractorMatchesKotlin parses SpringDepVersions.kt so the Go and Kotlin
// halves cannot drift apart silently. Skips when the Kotlin tree is absent
// (e.g. the querier module is vendored elsewhere).
func TestExtractorMatchesKotlin(t *testing.T) {
	kotlinPath := filepath.Join(
		"..", "..", "..",
		"indexer", "src", "main", "kotlin", "dev", "springdep", "indexer",
		"SpringDepVersions.kt",
	)
	content, err := os.ReadFile(kotlinPath)
	if err != nil {
		t.Skipf("Kotlin sources not available: %v", err)
	}
	pattern := regexp.MustCompile(`const val EXTRACTOR = (\d+)`)
	match := pattern.FindSubmatch(content)
	if match == nil {
		t.Fatal("EXTRACTOR constant not found in SpringDepVersions.kt")
	}
	kotlinValue, err := strconv.Atoi(string(match[1]))
	if err != nil {
		t.Fatal(err)
	}
	if kotlinValue != Extractor {
		t.Fatalf(
			"extractor version drift: Go versions.Extractor=%d, Kotlin SpringDepVersions.EXTRACTOR=%d",
			Extractor, kotlinValue,
		)
	}
}
