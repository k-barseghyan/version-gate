package io.github.kbarseghyan.versiongate.application;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.domain.DomainValidation;
import io.github.kbarseghyan.versiongate.domain.LiveReadSession;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.ResourcePolicies;
import io.github.kbarseghyan.versiongate.domain.SnapshotGenerationSession;
import io.github.kbarseghyan.versiongate.domain.SnapshotSelector;
import io.github.kbarseghyan.versiongate.domain.WriteSession;
import io.github.kbarseghyan.versiongate.port.VersionGateStore;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Validation and application facade for the four Version Gate business flows.
 *
 * <p>The {@link VersionGateStore} is the sole correctness boundary. This service never evaluates a
 * lease using JVM time, pre-reads a session before mutating it, or splits a lifecycle decision
 * across persistence calls.
 */
public final class VersionGateService {

  private final VersionGateStore store;
  private final Duration maximumLease;
  private final long maximumSnapshotSize;

  /**
   * Creates the storage-neutral application facade.
   *
   * @param store authoritative transactional store
   * @param maximumLease largest lease accepted from a client
   * @param maximumSnapshotSize largest complete snapshot representation accepted in bytes
   */
  public VersionGateService(
      VersionGateStore store, Duration maximumLease, long maximumSnapshotSize) {
    this.store = Objects.requireNonNull(store, "store is required");
    this.maximumLease = Objects.requireNonNull(maximumLease, "maximumLease is required");
    if (maximumLease.isZero() || maximumLease.isNegative()) {
      throw new IllegalArgumentException("maximumLease must be positive");
    }
    if (maximumSnapshotSize <= 0) {
      throw new IllegalArgumentException("maximumSnapshotSize must be positive");
    }
    this.maximumSnapshotSize = maximumSnapshotSize;
  }

  /** Registers one immutable resource and its independent business policies. */
  public Resource registerResource(RegisterResourceCommand command) {
    validate(
        () -> {
          Objects.requireNonNull(command, "command is required");
          DomainValidation.requireIdentifier(command.resourceId(), "resourceId");
          Objects.requireNonNull(command.policies(), "policies are required");
        });
    return store.registerResource(command.resourceId(), command.policies());
  }

