// Package envcheck reports the local runtime and build tools Research4Jar
// needs, including concrete installation guidance for users and agents.
package envcheck

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"

	"dev.research4jar/querier/internal/indexer"
)

type Status string

const (
	StatusOK      Status = "ok"
	StatusMissing Status = "missing"
	StatusWarning Status = "warning"
)

type Options struct {
	ProjectDir  string `json:"project_dir,omitempty"`
	SourceBuild bool   `json:"source_build"`
}

type Report struct {
	OK         bool    `json:"ok"`
	ProjectDir string  `json:"project_dir,omitempty"`
	Checks     []Check `json:"checks"`
}

type Check struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Status       Status   `json:"status"`
	RequiredFor  []string `json:"required_for"`
	Found        string   `json:"found,omitempty"`
	Version      string   `json:"version,omitempty"`
	Minimum      string   `json:"minimum,omitempty"`
	Message      string   `json:"message"`
	UserInstall  string   `json:"user_install,omitempty"`
	AgentInstall []string `json:"agent_install,omitempty"`
	Verify       []string `json:"verify,omitempty"`
}

type inspector struct {
	goos          string
	lookup        func(string) (string, error)
	locateIndexer func() (string, error)
	run           func(string, ...string) (string, error)
}

func Run(options Options) Report {
	return newInspector().Run(options)
}

func newInspector() inspector {
	return inspector{
		goos:          runtime.GOOS,
		lookup:        exec.LookPath,
		locateIndexer: func() (string, error) { return indexer.Locate("") },
		run: func(name string, args ...string) (string, error) {
			command := exec.Command(name, args...)
			combined, err := command.CombinedOutput()
			return strings.TrimSpace(string(combined)), err
		},
	}
}

func (i inspector) Run(options Options) Report {
	projectDir := options.ProjectDir
	if projectDir == "" {
		if cwd, err := os.Getwd(); err == nil {
			projectDir = cwd
		}
	}
	if absolute, err := filepath.Abs(projectDir); err == nil {
		projectDir = absolute
	}

	checks := []Check{
		i.checkIndexer(),
		i.checkJava(),
		i.checkProjectBuildTool(projectDir),
	}
	if options.SourceBuild {
		checks = append(
			checks,
			i.checkBuildJava(),
			i.checkJavac(),
			i.checkGo(),
			i.checkCommand(
				"make",
				"Make",
				[]string{"source build via make build/make install"},
				makeGuide(i.goos),
			),
			i.checkCommand(
				"bash",
				"Bash",
				[]string{"install.sh and end-to-end scripts"},
				installGuide{
					user:   "Install Bash or run from an environment that provides /usr/bin/env bash.",
					agent:  i.packageInstall("bash"),
					verify: []string{"bash --version"},
				},
			),
		)
	}

	ok := true
	for _, check := range checks {
		if check.Status == StatusMissing {
			ok = false
			break
		}
	}
	return Report{OK: ok, ProjectDir: projectDir, Checks: checks}
}

type installGuide struct {
	user   string
	agent  []string
	verify []string
}

func (i inspector) checkIndexer() Check {
	guide := installGuide{
		user: "Install a Research4Jar release archive and put bin/ on PATH, or build from source with ./install.sh.",
		agent: []string{
			"if [ -x ./install.sh ]; then ./install.sh; else echo 'Install a Research4Jar release archive and put bin/ on PATH' >&2; exit 1; fi",
			"PATH=\"$HOME/.local/bin:$PATH\" research4jar doctor --source-build",
		},
		verify: []string{"PATH=\"$HOME/.local/bin:$PATH\" research4jar-index --help"},
	}
	check := Check{
		ID:           "research4jar-index",
		Name:         "Research4Jar JVM indexer launcher",
		RequiredFor:  []string{"local jar extraction", "registry misses"},
		Minimum:      "bundled with Research4Jar",
		UserInstall:  guide.user,
		AgentInstall: guide.agent,
		Verify:       guide.verify,
	}
	if found, err := i.locateIndexer(); err == nil {
		check.Status = StatusOK
		check.Found = found
		check.Message = "research4jar-index is available."
		return check
	}
	check.Status = StatusMissing
	check.Message = "research4jar-index was not found next to the CLI or on PATH. A fully covered registry can avoid local extraction, but registry misses need this launcher."
	return check
}

