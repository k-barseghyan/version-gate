package io.github.kbarseghyan.versiongate.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
import io.github.kbarseghyan.versiongate.domain.SnapshotSelector;
import io.github.kbarseghyan.versiongate.domain.StoredSnapshot;
import io.github.kbarseghyan.versiongate.port.VersionGateStore;
import io.github.kbarseghyan.versiongate.testkit.InMemoryVersionGateStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class VersionGateControllerTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().findAndAddModules().build();

  private InMemoryVersionGateStore store;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    store = new InMemoryVersionGateStore(Clock.fixed(NOW, ZoneOffset.UTC));
    VersionGateService service = new VersionGateService(store, Duration.ofHours(1), 1024 * 1024);
    mockMvc = mockMvc(service);
  }

  @Test
  void registersOnlyValidExplicitResourcePolicyShapes() throws Exception {
    mockMvc
        .perform(
            post("/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(disabledResource("catalog")))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/resources/catalog"))
        .andExpect(jsonPath("$.policies.snapshotSupport").value("DISABLED"))
        .andExpect(jsonPath("$.policies.missingCurrentSnapshotPolicy").value("ALLOW_GAP"));

    mockMvc
        .perform(
            post("/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(enabledResource("orders", "CURRENT")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.policies.defaultSnapshotSelector").value("CURRENT"));

    mockMvc
        .perform(
            post("/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "resourceId": "missing-policies",
                      "policies": {
                        "snapshotSupport": "ENABLED",
                        "missingCurrentSnapshotPolicy": "ALLOW_GAP"
                      }
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc
        .perform(
            post("/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "resourceId": "invalid-combination",
                      "policies": {
                        "snapshotSupport": "ENABLED",
                        "missingCurrentSnapshotPolicy": "REQUIRE_CURRENT_SNAPSHOT",
                        "writerDuringSnapshotPolicy": "INVALIDATE_SNAPSHOT",
                        "defaultSnapshotSelector": "CURRENT",
                        "retrievalDuringWritePolicy": "REJECT_IF_WRITING"
                      }
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void exposesCompleteWriteAndLiveReadSessionLifecycles() throws Exception {
    register(disabledResource("catalog"));

    SessionToken write = begin("/resources/catalog/write-sessions", "writer");
    mockMvc
        .perform(
            post("/write-sessions/{sessionId}/renew", write.sessionId())
                .header(VersionGateController.FENCING_TOKEN_HEADER, write.fencingToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"leaseSeconds\":120}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WRITING"));
    mutate("/write-sessions/{sessionId}/complete", write)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    mockMvc
        .perform(get("/resources/catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeVersion").value(1));

    SessionToken read = begin("/resources/catalog/live-read-sessions", "reader-one");
    mockMvc
        .perform(
            post("/live-read-sessions/{sessionId}/renew", read.sessionId())
                .header(VersionGateController.FENCING_TOKEN_HEADER, read.fencingToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"leaseSeconds\":120}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.boundVersion").value(1));
    mutate("/live-read-sessions/{sessionId}/complete", read)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    SessionToken abandonedRead = begin("/resources/catalog/live-read-sessions", "reader-two");
    mutate("/live-read-sessions/{sessionId}/abandon", abandonedRead)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABANDONED"));

    SessionToken failedWrite = begin("/resources/catalog/write-sessions", "failed-writer");
    mockMvc
        .perform(
            post("/write-sessions/{sessionId}/fail", failedWrite.sessionId())
                .header(VersionGateController.FENCING_TOKEN_HEADER, failedWrite.fencingToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"upstream write failed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("upstream write failed"));

    SessionToken abandonedWrite = begin("/resources/catalog/write-sessions", "abandoned-writer");
    mutate("/write-sessions/{sessionId}/abandon", abandonedWrite)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABANDONED"));
  }

  @Test
  void publishesAndStreamsSnapshotsThroughEverySelector() throws Exception {
    register(enabledResource("catalog", "BY_VERSION"));
    activateFirstVersion("catalog");

    SessionToken generation = begin("/resources/catalog/snapshot-sessions", "snapshot-provider");
    mockMvc
        .perform(
            post("/snapshot-sessions/{sessionId}/renew", generation.sessionId())
                .header(VersionGateController.FENCING_TOKEN_HEADER, generation.fencingToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"leaseSeconds\":120}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snapshotVersion").value(1));

    byte[] payload = "immutable-snapshot".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256(payload);
    var submission =
        put("/snapshot-sessions/{sessionId}/snapshot", generation.sessionId())
            .header(VersionGateController.FENCING_TOKEN_HEADER, generation.fencingToken())
            .header(VersionGateController.CHECKSUM_HEADER, sha256)
            .header(HttpHeaders.CONTENT_ENCODING, "identity")
            .contentType("application/vnd.version-gate.snapshot")
            .content(payload);
    mockMvc
        .perform(submission)
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string(
                    HttpHeaders.LOCATION,
                    "http://localhost/resources/catalog/snapshots/versions/1"))
        .andExpect(jsonPath("$.snapshot.snapshotVersion").value(1))
        .andExpect(jsonPath("$.replayed").value(false));
    mockMvc
        .perform(submission)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(true));

    assertSnapshot("/resources/catalog/snapshots/versions/1", payload, 1, 1, false, "identity");
    assertSnapshot("/resources/catalog/snapshots/current", payload, 1, 1, false, "identity");
    assertSnapshot(
        "/resources/catalog/snapshots/latest-available", payload, 1, 1, false, "identity");
    assertSnapshot(
        "/resources/catalog/snapshots/default?version=1", payload, 1, 1, false, "identity");
    mockMvc
        .perform(get("/resources/catalog/snapshots/default"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    SessionToken nextWrite = begin("/resources/catalog/write-sessions", "second-writer");
    mutate("/write-sessions/{sessionId}/complete", nextWrite).andExpect(status().isOk());
    assertSnapshot(
        "/resources/catalog/snapshots/latest-available", payload, 1, 2, true, "identity");
    mockMvc
        .perform(get("/resources/catalog/snapshots/current"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CURRENT_SNAPSHOT_UNAVAILABLE"));

    SessionToken aborted = begin("/resources/catalog/snapshot-sessions", "aborted-provider");
    mutate("/snapshot-sessions/{sessionId}/abort", aborted)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABORTED"));
  }

  @Test
  void validatesUploadHeadersBeforeSnapshotStorage() throws Exception {
    register(enabledResource("catalog", "CURRENT"));
    activateFirstVersion("catalog");
    SessionToken generation = begin("/resources/catalog/snapshot-sessions", "provider");

    mockMvc
        .perform(
            put("/snapshot-sessions/{sessionId}/snapshot", generation.sessionId())
                .header(VersionGateController.FENCING_TOKEN_HEADER, generation.fencingToken())
                .contentType(MediaType.APPLICATION_OCTET_STREAM))
        .andExpect(status().isLengthRequired())
        .andExpect(jsonPath("$.code").value("CONTENT_LENGTH_REQUIRED"));
    mockMvc
        .perform(
            put("/snapshot-sessions/{sessionId}/snapshot", generation.sessionId())
                .header(VersionGateController.FENCING_TOKEN_HEADER, generation.fencingToken())
                .content(new byte[] {1}))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT_TYPE"));
    mockMvc
        .perform(
            put("/snapshot-sessions/{sessionId}/snapshot", generation.sessionId())
                .header(VersionGateController.FENCING_TOKEN_HEADER, generation.fencingToken())
                .header(VersionGateController.CHECKSUM_HEADER, "not-a-sha")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(new byte[] {1}))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mockMvc
        .perform(
            put("/snapshot-sessions/{sessionId}/snapshot", generation.sessionId())
                .header(VersionGateController.FENCING_TOKEN_HEADER, generation.fencingToken() + 1)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(new byte[] {1}))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value("STALE_FENCING_TOKEN"));
  }

  @ParameterizedTest
  @CsvSource({
    "VALIDATION_FAILED,400",
    "RESOURCE_NOT_FOUND,404",
    "ACTIVE_VERSION_NOT_FOUND,404",
    "WRITE_SESSION_NOT_FOUND,404",
    "LIVE_READ_SESSION_NOT_FOUND,404",
    "SNAPSHOT_SESSION_NOT_FOUND,404",
    "SNAPSHOT_NOT_FOUND,404",
    "CURRENT_SNAPSHOT_UNAVAILABLE,404",
    "RESOURCE_ALREADY_EXISTS,409",
    "WRITE_ALREADY_ACTIVE,409",
    "LIVE_READ_ACTIVE,409",
    "SNAPSHOT_SESSION_ALREADY_EXISTS,409",
    "SNAPSHOT_GENERATION_ACTIVE,409",
    "SNAPSHOT_SUPPORT_DISABLED,409",
    "CURRENT_SNAPSHOT_REQUIRED,409",
    "SNAPSHOT_INVALIDATED,409",
    "WRITE_IN_PROGRESS,409",
    "SNAPSHOT_CONFLICT,409",
    "LEASE_EXPIRED,409",
    "INVALID_SESSION_TRANSITION,409",
    "STALE_FENCING_TOKEN,412",
    "CHECKSUM_MISMATCH,422",
    "STORAGE_FAILURE,503"
  })
  void mapsApplicationErrors(ErrorCode code, int expectedStatus) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/sessions/example");

    var response =
        new VersionGateExceptionHandler()
            .handleVersionGateException(new VersionGateException(code, "failure"), request);

    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
    assertThat(response.getBody().getProperties()).containsEntry("code", code.name());
  }

  @Test
  void sanitizesStorageFailuresAndClosesUnsafeSnapshotContent() throws Exception {
    VersionGateStore unsafeStore = mock(VersionGateStore.class);
    CloseTrackingInputStream inputStream =
        new CloseTrackingInputStream("snapshot".getBytes(StandardCharsets.UTF_8));
    StoredSnapshot snapshot =
        new StoredSnapshot(
            "catalog",
            3,
            8,
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            Optional.of("gzip\r\nX-Injected: true"),
            "a".repeat(64),
            NOW);
    VersionGateStore.SnapshotContent unsafeContent =
        new VersionGateStore.SnapshotContent(
            new VersionGateStore.SnapshotResolution(
                snapshot, 3, SnapshotSelector.BY_VERSION, false),
            inputStream);
    when(unsafeStore.getSnapshot("catalog", SnapshotSelector.BY_VERSION, OptionalLong.of(3)))
        .thenReturn(unsafeContent);
    MockMvc unsafeMvc = mockMvc(new VersionGateService(unsafeStore, Duration.ofHours(1), 1024));

    unsafeMvc
        .perform(get("/resources/catalog/snapshots/versions/3"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STORAGE_FAILURE"));
    assertThat(inputStream.closed).isTrue();

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
    assertThatThrownBy(
            () ->
                new VersionGateException(
                    ErrorCode.VALIDATION_FAILED, "invalid", Map.of("status", 418)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved");
  }

  private void register(String request) throws Exception {
    mockMvc
        .perform(post("/resources").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isCreated());
  }

  private void activateFirstVersion(String resourceId) throws Exception {
    SessionToken token = begin("/resources/" + resourceId + "/write-sessions", "initial-writer");
    mutate("/write-sessions/{sessionId}/complete", token).andExpect(status().isOk());
  }

  private SessionToken begin(String path, String owner) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"owner":"%s","leaseSeconds":300}
                        """
                            .formatted(owner)))
            .andExpect(status().isCreated())
            .andExpect(header().exists(HttpHeaders.LOCATION))
            .andReturn();
    JsonNode json = JSON_MAPPER.readTree(result.getResponse().getContentAsByteArray());
    return new SessionToken(
        UUID.fromString(json.get("sessionId").stringValue()), json.get("fencingToken").asLong());
  }

  private org.springframework.test.web.servlet.ResultActions mutate(String path, SessionToken token)
      throws Exception {
    return mockMvc.perform(
        post(path, token.sessionId())
            .header(VersionGateController.FENCING_TOKEN_HEADER, token.fencingToken()));
  }

  private void assertSnapshot(
      String path,
      byte[] payload,
      long snapshotVersion,
      long activeVersion,
      boolean stale,
      String contentEncoding)
      throws Exception {
    MvcResult result = mockMvc.perform(get(path)).andExpect(request().asyncStarted()).andReturn();
    mockMvc
        .perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().bytes(payload))
        .andExpect(
            header()
                .string(
                    VersionGateController.SNAPSHOT_VERSION_HEADER, Long.toString(snapshotVersion)))
        .andExpect(
            header()
                .string(VersionGateController.ACTIVE_VERSION_HEADER, Long.toString(activeVersion)))
        .andExpect(
            header().string(VersionGateController.SNAPSHOT_STALE_HEADER, Boolean.toString(stale)))
        .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, contentEncoding));
  }

  private static MockMvc mockMvc(VersionGateService service) {
    return MockMvcBuilders.standaloneSetup(new VersionGateController(service))
        .setControllerAdvice(new VersionGateExceptionHandler())
        .build();
  }

  private static String disabledResource(String resourceId) {
    return """
        {
          "resourceId": "%s",
          "policies": {
            "snapshotSupport": "DISABLED",
            "missingCurrentSnapshotPolicy": "ALLOW_GAP"
          }
        }
        """
        .formatted(resourceId);
  }

  private static String enabledResource(String resourceId, String selector) {
    return """
        {
          "resourceId": "%s",
          "policies": {
            "snapshotSupport": "ENABLED",
            "missingCurrentSnapshotPolicy": "ALLOW_GAP",
            "writerDuringSnapshotPolicy": "BLOCK_WRITER",
            "defaultSnapshotSelector": "%s",
            "retrievalDuringWritePolicy": "ALLOW_WHILE_WRITING"
          }
        }
        """
        .formatted(resourceId, selector);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record SessionToken(UUID sessionId, long fencingToken) {}

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
