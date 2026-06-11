#!/usr/bin/env bash
set -euo pipefail

: "${SPRINGDEP_INDEX:?SPRINGDEP_INDEX must point to springdep-index}"
: "${SPRINGDEP_QUERY:?SPRINGDEP_QUERY must point to springdep}"

work="$(mktemp -d "${TMPDIR:-/tmp}/springdep-e2e.XXXXXX")"
registry_server=""
trap '[ -n "$registry_server" ] && kill "$registry_server" 2>/dev/null; rm -rf "$work"' EXIT

jars="$work/jars"
project="$work/project"
project2="$work/project2"
home1="$work/home1"
home2="$work/home2"
mkdir -p "$jars" "$project/nested/work" "$project2"
printf '# Existing project instructions\n' > "$project/CLAUDE.md"

make_jar() {
  local source="$1"
  local target="$2"
  (cd "$source" && jar --create --file "$target" .)
}

api_src="$work/api-src"
api_classes="$work/api-classes"
mkdir -p "$api_src/example" "$api_classes/META-INF/maven/com.example/api"
cat > "$api_src/example/Contract.java" <<'EOF'
package example;
public interface Contract {}
EOF
cat > "$api_src/example/MetaMarker.java" <<'EOF'
package example;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.ANNOTATION_TYPE)
public @interface MetaMarker {}
EOF
cat > "$api_src/example/Marker.java" <<'EOF'
package example;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@MetaMarker
public @interface Marker {
  String value();
}
EOF
javac -d "$api_classes" "$api_src/example/Contract.java" \
  "$api_src/example/Marker.java" "$api_src/example/MetaMarker.java"
cat > "$api_classes/META-INF/maven/com.example/api/pom.properties" <<'EOF'
groupId=com.example
artifactId=api
version=1.0
EOF
make_jar "$api_classes" "$jars/api.jar"

impl_src="$work/impl-src"
impl_classes="$work/impl-classes"
mkdir -p "$impl_src/other" "$impl_classes/META-INF/maven/com.example/implementation"
cat > "$impl_src/other/DirectImplementation.java" <<'EOF'
package other;
import example.Contract;
import example.Marker;
@Marker("direct")
public class DirectImplementation implements Contract {
  public static final String HEADER = "X-SpringDep-Test";
  public String value() {
    return "springdep.fixture.enabled";
  }
}
EOF
javac -cp "$jars/api.jar" -d "$impl_classes" \
  "$impl_src/other/DirectImplementation.java"
cat > "$impl_classes/META-INF/maven/com.example/implementation/pom.properties" <<'EOF'
groupId=com.example
artifactId=implementation
version=1.0
EOF
make_jar "$impl_classes" "$jars/implementation.jar"

# A third jar whose class only reaches example.Contract through the
# superclass chain — exercises the M2 transitive closure across jars.
deep_src="$work/deep-src"
deep_classes="$work/deep-classes"
mkdir -p "$deep_src/deep" "$deep_classes"
cat > "$deep_src/deep/Indirect.java" <<'EOF'
package deep;
import other.DirectImplementation;
public final class Indirect extends DirectImplementation {}
EOF
javac -cp "$jars/api.jar:$jars/implementation.jar" -d "$deep_classes" \
  "$deep_src/deep/Indirect.java"
make_jar "$deep_classes" "$jars/deep.jar"

# Fake Spring annotations compiled under the real FQNs let the e2e cover
# @Bean and @ConditionalOn* extraction without shipping Spring jars.
cfg_src="$work/cfg-src"
cfg_classes="$work/cfg-classes"
mkdir -p \
  "$cfg_src/org/springframework/context/annotation" \
  "$cfg_src/org/springframework/boot/autoconfigure/condition" \
  "$cfg_src/cfg"
