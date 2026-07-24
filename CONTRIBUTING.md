# Contributing

Thank you for helping improve Version Gate. Changes should preserve its narrow
contract: coordinate immutable snapshot publication without interpreting
client business data.

## Before opening a change

Use an issue for behavior changes, public endpoints, lifecycle transitions,
port signatures, or storage guarantees so the contract can be discussed before
implementation. Security reports follow [SECURITY.md](SECURITY.md), not the
public issue tracker.

V1 intentionally excludes concrete production storage adapters, client SDKs,
messaging systems, cache layers, generic locks, snapshot merging/restoration,
concurrent candidate versions, and multi-resource or multi-region atomicity.

This repository is the core and public SPI. Technology-specific implementations
belong in separate adapter repositories, such as the anticipated non-binding
examples `version-gate-storage-postgres` and `version-gate-storage-s3`. Do not
add a storage driver, provider SDK, migration, emulator, container stack, or
adapter integration suite to the core.

## Development setup

Requirements:

- JDK 21; and
- a POSIX-like shell for the Maven Wrapper scripts.

Run the complete verification suite:

```bash
./mvnw verify
```

Core tests use fakes or mocks at the public ports and require no database,
object store, or container runtime.

## Core change expectations

- Put lifecycle rules in the domain/application boundary, not in controllers.
- Keep framework, driver, query-language, and provider SDK types out of the
  domain, application model, and storage ports.
- Preserve bounded-memory upload/download; do not introduce whole-payload
  `byte[]` or `String` buffering.
- Add deterministic tests for the public behavior and every corrected
  regression.
- Include concurrency/failure tests when changing fencing, leasing,
  participant-state ordering, completion, or activation semantics.
- Treat public manifest visibility, error codes, and retry behavior as API
  compatibility.
- Update OpenAPI, Javadocs, README, architecture documentation, and changelog
  when user-visible or adapter-visible behavior changes.
- Do not commit credentials, generated build output, IDE state, or real
  snapshot data.
- Avoid runtime dependencies unless their core value justifies their security
  and maintenance cost.

## Storage SPI changes

A storage-port change requires more than a compiling fake. Its proposal must
explain how an adapter can preserve:

- one atomic non-terminal build per resource;
- an adapter-authoritative clock read after locking;
- fence/lease/state validation and atomic transitions;
- compare-and-set activation and publish-after-activation visibility;
- immutable streaming payloads with key + length + SHA-256 verification;
- same-content idempotency and different-content conflicts;
- missing/corrupt/unavailable storage error mapping; and
- crash recovery, orphan handling, and cleanup safety.

Adapter repositories are responsible for their own driver compatibility,
migrations, provider limits, concurrency tests, failure injection, and
integration tests. Core changes should add or update reusable contract tests
where practical so every adapter can demonstrate equivalent behavior.

Format and style checks are part of `./mvnw verify`. Keep commits focused and
use an imperative summary line. Pull requests should explain the behavior
change, failure semantics, tests run, and compatibility or operational impact.

Contributions intentionally submitted to this repository are licensed under the
[Apache License 2.0](LICENSE).
