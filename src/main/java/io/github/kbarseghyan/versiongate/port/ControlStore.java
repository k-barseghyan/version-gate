package io.github.kbarseghyan.versiongate.port;

import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.BuildStatus;
import io.github.kbarseghyan.versiongate.domain.Participant;
import io.github.kbarseghyan.versiongate.domain.ParticipantState;
import io.github.kbarseghyan.versiongate.domain.ParticipantStatus;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.SnapshotComponent;
import io.github.kbarseghyan.versiongate.domain.SnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.VersionManifest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative storage contract for Version Gate control state.
 *
 * <p>Implementations are production adapters supplied outside this repository. They must be
 * thread-safe, durable across process restarts, and enforce the documented atomicity rather than
 * relying on application-layer prechecks. In particular, an adapter owns the authoritative clock:
 * it must evaluate lease expiry inside the same critical section as each mutation and only after
 * acquiring any contended lock. Caller or service-process time is never authoritative.
 *
 * <p>Expected conflicts must be reported with the matching {@code ErrorCode} in a {@code
 * VersionGateException}; backend availability or integrity failures use {@code STORAGE_FAILURE}. No
 * framework-, driver-, or vendor-specific type may escape this SPI.
 *
 * <p>Build mutations use one stable precedence under their lock: unknown build ({@code
 * BUILD_NOT_FOUND}), wrong fence ({@code STALE_FENCING_TOKEN}), exact committed replay, expired
 * lease for a lease-sensitive first attempt ({@code LEASE_EXPIRED}), invalid policy/state ({@code
 * INVALID_BUILD_TRANSITION}), then operation-specific completeness or activation-CAS checks. Thus a
 * wrong fence never becomes a replay success, while an exact committed component, completion,
 * activation, abort, or failure replay remains stable after lease expiry. The repository
 * architecture guide defines the complete per-operation mapping.
 */
public interface ControlStore {

  /**
   * Registers an immutable resource definition.
   *
   * <p>The resource ID is unique. Policy, required components, and participants cannot be changed
   * after a successful call. A duplicate call reports {@code RESOURCE_ALREADY_EXISTS}.
   *
   * @param resourceId unique resource identifier
   * @param snapshotPolicy capture policy applied to every build
   * @param requiredComponentIds non-empty immutable component definition
   * @param participants coordinated callback endpoints, empty for client-managed resources and
   *     never above the core domain limit
   * @return newly persisted resource registration
   */
  Resource registerResource(
      String resourceId,
      SnapshotPolicy snapshotPolicy,
      Set<String> requiredComponentIds,
      List<Participant> participants);

  /**
   * Returns the resource definition, if registered.
   *
   * @param resourceId resource identifier
   * @return registered resource, or empty when no registration exists
   */
  Optional<Resource> findResource(String resourceId);

  /**
   * Atomically creates a fenced candidate build only when the resource has no non-terminal build.
   *
   * <p>The adapter may abandon an expired candidate in the same critical section. Fencing tokens
   * must never be reused for a resource, the target version must be unique, and it must be greater
   * than the active version when one exists. For a coordinated resource, the same atomic operation
   * initializes exactly one {@code PENDING} participant state for every registered participant.
   *
   * @param resourceId registered resource identifier
   * @param targetVersion non-negative version to produce
   * @param owner caller-defined build owner
   * @param leaseDuration positive initial lease duration
   * @return newly created fenced build
   */
  Build beginBuild(String resourceId, long targetVersion, String owner, Duration leaseDuration);

  /**
   * Atomically renews a live build after validating its fence, status, and unexpired lease.
   *
   * <p>For {@code COORDINATED_QUIESCE}, renewal is permitted only while the build is {@code
   * BUILDING}; this policy check must be atomic with the renewal.
   *
   * @param buildId build to renew
   * @param fencingToken current positive build token
   * @param leaseDuration positive duration measured from the adapter's authoritative time
   * @return build with its renewed lease deadline
   */
  Build renewBuild(UUID buildId, long fencingToken, Duration leaseDuration);

  /**
   * Atomically enters the requested snapshot phase after validating fence, lease, and lifecycle
   * state. The target must match the resource policy ({@code SNAPSHOTTING} for client-managed,
   * {@code QUIESCING} for coordinated). Replaying an already successful transition returns the
   * stable current build.
   *
   * @param buildId build entering its snapshot phase
   * @param fencingToken current positive build token
   * @param targetStatus policy-specific first snapshot-phase status
   * @return transitioned build, or the stable result of a successful replay
   */
  Build startSnapshotPhase(UUID buildId, long fencingToken, BuildStatus targetStatus);

  /**
   * Atomically advances a coordinated build from {@code QUIESCING} to {@code SNAPSHOTTING}. Replays
   * after success are idempotent.
   *
   * @param buildId coordinated build that completed quiescence
   * @param fencingToken current positive build token
   * @return snapshotting build, including on a successful replay
   */
  Build markSnapshotting(UUID buildId, long fencingToken);

  /**
   * Returns a build by its stable ID, including terminal builds.
   *
   * @param buildId stable build identifier
   * @return build, or empty when the identifier is unknown
   */
  Optional<Build> findBuild(UUID buildId);