cat > "$cfg_src/org/springframework/context/annotation/Bean.java" <<'EOF'
package org.springframework.context.annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Bean {
  String[] name() default {};
}
EOF
cat > "$cfg_src/org/springframework/boot/autoconfigure/condition/ConditionalOnClass.java" <<'EOF'
package org.springframework.boot.autoconfigure.condition;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ConditionalOnClass {
  String[] value() default {};
}
EOF
cat > "$cfg_src/cfg/DemoConfiguration.java" <<'EOF'
package cfg;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
@ConditionalOnClass({"javax.sql.DataSource"})
public class DemoConfiguration {
  @ConditionalOnClass({"java.util.List"})
  @Bean(name = {"demoBean"})
  public String demo() {
    return "demo";
  }
}
EOF
javac -d "$cfg_classes" \
  "$cfg_src/org/springframework/context/annotation/Bean.java" \
  "$cfg_src/org/springframework/boot/autoconfigure/condition/ConditionalOnClass.java" \
  "$cfg_src/cfg/DemoConfiguration.java"
make_jar "$cfg_classes" "$jars/demo-config.jar"

legacy_home="$work/legacy-home"
legacy_project="$work/legacy-project"
mkdir -p "$legacy_home/shards" "$legacy_project"
api_sha="$(python3 - "$jars/api.jar" <<'PY'
import hashlib
import pathlib
import sys
print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
legacy_shard="$legacy_home/shards/$api_sha@1.db"
sqlite3 "$legacy_shard" "CREATE TABLE legacy_marker(value TEXT);"
legacy_checksum="$(python3 - "$legacy_shard" <<'PY'
import hashlib
import pathlib
import sys
print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
sqlite3 "$legacy_home/manifest.db" <<SQL
CREATE TABLE shards (
  shard_id TEXT PRIMARY KEY,
  jar_coordinate TEXT,
  jar_filename TEXT NOT NULL,
  jar_sha256 TEXT NOT NULL,
  extractor_version INTEGER NOT NULL,
  shard_path TEXT NOT NULL,
  shard_checksum TEXT,
  size_bytes INTEGER,
  created_at INTEGER,
  last_access_at INTEGER,
  source TEXT
);
INSERT INTO shards VALUES (
  '$api_sha@1', 'com.example:api:1.0', 'api.jar', '$api_sha', 1,
  '$legacy_shard', '$legacy_checksum', 0, 0, 0, 'local'
);
SQL
"$SPRINGDEP_INDEX" --jars "$jars/api.jar" --project-dir "$legacy_project" \
  --home "$legacy_home" > "$work/version-invalidation.json"
python3 - "$work/version-invalidation.json" "$legacy_home" "$api_sha" <<'PY'
import json
import pathlib
import sys

stats = json.loads(pathlib.Path(sys.argv[1]).read_text())
home = pathlib.Path(sys.argv[2])
sha = sys.argv[3]
assert stats["jars_newly_indexed"] == 1, stats
assert stats["jars_skipped"] == 0, stats
assert (home / "shards" / f"{sha}@1.db").is_file()
assert (home / "shards" / f"{sha}@2.db").is_file()
PY

metadata="$work/metadata"
mkdir -p \
  "$metadata/META-INF/spring" \
  "$metadata/META-INF/maven/org.springframework.boot/spring-boot-autoconfigure"
cat > "$metadata/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports" <<'EOF'
# generated fixture
example.ImportedAutoConfiguration
example.SharedAutoConfiguration
EOF
cat > "$metadata/META-INF/spring.factories" <<'EOF'
org.springframework.boot.autoconfigure.EnableAutoConfiguration=example.LegacyAutoConfiguration,\
 example.SharedAutoConfiguration
EOF
cat > "$metadata/META-INF/spring-configuration-metadata.json" <<'EOF'
{
  "properties": [
    {
      "name": "spring.datasource.url",
      "type": "java.lang.String",
      "description": "JDBC URL of the database.",
      "sourceType": "org.springframework.boot.autoconfigure.jdbc.DataSourceProperties"
    }
  ]
}
EOF
cat > "$metadata/META-INF/additional-spring-configuration-metadata.json" <<'EOF'
{
  "properties": [
    {
      "name": "spring.datasource.hikari.maximum-pool-size",
      "type": "java.lang.Integer",
      "defaultValue": 10,
      "description": "Maximum pool size.",
      "sourceType": "com.zaxxer.hikari.HikariConfig"
    }
  ]
}
EOF
cat > "$metadata/META-INF/maven/org.springframework.boot/spring-boot-autoconfigure/pom.properties" <<'EOF'
groupId=org.springframework.boot
artifactId=spring-boot-autoconfigure
version=3.2.0
EOF
make_jar "$metadata" "$jars/spring-boot-autoconfigure.jar"
cp "$jars/spring-boot-autoconfigure.jar" "$jars/spring-boot-autoconfigure-copy.jar"

