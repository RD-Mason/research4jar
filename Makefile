GRADLEW := ./gradlew
GO ?= go
INDEXER := indexer/build/install/research4jar-index/bin/research4jar-index
QUERIER := build/bin/research4jar
PREFIX ?= $(HOME)/.local

.PHONY: all build test e2e install uninstall clean

all: build

build:
	$(GRADLEW) :indexer:installDist
	mkdir -p build/bin
	cd querier && $(GO) build -o ../$(QUERIER) ./cmd/research4jar

test:
	$(GRADLEW) :indexer:test
	cd querier && $(GO) test ./...
	cd querier && $(GO) vet ./...

e2e: build
	RESEARCH4JAR_INDEX="$(CURDIR)/$(INDEXER)" \
	RESEARCH4JAR_QUERY="$(CURDIR)/$(QUERIER)" \
	./tests/e2e.sh

install: build
	mkdir -p "$(PREFIX)/bin" "$(PREFIX)/libexec"
	rm -rf "$(PREFIX)/libexec/research4jar-index"
	cp -R indexer/build/install/research4jar-index "$(PREFIX)/libexec/research4jar-index"
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