  /**
   * Returns the resource's current non-terminal build.
   *
   * <p>An expired build must be atomically marked {@code ABANDONED} and omitted.
   *
   * @param resourceId registered resource identifier
   * @return current live build, or empty when none exists
   */
  Optional<Build> findCurrentBuild(String resourceId);

  /**
   * Atomically registers immutable component metadata for a snapshotting build.
   *
   * <p>For a new component, the adapter validates build identity, fence, live lease, phase, and
   * required-component membership. An exact replay must match the original object key, size, and
   * SHA-256 and returns the original component even after completion; a replay with a different
   * object key, size, or SHA-256 reports {@code COMPONENT_CONFLICT}.
   *
   * @param buildId build that owns the component
   * @param fencingToken current positive build token
   * @param component authoritative immutable metadata to register
   * @return newly registered component or the original component for an exact replay
   */
  SnapshotComponent registerSnapshotComponent(
      UUID buildId, long fencingToken, SnapshotComponent component);

  /**
   * Returns component metadata regardless of build visibility; intended for internal use cases.
   *
   * @param resourceId owning resource identifier
   * @param version candidate version
   * @param componentId component identifier
   * @return component metadata, or empty when absent
   */
  Optional<SnapshotComponent> findSnapshotComponent(
      String resourceId, long version, String componentId);

  /**
   * Returns all registered components for a candidate version in deterministic ID order.
   *
   * @param resourceId owning resource identifier
   * @param version candidate version
   * @return immutable component list, possibly empty
   */
  List<SnapshotComponent> findSnapshotComponents(String resourceId, long version);

  /**
   * Atomically finalizes one immutable manifest and moves a complete build to {@code READY}.
   *
   * <p>The adapter must recheck fence, lease, phase, and required component completeness. Replays
   * after {@code READY} or {@code ACTIVE} return the originally finalized manifest.
   *
   * @param buildId build to finalize
   * @param fencingToken current positive build token
   * @return immutable finalized manifest
   */
  VersionManifest completeBuild(UUID buildId, long fencingToken);

  /**
   * Atomically compare-and-sets the active-version pointer and marks a {@code READY} build {@code
   * ACTIVE}.
   *
   * <p>The adapter must validate fence, lease, finalized manifest, and that the current active
   * version still equals the build's recorded base version. The pointer change and build status
   * change are one atomic operation. A replay after success returns the same build.
   *
   * @param buildId ready build to activate
   * @param fencingToken current positive build token
   * @return active build, including on a successful replay
   */
  Build activateBuild(UUID buildId, long fencingToken);

  /**
   * Atomically marks a non-active candidate {@code ABANDONED} before any external cleanup occurs. A
   * first abort validates the current fence and live lease. Replays for already failed or abandoned
   * builds are idempotent even after lease expiry.
   *
   * @param buildId build to abandon
   * @param fencingToken current positive build token
   * @return abandoned build, or the stable terminal build for an idempotent replay
   */
  Build abortBuild(UUID buildId, long fencingToken);

  /**
   * Atomically marks a non-terminal build {@code FAILED}.
   *
   * <p>This safety transition must remain available after lease expiry so participant cleanup can
   * never precede durable terminalization.
   *
   * @param buildId build to fail
   * @param fencingToken current positive build token
   * @param reason bounded diagnostic reason
   * @return failed build, including on a successful replay
   */
  Build failBuild(UUID buildId, long fencingToken, String reason);

  /**
   * Returns the manifest selected by the resource's active-version pointer.
   *
   * @param resourceId registered resource identifier
   * @return active manifest, or empty before the first activation
   */
  Optional<VersionManifest> findActiveVersionManifest(String resourceId);

  /**
   * Returns a historical manifest only after its build has reached {@code ACTIVE}.
   *
   * <p>{@code READY}, failed, and abandoned candidates are not public historical versions.
   *
   * @param resourceId owning resource identifier
   * @param version requested historical version
   * @return active historical manifest, or empty when absent or not public
   */
  Optional<VersionManifest> findVersionManifest(String resourceId, long version);

  /**
   * Atomically records participant progress so callback retries can resume deterministically.
   *
   * <p>{@code RESUMED} and {@code ABORTED} are terminal for a participant/build pair and must never
   * be overwritten by a late success or failure from another callback. Other updates must follow
   * the coordinated protocol's forward state transitions.
   *
   * @param buildId coordinated build identifier
   * @param participantId registered participant identifier
   * @param status durable protocol status to record
   * @param detail optional diagnostic detail; adapters must accept {@code null}
   */
  void updateParticipantState(
      UUID buildId, String participantId, ParticipantStatus status, String detail);

  /**
   * Returns all participant states for a build in deterministic participant-ID order.
   *
   * @param buildId coordinated build identifier
   * @return exactly one state per registered participant for a coordinated build
   */
  List<ParticipantState> findParticipantStates(UUID buildId);

  /**
   * Atomically marks every expired non-terminal build {@code ABANDONED} using the adapter's
   * authoritative clock and returns the number changed.
   *
   * @return number of builds changed by this invocation
   */
  int abandonExpiredBuilds();
}
