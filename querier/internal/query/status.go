package query

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"dev.research4jar/querier/internal/depgraph"
	springpaths "dev.research4jar/querier/internal/paths"
	"dev.research4jar/querier/internal/project"
)

type DependencyProvenanceStatus struct {
	Available   bool   `json:"available"`
	Path        string `json:"path,omitempty"`
	BuildTool   string `json:"build_tool,omitempty"`
	Artifacts   int    `json:"artifacts,omitempty"`
	GeneratedAt int64  `json:"generated_at,omitempty"`
}

type ProjectStatusResponse struct {
	Indexed              bool                       `json:"indexed"`
	ProjectDir           string                     `json:"project_dir"`
	ProjectIndexPath     string                     `json:"project_index_path,omitempty"`
	SchemaVersion        int                        `json:"schema_version,omitempty"`
	ExtractorVersion     int                        `json:"extractor_version,omitempty"`
	ClasspathFingerprint string                     `json:"classpath_fingerprint,omitempty"`
	SessionDBPath        string                     `json:"session_db_path,omitempty"`
	SessionDBExists      bool                       `json:"session_db_exists"`
	BuiltAt              int64                      `json:"built_at,omitempty"`
	BuiltAtUTC           string                     `json:"built_at_utc,omitempty"`
	Coverage             Coverage                   `json:"coverage"`
	ManifestPath         string                     `json:"manifest_path,omitempty"`
	ManifestExists       bool                       `json:"manifest_exists"`
	DependencyProvenance DependencyProvenanceStatus `json:"dependency_provenance"`
}

func ProjectStatus(projectDir, home string) (ProjectStatusResponse, error) {
	status := ProjectStatusResponse{}
	projectPath, err := project.Locate(projectDir)
	if errors.Is(err, project.ErrNotFound) {
		root, rootErr := statusProjectDir(projectDir)
		if rootErr != nil {
			return ProjectStatusResponse{}, rootErr
		}
		status.ProjectDir = root
		status.Coverage = Coverage{JarsMissing: []string{}}
		status.DependencyProvenance = dependencyProvenanceStatus(root)
		return status, nil
	}
	if err != nil {
		return ProjectStatusResponse{}, err
	}

	pointer, err := project.Load(projectPath)
	if err != nil {
		return ProjectStatusResponse{}, err
	}
	root := filepath.Dir(filepath.Dir(projectPath))
	manifestPath := InferManifestPath(pointer.SessionDBPath)
	if home != "" {
		dataPaths, err := springpaths.Resolve(home)
		if err != nil {
			return ProjectStatusResponse{}, fmt.Errorf("resolve home: %w", err)
		}
		manifestPath = dataPaths.Manifest
	}

	status = ProjectStatusResponse{
		Indexed:              true,
		ProjectDir:           root,
		ProjectIndexPath:     projectPath,
		SchemaVersion:        pointer.SchemaVersion,
		ExtractorVersion:     pointer.ExtractorVersion,
		ClasspathFingerprint: pointer.ClasspathFingerprint,
		SessionDBPath:        pointer.SessionDBPath,
		SessionDBExists:      regularFile(pointer.SessionDBPath),
		BuiltAt:              pointer.BuiltAt,
		Coverage:             coverageFrom(pointer),
		ManifestPath:         manifestPath,
		ManifestExists:       regularFile(manifestPath),
		DependencyProvenance: dependencyProvenanceStatus(root),
	}
	if pointer.BuiltAt > 0 {
		status.BuiltAtUTC = time.Unix(pointer.BuiltAt, 0).UTC().Format(time.RFC3339)
	}
	return status, nil
}

func statusProjectDir(projectDir string) (string, error) {
	if projectDir != "" {
		return filepath.Abs(projectDir)
	}
	return os.Getwd()
}

func dependencyProvenanceStatus(projectDir string) DependencyProvenanceStatus {
	path := depgraph.Path(projectDir)
	graph, err := depgraph.Load(projectDir)
	if err != nil {
		return DependencyProvenanceStatus{Path: path}
	}
	return DependencyProvenanceStatus{
		Available:   true,
		Path:        path,
		BuildTool:   graph.BuildTool,
		Artifacts:   len(graph.Artifacts),
		GeneratedAt: graph.GeneratedAt,
	}
}

func regularFile(path string) bool {
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}
