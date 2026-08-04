package io.github.kbarseghyan.versiongate.testkit;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.domain.DomainValidation;
import io.github.kbarseghyan.versiongate.domain.LiveReadSession;
import io.github.kbarseghyan.versiongate.domain.LiveReadStatus;
import io.github.kbarseghyan.versiongate.domain.MissingCurrentSnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.ResourcePolicies;
import io.github.kbarseghyan.versiongate.domain.RetrievalDuringWritePolicy;
import io.github.kbarseghyan.versiongate.domain.SnapshotGenerationSession;
import io.github.kbarseghyan.versiongate.domain.SnapshotGenerationStatus;
import io.github.kbarseghyan.versiongate.domain.SnapshotSelector;
import io.github.kbarseghyan.versiongate.domain.SnapshotSupport;
import io.github.kbarseghyan.versiongate.domain.StoredSnapshot;
import io.github.kbarseghyan.versiongate.domain.WriteSession;
import io.github.kbarseghyan.versiongate.domain.WriteStatus;
import io.github.kbarseghyan.versiongate.domain.WriterDuringSnapshotPolicy;
import io.github.kbarseghyan.versiongate.port.VersionGateStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic, thread-safe implementation of the complete {@link VersionGateStore} contract.
 *
 * <p>This store is intended for tests and local composition only. It uses the supplied clock as its
 * storage-authoritative time source and keeps committed snapshot bytes in memory. Upload bytes
 * remain method-local and invisible until the atomic publication step.
 */
public final class InMemoryVersionGateStore implements VersionGateStore {

  private static final int COPY_BUFFER_SIZE = 8192;

  private final Clock authoritativeClock;
  private final BackingState backingState;
  private final Object monitor;
  private final Map<String, ResourceState> resources;
  private final Map<UUID, WriteSession> writeSessions;
  private final Map<UUID, String> writeResources;
  private final Map<UUID, LiveReadSession> liveReadSessions;
  private final Map<UUID, String> liveReadResources;
  private final Map<UUID, SnapshotGenerationSession> snapshotSessions;
  private final Map<UUID, String> snapshotResources;
  private final Map<AdmissionKey, AdmissionRecord> admissions;

  /**
   * Creates a deterministic store.
   *
   * @param authoritativeClock clock representing storage-authoritative time
   */
  public InMemoryVersionGateStore(Clock authoritativeClock) {
    this(new BackingState(authoritativeClock));
  }

  /**
   * Reconstructs a deterministic store around existing backing state.
   *
   * <p>This constructor is a test capability for proving that authoritative state belongs to the
   * store boundary rather than one application or adapter object.
   *
   * @param backingState state retained across reconstructed in-memory store instances
   */
  public InMemoryVersionGateStore(BackingState backingState) {
    this.backingState = Objects.requireNonNull(backingState, "backingState is required");
    authoritativeClock = backingState.authoritativeClock;
    monitor = backingState.monitor;
    resources = backingState.resources;
    writeSessions = backingState.writeSessions;
    writeResources = backingState.writeResources;
    liveReadSessions = backingState.liveReadSessions;
    liveReadResources = backingState.liveReadResources;
    snapshotSessions = backingState.snapshotSessions;
    snapshotResources = backingState.snapshotResources;
    admissions = backingState.admissions;
  }

  @Override
  public Resource registerResource(String resourceId, ResourcePolicies policies) {
    Objects.requireNonNull(policies, "policies are required");
    synchronized (monitor) {
      if (resources.containsKey(resourceId)) {
        throw failure(
            ErrorCode.RESOURCE_ALREADY_EXISTS, "Resource " + resourceId + " is already registered");
      }
      Instant now = authoritativeClock.instant();
      Resource resource = new Resource(resourceId, policies, null, now, now);
      resources.put(resourceId, new ResourceState(resource));
      return resource;
    }
  }

  @Override
  public Optional<Resource> findResource(String resourceId) {
    synchronized (monitor) {
      ResourceState state = resources.get(resourceId);
      return state == null ? Optional.empty() : Optional.of(state.resource);
    }
  }