func (i inspector) checkJava() Check {
	guide := javaRuntimeGuide(i.goos)
	check := Check{
		ID:           "java",
		Name:         "Java runtime 11+",
		RequiredFor:  []string{"running the JVM indexer", "local jar extraction", "registry misses"},
		Minimum:      "11",
		UserInstall:  guide.user,
		AgentInstall: guide.agent,
		Verify:       guide.verify,
	}
	found, err := i.lookup("java")
	if err != nil {
		check.Status = StatusMissing
		check.Message = "java was not found on PATH."
		return check
	}
	check.Found = found
	output, err := i.run("java", "-version")
	if err != nil {
		check.Status = StatusMissing
		check.Message = "java exists but java -version failed: " + oneLine(output)
		return check
	}
	check.Version = firstLine(output)
	if major, ok := parseJavaMajor(output); ok && major >= 11 {
		check.Status = StatusOK
		check.Message = "Java is new enough."
		return check
	}
	check.Status = StatusMissing
	check.Message = "Java is installed but version 11 or newer is required."
	return check
}

func (i inspector) checkBuildJava() Check {
	guide := javaBuildGuide(i.goos)
	check := Check{
		ID:           "java-source-build",
		Name:         "Java runtime 17+ for source builds",
		RequiredFor:  []string{"running Gradle and Kotlin build tools"},
		Minimum:      "17",
		UserInstall:  guide.user,
		AgentInstall: guide.agent,
		Verify:       []string{"java -version"},
	}
	found, err := i.lookup("java")
	if err != nil {
		check.Status = StatusMissing
		check.Message = "java was not found on PATH."
		return check
	}
	check.Found = found
	output, err := i.run("java", "-version")
	if err != nil {
		check.Status = StatusMissing
		check.Message = "java exists but java -version failed: " + oneLine(output)
		return check
	}
	check.Version = firstLine(output)
	if major, ok := parseJavaMajor(output); ok && major >= 17 {
		check.Status = StatusOK
		check.Message = "Java is new enough for source builds."
		return check
	}
	check.Status = StatusMissing
	check.Message = "Source builds require Java 17 or newer, even though the released indexer runtime supports Java 11."
	return check
}

func (i inspector) checkJavac() Check {
	guide := javaBuildGuide(i.goos)
	check := Check{
		ID:           "javac",
		Name:         "JDK compiler 17+",
		RequiredFor:  []string{"source build", "full verification"},
		Minimum:      "17",
		UserInstall:  guide.user,
		AgentInstall: guide.agent,
		Verify:       []string{"javac -version"},
	}
	found, err := i.lookup("javac")
	if err != nil {
		check.Status = StatusMissing
		check.Message = "javac was not found on PATH; install a JDK, not only a JRE."
		return check
	}
	check.Found = found
	output, err := i.run("javac", "-version")
	if err != nil {
		check.Status = StatusMissing
		check.Message = "javac exists but javac -version failed: " + oneLine(output)
		return check
	}
	check.Version = firstLine(output)
	if major, ok := parseJavaMajor(output); ok && major >= 17 {
		check.Status = StatusOK
		check.Message = "JDK compiler is new enough."
		return check
	}
	check.Status = StatusMissing
	check.Message = "javac is installed but version 17 or newer is required."
	return check
}