  /** Returns one registered resource. */
  public Resource getResource(String resourceId) {
    validateIdentifier(resourceId, "resourceId");
    return store
        .findResource(resourceId)
        .orElseThrow(
            () ->
                new VersionGateException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Resource " + resourceId + " was not found"));
  }

  /** Begins an exclusive leased and fenced coordinated write. */
  public VersionGateStore.SessionAdmission<WriteSession> beginWrite(BeginWriteCommand command) {
    validateBegin(command, "command");
    return store.beginWrite(
        command.resourceId(), command.owner(), command.leaseDuration(), command.idempotencyKey());
  }

  /** Returns the current durable state of one coordinated write session. */
  public WriteSession getWriteSession(UUID sessionId) {
    validateSessionId(sessionId);
    return store
        .findWriteSession(sessionId)
        .orElseThrow(
            () ->
                new VersionGateException(
                    ErrorCode.WRITE_SESSION_NOT_FOUND,
                    "Write session " + sessionId + " was not found"));
  }

  /** Renews a coordinated write through the authoritative store. */
  public WriteSession renewWrite(RenewSessionCommand command) {
    validateRenew(command);
    return store.renewWrite(command.sessionId(), command.fencingToken(), command.leaseDuration());
  }

  /** Completes a write and atomically activates its allocated version. */
  public WriteSession completeWrite(SessionCommand command) {
    validateSession(command);
    return store.completeWrite(command.sessionId(), command.fencingToken());
  }

  /** Terminates an unsuccessful write with a bounded diagnostic reason. */
  public WriteSession failWrite(FailWriteCommand command) {
    validate(
        () -> {
          Objects.requireNonNull(command, "command is required");
          validateSessionFields(command.sessionId(), command.fencingToken());
          DomainValidation.requireNonBlank(
              command.reason(), "reason", DomainValidation.TEXT_MAX_LENGTH);
        });
    return store.failWrite(command.sessionId(), command.fencingToken(), command.reason());
  }

  /** Explicitly abandons a coordinated write without activating its allocated version. */
  public WriteSession abandonWrite(SessionCommand command) {
    validateSession(command);
    return store.abandonWrite(command.sessionId(), command.fencingToken());
  }

  /** Begins a leased live read bound by storage to the active version. */
  public VersionGateStore.SessionAdmission<LiveReadSession> beginLiveRead(
      BeginLiveReadCommand command) {
    validateBegin(command, "command");
    return store.beginLiveRead(
        command.resourceId(), command.owner(), command.leaseDuration(), command.idempotencyKey());
  }

  /** Returns the current durable state of one coordinated live-read session. */
  public LiveReadSession getLiveReadSession(UUID sessionId) {
    validateSessionId(sessionId);
    return store
        .findLiveReadSession(sessionId)
        .orElseThrow(
            () ->
                new VersionGateException(
                    ErrorCode.LIVE_READ_SESSION_NOT_FOUND,
                    "Live-read session " + sessionId + " was not found"));
  }

  /** Renews a live-read session through the authoritative store. */
  public LiveReadSession renewLiveRead(RenewSessionCommand command) {
    validateRenew(command);
    return store.renewLiveRead(
        command.sessionId(), command.fencingToken(), command.leaseDuration());
  }

  /** Completes a live read and releases its writer exclusion claim. */
  public LiveReadSession completeLiveRead(SessionCommand command) {
    validateSession(command);
    return store.completeLiveRead(command.sessionId(), command.fencingToken());
  }

  /** Explicitly abandons a live-read session. */
  public LiveReadSession abandonLiveRead(SessionCommand command) {
    validateSession(command);
    return store.abandonLiveRead(command.sessionId(), command.fencingToken());
  }

  /** Begins externally driven snapshot generation bound by storage to the active version. */
  public VersionGateStore.SessionAdmission<SnapshotGenerationSession> beginSnapshot(
      BeginSnapshotCommand command) {
    validateBegin(command, "command");
    return store.beginSnapshot(
        command.resourceId(), command.owner(), command.leaseDuration(), command.idempotencyKey());
  }

  /** Returns the current durable state of one snapshot-generation session. */
  public SnapshotGenerationSession getSnapshotSession(UUID sessionId) {
    validateSessionId(sessionId);
    return store
        .findSnapshotSession(sessionId)
        .orElseThrow(
            () ->
                new VersionGateException(
                    ErrorCode.SNAPSHOT_SESSION_NOT_FOUND,
                    "Snapshot session " + sessionId + " was not found"));
  }

  /** Renews an active snapshot-generation session through the authoritative store. */
  public SnapshotGenerationSession renewSnapshot(RenewSessionCommand command) {
    validateRenew(command);
    return store.renewSnapshot(
        command.sessionId(), command.fencingToken(), command.leaseDuration());
  }

  /**
   * Streams a complete opaque snapshot to the authoritative store for atomic publication.
   *
   * <p>The caller retains ownership of the supplied stream.
   */
  public VersionGateStore.SnapshotSubmission submitSnapshot(SubmitSnapshotCommand command) {
    validateSnapshotSubmission(command);
    VersionGateStore.SnapshotUpload upload =
        validateAndGet(
            () ->
                new VersionGateStore.SnapshotUpload(
                    command.inputStream(),
                    command.contentLength(),
                    command.contentType(),
                    command.contentEncoding(),
                    command.expectedSha256()));
    return store.submitSnapshot(command.sessionId(), command.fencingToken(), upload);
  }

  /** Aborts snapshot generation without publishing staged content. */
  public SnapshotGenerationSession abortSnapshot(SessionCommand command) {
    validateSession(command);
    return store.abortSnapshot(command.sessionId(), command.fencingToken());
  }

  /** Retrieves one exact immutable snapshot regardless of an active write. */
  public VersionGateStore.SnapshotContent getSnapshotByVersion(String resourceId, long version) {
    validateIdentifier(resourceId, "resourceId");
    validateVersion(version);
    return store.getSnapshot(resourceId, SnapshotSelector.BY_VERSION, OptionalLong.of(version));
  }

  /** Retrieves the immutable snapshot for the active completed version. */
  public VersionGateStore.SnapshotContent getCurrentSnapshot(String resourceId) {
    validateIdentifier(resourceId, "resourceId");
    return store.getSnapshot(resourceId, SnapshotSelector.CURRENT, OptionalLong.empty());
  }

  /** Retrieves the highest stored coordinator version with explicit stale metadata. */
  public VersionGateStore.SnapshotContent getLatestAvailableSnapshot(String resourceId) {
    validateIdentifier(resourceId, "resourceId");
    return store.getSnapshot(resourceId, SnapshotSelector.LATEST_AVAILABLE, OptionalLong.empty());
  }

  /**
   * Retrieves a snapshot using the resource's immutable default selector.
   *
   * @param resourceId registered resource identifier
   * @param version exact version required only when the default selector is {@code BY_VERSION}
   */
  public VersionGateStore.SnapshotContent getDefaultSnapshot(
      String resourceId, OptionalLong version) {
    validate(
        () -> {
          DomainValidation.requireIdentifier(resourceId, "resourceId");
          Objects.requireNonNull(version, "version is required");
          if (version.isPresent() && version.getAsLong() < 0) {
            throw new IllegalArgumentException("version must not be negative");
          }
        });
    Resource resource = getResource(resourceId);
    SnapshotSelector selector =
        resource
            .policies()
            .defaultSnapshotSelector()
            .orElseThrow(
                () ->
                    new VersionGateException(
                        ErrorCode.SNAPSHOT_SUPPORT_DISABLED,
                        "Resource " + resourceId + " has snapshot support disabled"));
    validateDefaultSelectorVersion(selector, version);
    return store.getSnapshot(resourceId, selector, version);
  }

  private void validateBegin(BeginCommand command, String name) {
    validate(
        () -> {
          Objects.requireNonNull(command, name + " is required");
          DomainValidation.requireIdentifier(command.resourceId(), "resourceId");
          DomainValidation.requireNonBlank(
              command.owner(), "owner", DomainValidation.TEXT_MAX_LENGTH);
          requireValidLease(command.leaseDuration());
          DomainValidation.requireIdempotencyKey(command.idempotencyKey());
        });
  }

  private void validateRenew(RenewSessionCommand command) {
    validate(
        () -> {
          Objects.requireNonNull(command, "command is required");
          validateSessionFields(command.sessionId(), command.fencingToken());
          requireValidLease(command.leaseDuration());
        });
  }

  private static void validateSession(SessionCommand command) {
    validate(
        () -> {
          Objects.requireNonNull(command, "command is required");
          validateSessionFields(command.sessionId(), command.fencingToken());
        });
  }

  private void validateSnapshotSubmission(SubmitSnapshotCommand command) {
    validate(
        () -> {
          Objects.requireNonNull(command, "command is required");
          validateSessionFields(command.sessionId(), command.fencingToken());
          Objects.requireNonNull(command.inputStream(), "inputStream is required");
          if (command.contentLength() < 0) {
            throw new IllegalArgumentException("contentLength must not be negative");
          }
          if (command.contentLength() > maximumSnapshotSize) {
            throw new IllegalArgumentException(
                "contentLength must not exceed " + maximumSnapshotSize + " bytes");
          }
          DomainValidation.requireNonBlank(
              command.contentType(), "contentType", DomainValidation.TEXT_MAX_LENGTH);
          Objects.requireNonNull(command.contentEncoding(), "contentEncoding is required")
              .ifPresent(
                  value ->
                      DomainValidation.requireNonBlank(
                          value, "contentEncoding", DomainValidation.TEXT_MAX_LENGTH));
          Objects.requireNonNull(command.expectedSha256(), "expectedSha256 is required")
              .ifPresent(DomainValidation::requireSha256);
        });
  }

  private static void validateSessionFields(UUID sessionId, long fencingToken) {
    Objects.requireNonNull(sessionId, "sessionId is required");
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken must be positive");
    }
  }

