#!/usr/bin/env bash
# Build Research4Jar from source and install it for the current user.
# Requirements: JDK 17+, Go 1.23+. Override the target with PREFIX=/some/path.
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "install.sh: JDK 17+ is required to build the indexer" >&2
  exit 1
fi
if ! command -v go >/dev/null 2>&1; then
  echo "install.sh: Go 1.23+ is required to build the querier" >&2
  exit 1
fi

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
