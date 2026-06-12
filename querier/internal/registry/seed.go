package registry

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// DefaultMavenRepo is where seed coordinates resolve unless overridden.
const DefaultMavenRepo = "https://repo1.maven.org/maven2"

// Coordinate identifies one Maven artifact to seed.
type Coordinate struct {
	Group    string
	Artifact string
	Version  string
}

func (c Coordinate) String() string {
	return c.Group + ":" + c.Artifact + ":" + c.Version
}

// JarURL is the artifact's jar location under a Maven repository base URL.
func (c Coordinate) JarURL(repo string) string {
	return fmt.Sprintf(
		"%s/%s/%s/%s/%s-%s.jar",
		strings.TrimRight(repo, "/"),
		strings.ReplaceAll(c.Group, ".", "/"),
		url.PathEscape(c.Artifact),
		url.PathEscape(c.Version),
		url.PathEscape(c.Artifact),
		url.PathEscape(c.Version),
	)
}

// ParseCoordinates reads one group:artifact:version per line; blank lines and
// # comments are skipped.
func ParseCoordinates(reader io.Reader) ([]Coordinate, error) {
	var coordinates []Coordinate
	scanner := bufio.NewScanner(reader)
	line := 0
	for scanner.Scan() {
		line++
		text := strings.TrimSpace(scanner.Text())
		if text == "" || strings.HasPrefix(text, "#") {
			continue
		}
		parts := strings.Split(text, ":")
		if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" {
			return nil, fmt.Errorf(
				"line %d: %q is not group:artifact:version", line, text,
			)
		}
		coordinates = append(coordinates, Coordinate{
			Group:    parts[0],
			Artifact: parts[1],
			Version:  parts[2],
		})
	}
	return coordinates, scanner.Err()
}

// DownloadJars fetches every coordinate's jar into destDir. Individual
// failures are warned about and skipped — a partially seeded registry is
// still useful, and clients fall back to local extraction on misses.
// Returns the downloaded jar paths and the number of failures.
func DownloadJars(
	ctx context.Context, repo string, coordinates []Coordinate, destDir string, warnings io.Writer,
) ([]string, int) {
	if err := os.MkdirAll(destDir, 0o755); err != nil {
		fmt.Fprintf(warnings, "warning: %v\n", err)
		return nil, len(coordinates)
	}
	client := &http.Client{Timeout: 5 * time.Minute}
	jarPaths := make([]string, len(coordinates))
	var wg sync.WaitGroup
	jobs := make(chan int)
	workers := min(6, len(coordinates))
	var mu sync.Mutex
	failures := 0
	wg.Add(workers)
	for worker := 0; worker < workers; worker++ {
		go func() {
			defer wg.Done()
			for index := range jobs {
				coordinate := coordinates[index]
				target := filepath.Join(
					destDir,
					fmt.Sprintf("%s-%s.jar", coordinate.Artifact, coordinate.Version),
				)
				if err := downloadFile(ctx, client, coordinate.JarURL(repo), target); err != nil {
					mu.Lock()
					fmt.Fprintf(warnings, "warning: %s: %v; skipping\n", coordinate, err)
					failures++
					mu.Unlock()
					continue
				}
				jarPaths[index] = target
			}
		}()
	}
	for index := range coordinates {
		jobs <- index
	}
	close(jobs)
	wg.Wait()

	var downloaded []string
	for _, path := range jarPaths {
		if path != "" {
			downloaded = append(downloaded, path)
		}
	}
	return downloaded, failures
}

func downloadFile(ctx context.Context, client *http.Client, source, target string) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, source, nil)
	if err != nil {
		return err
	}
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("%s returned %s", source, response.Status)
	}
	file, err := os.CreateTemp(filepath.Dir(target), "."+filepath.Base(target)+".*")
	if err != nil {
		return err
	}
	tempPath := file.Name()
	defer os.Remove(tempPath)
	if _, err := io.Copy(file, response.Body); err != nil {
		file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return os.Rename(tempPath, target)
}
