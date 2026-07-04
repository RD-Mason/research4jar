GRADLEW := ./gradlew
GO ?= go
INDEXER := core/build/install/research4jar-index/bin/research4jar-index
QUERIER := build/bin/research4jar
CLI_JAR := cli/build/libs/research4jar-cli-0.1.0.jar
PREFIX ?= $(HOME)/.local

.PHONY: all build test e2e e2e-java install uninstall clean

all: build

build:
	$(GRADLEW) :core:installDist :cli:shadowJar
	mkdir -p build/bin
	cd querier && $(GO) build -o ../$(QUERIER) ./cmd/research4jar

test:
	$(GRADLEW) :core:check :cli:check
	cd querier && $(GO) test ./...
	cd querier && $(GO) vet ./...

e2e: build
	RESEARCH4JAR_INDEX="$(CURDIR)/$(INDEXER)" \
	RESEARCH4JAR_QUERY="$(CURDIR)/$(QUERIER)" \
	./tests/e2e.sh

# The same suite driven end to end by the JVM CLI (M6 parity gate).
e2e-java: build
	./tests/run-e2e-java.sh "$(CURDIR)/$(CLI_JAR)"

install: build
	mkdir -p "$(PREFIX)/bin" "$(PREFIX)/libexec"
	rm -rf "$(PREFIX)/libexec/research4jar-index"
	cp -R core/build/install/research4jar-index "$(PREFIX)/libexec/research4jar-index"
	install -m 0755 "$(QUERIER)" "$(PREFIX)/bin/research4jar"
	ln -sf "../libexec/research4jar-index/bin/research4jar-index" "$(PREFIX)/bin/research4jar-index"
	@echo "Installed research4jar to $(PREFIX)/bin/research4jar"
	@echo "Ensure $(PREFIX)/bin is on your PATH."

uninstall:
	rm -f "$(PREFIX)/bin/research4jar" "$(PREFIX)/bin/research4jar-index"
	rm -rf "$(PREFIX)/libexec/research4jar-index"

clean:
	$(GRADLEW) clean
	rm -rf build
