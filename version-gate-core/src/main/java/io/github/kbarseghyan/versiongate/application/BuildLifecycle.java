package io.github.kbarseghyan.versiongate.application;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.BuildStatus;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.SnapshotPolicy;
import java.time.Instant;
import java.util.Objects;

/** Pure lifecycle and fencing rules used by the application layer. */
final class BuildLifecycle {

  private BuildLifecycle() {}

  /**
   * Requires the caller's fencing token to match the build.
   *
   * @param build build being mutated
   * @param fencingToken token supplied by the caller
   * @throws NullPointerException when {@code build} is {@code null}
   * @throws VersionGateException with {@code STALE_FENCING_TOKEN} when the token does not match
   */
  public static void requireCurrentToken(Build build, long fencingToken) {
    Objects.requireNonNull(build, "build is required");
    if (build.fencingToken() != fencingToken) {
      throw new VersionGateException(
          ErrorCode.STALE_FENCING_TOKEN,
          "Fencing token " + fencingToken + " is stale for build " + build.buildId());
    }
  }

  /**
   * Requires a non-terminal build to have an unexpired lease.
   *
   * @param build build being checked
   * @param now instant at which to evaluate expiry
   * @throws NullPointerException when either argument is {@code null}
   * @throws VersionGateException with {@code LEASE_EXPIRED} when a non-terminal lease expired
   */
  public static void requireValidLease(Build build, Instant now) {
    Objects.requireNonNull(now, "now is required");
    if (!build.status().isTerminal() && build.leaseExpiredAt(now)) {
      throw new VersionGateException(
          ErrorCode.LEASE_EXPIRED, "Build " + build.buildId() + " lease has expired");
    }
  }

  /**
   * Resolves the first snapshot-phase state for the resource policy.
   *
   * <p>Successful replays in the policy's already-entered state return that same state.
   *
   * @param resource resource whose policy controls the transition
   * @param build candidate build
   * @return {@link BuildStatus#SNAPSHOTTING} for client-managed capture or {@link
   *     BuildStatus#QUIESCING} for coordinated capture
   * @throws VersionGateException with {@code INVALID_BUILD_TRANSITION} for any invalid state
   */
  public static BuildStatus snapshotStartStatus(Resource resource, Build build) {
    if (resource.snapshotPolicy() == SnapshotPolicy.CLIENT_MANAGED
        && build.status() == BuildStatus.SNAPSHOTTING) {
      return BuildStatus.SNAPSHOTTING;
    }
    if (resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE
        && (build.status() == BuildStatus.QUIESCING
            || build.status() == BuildStatus.SNAPSHOTTING)) {
      return BuildStatus.QUIESCING;
    }
    requireStatus(build, BuildStatus.BUILDING);
    return resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE
        ? BuildStatus.QUIESCING
        : BuildStatus.SNAPSHOTTING;
  }

  /**
   * Requires the build to be accepting snapshot components.
   *
   * @param build candidate build
   * @throws VersionGateException with {@code INVALID_BUILD_TRANSITION} unless snapshotting
   */
  public static void requireSnapshotSubmission(Build build) {
    requireStatus(build, BuildStatus.SNAPSHOTTING);
  }

  /**
   * Requires a snapshotting build or an idempotent completed-state replay.
   *
   * @param build candidate build
   * @throws VersionGateException with {@code INVALID_BUILD_TRANSITION} for any other state
   */
  public static void requireCompletable(Build build) {
    if (build.status() == BuildStatus.READY || build.status() == BuildStatus.ACTIVE) {
      return;
    }
    requireStatus(build, BuildStatus.SNAPSHOTTING);
  }

  /**
   * Requires a ready build or an idempotent active-state replay.
   *
   * @param build candidate build
   * @throws VersionGateException with {@code INVALID_BUILD_TRANSITION} for any other state
   */
  public static void requireActivatable(Build build) {
    if (build.status() == BuildStatus.ACTIVE) {
      return;
    }
    requireStatus(build, BuildStatus.READY);
  }

  /**
   * Requires a build whose lifecycle has not terminated.
   *
   * @param build candidate build
   * @throws VersionGateException with {@code INVALID_BUILD_TRANSITION} for a terminal build
   */
  public static void requireRenewable(Build build) {
    if (build.status().isTerminal()) {
      throw invalidTransition(build, "renew");
    }
  }

  /**
   * Requires a build that has not become active.
   *
   * <p>Failed and abandoned builds are accepted as idempotent abort replays.
   *
   * @param build candidate build
   * @throws VersionGateException with {@code INVALID_BUILD_TRANSITION} for an active build
   */
  public static void requireAbortable(Build build) {
    if (build.status() == BuildStatus.ABANDONED || build.status() == BuildStatus.FAILED) {
      return;
    }
    if (build.status() == BuildStatus.ACTIVE) {
      throw invalidTransition(build, "abort");
    }
  }

  /**
   * Requires an exact persisted lifecycle state.
   *
   * @param build candidate build
   * @param expected required status
   * @throws VersionGateException with {@code INVALID_BUILD_TRANSITION} when statuses differ
   */
  public static void requireStatus(Build build, BuildStatus expected) {
    if (build.status() != expected) {
      throw new VersionGateException(
          ErrorCode.INVALID_BUILD_TRANSITION,
          "Build " + build.buildId() + " is " + build.status() + "; expected " + expected);
    }
  }

  private static VersionGateException invalidTransition(Build build, String operation) {
    return new VersionGateException(
        ErrorCode.INVALID_BUILD_TRANSITION,
        "Cannot " + operation + " build " + build.buildId() + " in " + build.status());
  }
}
