package io.github.kbarseghyan.versiongate.application;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.BuildStatus;
import io.github.kbarseghyan.versiongate.domain.DomainValidation;
import io.github.kbarseghyan.versiongate.domain.Participant;
import io.github.kbarseghyan.versiongate.domain.ParticipantState;
import io.github.kbarseghyan.versiongate.domain.ParticipantStatus;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.SnapshotComponent;
import io.github.kbarseghyan.versiongate.domain.SnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.VersionManifest;
import io.github.kbarseghyan.versiongate.port.ControlStore;
import io.github.kbarseghyan.versiongate.port.ParticipantGateway;
import io.github.kbarseghyan.versiongate.port.SnapshotStore;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Storage-neutral application use cases for coordinating immutable version publication.
 *
 * <p>All concurrency-critical checks are repeated atomically by {@link ControlStore}; the
 * application prechecks provide early, stable errors and coordinate the two independent storage
 * ports without claiming a cross-store transaction.
 */
public final class VersionGateService {

  /** Media types accepted for snapshot component uploads in the core V1 protocol. */
  public static final Set<String> SUPPORTED_CONTENT_TYPES =
      Set.of("application/json", "application/x-ndjson", "application/octet-stream");

  private final ControlStore controlStore;
  private final SnapshotStore snapshotStore;
  private final ParticipantGateway participantGateway;
  private final Clock clock;
  private final Duration maximumLease;
  private final long maximumComponentSize;
  private final int maximumParticipantsPerResource;

  /**
   * Creates the storage-neutral application service.
   *
   * @param controlStore authoritative control-state port
   * @param snapshotStore immutable snapshot-payload port
   * @param participantGateway coordinated callback port
   * @param clock application clock used for capture metadata and early checks
   * @param maximumLease largest accepted lease duration
   * @param maximumComponentSize largest accepted component representation in bytes
   */
  public VersionGateService(
      ControlStore controlStore,
      SnapshotStore snapshotStore,
      ParticipantGateway participantGateway,
      Clock clock,
      Duration maximumLease,
      long maximumComponentSize) {
    this(
        controlStore,
        snapshotStore,
        participantGateway,
        clock,
        maximumLease,
        maximumComponentSize,
        DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE);
  }

