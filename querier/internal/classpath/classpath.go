// Package classpath discovers a Spring Boot project's dependency jars by
// asking its own build tool, so `springdep index` works without the user
// exporting jars manually.
package classpath

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

var ErrNoBuildTool = errors.New(
	"no pom.xml or build.gradle(.kts) found; pass --jars with a jar directory, glob, or list",
)

const gradleMarker = "SPRINGDEP_JAR:"

const gradleInitScript = `allprojects { project ->
    project.tasks.register("springdepClasspath") {
        doLast {
            def cfg = project.configurations.findByName("runtimeClasspath")
            if (cfg != null && cfg.canBeResolved) {
                cfg.files.each { println("` + gradleMarker + `" + it.absolutePath) }
            }
        }
    }
}
`

// Discover returns the absolute paths of the project's runtime dependency
// jars, resolved through Maven or Gradle (preferring their wrappers).
func Discover(projectDir string) ([]string, error) {
	absolute, err := filepath.Abs(projectDir)
	if err != nil {
		return nil, err
	}
	switch {
	case fileExists(filepath.Join(absolute, "pom.xml")):
		return discoverMaven(absolute)
	case fileExists(filepath.Join(absolute, "build.gradle")),
		fileExists(filepath.Join(absolute, "build.gradle.kts")),
		fileExists(filepath.Join(absolute, "settings.gradle")),
		fileExists(filepath.Join(absolute, "settings.gradle.kts")):
		return discoverGradle(absolute)
	default:
		return nil, ErrNoBuildTool
	}
}

func discoverMaven(projectDir string) ([]string, error) {
	output, err := os.CreateTemp("", "springdep-classpath-*.txt")
	if err != nil {
		return nil, err
	}
	outputPath := output.Name()
	output.Close()
	defer os.Remove(outputPath)

	command := buildCommand(
		projectDir, "mvnw", "mvn",
		"-q", "-DincludeScope=runtime",
		"dependency:build-classpath",
		"-Dmdep.outputFile="+outputPath,
	)
	if combined, err := command.CombinedOutput(); err != nil {
		return nil, fmt.Errorf(
			"maven classpath resolution failed: %w\n%s", err, tail(string(combined)),
		)
	}
	raw, err := os.ReadFile(outputPath)
	if err != nil {
		return nil, fmt.Errorf("read maven classpath output: %w", err)
	}
	entries := strings.Split(strings.TrimSpace(string(raw)), string(os.PathListSeparator))
	return filterJars(entries), nil
}

func discoverGradle(projectDir string) ([]string, error) {
	script, err := os.CreateTemp("", "springdep-init-*.gradle")
	if err != nil {
		return nil, err
	}
	scriptPath := script.Name()
	if _, err := script.WriteString(gradleInitScript); err != nil {
		script.Close()
		os.Remove(scriptPath)
		return nil, err
	}
	script.Close()
	defer os.Remove(scriptPath)

	command := buildCommand(
		projectDir, "gradlew", "gradle",
		"--init-script", scriptPath, "-q", "springdepClasspath",
	)
	combined, err := command.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf(
			"gradle classpath resolution failed: %w\n%s", err, tail(string(combined)),
		)
	}
	entries := []string{}
	for _, line := range strings.Split(string(combined), "\n") {
		line = strings.TrimSpace(line)
		if rest, found := strings.CutPrefix(line, gradleMarker); found {
			entries = append(entries, rest)
		}
	}
	return filterJars(entries), nil
}

// buildCommand prefers the project's wrapper script and falls back to the
// tool on PATH. Wrapper scripts need cmd.exe handling on Windows.
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

func filterJars(entries []string) []string {
	seen := map[string]bool{}
	jars := []string{}
	for _, entry := range entries {
		entry = strings.TrimSpace(entry)
		if entry == "" || !strings.HasSuffix(entry, ".jar") || seen[entry] {
			continue
		}
		seen[entry] = true
		jars = append(jars, entry)
	}
	return jars
}

func tail(output string) string {
	lines := strings.Split(strings.TrimSpace(output), "\n")
	if len(lines) > 20 {
		lines = lines[len(lines)-20:]
	}
	return strings.Join(lines, "\n")
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}
