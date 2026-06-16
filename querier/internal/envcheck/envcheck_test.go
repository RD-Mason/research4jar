package envcheck

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestParseJavaMajor(t *testing.T) {
	cases := map[string]int{
		`openjdk version "17.0.11" 2024-04-16`: 17,
		`java version "1.8.0_412"`:             8,
		`javac 21.0.2`:                         21,
	}
	for input, want := range cases {
		got, ok := parseJavaMajor(input)
		if !ok || got != want {
			t.Fatalf("parseJavaMajor(%q) = %d, %t; want %d, true", input, got, ok, want)
		}
	}
}

func TestParseGoVersion(t *testing.T) {
	major, minor, ok := parseGoVersion("go version go1.23.4 darwin/arm64")
	if !ok || major != 1 || minor != 23 {
		t.Fatalf("parseGoVersion() = %d.%d, %t; want 1.23, true", major, minor, ok)
	}
}

func TestRunReportsMissingMavenWithAgentGuidance(t *testing.T) {
	project := t.TempDir()
	if err := os.WriteFile(filepath.Join(project, "pom.xml"), []byte("<project/>"), 0o644); err != nil {
		t.Fatal(err)
	}
	report := testInspector(map[string]string{
		"java": "/usr/bin/java",
	}).Run(Options{ProjectDir: project})

	if report.OK {
		t.Fatal("expected report to fail when Maven is missing")
	}
	check := findCheck(report, "maven")
	if check == nil {
		t.Fatal("maven check not found")
	}
	if check.Status != StatusMissing {
		t.Fatalf("maven status = %s, want missing", check.Status)
	}
	if len(check.AgentInstall) == 0 {
		t.Fatal("expected agent install guidance")
	}
	if len(check.Verify) == 0 {
		t.Fatal("expected verification command")
	}
}

func TestRunSourceBuildChecksGoVersion(t *testing.T) {
	project := t.TempDir()
	if err := os.WriteFile(filepath.Join(project, "gradlew"), []byte("#!/usr/bin/env sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	report := testInspector(map[string]string{
		"java":  "/usr/bin/java",
		"javac": "/usr/bin/javac",
		"go":    "/usr/local/bin/go",
		"make":  "/usr/bin/make",
		"bash":  "/bin/bash",
	}).Run(Options{ProjectDir: project, SourceBuild: true})

	check := findCheck(report, "go")
	if check == nil {
		t.Fatal("go check not found")
	}
	if check.Status != StatusMissing {
		t.Fatalf("go status = %s, want missing for old Go", check.Status)
	}
	if check.Minimum != "1.23" {
		t.Fatalf("go minimum = %q, want 1.23", check.Minimum)
	}
}

func testInspector(paths map[string]string) inspector {
	return inspector{
		goos: "darwin",
		lookup: func(name string) (string, error) {
			path, ok := paths[name]
			if !ok {
				return "", errors.New("not found")
			}
			return path, nil
		},
		locateIndexer: func() (string, error) {
			return "/usr/local/bin/research4jar-index", nil
		},
		run: func(name string, args ...string) (string, error) {
			switch name {
			case "java":
				return `openjdk version "17.0.11" 2024-04-16`, nil
			case "javac":
				return "javac 17.0.11", nil
			case "go":
				return "go version go1.22.9 darwin/arm64", nil
			default:
				return name + " version", nil
			}
		},
	}
}

func findCheck(report Report, id string) *Check {
	for index := range report.Checks {
		if report.Checks[index].ID == id {
			return &report.Checks[index]
		}
	}
	return nil
}
