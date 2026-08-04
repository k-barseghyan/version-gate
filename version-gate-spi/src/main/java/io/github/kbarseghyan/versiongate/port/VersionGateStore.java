package io.github.kbarseghyan.versiongate.port;

import io.github.kbarseghyan.versiongate.domain.DomainValidation;
import io.github.kbarseghyan.versiongate.domain.LiveReadSession;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.ResourcePolicies;
import io.github.kbarseghyan.versiongate.domain.SnapshotGenerationSession;
import io.github.kbarseghyan.versiongate.domain.SnapshotSelector;
import io.github.kbarseghyan.versiongate.domain.StoredSnapshot;
import io.github.kbarseghyan.versiongate.domain.WriteSession;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One authoritative persistence boundary for all Version Gate coordination and immutable snapshot
 * data.
 *
 * <p>An implementation must serialize every policy, lifecycle, lease, fence, version-allocation,
 * snapshot-publication, and snapshot-resolution decision for one resource. It must obtain its
 * authoritative time after acquiring the relevant storage lock or equivalent serialization token;
 * coordinator-JVM time and application prechecks are never correctness boundaries. Expected
 * outcomes are reported as storage-neutral {@code VersionGateException} error codes, and vendor
 * types or failures never cross this port.
 *
 * <p>Snapshot bytes may be staged internally while {@link #submitSnapshot} verifies their exact
 * length, SHA-256, and representation metadata. Staged, incomplete, failed, expired, aborted, or
 * invalidated data is never visible through {@link #getSnapshot}. Publication of complete bytes and
 * terminalization of the generation session are one authoritative commit; no operation requires an
 * atomic commit across this store and another persistence system.
 *
 * <p>Snapshot submission and writer admission use the same per-resource serialization order. If
 * publication commits first, the snapshot exists before the writer is evaluated. If writer
 * admission atomically invalidates generation first, submission reports {@code
 * SNAPSHOT_INVALIDATED} and cannot publish. Active live readers reject a writer before snapshot
 * invalidation is considered.
 *
 * <p>Implementations are thread-safe and durable across coordinator process restarts. Snapshot
 * upload and download use bounded-memory streaming; callers retain upload-stream ownership and
 * close returned {@link SnapshotContent} instances.
 */
public interface VersionGateStore {

  /** Registers an immutable resource definition, failing when its ID already exists. */
  Resource registerResource(String resourceId, ResourcePolicies policies);

  /** Returns a registered resource, or empty when its ID is unknown. */
  Optional<Resource> findResource(String resourceId);

  /**
   * Atomically admits an exclusive write, allocates its never-reused coordinator version and fence,
   * and applies all resource policies.
   *
   * <p>Expired claims are classified using storage-authoritative time. A live reader rejects the
   * writer before an active snapshot session can be invalidated. With {@code INVALIDATE_SNAPSHOT},
   * invalidation and successful write admission are one commit.
   */
  WriteSession beginWrite(String resourceId, String owner, Duration leaseDuration);

  /** Returns a write session, including terminal history, or empty when unknown. */
  Optional<WriteSession> findWriteSession(UUID sessionId);

  /** Atomically renews a live write after checking its identity, fence, state, and lease. */
  WriteSession renewWrite(UUID sessionId, long fencingToken, Duration leaseDuration);

  /**
   * Atomically completes a live write, immediately activates its allocated version, and releases
   * write coordination.
   */
  WriteSession completeWrite(UUID sessionId, long fencingToken);

  /** Terminates a write as failed without activating its allocated version. */
  WriteSession failWrite(UUID sessionId, long fencingToken, String reason);

  /** Terminates a live write as abandoned without activating its allocated version. */
  WriteSession abandonWrite(UUID sessionId, long fencingToken);

  /**
   * Begins a leased, fenced live read bound by the store to the active version.
   *
   * <p>Several readers may coexist. An active write rejects admission; snapshot generation does
   * not.
   */
  LiveReadSession beginLiveRead(String resourceId, String owner, Duration leaseDuration);

  /** Returns a live-read session, including terminal history, or empty when unknown. */
  Optional<LiveReadSession> findLiveReadSession(UUID sessionId);

  /** Atomically renews a live reader after checking its identity, fence, state, and lease. */
  LiveReadSession renewLiveRead(UUID sessionId, long fencingToken, Duration leaseDuration);

  /** Successfully terminates a live read and releases its writer-blocking claim. */
  LiveReadSession completeLiveRead(UUID sessionId, long fencingToken);

  /** Abandons a live read and releases its writer-blocking claim. */
  LiveReadSession abandonLiveRead(UUID sessionId, long fencingToken);

  /**
   * Begins external snapshot generation bound by the store to the active version.
   *
   * <p>Snapshot support must be enabled, no write may be active, and no earlier generation session
   * may exist for the bound resource version. Live reads may coexist with generation.
   */
  SnapshotGenerationSession beginSnapshot(String resourceId, String owner, Duration leaseDuration);

  /** Returns a snapshot-generation session, including terminal history, or empty when unknown. */
  Optional<SnapshotGenerationSession> findSnapshotSession(UUID sessionId);

  /** Atomically renews generation after checking its identity, fence, state, and lease. */
  SnapshotGenerationSession renewSnapshot(
      UUID sessionId, long fencingToken, Duration leaseDuration);

  /** Terminates generation without publishing a snapshot. */
  SnapshotGenerationSession abortSnapshot(UUID sessionId, long fencingToken);

  /**
   * Verifies and atomically publishes one complete immutable payload for the session-bound version.
   *
   * <p>A correct-fence retry after publication consumes and verifies the supplied representation.
   * An exact replay returns the original snapshot with {@link SnapshotSubmission#replayed()} true;
   * any different bytes, length, SHA-256, content type, or content encoding reports {@code
   * SNAPSHOT_CONFLICT}. An invalidated session always reports {@code SNAPSHOT_INVALIDATED} and can
   * never publish, including on a late submission.
   */
  SnapshotSubmission submitSnapshot(
      UUID sessionId, long fencingToken, SnapshotUpload snapshotUpload);

  /**
   * Atomically resolves and opens immutable snapshot content with active-version metadata.
   *
   * <p>{@code BY_VERSION} requires {@code requestedVersion} and ignores the retrieval-during-write
   * policy. {@code CURRENT} and {@code LATEST_AVAILABLE} require it to be empty and apply the
   * resource's retrieval-during-write policy. Snapshot retrieval never acquires a live-data claim
   * or blocks another operation.
   */
  SnapshotContent getSnapshot(
      String resourceId, SnapshotSelector selector, OptionalLong requestedVersion);

  /**
   * Streaming request for one complete immutable snapshot representation.
   *
   * @param inputStream exact payload bytes; ownership remains with the caller
   * @param contentLength exact number of bytes the store must consume
   * @param contentType media type of the exact representation
   * @param contentEncoding optional content encoding of the exact representation
   * @param expectedSha256 optional expected SHA-256 of the exact bytes
   */
  record SnapshotUpload(
      InputStream inputStream,
      long contentLength,
      String contentType,
      Optional<String> contentEncoding,
      Optional<String> expectedSha256) {

    /** Creates a validated streaming snapshot request. */
    public SnapshotUpload {
      Objects.requireNonNull(inputStream, "inputStream is required");
      if (contentLength < 0) {
        throw new IllegalArgumentException("contentLength must not be negative");
      }
      DomainValidation.requireNonBlank(
          contentType, "contentType", DomainValidation.TEXT_MAX_LENGTH);
      contentEncoding = Objects.requireNonNull(contentEncoding, "contentEncoding is required");
      contentEncoding.ifPresent(
          value ->
              DomainValidation.requireNonBlank(
                  value, "contentEncoding", DomainValidation.TEXT_MAX_LENGTH));
      expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256 is required");
      expectedSha256 = expectedSha256.map(DomainValidation::requireSha256);
    }
  }

  /**
   * Result of atomic immutable snapshot publication.
   *
   * @param snapshot authoritative immutable metadata
   * @param replayed whether this call replayed an identical committed submission
   */
  record SnapshotSubmission(StoredSnapshot snapshot, boolean replayed) {

    /** Creates a validated immutable submission result. */
    public SnapshotSubmission {
      Objects.requireNonNull(snapshot, "snapshot is required");
    }
  }

  /**
   * Metadata observed atomically while resolving one stored snapshot.
   *
   * @param snapshot selected immutable snapshot metadata
   * @param activeVersion active completed version observed during selection
   * @param selector selector applied by the store
   * @param stale whether a latest-available result is older than the observed active version
   */
  record SnapshotResolution(
      StoredSnapshot snapshot, long activeVersion, SnapshotSelector selector, boolean stale) {

    /** Creates and cross-validates immutable resolution metadata. */
    public SnapshotResolution {
      Objects.requireNonNull(snapshot, "snapshot is required");
      if (activeVersion < 0) {
        throw new IllegalArgumentException("activeVersion must not be negative");
      }
      Objects.requireNonNull(selector, "selector is required");
      if (selector == SnapshotSelector.CURRENT && snapshot.snapshotVersion() != activeVersion) {
        throw new IllegalArgumentException("CURRENT snapshot must match activeVersion");
      }
      boolean expectedStale =
          selector == SnapshotSelector.LATEST_AVAILABLE
              && snapshot.snapshotVersion() < activeVersion;
      if (stale != expectedStale) {
        throw new IllegalArgumentException(
            "stale must report whether LATEST_AVAILABLE is older than activeVersion");
      }
    }
  }

  /**
   * Closeable streaming content plus the metadata observed during atomic resolution.
   *
   * @param resolution selected snapshot and active-version metadata
   * @param inputStream integrity-protected exact stored bytes
   */
  record SnapshotContent(SnapshotResolution resolution, InputStream inputStream)
      implements AutoCloseable {

    /** Creates validated streaming content. */
    public SnapshotContent {
      Objects.requireNonNull(resolution, "resolution is required");
      Objects.requireNonNull(inputStream, "inputStream is required");
    }

    /** Closes the underlying content stream. */
    @Override
    public void close() throws IOException {
      inputStream.close();
    }
  }
}