private="$work/private"
mkdir -p "$private/META-INF"
cat > "$private/META-INF/spring-configuration-metadata.json" <<'EOF'
{
  "properties": [
    {
      "name": "private.feature.enabled",
      "type": "java.lang.Boolean",
      "defaultValue": false
    }
  ]
}
EOF
make_jar "$private" "$jars/private-config.jar"

empty="$work/empty"
mkdir -p "$empty/content"
printf 'nothing to extract\n' > "$empty/content/readme.txt"
make_jar "$empty" "$jars/empty.jar"

bad_json="$work/bad-json"
mkdir -p "$bad_json/META-INF"
printf '{ this is not JSON\n' > "$bad_json/META-INF/spring-configuration-metadata.json"
make_jar "$bad_json" "$jars/bad-json.jar"

printf 'not a zip archive\n' > "$jars/broken.jar"

first_stats="$work/first-stats.json"
first_warnings="$work/first-warnings.txt"
"$SPRINGDEP_INDEX" \
  --jars "$jars" \
  --project-dir "$project" \
  --home "$home1" \
  > "$first_stats" 2> "$first_warnings"

python3 - "$first_stats" "$project/.springdep/project.json" <<'PY'
import json
import pathlib
import sys

stats = json.loads(pathlib.Path(sys.argv[1]).read_text())
pointer = json.loads(pathlib.Path(sys.argv[2]).read_text())
assert stats["jars_total"] == 9, stats
assert stats["jars_indexed"] == 8, stats
assert stats["jars_newly_indexed"] == 8, stats
assert stats["jars_skipped"] == 0, stats
assert stats["jars_missing"] == ["broken.jar"], stats
assert pointer["coverage"] == {
    "jars_total": 9,
    "jars_indexed": 8,
    "jars_missing": ["broken.jar"],
}, pointer
assert len(pointer["classpath_fingerprint"]) == 16, pointer
assert pathlib.Path(pointer["session_db_path"]).is_file(), pointer
PY

grep -q 'failed to parse META-INF/spring-configuration-metadata.json' "$first_warnings"
grep -q 'broken.jar: invalid or unreadable jar' "$first_warnings"
test "$(find "$home1/shards" -name '*.db' | wc -l | tr -d ' ')" = "8"
test -f "$home1/manifest.db"
test -f "$project/.springdep/project.json"
grep -q '^# Existing project instructions' "$project/CLAUDE.md"
test "$(grep -c '^## SpringDep（jar 配置项查询）$' "$project/CLAUDE.md")" = "1"

query_json="$work/query.json"
(cd "$project/nested/work" && \
  "$SPRINGDEP_QUERY" find-config-properties spring.datasource --page-size 10 > "$query_json")
python3 - "$query_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["total"] == 2, response
assert [item["name"] for item in response["results"]] == [
    "spring.datasource.hikari.maximum-pool-size",
    "spring.datasource.url",
], response
assert response["results"][0]["default"] == "10", response
assert response["results"][0]["source_jar"] == \
    "org.springframework.boot:spring-boot-autoconfigure:3.2.0", response
assert response["coverage"] == {
    "jars_total": 9,
    "jars_indexed": 8,
    "jars_missing": ["broken.jar"],
    "extractor_version": 2,
}, response
PY

private_json="$work/private-query.json"
"$SPRINGDEP_QUERY" find-config-properties private.feature \
  --project-dir "$project" > "$private_json"
python3 - "$private_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["results"][0]["source_jar"] == "private-config.jar", response
assert response["results"][0]["default"] == "false", response
PY

session_db="$(python3 - "$project/.springdep/project.json" <<'PY'
import json
import pathlib
import sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text())["session_db_path"])
PY
)"
test "$(sqlite3 "$session_db" \
  "SELECT COUNT(*) FROM spi_registrations WHERE mechanism='autoconfig.imports';")" = "2"
test "$(sqlite3 "$session_db" \
  "SELECT COUNT(*) FROM spi_registrations WHERE mechanism='spring.factories';")" = "2"
test "$(sqlite3 "$session_db" \
  "SELECT COUNT(*) FROM classes WHERE fqn='other.DirectImplementation';")" = "1"

