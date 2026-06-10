GRADLEW := ./gradlew
GO ?= go
INDEXER := indexer/build/install/springdep-index/bin/springdep-index
QUERIER := build/bin/springdep
PREFIX ?= $(HOME)/.local

.PHONY: all build test e2e install uninstall clean

all: build

build:
	$(GRADLEW) :indexer:installDist
	mkdir -p build/bin
	cd querier && $(GO) build -o ../$(QUERIER) ./cmd/springdep

test:
	$(GRADLEW) :indexer:test
	cd querier && $(GO) test ./...
	cd querier && $(GO) vet ./...

e2e: build
	SPRINGDEP_INDEX="$(CURDIR)/$(INDEXER)" \
	SPRINGDEP_QUERY="$(CURDIR)/$(QUERIER)" \
	./tests/e2e.sh

install: build
	mkdir -p "$(PREFIX)/bin" "$(PREFIX)/libexec"
	rm -rf "$(PREFIX)/libexec/springdep-index"
	cp -R indexer/build/install/springdep-index "$(PREFIX)/libexec/springdep-index"
	install -m 0755 "$(QUERIER)" "$(PREFIX)/bin/springdep"
	ln -sf "../libexec/springdep-index/bin/springdep-index" "$(PREFIX)/bin/springdep-index"
	@echo "Installed springdep to $(PREFIX)/bin/springdep"
	@echo "Ensure $(PREFIX)/bin is on your PATH."

uninstall:
	rm -f "$(PREFIX)/bin/springdep" "$(PREFIX)/bin/springdep-index"
	rm -rf "$(PREFIX)/libexec/springdep-index"

clean:
	$(GRADLEW) clean
	rm -rf build
