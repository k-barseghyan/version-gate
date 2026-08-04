package io.github.kbarseghyan.versiongate.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.domain.LiveReadSession;
import io.github.kbarseghyan.versiongate.domain.MissingCurrentSnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.ResourcePolicies;
import io.github.kbarseghyan.versiongate.domain.RetrievalDuringWritePolicy;
import io.github.kbarseghyan.versiongate.domain.SnapshotGenerationSession;
import io.github.kbarseghyan.versiongate.domain.SnapshotGenerationStatus;
import io.github.kbarseghyan.versiongate.domain.SnapshotSelector;
import io.github.kbarseghyan.versiongate.domain.SnapshotSupport;
import io.github.kbarseghyan.versiongate.domain.WriteSession;
import io.github.kbarseghyan.versiongate.domain.WriteStatus;
import io.github.kbarseghyan.versiongate.domain.WriterDuringSnapshotPolicy;
import io.github.kbarseghyan.versiongate.port.VersionGateStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reusable executable contract for complete, authoritative Version Gate stores.
 *
 * <p>Concrete production adapters should subclass this suite and provide a fixture capable of
 * advancing the adapter's own authoritative test time, such as a database test mechanism.
 */
public abstract class VersionGateStoreContract {

  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final byte[] SNAPSHOT = "snapshot-v1".getBytes(StandardCharsets.UTF_8);

  private VersionGateStore store;
  private VersionGateStoreTestFixture fixture;

  /** Supplies a fresh store and authoritative-time fixture for each test. */
  protected abstract VersionGateStoreTestFixture createFixture();

  @BeforeEach
  final void setUpStoreContract() {
    fixture = createFixture();
    store = fixture.store();
  }

  @Test
  final void snapshotDisabledResourcesNeedNoSnapshotCapabilityToCoordinateWritesAndReads() {
    registerDisabled("catalog");

    WriteSession write = store.beginWrite("catalog", "writer", LEASE);
    WriteSession completed = store.completeWrite(write.sessionId(), write.fencingToken());
    LiveReadSession read = store.beginLiveRead("catalog", "reader", LEASE);

    assertEquals(WriteStatus.COMPLETED, completed.status());
    assertEquals(1, read.boundVersion());
    assertCode(
        ErrorCode.SNAPSHOT_SUPPORT_DISABLED,
        () -> store.beginSnapshot("catalog", "snapshotter", LEASE));
    assertCode(
        ErrorCode.SNAPSHOT_SUPPORT_DISABLED,
        () -> store.getSnapshot("catalog", SnapshotSelector.CURRENT, OptionalLong.empty()));
  }

