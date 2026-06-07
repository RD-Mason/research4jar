GRADLEW := ./gradlew
GO ?= go
INDEXER := indexer/build/install/springdep-index/bin/springdep-index
QUERIER := build/bin/springdep

.PHONY: all build test e2e clean

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

clean:
	$(GRADLEW) clean
	rm -rf build
