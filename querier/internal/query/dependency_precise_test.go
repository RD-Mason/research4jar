package query

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDependencyPreciseResolvesImportAndSourceUsage(t *testing.T) {
	projectDir, sessionPath, manifestPath := dependencyFixture(t)
	sourcePath := filepath.Join(projectDir, "src", "main", "java", "example", "App.java")
	if err := os.MkdirAll(filepath.Dir(sourcePath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		sourcePath,
		[]byte("package example;\n\nimport other.DirectImplementation;\n\nclass App {}\n"),
		0o644,
	); err != nil {
		t.Fatal(err)
	}

	response, err := DependencyPrecise(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		projectDir,
		"import other.DirectImplementation;",
		20,
		true,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.InputKind != "import" || response.Normalized != "other.DirectImplementation" {
		t.Fatalf("unexpected lookup echo: %#v", response)
	}
	if response.Total != 1 || response.Origins[0].FQN != "other.DirectImplementation" ||
		response.Origins[0].Coordinate != "com.example:implementation:1.0" {
		t.Fatalf("unexpected origins: %#v", response.Origins)
	}
	if response.DependenciesTotal != 1 ||
		response.Dependencies[0].DirectDependency != "com.example:api:1.0" ||
		response.Dependencies[0].Parent != "com.example:api:1.0" {
		t.Fatalf("unexpected dependency path: %#v", response.Dependencies)
	}
	if len(response.SourceUsages) != 1 ||
		response.SourceUsages[0].Path != "src/main/java/example/App.java" ||
		response.SourceUsages[0].Line != 3 {
		t.Fatalf("unexpected source usages: %#v", response.SourceUsages)
	}
}

func TestDependencyPreciseFindsArtifactID(t *testing.T) {
	projectDir, sessionPath, manifestPath := dependencyFixture(t)
	response, err := DependencyPrecise(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		projectDir,
		"implementation",
		20,
		false,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Total != 1 ||
		response.Origins[0].Coordinate != "com.example:implementation:1.0" ||
		response.Origins[0].MatchReason != "artifact" {
		t.Fatalf("unexpected artifact origin: %#v", response.Origins)
	}
	if response.DependenciesTotal != 1 || response.Dependencies[0].Depth != 2 {
		t.Fatalf("unexpected dependency result: %#v", response.Dependencies)
	}
}

func TestClassPreciseFindsClassOriginAndDoesNotFallbackToArtifact(t *testing.T) {
	projectDir, sessionPath, manifestPath := dependencyFixture(t)
	response, err := ClassPrecise(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		projectDir,
		"DirectImplementation",
		20,
		false,
	)
	if err != nil {
		t.Fatal(err)
	}
	if response.Query.Command != "class" ||
		response.Total != 1 ||
		response.Origins[0].FQN != "other.DirectImplementation" ||
		response.Origins[0].Coordinate != "com.example:implementation:1.0" {
		t.Fatalf("unexpected class origin response: %#v", response)
	}

	noClass, err := ClassPrecise(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		projectDir,
		"implementation",
		20,
		false,
	)
	if err != nil {
		t.Fatal(err)
	}
	if noClass.Total != 0 || noClass.DependenciesTotal != 0 {
		t.Fatalf("class lookup should not fall back to artifact lookup: %#v", noClass)
	}
}

func TestSourceUsagesPreferHighSignalImportsAndSkipLowValueFiles(t *testing.T) {
	projectDir, sessionPath, manifestPath := dependencyFixture(t)
	writeTestFile(t,
		filepath.Join(projectDir, "src", "main", "java", "example", "Use.java"),
		"package example;\nclass Use { DirectImplementation field; }\n",
	)
	writeTestFile(t,
		filepath.Join(projectDir, "src", "main", "java", "example", "Import.java"),
		"package example;\nimport other.DirectImplementation;\nclass Import {}\n",
	)
	writeTestFile(t,
		filepath.Join(projectDir, "target", "generated-sources", "Skip.java"),
		"import other.DirectImplementation;\n",
	)
	writeTestFile(t,
		filepath.Join(projectDir, "src", "main", "java", "example", "Huge.java"),
		"import other.DirectImplementation;\n"+strings.Repeat("x", maxSourceUsageFileBytes),
	)

	response, err := ClassPrecise(
		context.Background(),
		symbolPointer(sessionPath),
		manifestPath,
		projectDir,
		"DirectImplementation",
		20,
		true,
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(response.SourceUsages) != 2 {
		t.Fatalf("unexpected source usages: %#v", response.SourceUsages)
	}
	if response.SourceUsages[0].Path != "src/main/java/example/Import.java" ||
		response.SourceUsages[0].Match != "import other.DirectImplementation" {
		t.Fatalf("first usage should be the high-signal import: %#v", response.SourceUsages)
	}
	if response.SourceUsages[1].Path != "src/main/java/example/Use.java" {
		t.Fatalf("broad symbol usage should follow imports: %#v", response.SourceUsages)
	}
	for _, usage := range response.SourceUsages {
		if strings.Contains(usage.Path, "target") || strings.Contains(usage.Path, "Huge.java") {
			t.Fatalf("low-value file should have been skipped: %#v", response.SourceUsages)
		}
	}
}

func writeTestFile(t *testing.T, path string, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
