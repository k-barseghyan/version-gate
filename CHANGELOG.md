# Changelog

All notable user-visible changes to Version Gate are documented here. This
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once a
public version is released.

## [Unreleased]

### Added

- Initial Java 21 and Spring Boot 4.0.7 coordination core.
- Resource registration and fenced single-build lifecycle.
- `CLIENT_MANAGED` and cooperative `COORDINATED_QUIESCE` snapshot policies.
- Public `ControlStore`, `SnapshotStore`, and participant gateway SPIs.
- REST/OpenAPI, participant callback, error, and configuration contracts.
- Unit and contract-focused tests for lifecycle, HTTP, streaming, fencing, and
  idempotency behavior.
- Bounded participant fan-out, exact callback wire-protocol enforcement, and
  sanitized storage-failure Problem Details with correlation IDs.
- Reusable `version-gate-testkit` contracts for control and snapshot adapters.

### Changed

- Refactored the repository into SPI, core, PostgreSQL-control, S3-snapshot,
  server, and testkit Maven modules.
- Moved HTTP, OpenAPI, callbacks, scheduling, and Spring Boot bootstrap into the
  executable `version-gate-server` module.
- Made `ControlStore.beginBuild` atomically allocate coordinator versions and
  fencing tokens; clients no longer submit `targetVersion`.
- Added official PostgreSQL-control and S3-snapshot placeholder modules while
  keeping concrete adapter implementations outside this refactoring change.
- Documented adapter requirements for authoritative lease time, atomic
  transitions and activation, publication visibility, immutable byte
  verification, crash safety, and retry semantics.

[Unreleased]: https://github.com/k-barseghyan/version-gate/commits/main
