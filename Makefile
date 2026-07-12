GRADLEW := ./gradlew
CLI_VERSION := 0.1.0
CLI_JAR := cli/build/libs/research4jar-cli-$(CLI_VERSION).jar
PREFIX ?= $(HOME)/.local

# Installed launcher. AppCDS is opportunistic: a one-time probe caches whether
# java accepts -XX:+AutoCreateSharedArchive (JDK 19+) under the data home,
# keyed by CLI version and JVM; the JVM then auto-creates and reuses a class
# archive, trimming startup. Java 8-18, unwritable homes, or any surprise just
# run plain java — CDS must never fail the actual command.
define LAUNCHER_SH
#!/usr/bin/env bash
# JAVA_OPTS overrides the default heap cap (extraction needs ~500MB peak).
# AppCDS state lives in <data-home>/cds; delete that directory to reset it.

version="$(CLI_VERSION)"
jar="$(PREFIX)/libexec/research4jar/research4jar-cli.jar"

if [ -n "$${RESEARCH4JAR_HOME:-}" ]; then
  data_home=$$RESEARCH4JAR_HOME
else
  case "$${OSTYPE:-$$(uname -s 2>/dev/null)}" in
    [Dd]arwin*) data_home="$$HOME/Library/Application Support/research4jar" ;;
    *) data_home="$${XDG_DATA_HOME:-$$HOME/.local/share}/research4jar" ;;
  esac
fi
cds_dir="$$data_home/cds"
flags_file="$$cds_dir/flags-$$version"
archive="$$cds_dir/app-$$version.jsa"
jvm_id="$$(command -v java 2>/dev/null):$${JAVA_HOME:-}"

# The cached answer only holds for the JVM that was probed — stale CDS flags
# are fatal on older JVMs — so a JVM switch drops the cache and re-probes.
cached_jvm=
[ -r "$$flags_file" ] && IFS= read -r cached_jvm < "$$flags_file"
[ -e "$$flags_file" ] && [ "$$cached_jvm" != "$$jvm_id" ] && rm -f "$$flags_file" "$$archive" 2>/dev/null

if [ ! -e "$$flags_file" ] && mkdir -p "$$cds_dir" 2>/dev/null && [ -w "$$cds_dir" ]; then
  tmp="$$flags_file.$$$$.tmp"
  if java -XX:+AutoCreateSharedArchive "-XX:SharedArchiveFile=$$archive" -Xlog:cds=off -version >/dev/null 2>&1; then
    printf '%s\n' "$$jvm_id" -XX:+AutoCreateSharedArchive "-XX:SharedArchiveFile=$$archive" -Xlog:cds=off > "$$tmp" 2>/dev/null
  else
    printf '%s\n' "$$jvm_id" > "$$tmp" 2>/dev/null
  fi
  # Drop the probe's minimal archive so the first real run dumps a full one.
  rm -f "$$archive" 2>/dev/null
  mv -f "$$tmp" "$$flags_file" 2>/dev/null || rm -f "$$tmp" 2>/dev/null
fi

# One flag per line, first line is the JVM id, so paths with spaces survive.
# Skip CDS when the directory is unwritable: a failed exit-time archive dump
# would fail the whole command.
cds=()
if [ -r "$$flags_file" ] && [ -w "$$cds_dir" ]; then
  header=1
  while IFS= read -r line; do
    if [ -n "$$header" ]; then header=; continue; fi
    [ -n "$$line" ] && cds+=("$$line")
  done < "$$flags_file"
fi

exec java -Xmx512m "$${cds[@]}" $${JAVA_OPTS:-} -jar "$$jar" "$$@"
endef
export LAUNCHER_SH

.PHONY: all build test e2e install uninstall clean

all: build

build:
	$(GRADLEW) :cli:shadowJar

test:
	$(GRADLEW) check

e2e: build
	./tests/run-e2e-java.sh "$(CURDIR)/$(CLI_JAR)"

install: build
	mkdir -p "$(PREFIX)/bin" "$(PREFIX)/libexec/research4jar"
	install -m 0644 "$(CLI_JAR)" "$(PREFIX)/libexec/research4jar/research4jar-cli.jar"
	printf '%s\n' "$$LAUNCHER_SH" > "$(PREFIX)/bin/research4jar"
	chmod 0755 "$(PREFIX)/bin/research4jar"
	@echo "Installed research4jar to $(PREFIX)/bin/research4jar"
	@echo "Ensure $(PREFIX)/bin is on your PATH."

uninstall:
	rm -f "$(PREFIX)/bin/research4jar"
	rm -rf "$(PREFIX)/libexec/research4jar"

clean:
	$(GRADLEW) clean
	rm -rf build
