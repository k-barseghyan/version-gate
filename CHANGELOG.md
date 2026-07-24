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

### Changed

- Corrected the architecture boundary: concrete control and payload storage
  adapters belong in separately released repositories and are not retained in
  this core.
- Documented adapter requirements for authoritative lease time, atomic
  transitions and activation, publication visibility, immutable byte
  verification, crash safety, and retry semantics.

[Unreleased]: https://github.com/k-barseghyan/version-gate/commits/main
