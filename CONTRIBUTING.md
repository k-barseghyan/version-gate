# Contributing

Thank you for helping improve Version Gate. Changes should preserve its narrow
contract: coordinate immutable snapshot publication without interpreting
client business data.

## Before opening a change

Use an issue for behavior changes, public endpoints, lifecycle transitions,
port signatures, or storage guarantees so the contract can be discussed before
implementation. Security reports follow [SECURITY.md](SECURITY.md), not the
public issue tracker.

The current refactor intentionally leaves the official PostgreSQL-control and
S3-snapshot modules as placeholders. Client SDKs, messaging systems, cache
layers, generic locks, snapshot merging/restoration, concurrent candidate
versions, and multi-resource or multi-region atomicity remain outside V1.

Official technology-specific implementations belong in dedicated modules in
this repository. Drivers, provider SDKs, migrations, emulators, container
stacks, and adapter integration tests must remain inside their adapter module;
they must never leak into `version-gate-spi` or `version-gate-core`.

## Development setup

Requirements:

- JDK 21; and
- a POSIX-like shell for the Maven Wrapper scripts.

Run the complete verification suite:

```bash
./mvnw clean verify
```

Framework-free module tests require no database, object store, or container
runtime. Concrete adapter integration tests may add scoped infrastructure when
their implementation is introduced.

## Module boundaries

- `version-gate-spi`: domain types, stable errors, and infrastructure ports;
  JDK only.
- `version-gate-core`: lifecycle and application use cases; SPI and JDK only.
- adapter modules: technology-specific infrastructure.
- `version-gate-server`: Spring MVC, OpenAPI, callbacks, scheduling, bootstrap,
  and selected adapter dependencies.
- `version-gate-testkit`: reusable semantic contracts and deterministic test
  implementations.

## Change expectations

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

Adapter modules are responsible for their own driver compatibility, migrations,
provider limits, concurrency tests, failure injection, and integration tests.
SPI changes should update `version-gate-testkit` so every adapter can
demonstrate equivalent behavior.

Format and style checks are part of `./mvnw clean verify`. Keep commits focused and
use an imperative summary line. Pull requests should explain the behavior
change, failure semantics, tests run, and compatibility or operational impact.

Contributions intentionally submitted to this repository are licensed under the
[Apache License 2.0](LICENSE).
