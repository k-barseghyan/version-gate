package io.github.kbarseghyan.versiongate.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.BuildStatus;
import io.github.kbarseghyan.versiongate.domain.Participant;
import io.github.kbarseghyan.versiongate.domain.SnapshotComponent;
import io.github.kbarseghyan.versiongate.domain.SnapshotPolicy;
import io.github.kbarseghyan.versiongate.port.ControlStore;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Reusable minimum semantic contract for {@link ControlStore} implementations.
 *
 * <p>Adapter tests extend this class and create a fresh {@link ControlStoreTestFixture}. The
 * fixture advances storage-authoritative time through an adapter-specific test mechanism; the
 * contract never assumes that a production adapter accepts an injected JVM clock. Adapters add
 * stronger integration tests for isolation, crash recovery, migrations, and lock/time ordering.
 */
public abstract class ControlStoreContract {

  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final Duration SHORT_LEASE = Duration.ofSeconds(10);

  private ControlStoreTestFixture fixture;
  private ControlStore store;

  /**
   * Creates an empty control-store fixture for one contract-test invocation.
   *
   * @return fresh adapter-specific fixture
   */
  protected abstract ControlStoreTestFixture createControlStoreFixture();

  @BeforeEach
  final void initializeContractStore() {
    fixture = createControlStoreFixture();
    store = fixture.store();
  }

  @Test
  final void allocatesMonotonicCoordinatorVersionsAndFencingTokens() {
    registerClientResource("catalog");

    Build first = store.beginBuild("catalog", "first-owner", LEASE);
    Build abandoned = store.abortBuild(first.buildId(), first.fencingToken());
    Build second = store.beginBuild("catalog", "second-owner", LEASE);

    assertEquals(BuildStatus.ABANDONED, abandoned.status());
    assertTrue(first.targetVersion() >= 0);
    assertTrue(second.targetVersion() > first.targetVersion());
    assertTrue(second.fencingToken() > first.fencingToken());
    assertNull(first.baseActiveVersion());
    assertNull(second.baseActiveVersion());
  }

  @Test
  final void rejectsASecondLiveBuildWithoutAllocatingClientChosenState() {
    registerClientResource("catalog");
    Build first = store.beginBuild("catalog", "first-owner", LEASE);

    assertCode(
        ErrorCode.BUILD_ALREADY_EXISTS, () -> store.beginBuild("catalog", "second-owner", LEASE));

    assertEquals(first, store.findCurrentBuild("catalog").orElseThrow());
  }

  @Test
  final void expiresTheCurrentBuildUsingAdapterAuthoritativeTime() {
    registerClientResource("catalog");
    Build build = store.beginBuild("catalog", "owner", SHORT_LEASE);

    fixture.advanceAuthoritativeTime(SHORT_LEASE);

    assertTrue(store.findCurrentBuild("catalog").isEmpty());
    assertEquals(BuildStatus.ABANDONED, store.findBuild(build.buildId()).orElseThrow().status());
  }

  @Test
  final void componentReplayUsesTheCompleteRepresentationIdentityWithStablePrecedence() {
    registerClientResource("catalog");
    Build build = snapshottingBuild("catalog", LEASE);
    SnapshotComponent original =
        component(build, "products", "application/json", Optional.empty(), "a".repeat(64), 17);

    assertEquals(
        original, store.registerSnapshotComponent(build.buildId(), build.fencingToken(), original));

    SnapshotComponent sameRepresentationDifferentCaptureMetadata =
        new SnapshotComponent(
            original.buildId(),
            original.resourceId(),
            original.version(),
            original.componentId(),
            original.objectKey(),
            original.contentType(),
            original.contentEncoding(),
            original.sha256(),
            original.size(),
            Optional.of("2"),
            original.capturedAt().plusSeconds(1));
    assertEquals(
        original,
        store.registerSnapshotComponent(
            build.buildId(), build.fencingToken(), sameRepresentationDifferentCaptureMetadata));

    assertCode(
        ErrorCode.COMPONENT_CONFLICT,
        () ->
            store.registerSnapshotComponent(
                build.buildId(),
                build.fencingToken(),
                withRepresentationMetadata(original, "application/cbor", Optional.empty())));
    assertCode(
        ErrorCode.COMPONENT_CONFLICT,
        () ->
            store.registerSnapshotComponent(
                build.buildId(),
                build.fencingToken(),
                withRepresentationMetadata(original, original.contentType(), Optional.of("gzip"))));

    store.completeBuild(build.buildId(), build.fencingToken());
    fixture.advanceAuthoritativeTime(LEASE);

    assertEquals(
        original, store.registerSnapshotComponent(build.buildId(), build.fencingToken(), original));
    assertCode(
        ErrorCode.COMPONENT_CONFLICT,
        () ->
            store.registerSnapshotComponent(
                build.buildId(),
                build.fencingToken(),
                withRepresentationMetadata(original, "application/cbor", Optional.empty())));
    assertCode(
        ErrorCode.COMPONENT_CONFLICT,
        () ->
            store.registerSnapshotComponent(
                build.buildId(),
                build.fencingToken(),
                withRepresentationMetadata(original, original.contentType(), Optional.of("gzip"))));
    assertCode(
        ErrorCode.STALE_FENCING_TOKEN,
        () -> store.registerSnapshotComponent(build.buildId(), build.fencingToken() + 1, original));
  }