  /**
   * Creates the storage-neutral application service with an explicit callback fan-out limit.
   *
   * @param controlStore authoritative control-state port
   * @param snapshotStore immutable snapshot-payload port
   * @param participantGateway coordinated callback port
   * @param clock application clock used for capture metadata and early checks
   * @param maximumLease largest accepted lease duration
   * @param maximumComponentSize largest accepted component representation in bytes
   * @param maximumParticipantsPerResource configured coordinated-callback fan-out limit
   */
  public VersionGateService(
      ControlStore controlStore,
      SnapshotStore snapshotStore,
      ParticipantGateway participantGateway,
      Clock clock,
      Duration maximumLease,
      long maximumComponentSize,
      int maximumParticipantsPerResource) {
    this.controlStore = Objects.requireNonNull(controlStore, "controlStore is required");
    this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore is required");
    this.participantGateway =
        Objects.requireNonNull(participantGateway, "participantGateway is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.maximumLease = Objects.requireNonNull(maximumLease, "maximumLease is required");
    if (maximumLease.isZero() || maximumLease.isNegative()) {
      throw new IllegalArgumentException("maximumLease must be positive");
    }
    if (maximumComponentSize <= 0) {
      throw new IllegalArgumentException("maximumComponentSize must be positive");
    }
    this.maximumComponentSize = maximumComponentSize;
    if (maximumParticipantsPerResource <= 0
        || maximumParticipantsPerResource > DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE) {
      throw new IllegalArgumentException(
          "maximumParticipantsPerResource must be between 1 and "
              + DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE);
    }
    this.maximumParticipantsPerResource = maximumParticipantsPerResource;
  }

  /**
   * Persists an immutable resource registration.
   *
   * <p>Coordinated participant destinations are validated before control state is written.
   *
   * @param command complete registration definition
   * @return persisted resource
   */
  public Resource registerResource(RegisterResourceCommand command) {
    validateResourceRegistration(command);
    if (command.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE) {
      command.participants().forEach(participantGateway::validateRegistration);
    }
    return controlStore.registerResource(
        command.resourceId(),
        command.snapshotPolicy(),
        command.requiredComponentIds(),
        command.participants());
  }

  /**
   * Returns a registered resource.
   *
   * @param resourceId resource identifier
   * @return registered resource
   * @throws VersionGateException with {@code RESOURCE_NOT_FOUND} when absent
   */
  public Resource getResource(String resourceId) {
    validateInput(() -> DomainValidation.requireIdentifier(resourceId, "resourceId"));
    return controlStore
        .findResource(resourceId)
        .orElseThrow(
            () ->
                new VersionGateException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Resource " + resourceId + " was not found"));
  }

  /**
   * Atomically starts a uniquely fenced candidate build.
   *
   * <p>The authoritative control store allocates both the coordinator version and fencing token.
   * Clients supply only ownership metadata and the requested initial lease.
   *
   * @param command target resource, owner, and initial lease
   * @return newly created build
   */
  public Build beginBuild(BeginBuildCommand command) {
    Objects.requireNonNull(command, "command is required");
    validateInput(
        () -> {
          DomainValidation.requireIdentifier(command.resourceId(), "resourceId");
          DomainValidation.requireNonBlank(
              command.owner(), "owner", DomainValidation.TEXT_MAX_LENGTH);
        });
    validateLease(command.leaseDuration());
    return controlStore.beginBuild(command.resourceId(), command.owner(), command.leaseDuration());
  }

  /**
   * Renews a live fenced build within the configured lease limit.
   *
   * @param command build identity, current fence, and requested duration
   * @return build with a renewed lease
   */
  public Build renewBuild(RenewBuildCommand command) {
    validateBuildToken(command.buildId(), command.fencingToken());
    validateLease(command.leaseDuration());
    Build build = requireBuild(command.buildId());
    BuildLifecycle.requireCurrentToken(build, command.fencingToken());
    BuildLifecycle.requireRenewable(build);
    Resource resource = getResource(build.resourceId());
    if (resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE
        && build.status() != BuildStatus.BUILDING) {
      throw new VersionGateException(
          ErrorCode.INVALID_BUILD_TRANSITION,
          "COORDINATED_QUIESCE leases cannot be renewed after quiescence begins");
    }
    return controlStore.renewBuild(
        command.buildId(), command.fencingToken(), command.leaseDuration());
  }

  /**
   * Enters the policy-specific snapshot phase.
   *
   * <p>For coordinated resources this also performs retryable quiesce and capture callbacks.
   *
   * @param command build identity and current fence
   * @return snapshotting build after any required coordination
   */
  public Build startSnapshotPhase(BuildTokenCommand command) {
    validateBuildToken(command.buildId(), command.fencingToken());
    Build build = requireBuild(command.buildId());
    BuildLifecycle.requireCurrentToken(build, command.fencingToken());
    Resource resource = getResource(build.resourceId());
    BuildStatus target = BuildLifecycle.snapshotStartStatus(resource, build);
    Build transitioned =
        controlStore.startSnapshotPhase(build.buildId(), command.fencingToken(), target);
    if (resource.snapshotPolicy() == SnapshotPolicy.CLIENT_MANAGED) {
      return transitioned;
    }
    return coordinateQuiescence(resource, transitioned, command.fencingToken());
  }

  /**
   * Streams and registers one required immutable snapshot component.
   *
   * <p>An exact replay consumes and verifies the supplied stream before returning the original
   * component metadata.
   *
   * @param command component metadata and exact representation stream
   * @return authoritative registered component metadata
   */
  public SnapshotComponent submitSnapshotComponent(SubmitComponentCommand command) {
    validateSnapshotSubmission(command);
    if (command.contentLength() > maximumComponentSize) {
      throw new VersionGateException(
          ErrorCode.VALIDATION_FAILED,
          "Snapshot component exceeds the configured maximum size of "
              + maximumComponentSize
              + " bytes");
    }
    Instant now = clock.instant();
    Build build = requireBuild(command.buildId());
    BuildLifecycle.requireCurrentToken(build, command.fencingToken());
    Resource resource = getResource(build.resourceId());
    if (!resource.requiredComponentIds().contains(command.componentId())) {
      throw new VersionGateException(
          ErrorCode.VALIDATION_FAILED,
          "Component "
              + command.componentId()
              + " is not required by resource "
              + resource.resourceId());
    }
    if (!SUPPORTED_CONTENT_TYPES.contains(command.contentType())) {
      throw new VersionGateException(
          ErrorCode.VALIDATION_FAILED,
          "Unsupported snapshot content type " + command.contentType(),
          Map.of("supportedContentTypes", SUPPORTED_CONTENT_TYPES));
    }

    Optional<SnapshotComponent> prior =
        controlStore.findSnapshotComponent(
            build.resourceId(), build.targetVersion(), command.componentId());
    if (prior.isPresent()) {
      SnapshotComponent existing = prior.get();
      if (command.expectedSha256().isPresent()
          && !existing.sha256().equalsIgnoreCase(command.expectedSha256().get())) {
        throw componentConflict(command.componentId());
      }
      if (command.contentLength() != existing.size()) {
        throw componentConflict(command.componentId());
      }
      SnapshotStore.StoredObject replayed =
          snapshotStore.uploadImmutable(
              new SnapshotStore.Upload(
                  existing.objectKey(),
                  command.inputStream(),
                  command.contentLength(),
                  existing.contentType(),
                  existing.contentEncoding(),
                  Optional.of(existing.sha256())));
      requireStoredObject(
          replayed, existing.objectKey(), existing.size(), Optional.of(existing.sha256()));
      return existing;
    }
    BuildLifecycle.requireSnapshotSubmission(build);

    String objectKey =
        "snapshots/"
            + build.resourceId()
            + "/"
            + build.targetVersion()
            + "/"
            + command.componentId();
    SnapshotStore.StoredObject storedObject =
        snapshotStore.uploadImmutable(
            new SnapshotStore.Upload(
                objectKey,
                command.inputStream(),
                command.contentLength(),
                command.contentType(),
                command.contentEncoding(),
                command.expectedSha256()));
    requireStoredObject(storedObject, objectKey, command.contentLength(), command.expectedSha256());

    SnapshotComponent component =
        new SnapshotComponent(
            build.buildId(),
            build.resourceId(),
            build.targetVersion(),
            command.componentId(),
            storedObject.reference().objectKey(),
            command.contentType(),
            command.contentEncoding(),
            storedObject.reference().sha256(),
            storedObject.reference().size(),
            command.schemaVersion(),
            command.capturedAt().orElse(now));
    return controlStore.registerSnapshotComponent(
        build.buildId(), command.fencingToken(), component);
  }

  /**
   * Verifies required payloads and finalizes an immutable manifest.
   *
   * <p>Coordinated participants are asked to resume after durable finalization.
   *
   * @param command build identity and current fence
   * @return finalized manifest, including for an idempotent replay
   */
  public VersionManifest completeBuild(BuildTokenCommand command) {
    validateBuildToken(command.buildId(), command.fencingToken());
    Build build = requireBuild(command.buildId());
    BuildLifecycle.requireCurrentToken(build, command.fencingToken());
    BuildLifecycle.requireCompletable(build);

    Resource resource = getResource(build.resourceId());
    if (build.status() == BuildStatus.READY || build.status() == BuildStatus.ACTIVE) {
      VersionManifest manifest =
          controlStore.completeBuild(build.buildId(), command.fencingToken());
      if (resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE) {
        resumeParticipants(resource, build);
      }
      return manifest;
    }
    List<SnapshotComponent> components =
        controlStore.findSnapshotComponents(build.resourceId(), build.targetVersion());
    Set<String> present =
        components.stream()
            .map(SnapshotComponent::componentId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Set<String> missing =
        resource.requiredComponentIds().stream()
            .filter(component -> !present.contains(component))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (!missing.isEmpty()) {
      throw new VersionGateException(
          ErrorCode.INCOMPLETE_SNAPSHOT,
          "Required snapshot components are missing",
          Map.of("missingComponentIds", missing));
    }
    if (resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE) {
      Set<String> incompleteParticipants =
          participantStatuses(resource, build.buildId()).entrySet().stream()
              .filter(
                  entry ->
                      entry.getValue() != ParticipantStatus.CAPTURE_REQUESTED
                          && entry.getValue() != ParticipantStatus.RESUMED)
              .map(Map.Entry::getKey)
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      if (!incompleteParticipants.isEmpty()) {
        throw new VersionGateException(
            ErrorCode.PARTICIPANT_FAILURE,
            "Not all participants acknowledged snapshot capture",
            Map.of("participantIds", incompleteParticipants));
      }
    }
    for (SnapshotComponent component : components) {
      SnapshotStore.ObjectReference reference =
          new SnapshotStore.ObjectReference(
              component.objectKey(), component.sha256(), component.size());
      snapshotStore.verify(reference);
    }

    VersionManifest manifest = controlStore.completeBuild(build.buildId(), command.fencingToken());
    if (resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE) {
      resumeParticipants(resource, build);
    }
    return manifest;
  }

  /**
   * Compare-and-sets the resource's active pointer to a ready build.
   *
   * @param command build identity and current fence
   * @return active build, including for an idempotent replay
   */
  public Build activateBuild(BuildTokenCommand command) {
    validateBuildToken(command.buildId(), command.fencingToken());
    Build build = requireBuild(command.buildId());
    BuildLifecycle.requireCurrentToken(build, command.fencingToken());
    BuildLifecycle.requireActivatable(build);
    Resource resource = getResource(build.resourceId());
    if (build.status() != BuildStatus.ACTIVE
        && resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE) {
      Set<String> notResumed =
          participantStatuses(resource, build.buildId()).entrySet().stream()
              .filter(entry -> entry.getValue() != ParticipantStatus.RESUMED)
              .map(Map.Entry::getKey)
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      if (!notResumed.isEmpty()) {
        throw new VersionGateException(
            ErrorCode.PARTICIPANT_FAILURE,
            "All participants must resume before activation",
            Map.of("participantIds", notResumed));
      }
    }
    if (build.status() != BuildStatus.ACTIVE) {
      for (SnapshotComponent component :
          controlStore.findSnapshotComponents(build.resourceId(), build.targetVersion())) {
        SnapshotStore.ObjectReference reference =
            new SnapshotStore.ObjectReference(
                component.objectKey(), component.sha256(), component.size());
        snapshotStore.verify(reference);
      }
    }
    return controlStore.activateBuild(build.buildId(), command.fencingToken());
  }

  /**
   * Durably abandons a candidate before participant and payload cleanup.
   *
   * @param command build identity and current fence
   * @return abandoned build, or stable prior failed/abandoned state for a replay
   */
  public Build abortBuild(BuildTokenCommand command) {
    validateBuildToken(command.buildId(), command.fencingToken());
    Build build = requireBuild(command.buildId());
    BuildLifecycle.requireCurrentToken(build, command.fencingToken());
    BuildLifecycle.requireAbortable(build);
    Resource resource = getResource(build.resourceId());
    Build aborted = controlStore.abortBuild(build.buildId(), command.fencingToken());
    if (resource.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE) {
      abortParticipants(resource, build);
    }
    deleteBuildObjects(build);
    return aborted;
  }

  /**
   * Returns the resource's current live candidate build.
   *
   * @param resourceId registered resource identifier
   * @return current non-terminal build, or empty when none exists
   */
  public Optional<Build> getCurrentBuild(String resourceId) {
    getResource(resourceId);
    return controlStore.findCurrentBuild(resourceId);
  }

  /**
   * Returns the manifest selected by a resource's active pointer.
   *
   * @param resourceId registered resource identifier
   * @return active version manifest
   * @throws VersionGateException with {@code VERSION_NOT_FOUND} before first activation
   */
  public VersionManifest getActiveVersion(String resourceId) {
    getResource(resourceId);
    return controlStore
        .findActiveVersionManifest(resourceId)
        .orElseThrow(
            () ->
                new VersionGateException(
                    ErrorCode.VERSION_NOT_FOUND,
                    "Resource " + resourceId + " has no active version"));
  }

  /**
   * Returns a publicly visible historical manifest.
   *
   * @param resourceId registered resource identifier
   * @param version non-negative activated version
   * @return requested active historical manifest
   * @throws VersionGateException with {@code VERSION_NOT_FOUND} when absent or not public
   */
  public VersionManifest getVersionManifest(String resourceId, long version) {
    validateVersion(version);
    getResource(resourceId);
    return requireManifest(resourceId, version);
  }

  /**
   * Opens an integrity-protected stream for a component of an active public version.
   *
   * <p>The caller must close the returned download.
   *
   * @param resourceId registered resource identifier
   * @param version non-negative activated version
   * @param componentId component identifier
   * @return component metadata paired with its closeable content stream
   */
  public SnapshotDownload streamSnapshotComponent(
      String resourceId, long version, String componentId) {
    validateInput(() -> DomainValidation.requireIdentifier(componentId, "componentId"));
    getVersionManifest(resourceId, version);
    SnapshotComponent component =
        controlStore
            .findSnapshotComponent(resourceId, version, componentId)
            .orElseThrow(
                () ->
                    new VersionGateException(
                        ErrorCode.COMPONENT_NOT_FOUND,
                        "Snapshot component "
                            + componentId
                            + " was not found for "
                            + resourceId
                            + " version "
                            + version));
    SnapshotStore.ObjectReference reference =
        new SnapshotStore.ObjectReference(
            component.objectKey(), component.sha256(), component.size());
    snapshotStore.verify(reference);
    SnapshotStore.ObjectContent content = snapshotStore.open(reference);
    if (content == null) {
      throw new VersionGateException(
          ErrorCode.STORAGE_FAILURE, "SnapshotStore returned no content for an existing reference");
    }
    return new SnapshotDownload(component, requireMatchingContent(component, content));
  }

  /**
   * Delegates atomic expiry processing to the authoritative control store.
   *
   * @return number of builds newly marked abandoned
   */
  public int abandonExpiredBuilds() {
    return controlStore.abandonExpiredBuilds();
  }

  private Build coordinateQuiescence(Resource resource, Build build, long fencingToken) {
    try {
      ParticipantGateway.CallbackContext quiesceContext =
          new ParticipantGateway.CallbackContext(build);
      Map<String, ParticipantStatus> states = participantStatuses(resource, build.buildId());
      for (Participant participant : resource.participants()) {
        ParticipantStatus status = states.get(participant.participantId());
        if (status == ParticipantStatus.PENDING || status == ParticipantStatus.FAILED) {
          participantGateway.quiesce(participant, quiesceContext);
          controlStore.updateParticipantState(
              build.buildId(), participant.participantId(), ParticipantStatus.QUIESCED, null);
        }
      }
      Build snapshotting = controlStore.markSnapshotting(build.buildId(), fencingToken);
      ParticipantGateway.CallbackContext captureContext =
          new ParticipantGateway.CallbackContext(snapshotting);
      states = participantStatuses(resource, build.buildId());
      for (Participant participant : resource.participants()) {
        ParticipantStatus status = states.get(participant.participantId());
        if (status == ParticipantStatus.QUIESCED) {
          participantGateway.capture(participant, captureContext);
          controlStore.updateParticipantState(
              build.buildId(),
              participant.participantId(),
              ParticipantStatus.CAPTURE_REQUESTED,
              null);
        }
      }
      return snapshotting;
    } catch (RuntimeException failure) {
      Build failed =
          controlStore.failBuild(build.buildId(), fencingToken, "Participant coordination failed");
      abortParticipants(resource, failed);
      throw new VersionGateException(
          ErrorCode.PARTICIPANT_FAILURE,
          "Coordinated quiescence failed; participants were asked to abort",
          failure);
    }
  }

  private void resumeParticipants(Resource resource, Build build) {
    ParticipantGateway.CallbackContext context = new ParticipantGateway.CallbackContext(build);
    Map<String, ParticipantStatus> states = participantStatuses(resource, build.buildId());
    for (Participant participant : resource.participants()) {
      if (states.get(participant.participantId()) == ParticipantStatus.RESUMED) {
        continue;
      }
      try {
        participantGateway.resume(participant, context);
        controlStore.updateParticipantState(
            build.buildId(), participant.participantId(), ParticipantStatus.RESUMED, null);
      } catch (RuntimeException failure) {
        controlStore.updateParticipantState(
            build.buildId(),
            participant.participantId(),
            ParticipantStatus.FAILED,
            "Resume callback failed");
      }
    }
  }

  private void abortParticipants(Resource resource, Build build) {
    ParticipantGateway.CallbackContext context = new ParticipantGateway.CallbackContext(build);
    Map<String, ParticipantStatus> states = participantStatuses(resource, build.buildId());
    for (Participant participant : resource.participants()) {
      if (states.get(participant.participantId()) == ParticipantStatus.ABORTED) {
        continue;
      }
      try {
        participantGateway.abort(participant, context);
        controlStore.updateParticipantState(
            build.buildId(), participant.participantId(), ParticipantStatus.ABORTED, null);
      } catch (RuntimeException failure) {
        controlStore.updateParticipantState(
            build.buildId(),
            participant.participantId(),
            ParticipantStatus.FAILED,
            "Abort callback failed");
      }
    }
  }

  private void deleteBuildObjects(Build build) {
    for (SnapshotComponent component :
        controlStore.findSnapshotComponents(build.resourceId(), build.targetVersion())) {
      try {
        snapshotStore.delete(
            new SnapshotStore.ObjectReference(
                component.objectKey(), component.sha256(), component.size()));
      } catch (RuntimeException ignored) {
        // Orphan cleanup is best effort; the authoritative active pointer is already safe.
      }
    }
  }

  private Map<String, ParticipantStatus> participantStatuses(Resource resource, UUID buildId) {
    List<ParticipantState> states = controlStore.findParticipantStates(buildId);
    Set<String> expected =
        resource.participants().stream()
            .map(Participant::participantId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Map<String, ParticipantStatus> actual;
    try {
      actual =
          states.stream()
              .peek(
                  state -> {
                    if (!state.buildId().equals(buildId)) {
                      throw new IllegalStateException("participant state belongs to another build");
                    }
                  })
              .collect(
                  java.util.stream.Collectors.toUnmodifiableMap(
                      ParticipantState::participantId, ParticipantState::status));
    } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
      throw new VersionGateException(
          ErrorCode.STORAGE_FAILURE,
          "ControlStore returned incoherent participant state",
          exception);
    }
    if (!actual.keySet().equals(expected)) {
      throw new VersionGateException(
          ErrorCode.STORAGE_FAILURE,
          "ControlStore participant state does not match the resource definition");
    }
    return actual;
  }

  private Build requireBuild(UUID buildId) {
    return controlStore
        .findBuild(buildId)
        .orElseThrow(
            () ->
                new VersionGateException(
                    ErrorCode.BUILD_NOT_FOUND, "Build " + buildId + " was not found"));
  }

  private VersionManifest requireManifest(String resourceId, long version) {
    return controlStore
        .findVersionManifest(resourceId, version)
        .orElseThrow(
            () ->
                new VersionGateException(
                    ErrorCode.VERSION_NOT_FOUND,
                    "Version " + version + " was not found for " + resourceId));
  }

  private void validateResourceRegistration(RegisterResourceCommand command) {
    Objects.requireNonNull(command, "command is required");
    validateInput(
        () -> {
          DomainValidation.requireIdentifier(command.resourceId(), "resourceId");
          Objects.requireNonNull(command.snapshotPolicy(), "snapshotPolicy is required");
          if (command.requiredComponentIds().isEmpty()) {
            throw new IllegalArgumentException("at least one required component is required");
          }
          command
              .requiredComponentIds()
              .forEach(
                  componentId -> DomainValidation.requireIdentifier(componentId, "componentId"));
          command
              .participants()
              .forEach(
                  participant -> {
                    Objects.requireNonNull(participant, "participant is required");
                    DomainValidation.requireIdentifier(
                        participant.participantId(), "participantId");
                  });
          long distinctParticipantIds =
              command.participants().stream().map(Participant::participantId).distinct().count();
          if (distinctParticipantIds != command.participants().size()) {
            throw new IllegalArgumentException("participant IDs must be unique");
          }
          if (command.snapshotPolicy() == SnapshotPolicy.CLIENT_MANAGED
              && !command.participants().isEmpty()) {
            throw new IllegalArgumentException(
                "CLIENT_MANAGED resources cannot register quiescence" + " participants");
          }
          if (command.snapshotPolicy() == SnapshotPolicy.COORDINATED_QUIESCE
              && command.participants().isEmpty()) {
            throw new IllegalArgumentException(
                "COORDINATED_QUIESCE resources require at least one" + " participant");
          }
          if (command.participants().size() > maximumParticipantsPerResource) {
            throw new IllegalArgumentException(
                "participants must contain at most " + maximumParticipantsPerResource + " entries");
          }
        });
  }

  private static void validateSnapshotSubmission(SubmitComponentCommand command) {
    Objects.requireNonNull(command, "command is required");
    validateInput(
        () -> {
          Objects.requireNonNull(command.buildId(), "buildId is required");
          if (command.fencingToken() <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
          }
          DomainValidation.requireIdentifier(command.componentId(), "componentId");
          Objects.requireNonNull(command.inputStream(), "inputStream is required");
          if (command.contentLength() < 0) {
            throw new IllegalArgumentException("contentLength must not be negative");
          }
          DomainValidation.requireNonBlank(command.contentType(), "contentType");
          command
              .contentEncoding()
              .ifPresent(
                  value ->
                      DomainValidation.requireNonBlank(
                          value, "contentEncoding", DomainValidation.TEXT_MAX_LENGTH));
          command.expectedSha256().ifPresent(DomainValidation::requireSha256);
          command
              .schemaVersion()
              .ifPresent(
                  value ->
                      DomainValidation.requireNonBlank(
                          value, "schemaVersion", DomainValidation.TEXT_MAX_LENGTH));
        });
  }

  private static void validateVersion(long version) {
    validateInput(
        () -> {
          if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
          }
        });
  }

  private static void validateBuildToken(UUID buildId, long fencingToken) {
    validateInput(
        () -> {
          Objects.requireNonNull(buildId, "buildId is required");
          if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
          }
        });
  }

  private static void validateInput(Runnable validation) {
    try {
      validation.run();
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new VersionGateException(
          ErrorCode.VALIDATION_FAILED, exception.getMessage(), exception);
    }
  }

  private void validateLease(Duration leaseDuration) {
    if (leaseDuration == null) {
      throw new VersionGateException(ErrorCode.VALIDATION_FAILED, "lease duration is required");
    }
    if (leaseDuration.isZero()
        || leaseDuration.isNegative()
        || leaseDuration.compareTo(maximumLease) > 0) {
      throw new VersionGateException(
          ErrorCode.VALIDATION_FAILED,
          "lease duration must be positive and no greater than " + maximumLease);
    }
  }

  private static VersionGateException componentConflict(String componentId) {
    return new VersionGateException(
        ErrorCode.COMPONENT_CONFLICT,
        "Component " + componentId + " already exists with different content");
  }

  private static void requireStoredObject(
      SnapshotStore.StoredObject storedObject,
      String expectedKey,
      long expectedSize,
      Optional<String> expectedSha256) {
    if (storedObject == null
        || !storedObject.reference().objectKey().equals(expectedKey)
        || storedObject.reference().size() != expectedSize
        || (expectedSha256.isPresent()
            && !storedObject.reference().sha256().equalsIgnoreCase(expectedSha256.get()))) {
      throw new VersionGateException(
          ErrorCode.STORAGE_FAILURE,
          "SnapshotStore returned a reference that does not match the upload");
    }
  }

  private static SnapshotStore.ObjectContent requireMatchingContent(
      SnapshotComponent component, SnapshotStore.ObjectContent content) {
    if (content.contentLength() == component.size()
        && content.sha256().equalsIgnoreCase(component.sha256())
        && content.contentType().equals(component.contentType())
        && content.contentEncoding().equals(component.contentEncoding())) {
      return content;
    }
    VersionGateException failure =
        new VersionGateException(
            ErrorCode.STORAGE_FAILURE,
            "SnapshotStore content does not match authoritative component metadata");
    try {
      content.close();
    } catch (Exception cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
    throw failure;
  }

  /**
   * Command that defines one immutable resource registration.
   *
   * @param resourceId unique path-safe resource identifier
   * @param snapshotPolicy capture policy
   * @param requiredComponentIds non-empty required component identifiers
   * @param participants coordinated callback participants, or empty for client-managed capture
   */
  public record RegisterResourceCommand(
      String resourceId,
      SnapshotPolicy snapshotPolicy,
      Set<String> requiredComponentIds,
      List<Participant> participants) {

    /**
     * Creates an immutable registration command.
     *
     * @param resourceId unique path-safe resource identifier
     * @param snapshotPolicy capture policy
     * @param requiredComponentIds non-empty required component identifiers
     * @param participants coordinated callback participants
     */
    public RegisterResourceCommand {
      requiredComponentIds = Set.copyOf(requiredComponentIds);
      participants = List.copyOf(participants);
    }
  }

  /**
   * Command to start one fenced candidate build.
   *
   * @param resourceId registered resource identifier
   * @param owner caller-defined owner for diagnostics
   * @param leaseDuration requested positive initial lease
   */
  public record BeginBuildCommand(String resourceId, String owner, Duration leaseDuration) {}

  /**
   * Command to renew one fenced build lease.
   *
   * @param buildId stable build identifier
   * @param fencingToken current positive fencing token
   * @param leaseDuration requested positive renewal duration
   */
  public record RenewBuildCommand(UUID buildId, long fencingToken, Duration leaseDuration) {}

  /**
   * Command for a fenced lifecycle mutation.
   *
   * @param buildId stable build identifier
   * @param fencingToken current positive fencing token
   */
  public record BuildTokenCommand(UUID buildId, long fencingToken) {}

  /**
   * Streaming command to add one immutable component to a candidate snapshot.
   *
   * @param buildId stable build identifier
   * @param fencingToken current positive fencing token
   * @param componentId required component identifier
   * @param inputStream stream of the exact representation; ownership remains with the caller
   * @param contentLength exact representation length in bytes
   * @param contentType supported representation media type
   * @param contentEncoding optional representation content encoding
   * @param expectedSha256 optional expected digest of the exact representation
   * @param schemaVersion optional caller-defined payload schema version
   * @param capturedAt optional capture instant; the service clock is used when absent
   */
  public record SubmitComponentCommand(
      UUID buildId,
      long fencingToken,
      String componentId,
      InputStream inputStream,
      long contentLength,
      String contentType,
      Optional<String> contentEncoding,
      Optional<String> expectedSha256,
      Optional<String> schemaVersion,
      Optional<Instant> capturedAt) {

    /**
     * Creates an immutable streaming component command.
     *
     * @param buildId stable build identifier
     * @param fencingToken current positive fencing token
     * @param componentId required component identifier
     * @param inputStream stream of the exact representation
     * @param contentLength exact representation length in bytes
     * @param contentType supported representation media type
     * @param contentEncoding optional representation content encoding
     * @param expectedSha256 optional expected digest
     * @param schemaVersion optional caller-defined schema version
     * @param capturedAt optional capture instant
     */
    public SubmitComponentCommand {
      contentEncoding =
          java.util.Objects.requireNonNull(contentEncoding, "contentEncoding is required");
      expectedSha256 =
          java.util.Objects.requireNonNull(expectedSha256, "expectedSha256 is required");
      schemaVersion = java.util.Objects.requireNonNull(schemaVersion, "schemaVersion is required");
      capturedAt = java.util.Objects.requireNonNull(capturedAt, "capturedAt is required");
    }
  }

  /**
   * Closeable component download.
   *
   * @param component authoritative public component metadata
   * @param content integrity-protected representation stream
   */
  public record SnapshotDownload(SnapshotComponent component, SnapshotStore.ObjectContent content)
      implements AutoCloseable {

    /**
     * Closes the underlying snapshot content.
     *
     * @throws Exception when the content stream cannot be closed
     */
    @Override
    public void close() throws Exception {
      content.close();
    }
  }
}