  private static void validateSessionId(UUID sessionId) {
    validate(() -> Objects.requireNonNull(sessionId, "sessionId is required"));
  }

  private void requireValidLease(Duration leaseDuration) {
    Objects.requireNonNull(leaseDuration, "leaseDuration is required");
    if (leaseDuration.isZero()
        || leaseDuration.isNegative()
        || leaseDuration.compareTo(maximumLease) > 0) {
      throw new IllegalArgumentException(
          "leaseDuration must be positive and no greater than " + maximumLease);
    }
  }

  private static void validateIdentifier(String value, String name) {
    validate(() -> DomainValidation.requireIdentifier(value, name));
  }

  private static void validateVersion(long version) {
    validate(
        () -> {
          if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
          }
        });
  }

  private static void validateDefaultSelectorVersion(
      SnapshotSelector selector, OptionalLong version) {
    validate(
        () -> {
          if (selector == SnapshotSelector.BY_VERSION && version.isEmpty()) {
            throw new IllegalArgumentException(
                "version is required when the default snapshot selector is BY_VERSION");
          }
          if (selector != SnapshotSelector.BY_VERSION && version.isPresent()) {
            throw new IllegalArgumentException(
                "version is allowed only when the default snapshot selector is BY_VERSION");
          }
        });
  }

  private static void validate(Runnable validation) {
    try {
      validation.run();
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new VersionGateException(
          ErrorCode.VALIDATION_FAILED, exception.getMessage(), exception);
    }
  }

  private static <T> T validateAndGet(java.util.function.Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new VersionGateException(
          ErrorCode.VALIDATION_FAILED, exception.getMessage(), exception);
    }
  }

  private interface BeginCommand {
    String resourceId();

    String owner();

    Duration leaseDuration();

    String idempotencyKey();
  }

  /** Immutable resource-registration command. */
  public record RegisterResourceCommand(String resourceId, ResourcePolicies policies) {}

  /** Immutable coordinated-write admission command. */
  public record BeginWriteCommand(
      String resourceId, String owner, Duration leaseDuration, String idempotencyKey)
      implements BeginCommand {}

  /** Immutable live-read admission command. */
  public record BeginLiveReadCommand(
      String resourceId, String owner, Duration leaseDuration, String idempotencyKey)
      implements BeginCommand {}

  /** Immutable snapshot-generation admission command. */
  public record BeginSnapshotCommand(
      String resourceId, String owner, Duration leaseDuration, String idempotencyKey)
      implements BeginCommand {}

  /** Immutable fenced session command. */
  public record SessionCommand(UUID sessionId, long fencingToken) {}

  /** Immutable fenced lease-renewal command. */
  public record RenewSessionCommand(UUID sessionId, long fencingToken, Duration leaseDuration) {}

  /** Immutable failed-write command. */
  public record FailWriteCommand(UUID sessionId, long fencingToken, String reason) {}

  /** Immutable streaming snapshot-publication command. */
  public record SubmitSnapshotCommand(
      UUID sessionId,
      long fencingToken,
      InputStream inputStream,
      long contentLength,
      String contentType,
      Optional<String> contentEncoding,
      Optional<String> expectedSha256) {}
}
