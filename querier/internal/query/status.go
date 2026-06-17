package query

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"dev.research4jar/querier/internal/classpath"
	"dev.research4jar/querier/internal/depgraph"
	springpaths "dev.research4jar/querier/internal/paths"
	"dev.research4jar/querier/internal/project"
	"dev.research4jar/querier/internal/session"
	"dev.research4jar/querier/internal/versions"
)

type DependencyProvenanceStatus struct {
	Available   bool   `json:"available"`
	Path        string `json:"path,omitempty"`
	BuildTool   string `json:"build_tool,omitempty"`
	Artifacts   int    `json:"artifacts,omitempty"`
	GeneratedAt int64  `json:"generated_at,omitempty"`
}

type ProjectStatusOptions struct {
	CheckClasspath bool
}

type ClasspathCheckStatus struct {
	Checked              bool     `json:"checked"`
	UpToDate             bool     `json:"up_to_date"`
	CurrentFingerprint   string   `json:"current_fingerprint,omitempty"`
	IndexedFingerprint   string   `json:"indexed_fingerprint,omitempty"`
	JarsResolved         int      `json:"jars_resolved,omitempty"`
	JarsUnique           int      `json:"jars_unique,omitempty"`
	JarsMissing          []string `json:"jars_missing,omitempty"`
	ExtractorVersion     int      `json:"extractor_version,omitempty"`
	DependencyResolution string   `json:"dependency_resolution,omitempty"`
	Error                string   `json:"error,omitempty"`
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
	ClasspathCheck       *ClasspathCheckStatus      `json:"classpath_check,omitempty"`
	NextSteps            []string                   `json:"next_steps,omitempty"`
}

func ProjectStatus(projectDir, home string) (ProjectStatusResponse, error) {
	return ProjectStatusWithOptions(projectDir, home, ProjectStatusOptions{})
}

func ProjectStatusWithOptions(
	projectDir, home string, options ProjectStatusOptions,
) (ProjectStatusResponse, error) {
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
		if options.CheckClasspath {
			check := checkClasspath(root, "")
			status.ClasspathCheck = &check
		}
		status.NextSteps = nextStepsForStatus(status, home)
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
	if options.CheckClasspath {
		check := checkClasspath(root, pointer.ClasspathFingerprint)
		status.ClasspathCheck = &check
	}
	status.NextSteps = nextStepsForStatus(status, home)
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

func nextStepsForStatus(status ProjectStatusResponse, home string) []string {
	indexCommand := statusCommand("research4jar index", status.ProjectDir, home)
	doctorCommand := statusCommand("research4jar doctor", status.ProjectDir, "")
	if !status.Indexed {
		return []string{
			"Create the project index: " + indexCommand,
			"If indexing fails, check the environment: " + doctorCommand,
		}
	}

	steps := []string{}
	missingStores := []string{}
	if !status.SessionDBExists {
		missingStores = append(missingStores, "session database")
	}
	if !status.ManifestExists {
		missingStores = append(missingStores, "manifest database")
	}
	if len(missingStores) > 0 {
		steps = append(
			steps,
			"Rebuild the index because the "+strings.Join(missingStores, " and ")+" is missing: "+indexCommand,
		)
	}
	if status.Coverage.JarsTotal > 0 && status.Coverage.JarsIndexed < status.Coverage.JarsTotal {
		steps = append(
			steps,
			"Some jars were not indexed; inspect coverage.jars_missing, fix unreadable jars, then rerun: "+indexCommand,
		)
	}
	if !status.DependencyProvenance.Available {
		steps = append(
			steps,
			"Dependency provenance is unavailable; Maven projects can recreate it with: "+indexCommand,
		)
	}
	if status.ClasspathCheck != nil && status.ClasspathCheck.Checked {
		if status.ClasspathCheck.Error != "" {
			steps = append(
				steps,
				"Classpath freshness check failed: "+status.ClasspathCheck.Error,
			)
		} else if !status.ClasspathCheck.UpToDate {
			steps = append(
				steps,
				"Runtime classpath changed since the last index; refresh it with: "+indexCommand,
			)
		}
	}
	if len(steps) == 0 {
		steps = append(
			steps,
			"Index is ready; try: research4jar dep precise '<import|class|coordinate|jar>'",
		)
	}
	return steps
}

func statusCommand(command string, projectDir string, home string) string {
	if projectDir != "" {
		command += fmt.Sprintf(" --project-dir %q", projectDir)
	}
	if home != "" {
		command += fmt.Sprintf(" --home %q", home)
	}
	return command
}

func checkClasspath(projectDir, indexedFingerprint string) ClasspathCheckStatus {
	status := ClasspathCheckStatus{
		Checked:            true,
		IndexedFingerprint: indexedFingerprint,
		ExtractorVersion:   versions.Extractor,
	}
	jars, err := classpath.Discover(projectDir)
	if err != nil {
		status.Error = err.Error()
		return status
	}
	status.DependencyResolution = "build_tool"
	status.JarsResolved = len(jars)
	if len(jars) == 0 {
		status.Error = "build tool resolved an empty runtime classpath"
		return status
	}

	seenHashes := map[string]bool{}
	shardIDs := []string{}
	missing := []string{}
	for _, jar := range jars {
		digest, err := fileSHA256(jar)
		if err != nil {
			missing = append(missing, filepath.Base(jar))
			continue
		}
		if seenHashes[digest] {
			continue
		}
		seenHashes[digest] = true
		shardIDs = append(shardIDs, digest+"@"+fmt.Sprint(versions.Extractor))
	}
	status.JarsUnique = len(shardIDs)
	status.JarsMissing = missing
	if len(missing) > 0 {
		status.Error = "some runtime classpath jars could not be read"
		return status
	}
	status.CurrentFingerprint = session.Fingerprint(shardIDs)
	status.UpToDate = indexedFingerprint != "" && status.CurrentFingerprint == indexedFingerprint
	return status
}

func fileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func regularFile(path string) bool {
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}
