// Package depgraph captures and stores Maven dependency provenance for a
// project. The index facts still come from jar bytes; this file explains why a
// jar is present on the classpath.
package depgraph

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

const (
	SchemaVersion = 1
	fileName      = "dependencies.json"
)

var ErrUnsupported = errors.New("dependency provenance is currently only available for Maven projects")

// Graph is written to .springdep/dependencies.json.
type Graph struct {
	SchemaVersion int        `json:"schema_version"`
	BuildTool     string     `json:"build_tool"`
	GeneratedAt   int64      `json:"generated_at"`
	ProjectRoot   string     `json:"project_root,omitempty"`
	Artifacts     []Artifact `json:"artifacts"`
}

// Artifact describes one resolved Maven node and its path from the project.
type Artifact struct {
	Coordinate string   `json:"coordinate"`
	Artifact   string   `json:"artifact"`
	Group      string   `json:"group"`
	Name       string   `json:"name"`
	Version    string   `json:"version"`
	Type       string   `json:"type,omitempty"`
	Classifier string   `json:"classifier,omitempty"`
	Scope      string   `json:"scope,omitempty"`
	Parent     string   `json:"parent,omitempty"`
	Direct     bool     `json:"direct"`
	Depth      int      `json:"depth"`
	Path       []string `json:"path"`
}

type node struct {
	id       string
	raw      string
	artifact Artifact
}

// Path returns the dependency provenance file for a project root.
func Path(projectDir string) string {
	return filepath.Join(projectDir, ".springdep", fileName)
}

// Load reads a previously captured graph.
func Load(projectDir string) (Graph, error) {
	file, err := os.Open(Path(projectDir))
	if err != nil {
		if os.IsNotExist(err) {
			return Graph{}, ErrUnsupported
		}
		return Graph{}, err
	}
	defer file.Close()
	var graph Graph
	if err := json.NewDecoder(file).Decode(&graph); err != nil {
		return Graph{}, err
	}
	if graph.SchemaVersion != SchemaVersion {
		return Graph{}, fmt.Errorf("unsupported dependency graph schema version %d", graph.SchemaVersion)
	}
	return graph, nil
}

// Write stores the graph under .springdep/dependencies.json.
func Write(projectDir string, graph Graph) error {
	directory := filepath.Join(projectDir, ".springdep")
	if err := os.MkdirAll(directory, 0o755); err != nil {
		return err
	}
	graph.SchemaVersion = SchemaVersion
	if graph.GeneratedAt == 0 {
		graph.GeneratedAt = time.Now().Unix()
	}
	formatted, err := json.MarshalIndent(graph, "", "  ")
	if err != nil {
		return err
	}
	target := filepath.Join(directory, fileName)
	temp, err := os.CreateTemp(directory, "."+fileName+".*.tmp")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	defer os.Remove(tempPath)
	if _, err := temp.Write(append(formatted, '\n')); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempPath, target)
}

// Capture runs Maven's dependency tree in TGF form and returns a graph. It is
// best-effort for callers: unsupported build tools are reported separately so
// indexing can continue without provenance.
func Capture(projectDir string) (Graph, error) {
	absolute, err := filepath.Abs(projectDir)
	if err != nil {
		return Graph{}, err
	}
	if !fileExists(filepath.Join(absolute, "pom.xml")) {
		return Graph{}, ErrUnsupported
	}
	output, err := os.CreateTemp("", "springdep-dependency-tree-*.tgf")
	if err != nil {
		return Graph{}, err
	}
	outputPath := output.Name()
	output.Close()
	defer os.Remove(outputPath)

	command := buildCommand(
		absolute, "mvnw", "mvn",
		"-q", "-Dscope=runtime", "dependency:tree",
		"-DoutputType=tgf", "-DoutputFile="+outputPath,
	)
	if combined, err := command.CombinedOutput(); err != nil {
		return Graph{}, fmt.Errorf("maven dependency tree failed: %w\n%s", err, tail(string(combined)))
	}
	file, err := os.Open(outputPath)
	if err != nil {
		return Graph{}, fmt.Errorf("read maven dependency tree: %w", err)
	}
	defer file.Close()
	graph, err := ParseTGF(file)
	if err != nil {
		return Graph{}, err
	}
	graph.ProjectRoot = absolute
	graph.GeneratedAt = time.Now().Unix()
	return graph, nil
}

