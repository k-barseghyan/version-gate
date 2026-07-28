package io.github.kbarseghyan.versiongate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.BuildStatus;
import io.github.kbarseghyan.versiongate.domain.Participant;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.SnapshotPolicy;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class BuildLifecycleTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
  private static final UUID BUILD_ID = UUID.fromString("bc9e43f5-f78f-4f60-9afb-b80dc7bb0fef");

  @Test
  void acceptsOnlyTheCurrentFencingToken() {
    Build build = build(BuildStatus.BUILDING, NOW.plusSeconds(30));

    assertThatCode(() -> BuildLifecycle.requireCurrentToken(build, 41)).doesNotThrowAnyException();

    assertError(ErrorCode.STALE_FENCING_TOKEN, () -> BuildLifecycle.requireCurrentToken(build, 40));
  }

  @Test
  void treatsTheLeaseExpiryInstantAsExpiredForNonTerminalBuilds() {
    Build build = build(BuildStatus.SNAPSHOTTING, NOW);

    assertError(ErrorCode.LEASE_EXPIRED, () -> BuildLifecycle.requireValidLease(build, NOW));
    assertThatCode(
            () ->
                BuildLifecycle.requireValidLease(
                    build(BuildStatus.SNAPSHOTTING, NOW.plusNanos(1)), NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void doesNotApplyLeaseChecksToTerminalBuilds() {
    for (BuildStatus status :
        List.of(BuildStatus.ACTIVE, BuildStatus.FAILED, BuildStatus.ABANDONED)) {
      assertThatCode(
              () -> BuildLifecycle.requireValidLease(build(status, NOW.minusSeconds(1)), NOW))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void selectsClientManagedSnapshotStatusAndIsIdempotent() {
    Resource resource = resource(SnapshotPolicy.CLIENT_MANAGED);

    assertThat(
            BuildLifecycle.snapshotStartStatus(resource, build(BuildStatus.BUILDING, validLease())))
        .isEqualTo(BuildStatus.SNAPSHOTTING);
    assertThat(
            BuildLifecycle.snapshotStartStatus(
                resource, build(BuildStatus.SNAPSHOTTING, validLease())))
        .isEqualTo(BuildStatus.SNAPSHOTTING);
  }

  @Test
  void selectsCoordinatedQuiesceStatusAndPreservesRetrySemantics() {
    Resource resource = resource(SnapshotPolicy.COORDINATED_QUIESCE);

    assertThat(
            BuildLifecycle.snapshotStartStatus(resource, build(BuildStatus.BUILDING, validLease())))
        .isEqualTo(BuildStatus.QUIESCING);
    assertThat(
            BuildLifecycle.snapshotStartStatus(
                resource, build(BuildStatus.QUIESCING, validLease())))
        .isEqualTo(BuildStatus.QUIESCING);
    assertThat(
            BuildLifecycle.snapshotStartStatus(
                resource, build(BuildStatus.SNAPSHOTTING, validLease())))
        .isEqualTo(BuildStatus.QUIESCING);
  }

  @Test
  void rejectsSnapshotStartFromAnInvalidBuildButDefersLeaseValidationToTheControlStore() {
    assertError(
        ErrorCode.INVALID_BUILD_TRANSITION,
        () ->
            BuildLifecycle.snapshotStartStatus(
                resource(SnapshotPolicy.CLIENT_MANAGED), build(BuildStatus.READY, validLease())));
    assertThat(
            BuildLifecycle.snapshotStartStatus(
                resource(SnapshotPolicy.CLIENT_MANAGED), build(BuildStatus.SNAPSHOTTING, NOW)))
        .isEqualTo(BuildStatus.SNAPSHOTTING);
  }

  @Test
  void requiresSnapshottingForSubmissionButDefersLeaseValidationToTheControlStore() {
    assertThatCode(
            () ->
                BuildLifecycle.requireSnapshotSubmission(
                    build(BuildStatus.SNAPSHOTTING, validLease())))
        .doesNotThrowAnyException();
    assertError(
        ErrorCode.INVALID_BUILD_TRANSITION,
        () -> BuildLifecycle.requireSnapshotSubmission(build(BuildStatus.BUILDING, validLease())));
    assertThatCode(
            () -> BuildLifecycle.requireSnapshotSubmission(build(BuildStatus.SNAPSHOTTING, NOW)))
        .doesNotThrowAnyException();
  }

  @Test
  void completionAcceptsSnapshottingAndIsIdempotentAfterReadyOrActive() {
    assertThatCode(
            () -> BuildLifecycle.requireCompletable(build(BuildStatus.SNAPSHOTTING, validLease())))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> BuildLifecycle.requireCompletable(build(BuildStatus.READY, NOW.minusSeconds(1))))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> BuildLifecycle.requireCompletable(build(BuildStatus.ACTIVE, NOW.minusSeconds(1))))
        .doesNotThrowAnyException();

    assertError(
        ErrorCode.INVALID_BUILD_TRANSITION,
        () -> BuildLifecycle.requireCompletable(build(BuildStatus.BUILDING, validLease())));
    assertThatCode(() -> BuildLifecycle.requireCompletable(build(BuildStatus.SNAPSHOTTING, NOW)))
        .doesNotThrowAnyException();
  }

  @Test
  void activationRequiresReadyAndIsIdempotentAfterActivation() {
    assertThatCode(() -> BuildLifecycle.requireActivatable(build(BuildStatus.READY, validLease())))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> BuildLifecycle.requireActivatable(build(BuildStatus.ACTIVE, NOW.minusSeconds(1))))
        .doesNotThrowAnyException();

    assertError(
        ErrorCode.INVALID_BUILD_TRANSITION,
        () -> BuildLifecycle.requireActivatable(build(BuildStatus.SNAPSHOTTING, validLease())));
    assertThatCode(() -> BuildLifecycle.requireActivatable(build(BuildStatus.READY, NOW)))
        .doesNotThrowAnyException();
  }

  @Test
  void renewalRequiresANonTerminalBuildButDefersLeaseValidationToTheControlStore() {
    for (BuildStatus status :
        List.of(
            BuildStatus.BUILDING,
            BuildStatus.QUIESCING,
            BuildStatus.SNAPSHOTTING,
            BuildStatus.READY)) {
      assertThatCode(() -> BuildLifecycle.requireRenewable(build(status, validLease())))
          .doesNotThrowAnyException();
    }

    assertThatCode(() -> BuildLifecycle.requireRenewable(build(BuildStatus.BUILDING, NOW)))
        .doesNotThrowAnyException();
    assertError(
        ErrorCode.INVALID_BUILD_TRANSITION,
        () -> BuildLifecycle.requireRenewable(build(BuildStatus.ACTIVE, validLease())));
  }

  @Test
  void abortIsIdempotentForFailedAndAbandonedButRejectsActive() {
    assertThatCode(
            () ->
                BuildLifecycle.requireAbortable(build(BuildStatus.ABANDONED, NOW.minusSeconds(1))))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> BuildLifecycle.requireAbortable(build(BuildStatus.FAILED, NOW.minusSeconds(1))))
        .doesNotThrowAnyException();
    assertThatCode(() -> BuildLifecycle.requireAbortable(build(BuildStatus.BUILDING, validLease())))
        .doesNotThrowAnyException();

    assertError(
        ErrorCode.INVALID_BUILD_TRANSITION,
        () -> BuildLifecycle.requireAbortable(build(BuildStatus.ACTIVE, validLease())));
    assertThatCode(() -> BuildLifecycle.requireAbortable(build(BuildStatus.BUILDING, NOW)))
        .doesNotThrowAnyException();
  }

  @Test
  void statusCheckReportsInvalidTransitions() {
    assertThatCode(
            () ->
                BuildLifecycle.requireStatus(
                    build(BuildStatus.BUILDING, validLease()), BuildStatus.BUILDING))
        .doesNotThrowAnyException();
    assertError(
        ErrorCode.INVALID_BUILD_TRANSITION,
        () ->
            BuildLifecycle.requireStatus(
                build(BuildStatus.BUILDING, validLease()), BuildStatus.SNAPSHOTTING));
  }

  @Test
  void enforcesTheHardCoordinatedParticipantLimit() {
    List<Participant> participants =
        IntStream.rangeClosed(0, DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE)
            .mapToObj(
                index ->
                    new Participant(
                        "participant-" + index, URI.create("https://participant.example")))
            .toList();

    assertThatThrownBy(
            () ->
                new Resource(
                    "catalog",
                    SnapshotPolicy.COORDINATED_QUIESCE,
                    Set.of("products"),
                    participants,
                    null,
                    NOW,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most " + DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE);
  }

  private static Instant validLease() {
    return NOW.plusSeconds(30);
  }

  private static Build build(BuildStatus status, Instant leaseExpiresAt) {
    return new Build(
        BUILD_ID,
        "catalog",
        7,
        6L,
        status,
        "test-owner",
        41,
        leaseExpiresAt,
        NOW.minusSeconds(60),
        NOW.minusSeconds(30));
  }

  private static Resource resource(SnapshotPolicy policy) {
    List<Participant> participants =
        policy == SnapshotPolicy.COORDINATED_QUIESCE
            ? List.of(new Participant("catalog-writer", URI.create("https://example.test")))
            : List.of();
    return new Resource(
        "catalog",
        policy,
        Set.of("products"),
        participants,
        null,
        NOW.minusSeconds(60),
        NOW.minusSeconds(60));
  }

  private static void assertError(ErrorCode expected, ThrowingCallable invocation) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(expected));
  }
}
