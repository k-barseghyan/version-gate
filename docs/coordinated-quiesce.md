# Coordinated quiescence protocol

`COORDINATED_QUIESCE` coordinates a protected capture window across registered
HTTP participants. It is deliberately cooperative: Version Gate cannot suspend
writes in another service or database. The consistency of the result depends on
every participant honoring the protocol.

This repository defines the protocol and implements its core use cases and HTTP
callback gateway. Durable build/participant progress and immutable component
payloads come from the `ControlStore` and `SnapshotStore` supplied by a composed
distribution. The official PostgreSQL-control and S3-snapshot modules are
placeholders until their concrete implementations are added.

No Java SDK is required. A participant exposes:

```text
POST /version-control/quiesce
POST /version-control/capture
POST /version-control/resume
POST /version-control/abort
```

## Protocol

1. Version Gate creates a fenced build in `BUILDING`.
2. It moves the build to `QUIESCING` and calls `quiesce` for every required
   participant.
3. Each participant reaches its own safe boundary, blocks or redirects relevant
   writes, persists the build/fence it accepted, and acknowledges.
4. Only after every participant acknowledges does Version Gate enter
   `SNAPSHOTTING` and call `capture`.
5. Participants produce and upload their required components through the
   composed distribution using the build's current fencing token. Its
   `SnapshotStore` must verify immutable bytes according to the public SPI. All
   capture callbacks must acknowledge before the build can be completed.
6. After all required components are present, Version Gate finalizes the
   manifest as `READY`.
7. Version Gate invokes `resume` on participants. Activation changes only the
   Version Gate active pointer; it never writes to participant databases.
8. If quiescence or capture cannot complete, Version Gate first durably records
   the build as `FAILED` or `ABANDONED`, then invokes best-effort `abort` (and
   resume behavior where necessary). The previous active version remains
   active.

## Callback request

All four callbacks receive the same JSON shape. This example represents a
`quiesce` action:

```json
{
  "protocolVersion": 1,
  "actionId": "45313938-04a5-3c48-bfa6-32ac4cb5e015",
  "participantId": "catalog-database",
  "buildId": "4dd965e8-4eb5-4bb1-bc58-bd95981f57f4",
  "resourceId": "catalog",
  "targetVersion": 42,
  "baseActiveVersion": 41,
  "fencingToken": 17,
  "leaseExpiresAt": "2030-01-02T03:04:05Z"
}
```

`targetVersion` in this callback is the coordinator version already allocated
by Version Gate. It is output context for the participant, not a value supplied
in the public begin-build request.

Each request also carries:

```http
Content-Type: application/json
Idempotency-Key: 45313938-04a5-3c48-bfa6-32ac4cb5e015
X-Version-Gate-Protocol-Version: 1
```

`actionId` is a deterministic UUID derived from the build ID, participant ID,
and operation. The same logical callback therefore has the same body action ID
and `Idempotency-Key` after a retry or coordinator restart. Different
participants and operations receive different action IDs. The protocol version
in the header and body must agree.

Action-level idempotency is necessary but not sufficient. Duplicate client
requests and callback retries can race, and different operations deliberately
have different action IDs. Participants must also enforce phase ordering for a
build and fence. Once a matching `resume` or `abort` has been accepted, that
release action is terminal and dominant: a later or reordered `quiesce` or
`capture` for the same build/fence must be rejected or treated as a no-op, even
though it carries another valid action ID. It must never re-protect writes or
start a new capture.

`baseActiveVersion` is `null` when the resource has no previously active
version. Participants already know which components they own from their local
configuration. A participant uploads those components through Version Gate's
public component endpoint; the callback does not carry component assignments or
payload bytes.

Any `2xx` response completes the callback. A non-`2xx` response, connection
failure, or timeout fails that callback attempt. The response body is ignored.
Callbacks may be delivered again after an ambiguous timeout.

