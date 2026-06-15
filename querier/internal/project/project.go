package project

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

var ErrNotFound = errors.New("no project index")

type Coverage struct {
	JarsTotal   int      `json:"jars_total"`
	JarsIndexed int      `json:"jars_indexed"`
	JarsMissing []string `json:"jars_missing"`
}

type Pointer struct {
	SchemaVersion        int      `json:"schema_version"`
	ExtractorVersion     int      `json:"extractor_version"`
	ClasspathFingerprint string   `json:"classpath_fingerprint"`
	SessionDBPath        string   `json:"session_db_path"`
	BuiltAt              int64    `json:"built_at"`
	Coverage             Coverage `json:"coverage"`
}

func Locate(projectDir string) (string, error) {
	if projectDir != "" {
		absolute, err := filepath.Abs(projectDir)
		if err != nil {
			return "", fmt.Errorf("resolve project directory: %w", err)
		}
		candidate := filepath.Join(absolute, ".research4jar", "project.json")
		if isRegularFile(candidate) {
			return candidate, nil
		}
		return "", ErrNotFound
	}

	current, err := os.Getwd()
	if err != nil {
		return "", fmt.Errorf("get working directory: %w", err)
	}
	for {
		candidate := filepath.Join(current, ".research4jar", "project.json")
		if isRegularFile(candidate) {
			return candidate, nil
		}
		parent := filepath.Dir(current)
		if parent == current {
			return "", ErrNotFound
		}
		current = parent
	}
}

// Root returns the project root that owns .research4jar/project.json.
func Root(projectDir string) (string, error) {
	projectPath, err := Locate(projectDir)
	if err != nil {
		return "", err
	}
	return filepath.Dir(filepath.Dir(projectPath)), nil
}

func Load(path string) (Pointer, error) {
	file, err := os.Open(path)
	if err != nil {
		return Pointer{}, fmt.Errorf("open project index: %w", err)
	}
	defer file.Close()

	var pointer Pointer
	if err := json.NewDecoder(file).Decode(&pointer); err != nil {
		return Pointer{}, fmt.Errorf("decode project index: %w", err)
	}
	if pointer.SchemaVersion != 1 && pointer.SchemaVersion != 2 {
		return Pointer{}, fmt.Errorf("unsupported project schema version %d", pointer.SchemaVersion)
	}
	if pointer.SessionDBPath == "" {
		return Pointer{}, errors.New("project index has no session_db_path")
	}
	return pointer, nil
}

func isRegularFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}
