package query

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"dev.research4jar/querier/internal/depgraph"
)

func TestProjectStatusReportsIndexedProject(t *testing.T) {
	projectDir, sessionPath, _ := dependencyFixture(t)
	manifestPath := filepath.Join(filepath.Dir(filepath.Dir(sessionPath)), "manifest.db")
	pointerPath := filepath.Join(projectDir, ".research4jar", "project.json")
	if err := os.WriteFile(pointerPath, []byte(`{
	  "schema_version": 2,
	  "extractor_version": 2,
	  "classpath_fingerprint": "abc123",
	  "session_db_path": "`+filepath.ToSlash(sessionPath)+`",
	  "built_at": 1700000000,
	  "coverage": {"jars_total": 3, "jars_indexed": 3, "jars_missing": []}
	}`), 0o644); err != nil {
		t.Fatal(err)
	}

	status, err := ProjectStatus(projectDir, "")
	if err != nil {
		t.Fatal(err)
	}
	if !status.Indexed ||
		status.ProjectDir != projectDir ||
		status.ProjectIndexPath != pointerPath ||
		status.SessionDBPath != sessionPath ||
		!status.SessionDBExists ||
		status.ManifestPath != manifestPath ||
		!status.ManifestExists ||
		status.BuiltAtUTC != "2023-11-14T22:13:20Z" {
		t.Fatalf("unexpected status: %#v", status)
	}
	if !status.DependencyProvenance.Available ||
		status.DependencyProvenance.BuildTool != "maven" ||
		status.DependencyProvenance.Artifacts != 3 {
		t.Fatalf("unexpected dependency provenance: %#v", status.DependencyProvenance)
	}
	if len(status.NextSteps) != 1 ||
		!strings.Contains(status.NextSteps[0], "research4jar dep precise") {
		t.Fatalf("unexpected next steps: %#v", status.NextSteps)
	}
}

func TestProjectStatusReportsUnindexedProject(t *testing.T) {
	projectDir := t.TempDir()
	if err := depgraph.Write(projectDir, depgraph.Graph{BuildTool: "maven"}); err != nil {
		t.Fatal(err)
	}
	status, err := ProjectStatus(projectDir, "")
	if err != nil {
		t.Fatal(err)
	}
	if status.Indexed ||
		status.ProjectDir != projectDir ||
		status.SessionDBExists ||
		status.ManifestExists ||
		status.Coverage.JarsMissing == nil {
		t.Fatalf("unexpected unindexed status: %#v", status)
	}
	if !status.DependencyProvenance.Available {
		t.Fatalf("dependency provenance should still be reported: %#v", status)
	}
	if len(status.NextSteps) != 2 ||
		!strings.Contains(status.NextSteps[0], "research4jar index") ||
		!strings.Contains(status.NextSteps[1], "research4jar doctor") {
		t.Fatalf("unexpected next steps: %#v", status.NextSteps)
	}
}

func TestProjectStatusNextStepsReportMissingStoresAndProvenance(t *testing.T) {
	projectDir, sessionPath, manifestPath := dependencyFixture(t)
	if err := os.Remove(sessionPath); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(manifestPath); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(depgraph.Path(projectDir)); err != nil {
		t.Fatal(err)
	}
	pointerPath := filepath.Join(projectDir, ".research4jar", "project.json")
	if err := os.WriteFile(pointerPath, []byte(`{
	  "schema_version": 2,
	  "extractor_version": 2,
	  "classpath_fingerprint": "abc123",
	  "session_db_path": "`+filepath.ToSlash(sessionPath)+`",
	  "built_at": 1700000000,
	  "coverage": {"jars_total": 3, "jars_indexed": 2, "jars_missing": ["missing.jar"]}
	}`), 0o644); err != nil {
		t.Fatal(err)
	}

	status, err := ProjectStatus(projectDir, "")
	if err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(status.NextSteps, "\n")
	for _, expected := range []string{
		"session database",
		"manifest database",
		"coverage.jars_missing",
		"Dependency provenance is unavailable",
	} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("next steps missing %q: %#v", expected, status.NextSteps)
		}
	}
}

