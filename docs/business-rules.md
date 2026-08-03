# Business rules and policy model

## Status and purpose

This document records the accepted target business model for Version Gate. It
is informative during the design phase and is the source of truth for the next
implementation phase. The current SPI, lifecycle, HTTP API, and configuration
do not yet implement all rules described here.

The model deliberately separates four flows:

1. a coordinated write;
2. a coordinated live read of distributed data;
3. coordinated generation of an immutable snapshot; and
4. retrieval of an already stored immutable snapshot.

The first three flows coordinate access to live distributed data. Stored
snapshot retrieval does not: immutable data can be read without blocking live
operations.

## Responsibility boundary

Version Gate coordinates only clients that participate in its protocol. It
cannot prevent a service from changing or reading distributed data without
first registering the operation.

Version Gate:

- allocates coordinator versions for accepted writes;
- persists leased, fenced operation sessions;
- applies the configured admission and conflict policies atomically;
- binds snapshot-generation sessions to the active coordinator version;
- accepts, verifies, and stores immutable snapshot data; and
- returns stored snapshots according to an explicit selection policy.

Clients:

- register before writing or coherently reading live distributed data;
- honor leases, fencing, rejection, and invalidation results;
- perform the actual distributed write, live read, or snapshot aggregation;
- stop work after losing a lease or receiving invalidation; and
- decide whether and when to retry a rejected request.

Version Gate does not interpret, join, merge, restore, or business-validate
snapshot payloads. It does not wait on behalf of a client or trigger snapshot
generation through callbacks in the accepted target model.

## Fixed invariants

The following rules are not configurable:

1. Writes never overlap other writes.
2. Writes never overlap coordinated live reads.
3. A snapshot is never stored if a write overlapped its generation.
4. Only one snapshot-generation session may exist for a resource version.
5. A snapshot-generation session is bound to the active version observed when
   the session begins. The provider does not choose that version later.
6. Stored snapshots are immutable. An identical submission is idempotent; a
   different representation for the same resource and version conflicts.
7. Successful write completion activates its allocated version atomically.
   There is no intermediate `READY` business state.
8. Failed, abandoned, and expired write versions are never reused; gaps in the
   coordinator-version sequence are valid.
9. Explicit retrieval by version never acquires a live-data coordination lock.
10. Admission, invalidation, completion, and activation decisions are durable
    and serialized by the authoritative control store.

The configured policy decides which operation loses a snapshot/write conflict;
it never permits an incoherent snapshot to become stored and visible.

## Operations

The operation names below describe business capabilities. Exact HTTP paths and
Java method names will be defined when this model is implemented.

| Operation | Role | Durable coordination effect |
| --- | --- | --- |
| `beginWrite` | Register a distributed write | Allocates the next version and creates an exclusive leased, fenced write session. |
| `completeWrite` | Finish a successful write | Atomically activates the allocated version and releases write coordination. |
| `failWrite` / `abandonWrite` | End an unsuccessful write | Terminates the session without changing the active version. |
| `beginLiveRead` | Register a coherent read of live distributed data | Creates a leased read session bound to the current active version and blocks writers. Multiple live reads may coexist. |
| `completeLiveRead` | Finish a live read | Releases that reader's coordination claim. |
| `beginSnapshot` | Register external snapshot aggregation | Creates a leased snapshot-generation session bound to the current active version. |
| `submitSnapshot` | Finish a valid snapshot generation | Verifies and atomically publishes the immutable snapshot for the session's bound version. |
| `abortSnapshot` | End snapshot generation without a snapshot | Terminates the session and stores nothing. |
| `getSnapshotByVersion` | Retrieve one known immutable version | Returns that snapshot if retained, regardless of live operations. |
| `getCurrentSnapshot` | Retrieve the active version's snapshot | Fails if the active version has no stored snapshot. |
| `getLatestAvailableSnapshot` | Retrieve the highest stored snapshot version | May return a snapshot older than the active version and reports both versions. |

Every coordination operation is fail-fast. Rejection does not create a server-
side wait, queue, or retry loop.

## Operation compatibility