  @Test
  final void committedStartSnapshotPhaseReplayPrecedesLeaseExpiry() {
    registerClientResource("catalog");
    Build build = store.beginBuild("catalog", "owner", SHORT_LEASE);
    Build committed =
        store.startSnapshotPhase(build.buildId(), build.fencingToken(), BuildStatus.SNAPSHOTTING);

    fixture.advanceAuthoritativeTime(SHORT_LEASE);

    assertEquals(
        committed,
        store.startSnapshotPhase(build.buildId(), build.fencingToken(), BuildStatus.SNAPSHOTTING));
    assertCode(
        ErrorCode.STALE_FENCING_TOKEN,
        () ->
            store.startSnapshotPhase(
                build.buildId(), build.fencingToken() + 1, BuildStatus.SNAPSHOTTING));
    assertCode(
        ErrorCode.LEASE_EXPIRED,
        () ->
            store.startSnapshotPhase(build.buildId(), build.fencingToken(), BuildStatus.QUIESCING));

    registerClientResource("orders");
    Build uncommitted = store.beginBuild("orders", "owner", SHORT_LEASE);
    fixture.advanceAuthoritativeTime(SHORT_LEASE);
    assertCode(
        ErrorCode.LEASE_EXPIRED,
        () ->
            store.startSnapshotPhase(
                uncommitted.buildId(), uncommitted.fencingToken(), BuildStatus.SNAPSHOTTING));
  }

  @Test
  final void committedCoordinatedTransitionReplaysPrecedeLeaseExpiry() {
    registerCoordinatedResource("orders");
    Build build = store.beginBuild("orders", "owner", SHORT_LEASE);
    store.startSnapshotPhase(build.buildId(), build.fencingToken(), BuildStatus.QUIESCING);
    Build committed = store.markSnapshotting(build.buildId(), build.fencingToken());

    fixture.advanceAuthoritativeTime(SHORT_LEASE);

    assertEquals(committed, store.markSnapshotting(build.buildId(), build.fencingToken()));
    assertEquals(
        committed,
        store.startSnapshotPhase(build.buildId(), build.fencingToken(), BuildStatus.QUIESCING));
    assertCode(
        ErrorCode.STALE_FENCING_TOKEN,
        () -> store.markSnapshotting(build.buildId(), build.fencingToken() + 1));

    registerCoordinatedResource("billing");
    Build uncommitted = store.beginBuild("billing", "owner", SHORT_LEASE);
    store.startSnapshotPhase(
        uncommitted.buildId(), uncommitted.fencingToken(), BuildStatus.QUIESCING);
    fixture.advanceAuthoritativeTime(SHORT_LEASE);
    assertCode(
        ErrorCode.LEASE_EXPIRED,
        () -> store.markSnapshotting(uncommitted.buildId(), uncommitted.fencingToken()));
  }

  @Test
  final void activationValidatesReadyManifestBeforeActiveVersionCompareAndSet() {
    registerClientResource("catalog");
    Build first = snapshottingBuild("catalog", LEASE);
    store.registerSnapshotComponent(
        first.buildId(),
        first.fencingToken(),
        component(first, "products", "application/json", Optional.empty(), "a".repeat(64), 17));
    store.completeBuild(first.buildId(), first.fencingToken());
    store.activateBuild(first.buildId(), first.fencingToken());

    Build second = snapshottingBuild("catalog", LEASE);
    store.registerSnapshotComponent(
        second.buildId(),
        second.fencingToken(),
        component(second, "products", "application/json", Optional.empty(), "b".repeat(64), 19));
    store.completeBuild(second.buildId(), second.fencingToken());
    Build ready = store.findBuild(second.buildId()).orElseThrow();
    fixture.corruptActivationPreconditions(ready);
    Long activeVersionBeforeAttempt = store.findResource("catalog").orElseThrow().activeVersion();
    assertNotEquals(ready.baseActiveVersion(), activeVersionBeforeAttempt);

    assertCode(
        ErrorCode.INCOMPLETE_SNAPSHOT,
        () -> store.activateBuild(ready.buildId(), ready.fencingToken()));
    assertEquals(BuildStatus.READY, store.findBuild(ready.buildId()).orElseThrow().status());
    assertEquals(
        activeVersionBeforeAttempt, store.findResource("catalog").orElseThrow().activeVersion());
  }

