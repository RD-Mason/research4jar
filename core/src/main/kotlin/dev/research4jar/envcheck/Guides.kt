package dev.research4jar.envcheck

/**
 * Installation guidance for the doctor checks, ported verbatim from
 * querier/internal/envcheck (Go). Agents execute these commands as-is, so
 * every string must stay byte-identical to the Go querier.
 */
internal data class InstallGuide(
    val user: String,
    val agent: List<String>,
    val verify: List<String>,
)

internal fun javaRuntimeGuide(goos: String): InstallGuide = when (goos) {
    "darwin" -> InstallGuide(
        user = "Install any Java runtime 11+; install JDK 17+ if you will build from source.",
        agent = listOf(
            "if command -v brew >/dev/null 2>&1; then brew install --cask temurin; else echo 'Install Java 11+ manually: https://adoptium.net/' >&2; exit 1; fi",
        ),
        verify = listOf("java -version"),
    )

    "windows" -> InstallGuide(
        user = "Install any Java runtime 11+; install JDK 17+ if you will build from source.",
        agent = listOf("winget install EclipseAdoptium.Temurin.17.JDK"),
        verify = listOf("java -version"),
    )

    else -> InstallGuide(
        user = "Install any Java runtime 11+ with your system package manager.",
        agent = listOf(
            "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y openjdk-17-jre; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y java-17-openjdk; elif command -v yum >/dev/null 2>&1; then sudo yum install -y java-17-openjdk; else echo 'Install Java 11+ manually' >&2; exit 1; fi",
        ),
        verify = listOf("java -version"),
    )
}

internal fun javaBuildGuide(goos: String): InstallGuide = when (goos) {
    "darwin" -> InstallGuide(
        user = "Install Eclipse Temurin 17+ or another JDK 17+, then reopen the terminal.",
        agent = listOf(
            "if command -v brew >/dev/null 2>&1; then brew install --cask temurin; else echo 'Install Homebrew or a JDK 17+ manually: https://adoptium.net/' >&2; exit 1; fi",
        ),
        verify = listOf("java -version", "javac -version"),
    )

    "windows" -> InstallGuide(
        user = "Install Eclipse Temurin 17+ from Adoptium or with winget, then reopen the terminal.",
        agent = listOf("winget install EclipseAdoptium.Temurin.17.JDK"),
        verify = listOf("java -version", "javac -version"),
    )

    else -> InstallGuide(
        user = "Install OpenJDK 17+ with your system package manager.",
        agent = listOf(
            "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y openjdk-17-jdk; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y java-17-openjdk-devel; elif command -v yum >/dev/null 2>&1; then sudo yum install -y java-17-openjdk-devel; else echo 'Install OpenJDK 17+ manually' >&2; exit 1; fi",
        ),
        verify = listOf("java -version", "javac -version"),
    )
}

internal fun goGuide(goos: String): InstallGuide = when (goos) {
    "darwin" -> InstallGuide(
        user = "Install Go 1.23+ from https://go.dev/dl/ or Homebrew.",
        agent = listOf(
            "if command -v brew >/dev/null 2>&1; then brew install go; else echo 'Install Go 1.23+ manually: https://go.dev/dl/' >&2; exit 1; fi",
        ),
        verify = listOf("go version"),
    )

    "windows" -> InstallGuide(
        user = "Install Go 1.23+ from https://go.dev/dl/ or with winget.",
        agent = listOf("winget install GoLang.Go"),
        verify = listOf("go version"),
    )

    else -> InstallGuide(
        user = "Install Go 1.23+ from https://go.dev/dl/ or your system package manager.",
        agent = listOf(
            "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y golang-go; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y golang; elif command -v yum >/dev/null 2>&1; then sudo yum install -y golang; else echo 'Install Go 1.23+ manually: https://go.dev/dl/' >&2; exit 1; fi",
        ),
        verify = listOf("go version"),
    )
}

internal fun mavenGuide(goos: String): InstallGuide = InstallGuide(
    user = "Prefer the project's ./mvnw wrapper. Otherwise install Maven or pass --jars explicitly.",
    agent = packageInstallFor(goos, "maven"),
    verify = listOf("mvn -version"),
)

internal fun gradleGuide(goos: String): InstallGuide = InstallGuide(
    user = "Prefer the project's ./gradlew wrapper. Otherwise install Gradle or pass --jars explicitly.",
    agent = packageInstallFor(goos, "gradle"),
    verify = listOf("gradle --version"),
)

internal fun makeGuide(goos: String): InstallGuide = when (goos) {
    "darwin" -> InstallGuide(
        user = "Install Xcode Command Line Tools.",
        agent = listOf("xcode-select --install"),
        verify = listOf("make --version"),
    )

    "windows" -> InstallGuide(
        user = "Run the source build from WSL, Git Bash with make, or another environment that provides make.",
        agent = listOf(
            "echo 'Install make through WSL or Git Bash, then rerun research4jar doctor --source-build' >&2; exit 1",
        ),
        verify = listOf("make --version"),
    )

    else -> InstallGuide(
        user = "Install make with your system package manager.",
        agent = packageInstallFor(goos, "make"),
        verify = listOf("make --version"),
    )
}

internal fun packageInstallFor(goos: String, pkg: String): List<String> = when (goos) {
    "darwin" -> listOf(
        "if command -v brew >/dev/null 2>&1; then brew install $pkg; else echo 'Install $pkg manually or install Homebrew first' >&2; exit 1; fi",
    )

    "windows" -> when (pkg) {
        "maven" -> listOf("winget install Apache.Maven")
        "gradle" -> listOf("winget install Gradle.Gradle")
        else -> listOf("winget install $pkg")
    }

    else -> listOf(
        "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y $pkg; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y $pkg; elif command -v yum >/dev/null 2>&1; then sudo yum install -y $pkg; else echo 'Install $pkg manually' >&2; exit 1; fi",
    )
}