  @Test
  final void firstWriteIsAdmittedAndSuccessfulCompletionImmediatelyActivatesItsVersion() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.REQUIRE_CURRENT_SNAPSHOT,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);

    WriteSession write = store.beginWrite("catalog", "writer", LEASE);
    assertEquals(1, write.allocatedVersion());
    assertEquals(null, write.baseActiveVersion());

    WriteSession completed = store.completeWrite(write.sessionId(), write.fencingToken());

    assertEquals(WriteStatus.COMPLETED, completed.status());
    assertEquals(1L, store.findResource("catalog").orElseThrow().activeVersion());
    assertEquals(completed, store.completeWrite(write.sessionId(), write.fencingToken()));
  }

  @Test
  final void failedAndAbandonedWriteVersionsAreNeverReused() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    WriteSession failed = store.beginWrite("catalog", "writer-1", LEASE);
    store.failWrite(failed.sessionId(), failed.fencingToken(), "failed");
    WriteSession abandoned = store.beginWrite("catalog", "writer-2", LEASE);
    store.abandonWrite(abandoned.sessionId(), abandoned.fencingToken());
    WriteSession successful = store.beginWrite("catalog", "writer-3", LEASE);
    store.completeWrite(successful.sessionId(), successful.fencingToken());

    assertEquals(1, failed.allocatedVersion());
    assertEquals(2, abandoned.allocatedVersion());
    assertEquals(3, successful.allocatedVersion());
    assertEquals(3L, store.findResource("catalog").orElseThrow().activeVersion());
  }

  @Test
  final void competingWritesCannotBothBeAdmitted() {
    registerDisabled("catalog");
    store.beginWrite("catalog", "writer-1", LEASE);
    assertCode(
        ErrorCode.WRITE_ALREADY_ACTIVE, () -> store.beginWrite("catalog", "writer-2", LEASE));
  }

  @Test
  final void simultaneousCompetingWritesHaveExactlyOneWinner() throws Exception {
    registerDisabled("catalog");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Object> first = executor.submit(() -> beginWriteAfter(start, "writer-1"));
      Future<Object> second = executor.submit(() -> beginWriteAfter(start, "writer-2"));
      start.countDown();

      Object firstOutcome = get(first);
      Object secondOutcome = get(second);
      assertTrue(
          firstOutcome instanceof WriteSession || secondOutcome instanceof WriteSession,
          "one writer must be admitted");
      assertTrue(
          firstOutcome == ErrorCode.WRITE_ALREADY_ACTIVE
              || secondOutcome == ErrorCode.WRITE_ALREADY_ACTIVE,
          "one writer must be rejected");
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  final void multipleLiveReadsCoexistAndRejectAnArrivingWriter() {
    registerDisabled("catalog");
    activate("catalog");

    LiveReadSession first = store.beginLiveRead("catalog", "reader-1", LEASE);
    LiveReadSession second = store.beginLiveRead("catalog", "reader-2", LEASE);
    assertEquals(first.boundVersion(), second.boundVersion());
    assertNotEquals(first.sessionId(), second.sessionId());
    assertCode(ErrorCode.LIVE_READ_ACTIVE, () -> store.beginWrite("catalog", "writer", LEASE));

    store.completeLiveRead(first.sessionId(), first.fencingToken());
    assertCode(ErrorCode.LIVE_READ_ACTIVE, () -> store.beginWrite("catalog", "writer", LEASE));
    store.completeLiveRead(second.sessionId(), second.fencingToken());
    assertNotNull(store.beginWrite("catalog", "writer", LEASE));
  }

  @Test
  final void activeWritesRejectNewLiveReadsAndSnapshotGeneration() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    store.beginWrite("catalog", "writer", LEASE);

    assertCode(ErrorCode.WRITE_IN_PROGRESS, () -> store.beginLiveRead("catalog", "reader", LEASE));
    assertCode(
        ErrorCode.WRITE_IN_PROGRESS, () -> store.beginSnapshot("catalog", "snapshotter", LEASE));
  }

  @Test
  final void snapshotGenerationCoexistsWithLiveReads() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");

    LiveReadSession read = store.beginLiveRead("catalog", "reader", LEASE);
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);

    assertEquals(read.boundVersion(), snapshot.snapshotVersion());
  }

  @Test
  final void blockWriterPolicyRejectsWriterWithoutInvalidatingSnapshot() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);

    assertCode(
        ErrorCode.SNAPSHOT_GENERATION_ACTIVE, () -> store.beginWrite("catalog", "writer", LEASE));
    assertEquals(
        SnapshotGenerationStatus.GENERATING,
        store.findSnapshotSession(snapshot.sessionId()).orElseThrow().status());
  }

  @Test
  final void invalidatePolicyAtomicallyInvalidatesSnapshotAndAdmitsWriter() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);

    WriteSession write = store.beginWrite("catalog", "writer", LEASE);

    assertEquals(2, write.allocatedVersion());
    assertEquals(
        SnapshotGenerationStatus.INVALIDATED,
        store.findSnapshotSession(snapshot.sessionId()).orElseThrow().status());
    assertCode(ErrorCode.SNAPSHOT_INVALIDATED, () -> submit(snapshot, SNAPSHOT));
  }

  @Test
  final void rejectedWriteConstructionCannotPartiallyInvalidateSnapshotGeneration() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);

    assertThrows(IllegalArgumentException.class, () -> store.beginWrite("catalog", " ", LEASE));

    assertEquals(
        SnapshotGenerationStatus.GENERATING,
        store.findSnapshotSession(snapshot.sessionId()).orElseThrow().status());
    assertFalse(submit(snapshot, SNAPSHOT).replayed());
  }

  @Test
  final void liveReadPrecedencePreventsSnapshotInvalidation() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);
    store.beginLiveRead("catalog", "reader", LEASE);

    assertCode(ErrorCode.LIVE_READ_ACTIVE, () -> store.beginWrite("catalog", "writer", LEASE));
    assertEquals(
        SnapshotGenerationStatus.GENERATING,
        store.findSnapshotSession(snapshot.sessionId()).orElseThrow().status());
  }

  @Test
  final void missingCurrentSnapshotPolicyAllowsGapOrRequiresCurrentSnapshotAfterBootstrap() {
    registerEnabled(
        "allow",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("allow");
    assertNotNull(store.beginWrite("allow", "writer-2", LEASE));

    registerEnabled(
        "require",
        MissingCurrentSnapshotPolicy.REQUIRE_CURRENT_SNAPSHOT,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("require");
    assertCode(
        ErrorCode.CURRENT_SNAPSHOT_REQUIRED, () -> store.beginWrite("require", "writer-2", LEASE));
    SnapshotGenerationSession snapshot = store.beginSnapshot("require", "snapshotter", LEASE);
    submit(snapshot, SNAPSHOT);
    assertNotNull(store.beginWrite("require", "writer-2", LEASE));
  }

  @Test
  final void snapshotSessionIsStoreBoundToActiveVersionAndUniqueForThatVersion() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);

    assertEquals(1, snapshot.snapshotVersion());
    store.abortSnapshot(snapshot.sessionId(), snapshot.fencingToken());
    assertCode(
        ErrorCode.SNAPSHOT_SESSION_ALREADY_EXISTS,
        () -> store.beginSnapshot("catalog", "snapshotter-2", LEASE));
  }

  @Test
  final void immutableSnapshotReplayAndConflictCoverEveryRepresentationField() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);

    VersionGateStore.SnapshotSubmission first = submit(snapshot, SNAPSHOT);
    WriteSession nextWrite = store.beginWrite("catalog", "writer-2", LEASE);
    store.completeWrite(nextWrite.sessionId(), nextWrite.fencingToken());
    fixture.advanceAuthoritativeTime(LEASE.plusSeconds(1));
    VersionGateStore.SnapshotSubmission replay = submit(snapshot, SNAPSHOT);

    assertFalse(first.replayed());
    assertTrue(replay.replayed());
    assertEquals(first.snapshot(), replay.snapshot());
    assertCode(
        ErrorCode.SNAPSHOT_CONFLICT,
        () -> submit(snapshot, "different".getBytes(StandardCharsets.UTF_8)));
    assertCode(
        ErrorCode.SNAPSHOT_CONFLICT,
        () -> submit(snapshot, SNAPSHOT, "application/json", Optional.empty()));
    assertCode(
        ErrorCode.SNAPSHOT_CONFLICT,
        () -> submit(snapshot, SNAPSHOT, "application/octet-stream", Optional.of("gzip")));
  }

  @Test
  final void byVersionIgnoresWritePolicyWhileCurrentAndLatestCanRejectDuringWrite()
      throws IOException {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.REJECT_IF_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);
    submit(snapshot, SNAPSHOT);
    store.beginWrite("catalog", "writer-2", LEASE);

    try (VersionGateStore.SnapshotContent content =
        store.getSnapshot("catalog", SnapshotSelector.BY_VERSION, OptionalLong.of(1))) {
      assertArrayEquals(SNAPSHOT, content.inputStream().readAllBytes());
      assertEquals(1, content.resolution().snapshot().snapshotVersion());
      assertEquals(1, content.resolution().activeVersion());
    }
    assertCode(
        ErrorCode.WRITE_IN_PROGRESS,
        () -> store.getSnapshot("catalog", SnapshotSelector.CURRENT, OptionalLong.empty()));
    assertCode(
        ErrorCode.WRITE_IN_PROGRESS,
        () ->
            store.getSnapshot("catalog", SnapshotSelector.LATEST_AVAILABLE, OptionalLong.empty()));
  }

  @Test
  final void currentRequiresExactActiveSnapshotAndLatestReportsExplicitGapStaleness()
      throws IOException {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);
    submit(snapshot, SNAPSHOT);

    try (VersionGateStore.SnapshotContent current =
        store.getSnapshot("catalog", SnapshotSelector.CURRENT, OptionalLong.empty())) {
      assertFalse(current.resolution().stale());
      assertEquals(1, current.resolution().activeVersion());
    }

    WriteSession second = store.beginWrite("catalog", "writer-2", LEASE);
    store.completeWrite(second.sessionId(), second.fencingToken());
    assertCode(
        ErrorCode.CURRENT_SNAPSHOT_UNAVAILABLE,
        () -> store.getSnapshot("catalog", SnapshotSelector.CURRENT, OptionalLong.empty()));
    try (VersionGateStore.SnapshotContent latest =
        store.getSnapshot("catalog", SnapshotSelector.LATEST_AVAILABLE, OptionalLong.empty())) {
      assertEquals(1, latest.resolution().snapshot().snapshotVersion());
      assertEquals(2, latest.resolution().activeVersion());
      assertTrue(latest.resolution().stale());
    }
  }

  @Test
  final void allowWhileWritingResolvesCurrentAndLatestAgainstPreviouslyActiveVersion()
      throws IOException {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = store.beginSnapshot("catalog", "snapshotter", LEASE);
    submit(snapshot, SNAPSHOT);
    store.beginWrite("catalog", "writer-2", LEASE);

    try (VersionGateStore.SnapshotContent current =
            store.getSnapshot("catalog", SnapshotSelector.CURRENT, OptionalLong.empty());
        VersionGateStore.SnapshotContent latest =
            store.getSnapshot("catalog", SnapshotSelector.LATEST_AVAILABLE, OptionalLong.empty())) {
      assertEquals(1, current.resolution().snapshot().snapshotVersion());
      assertEquals(1, latest.resolution().snapshot().snapshotVersion());
      assertFalse(latest.resolution().stale());
    }
  }

  @Test
  final void authoritativeLeaseExpiryReleasesCoordinationButRejectsExpiredOwnerAndStaleFence() {
    registerDisabled("catalog");
    WriteSession first = store.beginWrite("catalog", "writer-1", Duration.ofSeconds(5));
    assertCode(
        ErrorCode.STALE_FENCING_TOKEN,
        () -> store.completeWrite(first.sessionId(), first.fencingToken() + 1));

    fixture.advanceAuthoritativeTime(Duration.ofSeconds(5));

    assertCode(
        ErrorCode.STALE_FENCING_TOKEN,
        () -> store.completeWrite(first.sessionId(), first.fencingToken() + 1));
    assertCode(
        ErrorCode.LEASE_EXPIRED,
        () -> store.completeWrite(first.sessionId(), first.fencingToken()));
    WriteSession second = store.beginWrite("catalog", "writer-2", LEASE);
    assertEquals(2, second.allocatedVersion());
    assertTrue(second.fencingToken() > first.fencingToken());
  }

  @Test
  final void expiredLiveReadNoLongerBlocksWriterAndExpiredSnapshotCannotPublish() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    LiveReadSession read = store.beginLiveRead("catalog", "reader", Duration.ofSeconds(5));
    SnapshotGenerationSession snapshot =
        store.beginSnapshot("catalog", "snapshotter", Duration.ofSeconds(5));
    fixture.advanceAuthoritativeTime(Duration.ofSeconds(5));

    assertCode(
        ErrorCode.LEASE_EXPIRED,
        () -> store.completeLiveRead(read.sessionId(), read.fencingToken()));
    assertNotNull(store.beginWrite("catalog", "writer", LEASE));
    assertCode(ErrorCode.LEASE_EXPIRED, () -> submit(snapshot, SNAPSHOT));
  }

  @Test
  final void concurrentSnapshotFinalizationAndWriterAdmissionHaveOnlySerializedOutcomes()
      throws Exception {
    String resourceId = "race";
    registerEnabled(
        resourceId,
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate(resourceId);
    SnapshotGenerationSession snapshot = store.beginSnapshot(resourceId, "snapshotter", LEASE);
    CountDownLatch uploadReadStarted = new CountDownLatch(1);
    CountDownLatch releaseUpload = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    InputStream overlappingUpload =
        new CoordinatedInputStream(SNAPSHOT, uploadReadStarted, releaseUpload);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> submission =
          executor.submit(
              () -> {
                try {
                  return submit(snapshot, overlappingUpload, SNAPSHOT.length);
                } catch (VersionGateException exception) {
                  return exception.code();
                }
              });
      assertTrue(uploadReadStarted.await(5, TimeUnit.SECONDS));
      Future<WriteSession> writer =
          executor.submit(
              () -> {
                writerStarted.countDown();
                return store.beginWrite(resourceId, "writer", LEASE);
              });
      assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
      releaseUpload.countDown();

      assertEquals(2, get(writer).allocatedVersion());
      Object snapshotOutcome = get(submission);
      if (snapshotOutcome instanceof VersionGateStore.SnapshotSubmission result) {
        assertFalse(result.replayed());
        try (VersionGateStore.SnapshotContent content =
            store.getSnapshot(resourceId, SnapshotSelector.BY_VERSION, OptionalLong.of(1))) {
          assertArrayEquals(SNAPSHOT, content.inputStream().readAllBytes());
        }
      } else {
        assertEquals(ErrorCode.SNAPSHOT_INVALIDATED, snapshotOutcome);
        assertCode(
            ErrorCode.SNAPSHOT_NOT_FOUND,
            () -> store.getSnapshot(resourceId, SnapshotSelector.BY_VERSION, OptionalLong.of(1)));
      }
    } finally {
      releaseUpload.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private void registerDisabled(String resourceId) {
    store.registerResource(
        resourceId,
        new ResourcePolicies(
            SnapshotSupport.DISABLED,
            MissingCurrentSnapshotPolicy.ALLOW_GAP,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
  }

  private void registerEnabled(
      String resourceId,
      MissingCurrentSnapshotPolicy missing,
      WriterDuringSnapshotPolicy writer,
      RetrievalDuringWritePolicy retrieval) {
    store.registerResource(
        resourceId,
        new ResourcePolicies(
            SnapshotSupport.ENABLED,
            missing,
            Optional.of(writer),
            Optional.of(SnapshotSelector.LATEST_AVAILABLE),
            Optional.of(retrieval)));
  }

  private WriteSession activate(String resourceId) {
    WriteSession write = store.beginWrite(resourceId, "writer", LEASE);
    return store.completeWrite(write.sessionId(), write.fencingToken());
  }

  private VersionGateStore.SnapshotSubmission submit(
      SnapshotGenerationSession session, byte[] bytes) {
    return submit(session, bytes, "application/octet-stream", Optional.empty());
  }

  private VersionGateStore.SnapshotSubmission submit(
      SnapshotGenerationSession session,
      byte[] bytes,
      String contentType,
      Optional<String> contentEncoding) {
    return store.submitSnapshot(
        session.sessionId(),
        session.fencingToken(),
        new VersionGateStore.SnapshotUpload(
            new ByteArrayInputStream(bytes),
            bytes.length,
            contentType,
            contentEncoding,
            Optional.empty()));
  }

  private VersionGateStore.SnapshotSubmission submit(
      SnapshotGenerationSession session, InputStream inputStream, long contentLength) {
    return store.submitSnapshot(
        session.sessionId(),
        session.fencingToken(),
        new VersionGateStore.SnapshotUpload(
            inputStream,
            contentLength,
            "application/octet-stream",
            Optional.empty(),
            Optional.empty()));
  }

  private Object beginWriteAfter(CountDownLatch start, String owner) throws InterruptedException {
    start.await();
    try {
      return store.beginWrite("catalog", owner, LEASE);
    } catch (VersionGateException exception) {
      return exception.code();
    }
  }

  private static void assertCode(ErrorCode expected, ThrowingOperation operation) {
    VersionGateException exception = assertThrows(VersionGateException.class, operation::run);
    assertEquals(expected, exception.code());
  }

  private static <T> T get(Future<T> future) throws Exception {
    try {
      return future.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Exception checked) {
        throw checked;
      }
      throw exception;
    }
  }

  @FunctionalInterface
  private interface ThrowingOperation {
    void run() throws Exception;
  }

  private static final class CoordinatedInputStream extends InputStream {
    private final ByteArrayInputStream delegate;
    private final CountDownLatch readStarted;
    private final CountDownLatch releaseRead;
    private boolean coordinated;

    private CoordinatedInputStream(
        byte[] bytes, CountDownLatch readStarted, CountDownLatch releaseRead) {
      delegate = new ByteArrayInputStream(bytes);
      this.readStarted = readStarted;
      this.releaseRead = releaseRead;
    }

    @Override
    public int read() throws IOException {
      coordinate();
      return delegate.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      coordinate();
      return delegate.read(bytes, offset, length);
    }

    private void coordinate() throws IOException {
      if (coordinated) {
        return;
      }
      coordinated = true;
      readStarted.countDown();
      try {
        if (!releaseRead.await(5, TimeUnit.SECONDS)) {
          throw new IOException("Timed out waiting to release the coordinated upload");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while coordinating the upload", exception);
      }
    }
  }
}
