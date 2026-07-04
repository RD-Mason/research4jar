// Package indexer locates and runs the JVM extraction binary so the Go CLI
// can drive the full index-then-query workflow on its own.
package indexer

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"dev.research4jar/querier/internal/classpath"
)

var ErrNotFound = errors.New(
	"research4jar-index not found; set RESEARCH4JAR_INDEX, pass --indexer, " +
		"or install it next to the research4jar binary",
)

// Locate finds the research4jar-index launcher: explicit flag, RESEARCH4JAR_INDEX
// env var, the install layout next to this executable, then PATH.
func Locate(explicit string) (string, error) {
	if explicit != "" {
		return explicit, nil
	}
	if env := os.Getenv("RESEARCH4JAR_INDEX"); env != "" {
		return env, nil
	}
	launcher := "research4jar-index"
	if runtime.GOOS == "windows" {
		launcher = "research4jar-index.bat"
	}
	if executable, err := os.Executable(); err == nil {
		binDir := filepath.Dir(executable)
		candidates := []string{
			filepath.Join(binDir, launcher),
			// `make install` layout: bin/research4jar next to
			// libexec/research4jar-index/bin/research4jar-index.
			filepath.Join(binDir, "..", "libexec", "research4jar-index", "bin", launcher),
			// In-repo layout for development builds.
			filepath.Join(
				binDir, "..", "..", "core", "build", "install",
				"research4jar-index", "bin", launcher,
			),
		}
		for _, candidate := range candidates {
			if isExecutableFile(candidate) {
				return filepath.Clean(candidate), nil
			}
		}
	}
	if found, err := exec.LookPath(launcher); err == nil {
		return found, nil
	}
	return "", ErrNotFound
}

// Run indexes the project: jars may be a directory/glob/list accepted by the
// indexer; when empty the project's build tool resolves the classpath first.
// Indexer output streams through to the caller's stdout/stderr.
func Run(indexerBin, jars, projectDir, home string) error {
	if jars == "" {
		discovered, err := classpath.Discover(projectDir)
		if err != nil {
			return err
		}
		if len(discovered) == 0 {
			return errors.New("build tool resolved an empty runtime classpath")
		}
		fmt.Fprintf(
			os.Stderr,
			"research4jar: resolved %d dependency jars from the build tool\n",
			len(discovered),
		)
		jars = strings.Join(discovered, ",")
	}
	args := []string{"--jars", jars, "--project-dir", projectDir}
	if home != "" {
		args = append(args, "--home", home)
	}
	command := exec.Command(indexerBin, args...)
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	// The Gradle launcher sets no JVM flags, so the default max heap is ~25%
	// of physical RAM and the extractor's peak RSS balloons far past what it
	// needs (measured 2.1 GB default vs 550 MB at -Xmx256m with no slowdown
	// on a 99-jar classpath). Cap it unless the user configured the JVM.
	if os.Getenv("RESEARCH4JAR_INDEX_OPTS") == "" && os.Getenv("JAVA_OPTS") == "" {
		command.Env = append(os.Environ(), "RESEARCH4JAR_INDEX_OPTS=-Xmx512m")
	}
	return command.Run()
}

func isExecutableFile(path string) bool {
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return false
	}
	if runtime.GOOS == "windows" {
		return true
	}
	return info.Mode().Perm()&0o111 != 0
}
