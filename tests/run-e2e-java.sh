#!/usr/bin/env bash
# Runs tests/e2e.sh with the JVM CLI standing in for both binaries — the M6
# parity gate. Usage: tests/run-e2e-java.sh /abs/path/to/research4jar-cli.jar
set -euo pipefail

CLI_JAR="${1:?usage: run-e2e-java.sh <research4jar-cli.jar>}"
[ -f "$CLI_JAR" ] || { echo "cli jar not found: $CLI_JAR" >&2; exit 1; }

shim_dir="$(mktemp -d "${TMPDIR:-/tmp}/research4jar-java-shim.XXXXXX")"
trap 'rm -rf "$shim_dir"' EXIT

cat > "$shim_dir/research4jar-index" <<EOF
#!/usr/bin/env bash
exec java -jar "$CLI_JAR" index-raw "\$@"
EOF
cat > "$shim_dir/research4jar" <<EOF
#!/usr/bin/env bash
exec java -jar "$CLI_JAR" "\$@"
EOF
chmod +x "$shim_dir/research4jar-index" "$shim_dir/research4jar"

RESEARCH4JAR_INDEX="$shim_dir/research4jar-index" \
RESEARCH4JAR_QUERY="$shim_dir/research4jar" \
RESEARCH4JAR_NO_DAEMON=1 \
"$(dirname "$0")/e2e.sh"
