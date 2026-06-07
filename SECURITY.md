# Security Policy

## Reporting a Vulnerability

Do not open a public issue for a suspected vulnerability.

Send a private report to **[SECURITY CONTACT EMAIL TO BE CONFIRMED]** with:

- the affected version or commit
- reproduction steps or a proof of concept
- expected impact
- any suggested mitigation

The maintainer will acknowledge the report, assess severity, and coordinate disclosure. Response targets will be published when the contact address is finalized.

## Security Model

SpringDep treats input jars as untrusted data. The indexer reads ZIP entries and parses class bytes with ASM; it does not load classes, initialize them, or execute jar code. Malformed and unsupported class files are isolated as warnings.

Shard writes use temporary files, `fsync`, and atomic rename. The manifest records shard checksums. A future shard download feature will accept a shard only after verifying that its identity matches the local jar hash and extractor version.

This design reduces the supply-chain attack surface, but parsers, decompression, SQLite libraries, and resource exhaustion remain relevant security boundaries.