func TestProjectStatusCheckClasspathReportsStaleIndex(t *testing.T) {
	projectDir, sessionPath, _ := dependencyFixture(t)
	jarPath := filepath.Join(projectDir, "deps", "demo.jar")
	if err := os.MkdirAll(filepath.Dir(jarPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(jarPath, []byte("demo jar bytes"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(projectDir, "pom.xml"), []byte("<project/>"), 0o644); err != nil {
		t.Fatal(err)
	}
	fakeBin := fakeMavenBin(t, jarPath)
	t.Setenv("PATH", fakeBin+string(os.PathListSeparator)+os.Getenv("PATH"))

	pointerPath := filepath.Join(projectDir, ".research4jar", "project.json")
	if err := os.WriteFile(pointerPath, []byte(`{
	  "schema_version": 2,
	  "extractor_version": 2,
	  "classpath_fingerprint": "stale",
	  "session_db_path": "`+filepath.ToSlash(sessionPath)+`",
	  "built_at": 1700000000,
	  "coverage": {"jars_total": 1, "jars_indexed": 1, "jars_missing": []}
	}`), 0o644); err != nil {
		t.Fatal(err)
	}

	status, err := ProjectStatusWithOptions(
		projectDir, "", ProjectStatusOptions{CheckClasspath: true},
	)
	if err != nil {
		t.Fatal(err)
	}
	if status.ClasspathCheck == nil ||
		!status.ClasspathCheck.Checked ||
		status.ClasspathCheck.UpToDate ||
		status.ClasspathCheck.CurrentFingerprint == "" ||
		status.ClasspathCheck.IndexedFingerprint != "stale" ||
		status.ClasspathCheck.JarsResolved != 1 ||
		status.ClasspathCheck.JarsUnique != 1 {
		t.Fatalf("unexpected classpath check: %#v", status.ClasspathCheck)
	}
	if !strings.Contains(strings.Join(status.NextSteps, "\n"), "Runtime classpath changed") {
		t.Fatalf("next steps should report stale index: %#v", status.NextSteps)
	}
}

func TestFakeMavenHelper(t *testing.T) {
	if os.Getenv("RESEARCH4JAR_FAKE_MAVEN") != "1" {
		return
	}
	outputPath := ""
	for _, arg := range os.Args[1:] {
		if rest, found := strings.CutPrefix(arg, "-Dmdep.outputFile="); found {
			outputPath = rest
			break
		}
	}
	if outputPath == "" {
		os.Exit(2)
	}
	if err := os.WriteFile(outputPath, []byte(os.Getenv("RESEARCH4JAR_FAKE_CLASSPATH_JAR")), 0o644); err != nil {
		os.Exit(3)
	}
	os.Exit(0)
}

func fakeMavenBin(t *testing.T, jarPath string) string {
	t.Helper()
	binDir := filepath.Join(t.TempDir(), "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatal(err)
	}
	testBinary, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	name := "mvn"
	script := fmt.Sprintf(
		"#!/bin/sh\nRESEARCH4JAR_FAKE_MAVEN=1 RESEARCH4JAR_FAKE_CLASSPATH_JAR=%s %s -test.run=TestFakeMavenHelper -- \"$@\"\n",
		shellQuote(jarPath),
		shellQuote(testBinary),
	)
	if runtime.GOOS == "windows" {
		name = "mvn.bat"
		script = fmt.Sprintf(
			"@echo off\r\nset \"RESEARCH4JAR_FAKE_MAVEN=1\"\r\n"+
				"set \"RESEARCH4JAR_FAKE_CLASSPATH_JAR=%s\"\r\n\"%s\" -test.run=TestFakeMavenHelper -- %%*\r\n",
			jarPath,
			testBinary,
		)
	}
	path := filepath.Join(binDir, name)
	if err := os.WriteFile(path, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	return binDir
}

func shellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "'\\''") + "'"
}