  @Override
  public SessionAdmission<WriteSession> beginWrite(
      String resourceId, String owner, Duration leaseDuration, String idempotencyKey) {
    requireBeginInputs(owner, leaseDuration, idempotencyKey);
    synchronized (monitor) {
      ResourceState state = requireResource(resourceId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      AdmissionKey admissionKey =
          new AdmissionKey(resourceId, BeginOperation.WRITE, idempotencyKey);
      AdmissionFingerprint fingerprint = new AdmissionFingerprint(owner, leaseDuration);
      AdmissionRecord priorAdmission = admissions.get(admissionKey);
      if (priorAdmission != null) {
        requireSameFingerprint(priorAdmission, fingerprint);
        WriteSession priorSession = writeSessions.get(priorAdmission.sessionId());
        if (priorSession == null) {
          throw failure(ErrorCode.STORAGE_FAILURE, "Write admission history is inconsistent");
        }
        return new SessionAdmission<>(priorSession, true);
      }
      if (state.activeWriteSessionId != null) {
        throw failure(
            ErrorCode.WRITE_ALREADY_ACTIVE, "A write is already active for " + resourceId);
      }
      if (!state.activeLiveReadSessionIds.isEmpty()) {
        throw failure(
            ErrorCode.LIVE_READ_ACTIVE, "A coordinated live read is active for " + resourceId);
      }

      SnapshotGenerationSession generation = activeSnapshotSession(state);
      if (generation != null && writerPolicy(state) == WriterDuringSnapshotPolicy.BLOCK_WRITER) {
        throw failure(
            ErrorCode.SNAPSHOT_GENERATION_ACTIVE,
            "Snapshot generation is active for " + resourceId);
      }
      if (state.resource.activeVersion() != null
          && state.resource.policies().snapshotSupport() == SnapshotSupport.ENABLED
          && state.resource.policies().missingCurrentSnapshotPolicy()
              == MissingCurrentSnapshotPolicy.REQUIRE_CURRENT_SNAPSHOT
          && !state.snapshots.containsKey(state.resource.activeVersion())) {
        throw failure(
            ErrorCode.CURRENT_SNAPSHOT_REQUIRED,
            "The active version of " + resourceId + " has no stored snapshot");
      }

      long allocatedVersion = state.nextVersion;
      long fencingToken = state.nextFencingToken;
      UUID sessionId = new UUID(0L, backingState.nextSessionSequence);
      long followingVersion = incrementSequence(allocatedVersion, "coordinator version");
      long followingFencingToken = incrementSequence(fencingToken, "fencing token");
      long followingSessionSequence =
          incrementSequence(backingState.nextSessionSequence, "session identifier");
      Instant leaseExpiresAt = now.plus(leaseDuration);
      WriteSession session =
          new WriteSession(
              sessionId,
              resourceId,
              allocatedVersion,
              state.resource.activeVersion(),
              WriteStatus.WRITING,
              owner,
              fencingToken,
              leaseExpiresAt,
              Optional.empty(),
              now,
              now);

      if (generation != null) {
        SnapshotGenerationSession invalidated =
            snapshotWithStatus(
                generation, SnapshotGenerationStatus.INVALIDATED, generation.leaseExpiresAt(), now);
        snapshotSessions.put(generation.sessionId(), invalidated);
        state.activeSnapshotSessionId = null;
      }
      state.nextVersion = followingVersion;
      state.nextFencingToken = followingFencingToken;
      backingState.nextSessionSequence = followingSessionSequence;
      writeSessions.put(sessionId, session);
      writeResources.put(sessionId, resourceId);
      admissions.put(admissionKey, new AdmissionRecord(fingerprint, sessionId));
      state.activeWriteSessionId = sessionId;
      return new SessionAdmission<>(session, false);
    }
  }

  @Override
  public Optional<WriteSession> findWriteSession(UUID sessionId) {
    synchronized (monitor) {
      String resourceId = writeResources.get(sessionId);
      if (resourceId == null) {
        return Optional.empty();
      }
      expireSessions(resources.get(resourceId), authoritativeClock.instant());
      return Optional.of(writeSessions.get(sessionId));
    }
  }

  @Override
  public WriteSession renewWrite(UUID sessionId, long fencingToken, Duration leaseDuration) {
    requirePositiveDuration(leaseDuration);
    synchronized (monitor) {
      ResourceState state = requireWriteResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      WriteSession current = requireWriteSessionAndFence(sessionId, fencingToken);
      requireLiveWrite(current);
      WriteSession renewed =
          new WriteSession(
              current.sessionId(),
              current.resourceId(),
              current.allocatedVersion(),
              current.baseActiveVersion(),
              current.status(),
              current.owner(),
              current.fencingToken(),
              now.plus(leaseDuration),
              current.failureReason(),
              current.createdAt(),
              now);
      writeSessions.put(sessionId, renewed);
      return renewed;
    }
  }

  @Override
  public WriteSession completeWrite(UUID sessionId, long fencingToken) {
    synchronized (monitor) {
      ResourceState state = requireWriteResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      WriteSession current = requireWriteSessionAndFence(sessionId, fencingToken);
      if (current.status() == WriteStatus.COMPLETED) {
        return current;
      }
      requireLiveWrite(current);
      if (!Objects.equals(state.activeWriteSessionId, sessionId)) {
        throw failure(ErrorCode.STORAGE_FAILURE, "Active write ownership is inconsistent");
      }
      if (!Objects.equals(state.resource.activeVersion(), current.baseActiveVersion())) {
        throw failure(ErrorCode.STORAGE_FAILURE, "The active version changed during a write");
      }
      WriteSession completed =
          writeWithStatus(current, WriteStatus.COMPLETED, current.failureReason(), now);
      Resource activatedResource =
          new Resource(
              state.resource.resourceId(),
              state.resource.policies(),
              current.allocatedVersion(),
              state.resource.createdAt(),
              now);
      writeSessions.put(sessionId, completed);
      state.activeWriteSessionId = null;
      state.resource = activatedResource;
      return completed;
    }
  }

  @Override
  public WriteSession failWrite(UUID sessionId, long fencingToken, String reason) {
    synchronized (monitor) {
      ResourceState state = requireWriteResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      WriteSession current = requireWriteSessionAndFence(sessionId, fencingToken);
      if (current.status() == WriteStatus.FAILED) {
        return current;
      }
      requireLiveWrite(current);
      WriteSession failed = writeWithStatus(current, WriteStatus.FAILED, Optional.of(reason), now);
      writeSessions.put(sessionId, failed);
      state.activeWriteSessionId = null;
      return failed;
    }
  }

  @Override
  public WriteSession abandonWrite(UUID sessionId, long fencingToken) {
    synchronized (monitor) {
      ResourceState state = requireWriteResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      WriteSession current = requireWriteSessionAndFence(sessionId, fencingToken);
      if (current.status() == WriteStatus.ABANDONED) {
        return current;
      }
      requireLiveWrite(current);
      WriteSession abandoned =
          writeWithStatus(current, WriteStatus.ABANDONED, current.failureReason(), now);
      writeSessions.put(sessionId, abandoned);
      state.activeWriteSessionId = null;
      return abandoned;
    }
  }

  @Override
  public SessionAdmission<LiveReadSession> beginLiveRead(
      String resourceId, String owner, Duration leaseDuration, String idempotencyKey) {
    requireBeginInputs(owner, leaseDuration, idempotencyKey);
    synchronized (monitor) {
      ResourceState state = requireResource(resourceId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      AdmissionKey admissionKey =
          new AdmissionKey(resourceId, BeginOperation.LIVE_READ, idempotencyKey);
      AdmissionFingerprint fingerprint = new AdmissionFingerprint(owner, leaseDuration);
      AdmissionRecord priorAdmission = admissions.get(admissionKey);
      if (priorAdmission != null) {
        requireSameFingerprint(priorAdmission, fingerprint);
        LiveReadSession priorSession = liveReadSessions.get(priorAdmission.sessionId());
        if (priorSession == null) {
          throw failure(ErrorCode.STORAGE_FAILURE, "Live-read admission history is inconsistent");
        }
        return new SessionAdmission<>(priorSession, true);
      }
      if (state.activeWriteSessionId != null) {
        throw failure(ErrorCode.WRITE_IN_PROGRESS, "A write is active for " + resourceId);
      }
      long activeVersion = requireActiveVersion(state);
      UUID sessionId = nextSessionId();
      LiveReadSession session =
          new LiveReadSession(
              sessionId,
              resourceId,
              activeVersion,
              LiveReadStatus.READING,
              owner,
              nextFencingToken(state),
              now.plus(leaseDuration),
              now,
              now);
      liveReadSessions.put(sessionId, session);
      liveReadResources.put(sessionId, resourceId);
      admissions.put(admissionKey, new AdmissionRecord(fingerprint, sessionId));
      state.activeLiveReadSessionIds.add(sessionId);
      return new SessionAdmission<>(session, false);
    }
  }

  @Override
  public Optional<LiveReadSession> findLiveReadSession(UUID sessionId) {
    synchronized (monitor) {
      String resourceId = liveReadResources.get(sessionId);
      if (resourceId == null) {
        return Optional.empty();
      }
      expireSessions(resources.get(resourceId), authoritativeClock.instant());
      return Optional.of(liveReadSessions.get(sessionId));
    }
  }

  @Override
  public LiveReadSession renewLiveRead(UUID sessionId, long fencingToken, Duration leaseDuration) {
    requirePositiveDuration(leaseDuration);
    synchronized (monitor) {
      ResourceState state = requireLiveReadResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      LiveReadSession current = requireLiveReadSessionAndFence(sessionId, fencingToken);
      requireLiveRead(current);
      LiveReadSession renewed =
          new LiveReadSession(
              current.sessionId(),
              current.resourceId(),
              current.boundVersion(),
              current.status(),
              current.owner(),
              current.fencingToken(),
              now.plus(leaseDuration),
              current.createdAt(),
              now);
      liveReadSessions.put(sessionId, renewed);
      return renewed;
    }
  }

  @Override
  public LiveReadSession completeLiveRead(UUID sessionId, long fencingToken) {
    return finishLiveRead(sessionId, fencingToken, LiveReadStatus.COMPLETED);
  }

  @Override
  public LiveReadSession abandonLiveRead(UUID sessionId, long fencingToken) {
    return finishLiveRead(sessionId, fencingToken, LiveReadStatus.ABANDONED);
  }

  @Override
  public SessionAdmission<SnapshotGenerationSession> beginSnapshot(
      String resourceId, String owner, Duration leaseDuration, String idempotencyKey) {
    requireBeginInputs(owner, leaseDuration, idempotencyKey);
    synchronized (monitor) {
      ResourceState state = requireResource(resourceId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      AdmissionKey admissionKey =
          new AdmissionKey(resourceId, BeginOperation.SNAPSHOT, idempotencyKey);
      AdmissionFingerprint fingerprint = new AdmissionFingerprint(owner, leaseDuration);
      AdmissionRecord priorAdmission = admissions.get(admissionKey);
      if (priorAdmission != null) {
        requireSameFingerprint(priorAdmission, fingerprint);
        SnapshotGenerationSession priorSession = snapshotSessions.get(priorAdmission.sessionId());
        if (priorSession == null) {
          throw failure(ErrorCode.STORAGE_FAILURE, "Snapshot admission history is inconsistent");
        }
        return new SessionAdmission<>(priorSession, true);
      }
      if (state.resource.policies().snapshotSupport() == SnapshotSupport.DISABLED) {
        throw failure(
            ErrorCode.SNAPSHOT_SUPPORT_DISABLED, "Snapshot support is disabled for " + resourceId);
      }
      if (state.activeWriteSessionId != null) {
        throw failure(ErrorCode.WRITE_IN_PROGRESS, "A write is active for " + resourceId);
      }
      long activeVersion = requireActiveVersion(state);
      if (state.activeSnapshotSessionId != null || state.snapshots.containsKey(activeVersion)) {
        throw failure(
            ErrorCode.SNAPSHOT_SESSION_ALREADY_EXISTS,
            "An active snapshot-generation session or stored snapshot already exists for version "
                + activeVersion);
      }
      UUID sessionId = nextSessionId();
      SnapshotGenerationSession session =
          new SnapshotGenerationSession(
              sessionId,
              resourceId,
              activeVersion,
              SnapshotGenerationStatus.GENERATING,
              owner,
              nextFencingToken(state),
              now.plus(leaseDuration),
              now,
              now);
      snapshotSessions.put(sessionId, session);
      snapshotResources.put(sessionId, resourceId);
      admissions.put(admissionKey, new AdmissionRecord(fingerprint, sessionId));
      state.activeSnapshotSessionId = sessionId;
      return new SessionAdmission<>(session, false);
    }
  }

  @Override
  public Optional<SnapshotGenerationSession> findSnapshotSession(UUID sessionId) {
    synchronized (monitor) {
      String resourceId = snapshotResources.get(sessionId);
      if (resourceId == null) {
        return Optional.empty();
      }
      expireSessions(resources.get(resourceId), authoritativeClock.instant());
      return Optional.of(snapshotSessions.get(sessionId));
    }
  }

  @Override
  public SnapshotGenerationSession renewSnapshot(
      UUID sessionId, long fencingToken, Duration leaseDuration) {
    requirePositiveDuration(leaseDuration);
    synchronized (monitor) {
      ResourceState state = requireSnapshotResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      SnapshotGenerationSession current = requireSnapshotSessionAndFence(sessionId, fencingToken);
      requireGenerating(current);
      SnapshotGenerationSession renewed =
          new SnapshotGenerationSession(
              current.sessionId(),
              current.resourceId(),
              current.snapshotVersion(),
              current.status(),
              current.owner(),
              current.fencingToken(),
              now.plus(leaseDuration),
              current.createdAt(),
              now);
      snapshotSessions.put(sessionId, renewed);
      return renewed;
    }
  }

  @Override
  public SnapshotGenerationSession abortSnapshot(UUID sessionId, long fencingToken) {
    synchronized (monitor) {
      ResourceState state = requireSnapshotResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      SnapshotGenerationSession current = requireSnapshotSessionAndFence(sessionId, fencingToken);
      if (current.status() == SnapshotGenerationStatus.ABORTED) {
        return current;
      }
      requireGenerating(current);
      SnapshotGenerationSession aborted =
          snapshotWithStatus(
              current, SnapshotGenerationStatus.ABORTED, current.leaseExpiresAt(), now);
      snapshotSessions.put(sessionId, aborted);
      state.activeSnapshotSessionId = null;
      return aborted;
    }
  }

  @Override
  public SnapshotSubmission submitSnapshot(
      UUID sessionId, long fencingToken, SnapshotUpload upload) {
    Objects.requireNonNull(upload, "upload is required");
    synchronized (monitor) {
      ResourceState state = requireSnapshotResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      SnapshotGenerationSession session = requireSnapshotSessionAndFence(sessionId, fencingToken);
      requireSubmittable(session);
    }

    StagedSnapshot staged;
    try {
      staged = stage(upload);
    } catch (VersionGateException stagingFailure) {
      synchronized (monitor) {
        ResourceState state = requireSnapshotResource(sessionId);
        Instant now = authoritativeClock.instant();
        expireSessions(state, now);
        SnapshotGenerationSession session = requireSnapshotSessionAndFence(sessionId, fencingToken);
        if (session.status() == SnapshotGenerationStatus.INVALIDATED) {
          throw failure(
              ErrorCode.SNAPSHOT_INVALIDATED,
              "Snapshot session " + sessionId + " was invalidated by a writer");
        }
      }
      throw stagingFailure;
    }

    synchronized (monitor) {
      ResourceState state = requireSnapshotResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      SnapshotGenerationSession session = requireSnapshotSessionAndFence(sessionId, fencingToken);
      if (session.status() == SnapshotGenerationStatus.INVALIDATED) {
        throw failure(
            ErrorCode.SNAPSHOT_INVALIDATED,
            "Snapshot session " + sessionId + " was invalidated by a writer");
      }
      if (session.status() == SnapshotGenerationStatus.PUBLISHED) {
        SnapshotData existing = state.snapshots.get(session.snapshotVersion());
        if (existing == null) {
          throw failure(ErrorCode.STORAGE_FAILURE, "Published snapshot metadata is inconsistent");
        }
        requireSameSnapshot(existing, staged);
        return new SnapshotSubmission(existing.snapshot, true);
      }
      requireGenerating(session);
      if (state.activeWriteSessionId != null
          || !Objects.equals(state.activeSnapshotSessionId, sessionId)) {
        throw failure(ErrorCode.STORAGE_FAILURE, "Snapshot publication ownership is inconsistent");
      }
      StoredSnapshot stored =
          new StoredSnapshot(
              session.resourceId(),
              session.snapshotVersion(),
              staged.bytes.length,
              upload.contentType(),
              upload.contentEncoding(),
              staged.sha256,
              now);
      SnapshotGenerationSession published =
          snapshotWithStatus(
              session, SnapshotGenerationStatus.PUBLISHED, session.leaseExpiresAt(), now);
      state.snapshots.put(session.snapshotVersion(), new SnapshotData(stored, staged.bytes));
      snapshotSessions.put(sessionId, published);
      state.activeSnapshotSessionId = null;
      return new SnapshotSubmission(stored, false);
    }
  }

  @Override
  public SnapshotContent getSnapshot(
      String resourceId, SnapshotSelector selector, OptionalLong requestedVersion) {
    Objects.requireNonNull(selector, "selector is required");
    Objects.requireNonNull(requestedVersion, "requestedVersion is required");
    validateSelection(selector, requestedVersion);
    synchronized (monitor) {
      ResourceState state = requireResource(resourceId);
      expireSessions(state, authoritativeClock.instant());
      if (selector != SnapshotSelector.BY_VERSION) {
        if (state.resource.policies().snapshotSupport() == SnapshotSupport.DISABLED) {
          throw failure(
              ErrorCode.SNAPSHOT_SUPPORT_DISABLED,
              "Snapshot support is disabled for " + resourceId);
        }
        if (state.activeWriteSessionId != null
            && state.resource.policies().retrievalDuringWritePolicy().orElseThrow()
                == RetrievalDuringWritePolicy.REJECT_IF_WRITING) {
          throw failure(ErrorCode.WRITE_IN_PROGRESS, "A write is active for " + resourceId);
        }
      }

      long activeVersion = requireResolutionActiveVersion(state, selector);
      SnapshotData selected =
          switch (selector) {
            case BY_VERSION -> state.snapshots.get(requestedVersion.getAsLong());
            case CURRENT -> state.snapshots.get(activeVersion);
            case LATEST_AVAILABLE ->
                state.snapshots.isEmpty() ? null : state.snapshots.lastEntry().getValue();
          };
      if (selected == null) {
        ErrorCode code =
            selector == SnapshotSelector.CURRENT
                ? ErrorCode.CURRENT_SNAPSHOT_UNAVAILABLE
                : ErrorCode.SNAPSHOT_NOT_FOUND;
        throw failure(code, "No snapshot matches " + selector + " for " + resourceId);
      }
      boolean stale =
          selector == SnapshotSelector.LATEST_AVAILABLE
              && selected.snapshot.snapshotVersion() < activeVersion;
      SnapshotResolution resolution =
          new SnapshotResolution(selected.snapshot, activeVersion, selector, stale);
      return new SnapshotContent(resolution, new ByteArrayInputStream(selected.bytes.clone()));
    }
  }

  private LiveReadSession finishLiveRead(UUID sessionId, long fencingToken, LiveReadStatus target) {
    synchronized (monitor) {
      ResourceState state = requireLiveReadResource(sessionId);
      Instant now = authoritativeClock.instant();
      expireSessions(state, now);
      LiveReadSession current = requireLiveReadSessionAndFence(sessionId, fencingToken);
      if (current.status() == target) {
        return current;
      }
      requireLiveRead(current);
      LiveReadSession finished = liveReadWithStatus(current, target, now);
      liveReadSessions.put(sessionId, finished);
      state.activeLiveReadSessionIds.remove(sessionId);
      return finished;
    }
  }

  private ResourceState requireResource(String resourceId) {
    ResourceState state = resources.get(resourceId);
    if (state == null) {
      throw failure(ErrorCode.RESOURCE_NOT_FOUND, "Resource " + resourceId + " was not found");
    }
    return state;
  }

  private ResourceState requireWriteResource(UUID sessionId) {
    String resourceId = writeResources.get(sessionId);
    if (resourceId == null) {
      throw failure(
          ErrorCode.WRITE_SESSION_NOT_FOUND, "Write session " + sessionId + " was not found");
    }
    return resources.get(resourceId);
  }

  private ResourceState requireLiveReadResource(UUID sessionId) {
    String resourceId = liveReadResources.get(sessionId);
    if (resourceId == null) {
      throw failure(
          ErrorCode.LIVE_READ_SESSION_NOT_FOUND,
          "Live-read session " + sessionId + " was not found");
    }
    return resources.get(resourceId);
  }

  private ResourceState requireSnapshotResource(UUID sessionId) {
    String resourceId = snapshotResources.get(sessionId);
    if (resourceId == null) {
      throw failure(
          ErrorCode.SNAPSHOT_SESSION_NOT_FOUND, "Snapshot session " + sessionId + " was not found");
    }
    return resources.get(resourceId);
  }

  private WriteSession requireWriteSessionAndFence(UUID sessionId, long fencingToken) {
    WriteSession session = writeSessions.get(sessionId);
    if (session == null) {
      throw failure(
          ErrorCode.WRITE_SESSION_NOT_FOUND, "Write session " + sessionId + " was not found");
    }
    requireFence(session.fencingToken(), fencingToken, sessionId);
    return session;
  }

  private LiveReadSession requireLiveReadSessionAndFence(UUID sessionId, long fencingToken) {
    LiveReadSession session = liveReadSessions.get(sessionId);
    if (session == null) {
      throw failure(
          ErrorCode.LIVE_READ_SESSION_NOT_FOUND,
          "Live-read session " + sessionId + " was not found");
    }
    requireFence(session.fencingToken(), fencingToken, sessionId);
    return session;
  }

  private SnapshotGenerationSession requireSnapshotSessionAndFence(
      UUID sessionId, long fencingToken) {
    SnapshotGenerationSession session = snapshotSessions.get(sessionId);
    if (session == null) {
      throw failure(
          ErrorCode.SNAPSHOT_SESSION_NOT_FOUND, "Snapshot session " + sessionId + " was not found");
    }
    requireFence(session.fencingToken(), fencingToken, sessionId);
    return session;
  }

  private static void requireFence(long expected, long actual, UUID sessionId) {
    if (expected != actual) {
      throw failure(
          ErrorCode.STALE_FENCING_TOKEN,
          "Fencing token " + actual + " is stale for session " + sessionId);
    }
  }

  private static void requireLiveWrite(WriteSession session) {
    if (session.status() == WriteStatus.EXPIRED) {
      throw failure(ErrorCode.LEASE_EXPIRED, "Write session lease has expired");
    }
    if (session.status() != WriteStatus.WRITING) {
      throw invalidTransition("write", session.status());
    }
  }

  private static void requireLiveRead(LiveReadSession session) {
    if (session.status() == LiveReadStatus.EXPIRED) {
      throw failure(ErrorCode.LEASE_EXPIRED, "Live-read session lease has expired");
    }
    if (session.status() != LiveReadStatus.READING) {
      throw invalidTransition("live read", session.status());
    }
  }

  private static void requireGenerating(SnapshotGenerationSession session) {
    if (session.status() == SnapshotGenerationStatus.EXPIRED) {
      throw failure(ErrorCode.LEASE_EXPIRED, "Snapshot session lease has expired");
    }
    if (session.status() == SnapshotGenerationStatus.INVALIDATED) {
      throw failure(ErrorCode.SNAPSHOT_INVALIDATED, "Snapshot session was invalidated");
    }
    if (session.status() != SnapshotGenerationStatus.GENERATING) {
      throw invalidTransition("snapshot generation", session.status());
    }
  }

  private static void requireSubmittable(SnapshotGenerationSession session) {
    if (session.status() == SnapshotGenerationStatus.PUBLISHED) {
      return;
    }
    requireGenerating(session);
  }

  private void expireSessions(ResourceState state, Instant now) {
    if (state.activeWriteSessionId != null) {
      WriteSession write = writeSessions.get(state.activeWriteSessionId);
      if (write.status() == WriteStatus.WRITING && expired(write.leaseExpiresAt(), now)) {
        writeSessions.put(
            write.sessionId(),
            writeWithStatus(write, WriteStatus.EXPIRED, write.failureReason(), now));
        state.activeWriteSessionId = null;
      }
    }
    Set<UUID> expiredReads = new HashSet<>();
    for (UUID sessionId : state.activeLiveReadSessionIds) {
      LiveReadSession read = liveReadSessions.get(sessionId);
      if (read.status() == LiveReadStatus.READING && expired(read.leaseExpiresAt(), now)) {
        liveReadSessions.put(sessionId, liveReadWithStatus(read, LiveReadStatus.EXPIRED, now));
        expiredReads.add(sessionId);
      }
    }
    state.activeLiveReadSessionIds.removeAll(expiredReads);
    if (state.activeSnapshotSessionId != null) {
      SnapshotGenerationSession snapshot = snapshotSessions.get(state.activeSnapshotSessionId);
      if (snapshot.status() == SnapshotGenerationStatus.GENERATING
          && expired(snapshot.leaseExpiresAt(), now)) {
        snapshotSessions.put(
            snapshot.sessionId(),
            snapshotWithStatus(
                snapshot, SnapshotGenerationStatus.EXPIRED, snapshot.leaseExpiresAt(), now));
        state.activeSnapshotSessionId = null;
      }
    }
  }

  private static boolean expired(Instant leaseExpiresAt, Instant now) {
    return !leaseExpiresAt.isAfter(now);
  }

  private static WriteSession writeWithStatus(
      WriteSession session, WriteStatus status, Optional<String> failureReason, Instant updatedAt) {
    return new WriteSession(
        session.sessionId(),
        session.resourceId(),
        session.allocatedVersion(),
        session.baseActiveVersion(),
        status,
        session.owner(),
        session.fencingToken(),
        session.leaseExpiresAt(),
        failureReason,
        session.createdAt(),
        updatedAt);
  }

  private static LiveReadSession liveReadWithStatus(
      LiveReadSession session, LiveReadStatus status, Instant updatedAt) {
    return new LiveReadSession(
        session.sessionId(),
        session.resourceId(),
        session.boundVersion(),
        status,
        session.owner(),
        session.fencingToken(),
        session.leaseExpiresAt(),
        session.createdAt(),
        updatedAt);
  }

  private static SnapshotGenerationSession snapshotWithStatus(
      SnapshotGenerationSession session,
      SnapshotGenerationStatus status,
      Instant leaseExpiresAt,
      Instant updatedAt) {
    return new SnapshotGenerationSession(
        session.sessionId(),
        session.resourceId(),
        session.snapshotVersion(),
        status,
        session.owner(),
        session.fencingToken(),
        leaseExpiresAt,
        session.createdAt(),
        updatedAt);
  }

  private static WriterDuringSnapshotPolicy writerPolicy(ResourceState state) {
    return state.resource.policies().writerDuringSnapshotPolicy().orElseThrow();
  }

  private SnapshotGenerationSession activeSnapshotSession(ResourceState state) {
    return state.activeSnapshotSessionId == null
        ? null
        : snapshotSessions.get(state.activeSnapshotSessionId);
  }

  private static long requireActiveVersion(ResourceState state) {
    if (state.resource.activeVersion() == null) {
      throw failure(
          ErrorCode.ACTIVE_VERSION_NOT_FOUND,
          "Resource " + state.resource.resourceId() + " has no active version");
    }
    return state.resource.activeVersion();
  }

  private static long requireResolutionActiveVersion(
      ResourceState state, SnapshotSelector selector) {
    if (state.resource.activeVersion() == null) {
      ErrorCode code =
          selector == SnapshotSelector.CURRENT
              ? ErrorCode.CURRENT_SNAPSHOT_UNAVAILABLE
              : ErrorCode.SNAPSHOT_NOT_FOUND;
      throw failure(code, "Resource " + state.resource.resourceId() + " has no active version");
    }
    return state.resource.activeVersion();
  }

  private UUID nextSessionId() {
    long current = backingState.nextSessionSequence;
    backingState.nextSessionSequence = incrementSequence(current, "session identifier");
    return new UUID(0L, current);
  }

  private static long nextFencingToken(ResourceState state) {
    long current = state.nextFencingToken;
    state.nextFencingToken = incrementSequence(current, "fencing token");
    return current;
  }

  private static long incrementSequence(long current, String name) {
    try {
      return Math.addExact(current, 1);
    } catch (ArithmeticException exception) {
      throw new VersionGateException(
          ErrorCode.STORAGE_FAILURE, "The " + name + " sequence is exhausted", exception);
    }
  }

  private static void requirePositiveDuration(Duration duration) {
    Objects.requireNonNull(duration, "leaseDuration is required");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
  }

  private static void requireBeginInputs(
      String owner, Duration leaseDuration, String idempotencyKey) {
    DomainValidation.requireNonBlank(owner, "owner", DomainValidation.TEXT_MAX_LENGTH);
    requirePositiveDuration(leaseDuration);
    DomainValidation.requireIdempotencyKey(idempotencyKey);
  }

  private static void requireSameFingerprint(
      AdmissionRecord priorAdmission, AdmissionFingerprint fingerprint) {
    if (!priorAdmission.fingerprint().equals(fingerprint)) {
      throw failure(
          ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
          "The idempotency key was already used with a different owner or lease duration");
    }
  }

  private static void validateSelection(SnapshotSelector selector, OptionalLong requestedVersion) {
    if (selector == SnapshotSelector.BY_VERSION) {
      if (requestedVersion.isEmpty() || requestedVersion.getAsLong() < 0) {
        throw failure(
            ErrorCode.VALIDATION_FAILED, "BY_VERSION requires a non-negative snapshot version");
      }
    } else if (requestedVersion.isPresent()) {
      throw failure(ErrorCode.VALIDATION_FAILED, selector + " does not accept a requested version");
    }
  }

  private static StagedSnapshot stage(SnapshotUpload upload) {
    if (upload.contentLength() > Integer.MAX_VALUE) {
      throw failure(
          ErrorCode.VALIDATION_FAILED,
          "The deterministic store cannot retain a snapshot larger than 2147483647 bytes");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream((int) upload.contentLength());
    byte[] buffer = new byte[COPY_BUFFER_SIZE];
    long consumed = 0;
    try {
      while (true) {
        int read = upload.inputStream().read(buffer);
        if (read < 0) {
          break;
        }
        consumed += read;
        if (consumed > upload.contentLength()) {
          throw failure(
              ErrorCode.VALIDATION_FAILED, "Snapshot body contains more bytes than Content-Length");
        }
        output.write(buffer, 0, read);
      }
    } catch (IOException exception) {
      throw new VersionGateException(
          ErrorCode.STORAGE_FAILURE, "Snapshot staging failed", exception);
    }
    if (consumed != upload.contentLength()) {
      throw failure(ErrorCode.VALIDATION_FAILED, "Snapshot body is shorter than Content-Length");
    }
    byte[] bytes = output.toByteArray();
    String sha256 = sha256(bytes);
    if (upload.expectedSha256().isPresent()
        && !sha256.equals(upload.expectedSha256().orElseThrow())) {
      throw failure(ErrorCode.CHECKSUM_MISMATCH, "Snapshot checksum does not match");
    }
    return new StagedSnapshot(bytes, sha256, upload.contentType(), upload.contentEncoding());
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
    }
  }

  private static void requireSameSnapshot(SnapshotData existing, StagedSnapshot staged) {
    if (existing.snapshot.contentLength() != staged.bytes.length
        || !existing.snapshot.sha256().equals(staged.sha256)
        || !existing.snapshot.contentType().equals(staged.contentType)
        || !existing.snapshot.contentEncoding().equals(staged.contentEncoding)
        || !Arrays.equals(existing.bytes, staged.bytes)) {
      throw failure(
          ErrorCode.SNAPSHOT_CONFLICT,
          "A different immutable snapshot is already published for this version");
    }
  }

  private static VersionGateException invalidTransition(String operation, Object status) {
    return failure(
        ErrorCode.INVALID_SESSION_TRANSITION,
        "Cannot continue " + operation + " session in " + status);
  }

  private static VersionGateException failure(ErrorCode code, String message) {
    return new VersionGateException(code, message);
  }

  /**
   * Mutable backing state used to reconstruct the in-memory adapter without reusing its object.
   *
   * <p>The type deliberately exposes no state accessors. It is only a deterministic test analogue
   * for reopening one authoritative persistence boundary.
   */
  public static final class BackingState {

    private final Clock authoritativeClock;
    private final Object monitor = new Object();
    private final Map<String, ResourceState> resources = new HashMap<>();
    private final Map<UUID, WriteSession> writeSessions = new HashMap<>();
    private final Map<UUID, String> writeResources = new HashMap<>();
    private final Map<UUID, LiveReadSession> liveReadSessions = new HashMap<>();
    private final Map<UUID, String> liveReadResources = new HashMap<>();
    private final Map<UUID, SnapshotGenerationSession> snapshotSessions = new HashMap<>();
    private final Map<UUID, String> snapshotResources = new HashMap<>();
    private final Map<AdmissionKey, AdmissionRecord> admissions = new HashMap<>();
    private long nextSessionSequence = 1;

    /** Creates empty backing state with one authoritative clock shared by every reconstruction. */
    public BackingState(Clock authoritativeClock) {
      this.authoritativeClock =
          Objects.requireNonNull(authoritativeClock, "authoritativeClock is required");
    }
  }

  private enum BeginOperation {
    WRITE,
    LIVE_READ,
    SNAPSHOT
  }

  private record AdmissionKey(String resourceId, BeginOperation operation, String idempotencyKey) {}

  private record AdmissionFingerprint(String owner, Duration leaseDuration) {}

  private record AdmissionRecord(AdmissionFingerprint fingerprint, UUID sessionId) {}

  private static final class ResourceState {
    private Resource resource;
    private long nextVersion = 1;
    private long nextFencingToken = 1;
    private UUID activeWriteSessionId;
    private final Set<UUID> activeLiveReadSessionIds = new HashSet<>();
    private UUID activeSnapshotSessionId;
    private final NavigableMap<Long, SnapshotData> snapshots = new TreeMap<>();

    private ResourceState(Resource resource) {
      this.resource = resource;
    }
  }

  private record SnapshotData(StoredSnapshot snapshot, byte[] bytes) {}

  private record StagedSnapshot(
      byte[] bytes, String sha256, String contentType, Optional<String> contentEncoding) {}
}