This table describes admission while operations are active. Snapshot read
details are refined in [Snapshot retrieval](#snapshot-retrieval).

| Currently active | New write | New live read | New snapshot generation | Stored snapshot read |
| --- | --- | --- | --- | --- |
| Nothing | Apply the missing-current-snapshot policy, then allow | Allow | Allow when snapshot support is enabled and the target has no stored snapshot | Evaluate the requested selector |
| Write | Reject | Reject | Reject | By-version is allowed; current/latest follows its write policy |
| Live read(s) | Reject | Allow | Allow | Evaluate the requested selector |
| Snapshot generation | Apply the snapshot/write conflict policy | Allow | Reject | Evaluate the requested selector |
| Live read(s) and snapshot generation | Reject because a live read is active | Allow | Reject | Evaluate the requested selector |

The live-read rule has precedence. If both live reads and snapshot generation
are active, an arriving writer is rejected even when the configured snapshot
policy would otherwise invalidate the snapshot session.

## Snapshot configuration

Policies are independent dimensions so clients can express the consistency and
availability trade-off they actually need. Configuration is resource-scoped.

### Snapshot support

| Value | Rule |
| --- | --- |
| `DISABLED` | Snapshot generation and non-by-version snapshot selection are unavailable. Writes and coordinated live reads remain supported. |
| `ENABLED` | Snapshot generation and retrieval policies apply. |

Snapshots are a secondary capability. A resource may use Version Gate only to
coordinate writers and live readers.

### Missing current snapshot when a writer arrives

| Value | Rule |
| --- | --- |
| `ALLOW_GAP` | A missing snapshot for the active version does not prevent the next write. |
| `REQUIRE_CURRENT_SNAPSHOT` | Reject the writer until a snapshot for the active version exists. |

This check applies only after a resource has an active version. It must not make
the first write impossible. The exact bootstrap behavior for pre-existing data
is still an implementation-phase decision.

`ALLOW_GAP` permits stored versions such as 7, 8, and 10 with no snapshot for
version 9. The coordinator versions remain ordered even when snapshot versions
contain gaps.

### Writer arriving during snapshot generation

| Value | Rule |
| --- | --- |
| `BLOCK_WRITER` | Reject the writer and allow snapshot generation to continue. |
| `INVALIDATE_SNAPSHOT` | Atomically invalidate the snapshot session and admit the writer, provided no live read blocks it. A later submission for the invalidated session is rejected and nothing is stored. |

`INVALIDATE_SNAPSHOT` is compatible only with `ALLOW_GAP`. Combining it with
`REQUIRE_CURRENT_SNAPSHOT` would admit a writer while deliberately preventing
the required current snapshot, so configuration validation must reject that
combination.

### Complete admission table

| Snapshot support | Missing-current policy | Snapshot/write conflict | Missing current snapshot | Writer during snapshot generation |
| --- | --- | --- | --- | --- |
| `DISABLED` | `ALLOW_GAP` | Not applicable | Allow writer | Snapshot generation is unavailable |
| `ENABLED` | `ALLOW_GAP` | `BLOCK_WRITER` | Allow writer | Reject writer |
| `ENABLED` | `ALLOW_GAP` | `INVALIDATE_SNAPSHOT` | Allow writer | Invalidate snapshot atomically, then admit writer unless a live read exists |
| `ENABLED` | `REQUIRE_CURRENT_SNAPSHOT` | `BLOCK_WRITER` | Reject writer | Reject writer and allow generation to continue |

All other combinations are invalid configuration.

## Snapshot retrieval

Snapshot retrieval selects immutable stored data; it never joins a live-read
session and never blocks another operation.

### Selector

| Selector | Result |
| --- | --- |
| `BY_VERSION(version)` | Return the requested version if it exists and has not been removed by retention. Ignore active writes. |
| `CURRENT` | Require the snapshot whose version exactly equals the active completed version. A gap is an error. |
| `LATEST_AVAILABLE` | Return the highest stored snapshot version, even when it is older than the active version. |

`CURRENT` is used instead of the ambiguous name `LATEST`: the latest active
coordinator version and the latest available snapshot version may differ.

Every successful response exposes at least `snapshotVersion` and the active
version observed while resolving the request. A `LATEST_AVAILABLE` response is
explicitly stale when `snapshotVersion < activeVersion`; the service never
presents an older snapshot as current.

Snapshot order comes exclusively from Version Gate's per-resource coordinator
versions. Arrival time, timestamps, hashes, and payload content never determine
which snapshot is newer.

### Concurrent-write policy

For `CURRENT` and `LATEST_AVAILABLE`, clients may configure:

| Value | Rule while a write is active |
| --- | --- |
| `ALLOW_WHILE_WRITING` | Resolve the selector normally. The previously active version remains active until the write succeeds. |
| `REJECT_IF_WRITING` | Fail with a write-in-progress result; the client decides whether and when to retry. |

Explicit `BY_VERSION` retrieval remains allowed regardless of this policy. A
request may still fail because the specified snapshot never existed or was
removed by retention.

Version Gate does not provide `WAIT_FOR_CURRENT`. Server-side waiting is not
part of the service's responsibility.

### Retrieval examples

Assume active version 9, write 10 in progress, and stored snapshots 7 and 8:

| Request | Result |
| --- | --- |
| `BY_VERSION(8)` | Snapshot 8 |
| `LATEST_AVAILABLE + ALLOW_WHILE_WRITING` | Snapshot 8 with `activeVersion=9` and stale metadata |
| `LATEST_AVAILABLE + REJECT_IF_WRITING` | Write-in-progress rejection |
| `CURRENT + ALLOW_WHILE_WRITING` | Current-snapshot-not-available rejection because snapshot 9 is absent |
| `CURRENT + REJECT_IF_WRITING` | Write-in-progress rejection |

## Lifecycle and correlation

### Write

```text
beginWrite
  -> durable BUILDING session with version, lease, and fencing token
  -> client changes distributed data
  -> completeWrite atomically makes the version ACTIVE

BUILDING -> FAILED or ABANDONED on unsuccessful termination
```

There is no `READY` state. Completion and activation are one business
operation. The control store allocates versions from a monotonically increasing
per-resource sequence at accepted `beginWrite`; allocation is based on storage
serialization order, not timestamps or client values.

### Live read

`beginLiveRead` creates a durable leased session before the client reads live
distributed data. The session binds the read to the current active version and
prevents new writers until completion or authoritative lease expiry. Several
live-read sessions may coexist.

### Snapshot generation

```text
beginSnapshot
  -> durable SNAPSHOTTING session bound to active version 9
  -> provider reads and aggregates the stable distributed data
  -> submitSnapshot(session, payload)
  -> immutable snapshot for version 9 exists
```

`SNAPSHOTTING` describes the registered external generation window, not HTTP
upload progress. The stored snapshot has no lifecycle status: it either exists
in complete, verified form or does not exist.

The session removes correlation ambiguity. The provider submits its session
token; it does not claim that arbitrary incoming bytes belong to the current
version. If `INVALIDATE_SNAPSHOT` admits a writer, the invalidated terminal
session record must remain durable long enough to reject a late submission.

Finalization and writer admission have one serialization order. If snapshot
finalization commits first, the snapshot exists before the writer is evaluated.
If invalidation and write admission commit first, later finalization fails with
the invalidated-session outcome.

For a multi-component snapshot, component upload may be staged behind the
session, but no partial snapshot is visible. Finalization verifies the complete
required component set and publishes it atomically.

## Conceptual rejection outcomes

Exact public error-code names remain an implementation decision, but clients
must be able to distinguish these outcomes:

| Outcome | Meaning |
| --- | --- |
| Write already active | Another writer owns the resource. |
| Live read active | One or more coordinated live readers block the writer. |
| Snapshot generation active | The configured policy gives the current snapshot session priority. |
| Current snapshot required | The active version has no stored snapshot and policy forbids a new write. |
| Snapshot invalidated | A writer won the configured conflict and the submitted snapshot must not be stored. |
| Snapshot support disabled | The resource does not provide snapshot operations. |
| Snapshot not found | The explicitly requested or selected immutable snapshot does not exist. |
| Current snapshot unavailable | The active version exists but has no stored snapshot. |
| Write in progress | Retrieval policy rejects current/latest selection during a write. |
| Lease expired or stale fence | The caller no longer owns the registered operation. |

Failure precedence must be deterministic when several conditions are true. In
particular, an active live read prevents write admission before snapshot
invalidation is considered, and an explicit by-version read ignores the
concurrent-write retrieval policy.

## Informative configuration shape

The future configuration guide should expose these independent choices rather
than one large policy enum:

```yaml
snapshot:
  support: ENABLED
  missing-current: ALLOW_GAP
  writer-during-generation: INVALIDATE_SNAPSHOT
  retrieval:
    default-selector: LATEST_AVAILABLE
    during-write: ALLOW_WHILE_WRITING
```

These keys are illustrative only and are not accepted by the current server.
Defaults, configuration mutability, retention, authorization, exact endpoint
shapes, initial-resource/bootstrap semantics, and public error-code names must
be decided with the implementation. Storage topology is also separate from
these business rules; this document does not choose PostgreSQL-only or split
control/payload storage.