implementations_json="$work/implementations-query.json"
"$SPRINGDEP_QUERY" find-implementations example.Contract \
  --project-dir "$project" > "$implementations_json"
python3 - "$implementations_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["query"] == {
    "command": "find-implementations",
    "arg": "example.Contract",
}, response
# Transitive by default: deep.Indirect reaches Contract only through
# other.DirectImplementation, which lives in a different jar.
assert response["total"] == 2, response
assert [item["fqn"] for item in response["results"]] == [
    "deep.Indirect",
    "other.DirectImplementation",
], response
assert response["results"][0]["source_jar"] == "deep.jar", response
assert response["results"][1]["source_jar"] == "com.example:implementation:1.0", response
PY

direct_impl_json="$work/direct-implementations-query.json"
"$SPRINGDEP_QUERY" find-implementations example.Contract --direct \
  --project-dir "$project" > "$direct_impl_json"
python3 - "$direct_impl_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["query"]["direct"] is True, response
assert response["total"] == 1, response
assert response["results"][0]["fqn"] == "other.DirectImplementation", response
PY

annotation_json="$work/annotation-query.json"
"$SPRINGDEP_QUERY" find-by-annotation example.Marker \
  --project-dir "$project" > "$annotation_json"
python3 - "$annotation_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["total"] == 1, response
assert response["results"][0] == {
    "fqn": "other.DirectImplementation",
    "source_jar": "com.example:implementation:1.0",
    "attributes": {"value": "direct"},
    "matched_annotation": "example.Marker",
}, response
PY

meta_annotation_json="$work/meta-annotation-query.json"
"$SPRINGDEP_QUERY" find-by-annotation example.MetaMarker \
  --project-dir "$project" > "$meta_annotation_json"
python3 - "$meta_annotation_json" <<'PY'
import json
import pathlib
import sys

# @Marker is itself annotated @MetaMarker, so querying @MetaMarker finds the
# annotation type directly plus every @Marker class via meta-expansion.
response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["total"] == 2, response
assert [
    (item["fqn"], item["matched_annotation"]) for item in response["results"]
] == [
    ("example.Marker", "example.MetaMarker"),
    ("other.DirectImplementation", "example.Marker"),
], response
PY

class_json="$work/get-class.json"
"$SPRINGDEP_QUERY" get-class other.DirectImplementation \
  --project-dir "$project" > "$class_json"
python3 - "$class_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["total"] == 1, response
detail = response["results"][0]
assert detail["kind"] == "class", detail
assert detail["interfaces"] == ["example.Contract"], detail
assert any(a["fqn"] == "example.Marker" for a in detail["annotations"]), detail
assert any(m["name"] == "value" for m in detail["methods"]), detail
PY

beans_json="$work/bean-definitions.json"
"$SPRINGDEP_QUERY" get-bean-definitions java.lang.String \
  --project-dir "$project" > "$beans_json"
python3 - "$beans_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["total"] == 1, response
bean = response["results"][0]
assert bean["bean_name"] == "demoBean", bean
assert bean["config_class"] == "cfg.DemoConfiguration", bean
assert bean["conditions"] == [{
    "target": "bean_method",
    "type": "OnClass",
    "ref_value": {"value": ["java.util.List"]},
}], bean
PY

conditional_json="$work/explain-conditional.json"
"$SPRINGDEP_QUERY" explain-conditional cfg.DemoConfiguration \
  --project-dir "$project" > "$conditional_json"
python3 - "$conditional_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["total"] == 1, response
target = response["results"][0]
assert target["class_conditions"] == [{
    "target": "class",
    "type": "OnClass",
    "ref_value": {"value": ["javax.sql.DataSource"]},
}], target
assert len(target["bean_methods"]) == 1, target
assert target["bean_methods"][0]["bean_name"] == "demoBean", target
PY

string_json="$work/find-string.json"
"$SPRINGDEP_QUERY" find-string springdep.fixture \
  --project-dir "$project" > "$string_json"
python3 - "$string_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["total"] == 1, response
constant = response["results"][0]
assert constant["value"] == "springdep.fixture.enabled", constant
assert constant["class_fqn"] == "other.DirectImplementation", constant
PY

extensions_json="$work/extension-points.json"
"$SPRINGDEP_QUERY" list-extension-points \
  --project-dir "$project" > "$extensions_json"
