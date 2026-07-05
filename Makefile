GRADLEW := ./gradlew
CLI_JAR := cli/build/libs/research4jar-cli-0.1.0.jar
PREFIX ?= $(HOME)/.local

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
	printf '#!/usr/bin/env bash\n# JAVA_OPTS overrides the default heap cap (extraction needs ~500MB peak).\nexec java -Xmx512m $${JAVA_OPTS:-} -jar "%s/libexec/research4jar/research4jar-cli.jar" "$$@"\n' "$(PREFIX)" > "$(PREFIX)/bin/research4jar"
	chmod 0755 "$(PREFIX)/bin/research4jar"
	@echo "Installed research4jar to $(PREFIX)/bin/research4jar"
	@echo "Ensure $(PREFIX)/bin is on your PATH."

uninstall:
	rm -f "$(PREFIX)/bin/research4jar"
	rm -rf "$(PREFIX)/libexec/research4jar"

clean:
	$(GRADLEW) clean
	rm -rf build
