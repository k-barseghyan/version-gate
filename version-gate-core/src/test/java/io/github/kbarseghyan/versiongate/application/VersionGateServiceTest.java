package io.github.kbarseghyan.versiongate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
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
import io.github.kbarseghyan.versiongate.domain.WriteSession;
import io.github.kbarseghyan.versiongate.domain.WriteStatus;
import io.github.kbarseghyan.versiongate.domain.WriterDuringSnapshotPolicy;
import io.github.kbarseghyan.versiongate.port.VersionGateStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VersionGateServiceTest {

  private static final Duration MAXIMUM_LEASE = Duration.ofHours(1);
  private static final long MAXIMUM_SNAPSHOT_SIZE = 1024;
  private static final UUID SESSION_ID = UUID.fromString("4dd965e8-4b5b-4bb1-bc58-bd95981f57f4");
  private static final long FENCING_TOKEN = 17;
  private static final String IDEMPOTENCY_KEY = "request-1";
  private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");

  private VersionGateStore store;
  private VersionGateService service;

  @BeforeEach
  void setUp() {
    store = mock(VersionGateStore.class);
    service = new VersionGateService(store, MAXIMUM_LEASE, MAXIMUM_SNAPSHOT_SIZE);
  }

  @Test
  void constructorRejectsInvalidLimits() {
    assertThatThrownBy(() -> new VersionGateService(store, Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VersionGateService(store, Duration.ofSeconds(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void registrationValidatesThenDelegatesTheCompleteImmutablePolicySet() {
    ResourcePolicies policies = disabledPolicies();
    Resource resource = resource(policies);
    when(store.registerResource("catalog", policies)).thenReturn(resource);

    assertThat(
            service.registerResource(
                new VersionGateService.RegisterResourceCommand("catalog", policies)))
        .isSameAs(resource);

    verify(store).registerResource("catalog", policies);
    verifyNoMoreInteractions(store);
  }

  @Test
  void invalidRegistrationDoesNotReachStorage() {
    assertValidationFailure(
        () ->
            service.registerResource(
                new VersionGateService.RegisterResourceCommand("not/a/resource", null)));

    verifyNoInteractions(store);
  }

  @Test
  void getResourceMapsAnAbsentRegistrationAndReturnsAPresentOne() {
    Resource resource = resource(disabledPolicies());
    when(store.findResource("catalog")).thenReturn(Optional.of(resource));
    when(store.findResource("missing")).thenReturn(Optional.empty());

    assertThat(service.getResource("catalog")).isSameAs(resource);
    assertThatThrownBy(() -> service.getResource("missing"))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
  }

  @Test
  void sessionLookupsReturnCurrentStateAndMapMissingSessions() {
    UUID writeId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID readId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID snapshotId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    UUID missingId = UUID.fromString("00000000-0000-0000-0000-000000000004");
    Instant leaseExpiresAt = NOW.plus(Duration.ofMinutes(5));
    WriteSession write =
        new WriteSession(
            writeId,
            "catalog",
            1,
            null,
            WriteStatus.WRITING,
            "writer",
            1,
            leaseExpiresAt,
            Optional.empty(),
            NOW,
            NOW);
    LiveReadSession read =
        new LiveReadSession(
            readId, "catalog", 1, LiveReadStatus.READING, "reader", 2, leaseExpiresAt, NOW, NOW);
    SnapshotGenerationSession snapshot =
        new SnapshotGenerationSession(
            snapshotId,
            "catalog",
            1,
            SnapshotGenerationStatus.GENERATING,
            "snapshotter",
            3,
            leaseExpiresAt,
            NOW,
            NOW);
    when(store.findWriteSession(writeId)).thenReturn(Optional.of(write));
    when(store.findLiveReadSession(readId)).thenReturn(Optional.of(read));
    when(store.findSnapshotSession(snapshotId)).thenReturn(Optional.of(snapshot));
    when(store.findWriteSession(missingId)).thenReturn(Optional.empty());

    assertThat(service.getWriteSession(writeId)).isSameAs(write);
    assertThat(service.getLiveReadSession(readId)).isSameAs(read);
    assertThat(service.getSnapshotSession(snapshotId)).isSameAs(snapshot);
    assertThatThrownBy(() -> service.getWriteSession(missingId))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.WRITE_SESSION_NOT_FOUND));
    assertValidationFailure(() -> service.getLiveReadSession(null));
  }

  @Test
  void beginOperationsDelegateWithoutApplicationLifecyclePrechecks() {
    Duration lease = Duration.ofMinutes(5);

    service.beginWrite(
        new VersionGateService.BeginWriteCommand("catalog", "writer", lease, IDEMPOTENCY_KEY));
    service.beginLiveRead(
        new VersionGateService.BeginLiveReadCommand("catalog", "reader", lease, IDEMPOTENCY_KEY));
    service.beginSnapshot(
        new VersionGateService.BeginSnapshotCommand(
            "catalog", "snapshotter", lease, IDEMPOTENCY_KEY));

    verify(store).beginWrite("catalog", "writer", lease, IDEMPOTENCY_KEY);
    verify(store).beginLiveRead("catalog", "reader", lease, IDEMPOTENCY_KEY);
    verify(store).beginSnapshot("catalog", "snapshotter", lease, IDEMPOTENCY_KEY);
    verifyNoMoreInteractions(store);
  }

  @Test
  void beginOperationsRejectInvalidIdentifiersOwnersAndLeases() {
    List<VersionGateService.BeginWriteCommand> invalid =
        List.of(
            new VersionGateService.BeginWriteCommand(
                "bad/id", "owner", Duration.ofMinutes(1), IDEMPOTENCY_KEY),
            new VersionGateService.BeginWriteCommand(
                "catalog", " ", Duration.ofMinutes(1), IDEMPOTENCY_KEY),
            new VersionGateService.BeginWriteCommand(
                "catalog", "x".repeat(256), Duration.ofMinutes(1), IDEMPOTENCY_KEY),
            new VersionGateService.BeginWriteCommand(
                "catalog", "owner", Duration.ZERO, IDEMPOTENCY_KEY),
            new VersionGateService.BeginWriteCommand(
                "catalog", "owner", MAXIMUM_LEASE.plusNanos(1), IDEMPOTENCY_KEY),
            new VersionGateService.BeginWriteCommand(
                "catalog", "owner", Duration.ofMinutes(1), "bad key"),
            new VersionGateService.BeginWriteCommand(
                "catalog", "owner", Duration.ofMinutes(1), "x".repeat(129)));

    invalid.forEach(command -> assertValidationFailure(() -> service.beginWrite(command)));
    assertValidationFailure(() -> service.beginLiveRead(null));
    assertValidationFailure(() -> service.beginSnapshot(null));

    verifyNoInteractions(store);
  }

  @Test
  void writeMutationsDelegateDirectlyWithoutFindingOrCheckingTheSession() {
    Duration lease = Duration.ofMinutes(10);
    VersionGateService.SessionCommand session =
        new VersionGateService.SessionCommand(SESSION_ID, FENCING_TOKEN);
    VersionGateService.RenewSessionCommand renewal =
        new VersionGateService.RenewSessionCommand(SESSION_ID, FENCING_TOKEN, lease);

    service.renewWrite(renewal);
    service.completeWrite(session);
    service.failWrite(
        new VersionGateService.FailWriteCommand(SESSION_ID, FENCING_TOKEN, "upstream failed"));
    service.abandonWrite(session);

    verify(store).renewWrite(SESSION_ID, FENCING_TOKEN, lease);
    verify(store).completeWrite(SESSION_ID, FENCING_TOKEN);
    verify(store).failWrite(SESSION_ID, FENCING_TOKEN, "upstream failed");
    verify(store).abandonWrite(SESSION_ID, FENCING_TOKEN);
    verify(store, never()).findWriteSession(any());
    verifyNoMoreInteractions(store);
  }

  @Test
  void liveReadMutationsDelegateDirectlyWithoutFindingOrCheckingTheSession() {
    Duration lease = Duration.ofMinutes(10);
    VersionGateService.SessionCommand session =
        new VersionGateService.SessionCommand(SESSION_ID, FENCING_TOKEN);
    VersionGateService.RenewSessionCommand renewal =
        new VersionGateService.RenewSessionCommand(SESSION_ID, FENCING_TOKEN, lease);

    service.renewLiveRead(renewal);
    service.completeLiveRead(session);
    service.abandonLiveRead(session);

    verify(store).renewLiveRead(SESSION_ID, FENCING_TOKEN, lease);
    verify(store).completeLiveRead(SESSION_ID, FENCING_TOKEN);
    verify(store).abandonLiveRead(SESSION_ID, FENCING_TOKEN);
    verify(store, never()).findLiveReadSession(any());
    verifyNoMoreInteractions(store);
  }

  @Test
  void snapshotMutationsDelegateDirectlyWithoutFindingOrCheckingTheSession() {
    Duration lease = Duration.ofMinutes(10);
    VersionGateService.SessionCommand session =
        new VersionGateService.SessionCommand(SESSION_ID, FENCING_TOKEN);
    VersionGateService.RenewSessionCommand renewal =
        new VersionGateService.RenewSessionCommand(SESSION_ID, FENCING_TOKEN, lease);

    service.renewSnapshot(renewal);
    service.abortSnapshot(session);

    verify(store).renewSnapshot(SESSION_ID, FENCING_TOKEN, lease);
    verify(store).abortSnapshot(SESSION_ID, FENCING_TOKEN);
    verify(store, never()).findSnapshotSession(any());
    verifyNoMoreInteractions(store);
  }

  @Test
  void sessionMutationsValidateFenceLeaseAndFailureReasonBeforeStorage() {
    assertValidationFailure(
        () -> service.completeWrite(new VersionGateService.SessionCommand(null, FENCING_TOKEN)));
    assertValidationFailure(
        () -> service.completeLiveRead(new VersionGateService.SessionCommand(SESSION_ID, 0)));
    assertValidationFailure(
        () ->
            service.renewSnapshot(
                new VersionGateService.RenewSessionCommand(
                    SESSION_ID, FENCING_TOKEN, MAXIMUM_LEASE.plusSeconds(1))));
    assertValidationFailure(
        () ->
            service.failWrite(
                new VersionGateService.FailWriteCommand(SESSION_ID, FENCING_TOKEN, " ")));
    assertValidationFailure(
        () ->
            service.failWrite(
                new VersionGateService.FailWriteCommand(
                    SESSION_ID, FENCING_TOKEN, "x".repeat(256))));

    verifyNoInteractions(store);
  }

  @Test
  void submitSnapshotAcceptsOpaqueContentAndDelegatesTheExactStreamAtTheMaximumSize() {
    InputStream inputStream = new ByteArrayInputStream(new byte[] {1, 2, 3});
    String uppercaseSha256 = "A".repeat(64);
    VersionGateService.SubmitSnapshotCommand command =
        new VersionGateService.SubmitSnapshotCommand(
            SESSION_ID,
            FENCING_TOKEN,
            inputStream,
            MAXIMUM_SNAPSHOT_SIZE,
            "application/vnd.example.arbitrary+binary",
            Optional.of("zstd"),
            Optional.of(uppercaseSha256));

    service.submitSnapshot(command);

    ArgumentCaptor<VersionGateStore.SnapshotUpload> uploadCaptor =
        ArgumentCaptor.forClass(VersionGateStore.SnapshotUpload.class);
    verify(store).submitSnapshot(eq(SESSION_ID), eq(FENCING_TOKEN), uploadCaptor.capture());
    VersionGateStore.SnapshotUpload upload = uploadCaptor.getValue();
    assertThat(upload.inputStream()).isSameAs(inputStream);
    assertThat(upload.contentLength()).isEqualTo(MAXIMUM_SNAPSHOT_SIZE);
    assertThat(upload.contentType()).isEqualTo("application/vnd.example.arbitrary+binary");
    assertThat(upload.contentEncoding()).contains("zstd");
    assertThat(upload.expectedSha256()).contains("a".repeat(64));
    verifyNoMoreInteractions(store);
  }

  @Test
  void submitSnapshotRejectsInvalidMetadataAndOversizedRepresentations() {
    List<VersionGateService.SubmitSnapshotCommand> invalid =
        List.of(
            snapshotCommand(InputStream.nullInputStream(), -1, "application/octet-stream"),
            snapshotCommand(
                InputStream.nullInputStream(),
                MAXIMUM_SNAPSHOT_SIZE + 1,
                "application/octet-stream"),
            snapshotCommand(InputStream.nullInputStream(), 0, " "),
            snapshotCommand(InputStream.nullInputStream(), 0, "x".repeat(256)),
            new VersionGateService.SubmitSnapshotCommand(
                SESSION_ID,
                FENCING_TOKEN,
                InputStream.nullInputStream(),
                0,
                "application/octet-stream",
                Optional.of(" "),
                Optional.empty()),
            new VersionGateService.SubmitSnapshotCommand(
                SESSION_ID,
                FENCING_TOKEN,
                InputStream.nullInputStream(),
                0,
                "application/octet-stream",
                Optional.of("x".repeat(256)),
                Optional.empty()),
            new VersionGateService.SubmitSnapshotCommand(
                SESSION_ID,
                FENCING_TOKEN,
                InputStream.nullInputStream(),
                0,
                "application/octet-stream",
                Optional.empty(),
                Optional.of("not-a-sha")));

    invalid.forEach(command -> assertValidationFailure(() -> service.submitSnapshot(command)));
    assertValidationFailure(
        () ->
            service.submitSnapshot(
                new VersionGateService.SubmitSnapshotCommand(
                    SESSION_ID,
                    FENCING_TOKEN,
                    null,
                    0,
                    "application/octet-stream",
                    Optional.empty(),
                    Optional.empty())));

    verifyNoInteractions(store);
  }

  @Test
  void explicitSnapshotRetrievalDelegatesEachSelectorAndByVersionIgnoresDefaults() {
    service.getSnapshotByVersion("catalog", 9);
    service.getCurrentSnapshot("catalog");
    service.getLatestAvailableSnapshot("catalog");

    verify(store).getSnapshot("catalog", SnapshotSelector.BY_VERSION, OptionalLong.of(9));
    verify(store).getSnapshot("catalog", SnapshotSelector.CURRENT, OptionalLong.empty());
    verify(store).getSnapshot("catalog", SnapshotSelector.LATEST_AVAILABLE, OptionalLong.empty());
    verifyNoMoreInteractions(store);
  }

  @Test
  void explicitSnapshotRetrievalRejectsInvalidInputsBeforeStorage() {
    assertValidationFailure(() -> service.getSnapshotByVersion("catalog", -1));
    assertValidationFailure(() -> service.getCurrentSnapshot("bad/id"));
    assertValidationFailure(() -> service.getLatestAvailableSnapshot(null));

    verifyNoInteractions(store);
  }

  @Test
  void defaultSnapshotRetrievalUsesTheImmutableConfiguredSelectorAndVersionShape() {
    Resource byVersion = resource(enabledPolicies(SnapshotSelector.BY_VERSION));
    Resource current = resource(enabledPolicies(SnapshotSelector.CURRENT));
    Resource latest = resource(enabledPolicies(SnapshotSelector.LATEST_AVAILABLE));
    when(store.findResource("by-version")).thenReturn(Optional.of(byVersion));
    when(store.findResource("current")).thenReturn(Optional.of(current));
    when(store.findResource("latest")).thenReturn(Optional.of(latest));

    service.getDefaultSnapshot("by-version", OptionalLong.of(8));
    service.getDefaultSnapshot("current", OptionalLong.empty());
    service.getDefaultSnapshot("latest", OptionalLong.empty());

    verify(store).getSnapshot("by-version", SnapshotSelector.BY_VERSION, OptionalLong.of(8));
    verify(store).getSnapshot("current", SnapshotSelector.CURRENT, OptionalLong.empty());
    verify(store).getSnapshot("latest", SnapshotSelector.LATEST_AVAILABLE, OptionalLong.empty());
  }

  @Test
  void defaultSnapshotRetrievalRejectsDisabledSupportAndMismatchedVersionShape() {
    when(store.findResource("disabled")).thenReturn(Optional.of(resource(disabledPolicies())));
    when(store.findResource("by-version"))
        .thenReturn(Optional.of(resource(enabledPolicies(SnapshotSelector.BY_VERSION))));
    when(store.findResource("current"))
        .thenReturn(Optional.of(resource(enabledPolicies(SnapshotSelector.CURRENT))));

    assertThatThrownBy(() -> service.getDefaultSnapshot("disabled", OptionalLong.empty()))
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception ->
                assertThat(exception.code()).isEqualTo(ErrorCode.SNAPSHOT_SUPPORT_DISABLED));
    assertValidationFailure(() -> service.getDefaultSnapshot("by-version", OptionalLong.empty()));
    assertValidationFailure(() -> service.getDefaultSnapshot("current", OptionalLong.of(7)));

    verify(store, never()).getSnapshot(any(), any(), any());
  }

  private static VersionGateService.SubmitSnapshotCommand snapshotCommand(
      InputStream inputStream, long contentLength, String contentType) {
    return new VersionGateService.SubmitSnapshotCommand(
        SESSION_ID,
        FENCING_TOKEN,
        inputStream,
        contentLength,
        contentType,
        Optional.empty(),
        Optional.empty());
  }

  private static Resource resource(ResourcePolicies policies) {
    return new Resource("catalog", policies, null, NOW, NOW);
  }

  private static ResourcePolicies disabledPolicies() {
    return new ResourcePolicies(
        SnapshotSupport.DISABLED,
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static ResourcePolicies enabledPolicies(SnapshotSelector selector) {
    return new ResourcePolicies(
        SnapshotSupport.ENABLED,
        MissingCurrentSnapshotPolicy.ALLOW_GAP,
        Optional.of(WriterDuringSnapshotPolicy.BLOCK_WRITER),
        Optional.of(selector),
        Optional.of(RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING));
  }

  private static void assertValidationFailure(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }
}
