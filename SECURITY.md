# Security policy

## Supported versions

Version Gate has not made its first public release. Security fixes are applied
to the latest code on `main`; a supported-version table will be added with the
first release.

## Report a vulnerability

Do not disclose a suspected vulnerability in a public issue, discussion, pull
request, or log excerpt.

Use **Security → Report a vulnerability** in the
[`k-barseghyan/version-gate`](https://github.com/k-barseghyan/version-gate)
repository to open a private GitHub security advisory. Include:

- affected commit or version;
- impact and prerequisites;
- reproducible steps or a minimal proof of concept;
- suggested mitigation, if known; and
- a safe way to contact you for follow-up.

Do not include production credentials, personal data, or third-party secrets.
If private vulnerability reporting is temporarily unavailable, open a public
issue that requests a private contact channel without describing the
vulnerability.

The maintainer will acknowledge a complete report as availability permits,
assess severity and affected versions, coordinate a fix and disclosure, and
credit the reporter if requested. No fixed response or remediation deadline is
promised before the project establishes a formal security response process.

## Deployment responsibility

This repository is a coordination core and contains no production storage
adapter. A runnable distribution selects third-party or separately maintained
`ControlStore` and `SnapshotStore` implementations. Operators must assess those
artifacts, their transitive dependencies, migrations, provider permissions, and
release/support policy independently; inclusion in a distribution does not make
an adapter part of this repository's security boundary.

V1 does not provide end-user authentication or TLS termination. Deploy a
composed distribution behind an authenticated TLS gateway, restrict ingress and
egress, inject adapter credentials at runtime, and configure encryption,
backup, retention, audit, rate, and request-size controls appropriate to the
snapshot data. Callback destinations are denied by default and require an exact
allowlist; network policy is still required to limit DNS-rebinding and
control-plane access.

Fencing tokens prevent stale build writers; they are not authentication
secrets. Provider errors, object metadata, and checksum headers must not be
logged with sensitive payloads or credentials. A storage adapter must fail
closed on missing or corrupt immutable content and must not expose an
unactivated manifest.
