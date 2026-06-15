package query

import (
	"fmt"

	springpaths "dev.research4jar/querier/internal/paths"
	"dev.research4jar/querier/internal/project"
)

// ResolveProject locates and loads the project pointer and derives the
// manifest path, honoring an optional explicit home override. Shared by the
// CLI commands and the MCP server.
func ResolveProject(projectDir, home string) (project.Pointer, string, error) {
	projectPath, err := project.Locate(projectDir)
	if err != nil {
		return project.Pointer{}, "", err
	}
	pointer, err := project.Load(projectPath)
	if err != nil {
		return project.Pointer{}, "", err
	}
	manifestPath := InferManifestPath(pointer.SessionDBPath)
	if home != "" {
		dataPaths, err := springpaths.Resolve(home)
		if err != nil {
			return project.Pointer{}, "", fmt.Errorf("resolve home: %w", err)
		}
		manifestPath = dataPaths.Manifest
	}
	return pointer, manifestPath, nil
}
