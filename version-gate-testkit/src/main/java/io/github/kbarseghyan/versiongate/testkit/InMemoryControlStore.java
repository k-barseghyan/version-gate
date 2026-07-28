package io.github.kbarseghyan.versiongate.testkit;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.BuildStatus;
import io.github.kbarseghyan.versiongate.domain.Participant;
import io.github.kbarseghyan.versiongate.domain.ParticipantState;
import io.github.kbarseghyan.versiongate.domain.ParticipantStatus;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.SnapshotComponent;
import io.github.kbarseghyan.versiongate.domain.SnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.VersionManifest;
import io.github.kbarseghyan.versiongate.port.ControlStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic, thread-safe implementation of the authoritative control-store contract for tests.
 */
public final class InMemoryControlStore implements ControlStore {

  private final Clock clock;
  private final Map<String, Resource> resources = new HashMap<>();
  private final Map<UUID, Build> builds = new HashMap<>();
  private final Map<String, UUID> currentBuilds = new HashMap<>();
  private final Map<ResourceVersion, UUID> versionBuilds = new HashMap<>();
  private final Map<String, Long> lastAllocatedVersions = new HashMap<>();
  private final Map<String, Long> lastFencingTokens = new HashMap<>();
  private final Map<ComponentKey, SnapshotComponent> components = new HashMap<>();
  private final Map<ResourceVersion, VersionManifest> manifests = new HashMap<>();
  private final Map<ParticipantKey, ParticipantProgress> participantStates = new HashMap<>();

  private long nextBuildSequence = 1;

  /**
   * Creates an empty deterministic store.
   *
   * @param clock authoritative clock used for lease decisions
   */
  public InMemoryControlStore(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public synchronized Resource registerResource(
      String resourceId,
      SnapshotPolicy snapshotPolicy,
      Set<String> requiredComponentIds,
      List<Participant> participants) {
    if (resources.containsKey(resourceId)) {
      throw error(
          ErrorCode.RESOURCE_ALREADY_EXISTS, "Resource " + resourceId + " is already registered");
    }
    Instant now = now();
    Resource resource =
        new Resource(
            resourceId, snapshotPolicy, requiredComponentIds, participants, null, now, now);
    resources.put(resourceId, resource);
    return resource;
  }

  @Override
  public synchronized Optional<Resource> findResource(String resourceId) {
    return Optional.ofNullable(resources.get(resourceId));
  }

  @Override
  public synchronized Build beginBuild(String resourceId, String owner, Duration leaseDuration) {
    Resource resource = requireResource(resourceId);
    Instant now = now();
    UUID currentId = currentBuilds.get(resourceId);
    if (currentId != null) {
      Build current = builds.get(currentId);
      if (!current.status().isTerminal() && current.leaseExpiredAt(now)) {
        builds.put(currentId, withStatus(current, BuildStatus.ABANDONED, now));
        currentBuilds.remove(resourceId);
      } else if (!current.status().isTerminal()) {
        throw error(
            ErrorCode.BUILD_ALREADY_EXISTS,
            "Resource " + resourceId + " already has a non-terminal build");
      } else {
        currentBuilds.remove(resourceId);
      }
    }
    if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw error(ErrorCode.VALIDATION_FAILED, "lease duration must be positive");
    }

    long targetVersion;
    try {
      targetVersion = Math.addExact(lastAllocatedVersions.getOrDefault(resourceId, 0L), 1L);
    } catch (ArithmeticException exhausted) {
      throw error(
          ErrorCode.STORAGE_FAILURE,
          "Coordinator version space is exhausted for resource " + resourceId);
    }
    ResourceVersion versionKey = new ResourceVersion(resourceId, targetVersion);
    if (versionBuilds.containsKey(versionKey)) {
      throw error(
          ErrorCode.STORAGE_FAILURE,
          "Coordinator version allocation is inconsistent for resource " + resourceId);
    }
    long fencingToken;
    try {
      fencingToken = Math.addExact(lastFencingTokens.getOrDefault(resourceId, 0L), 1L);
    } catch (ArithmeticException exhausted) {
      throw error(
          ErrorCode.STORAGE_FAILURE, "Fencing-token space is exhausted for resource " + resourceId);
    }
    UUID buildId = new UUID(0L, nextBuildSequence++);
    Build build =
        new Build(
            buildId,
            resourceId,
            targetVersion,
            resource.activeVersion(),
            BuildStatus.BUILDING,
            owner,
            fencingToken,
            now.plus(leaseDuration),
            now,
            now);
    lastAllocatedVersions.put(resourceId, targetVersion);
    lastFencingTokens.put(resourceId, fencingToken);
    builds.put(buildId, build);
    currentBuilds.put(resourceId, buildId);
    versionBuilds.put(versionKey, buildId);
    resource.participants().stream()
        .sorted(Comparator.comparing(Participant::participantId))
        .forEach(
            participant -> {
              ParticipantState state =
                  new ParticipantState(
                      buildId,
                      participant.participantId(),
                      ParticipantStatus.PENDING,
                      Optional.empty(),
                      now);
              participantStates.put(
                  new ParticipantKey(buildId, participant.participantId()),
                  new ParticipantProgress(state, 0));
            });
    return build;
  }