python3 - "$extensions_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
points = {
    (item["mechanism"], item["key"]): item["implementations"]
    for item in response["extension_points"]
}
assert points[("autoconfig.imports", None)] == 2, points
assert points[(
    "spring.factories",
    "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
)] == 2, points
PY

second_stats="$work/second-stats.json"
"$SPRINGDEP_INDEX" \
  --jars "$jars" \
  --project-dir "$project" \
  --home "$home1" \
  > "$second_stats" 2> /dev/null
python3 - "$second_stats" <<'PY'
import json
import pathlib
import sys

stats = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert stats["jars_total"] == 9, stats
assert stats["jars_indexed"] == 8, stats
assert stats["jars_newly_indexed"] == 0, stats
assert stats["jars_skipped"] == 8, stats
assert stats["jars_missing"] == ["broken.jar"], stats
PY
test "$(grep -c '^## SpringDep（jar 配置项查询）$' "$project/CLAUDE.md")" = "1"

corrupt_shard="$(find "$home1/shards" -name '*.db' | sort | head -1)"
printf 'corruption' >> "$corrupt_shard"
rebuild_stats="$work/rebuild-stats.json"
"$SPRINGDEP_INDEX" \
  --jars "$jars" \
  --project-dir "$project" \
  --home "$home1" \
  > "$rebuild_stats" 2> /dev/null
python3 - "$rebuild_stats" <<'PY'
import json
import pathlib
import sys

stats = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert stats["jars_newly_indexed"] == 1, stats
assert stats["jars_skipped"] == 7, stats
PY

"$SPRINGDEP_INDEX" \
  --jars "$jars" \
  --project-dir "$project2" \
  --home "$home2" \
  > /dev/null 2> /dev/null