  @Test
  final void activationValidatesBuildStateBeforeActiveVersionCompareAndSet() {
    registerClientResource("catalog");
    Build first = snapshottingBuild("catalog", LEASE);
    store.registerSnapshotComponent(
        first.buildId(),
        first.fencingToken(),
        component(first, "products", "application/json", Optional.empty(), "a".repeat(64), 17));
    store.completeBuild(first.buildId(), first.fencingToken());
    store.activateBuild(first.buildId(), first.fencingToken());

    Build abandoned = store.beginBuild("catalog", "abandoned-owner", LEASE);
    store.abortBuild(abandoned.buildId(), abandoned.fencingToken());

    Build replacement = snapshottingBuild("catalog", LEASE);
    store.registerSnapshotComponent(
        replacement.buildId(),
        replacement.fencingToken(),
        component(
            replacement, "products", "application/json", Optional.empty(), "b".repeat(64), 19));
    store.completeBuild(replacement.buildId(), replacement.fencingToken());
    store.activateBuild(replacement.buildId(), replacement.fencingToken());
    Long activeVersionBeforeAttempt = store.findResource("catalog").orElseThrow().activeVersion();
    assertNotEquals(abandoned.baseActiveVersion(), activeVersionBeforeAttempt);

    assertCode(
        ErrorCode.INVALID_BUILD_TRANSITION,
        () -> store.activateBuild(abandoned.buildId(), abandoned.fencingToken()));
    assertEquals(
        activeVersionBeforeAttempt, store.findResource("catalog").orElseThrow().activeVersion());
  }

  @Test
  final void oneSweepAbandonsEveryBuildExpiredAtItsAuthoritativeDecisionPoint() {
    registerClientResource("catalog");
    registerClientResource("orders");
    registerClientResource("billing");
    Build firstExpired = store.beginBuild("catalog", "owner", SHORT_LEASE);
    Build secondExpired = store.beginBuild("orders", "owner", SHORT_LEASE);
    Build live = store.beginBuild("billing", "owner", SHORT_LEASE.plusSeconds(1));

    fixture.advanceAuthoritativeTime(SHORT_LEASE);

    assertEquals(2, store.abandonExpiredBuilds());
    assertEquals(
        BuildStatus.ABANDONED, store.findBuild(firstExpired.buildId()).orElseThrow().status());
    assertEquals(
        BuildStatus.ABANDONED, store.findBuild(secondExpired.buildId()).orElseThrow().status());
    assertEquals(live, store.findCurrentBuild("billing").orElseThrow());
    assertEquals(0, store.abandonExpiredBuilds());
  }

  private Build snapshottingBuild(String resourceId, Duration lease) {
    Build build = store.beginBuild(resourceId, "owner", lease);
    return store.startSnapshotPhase(
        build.buildId(), build.fencingToken(), BuildStatus.SNAPSHOTTING);
  }

  private static SnapshotComponent component(
      Build build,
      String componentId,
      String contentType,
      Optional<String> contentEncoding,
      String sha256,
      long size) {
    return new SnapshotComponent(
        build.buildId(),
        build.resourceId(),
        build.targetVersion(),
        componentId,
        "snapshots/" + build.resourceId() + "/" + build.targetVersion() + "/" + componentId,
        contentType,
        contentEncoding,
        sha256,
        size,
        Optional.of("1"),
        build.createdAt());
  }

  private static SnapshotComponent withRepresentationMetadata(
      SnapshotComponent component, String contentType, Optional<String> contentEncoding) {
    return new SnapshotComponent(
        component.buildId(),
        component.resourceId(),
        component.version(),
        component.componentId(),
        component.objectKey(),
        contentType,
        contentEncoding,
        component.sha256(),
        component.size(),
        component.schemaVersion(),
        component.capturedAt());
  }

  private void registerClientResource(String resourceId) {
    store.registerResource(
        resourceId, SnapshotPolicy.CLIENT_MANAGED, Set.of("products"), List.of());
  }

  private void registerCoordinatedResource(String resourceId) {
    store.registerResource(
        resourceId,
        SnapshotPolicy.COORDINATED_QUIESCE,
        Set.of("products"),
        List.of(
            new Participant("database", URI.create("https://" + resourceId + ".example.test"))));
  }

  private static void assertCode(ErrorCode code, Executable invocation) {
    VersionGateException failure = assertThrows(VersionGateException.class, invocation);
    assertEquals(code, failure.code());
  }
}