func (i inspector) checkGo() Check {
	guide := goGuide(i.goos)
	check := Check{
		ID:           "go",
		Name:         "Go 1.23+",
		RequiredFor:  []string{"source build", "querier tests"},
		Minimum:      "1.23",
		UserInstall:  guide.user,
		AgentInstall: guide.agent,
		Verify:       guide.verify,
	}
	found, err := i.lookup("go")
	if err != nil {
		check.Status = StatusMissing
		check.Message = "go was not found on PATH."
		return check
	}
	check.Found = found
	output, err := i.run("go", "version")
	if err != nil {
		check.Status = StatusMissing
		check.Message = "go exists but go version failed: " + oneLine(output)
		return check
	}
	check.Version = firstLine(output)
	if major, minor, ok := parseGoVersion(output); ok && (major > 1 || major == 1 && minor >= 23) {
		check.Status = StatusOK
		check.Message = "Go is new enough."
		return check
	}
	check.Status = StatusMissing
	check.Message = "Go is installed but version 1.23 or newer is required."
	return check
}

func (i inspector) checkProjectBuildTool(projectDir string) Check {
	requiredFor := []string{"research4jar index without --jars", "MCP index_project without jars"}
	switch {
	case fileExists(filepath.Join(projectDir, "pom.xml")):
		return i.checkBuildTool(
			"maven",
			"Maven wrapper or Maven",
			projectDir,
			[]string{"mvnw", "mvnw.cmd", "mvnw.bat"},
			"mvn",
			requiredFor,
			mavenGuide(i.goos),
		)
	case fileExists(filepath.Join(projectDir, "build.gradle")),
		fileExists(filepath.Join(projectDir, "build.gradle.kts")),
		fileExists(filepath.Join(projectDir, "settings.gradle")),
		fileExists(filepath.Join(projectDir, "settings.gradle.kts")):
		return i.checkBuildTool(
			"gradle",
			"Gradle wrapper or Gradle",
			projectDir,
			[]string{"gradlew", "gradlew.cmd", "gradlew.bat"},
			"gradle",
			requiredFor,
			gradleGuide(i.goos),
		)
	default:
		return Check{
			ID:          "project-build-tool",
			Name:        "Project build tool",
			Status:      StatusWarning,
			RequiredFor: requiredFor,
			Message:     "No pom.xml or Gradle build file found. Pass --jars with a jar directory, glob, or comma-separated list.",
			UserInstall: "No build tool is required when you pass --jars explicitly.",
			Verify:      []string{"research4jar index --jars <DIR|GLOB|LIST>"},
		}
	}
}

func (i inspector) checkBuildTool(
	id, name, projectDir string,
	wrappers []string,
	fallback string,
	requiredFor []string,
	guide installGuide,
) Check {
	check := Check{
		ID:           id,
		Name:         name,
		RequiredFor:  requiredFor,
		UserInstall:  guide.user,
		AgentInstall: guide.agent,
		Verify:       guide.verify,
	}
	for _, wrapper := range wrappers {
		path := filepath.Join(projectDir, wrapper)
		if fileExists(path) {
			check.Found = path
			if isRunnableScript(path, i.goos) {
				check.Status = StatusOK
				check.Message = "Project wrapper is available."
				return check
			}
			check.Status = StatusMissing
			check.Message = "Project wrapper exists but is not executable. Run chmod +x " + filepath.Base(path) + "."
			check.AgentInstall = []string{"chmod +x " + shellQuote(path)}
			check.Verify = []string{path + " --version"}
			return check
		}
	}
	if found, err := i.lookup(fallback); err == nil {
		check.Status = StatusOK
		check.Found = found
		check.Message = fallback + " is available on PATH."
		return check
	}
	check.Status = StatusMissing
	check.Message = "No project wrapper or " + fallback + " executable was found. Pass --jars to avoid build-tool resolution, or install " + fallback + "."
	return check
}

