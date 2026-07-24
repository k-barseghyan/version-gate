package io.github.kbarseghyan.versiongate.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.application.VersionGateService;
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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VersionGateControllerTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
  private static final UUID BUILD_ID = UUID.fromString("03ec5f06-2d80-4324-9a32-f57692c4fc1d");
  private static final String SHA_256 = "a".repeat(64);

  private VersionGateService service;
  private TestControlStore controlStore;
  private TestSnapshotStore snapshotStore;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    controlStore = new TestControlStore();
    snapshotStore = new TestSnapshotStore();
    service =
        new VersionGateService(
            controlStore,
            snapshotStore,
            new NoOpParticipantGateway(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofHours(1),
            1024 * 1024);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new VersionGateController(service))
            .setControllerAdvice(new VersionGateExceptionHandler())
            .build();
  }

  @Test
  void registersReadmeClientManagedRequestWithoutParticipants() throws Exception {
    mockMvc
        .perform(
            post("/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "resourceId": "catalog",
                      "snapshotPolicy": "CLIENT_MANAGED",
                      "requiredComponentIds": ["products", "prices"]
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/resources/catalog"))
        .andExpect(jsonPath("$.resourceId").value("catalog"));

    assertThat(controlStore.resource.participants()).isEmpty();
    assertThat(controlStore.resource.requiredComponentIds())
        .containsExactlyInAnyOrder("products", "prices");
  }

  @Test
  void registersCoordinatedResourceParticipants() throws Exception {
    mockMvc
        .perform(
            post("/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "resourceId": "orders",
                      "snapshotPolicy": "COORDINATED_QUIESCE",
                      "requiredComponentIds": ["orders"],
                      "participants": [
                        {
                          "participantId": "orders-database",
                          "baseUri": "https://orders.internal"
                        }
                      ]
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.participants[0].participantId").value("orders-database"));

    assertThat(controlStore.resource.snapshotPolicy())
        .isEqualTo(SnapshotPolicy.COORDINATED_QUIESCE);
    assertThat(controlStore.resource.participants())
        .containsExactly(new Participant("orders-database", URI.create("https://orders.internal")));
  }

  @Test
  void returnsStructuredBeanValidationErrors() throws Exception {
    mockMvc
        .perform(post("/resources").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors.length()").value(3));
  }

  @Test
  void mapsLeaseSecondsForBeginAndRenew() throws Exception {
    mockMvc
        .perform(
            post("/resources/catalog/builds")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "targetVersion": 5,
                      "owner": "catalog-publisher",
                      "leaseSeconds": 300
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string(HttpHeaders.LOCATION, "http://localhost/resources/catalog/builds/current"))
        .andExpect(jsonPath("$.targetVersion").value(5));

    assertThat(controlStore.lastLeaseDuration).isEqualTo(Duration.ofSeconds(300));

    mockMvc
        .perform(
            post("/builds/{buildId}/renew", BUILD_ID)
                .header(VersionGateController.FENCING_TOKEN_HEADER, 17)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"leaseSeconds\":120}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fencingToken").value(17));

    assertThat(controlStore.lastLeaseDuration).isEqualTo(Duration.ofSeconds(120));
    assertThat(controlStore.lastFencingToken).isEqualTo(17);
  }

  @Test
  void mapsStreamingUploadHeadersToApplicationCommand() throws Exception {
    byte[] payload = "{\"id\":1}\n".getBytes(StandardCharsets.UTF_8);
    controlStore.resource =
        new Resource(
            "catalog",
            SnapshotPolicy.CLIENT_MANAGED,
            Set.of("products"),
            List.of(),
            null,
            NOW,
            NOW);
    controlStore.build = build(BuildStatus.SNAPSHOTTING, 17);
    controlStore.snapshotComponent = null;

    mockMvc
        .perform(
            put("/builds/{buildId}/components/products", BUILD_ID)
                .header(VersionGateController.FENCING_TOKEN_HEADER, 17)
                .header(VersionGateController.CHECKSUM_HEADER, SHA_256)
                .header(VersionGateController.SCHEMA_VERSION_HEADER, "catalog/1")
                .header(VersionGateController.CAPTURED_AT_HEADER, NOW.toString())
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .header(HttpHeaders.CONTENT_LENGTH, payload.length)
                .contentType("application/x-ndjson; charset=UTF-8")
                .content(payload))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string(
                    HttpHeaders.LOCATION,
                    "http://localhost/resources/catalog/versions/1/components/products"))
        .andExpect(jsonPath("$.componentId").value("products"));

    assertThat(snapshotStore.upload).isNotNull();
    assertThat(snapshotStore.upload.contentLength()).isEqualTo(payload.length);
    assertThat(snapshotStore.upload.contentType()).isEqualTo("application/x-ndjson");
    assertThat(snapshotStore.upload.contentEncoding()).contains("gzip");
    assertThat(snapshotStore.upload.expectedSha256()).contains(SHA_256);
    assertThat(snapshotStore.uploadedBytes).containsExactly(payload);
    assertThat(controlStore.snapshotComponent.schemaVersion()).contains("catalog/1");
    assertThat(controlStore.snapshotComponent.capturedAt()).isEqualTo(NOW);
  }

  @Test
  void acceptsAnExplicitlyEmptyComponent() throws Exception {
    controlStore.build = build(BuildStatus.SNAPSHOTTING, 17);

    mockMvc
        .perform(
            put("/builds/{buildId}/components/products", BUILD_ID)
                .header(VersionGateController.FENCING_TOKEN_HEADER, 17)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(new byte[0]))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.size").value(0));

    assertThat(snapshotStore.upload.contentLength()).isZero();
    assertThat(snapshotStore.uploadedBytes).isEmpty();
  }

  @Test
  void rejectsUploadWithoutContentLengthAsProblemDetail() throws Exception {
    mockMvc
        .perform(
            put("/builds/{buildId}/components/products", BUILD_ID)
                .header(VersionGateController.FENCING_TOKEN_HEADER, 17)
                .contentType(MediaType.APPLICATION_OCTET_STREAM))
        .andExpect(status().isLengthRequired())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(411))
        .andExpect(jsonPath("$.code").value("CONTENT_LENGTH_REQUIRED"))
        .andExpect(jsonPath("$.instance").value("/builds/" + BUILD_ID + "/components/products"));
  }

  @Test
  void rejectsUnsupportedSnapshotContentType() throws Exception {
    mockMvc
        .perform(
            put("/builds/{buildId}/components/products", BUILD_ID)
                .header(VersionGateController.FENCING_TOKEN_HEADER, 17)
                .header(HttpHeaders.CONTENT_LENGTH, 1)
                .contentType(MediaType.TEXT_PLAIN)
                .content(new byte[] {0}))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT_TYPE"));
  }

  @Test
  void rejectsMalformedChecksumBeforeAnExistingComponentCanProduceAConflict() throws Exception {
    controlStore.resource =
        new Resource(
            "catalog",
            SnapshotPolicy.CLIENT_MANAGED,
            Set.of("products"),
            List.of(),
            null,
            NOW,
            NOW);
    controlStore.build = build(BuildStatus.SNAPSHOTTING, 17);
    controlStore.snapshotComponent =
        component(
            "catalog",
            1,
            "products",
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            Optional.empty(),
            Optional.empty(),
            3);

    mockMvc
        .perform(
            put("/builds/{buildId}/components/products", BUILD_ID)
                .header(VersionGateController.FENCING_TOKEN_HEADER, 17)
                .header(VersionGateController.CHECKSUM_HEADER, "not-a-sha-256")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(new byte[] {1, 2, 3}))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(snapshotStore.upload).isNull();
  }

  @Test
  void rejectsBlankAndOverlengthSnapshotMetadataHeadersBeforeUpload() throws Exception {
    String[] headerNames = {
      HttpHeaders.CONTENT_ENCODING,
      HttpHeaders.CONTENT_ENCODING,
      VersionGateController.SCHEMA_VERSION_HEADER,
      VersionGateController.SCHEMA_VERSION_HEADER
    };
    String[] headerValues = {" ", "x".repeat(256), " ", "x".repeat(256)};

    for (int index = 0; index < headerNames.length; index++) {
      mockMvc
          .perform(
              put("/builds/{buildId}/components/products", BUILD_ID)
                  .header(VersionGateController.FENCING_TOKEN_HEADER, 17)
                  .header(headerNames[index], headerValues[index])
                  .contentType(MediaType.APPLICATION_OCTET_STREAM)
                  .content(new byte[] {1}))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    assertThat(snapshotStore.upload).isNull();
  }

  @Test
  void rejectsOverlengthRegistrationAndBuildFieldsBeforeControlStorage() throws Exception {
    mockMvc
        .perform(
            post("/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "resourceId": "%s",
                      "snapshotPolicy": "CLIENT_MANAGED",
                      "requiredComponentIds": ["products"]
                    }
                    """
                        .formatted("x".repeat(129))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc
        .perform(
            post("/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "resourceId": "orders",
                      "snapshotPolicy": "COORDINATED_QUIESCE",
                      "requiredComponentIds": ["orders"],
                      "participants": [
                        {
                          "participantId": "%s",
                          "baseUri": "https://orders.internal"
                        }
                      ]
                    }
                    """
                        .formatted("x".repeat(129))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc
        .perform(
            post("/resources/catalog/builds")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "targetVersion": 5,
                      "owner": "%s",
                      "leaseSeconds": 300
                    }
                    """
                        .formatted("x".repeat(256))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(controlStore.resource.resourceId()).isEqualTo("catalog");
    assertThat(controlStore.lastLeaseDuration).isNull();
  }

  @Test
  void rejectsAnOverlengthComponentPathBeforeUpload() throws Exception {
    mockMvc
        .perform(
            put("/builds/{buildId}/components/{componentId}", BUILD_ID, "x".repeat(129))
                .header(VersionGateController.FENCING_TOKEN_HEADER, 17)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(new byte[] {1}))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(snapshotStore.upload).isNull();
  }

  @Test
  void mapsStaleFencingTokenToPreconditionFailedProblem() throws Exception {
    controlStore.build = build(BuildStatus.BUILDING, 17);

    mockMvc
        .perform(
            post("/builds/{buildId}/snapshot", BUILD_ID)
                .header(VersionGateController.FENCING_TOKEN_HEADER, 2))
        .andExpect(status().isPreconditionFailed())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:version-gate:problem:stale-fencing-token"))
        .andExpect(jsonPath("$.code").value("STALE_FENCING_TOKEN"))
        .andExpect(jsonPath("$.detail").value("Fencing token 2 is stale for build " + BUILD_ID));
  }

  @Test
  void returnsNotFoundWhenAResourceHasNoCurrentBuild() throws Exception {
    controlStore.currentBuild = Optional.empty();

    mockMvc
        .perform(get("/resources/catalog/builds/current"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("BUILD_NOT_FOUND"));
  }

  @Test
  void rejectsNegativeVersionPathValues() throws Exception {
    mockMvc
        .perform(get("/resources/catalog/versions/-1/manifest"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @ParameterizedTest
  @CsvSource({
    "VALIDATION_FAILED, 400",
    "COMPONENT_CONFLICT, 409",
    "STALE_FENCING_TOKEN, 412",
    "CHECKSUM_MISMATCH, 422",
    "PARTICIPANT_FAILURE, 502",
    "SNAPSHOT_OBJECT_MISSING, 503",
    "STORAGE_FAILURE, 503"
  })
  void mapsApplicationErrorCodes(ErrorCode code, int expectedStatus) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/builds/example");

    var response =
        new VersionGateExceptionHandler()
            .handleVersionGateException(new VersionGateException(code, "failure"), request);

    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
    assertThat(response.getBody().getProperties()).containsEntry("code", code.name());
  }

  @Test
  void sanitizesStorageFailuresAndProtectsReservedProblemFields() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resources/catalog");
    VersionGateException failure =
        new VersionGateException(
            ErrorCode.STORAGE_FAILURE,
            "provider endpoint https://secret.internal failed",
            Map.of("providerMessage", "credential-bearing backend detail"));

    var response = new VersionGateExceptionHandler().handleVersionGateException(failure, request);

    assertThat(response.getBody().getDetail())
        .isEqualTo("A storage dependency could not complete the request");
    assertThat(response.getBody().getProperties())
        .containsEntry("code", "STORAGE_FAILURE")
        .containsKey("correlationId")
        .doesNotContainKey("providerMessage");
    assertThat(response.getBody().toString()).doesNotContain("secret", "credential");

    assertThatThrownBy(
            () ->
                new VersionGateException(
                    ErrorCode.VALIDATION_FAILED, "invalid", Map.of("status", 418)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved");
  }

  @Test
  void streamsComponentAndClosesObjectContent() throws Exception {
    byte[] payload = "snapshot-data".getBytes(StandardCharsets.UTF_8);
    CloseTrackingInputStream inputStream = new CloseTrackingInputStream(payload);
    SnapshotComponent component =
        component(
            "catalog",
            3,
            "products",
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            Optional.of("gzip"),
            Optional.of("catalog/3"),
            payload.length);
    SnapshotStore.ObjectContent objectContent =
        new SnapshotStore.ObjectContent(
            inputStream,
            payload.length,
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            Optional.of("gzip"),
            SHA_256);
    controlStore.snapshotComponent = component;
    snapshotStore.objectContent = objectContent;

    MvcResult result =
        mockMvc
            .perform(get("/resources/catalog/versions/3/components/products"))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc
        .perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().bytes(payload))
        .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, payload.length))
        .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
        .andExpect(header().string(VersionGateController.CHECKSUM_HEADER, SHA_256))
        .andExpect(header().string(VersionGateController.SCHEMA_VERSION_HEADER, "catalog/3"))
        .andExpect(header().string(VersionGateController.CAPTURED_AT_HEADER, NOW.toString()));
    assertThat(inputStream.closed).isTrue();
  }

  @Test
  void closesObjectContentWhenStoredResponseMetadataIsUnsafe() throws Exception {
    byte[] payload = "snapshot-data".getBytes(StandardCharsets.UTF_8);
    CloseTrackingInputStream inputStream = new CloseTrackingInputStream(payload);
    String unsafeEncoding = "gzip\r\nX-Injected: true";
    controlStore.snapshotComponent =
        component(
            "catalog",
            3,
            "products",
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            Optional.of(unsafeEncoding),
            Optional.empty(),
            payload.length);
    snapshotStore.objectContent =
        new SnapshotStore.ObjectContent(
            inputStream,
            payload.length,
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            Optional.of(unsafeEncoding),
            SHA_256);

    mockMvc
        .perform(get("/resources/catalog/versions/3/components/products"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STORAGE_FAILURE"));

    assertThat(inputStream.closed).isTrue();
  }

  private static SnapshotComponent component(
      String resourceId,
      long version,
      String componentId,
      String contentType,
      Optional<String> contentEncoding,
      Optional<String> schemaVersion,
      long size) {
    return new SnapshotComponent(
        BUILD_ID,
        resourceId,
        version,
        componentId,
        "snapshots/" + resourceId + "/" + version + "/" + componentId,
        contentType,
        contentEncoding,
        SHA_256,
        size,
        schemaVersion,
        NOW);
  }

  private static Build build(BuildStatus status, long fencingToken) {
    return new Build(
        BUILD_ID,
        "catalog",
        1,
        null,
        status,
        "catalog-publisher",
        fencingToken,
        NOW.plusSeconds(600),
        NOW,
        NOW);
  }

  private static final class TestControlStore implements ControlStore {

    private Resource resource =
        new Resource(
            "catalog",
            SnapshotPolicy.CLIENT_MANAGED,
            Set.of("products"),
            List.of(),
            null,
            NOW,
            NOW);
    private Build build = build(BuildStatus.BUILDING, 17);
    private Optional<Build> currentBuild = Optional.empty();
    private SnapshotComponent snapshotComponent;
    private Duration lastLeaseDuration;
    private long lastFencingToken;

    @Override
    public Resource registerResource(
        String resourceId,
        SnapshotPolicy snapshotPolicy,
        Set<String> requiredComponentIds,
        List<Participant> participants) {
      resource =
          new Resource(
              resourceId, snapshotPolicy, requiredComponentIds, participants, null, NOW, NOW);
      return resource;
    }

    @Override
    public Optional<Resource> findResource(String resourceId) {
      return resource.resourceId().equals(resourceId) ? Optional.of(resource) : Optional.empty();
    }

    @Override
    public Build beginBuild(
        String resourceId, long targetVersion, String owner, Duration leaseDuration) {
      lastLeaseDuration = leaseDuration;
      build =
          new Build(
              BUILD_ID,
              resourceId,
              targetVersion,
              resource.activeVersion(),
              BuildStatus.BUILDING,
              owner,
              17,
              NOW.plus(leaseDuration),
              NOW,
              NOW);
      currentBuild = Optional.of(build);
      return build;
    }

    @Override
    public Build renewBuild(UUID buildId, long fencingToken, Duration leaseDuration) {
      lastLeaseDuration = leaseDuration;
      lastFencingToken = fencingToken;
      build =
          new Build(
              build.buildId(),
              build.resourceId(),
              build.targetVersion(),
              build.baseActiveVersion(),
              build.status(),
              build.owner(),
              build.fencingToken(),
              NOW.plus(leaseDuration),
              build.createdAt(),
              NOW);
      return build;
    }

    @Override
    public Build startSnapshotPhase(UUID buildId, long fencingToken, BuildStatus targetStatus) {
      throw unsupported();
    }

    @Override
    public Build markSnapshotting(UUID buildId, long fencingToken) {
      throw unsupported();
    }

    @Override
    public Optional<Build> findBuild(UUID buildId) {
      return build.buildId().equals(buildId) ? Optional.of(build) : Optional.empty();
    }

    @Override
    public Optional<Build> findCurrentBuild(String resourceId) {
      return currentBuild;
    }

    @Override
    public SnapshotComponent registerSnapshotComponent(
        UUID buildId, long fencingToken, SnapshotComponent component) {
      snapshotComponent = component;
      return component;
    }

    @Override
    public Optional<SnapshotComponent> findSnapshotComponent(
        String resourceId, long version, String componentId) {
      if (snapshotComponent == null
          || !snapshotComponent.resourceId().equals(resourceId)
          || snapshotComponent.version() != version
          || !snapshotComponent.componentId().equals(componentId)) {
        return Optional.empty();
      }
      return Optional.of(snapshotComponent);
    }

    @Override
    public List<SnapshotComponent> findSnapshotComponents(String resourceId, long version) {
      return findSnapshotComponent(resourceId, version, "products").stream().toList();
    }

    @Override
    public VersionManifest completeBuild(UUID buildId, long fencingToken) {
      throw unsupported();
    }

    @Override
    public Build activateBuild(UUID buildId, long fencingToken) {
      throw unsupported();
    }

    @Override
    public Build abortBuild(UUID buildId, long fencingToken) {
      throw unsupported();
    }

    @Override
    public Build failBuild(UUID buildId, long fencingToken, String reason) {
      throw unsupported();
    }

    @Override
    public Optional<VersionManifest> findActiveVersionManifest(String resourceId) {
      throw unsupported();
    }

    @Override
    public Optional<VersionManifest> findVersionManifest(String resourceId, long version) {
      if (snapshotComponent == null
          || !snapshotComponent.resourceId().equals(resourceId)
          || snapshotComponent.version() != version) {
        return Optional.empty();
      }
      return Optional.of(
          new VersionManifest(
              resourceId, version, BUILD_ID, null, NOW, List.of(snapshotComponent)));
    }

    @Override
    public void updateParticipantState(
        UUID buildId, String participantId, ParticipantStatus status, String detail) {
      throw unsupported();
    }

    @Override
    public List<ParticipantState> findParticipantStates(UUID buildId) {
      throw unsupported();
    }

    @Override
    public int abandonExpiredBuilds() {
      throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
      return new UnsupportedOperationException("not used by this HTTP test");
    }
  }

  private static final class TestSnapshotStore implements SnapshotStore {

    private Upload upload;
    private byte[] uploadedBytes;
    private ObjectContent objectContent;

    @Override
    public StoredObject uploadImmutable(Upload upload) {
      this.upload = upload;
      try {
        uploadedBytes = upload.inputStream().readAllBytes();
      } catch (IOException exception) {
        throw new AssertionError(exception);
      }
      return new StoredObject(
          new ObjectReference(upload.objectKey(), SHA_256, uploadedBytes.length), false);
    }

    @Override
    public void verify(ObjectReference objectReference) {}

    @Override
    public ObjectContent open(ObjectReference objectReference) {
      return objectContent;
    }

    @Override
    public void delete(ObjectReference objectReference) {}
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

  @SuppressWarnings("resource")
  private static final class CloseTrackingInputStream extends ByteArrayInputStream {

    private boolean closed;

    private CloseTrackingInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
