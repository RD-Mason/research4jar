#!/usr/bin/env bash
set -euo pipefail

: "${SPRINGDEP_INDEX:?SPRINGDEP_INDEX must point to springdep-index}"
: "${SPRINGDEP_QUERY:?SPRINGDEP_QUERY must point to springdep}"

work="$(mktemp -d "${TMPDIR:-/tmp}/springdep-e2e.XXXXXX")"
trap 'rm -rf "$work"' EXIT

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
cat > "$api_src/example/Marker.java" <<'EOF'
package example;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Marker {
  String value();
}
EOF
javac -d "$api_classes" "$api_src/example/Contract.java" "$api_src/example/Marker.java"
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
public final class DirectImplementation implements Contract {
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
assert stats["jars_total"] == 7, stats
assert stats["jars_indexed"] == 6, stats
assert stats["jars_newly_indexed"] == 6, stats
assert stats["jars_skipped"] == 0, stats
assert stats["jars_missing"] == ["broken.jar"], stats
assert pointer["coverage"] == {
    "jars_total": 7,
    "jars_indexed": 6,
    "jars_missing": ["broken.jar"],
}, pointer
assert len(pointer["classpath_fingerprint"]) == 16, pointer
assert pathlib.Path(pointer["session_db_path"]).is_file(), pointer
PY

grep -q 'failed to parse META-INF/spring-configuration-metadata.json' "$first_warnings"
grep -q 'broken.jar: invalid or unreadable jar' "$first_warnings"
test "$(find "$home1/shards" -name '*.db' | wc -l | tr -d ' ')" = "6"
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
    "jars_total": 7,
    "jars_indexed": 6,
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
assert response["total"] == 1, response
assert response["results"] == [{
    "fqn": "other.DirectImplementation",
    "source_jar": "com.example:implementation:1.0",
    "attributes": None,
}], response
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
}, response
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
assert stats["jars_total"] == 7, stats
assert stats["jars_indexed"] == 6, stats
assert stats["jars_newly_indexed"] == 0, stats
assert stats["jars_skipped"] == 6, stats
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
assert stats["jars_skipped"] == 5, stats
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

echo "SpringDep M1 end-to-end checks passed."