func (i inspector) checkCommand(id, name string, requiredFor []string, guide installGuide) Check {
	check := Check{
		ID:           id,
		Name:         name,
		RequiredFor:  requiredFor,
		UserInstall:  guide.user,
		AgentInstall: guide.agent,
		Verify:       guide.verify,
	}
	if found, err := i.lookup(id); err == nil {
		check.Status = StatusOK
		check.Found = found
		check.Message = name + " is available."
		return check
	}
	check.Status = StatusMissing
	check.Message = id + " was not found on PATH."
	return check
}

func parseJavaMajor(output string) (int, bool) {
	version := ""
	quoted := regexp.MustCompile(`version "([^"]+)"`).FindStringSubmatch(output)
	if len(quoted) == 2 {
		version = quoted[1]
	} else {
		plain := regexp.MustCompile(`\b(?:java|javac|openjdk)\s+([0-9]+(?:\.[0-9]+)*)`).FindStringSubmatch(output)
		if len(plain) == 2 {
			version = plain[1]
		}
	}
	if version == "" {
		return 0, false
	}
	parts := strings.Split(version, ".")
	if len(parts) == 0 {
		return 0, false
	}
	if parts[0] == "1" && len(parts) > 1 {
		major, err := strconv.Atoi(parts[1])
		return major, err == nil
	}
	major, err := strconv.Atoi(parts[0])
	return major, err == nil
}

func parseGoVersion(output string) (int, int, bool) {
	match := regexp.MustCompile(`go([0-9]+)\.([0-9]+)`).FindStringSubmatch(output)
	if len(match) != 3 {
		return 0, 0, false
	}
	major, majorErr := strconv.Atoi(match[1])
	minor, minorErr := strconv.Atoi(match[2])
	return major, minor, majorErr == nil && minorErr == nil
}

func javaRuntimeGuide(goos string) installGuide {
	switch goos {
	case "darwin":
		return installGuide{
			user: "Install any Java runtime 11+; install JDK 17+ if you will build from source.",
			agent: []string{
				"if command -v brew >/dev/null 2>&1; then brew install --cask temurin; else echo 'Install Java 11+ manually: https://adoptium.net/' >&2; exit 1; fi",
			},
			verify: []string{"java -version"},
		}
	case "windows":
		return installGuide{
			user:   "Install any Java runtime 11+; install JDK 17+ if you will build from source.",
			agent:  []string{"winget install EclipseAdoptium.Temurin.17.JDK"},
			verify: []string{"java -version"},
		}
	default:
		return installGuide{
			user: "Install any Java runtime 11+ with your system package manager.",
			agent: []string{
				"if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y openjdk-17-jre; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y java-17-openjdk; elif command -v yum >/dev/null 2>&1; then sudo yum install -y java-17-openjdk; else echo 'Install Java 11+ manually' >&2; exit 1; fi",
			},
			verify: []string{"java -version"},
		}
	}
}

func javaBuildGuide(goos string) installGuide {
	switch goos {
	case "darwin":
		return installGuide{
			user: "Install Eclipse Temurin 17+ or another JDK 17+, then reopen the terminal.",
			agent: []string{
				"if command -v brew >/dev/null 2>&1; then brew install --cask temurin; else echo 'Install Homebrew or a JDK 17+ manually: https://adoptium.net/' >&2; exit 1; fi",
			},
			verify: []string{"java -version", "javac -version"},
		}
	case "windows":
		return installGuide{
			user:   "Install Eclipse Temurin 17+ from Adoptium or with winget, then reopen the terminal.",
			agent:  []string{"winget install EclipseAdoptium.Temurin.17.JDK"},
			verify: []string{"java -version", "javac -version"},
		}
	default:
		return installGuide{
			user: "Install OpenJDK 17+ with your system package manager.",
			agent: []string{
				"if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y openjdk-17-jdk; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y java-17-openjdk-devel; elif command -v yum >/dev/null 2>&1; then sudo yum install -y java-17-openjdk-devel; else echo 'Install OpenJDK 17+ manually' >&2; exit 1; fi",
			},
			verify: []string{"java -version", "javac -version"},
		}
	}
}