for shard in "$home1"/shards/*.db; do
  cmp "$shard" "$home2/shards/$(basename "$shard")"
done

concurrent_home="$work/concurrent-home"
"$SPRINGDEP_INDEX" --jars "$jars" --project-dir "$work/concurrent-project-a" \
  --home "$concurrent_home" > "$work/concurrent-a.json" 2> "$work/concurrent-a.err" &
pid_a=$!
"$SPRINGDEP_INDEX" --jars "$jars" --project-dir "$work/concurrent-project-b" \
  --home "$concurrent_home" > "$work/concurrent-b.json" 2> "$work/concurrent-b.err" &
pid_b=$!
wait "$pid_a"
wait "$pid_b"
test "$(sqlite3 "$concurrent_home/manifest.db" 'PRAGMA integrity_check;')" = "ok"
for database in "$concurrent_home"/shards/*.db "$concurrent_home"/sessions/*.db; do
  test "$(sqlite3 "$database" 'PRAGMA integrity_check;')" = "ok"
done

# The Go CLI drives the JVM indexer end to end (springdep index).
cli_index_project="$work/cli-index-project"
cli_index_home="$work/cli-index-home"
mkdir -p "$cli_index_project"
"$SPRINGDEP_QUERY" index --jars "$jars/api.jar" \
  --project-dir "$cli_index_project" --home "$cli_index_home" \
  --indexer "$SPRINGDEP_INDEX" > "$work/cli-index-stats.json" 2> /dev/null
python3 - "$work/cli-index-stats.json" <<'PY'
import json
import pathlib
import sys

stats = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert stats["jars_indexed"] == 1, stats
PY
test -f "$cli_index_project/.springdep/project.json"

# MCP stdio handshake: initialize, list tools, call a query tool.
mcp_output="$work/mcp-output.jsonl"
{
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"e2e","version":"0"}}}'
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"find_implementations\",\"arguments\":{\"fqn\":\"example.Contract\",\"project_dir\":\"$project\"}}}"
} | "$SPRINGDEP_QUERY" mcp > "$mcp_output"
python3 - "$mcp_output" <<'PY'
import json
import pathlib
import sys

lines = [
    json.loads(line)
    for line in pathlib.Path(sys.argv[1]).read_text().splitlines()
    if line.strip()
]
assert len(lines) == 3, lines
by_id = {item["id"]: item for item in lines}
assert by_id[1]["result"]["serverInfo"]["name"] == "springdep", lines
tool_names = {tool["name"] for tool in by_id[2]["result"]["tools"]}
assert {"index_project", "find_implementations", "get_class"} <= tool_names, tool_names
call = by_id[3]["result"]
assert not call.get("isError"), call
payload = json.loads(call["content"][0]["text"])
assert payload["total"] == 2, payload
assert payload["results"][0]["fqn"] == "deep.Indirect", payload
PY

error_json="$work/error.json"
if (cd "$work" && "$SPRINGDEP_QUERY" find-config-properties spring.datasource > "$error_json"); then
  echo "query unexpectedly succeeded without a project index" >&2
  exit 1
fi
python3 - "$error_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["error"] == "no_project_index", response
PY

# --- M5: signed registry export, prefetch-only index, cache stats and GC ---
registry_dir="$work/registry"
keygen_json="$work/keygen.json"
"$SPRINGDEP_QUERY" registry keygen "$work/keys/signing.key" > "$keygen_json"
pubkey="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["public_key"])' "$keygen_json")"

export_json="$work/export.json"
"$SPRINGDEP_QUERY" registry export "$registry_dir" \
  --sign-key "$work/keys/signing.key" --home "$home1" > "$export_json"
python3 - "$export_json" <<'PY'
import json
import pathlib
import sys

result = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert result["exported"] >= 3, result
assert result["signed"] is True, result
PY

port_file="$work/registry-port"
python3 - "$registry_dir" "$port_file" <<'PY' &
import functools
import http.server
import pathlib
import socketserver
import sys

handler = functools.partial(
    http.server.SimpleHTTPRequestHandler, directory=sys.argv[1]
)
socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("127.0.0.1", 0), handler) as httpd:
    pathlib.Path(sys.argv[2]).write_text(str(httpd.server_address[1]))
    httpd.serve_forever()
PY
registry_server=$!
for _ in $(seq 1 50); do
  [ -s "$port_file" ] && break
  sleep 0.1
done
[ -s "$port_file" ] || { echo "registry server did not start" >&2; exit 1; }
registry_port="$(cat "$port_file")"

home3="$work/home3"
project3="$work/project3"
mkdir -p "$project3"
index3_json="$work/index3.json"
index3_err="$work/index3.err"
if ! "$SPRINGDEP_QUERY" index --jars "$jars" --project-dir "$project3" --home "$home3" \
  --registry "http://127.0.0.1:$registry_port" --registry-pubkey "$pubkey" \
  > "$index3_json" 2> "$index3_err"; then
  echo "registry-backed index failed:" >&2
  cat "$index3_json" "$index3_err" >&2
  exit 1
fi
kill "$registry_server" 2>/dev/null || true
registry_server=""
grep -q "downloaded" "$index3_err" || {
  echo "expected prefetch summary on stderr:" >&2
  cat "$index3_err" >&2
  exit 1
}
python3 - "$index3_json" <<'PY'
import json
import pathlib
import sys

stats = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert stats["jars_newly_indexed"] == 0, stats
assert stats["jars_skipped"] == stats["jars_indexed"], stats
assert stats["jars_indexed"] >= 3, stats
PY

registry_query_json="$work/registry-query.json"
"$SPRINGDEP_QUERY" find-implementations example.Contract \
  --project-dir "$project3" > "$registry_query_json"
python3 - "$registry_query_json" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert response["total"] == 2, response
PY

stats_json="$work/cache-stats.json"
"$SPRINGDEP_QUERY" cache stats --home "$home3" > "$stats_json"
python3 - "$stats_json" <<'PY'
import json
import pathlib
import sys

stats = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert stats["shard_count"] >= 3, stats
assert stats["shards_remote"] == stats["shard_count"], stats
PY

touch "$home3/shards/deadbeef@2.db"
gc_json="$work/cache-gc.json"
"$SPRINGDEP_QUERY" cache gc --home "$home3" > "$gc_json"
python3 - "$gc_json" <<'PY'
import json
import pathlib
import sys

result = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert result["removed_orphans"] == 1, result
assert result["remaining_shards"] >= 3, result
PY
if [ -e "$home3/shards/deadbeef@2.db" ]; then
  echo "cache gc left the orphan shard file behind" >&2
  exit 1
fi

echo "SpringDep M2 + M5 end-to-end checks passed."
