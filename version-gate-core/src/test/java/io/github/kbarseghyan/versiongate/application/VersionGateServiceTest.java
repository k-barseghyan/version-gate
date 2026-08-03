package io.github.kbarseghyan.versiongate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import io.github.kbarseghyan.versiongate.port.ParticipantGateway;
import io.github.kbarseghyan.versiongate.port.SnapshotStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class VersionGateServiceTest {

  private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");

  private ControlStore controlStore;
  private SnapshotStore snapshotStore;
  private ParticipantGateway participantGateway;
  private VersionGateService service;

  @BeforeEach
  void setUp() {
    controlStore = mock(ControlStore.class);
    snapshotStore = mock(SnapshotStore.class);
    participantGateway = mock(ParticipantGateway.class);
    service =
        new VersionGateService(
            controlStore,
            snapshotStore,
            participantGateway,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofHours(1),
            1024 * 1024);
  }

  @Test
  void staleTokenCannotReachSnapshotStorage() {
    Build build = build(BuildStatus.SNAPSHOTTING);
    when(controlStore.findBuild(build.buildId())).thenReturn(Optional.of(build));

    VersionGateService.SubmitComponentCommand command =
        componentCommand(build, build.fencingToken() - 1, InputStream.nullInputStream());

    assertThatThrownBy(() -> service.submitSnapshotComponent(command))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.STALE_FENCING_TOKEN));
    verifyNoInteractions(snapshotStore);
  }

  @Test
  void malformedExpectedChecksumIsRejectedBeforeThePriorComponentBranch() {
    Build build = build(BuildStatus.SNAPSHOTTING);
    SnapshotComponent prior = component(build, "products", "a".repeat(64));
    when(controlStore.findBuild(build.buildId())).thenReturn(Optional.of(build));
    when(controlStore.findResource(build.resourceId()))
        .thenReturn(Optional.of(resource(Set.of("products"))));
    when(controlStore.findSnapshotComponent(build.resourceId(), build.targetVersion(), "products"))
        .thenReturn(Optional.of(prior));

    assertValidationFailure(
        () ->
            service.submitSnapshotComponent(
                new VersionGateService.SubmitComponentCommand(
                    build.buildId(),
                    build.fencingToken(),
                    "products",
                    InputStream.nullInputStream(),
                    3,
                    "application/octet-stream",
                    Optional.empty(),
                    Optional.of("definitely-not-a-sha-256"),
                    Optional.empty(),
                    Optional.of(NOW))));

    verifyNoInteractions(controlStore);
    verifyNoInteractions(snapshotStore);
  }

  @Test
  void invalidSnapshotBoundaryValuesAreRejectedBeforeStoresAreCalled() {
    Build build = build(BuildStatus.SNAPSHOTTING);
    List<VersionGateService.SubmitComponentCommand> invalidCommands =
        List.of(
            submitCommand("/", 3, Optional.empty(), Optional.empty()),
            submitCommand("x".repeat(129), 3, Optional.empty(), Optional.empty()),
            submitCommand("products", -1, Optional.empty(), Optional.empty()),
            submitCommand("products", 3, Optional.of(" "), Optional.empty()),
            submitCommand("products", 3, Optional.of("x".repeat(256)), Optional.empty()),
            submitCommand("products", 3, Optional.empty(), Optional.of(" ")),
            submitCommand("products", 3, Optional.empty(), Optional.of("x".repeat(256))));

    invalidCommands.forEach(
        command -> assertValidationFailure(() -> service.submitSnapshotComponent(command)));

    verifyNoInteractions(controlStore);
    verifyNoInteractions(snapshotStore);
  }

  @Test
  void registrationAndOwnerLimitsAreRejectedBeforeControlStorage() {
    assertValidationFailure(
        () ->
            service.registerResource(
                new VersionGateService.RegisterResourceCommand(
                    "x".repeat(129),
                    SnapshotPolicy.CLIENT_MANAGED,
                    Set.of("products"),
                    List.of())));
    assertValidationFailure(
        () ->
            service.registerResource(
                new VersionGateService.RegisterResourceCommand(
                    "catalog", SnapshotPolicy.CLIENT_MANAGED, Set.of("x".repeat(129)), List.of())));
    assertValidationFailure(
        () ->
            service.beginBuild(
                new VersionGateService.BeginBuildCommand(
                    "catalog", "x".repeat(256), Duration.ofMinutes(5))));

    verifyNoInteractions(controlStore);
    verifyNoInteractions(snapshotStore);
    verifyNoInteractions(participantGateway);
  }

  @Test
  void coordinatedRegistrationRespectsTheConfiguredParticipantLimit() {
    VersionGateService boundedService =
        new VersionGateService(
            controlStore,
            snapshotStore,
            participantGateway,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofHours(1),
            1024 * 1024,
            1);
    List<Participant> participants = List.of(participant("database"), participant("cache"));

    assertValidationFailure(
        () ->
            boundedService.registerResource(
                new VersionGateService.RegisterResourceCommand(
                    "catalog",
                    SnapshotPolicy.COORDINATED_QUIESCE,
                    Set.of("products"),
                    participants)));

    verifyNoInteractions(controlStore);
    verifyNoInteractions(snapshotStore);
    verifyNoInteractions(participantGateway);
  }

  @Test
  void completionReportsAllMissingRequiredComponentsBeforeTransition() {
    Build build = build(BuildStatus.SNAPSHOTTING);
    Resource resource = resource(Set.of("products", "prices"));
    SnapshotComponent products = component(build, "products", "a".repeat(64));
    when(controlStore.findBuild(build.buildId())).thenReturn(Optional.of(build));
    when(controlStore.findResource(build.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.findSnapshotComponents(build.resourceId(), build.targetVersion()))
        .thenReturn(List.of(products));

    assertThatThrownBy(
            () ->
                service.completeBuild(
                    new VersionGateService.BuildTokenCommand(
                        build.buildId(), build.fencingToken())))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.INCOMPLETE_SNAPSHOT);
              assertThat(exception.details().get("missingComponentIds"))
                  .isEqualTo(Set.of("prices"));
            });
    verify(controlStore, never()).completeBuild(any(), anyLong());
    verifyNoInteractions(snapshotStore);
  }

  @Test
  void sameChecksumSubmissionReplaysThroughTheAuthoritativeSnapshotStore() {
    Build build = build(BuildStatus.SNAPSHOTTING);
    SnapshotComponent prior = component(build, "products", "a".repeat(64));
    Resource resource = resource(Set.of("products"));
    when(controlStore.findBuild(build.buildId())).thenReturn(Optional.of(build));
    when(controlStore.findResource(build.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.findSnapshotComponent(build.resourceId(), build.targetVersion(), "products"))
        .thenReturn(Optional.of(prior));
    when(snapshotStore.uploadImmutable(any()))
        .thenReturn(
            new SnapshotStore.StoredObject(
                new SnapshotStore.ObjectReference(prior.objectKey(), prior.sha256(), prior.size()),
                true));

    SnapshotComponent result =
        service.submitSnapshotComponent(
            componentCommand(
                build, build.fencingToken(), new ByteArrayInputStream(new byte[] {1, 2, 3})));

    assertThat(result).isSameAs(prior);
    verify(snapshotStore).uploadImmutable(any());
    verify(controlStore, never()).registerSnapshotComponent(any(), anyLong(), any());
  }

  @Test
  void sameChecksumSubmissionRepairsADeletedObject() {
    Build build = build(BuildStatus.SNAPSHOTTING);
    SnapshotComponent prior = component(build, "products", "a".repeat(64));
    Resource resource = resource(Set.of("products"));
    when(controlStore.findBuild(build.buildId())).thenReturn(Optional.of(build));
    when(controlStore.findResource(build.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.findSnapshotComponent(build.resourceId(), build.targetVersion(), "products"))
        .thenReturn(Optional.of(prior));
    when(snapshotStore.uploadImmutable(any()))
        .thenReturn(
            new SnapshotStore.StoredObject(
                new SnapshotStore.ObjectReference(prior.objectKey(), prior.sha256(), prior.size()),
                false));
    SnapshotComponent result =
        service.submitSnapshotComponent(
            componentCommand(
                build, build.fencingToken(), new ByteArrayInputStream(new byte[] {1, 2, 3})));

    assertThat(result).isSameAs(prior);
    verify(snapshotStore).uploadImmutable(any());
  }

  @Test
  void priorComponentWithDifferentContentTypeConflictsBeforeSnapshotStorage() {
    Build build = build(BuildStatus.SNAPSHOTTING);
    SnapshotComponent prior = component(build, "products", "a".repeat(64));
    Resource resource = resource(Set.of("products"));
    when(controlStore.findBuild(build.buildId())).thenReturn(Optional.of(build));
    when(controlStore.findResource(build.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.findSnapshotComponent(build.resourceId(), build.targetVersion(), "products"))
        .thenReturn(Optional.of(prior));

    VersionGateService.SubmitComponentCommand command =
        new VersionGateService.SubmitComponentCommand(
            build.buildId(),
            build.fencingToken(),
            "products",
            new ByteArrayInputStream(new byte[] {1, 2, 3}),
            3,
            "application/json",
            Optional.empty(),
            Optional.of(prior.sha256()),
            Optional.empty(),
            Optional.of(NOW));

    assertThatThrownBy(() -> service.submitSnapshotComponent(command))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.COMPONENT_CONFLICT));
    verifyNoInteractions(snapshotStore);
    verify(controlStore, never()).registerSnapshotComponent(any(), anyLong(), any());
  }

  @Test
  void priorComponentWithDifferentContentEncodingConflictsBeforeSnapshotStorage() {
    Build build = build(BuildStatus.SNAPSHOTTING);
    SnapshotComponent prior = component(build, "products", "a".repeat(64));
    Resource resource = resource(Set.of("products"));
    when(controlStore.findBuild(build.buildId())).thenReturn(Optional.of(build));
    when(controlStore.findResource(build.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.findSnapshotComponent(build.resourceId(), build.targetVersion(), "products"))
        .thenReturn(Optional.of(prior));

    VersionGateService.SubmitComponentCommand command =
        new VersionGateService.SubmitComponentCommand(
            build.buildId(),
            build.fencingToken(),
            "products",
            new ByteArrayInputStream(new byte[] {1, 2, 3}),
            3,
            prior.contentType(),
            Optional.of("gzip"),
            Optional.of(prior.sha256()),
            Optional.empty(),
            Optional.of(NOW));

    assertThatThrownBy(() -> service.submitSnapshotComponent(command))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.COMPONENT_CONFLICT));
    verifyNoInteractions(snapshotStore);
    verify(controlStore, never()).registerSnapshotComponent(any(), anyLong(), any());
  }

  @Test
  void readyCompletionReplayUsesStoredManifestWithoutRecheckingObjects() {
    Build build = build(BuildStatus.READY);
    Resource resource = resource(Set.of("products"));
    var manifest =
        new io.github.kbarseghyan.versiongate.domain.VersionManifest(
            build.resourceId(),
            build.targetVersion(),
            build.buildId(),
            build.baseActiveVersion(),
            NOW,
            List.of(component(build, "products", "a".repeat(64))));
    when(controlStore.findBuild(build.buildId())).thenReturn(Optional.of(build));
    when(controlStore.findResource(build.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.completeBuild(build.buildId(), build.fencingToken())).thenReturn(manifest);

    assertThat(
            service.completeBuild(
                new VersionGateService.BuildTokenCommand(build.buildId(), build.fencingToken())))
        .isSameAs(manifest);
    verifyNoInteractions(snapshotStore);
  }

  @Test
  void componentDownloadIsHiddenUntilItsVersionWasActivated() {
    Build build = build(BuildStatus.READY);
    when(controlStore.findResource(build.resourceId()))
        .thenReturn(Optional.of(resource(Set.of("products"))));
    when(controlStore.findVersionManifest(build.resourceId(), build.targetVersion()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.streamSnapshotComponent(
                    build.resourceId(), build.targetVersion(), "products"))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VERSION_NOT_FOUND));
    verify(controlStore, never()).findSnapshotComponent(any(), anyLong(), any());
    verifyNoInteractions(snapshotStore);
  }

  @Test
  void coordinatedFlowQuiescesCapturesFinalizesResumesThenActivates() {
    Build building = build(BuildStatus.BUILDING);
    Build quiescing = build(BuildStatus.QUIESCING);
    Build snapshotting = build(BuildStatus.SNAPSHOTTING);
    Build ready = build(BuildStatus.READY);
    Build active = build(BuildStatus.ACTIVE);
    Participant database = participant("database");
    Participant cache = participant("cache");
    Resource resource = coordinatedResource(List.of(database, cache));
    SnapshotComponent component = component(snapshotting, "products", "a".repeat(64));
    VersionManifest manifest = manifest(ready, component);

    when(controlStore.findBuild(building.buildId()))
        .thenReturn(Optional.of(building))
        .thenReturn(Optional.of(snapshotting))
        .thenReturn(Optional.of(ready));
    when(controlStore.findResource(building.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.startSnapshotPhase(
            building.buildId(), building.fencingToken(), BuildStatus.QUIESCING))
        .thenReturn(quiescing);
    when(controlStore.markSnapshotting(building.buildId(), building.fencingToken()))
        .thenReturn(snapshotting);
    when(controlStore.findParticipantStates(building.buildId()))
        .thenReturn(
            List.of(
                participantState(building, database, ParticipantStatus.PENDING),
                participantState(building, cache, ParticipantStatus.PENDING)))
        .thenReturn(
            List.of(
                participantState(building, database, ParticipantStatus.QUIESCED),
                participantState(building, cache, ParticipantStatus.QUIESCED)))
        .thenReturn(
            List.of(
                participantState(building, database, ParticipantStatus.CAPTURE_REQUESTED),
                participantState(building, cache, ParticipantStatus.CAPTURE_REQUESTED)))
        .thenReturn(
            List.of(
                participantState(building, database, ParticipantStatus.CAPTURE_REQUESTED),
                participantState(building, cache, ParticipantStatus.CAPTURE_REQUESTED)))
        .thenReturn(
            List.of(
                participantState(building, database, ParticipantStatus.RESUMED),
                participantState(building, cache, ParticipantStatus.RESUMED)));
    when(controlStore.findSnapshotComponents(building.resourceId(), building.targetVersion()))
        .thenReturn(List.of(component));
    when(controlStore.completeBuild(building.buildId(), building.fencingToken()))
        .thenReturn(manifest);
    when(controlStore.activateBuild(building.buildId(), building.fencingToken()))
        .thenReturn(active);

    assertThat(
            service.startSnapshotPhase(
                new VersionGateService.BuildTokenCommand(
                    building.buildId(), building.fencingToken())))
        .isSameAs(snapshotting);
    assertThat(
            service.completeBuild(
                new VersionGateService.BuildTokenCommand(
                    building.buildId(), building.fencingToken())))
        .isSameAs(manifest);
    assertThat(
            service.activateBuild(
                new VersionGateService.BuildTokenCommand(
                    building.buildId(), building.fencingToken())))
        .isSameAs(active);

    SnapshotStore.ObjectReference reference =
        new SnapshotStore.ObjectReference(
            component.objectKey(), component.sha256(), component.size());
    InOrder order = inOrder(controlStore, participantGateway, snapshotStore);
    order
        .verify(controlStore)
        .startSnapshotPhase(building.buildId(), building.fencingToken(), BuildStatus.QUIESCING);
    order
        .verify(participantGateway)
        .quiesce(database, new ParticipantGateway.CallbackContext(quiescing));
    order
        .verify(controlStore)
        .updateParticipantState(
            building.buildId(), database.participantId(), ParticipantStatus.QUIESCED, null);
    order
        .verify(participantGateway)
        .quiesce(cache, new ParticipantGateway.CallbackContext(quiescing));
    order
        .verify(controlStore)
        .updateParticipantState(
            building.buildId(), cache.participantId(), ParticipantStatus.QUIESCED, null);
    order.verify(controlStore).markSnapshotting(building.buildId(), building.fencingToken());
    order
        .verify(participantGateway)
        .capture(database, new ParticipantGateway.CallbackContext(snapshotting));
    order
        .verify(controlStore)
        .updateParticipantState(
            building.buildId(),
            database.participantId(),
            ParticipantStatus.CAPTURE_REQUESTED,
            null);
    order
        .verify(participantGateway)
        .capture(cache, new ParticipantGateway.CallbackContext(snapshotting));
    order
        .verify(controlStore)
        .updateParticipantState(
            building.buildId(), cache.participantId(), ParticipantStatus.CAPTURE_REQUESTED, null);
    order.verify(snapshotStore).verify(reference);
    order.verify(controlStore).completeBuild(building.buildId(), building.fencingToken());
    order
        .verify(participantGateway)
        .resume(database, new ParticipantGateway.CallbackContext(snapshotting));
    order
        .verify(controlStore)
        .updateParticipantState(
            building.buildId(), database.participantId(), ParticipantStatus.RESUMED, null);
    order
        .verify(participantGateway)
        .resume(cache, new ParticipantGateway.CallbackContext(snapshotting));
    order
        .verify(controlStore)
        .updateParticipantState(
            building.buildId(), cache.participantId(), ParticipantStatus.RESUMED, null);
    order.verify(snapshotStore).verify(reference);
    order.verify(controlStore).activateBuild(building.buildId(), building.fencingToken());
  }

  @Test
  void coordinatedActivationRequiresEveryParticipantToHaveResumed() {
    Build ready = build(BuildStatus.READY);
    Participant database = participant("database");
    Participant cache = participant("cache");
    Resource resource = coordinatedResource(List.of(database, cache));
    when(controlStore.findBuild(ready.buildId())).thenReturn(Optional.of(ready));
    when(controlStore.findResource(ready.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.findParticipantStates(ready.buildId()))
        .thenReturn(
            List.of(
                participantState(ready, database, ParticipantStatus.RESUMED),
                participantState(ready, cache, ParticipantStatus.CAPTURE_REQUESTED)));

    assertThatThrownBy(
            () ->
                service.activateBuild(
                    new VersionGateService.BuildTokenCommand(
                        ready.buildId(), ready.fencingToken())))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.PARTICIPANT_FAILURE);
              assertThat(exception.details().get("participantIds"))
                  .isEqualTo(Set.of(cache.participantId()));
            });

    verify(controlStore, never()).activateBuild(any(), anyLong());
    verifyNoInteractions(snapshotStore);
  }

  @Test
  void coordinatedCallbackFailureTerminalizesBuildBeforeAbortCallbacks() {
    Build building = build(BuildStatus.BUILDING);
    Build quiescing = build(BuildStatus.QUIESCING);
    Build failed = build(BuildStatus.FAILED);
    Participant database = participant("database");
    Resource resource = coordinatedResource(List.of(database));
    ParticipantState pending = participantState(building, database, ParticipantStatus.PENDING);
    when(controlStore.findBuild(building.buildId())).thenReturn(Optional.of(building));
    when(controlStore.findResource(building.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.startSnapshotPhase(
            building.buildId(), building.fencingToken(), BuildStatus.QUIESCING))
        .thenReturn(quiescing);
    when(controlStore.findParticipantStates(building.buildId()))
        .thenReturn(List.of(pending))
        .thenReturn(List.of(pending));
    when(controlStore.failBuild(
            building.buildId(), building.fencingToken(), "Participant coordination failed"))
        .thenReturn(failed);
    doThrow(new IllegalStateException("participant unavailable"))
        .when(participantGateway)
        .quiesce(database, new ParticipantGateway.CallbackContext(quiescing));

    assertThatThrownBy(
            () ->
                service.startSnapshotPhase(
                    new VersionGateService.BuildTokenCommand(
                        building.buildId(), building.fencingToken())))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.PARTICIPANT_FAILURE));

    InOrder order = inOrder(controlStore, participantGateway);
    order
        .verify(participantGateway)
        .quiesce(database, new ParticipantGateway.CallbackContext(quiescing));
    order
        .verify(controlStore)
        .failBuild(building.buildId(), building.fencingToken(), "Participant coordination failed");
    order
        .verify(participantGateway)
        .abort(database, new ParticipantGateway.CallbackContext(failed));
    order
        .verify(controlStore)
        .updateParticipantState(
            building.buildId(), database.participantId(), ParticipantStatus.ABORTED, null);
    verify(controlStore, never()).markSnapshotting(any(), anyLong());
    verify(participantGateway, never()).capture(any(), any());
  }

  @Test
  void coordinatedParticipantStateIncoherenceFailsClosed() {
    Build ready = build(BuildStatus.READY);
    Participant database = participant("database");
    Participant cache = participant("cache");
    Resource resource = coordinatedResource(List.of(database, cache));
    ParticipantState databaseResumed = participantState(ready, database, ParticipantStatus.RESUMED);
    ParticipantState cacheResumed = participantState(ready, cache, ParticipantStatus.RESUMED);
    ParticipantState wrongBuild =
        participantState(
            build(UUID.fromString("c12d1a25-b857-4107-a218-f8f43c019ceb"), BuildStatus.READY),
            database,
            ParticipantStatus.RESUMED);
    List<IncoherentParticipantStates> incoherentStates =
        List.of(
            new IncoherentParticipantStates("missing", List.of(databaseResumed)),
            new IncoherentParticipantStates(
                "duplicate", List.of(databaseResumed, databaseResumed, cacheResumed)),
            new IncoherentParticipantStates("wrong build", List.of(wrongBuild, cacheResumed)));
    when(controlStore.findBuild(ready.buildId())).thenReturn(Optional.of(ready));
    when(controlStore.findResource(ready.resourceId())).thenReturn(Optional.of(resource));

    for (IncoherentParticipantStates incoherent : incoherentStates) {
      when(controlStore.findParticipantStates(ready.buildId())).thenReturn(incoherent.states());

      assertThatThrownBy(
              () ->
                  service.activateBuild(
                      new VersionGateService.BuildTokenCommand(
                          ready.buildId(), ready.fencingToken())))
          .as(incoherent.description())
          .isInstanceOfSatisfying(
              VersionGateException.class,
              exception -> assertThat(exception.code()).isEqualTo(ErrorCode.STORAGE_FAILURE));
    }

    verify(controlStore, never()).activateBuild(any(), anyLong());
    verifyNoInteractions(snapshotStore);
  }

  @Test
  void completionReplayRetriesOnlyParticipantsWhoseResumeDidNotSucceed() {
    Build ready = build(BuildStatus.READY);
    Participant database = participant("database");
    Participant cache = participant("cache");
    Resource resource = coordinatedResource(List.of(database, cache));
    SnapshotComponent component = component(ready, "products", "a".repeat(64));
    VersionManifest manifest = manifest(ready, component);
    when(controlStore.findBuild(ready.buildId())).thenReturn(Optional.of(ready));
    when(controlStore.findResource(ready.resourceId())).thenReturn(Optional.of(resource));
    when(controlStore.completeBuild(ready.buildId(), ready.fencingToken())).thenReturn(manifest);
    when(controlStore.findParticipantStates(ready.buildId()))
        .thenReturn(
            List.of(
                participantState(ready, database, ParticipantStatus.CAPTURE_REQUESTED),
                participantState(ready, cache, ParticipantStatus.CAPTURE_REQUESTED)))
        .thenReturn(
            List.of(
                participantState(ready, database, ParticipantStatus.RESUMED),
                participantState(ready, cache, ParticipantStatus.FAILED)));
    doThrow(new IllegalStateException("resume unavailable"))
        .doNothing()
        .when(participantGateway)
        .resume(cache, new ParticipantGateway.CallbackContext(ready));

    VersionGateService.BuildTokenCommand command =
        new VersionGateService.BuildTokenCommand(ready.buildId(), ready.fencingToken());
    assertThat(service.completeBuild(command)).isSameAs(manifest);
    assertThat(service.completeBuild(command)).isSameAs(manifest);

    verify(controlStore, times(2)).completeBuild(ready.buildId(), ready.fencingToken());
    verify(participantGateway).resume(database, new ParticipantGateway.CallbackContext(ready));
    verify(participantGateway, times(2))
        .resume(cache, new ParticipantGateway.CallbackContext(ready));
    verify(controlStore)
        .updateParticipantState(
            ready.buildId(),
            cache.participantId(),
            ParticipantStatus.FAILED,
            "Resume callback failed");
    verify(controlStore)
        .updateParticipantState(
            ready.buildId(), cache.participantId(), ParticipantStatus.RESUMED, null);
  }

  private static Build build(BuildStatus status) {
    return build(UUID.fromString("4dd965e8-4eb5-4bb1-bc58-bd95981f57f4"), status);
  }

  private static Build build(UUID buildId, BuildStatus status) {
    return new Build(
        buildId,
        "catalog",
        1,
        null,
        status,
        "test-owner",
        17,
        NOW.plusSeconds(300),
        NOW.minusSeconds(10),
        NOW);
  }

  private static Resource resource(Set<String> requiredComponents) {
    return new Resource(
        "catalog",
        SnapshotPolicy.CLIENT_MANAGED,
        requiredComponents,
        List.of(),
        null,
        NOW.minusSeconds(20),
        NOW);
  }

  private static Resource coordinatedResource(List<Participant> participants) {
    return new Resource(
        "catalog",
        SnapshotPolicy.COORDINATED_QUIESCE,
        Set.of("products"),
        participants,
        null,
        NOW.minusSeconds(20),
        NOW);
  }

  private static Participant participant(String participantId) {
    return new Participant(
        participantId, URI.create("https://" + participantId + ".example.test/callbacks"));
  }

  private static ParticipantState participantState(
      Build build, Participant participant, ParticipantStatus status) {
    return new ParticipantState(
        build.buildId(), participant.participantId(), status, Optional.empty(), NOW);
  }

  private static VersionManifest manifest(Build build, SnapshotComponent component) {
    return new VersionManifest(
        build.resourceId(),
        build.targetVersion(),
        build.buildId(),
        build.baseActiveVersion(),
        NOW,
        List.of(component));
  }

  private static SnapshotComponent component(Build build, String componentId, String sha256) {
    return new SnapshotComponent(
        build.buildId(),
        build.resourceId(),
        build.targetVersion(),
        componentId,
        "snapshots/catalog/1/" + componentId,
        "application/octet-stream",
        Optional.empty(),
        sha256,
        3,
        Optional.empty(),
        NOW);
  }

  private static VersionGateService.SubmitComponentCommand componentCommand(
      Build build, long fencingToken, InputStream inputStream) {
    return new VersionGateService.SubmitComponentCommand(
        build.buildId(),
        fencingToken,
        "products",
        inputStream,
        3,
        "application/octet-stream",
        Optional.empty(),
        Optional.of("a".repeat(64)),
        Optional.empty(),
        Optional.of(NOW));
  }

  private static VersionGateService.SubmitComponentCommand submitCommand(
      String componentId,
      long contentLength,
      Optional<String> contentEncoding,
      Optional<String> schemaVersion) {
    return new VersionGateService.SubmitComponentCommand(
        UUID.fromString("4dd965e8-4eb5-4bb1-bc58-bd95981f57f4"),
        17,
        componentId,
        InputStream.nullInputStream(),
        contentLength,
        "application/octet-stream",
        contentEncoding,
        Optional.empty(),
        schemaVersion,
        Optional.of(NOW));
  }

  private static void assertValidationFailure(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  private record IncoherentParticipantStates(String description, List<ParticipantState> states) {}
}
