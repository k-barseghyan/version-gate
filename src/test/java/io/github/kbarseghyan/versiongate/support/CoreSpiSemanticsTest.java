package io.github.kbarseghyan.versiongate.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.application.VersionGateService;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.BuildStatus;
import io.github.kbarseghyan.versiongate.domain.Participant;
import io.github.kbarseghyan.versiongate.domain.ParticipantStatus;
import io.github.kbarseghyan.versiongate.domain.SnapshotComponent;
import io.github.kbarseghyan.versiongate.domain.SnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.VersionManifest;
import io.github.kbarseghyan.versiongate.port.ParticipantGateway;
import io.github.kbarseghyan.versiongate.port.SnapshotStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoreSpiSemanticsTest {

  private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
  private static final Duration LEASE = Duration.ofMinutes(5);

  private MutableClock clock;
  private InMemoryControlStore controlStore;
  private InMemorySnapshotStore snapshotStore;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(NOW);
    controlStore = new InMemoryControlStore(clock);
    snapshotStore = new InMemorySnapshotStore();
  }

  @Test
  void simultaneousBeginsYieldExactlyOneBuildAndNeverReuseItsFence() throws Exception {
    registerClientResource("catalog", Set.of("products"));
    assertCode(
        ErrorCode.RESOURCE_ALREADY_EXISTS,
        () ->
            controlStore.registerResource(
                "catalog", SnapshotPolicy.CLIENT_MANAGED, Set.of("prices"), List.of()));
    assertThat(controlStore.findResource("catalog").orElseThrow().requiredComponentIds())
        .isEqualTo(Set.of("products"));
    int contenderCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
    CountDownLatch ready = new CountDownLatch(contenderCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<BeginAttempt>> attempts = new ArrayList<>();
    try {
      for (int index = 0; index < contenderCount; index++) {
        long targetVersion = index + 1L;
        attempts.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  try {
                    return new BeginAttempt(
                        controlStore.beginBuild(
                            "catalog", targetVersion, "owner-" + targetVersion, LEASE),
                        null);
                  } catch (VersionGateException exception) {
                    return new BeginAttempt(null, exception.code());
                  }
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<BeginAttempt> results = new ArrayList<>();
      for (Future<BeginAttempt> attempt : attempts) {
        results.add(attempt.get(5, TimeUnit.SECONDS));
      }
      List<Build> successful =
          results.stream().map(BeginAttempt::build).filter(java.util.Objects::nonNull).toList();
      assertThat(successful).hasSize(1);
      assertThat(results)
          .filteredOn(result -> result.build() == null)
          .allMatch(result -> result.errorCode() == ErrorCode.BUILD_ALREADY_EXISTS);

      Build winner = successful.getFirst();
      Build abandoned = controlStore.abortBuild(winner.buildId(), winner.fencingToken());
      assertThat(abandoned.status()).isEqualTo(BuildStatus.ABANDONED);
      assertThat(controlStore.abortBuild(winner.buildId(), winner.fencingToken()))
          .isSameAs(abandoned);
      Build next = controlStore.beginBuild("catalog", 100, "next-owner", LEASE);
      assertThat(next.fencingToken()).isEqualTo(winner.fencingToken() + 1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void staleAndExpiredMutationsFailWhileFailureTerminalizationRemainsAvailable() {
    registerClientResource("catalog", Set.of("products"));
    Build build = controlStore.beginBuild("catalog", 1, "owner", Duration.ofSeconds(10));

    assertCode(
        ErrorCode.STALE_FENCING_TOKEN,
        () ->
            controlStore.startSnapshotPhase(
                build.buildId(), build.fencingToken() - 1, BuildStatus.SNAPSHOTTING));

    clock.advance(Duration.ofSeconds(10));
    assertCode(
        ErrorCode.LEASE_EXPIRED,
        () -> controlStore.renewBuild(build.buildId(), build.fencingToken(), LEASE));
    assertCode(
        ErrorCode.LEASE_EXPIRED,
        () ->
            controlStore.startSnapshotPhase(
                build.buildId(), build.fencingToken(), BuildStatus.SNAPSHOTTING));
    assertCode(
        ErrorCode.LEASE_EXPIRED,
        () -> controlStore.abortBuild(build.buildId(), build.fencingToken()));

    Build failed = controlStore.failBuild(build.buildId(), build.fencingToken(), "cleanup");
    assertThat(failed.status()).isEqualTo(BuildStatus.FAILED);
    assertThat(controlStore.failBuild(build.buildId(), build.fencingToken(), "replay"))
        .isSameAs(failed);
    assertThat(controlStore.abortBuild(build.buildId(), build.fencingToken())).isSameAs(failed);
  }

  @Test
  void coordinatedRenewalRejectsAtomicallyOnceQuiescenceBegins() {
    controlStore.registerResource(
        "orders",
        SnapshotPolicy.COORDINATED_QUIESCE,
        Set.of("orders"),
        List.of(new Participant("database", URI.create("https://database.example"))));
    Build build = controlStore.beginBuild("orders", 1, "owner", LEASE);
    Build quiescing =
        controlStore.startSnapshotPhase(
            build.buildId(), build.fencingToken(), BuildStatus.QUIESCING);

    assertThat(quiescing.status()).isEqualTo(BuildStatus.QUIESCING);
    assertCode(
        ErrorCode.INVALID_BUILD_TRANSITION,
        () -> controlStore.renewBuild(build.buildId(), build.fencingToken(), LEASE));
    assertThat(controlStore.markSnapshotting(build.buildId(), build.fencingToken()).status())
        .isEqualTo(BuildStatus.SNAPSHOTTING);
  }

  @Test
  void completionRechecksRequiredComponentsAndKeepsItsManifestStable() {
    registerClientResource("catalog", Set.of("prices", "products"));
    Build build = snapshottingBuild("catalog", 1);
    SnapshotComponent products = component(build, "products", "a".repeat(64), 3);
    controlStore.registerSnapshotComponent(build.buildId(), build.fencingToken(), products);

    assertCode(
        ErrorCode.INCOMPLETE_SNAPSHOT,
        () -> controlStore.completeBuild(build.buildId(), build.fencingToken()));

    SnapshotComponent prices = component(build, "prices", "b".repeat(64), 5);
    controlStore.registerSnapshotComponent(build.buildId(), build.fencingToken(), prices);
    VersionManifest completed = controlStore.completeBuild(build.buildId(), build.fencingToken());
    VersionManifest replayed = controlStore.completeBuild(build.buildId(), build.fencingToken());

    assertThat(replayed).isSameAs(completed);
    assertThat(completed.components())
        .extracting(SnapshotComponent::componentId)
        .containsExactly("prices", "products");
    assertThat(controlStore.findVersionManifest("catalog", 1)).isEmpty();
    assertThat(
            controlStore.registerSnapshotComponent(build.buildId(), build.fencingToken(), products))
        .isSameAs(products);

    SnapshotComponent changedKey =
        new SnapshotComponent(
            products.buildId(),
            products.resourceId(),
            products.version(),
            products.componentId(),
            products.objectKey() + "-changed",
            products.contentType(),
            products.contentEncoding(),
            products.sha256(),
            products.size(),
            products.schemaVersion(),
            products.capturedAt());
    assertCode(
        ErrorCode.COMPONENT_CONFLICT,
        () ->
            controlStore.registerSnapshotComponent(
                build.buildId(), build.fencingToken(), changedKey));
  }

  @Test
  void activationAtomicallyPublishesOneStableVersionAndPreservesHistory() {
    registerClientResource("catalog", Set.of("products"));
    Build first = snapshottingBuild("catalog", 1);
    controlStore.registerSnapshotComponent(
        first.buildId(), first.fencingToken(), component(first, "products", "a".repeat(64), 3));
    VersionManifest firstManifest =
        controlStore.completeBuild(first.buildId(), first.fencingToken());

    assertThat(controlStore.findActiveVersionManifest("catalog")).isEmpty();
    assertThat(controlStore.findVersionManifest("catalog", 1)).isEmpty();
    Build firstActivation = controlStore.activateBuild(first.buildId(), first.fencingToken());
    assertThat(firstActivation.status()).isEqualTo(BuildStatus.ACTIVE);
    assertThat(controlStore.activateBuild(first.buildId(), first.fencingToken()))
        .isSameAs(firstActivation);
    assertThat(controlStore.findActiveVersionManifest("catalog")).contains(firstManifest);
    assertThat(controlStore.findVersionManifest("catalog", 1)).contains(firstManifest);

    Build second = snapshottingBuild("catalog", 2);
    assertThat(second.baseActiveVersion()).isEqualTo(1);
    controlStore.registerSnapshotComponent(
        second.buildId(), second.fencingToken(), component(second, "products", "b".repeat(64), 4));
    VersionManifest secondManifest =
        controlStore.completeBuild(second.buildId(), second.fencingToken());
    assertThat(controlStore.findActiveVersionManifest("catalog")).contains(firstManifest);
    assertThat(controlStore.findVersionManifest("catalog", 2)).isEmpty();

    controlStore.activateBuild(second.buildId(), second.fencingToken());
    assertThat(controlStore.findActiveVersionManifest("catalog")).contains(secondManifest);
    assertThat(controlStore.findVersionManifest("catalog", 1)).contains(firstManifest);
    assertThat(controlStore.findVersionManifest("catalog", 2)).contains(secondManifest);
  }

  @Test
  void participantProgressCanRetryAfterFailureButTerminalStatesNeverRegress() {
    controlStore.registerResource(
        "orders",
        SnapshotPolicy.COORDINATED_QUIESCE,
        Set.of("orders"),
        List.of(new Participant("database", URI.create("https://database.example"))));
    Build build = controlStore.beginBuild("orders", 1, "owner", LEASE);

    controlStore.updateParticipantState(
        build.buildId(), "database", ParticipantStatus.QUIESCED, null);
    controlStore.updateParticipantState(
        build.buildId(), "database", ParticipantStatus.CAPTURE_REQUESTED, null);
    controlStore.updateParticipantState(
        build.buildId(), "database", ParticipantStatus.FAILED, "capture failed");
    controlStore.updateParticipantState(
        build.buildId(), "database", ParticipantStatus.QUIESCED, null);
    controlStore.updateParticipantState(
        build.buildId(), "database", ParticipantStatus.CAPTURE_REQUESTED, null);
    controlStore.updateParticipantState(
        build.buildId(), "database", ParticipantStatus.RESUMED, null);
    controlStore.updateParticipantState(
        build.buildId(), "database", ParticipantStatus.FAILED, "late failure");
    controlStore.updateParticipantState(
        build.buildId(), "database", ParticipantStatus.ABORTED, "late abort");

    assertThat(controlStore.findParticipantStates(build.buildId()))
        .singleElement()
        .satisfies(state -> assertThat(state.status()).isEqualTo(ParticipantStatus.RESUMED));
  }

  @Test
  void sweeperAbandonsOnlyExpiredBuildsUsingTheInjectedClock() {
    registerClientResource("catalog", Set.of("products"));
    registerClientResource("orders", Set.of("orders"));
    Build expired = controlStore.beginBuild("catalog", 1, "owner", Duration.ofSeconds(10));
    Build live = controlStore.beginBuild("orders", 1, "owner", Duration.ofSeconds(30));

    clock.advance(Duration.ofSeconds(10));
    assertThat(controlStore.abandonExpiredBuilds()).isOne();
    assertThat(controlStore.findBuild(expired.buildId()))
        .get()
        .extracting(Build::status)
        .isEqualTo(BuildStatus.ABANDONED);
    assertThat(controlStore.findCurrentBuild("catalog")).isEmpty();
    assertThat(controlStore.findCurrentBuild("orders")).contains(live);
  }

  @Test
  void snapshotStoreStreamsBoundedUploadsAndEnforcesImmutableReferences() throws Exception {
    byte[] bytes = new byte[20_000];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) (index % 251);
    }
    TrackingInputStream input = new TrackingInputStream(bytes);
    SnapshotStore.Upload upload =
        new SnapshotStore.Upload(
            "snapshots/catalog/1/products",
            input,
            bytes.length,
            "application/octet-stream",
            Optional.of("identity"),
            Optional.of(sha256(bytes)));

    SnapshotStore.StoredObject first = snapshotStore.uploadImmutable(upload);
    assertThat(first.alreadyExisted()).isFalse();
    assertThat(input.maximumRequestedBytes).isBetween(1, 8192);
    assertThat(input.closed).isFalse();
    snapshotStore.verify(first.reference());

    SnapshotStore.StoredObject replay =
        snapshotStore.uploadImmutable(
            new SnapshotStore.Upload(
                upload.objectKey(),
                new ByteArrayInputStream(bytes),
                bytes.length,
                upload.contentType(),
                upload.contentEncoding(),
                upload.expectedSha256()));
    assertThat(replay.alreadyExisted()).isTrue();
    assertThat(replay.reference()).isEqualTo(first.reference());

    byte[] changed = bytes.clone();
    changed[0]++;
    assertCode(
        ErrorCode.COMPONENT_CONFLICT,
        () ->
            snapshotStore.uploadImmutable(
                new SnapshotStore.Upload(
                    upload.objectKey(),
                    new ByteArrayInputStream(changed),
                    changed.length,
                    upload.contentType(),
                    upload.contentEncoding(),
                    Optional.empty())));

    try (SnapshotStore.ObjectContent content = snapshotStore.open(first.reference())) {
      ByteArrayOutputStream streamed = new ByteArrayOutputStream();
      byte[] chunk = new byte[257];
      int read;
      while ((read = content.inputStream().read(chunk)) >= 0) {
        streamed.write(chunk, 0, read);
      }
      assertThat(streamed.toByteArray()).containsExactly(bytes);
      assertThat(content.contentLength()).isEqualTo(bytes.length);
      assertThat(content.sha256()).isEqualTo(sha256(bytes));
    }

    SnapshotStore.ObjectReference wrongReference =
        new SnapshotStore.ObjectReference(
            first.reference().objectKey(), "f".repeat(64), first.reference().size());
    assertCode(ErrorCode.STORAGE_FAILURE, () -> snapshotStore.delete(wrongReference));
    snapshotStore.verify(first.reference());
    snapshotStore.delete(first.reference());
    assertCode(ErrorCode.SNAPSHOT_OBJECT_MISSING, () -> snapshotStore.verify(first.reference()));
  }

  @Test
  void snapshotStoreRejectsShortLongAndChecksumMismatchedBodies() {
    assertCode(
        ErrorCode.VALIDATION_FAILED,
        () ->
            snapshotStore.uploadImmutable(upload("short", new byte[] {1, 2}, 3, Optional.empty())));
    assertCode(
        ErrorCode.VALIDATION_FAILED,
        () ->
            snapshotStore.uploadImmutable(
                upload("long", new byte[] {1, 2, 3}, 2, Optional.empty())));
    assertCode(
        ErrorCode.CHECKSUM_MISMATCH,
        () ->
            snapshotStore.uploadImmutable(
                upload("checksum", new byte[] {1, 2, 3}, 3, Optional.of("0".repeat(64)))));
    assertThat(snapshotStore.size()).isZero();
  }

  @Test
  void applicationFlowUsesTheReusableStoresWithoutBypassingStreamingOrVisibility()
      throws Exception {
    VersionGateService service =
        new VersionGateService(
            controlStore,
            snapshotStore,
            new NoOpParticipantGateway(),
            clock,
            Duration.ofHours(1),
            1024 * 1024);
    service.registerResource(
        new VersionGateService.RegisterResourceCommand(
            "catalog", SnapshotPolicy.CLIENT_MANAGED, Set.of("products"), List.of()));
    Build build =
        service.beginBuild(new VersionGateService.BeginBuildCommand("catalog", 1, "owner", LEASE));
    service.startSnapshotPhase(
        new VersionGateService.BuildTokenCommand(build.buildId(), build.fencingToken()));
    byte[] bytes = "streamed-snapshot".getBytes(StandardCharsets.UTF_8);
    service.submitSnapshotComponent(
        new VersionGateService.SubmitComponentCommand(
            build.buildId(),
            build.fencingToken(),
            "products",
            new ByteArrayInputStream(bytes),
            bytes.length,
            "application/octet-stream",
            Optional.empty(),
            Optional.of(sha256(bytes)),
            Optional.of("1"),
            Optional.of(clock.instant())));
    VersionManifest manifest =
        service.completeBuild(
            new VersionGateService.BuildTokenCommand(build.buildId(), build.fencingToken()));
    assertCode(ErrorCode.VERSION_NOT_FOUND, () -> service.getVersionManifest("catalog", 1));
    service.activateBuild(
        new VersionGateService.BuildTokenCommand(build.buildId(), build.fencingToken()));

    assertThat(service.getVersionManifest("catalog", 1)).isEqualTo(manifest);
    try (VersionGateService.SnapshotDownload download =
        service.streamSnapshotComponent("catalog", 1, "products")) {
      assertThat(download.content().inputStream().readAllBytes()).containsExactly(bytes);
    }
  }

  private void registerClientResource(String resourceId, Set<String> components) {
    controlStore.registerResource(resourceId, SnapshotPolicy.CLIENT_MANAGED, components, List.of());
  }

  private Build snapshottingBuild(String resourceId, long version) {
    Build build = controlStore.beginBuild(resourceId, version, "owner", LEASE);
    return controlStore.startSnapshotPhase(
        build.buildId(), build.fencingToken(), BuildStatus.SNAPSHOTTING);
  }

  private SnapshotComponent component(Build build, String componentId, String checksum, long size) {
    return new SnapshotComponent(
        build.buildId(),
        build.resourceId(),
        build.targetVersion(),
        componentId,
        "snapshots/" + build.resourceId() + "/" + build.targetVersion() + "/" + componentId,
        "application/octet-stream",
        Optional.empty(),
        checksum,
        size,
        Optional.empty(),
        clock.instant());
  }

  private static SnapshotStore.Upload upload(
      String key, byte[] bytes, long declaredLength, Optional<String> expectedSha256) {
    return new SnapshotStore.Upload(
        key,
        new ByteArrayInputStream(bytes),
        declaredLength,
        "application/octet-stream",
        Optional.empty(),
        expectedSha256);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void assertCode(ErrorCode code, ThrowableAssert.ThrowingCallable invocation) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            VersionGateException.class, exception -> assertThat(exception.code()).isEqualTo(code));
  }

  private record BeginAttempt(Build build, ErrorCode errorCode) {}

  private static final class TrackingInputStream extends ByteArrayInputStream {

    private int maximumRequestedBytes;
    private boolean closed;

    private TrackingInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public synchronized int read(byte[] bytes, int offset, int length) {
      maximumRequestedBytes = Math.max(maximumRequestedBytes, length);
      return super.read(bytes, offset, length);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  private static final class NoOpParticipantGateway implements ParticipantGateway {

    @Override
    public void quiesce(Participant participant, CallbackContext context) {}

    @Override
    public void capture(Participant participant, CallbackContext context) {}

    @Override
    public void resume(Participant participant, CallbackContext context) {}

    @Override
    public void abort(Participant participant, CallbackContext context) {}
  }
}