Resource registration associates participants with their callback base URIs:

```json
{
  "resourceId": "catalog",
  "snapshotPolicy": "COORDINATED_QUIESCE",
  "requiredComponentIds": ["products", "prices"],
  "participants": [
    {
      "participantId": "catalog-database",
      "baseUri": "https://catalog.internal"
    }
  ]
}
```

Participant base URIs must use an operator-approved network boundary. Production
deployments should require HTTPS, reject redirects to untrusted destinations,
and prevent callback access to link-local or control-plane endpoints.
Version Gate requires each normalized, exact base URI to appear in
`VERSION_GATE_PARTICIPANT_ALLOWED_BASE_URIS`; scheme, host, effective port, and
base path are all significant, and an empty list denies every callback.
Participant destinations are validated before coordinated resource registration
is persisted. Plain HTTP is rejected unless
`VERSION_GATE_PARTICIPANT_ALLOW_HTTP=true`. Operators must still restrict
service egress and protect DNS because URI validation alone cannot prevent DNS
rebinding.

Callback authentication is deployment-specific and must be configured at the
HTTP boundary; neither the action ID nor the fencing token is a bearer
credential.

The build lease must cover the whole coordinated capture and activation flow.
Renewal is supported while the build remains `BUILDING`, but V1 rejects renewal
after it enters `QUIESCING`: changing `leaseExpiresAt` would change the callback
body while reusing the same idempotent action ID.

V1 sends callbacks synchronously and applies the configured timeout to each
request. The server defaults to at most eight registered participants per
resource and always enforces a hard maximum of 32. A distribution should choose
a smaller configured maximum when necessary so the complete quiesce, capture,
resume, and failure-cleanup window fits its lease and ingress timeout budgets.

## Participant requirements

A correct participant:

- persists the result for each `actionId` and returns that stable result when
  the same action is delivered again;
- rejects a reused `actionId` whose protocol version or body differs;
- rejects a lower fencing token after accepting a higher one for the resource;
- persists ordered phase state per build/fence, with `resume` and `abort`
  terminal and dominant over any later/reordered `quiesce` or `capture`;
- does not acknowledge `quiesce` until the relevant writes are actually
  protected;
- remains protected until a matching `resume` or `abort` is processed;
- makes repeated `quiesce`, `capture`, `resume`, and `abort` calls safe;
- uploads exact, immutable bytes with a SHA-256;
- automatically resumes protected writes no later than `leaseExpiresAt`, so a
  lost coordinator cannot block writes indefinitely; and
- records enough state to recover safely after its own restart.

An HTTP success means the participant completed the requested phase, not merely
that it accepted a request. For `capture`, the participant defines whether
success means its protected capture action was synchronously completed or
durably initiated, but it must not acknowledge before its own safety contract is
satisfied. Version Gate cannot enforce that contract. Because callbacks may be
retried with the same action ID, a participant must not create a second snapshot
for a duplicate `capture` unless it produces the same immutable component
identity and bytes.

## Failure semantics

The callback exchange cannot be an atomic commit across participants. In
particular:

- a timeout is ambiguous, and the callback may have completed;
- some participants may be quiescent while another fails;
- `resume` and `abort` are best-effort network operations;
- a participant can violate the protocol or resume itself at its safety
  deadline; and
- Version Gate cannot validate cross-component business consistency.

On a failure, the coordinator records participant progress, stops advancing the
build through the distribution's `ControlStore`, attempts safe cleanup
callbacks in that request, and never activates an incomplete manifest. The
control adapter must forbid participant-state regression out of `RESUMED` or
`ABORTED`, including when duplicate client requests race. V1 has no durable
callback reconciliation worker.
Additional cleanup or recovery retries require an explicit client/operator
replay. Every participant must therefore also release protected writes at its
recorded `leaseExpiresAt` deadline. Operators must monitor both coordinator
state and participant-local quiescence deadlines.