func goGuide(goos string) installGuide {
	switch goos {
	case "darwin":
		return installGuide{
			user:   "Install Go 1.23+ from https://go.dev/dl/ or Homebrew.",
			agent:  []string{"if command -v brew >/dev/null 2>&1; then brew install go; else echo 'Install Go 1.23+ manually: https://go.dev/dl/' >&2; exit 1; fi"},
			verify: []string{"go version"},
		}
	case "windows":
		return installGuide{
			user:   "Install Go 1.23+ from https://go.dev/dl/ or with winget.",
			agent:  []string{"winget install GoLang.Go"},
			verify: []string{"go version"},
		}
	default:
		return installGuide{
			user: "Install Go 1.23+ from https://go.dev/dl/ or your system package manager.",
			agent: []string{
				"if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y golang-go; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y golang; elif command -v yum >/dev/null 2>&1; then sudo yum install -y golang; else echo 'Install Go 1.23+ manually: https://go.dev/dl/' >&2; exit 1; fi",
			},
			verify: []string{"go version"},
		}
	}
}

func mavenGuide(goos string) installGuide {
	return installGuide{
		user:   "Prefer the project's ./mvnw wrapper. Otherwise install Maven or pass --jars explicitly.",
		agent:  packageInstallFor(goos, "maven"),
		verify: []string{"mvn -version"},
	}
}

func gradleGuide(goos string) installGuide {
	return installGuide{
		user:   "Prefer the project's ./gradlew wrapper. Otherwise install Gradle or pass --jars explicitly.",
		agent:  packageInstallFor(goos, "gradle"),
		verify: []string{"gradle --version"},
	}
}

func makeGuide(goos string) installGuide {
	switch goos {
	case "darwin":
		return installGuide{
			user:   "Install Xcode Command Line Tools.",
			agent:  []string{"xcode-select --install"},
			verify: []string{"make --version"},
		}
	case "windows":
		return installGuide{
			user:   "Run the source build from WSL, Git Bash with make, or another environment that provides make.",
			agent:  []string{"echo 'Install make through WSL or Git Bash, then rerun research4jar doctor --source-build' >&2; exit 1"},
			verify: []string{"make --version"},
		}
	default:
		return installGuide{
			user:   "Install make with your system package manager.",
			agent:  packageInstallFor(goos, "make"),
			verify: []string{"make --version"},
		}
	}
}

func (i inspector) packageInstall(pkg string) []string {
	return packageInstallFor(i.goos, pkg)
}

func packageInstallFor(goos, pkg string) []string {
	switch goos {
	case "darwin":
		return []string{
			fmt.Sprintf("if command -v brew >/dev/null 2>&1; then brew install %s; else echo 'Install %s manually or install Homebrew first' >&2; exit 1; fi", pkg, pkg),
		}
	case "windows":
		switch pkg {
		case "maven":
			return []string{"winget install Apache.Maven"}
		case "gradle":
			return []string{"winget install Gradle.Gradle"}
		default:
			return []string{fmt.Sprintf("winget install %s", pkg)}
		}
	default:
		return []string{
			fmt.Sprintf("if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y %s; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y %s; elif command -v yum >/dev/null 2>&1; then sudo yum install -y %s; else echo 'Install %s manually' >&2; exit 1; fi", pkg, pkg, pkg, pkg),
		}
	}
}

func firstLine(output string) string {
	output = strings.TrimSpace(output)
	if output == "" {
		return ""
	}
	line, _, _ := strings.Cut(output, "\n")
	return strings.TrimSpace(line)
}

func oneLine(output string) string {
	line := firstLine(output)
	if line == "" {
		return "no output"
	}
	return line
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

func isRunnableScript(path, goos string) bool {
	if goos == "windows" {
		return true
	}
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return info.Mode().Perm()&0o111 != 0
}

func shellQuote(value string) string {
	if value == "" {
		return "''"
	}
	return "'" + strings.ReplaceAll(value, "'", "'\"'\"'") + "'"
}