  @Override
  public synchronized Build renewBuild(UUID buildId, long fencingToken, Duration leaseDuration) {
    Build build = requireBuildAndToken(buildId, fencingToken);
    if (build.status().isTerminal()) {
      throw invalidTransition(build, "renew");
    }
    Instant now = now();
    requireValidLease(build, now);
    Resource resource = requireResource(build.resourceId());
    if (resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE
        && build.status() != BuildStatus.BUILDING) {
      throw invalidTransition(build, "renew a coordinated build after quiescence has started");
    }
    if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw error(ErrorCode.VALIDATION_FAILED, "lease duration must be positive");
    }
    Build renewed =
        new Build(
            build.buildId(),
            build.resourceId(),
            build.targetVersion(),
            build.baseActiveVersion(),
            build.status(),
            build.owner(),
            build.fencingToken(),
            now.plus(leaseDuration),
            build.createdAt(),
            now);
    builds.put(buildId, renewed);
    return renewed;
  }

  @Override
  public synchronized Build startSnapshotPhase(
      UUID buildId, long fencingToken, BuildStatus targetStatus) {
    Build build = requireBuildAndToken(buildId, fencingToken);
    Instant now = now();
    requireValidLease(build, now);
    Resource resource = requireResource(build.resourceId());
    BuildStatus requiredTarget =
        resource.snapshotPolicy() == SnapshotPolicy.CLIENT_MANAGED
            ? BuildStatus.SNAPSHOTTING
            : BuildStatus.QUIESCING;
    if (targetStatus != requiredTarget) {
      throw invalidTransition(build, "enter " + targetStatus + " for " + resource.snapshotPolicy());
    }
    if (build.status() == targetStatus
        || (resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE
            && build.status() == BuildStatus.SNAPSHOTTING)) {
      return build;
    }
    if (build.status() != BuildStatus.BUILDING) {
      throw invalidTransition(build, "start snapshot phase");
    }
    Build transitioned = withStatus(build, targetStatus, now);
    builds.put(buildId, transitioned);
    return transitioned;
  }

  @Override
  public synchronized Build markSnapshotting(UUID buildId, long fencingToken) {
    Build build = requireBuildAndToken(buildId, fencingToken);
    Instant now = now();
    requireValidLease(build, now);
    Resource resource = requireResource(build.resourceId());
    if (resource.snapshotPolicy() != SnapshotPolicy.COORDINATED_QUIESCE) {
      throw invalidTransition(build, "mark a client-managed build as coordinated");
    }
    if (build.status() == BuildStatus.SNAPSHOTTING) {
      return build;
    }
    if (build.status() != BuildStatus.QUIESCING) {
      throw invalidTransition(build, "finish quiescence");
    }
    Build transitioned = withStatus(build, BuildStatus.SNAPSHOTTING, now);
    builds.put(buildId, transitioned);
    return transitioned;
  }

  @Override
  public synchronized Optional<Build> findBuild(UUID buildId) {
    return Optional.ofNullable(builds.get(buildId));
  }

  @Override
  public synchronized Optional<Build> findCurrentBuild(String resourceId) {
    UUID buildId = currentBuilds.get(resourceId);
    if (buildId == null) {
      return Optional.empty();
    }
    Build build = builds.get(buildId);
    if (build.status().isTerminal()) {
      currentBuilds.remove(resourceId);
      return Optional.empty();
    }
    Instant now = now();
    if (build.leaseExpiredAt(now)) {
      builds.put(buildId, withStatus(build, BuildStatus.ABANDONED, now));
      currentBuilds.remove(resourceId);
      return Optional.empty();
    }
    return Optional.of(build);
  }

  @Override
  public synchronized SnapshotComponent registerSnapshotComponent(
      UUID buildId, long fencingToken, SnapshotComponent component) {
    Build build = requireBuildAndToken(buildId, fencingToken);
    requireComponentIdentity(component, build);
    ComponentKey key =
        new ComponentKey(component.resourceId(), component.version(), component.componentId());
    SnapshotComponent existing = components.get(key);
    if (existing != null) {
      if (existing.objectKey().equals(component.objectKey())
          && existing.size() == component.size()
          && existing.sha256().equals(component.sha256())) {
        return existing;
      }
      throw componentConflict(component.componentId());
    }
    Instant now = now();
    requireValidLease(build, now);
    if (build.status() != BuildStatus.SNAPSHOTTING) {
      throw invalidTransition(build, "register a snapshot component");
    }
    Resource resource = requireResource(build.resourceId());
    if (!resource.requiredComponentIds().contains(component.componentId())) {
      throw error(
          ErrorCode.VALIDATION_FAILED,
          "Component " + component.componentId() + " is not required by " + build.resourceId());
    }
    components.put(key, component);
    return component;
  }

  @Override
  public synchronized Optional<SnapshotComponent> findSnapshotComponent(
      String resourceId, long version, String componentId) {
    return Optional.ofNullable(components.get(new ComponentKey(resourceId, version, componentId)));
  }

  @Override
  public synchronized List<SnapshotComponent> findSnapshotComponents(
      String resourceId, long version) {
    return components.entrySet().stream()
        .filter(
            entry ->
                entry.getKey().resourceId().equals(resourceId)
                    && entry.getKey().version() == version)
        .map(Map.Entry::getValue)
        .sorted(Comparator.comparing(SnapshotComponent::componentId))
        .toList();
  }

  @Override
  public synchronized VersionManifest completeBuild(UUID buildId, long fencingToken) {
    Build build = requireBuildAndToken(buildId, fencingToken);
    ResourceVersion versionKey = new ResourceVersion(build.resourceId(), build.targetVersion());
    VersionManifest existing = manifests.get(versionKey);
    if (existing != null
        && (build.status() == BuildStatus.READY || build.status() == BuildStatus.ACTIVE)) {
      return existing;
    }
    Instant now = now();
    requireValidLease(build, now);
    if (build.status() != BuildStatus.SNAPSHOTTING) {
      throw invalidTransition(build, "complete");
    }
    Resource resource = requireResource(build.resourceId());
    List<SnapshotComponent> found =
        findSnapshotComponents(build.resourceId(), build.targetVersion());
    Set<String> foundIds =
        found.stream().map(SnapshotComponent::componentId).collect(Collectors.toUnmodifiableSet());
    Set<String> missing =
        resource.requiredComponentIds().stream()
            .filter(componentId -> !foundIds.contains(componentId))
            .collect(Collectors.toUnmodifiableSet());
    if (!missing.isEmpty()) {
      throw new VersionGateException(
          ErrorCode.INCOMPLETE_SNAPSHOT,
          "Required snapshot components are missing",
          Map.of("missingComponentIds", missing));
    }
    VersionManifest manifest =
        new VersionManifest(
            build.resourceId(),
            build.targetVersion(),
            build.buildId(),
            build.baseActiveVersion(),
            now,
            found);
    manifests.put(versionKey, manifest);
    builds.put(buildId, withStatus(build, BuildStatus.READY, now));
    return manifest;
  }

  @Override
  public synchronized Build activateBuild(UUID buildId, long fencingToken) {
    Build build = requireBuildAndToken(buildId, fencingToken);
    if (build.status() == BuildStatus.ACTIVE) {
      return build;
    }
    Instant now = now();
    requireValidLease(build, now);
    if (build.status() != BuildStatus.READY) {
      throw invalidTransition(build, "activate");
    }
    Resource resource = requireResource(build.resourceId());
    if (!Objects.equals(resource.activeVersion(), build.baseActiveVersion())) {
      throw error(
          ErrorCode.ACTIVATION_CONFLICT,
          "Active version changed while build " + build.buildId() + " was running");
    }
    ResourceVersion versionKey = new ResourceVersion(build.resourceId(), build.targetVersion());
    if (!manifests.containsKey(versionKey)) {
      throw error(
          ErrorCode.INCOMPLETE_SNAPSHOT, "Build " + build.buildId() + " has no finalized manifest");
    }
    Resource activatedResource =
        new Resource(
            resource.resourceId(),
            resource.snapshotPolicy(),
            resource.requiredComponentIds(),
            resource.participants(),
            build.targetVersion(),
            resource.createdAt(),
            now);
    Build activated = withStatus(build, BuildStatus.ACTIVE, now);
    resources.put(resource.resourceId(), activatedResource);
    builds.put(buildId, activated);
    currentBuilds.remove(resource.resourceId());
    return activated;
  }

  @Override
  public synchronized Build abortBuild(UUID buildId, long fencingToken) {
    Build build = requireBuildAndToken(buildId, fencingToken);
    if (build.status() == BuildStatus.ABANDONED || build.status() == BuildStatus.FAILED) {
      return build;
    }
    if (build.status() == BuildStatus.ACTIVE) {
      throw invalidTransition(build, "abort");
    }
    Instant now = now();
    requireValidLease(build, now);
    Build abandoned = withStatus(build, BuildStatus.ABANDONED, now);
    builds.put(buildId, abandoned);
    currentBuilds.remove(build.resourceId(), buildId);
    return abandoned;
  }

  @Override
  public synchronized Build failBuild(UUID buildId, long fencingToken, String reason) {
    Build build = requireBuildAndToken(buildId, fencingToken);
    if (build.status() == BuildStatus.FAILED) {
      return build;
    }
    if (build.status().isTerminal()) {
      throw invalidTransition(build, "fail");
    }
    Build failed = withStatus(build, BuildStatus.FAILED, now());
    builds.put(buildId, failed);
    currentBuilds.remove(build.resourceId(), buildId);
    return failed;
  }

  @Override
  public synchronized Optional<VersionManifest> findActiveVersionManifest(String resourceId) {
    Resource resource = resources.get(resourceId);
    if (resource == null || resource.activeVersion() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        manifests.get(new ResourceVersion(resourceId, resource.activeVersion())));
  }

  @Override
  public synchronized Optional<VersionManifest> findVersionManifest(
      String resourceId, long version) {
    VersionManifest manifest = manifests.get(new ResourceVersion(resourceId, version));
    if (manifest == null) {
      return Optional.empty();
    }
    Build build = builds.get(manifest.buildId());
    return build != null && build.status() == BuildStatus.ACTIVE
        ? Optional.of(manifest)
        : Optional.empty();
  }

  @Override
  public synchronized void updateParticipantState(
      UUID buildId, String participantId, ParticipantStatus status, String detail) {
    requireBuild(buildId);
    ParticipantKey key = new ParticipantKey(buildId, participantId);
    ParticipantProgress current = participantStates.get(key);
    if (current == null) {
      throw error(
          ErrorCode.VALIDATION_FAILED,
          "Participant " + participantId + " is not registered for build " + buildId);
    }
    ParticipantStatus currentStatus = current.state().status();
    if (currentStatus == ParticipantStatus.RESUMED || currentStatus == ParticipantStatus.ABORTED) {
      return;
    }
    if (currentStatus == status) {
      return;
    }

    int highWater = current.highWater();
    int nextHighWater = highWater;
    if (status == ParticipantStatus.ABORTED) {
      // Abort is a terminal safety transition from any non-terminal protocol state.
    } else if (status == ParticipantStatus.FAILED) {
      // Failure is retryable and does not erase previously acknowledged progress.
    } else {
      int targetStep = participantStep(status);
      boolean protocolRestart =
          currentStatus == ParticipantStatus.FAILED && status == ParticipantStatus.QUIESCED;
      if (!protocolRestart && targetStep != highWater + 1) {
        throw error(
            ErrorCode.INVALID_BUILD_TRANSITION,
            "Participant "
                + participantId
                + " cannot transition from "
                + currentStatus
                + " to "
                + status);
      }
      nextHighWater = protocolRestart ? 1 : targetStep;
    }
    ParticipantState updated =
        new ParticipantState(buildId, participantId, status, Optional.ofNullable(detail), now());
    participantStates.put(key, new ParticipantProgress(updated, nextHighWater));
  }

  @Override
  public synchronized List<ParticipantState> findParticipantStates(UUID buildId) {
    requireBuild(buildId);
    return participantStates.entrySet().stream()
        .filter(entry -> entry.getKey().buildId().equals(buildId))
        .map(entry -> entry.getValue().state())
        .sorted(Comparator.comparing(ParticipantState::participantId))
        .toList();
  }

  @Override
  public synchronized int abandonExpiredBuilds() {
    Instant now = now();
    List<UUID> expired = new ArrayList<>();
    for (Map.Entry<UUID, Build> entry : builds.entrySet()) {
      Build build = entry.getValue();
      if (!build.status().isTerminal() && build.leaseExpiredAt(now)) {
        expired.add(entry.getKey());
      }
    }
    expired.sort(Comparator.comparing(UUID::toString));
    for (UUID buildId : expired) {
      Build build = builds.get(buildId);
      builds.put(buildId, withStatus(build, BuildStatus.ABANDONED, now));
      currentBuilds.remove(build.resourceId(), buildId);
    }
    return expired.size();
  }

  private Instant now() {
    return clock.instant();
  }

  private Resource requireResource(String resourceId) {
    Resource resource = resources.get(resourceId);
    if (resource == null) {
      throw error(ErrorCode.RESOURCE_NOT_FOUND, "Resource " + resourceId + " was not found");
    }
    return resource;
  }

  private Build requireBuild(UUID buildId) {
    Build build = builds.get(buildId);
    if (build == null) {
      throw error(ErrorCode.BUILD_NOT_FOUND, "Build " + buildId + " was not found");
    }
    return build;
  }

  private Build requireBuildAndToken(UUID buildId, long fencingToken) {
    Build build = requireBuild(buildId);
    if (build.fencingToken() != fencingToken) {
      throw error(
          ErrorCode.STALE_FENCING_TOKEN,
          "Fencing token " + fencingToken + " is stale for build " + buildId);
    }
    return build;
  }

  private static void requireValidLease(Build build, Instant now) {
    if (!build.status().isTerminal() && build.leaseExpiredAt(now)) {
      throw error(ErrorCode.LEASE_EXPIRED, "Build " + build.buildId() + " lease has expired");
    }
  }

  private static void requireComponentIdentity(SnapshotComponent component, Build build) {
    if (!component.buildId().equals(build.buildId())
        || !component.resourceId().equals(build.resourceId())
        || component.version() != build.targetVersion()) {
      throw error(
          ErrorCode.VALIDATION_FAILED,
          "Snapshot component identity does not match build " + build.buildId());
    }
  }

  private static int participantStep(ParticipantStatus status) {
    return switch (status) {
      case PENDING -> 0;
      case QUIESCED -> 1;
      case CAPTURE_REQUESTED -> 2;
      case RESUMED -> 3;
      case ABORTED, FAILED ->
          throw new IllegalArgumentException(status + " is not a progress step");
    };
  }

  private static Build withStatus(Build build, BuildStatus status, Instant updatedAt) {
    return new Build(
        build.buildId(),
        build.resourceId(),
        build.targetVersion(),
        build.baseActiveVersion(),
        status,
        build.owner(),
        build.fencingToken(),
        build.leaseExpiresAt(),
        build.createdAt(),
        updatedAt);
  }

  private static VersionGateException componentConflict(String componentId) {
    return error(
        ErrorCode.COMPONENT_CONFLICT,
        "Component " + componentId + " already exists with different content");
  }

  private static VersionGateException invalidTransition(Build build, String operation) {
    return error(
        ErrorCode.INVALID_BUILD_TRANSITION,
        "Cannot " + operation + " build " + build.buildId() + " in " + build.status());
  }

  private static VersionGateException error(ErrorCode code, String message) {
    return new VersionGateException(code, message);
  }

  private record ResourceVersion(String resourceId, long version) {}

  private record ComponentKey(String resourceId, long version, String componentId) {}

  private record ParticipantKey(UUID buildId, String participantId) {}

  private record ParticipantProgress(ParticipantState state, int highWater) {}
}
