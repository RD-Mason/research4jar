#!/usr/bin/env bash
# Build Research4Jar from source and install it for the current user.
# Requirements: JDK 17+, Go 1.23+, make. Override with PREFIX=/some/path.
set -euo pipefail

cd "$(dirname "$0")"

agent_install() {
  local package="$1"
  case "$(uname -s 2>/dev/null || printf unknown)" in
    Darwin)
      case "$package" in
        jdk) printf '%s\n' "if command -v brew >/dev/null 2>&1; then brew install --cask temurin; else echo 'Install Homebrew or JDK 17+ manually: https://adoptium.net/' >&2; exit 1; fi" ;;
        go) printf '%s\n' "if command -v brew >/dev/null 2>&1; then brew install go; else echo 'Install Go 1.23+ manually: https://go.dev/dl/' >&2; exit 1; fi" ;;
        make) printf '%s\n' "xcode-select --install" ;;
      esac
      ;;
    Linux)
      case "$package" in
        jdk) printf '%s\n' "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y openjdk-17-jdk; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y java-17-openjdk-devel; elif command -v yum >/dev/null 2>&1; then sudo yum install -y java-17-openjdk-devel; else echo 'Install OpenJDK 17+ manually' >&2; exit 1; fi" ;;
        go) printf '%s\n' "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y golang-go; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y golang; elif command -v yum >/dev/null 2>&1; then sudo yum install -y golang; else echo 'Install Go 1.23+ manually: https://go.dev/dl/' >&2; exit 1; fi" ;;
        make) printf '%s\n' "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y make; elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y make; elif command -v yum >/dev/null 2>&1; then sudo yum install -y make; else echo 'Install make manually' >&2; exit 1; fi" ;;
      esac
      ;;
    *)
      printf '%s\n' "Install $package with your OS package manager, then rerun ./install.sh"
      ;;
  esac
}

missing() {
  local title="$1"
  local user="$2"
  local agent="$3"
  local verify="$4"
  {
    echo "install.sh: $title"
    echo "User install: $user"
    echo "Agent install:"
    echo "  $agent"
    echo "Verify:"
    echo "  $verify"
  } >&2
  exit 1
}

java_major() {
  local command="$1"
  local version
  version="$("$command" -version 2>&1 | awk 'NR == 1 { for (i = 1; i <= NF; i++) if ($i ~ /^[\"]?[0-9]+([._][0-9]+)*/) { gsub(/\"/, "", $i); print $i; exit } }')"
  case "$version" in
    1.*) printf '%s\n' "$(printf '%s' "$version" | cut -d. -f2)" ;;
    *) printf '%s\n' "${version%%.*}" ;;
  esac
}

require_java_command() {
  local command="$1"
  local label="$2"
  if ! command -v "$command" >/dev/null 2>&1; then
    missing "$label 17+ is required to build Research4Jar" \
      "Install Eclipse Temurin/OpenJDK 17+ and reopen the terminal." \
      "$(agent_install jdk)" \
      "$command -version"
  fi
  local major
  major="$(java_major "$command")"
  if [[ -z "$major" || ! "$major" =~ ^[0-9]+$ || "$major" -lt 17 ]]; then
    missing "$label 17+ is required; found $("$command" -version 2>&1 | head -n 1)" \
      "Install Eclipse Temurin/OpenJDK 17+ and ensure it is first on PATH." \
      "$(agent_install jdk)" \
      "$command -version"
  fi
}

go_minor() {
  go version 2>/dev/null | awk '{print $3}' | sed -nE 's/^go([0-9]+)\.([0-9]+).*/\1 \2/p'
}

require_go() {
  if ! command -v go >/dev/null 2>&1; then
    missing "Go 1.23+ is required to build the querier" \
      "Install Go 1.23+ from https://go.dev/dl/ or your package manager." \
      "$(agent_install go)" \
      "go version"
  fi
  read -r major minor <<EOF
$(go_minor)
EOF
  if [[ -z "${major:-}" || -z "${minor:-}" ||
    ! "$major" =~ ^[0-9]+$ || ! "$minor" =~ ^[0-9]+$ ||
    "$major" -lt 1 || ( "$major" -eq 1 && "$minor" -lt 23 ) ]]; then
    missing "Go 1.23+ is required; found $(go version 2>/dev/null || echo unknown)" \
      "Install Go 1.23+ from https://go.dev/dl/ or your package manager." \
      "$(agent_install go)" \
      "go version"
  fi
}

if ! command -v make >/dev/null 2>&1; then
  missing "make is required to run make install" \
    "Install build tools for your OS." \
    "$(agent_install make)" \
    "make --version"
fi
require_java_command java "Java runtime"
require_java_command javac "JDK compiler"
require_go

PREFIX="${PREFIX:-$HOME/.local}"
make install PREFIX="$PREFIX"

case ":$PATH:" in
  *":$PREFIX/bin:"*) ;;
  *)
    echo
    echo "NOTE: $PREFIX/bin is not on your PATH. Add this to your shell profile:"
    echo "  export PATH=\"$PREFIX/bin:\$PATH\""
    ;;
esac

echo
echo "Quick start (inside a Spring Boot project):"
echo "  research4jar index                                   # auto-resolves jars via Maven/Gradle"
echo "  research4jar find-config-properties spring.datasource"
echo
echo "MCP (Cursor / Claude Code / any MCP host):"
echo "  command: research4jar   args: [\"mcp\"]"
