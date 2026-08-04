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
import io.github.kbarseghyan.versiongate.domain.LiveReadStatus;
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
import java.util.concurrent.atomic.AtomicLong;
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
  private final AtomicLong nextIdempotencyKey = new AtomicLong();

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

    WriteSession write = beginWrite("catalog", "writer", LEASE);
    WriteSession completed = store.completeWrite(write.sessionId(), write.fencingToken());
    LiveReadSession read = beginLiveRead("catalog", "reader", LEASE);

    assertEquals(WriteStatus.COMPLETED, completed.status());
    assertEquals(1, read.boundVersion());
    assertCode(
        ErrorCode.SNAPSHOT_SUPPORT_DISABLED, () -> beginSnapshot("catalog", "snapshotter", LEASE));
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

    WriteSession write = beginWrite("catalog", "writer", LEASE);
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
    WriteSession failed = beginWrite("catalog", "writer-1", LEASE);
    store.failWrite(failed.sessionId(), failed.fencingToken(), "failed");
    WriteSession abandoned = beginWrite("catalog", "writer-2", LEASE);
    store.abandonWrite(abandoned.sessionId(), abandoned.fencingToken());
    WriteSession successful = beginWrite("catalog", "writer-3", LEASE);
    store.completeWrite(successful.sessionId(), successful.fencingToken());

    assertEquals(1, failed.allocatedVersion());
    assertEquals(2, abandoned.allocatedVersion());
    assertEquals(3, successful.allocatedVersion());
    assertEquals(3L, store.findResource("catalog").orElseThrow().activeVersion());
  }

  @Test
  final void competingWritesCannotBothBeAdmitted() {
    registerDisabled("catalog");
    beginWrite("catalog", "writer-1", LEASE);
    assertCode(ErrorCode.WRITE_ALREADY_ACTIVE, () -> beginWrite("catalog", "writer-2", LEASE));
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
  final void beginAdmissionsReplayTheSameSessionInItsCurrentDurableState() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);

    VersionGateStore.SessionAdmission<WriteSession> firstWrite =
        store.beginWrite("catalog", "writer", LEASE, "write-request");
    VersionGateStore.SessionAdmission<WriteSession> writeReplay =
        store.beginWrite("catalog", "writer", LEASE, "write-request");
    assertFalse(firstWrite.replayed());
    assertTrue(writeReplay.replayed());
    assertEquals(firstWrite.session(), writeReplay.session());
    store.completeWrite(firstWrite.session().sessionId(), firstWrite.session().fencingToken());
    assertEquals(
        WriteStatus.COMPLETED,
        store.beginWrite("catalog", "writer", LEASE, "write-request").session().status());

    VersionGateStore.SessionAdmission<LiveReadSession> firstRead =
        store.beginLiveRead("catalog", "reader", LEASE, "read-request");
    VersionGateStore.SessionAdmission<LiveReadSession> readReplay =
        store.beginLiveRead("catalog", "reader", LEASE, "read-request");
    assertFalse(firstRead.replayed());
    assertTrue(readReplay.replayed());
    assertEquals(firstRead.session(), readReplay.session());
    store.completeLiveRead(firstRead.session().sessionId(), firstRead.session().fencingToken());
    assertEquals(
        LiveReadStatus.COMPLETED,
        store.beginLiveRead("catalog", "reader", LEASE, "read-request").session().status());

    VersionGateStore.SessionAdmission<SnapshotGenerationSession> firstSnapshot =
        store.beginSnapshot("catalog", "snapshotter", LEASE, "snapshot-request");
    VersionGateStore.SessionAdmission<SnapshotGenerationSession> snapshotReplay =
        store.beginSnapshot("catalog", "snapshotter", LEASE, "snapshot-request");
    assertFalse(firstSnapshot.replayed());
    assertTrue(snapshotReplay.replayed());
    assertEquals(firstSnapshot.session(), snapshotReplay.session());
    store.abortSnapshot(
        firstSnapshot.session().sessionId(), firstSnapshot.session().fencingToken());
    assertEquals(
        SnapshotGenerationStatus.ABORTED,
        store
            .beginSnapshot("catalog", "snapshotter", LEASE, "snapshot-request")
            .session()
            .status());
    VersionGateStore.SessionAdmission<SnapshotGenerationSession> publishedSnapshot =
        store.beginSnapshot("catalog", "snapshotter", LEASE, "snapshot-publish-request");
    submit(publishedSnapshot.session(), SNAPSHOT);
    assertEquals(
        SnapshotGenerationStatus.PUBLISHED,
        store
            .beginSnapshot("catalog", "snapshotter", LEASE, "snapshot-publish-request")
            .session()
            .status());
  }

  @Test
  final void admissionReplayDoesNotExtendDeadlinesAndReportsAuthoritativeExpiryForEveryFlow() {
    registerDisabled("writes");
    registerDisabled("reads");
    registerEnabled(
        "snapshots",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("reads");
    activate("snapshots");
    Duration shortLease = Duration.ofSeconds(5);
    VersionGateStore.SessionAdmission<WriteSession> firstWrite =
        store.beginWrite("writes", "writer", shortLease, "write-request");
    VersionGateStore.SessionAdmission<LiveReadSession> firstRead =
        store.beginLiveRead("reads", "reader", shortLease, "read-request");
    VersionGateStore.SessionAdmission<SnapshotGenerationSession> firstSnapshot =
        store.beginSnapshot("snapshots", "snapshotter", shortLease, "snapshot-request");
    fixture.advanceAuthoritativeTime(Duration.ofSeconds(4));

    VersionGateStore.SessionAdmission<WriteSession> writeReplay =
        store.beginWrite("writes", "writer", shortLease, "write-request");
    VersionGateStore.SessionAdmission<LiveReadSession> readReplay =
        store.beginLiveRead("reads", "reader", shortLease, "read-request");
    VersionGateStore.SessionAdmission<SnapshotGenerationSession> snapshotReplay =
        store.beginSnapshot("snapshots", "snapshotter", shortLease, "snapshot-request");
    assertTrue(writeReplay.replayed());
    assertTrue(readReplay.replayed());
    assertTrue(snapshotReplay.replayed());
    assertEquals(firstWrite.session().leaseExpiresAt(), writeReplay.session().leaseExpiresAt());
    assertEquals(firstRead.session().leaseExpiresAt(), readReplay.session().leaseExpiresAt());
    assertEquals(
        firstSnapshot.session().leaseExpiresAt(), snapshotReplay.session().leaseExpiresAt());

    fixture.advanceAuthoritativeTime(Duration.ofSeconds(1));
    VersionGateStore.SessionAdmission<WriteSession> expiredWrite =
        store.beginWrite("writes", "writer", shortLease, "write-request");
    VersionGateStore.SessionAdmission<LiveReadSession> expiredRead =
        store.beginLiveRead("reads", "reader", shortLease, "read-request");
    VersionGateStore.SessionAdmission<SnapshotGenerationSession> expiredSnapshot =
        store.beginSnapshot("snapshots", "snapshotter", shortLease, "snapshot-request");
    assertTrue(expiredWrite.replayed());
    assertTrue(expiredRead.replayed());
    assertTrue(expiredSnapshot.replayed());
    assertEquals(WriteStatus.EXPIRED, expiredWrite.session().status());
    assertEquals(LiveReadStatus.EXPIRED, expiredRead.session().status());
    assertEquals(SnapshotGenerationStatus.EXPIRED, expiredSnapshot.session().status());
    assertEquals(firstWrite.session().sessionId(), expiredWrite.session().sessionId());
    assertEquals(firstRead.session().sessionId(), expiredRead.session().sessionId());
    assertEquals(firstSnapshot.session().sessionId(), expiredSnapshot.session().sessionId());
  }

  @Test
  final void liveReadAndSnapshotFingerprintsAndOperationScopesAreDeterministic() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");

    VersionGateStore.SessionAdmission<WriteSession> write =
        store.beginWrite("catalog", "writer", LEASE, "shared-operation-key");
    store.completeWrite(write.session().sessionId(), write.session().fencingToken());
    VersionGateStore.SessionAdmission<LiveReadSession> read =
        store.beginLiveRead("catalog", "reader", LEASE, "shared-operation-key");
    VersionGateStore.SessionAdmission<SnapshotGenerationSession> snapshot =
        store.beginSnapshot("catalog", "snapshotter", LEASE, "shared-operation-key");

    assertFalse(write.replayed());
    assertFalse(read.replayed());
    assertFalse(snapshot.replayed());
    assertCode(
        ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        () -> store.beginLiveRead("catalog", "different-reader", LEASE, "shared-operation-key"));
    assertCode(
        ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        () ->
            store.beginLiveRead("catalog", "reader", LEASE.plusSeconds(1), "shared-operation-key"));
    assertCode(
        ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        () ->
            store.beginSnapshot("catalog", "different-snapshotter", LEASE, "shared-operation-key"));
    assertCode(
        ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        () ->
            store.beginSnapshot(
                "catalog", "snapshotter", LEASE.plusSeconds(1), "shared-operation-key"));
  }

  @Test
  final void failedLiveReadAndSnapshotAdmissionsDoNotReserveKeys() {
    registerDisabled("reads");
    activate("reads");
    WriteSession readBlocker = beginWrite("reads", "writer", LEASE);
    assertCode(
        ErrorCode.WRITE_IN_PROGRESS,
        () -> store.beginLiveRead("reads", "reader", LEASE, "read-after-write"));
    store.completeWrite(readBlocker.sessionId(), readBlocker.fencingToken());
    assertFalse(store.beginLiveRead("reads", "reader", LEASE, "read-after-write").replayed());

    registerEnabled(
        "snapshots",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("snapshots");
    WriteSession snapshotBlocker = beginWrite("snapshots", "writer", LEASE);
    assertCode(
        ErrorCode.WRITE_IN_PROGRESS,
        () -> store.beginSnapshot("snapshots", "snapshotter", LEASE, "snapshot-after-write"));
    store.failWrite(snapshotBlocker.sessionId(), snapshotBlocker.fencingToken(), "failed");
    assertFalse(
        store.beginSnapshot("snapshots", "snapshotter", LEASE, "snapshot-after-write").replayed());
  }

  @Test
  final void idempotencyFingerprintScopeAndFailedAdmissionAreDeterministic() {
    registerDisabled("catalog");
    registerDisabled("orders");
    VersionGateStore.SessionAdmission<WriteSession> first =
        store.beginWrite("catalog", "writer", LEASE, "shared-key");

    assertCode(
        ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        () -> store.beginWrite("catalog", "different-owner", LEASE, "shared-key"));
    assertCode(
        ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        () -> store.beginWrite("catalog", "writer", LEASE.plusSeconds(1), "shared-key"));

    assertFalse(store.beginWrite("orders", "writer", LEASE, "shared-key").replayed());
    store.completeWrite(first.session().sessionId(), first.session().fencingToken());
    assertFalse(store.beginLiveRead("catalog", "reader", LEASE, "shared-key").replayed());

    assertCode(
        ErrorCode.LIVE_READ_ACTIVE,
        () -> store.beginWrite("catalog", "later-writer", LEASE, "unreserved-key"));
    LiveReadSession read =
        store.beginLiveRead("catalog", "reader-2", LEASE, "second-read-request").session();
    store.completeLiveRead(read.sessionId(), read.fencingToken());
    LiveReadSession firstRead =
        store.beginLiveRead("catalog", "reader", LEASE, "shared-key").session();
    store.completeLiveRead(firstRead.sessionId(), firstRead.fencingToken());
    assertFalse(store.beginWrite("catalog", "later-writer", LEASE, "unreserved-key").replayed());
  }

  @Test
  final void concurrentIdenticalAdmissionsCreateOneSessionAndOneReplay() throws Exception {
    registerDisabled("catalog");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<VersionGateStore.SessionAdmission<WriteSession>> first =
          executor.submit(
              () -> {
                start.await();
                return store.beginWrite("catalog", "writer", LEASE, "same-request");
              });
      Future<VersionGateStore.SessionAdmission<WriteSession>> second =
          executor.submit(
              () -> {
                start.await();
                return store.beginWrite("catalog", "writer", LEASE, "same-request");
              });
      start.countDown();

      VersionGateStore.SessionAdmission<WriteSession> firstOutcome = get(first);
      VersionGateStore.SessionAdmission<WriteSession> secondOutcome = get(second);
      assertEquals(firstOutcome.session().sessionId(), secondOutcome.session().sessionId());
      assertNotEquals(firstOutcome.replayed(), secondOutcome.replayed());
      assertEquals(1, firstOutcome.session().allocatedVersion());
      assertEquals(1, secondOutcome.session().allocatedVersion());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  final void concurrentDifferentFingerprintsCreateOneSessionAndOneKeyConflict() throws Exception {
    registerDisabled("catalog");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Object> first =
          executor.submit(
              () ->
                  captureAfter(
                      start, () -> store.beginWrite("catalog", "writer-1", LEASE, "same-key")));
      Future<Object> second =
          executor.submit(
              () ->
                  captureAfter(
                      start, () -> store.beginWrite("catalog", "writer-2", LEASE, "same-key")));
      start.countDown();

      Object firstOutcome = get(first);
      Object secondOutcome = get(second);
      assertTrue(
          firstOutcome instanceof VersionGateStore.SessionAdmission<?>
              || secondOutcome instanceof VersionGateStore.SessionAdmission<?>,
          "one fingerprint must create the session");
      assertTrue(
          firstOutcome == ErrorCode.IDEMPOTENCY_KEY_CONFLICT
              || secondOutcome == ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
          "the other fingerprint must conflict with the committed key");
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  final void liveReadAndSnapshotIdempotencySerializesConcurrentSameKeyRequests() throws Exception {
    registerDisabled("read-replay");
    registerDisabled("read-conflict");
    activate("read-replay");
    activate("read-conflict");
    registerEnabled(
        "snapshot-replay",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    registerEnabled(
        "snapshot-conflict",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("snapshot-replay");
    activate("snapshot-conflict");

    assertConcurrentReplay(
        () -> store.beginLiveRead("read-replay", "reader", LEASE, "same-read-request"));
    assertConcurrentKeyConflict(
        () -> store.beginLiveRead("read-conflict", "reader-1", LEASE, "same-read-key"),
        () -> store.beginLiveRead("read-conflict", "reader-2", LEASE, "same-read-key"));
    assertConcurrentReplay(
        () ->
            store.beginSnapshot("snapshot-replay", "snapshotter", LEASE, "same-snapshot-request"));
    assertConcurrentKeyConflict(
        () -> store.beginSnapshot("snapshot-conflict", "snapshotter-1", LEASE, "same-snapshot-key"),
        () ->
            store.beginSnapshot("snapshot-conflict", "snapshotter-2", LEASE, "same-snapshot-key"));
  }

  @Test
  final void replayedWriteAdmissionNeverRepeatsSnapshotInvalidation() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession firstSnapshot = beginSnapshot("catalog", "snapshotter-1", LEASE);
    VersionGateStore.SessionAdmission<WriteSession> write =
        store.beginWrite("catalog", "writer", LEASE, "invalidate-once");
    store.failWrite(write.session().sessionId(), write.session().fencingToken(), "failed");
    SnapshotGenerationSession retrySnapshot = beginSnapshot("catalog", "snapshotter-2", LEASE);

    VersionGateStore.SessionAdmission<WriteSession> replay =
        store.beginWrite("catalog", "writer", LEASE, "invalidate-once");

    assertTrue(replay.replayed());
    assertEquals(WriteStatus.FAILED, replay.session().status());
    assertEquals(
        SnapshotGenerationStatus.INVALIDATED,
        store.findSnapshotSession(firstSnapshot.sessionId()).orElseThrow().status());
    assertEquals(
        SnapshotGenerationStatus.GENERATING,
        store.findSnapshotSession(retrySnapshot.sessionId()).orElseThrow().status());
  }

  @Test
  final void simultaneousWriteAndLiveReadAdmissionSerializeWithExactlyOneWinner() throws Exception {
    registerDisabled("catalog");
    activate("catalog");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Object> write =
          executor.submit(() -> captureAfter(start, () -> beginWrite("catalog", "writer", LEASE)));
      Future<Object> read =
          executor.submit(
              () -> captureAfter(start, () -> beginLiveRead("catalog", "reader", LEASE)));
      start.countDown();

      Object writeOutcome = get(write);
      Object readOutcome = get(read);
      if (writeOutcome instanceof WriteSession) {
        assertEquals(ErrorCode.WRITE_IN_PROGRESS, readOutcome);
      } else {
        assertEquals(ErrorCode.LIVE_READ_ACTIVE, writeOutcome);
        assertTrue(readOutcome instanceof LiveReadSession, "the live read must be admitted");
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  final void simultaneousWriteAndBlockingSnapshotAdmissionSerializeWithExactlyOneWinner()
      throws Exception {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Object> write =
          executor.submit(() -> captureAfter(start, () -> beginWrite("catalog", "writer", LEASE)));
      Future<Object> snapshot =
          executor.submit(
              () -> captureAfter(start, () -> beginSnapshot("catalog", "snapshotter", LEASE)));
      start.countDown();

      Object writeOutcome = get(write);
      Object snapshotOutcome = get(snapshot);
      if (writeOutcome instanceof WriteSession) {
        assertEquals(ErrorCode.WRITE_IN_PROGRESS, snapshotOutcome);
      } else {
        assertEquals(ErrorCode.SNAPSHOT_GENERATION_ACTIVE, writeOutcome);
        assertTrue(
            snapshotOutcome instanceof SnapshotGenerationSession,
            "snapshot generation must be admitted");
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  final void simultaneousSnapshotAdmissionsHaveExactlyOneWinner() throws Exception {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Object> first =
          executor.submit(
              () -> captureAfter(start, () -> beginSnapshot("catalog", "snapshotter-1", LEASE)));
      Future<Object> second =
          executor.submit(
              () -> captureAfter(start, () -> beginSnapshot("catalog", "snapshotter-2", LEASE)));
      start.countDown();

      Object firstOutcome = get(first);
      Object secondOutcome = get(second);
      assertTrue(
          firstOutcome instanceof SnapshotGenerationSession
              || secondOutcome instanceof SnapshotGenerationSession,
          "one snapshot generation must be admitted");
      assertTrue(
          firstOutcome == ErrorCode.SNAPSHOT_SESSION_ALREADY_EXISTS
              || secondOutcome == ErrorCode.SNAPSHOT_SESSION_ALREADY_EXISTS,
          "one snapshot generation must be rejected");
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  final void multipleLiveReadsCoexistAndRejectAnArrivingWriter() {
    registerDisabled("catalog");
    activate("catalog");

    LiveReadSession first = beginLiveRead("catalog", "reader-1", LEASE);
    LiveReadSession second = beginLiveRead("catalog", "reader-2", LEASE);
    assertEquals(first.boundVersion(), second.boundVersion());
    assertNotEquals(first.sessionId(), second.sessionId());
    assertCode(ErrorCode.LIVE_READ_ACTIVE, () -> beginWrite("catalog", "writer", LEASE));

    store.completeLiveRead(first.sessionId(), first.fencingToken());
    assertCode(ErrorCode.LIVE_READ_ACTIVE, () -> beginWrite("catalog", "writer", LEASE));
    store.completeLiveRead(second.sessionId(), second.fencingToken());
    assertNotNull(beginWrite("catalog", "writer", LEASE));
  }

  @Test
  final void activeWritesRejectNewLiveReadsAndSnapshotGeneration() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    beginWrite("catalog", "writer", LEASE);

    assertCode(ErrorCode.WRITE_IN_PROGRESS, () -> beginLiveRead("catalog", "reader", LEASE));
    assertCode(ErrorCode.WRITE_IN_PROGRESS, () -> beginSnapshot("catalog", "snapshotter", LEASE));
  }

  @Test
  final void snapshotGenerationCoexistsWithLiveReads() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");

    LiveReadSession read = beginLiveRead("catalog", "reader", LEASE);
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);

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
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);

    assertCode(ErrorCode.SNAPSHOT_GENERATION_ACTIVE, () -> beginWrite("catalog", "writer", LEASE));
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
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);

    WriteSession write = beginWrite("catalog", "writer", LEASE);

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
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);

    assertThrows(IllegalArgumentException.class, () -> beginWrite("catalog", " ", LEASE));

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
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);
    beginLiveRead("catalog", "reader", LEASE);

    assertCode(ErrorCode.LIVE_READ_ACTIVE, () -> beginWrite("catalog", "writer", LEASE));
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
    assertNotNull(beginWrite("allow", "writer-2", LEASE));

    registerEnabled(
        "require",
        MissingCurrentSnapshotPolicy.REQUIRE_CURRENT_SNAPSHOT,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("require");
    assertCode(ErrorCode.CURRENT_SNAPSHOT_REQUIRED, () -> beginWrite("require", "writer-2", LEASE));
    SnapshotGenerationSession snapshot = beginSnapshot("require", "snapshotter", LEASE);
    submit(snapshot, SNAPSHOT);
    assertNotNull(beginWrite("require", "writer-2", LEASE));
  }

  @Test
  final void unsuccessfulSnapshotAttemptsRetainHistoryAndAllowRetryForSameActiveVersion() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");

    SnapshotGenerationSession aborted = beginSnapshot("catalog", "aborted-snapshotter", LEASE);
    store.abortSnapshot(aborted.sessionId(), aborted.fencingToken());
    SnapshotGenerationSession afterAbort =
        beginSnapshot("catalog", "retry-after-abort", Duration.ofSeconds(5));
    assertEquals(aborted.snapshotVersion(), afterAbort.snapshotVersion());
    assertEquals(
        SnapshotGenerationStatus.ABORTED,
        store.findSnapshotSession(aborted.sessionId()).orElseThrow().status());
    assertCode(ErrorCode.INVALID_SESSION_TRANSITION, () -> submit(aborted, SNAPSHOT));

    fixture.advanceAuthoritativeTime(Duration.ofSeconds(5));
    SnapshotGenerationSession afterExpiry = beginSnapshot("catalog", "retry-after-expiry", LEASE);
    assertEquals(afterAbort.snapshotVersion(), afterExpiry.snapshotVersion());
    assertEquals(
        SnapshotGenerationStatus.EXPIRED,
        store.findSnapshotSession(afterAbort.sessionId()).orElseThrow().status());
    assertCode(ErrorCode.LEASE_EXPIRED, () -> submit(afterAbort, SNAPSHOT));

    WriteSession invalidatingWrite = beginWrite("catalog", "invalidating-writer", LEASE);
    store.failWrite(
        invalidatingWrite.sessionId(), invalidatingWrite.fencingToken(), "writer failed");
    SnapshotGenerationSession afterInvalidation =
        beginSnapshot("catalog", "retry-after-invalidation", LEASE);
    assertEquals(afterExpiry.snapshotVersion(), afterInvalidation.snapshotVersion());
    assertEquals(
        SnapshotGenerationStatus.INVALIDATED,
        store.findSnapshotSession(afterExpiry.sessionId()).orElseThrow().status());
    assertCode(ErrorCode.SNAPSHOT_INVALIDATED, () -> submit(afterExpiry, SNAPSHOT));

    assertFalse(submit(afterInvalidation, SNAPSHOT).replayed());
    assertCode(
        ErrorCode.SNAPSHOT_SESSION_ALREADY_EXISTS,
        () -> beginSnapshot("catalog", "after-publication", LEASE));
  }

  @Test
  final void requiredSnapshotPolicyCanRecoverFromAbortedAndExpiredAttempts() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.REQUIRE_CURRENT_SNAPSHOT,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");

    SnapshotGenerationSession aborted = beginSnapshot("catalog", "aborted-snapshotter", LEASE);
    store.abortSnapshot(aborted.sessionId(), aborted.fencingToken());
    SnapshotGenerationSession expiring =
        beginSnapshot("catalog", "expiring-snapshotter", Duration.ofSeconds(5));
    fixture.advanceAuthoritativeTime(Duration.ofSeconds(5));
    SnapshotGenerationSession successful =
        beginSnapshot("catalog", "successful-snapshotter", LEASE);

    assertEquals(aborted.snapshotVersion(), expiring.snapshotVersion());
    assertEquals(expiring.snapshotVersion(), successful.snapshotVersion());
    assertEquals(
        SnapshotGenerationStatus.ABORTED,
        store.findSnapshotSession(aborted.sessionId()).orElseThrow().status());
    assertEquals(
        SnapshotGenerationStatus.EXPIRED,
        store.findSnapshotSession(expiring.sessionId()).orElseThrow().status());
    assertFalse(submit(successful, SNAPSHOT).replayed());
    assertEquals(2, beginWrite("catalog", "next-writer", LEASE).allocatedVersion());
  }

  @Test
  final void activeSnapshotAttemptRejectsAnotherAttemptForTheSameVersion() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);

    assertEquals(1, snapshot.snapshotVersion());
    assertCode(
        ErrorCode.SNAPSHOT_SESSION_ALREADY_EXISTS,
        () -> beginSnapshot("catalog", "snapshotter-2", LEASE));
  }

  @Test
  final void immutableSnapshotReplayAndConflictCoverEveryRepresentationField() {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);

    VersionGateStore.SnapshotSubmission first = submit(snapshot, SNAPSHOT);
    WriteSession nextWrite = beginWrite("catalog", "writer-2", LEASE);
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
  final void failedSnapshotUploadsRemainInvisibleAndTheSameSessionCanRetry() throws IOException {
    registerEnabled(
        "catalog",
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.BLOCK_WRITER,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate("catalog");
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);

    assertCode(
        ErrorCode.VALIDATION_FAILED,
        () ->
            submit(
                snapshot,
                new ByteArrayInputStream(SNAPSHOT),
                SNAPSHOT.length + 1L,
                Optional.empty()));
    assertSnapshotStillGeneratingAndInvisible(snapshot);

    assertCode(
        ErrorCode.VALIDATION_FAILED,
        () ->
            submit(
                snapshot,
                new ByteArrayInputStream(SNAPSHOT),
                SNAPSHOT.length - 1L,
                Optional.empty()));
    assertSnapshotStillGeneratingAndInvisible(snapshot);

    assertCode(
        ErrorCode.CHECKSUM_MISMATCH,
        () ->
            submit(
                snapshot,
                new ByteArrayInputStream(SNAPSHOT),
                SNAPSHOT.length,
                Optional.of("0".repeat(64))));
    assertSnapshotStillGeneratingAndInvisible(snapshot);

    InputStream failingUpload =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("simulated upload failure");
          }
        };
    assertCode(
        ErrorCode.STORAGE_FAILURE,
        () -> submit(snapshot, failingUpload, SNAPSHOT.length, Optional.empty()));
    assertSnapshotStillGeneratingAndInvisible(snapshot);

    assertFalse(submit(snapshot, SNAPSHOT).replayed());
    try (VersionGateStore.SnapshotContent content =
        store.getSnapshot("catalog", SnapshotSelector.BY_VERSION, OptionalLong.of(1))) {
      assertArrayEquals(SNAPSHOT, content.inputStream().readAllBytes());
    }
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
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);
    submit(snapshot, SNAPSHOT);
    beginWrite("catalog", "writer-2", LEASE);

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
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);
    submit(snapshot, SNAPSHOT);

    try (VersionGateStore.SnapshotContent current =
        store.getSnapshot("catalog", SnapshotSelector.CURRENT, OptionalLong.empty())) {
      assertFalse(current.resolution().stale());
      assertEquals(1, current.resolution().activeVersion());
    }

    WriteSession second = beginWrite("catalog", "writer-2", LEASE);
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
    SnapshotGenerationSession snapshot = beginSnapshot("catalog", "snapshotter", LEASE);
    submit(snapshot, SNAPSHOT);
    beginWrite("catalog", "writer-2", LEASE);

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
    WriteSession first = beginWrite("catalog", "writer-1", Duration.ofSeconds(5));
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
    WriteSession second = beginWrite("catalog", "writer-2", LEASE);
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
    LiveReadSession read = beginLiveRead("catalog", "reader", Duration.ofSeconds(5));
    SnapshotGenerationSession snapshot =
        beginSnapshot("catalog", "snapshotter", Duration.ofSeconds(5));
    fixture.advanceAuthoritativeTime(Duration.ofSeconds(5));

    assertCode(
        ErrorCode.LEASE_EXPIRED,
        () -> store.completeLiveRead(read.sessionId(), read.fencingToken()));
    assertNotNull(beginWrite("catalog", "writer", LEASE));
    assertCode(ErrorCode.LEASE_EXPIRED, () -> submit(snapshot, SNAPSHOT));
  }

  @Test
  final void writerAdmissionWhileUploadIsStagedInvalidatesBeforeSnapshotPublication()
      throws Exception {
    String resourceId = "race";
    registerEnabled(
        resourceId,
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate(resourceId);
    SnapshotGenerationSession snapshot = beginSnapshot(resourceId, "snapshotter", LEASE);
    CountDownLatch uploadReadStarted = new CountDownLatch(1);
    CountDownLatch releaseUpload = new CountDownLatch(1);
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
      Future<WriteSession> writer = executor.submit(() -> beginWrite(resourceId, "writer", LEASE));

      assertEquals(2, get(writer).allocatedVersion());
      assertEquals(
          SnapshotGenerationStatus.INVALIDATED,
          store.findSnapshotSession(snapshot.sessionId()).orElseThrow().status());
      releaseUpload.countDown();

      assertEquals(ErrorCode.SNAPSHOT_INVALIDATED, get(submission));
      assertCode(
          ErrorCode.SNAPSHOT_NOT_FOUND,
          () -> store.getSnapshot(resourceId, SnapshotSelector.BY_VERSION, OptionalLong.of(1)));
    } finally {
      releaseUpload.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  final void writerInvalidationTakesPrecedenceOverConcurrentMalformedUploadFailure()
      throws Exception {
    String resourceId = "malformed-race";
    registerEnabled(
        resourceId,
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate(resourceId);
    SnapshotGenerationSession snapshot = beginSnapshot(resourceId, "snapshotter", LEASE);
    CountDownLatch uploadReadStarted = new CountDownLatch(1);
    CountDownLatch releaseUpload = new CountDownLatch(1);
    InputStream malformedUpload =
        new CoordinatedInputStream(SNAPSHOT, uploadReadStarted, releaseUpload);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Object> submission =
          executor.submit(
              () -> {
                try {
                  return submit(snapshot, malformedUpload, SNAPSHOT.length - 1L);
                } catch (VersionGateException exception) {
                  return exception.code();
                }
              });
      assertTrue(uploadReadStarted.await(5, TimeUnit.SECONDS));

      WriteSession writer = beginWrite(resourceId, "writer", LEASE);
      assertEquals(2, writer.allocatedVersion());
      assertEquals(
          SnapshotGenerationStatus.INVALIDATED,
          store.findSnapshotSession(snapshot.sessionId()).orElseThrow().status());
      releaseUpload.countDown();

      assertEquals(ErrorCode.SNAPSHOT_INVALIDATED, get(submission));
      assertCode(
          ErrorCode.SNAPSHOT_NOT_FOUND,
          () -> store.getSnapshot(resourceId, SnapshotSelector.BY_VERSION, OptionalLong.of(1)));
    } finally {
      releaseUpload.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  final void completedSnapshotPublicationRemainsVisibleAfterLaterWriterAdmission()
      throws IOException {
    String resourceId = "publication-first";
    registerEnabled(
        resourceId,
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT,
        RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING);
    activate(resourceId);
    SnapshotGenerationSession snapshot = beginSnapshot(resourceId, "snapshotter", LEASE);

    assertFalse(submit(snapshot, SNAPSHOT).replayed());
    WriteSession writer = beginWrite(resourceId, "writer", LEASE);

    assertEquals(2, writer.allocatedVersion());
    assertEquals(
        SnapshotGenerationStatus.PUBLISHED,
        store.findSnapshotSession(snapshot.sessionId()).orElseThrow().status());
    try (VersionGateStore.SnapshotContent content =
        store.getSnapshot(resourceId, SnapshotSelector.BY_VERSION, OptionalLong.of(1))) {
      assertArrayEquals(SNAPSHOT, content.inputStream().readAllBytes());
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
    WriteSession write = beginWrite(resourceId, "writer", LEASE);
    return store.completeWrite(write.sessionId(), write.fencingToken());
  }

  private WriteSession beginWrite(String resourceId, String owner, Duration leaseDuration) {
    return store.beginWrite(resourceId, owner, leaseDuration, nextKey("write")).session();
  }

  private LiveReadSession beginLiveRead(String resourceId, String owner, Duration leaseDuration) {
    return store.beginLiveRead(resourceId, owner, leaseDuration, nextKey("read")).session();
  }

  private SnapshotGenerationSession beginSnapshot(
      String resourceId, String owner, Duration leaseDuration) {
    return store.beginSnapshot(resourceId, owner, leaseDuration, nextKey("snapshot")).session();
  }

  private String nextKey(String operation) {
    return operation + "-" + nextIdempotencyKey.incrementAndGet();
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
    return submit(session, inputStream, contentLength, Optional.empty());
  }

  private VersionGateStore.SnapshotSubmission submit(
      SnapshotGenerationSession session,
      InputStream inputStream,
      long contentLength,
      Optional<String> expectedSha256) {
    return store.submitSnapshot(
        session.sessionId(),
        session.fencingToken(),
        new VersionGateStore.SnapshotUpload(
            inputStream,
            contentLength,
            "application/octet-stream",
            Optional.empty(),
            expectedSha256));
  }

  private void assertSnapshotStillGeneratingAndInvisible(SnapshotGenerationSession session) {
    assertEquals(
        SnapshotGenerationStatus.GENERATING,
        store.findSnapshotSession(session.sessionId()).orElseThrow().status());
    assertCode(
        ErrorCode.SNAPSHOT_NOT_FOUND,
        () ->
            store.getSnapshot(
                session.resourceId(),
                SnapshotSelector.BY_VERSION,
                OptionalLong.of(session.snapshotVersion())));
  }

  private Object beginWriteAfter(CountDownLatch start, String owner) throws InterruptedException {
    start.await();
    try {
      return beginWrite("catalog", owner, LEASE);
    } catch (VersionGateException exception) {
      return exception.code();
    }
  }

  private static void assertConcurrentReplay(ThrowingSupplier operation) throws Exception {
    ConcurrentOutcomes outcomes = runConcurrently(operation, operation);
    assertTrue(
        outcomes.first() instanceof VersionGateStore.SessionAdmission<?>,
        "the first request must return an admission");
    assertTrue(
        outcomes.second() instanceof VersionGateStore.SessionAdmission<?>,
        "the second request must return an admission");
    VersionGateStore.SessionAdmission<?> first =
        (VersionGateStore.SessionAdmission<?>) outcomes.first();
    VersionGateStore.SessionAdmission<?> second =
        (VersionGateStore.SessionAdmission<?>) outcomes.second();
    assertEquals(first.session(), second.session());
    assertNotEquals(first.replayed(), second.replayed());
  }

  private static void assertConcurrentKeyConflict(
      ThrowingSupplier firstOperation, ThrowingSupplier secondOperation) throws Exception {
    ConcurrentOutcomes outcomes = runConcurrently(firstOperation, secondOperation);
    if (outcomes.first() instanceof VersionGateStore.SessionAdmission<?>) {
      assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, outcomes.second());
    } else {
      assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, outcomes.first());
      assertTrue(
          outcomes.second() instanceof VersionGateStore.SessionAdmission<?>,
          "one fingerprint must create the session");
    }
  }

  private static ConcurrentOutcomes runConcurrently(
      ThrowingSupplier firstOperation, ThrowingSupplier secondOperation) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Object> first = executor.submit(() -> captureAfter(start, firstOperation));
      Future<Object> second = executor.submit(() -> captureAfter(start, secondOperation));
      start.countDown();
      return new ConcurrentOutcomes(get(first), get(second));
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static Object captureAfter(CountDownLatch start, ThrowingSupplier operation)
      throws Exception {
    start.await();
    try {
      return operation.get();
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

  @FunctionalInterface
  private interface ThrowingSupplier {
    Object get() throws Exception;
  }

  private record ConcurrentOutcomes(Object first, Object second) {}

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
