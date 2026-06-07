# Contributing to SpringDep

SpringDep is early-stage software. Focused bug reports, reproducible fixtures, documentation corrections, and small well-tested pull requests are especially useful.

## Development Setup

Requirements:

- JDK 17 or newer
- Go 1.23 or newer
- Git and Bash

Build both binaries:

```bash
make build
```

Run all unit and static checks:

```bash
make test
```

Run the generated cross-language integration suite:

```bash
make e2e
```

Gradle resolves fixed-version Spring jars for golden tests. Do not commit third-party jar files. The actuator oracle test compares static `@Bean` and condition facts with runtime actuator reports.

## Code Style

- Follow the existing Kotlin and Go package structure.
- Keep the SQLite schema and JSON command contracts explicit and deterministic.
- Do not load classes or execute code from indexed jars.
- Use structured parsers for bytecode, properties, JSON, and SQL data.
- Keep changes scoped; avoid unrelated formatting or refactors.
- Run `gofmt` on Go changes.

## Tests

Add coverage proportional to the behavior changed:

- Extraction changes should include a bytecode fixture or a fixed real-jar assertion.
- Storage changes should verify determinism and cross-shard ID integrity.
- Query changes should test pagination, source provenance, JSON output, and coverage.
- Runtime semantics should be sampled against Spring's condition and bean reports when applicable.

## Branches and Pull Requests

1. Create a focused branch from `main`.
2. Add or update tests with the implementation.
3. Run `make test` and `make e2e`.
4. Open a pull request describing the behavior, compatibility impact, and verification.
5. Link the relevant issue when one exists.

CI must pass on Ubuntu and Windows before merge. Maintainers may ask for a smaller change when a pull request mixes unrelated concerns.

## Commit Messages and DCO

Conventional Commits are recommended, for example:

```text
feat(indexer): extract direct method annotations
fix(query): preserve source jar during pagination
docs: clarify direct annotation matching
```

Contributors certify the Developer Certificate of Origin by adding a sign-off:

```bash
git commit -s -m "feat: describe the change"
```

The sign-off records that you have the right to submit the contribution under the project's license.

## Conduct

All participants must follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
