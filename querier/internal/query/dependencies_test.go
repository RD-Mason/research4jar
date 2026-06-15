package query

import (
	"context"
	"path/filepath"
	"testing"

	"dev.springdep/querier/internal/depgraph"
)

func dependencyFixture(t *testing.T) (string, string, string) {
	t.Helper()
	root := t.TempDir()
	projectDir := filepath.Join(root, "project")
	sessionPath := filepath.Join(root, "sessions", "fingerprint.db")
	manifestPath := filepath.Join(root, "manifest.db")
	createSymbolSession(t, sessionPath)
	createSymbolManifest(t, manifestPath)
	err := depgraph.Write(projectDir, depgraph.Graph{
		BuildTool: "maven",
		Artifacts: []depgraph.Artifact{
			{
				Coordinate: "com.example:app:1.0",
				Artifact:   "com.example:app",
				Group:      "com.example",
				Name:       "app",
				Version:    "1.0",
				Depth:      0,
			},
			{
				Coordinate: "com.example:api:1.0",
				Artifact:   "com.example:api",
				Group:      "com.example",
				Name:       "api",
				Version:    "1.0",
				Scope:      "compile",
				Direct:     true,
				Depth:      1,
				Parent:     "com.example:app:1.0",
				Path:       []string{"com.example:api:1.0"},
			},
			{
				Coordinate: "com.example:implementation:1.0",
				Artifact:   "com.example:implementation",
				Group:      "com.example",
				Name:       "implementation",
				Version:    "1.0",
				Scope:      "runtime",
				Depth:      2,
				Parent:     "com.example:api:1.0",
				Path: []string{
					"com.example:api:1.0",
					"com.example:implementation:1.0",
				},
			},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return projectDir, sessionPath, manifestPath
}

func TestWhyDependencyFromClassJarAndCoordinate(t *testing.T) {
	projectDir, sessionPath, manifestPath := dependencyFixture(t)
	pointer := symbolPointer(sessionPath)

	byClass, err := WhyDependency(
		context.Background(), pointer, manifestPath, projectDir, "other.DirectImplementation",
	)
	if err != nil {
		t.Fatal(err)
	}
	if byClass.Total != 1 ||
		byClass.Results[0].Coordinate != "com.example:implementation:1.0" ||
		byClass.Results[0].MatchedBy != "class" ||
		byClass.Results[0].Depth != 2 {
		t.Fatalf("unexpected class provenance: %#v", byClass)
	}

	byJar, err := WhyDependency(
		context.Background(), pointer, manifestPath, projectDir, "implementation.jar",
	)
	if err != nil {
		t.Fatal(err)
	}
	if byJar.Total != 1 || byJar.Results[0].MatchedBy != "jar_filename" {
		t.Fatalf("unexpected jar provenance: %#v", byJar)
	}

	byArtifact, err := WhyDependency(
		context.Background(), pointer, manifestPath, projectDir, "com.example:api",
	)
	if err != nil {
		t.Fatal(err)
	}
	if byArtifact.Total != 1 || !byArtifact.Results[0].Direct {
		t.Fatalf("unexpected coordinate provenance: %#v", byArtifact)
	}
}