// ParseTGF parses Maven dependency:tree -DoutputType=tgf output.
func ParseTGF(reader io.Reader) (Graph, error) {
	nodes := map[string]node{}
	children := map[string][]string{}
	parents := map[string]string{}
	scanner := bufio.NewScanner(reader)
	line := 0
	inEdges := false
	for scanner.Scan() {
		line++
		text := strings.TrimSpace(scanner.Text())
		if text == "" {
			continue
		}
		if text == "#" {
			inEdges = true
			continue
		}
		if !inEdges {
			id, raw, found := strings.Cut(text, " ")
			if !found || id == "" || raw == "" {
				return Graph{}, fmt.Errorf("line %d: malformed TGF node %q", line, text)
			}
			artifact, err := parseMavenCoordinate(raw)
			if err != nil {
				return Graph{}, fmt.Errorf("line %d: %w", line, err)
			}
			nodes[id] = node{id: id, raw: raw, artifact: artifact}
			continue
		}
		parts := strings.Fields(text)
		if len(parts) < 2 {
			return Graph{}, fmt.Errorf("line %d: malformed TGF edge %q", line, text)
		}
		parent, child := parts[0], parts[1]
		children[parent] = append(children[parent], child)
		if parents[child] == "" {
			parents[child] = parent
		}
	}
	if err := scanner.Err(); err != nil {
		return Graph{}, err
	}
	if len(nodes) == 0 {
		return Graph{}, errors.New("maven dependency tree was empty")
	}

	rootIDs := []string{}
	for id := range nodes {
		if parents[id] == "" {
			rootIDs = append(rootIDs, id)
		}
	}
	sort.Strings(rootIDs)
	rootSet := map[string]bool{}
	for _, id := range rootIDs {
		rootSet[id] = true
	}

	artifacts := make([]Artifact, 0, len(nodes))
	for id, item := range nodes {
		artifact := item.artifact
		parentID := parents[id]
		if parentID != "" {
			artifact.Parent = nodes[parentID].artifact.Coordinate
		}
		artifact.Direct = parentID != "" && rootSet[parentID]
		artifact.Path = pathFor(id, parents, nodes, rootSet)
		artifact.Depth = len(artifact.Path)
		artifacts = append(artifacts, artifact)
	}
	sort.Slice(artifacts, func(i, j int) bool {
		if artifacts[i].Depth != artifacts[j].Depth {
			return artifacts[i].Depth < artifacts[j].Depth
		}
		return artifacts[i].Coordinate < artifacts[j].Coordinate
	})
	return Graph{
		SchemaVersion: SchemaVersion,
		BuildTool:     "maven",
		Artifacts:     artifacts,
	}, nil
}

func parseMavenCoordinate(raw string) (Artifact, error) {
	parts := strings.Split(strings.TrimSpace(raw), ":")
	if len(parts) != 4 && len(parts) != 5 && len(parts) != 6 {
		return Artifact{}, fmt.Errorf("%q is not a Maven coordinate", raw)
	}
	artifact := Artifact{
		Group: parts[0],
		Name:  parts[1],
		Type:  parts[2],
	}
	switch len(parts) {
	case 4:
		artifact.Version = parts[3]
	case 5:
		artifact.Version = parts[3]
		artifact.Scope = parts[4]
	case 6:
		artifact.Classifier = parts[3]
		artifact.Version = parts[4]
		artifact.Scope = parts[5]
	}
	if artifact.Group == "" || artifact.Name == "" || artifact.Version == "" {
		return Artifact{}, fmt.Errorf("%q is not a Maven coordinate", raw)
	}
	artifact.Artifact = artifact.Group + ":" + artifact.Name
	artifact.Coordinate = artifact.Artifact + ":" + artifact.Version
	return artifact, nil
}

func pathFor(id string, parents map[string]string, nodes map[string]node, rootSet map[string]bool) []string {
	reversed := []string{}
	for current := id; current != ""; current = parents[current] {
		if rootSet[current] {
			break
		}
		item, ok := nodes[current]
		if !ok {
			break
		}
		reversed = append(reversed, item.artifact.Coordinate)
	}
	path := make([]string, 0, len(reversed))
	for index := len(reversed) - 1; index >= 0; index-- {
		path = append(path, reversed[index])
	}
	return path
}

func buildCommand(projectDir, wrapper, fallback string, args ...string) *exec.Cmd {
	var command *exec.Cmd
	if runtime.GOOS == "windows" {
		wrapperPath := filepath.Join(projectDir, wrapper+".bat")
		if !fileExists(wrapperPath) {
			wrapperPath = filepath.Join(projectDir, wrapper+".cmd")
		}
		if fileExists(wrapperPath) {
			command = exec.Command("cmd", append([]string{"/c", wrapperPath}, args...)...)
		} else {
			command = exec.Command(fallback, args...)
		}
	} else {
		wrapperPath := filepath.Join(projectDir, wrapper)
		if fileExists(wrapperPath) {
			command = exec.Command(wrapperPath, args...)
		} else {
			command = exec.Command(fallback, args...)
		}
	}
	command.Dir = projectDir
	return command
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

func tail(output string) string {
	lines := strings.Split(strings.TrimSpace(output), "\n")
	if len(lines) > 20 {
		lines = lines[len(lines)-20:]
	}
	return strings.Join(lines, "\n")
}
