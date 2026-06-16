package query

import (
	"os"
	"path/filepath"
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
}
