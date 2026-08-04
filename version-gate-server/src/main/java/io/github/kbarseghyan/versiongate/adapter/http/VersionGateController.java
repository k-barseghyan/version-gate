package io.github.kbarseghyan.versiongate.adapter.http;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.application.VersionGateService;
import io.github.kbarseghyan.versiongate.domain.DomainValidation;
import io.github.kbarseghyan.versiongate.domain.LiveReadSession;
import io.github.kbarseghyan.versiongate.domain.MissingCurrentSnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.ResourcePolicies;
import io.github.kbarseghyan.versiongate.domain.RetrievalDuringWritePolicy;
import io.github.kbarseghyan.versiongate.domain.SnapshotGenerationSession;
import io.github.kbarseghyan.versiongate.domain.SnapshotSelector;
import io.github.kbarseghyan.versiongate.domain.SnapshotSupport;
import io.github.kbarseghyan.versiongate.domain.StoredSnapshot;
import io.github.kbarseghyan.versiongate.domain.WriteSession;
import io.github.kbarseghyan.versiongate.domain.WriterDuringSnapshotPolicy;
import io.github.kbarseghyan.versiongate.port.VersionGateStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** HTTP boundary for coordinated writes, live reads, snapshot generation, and snapshot reads. */
@RestController
@RequestMapping
@Tag(name = "Version Gate", description = "Coordinate coherent distributed writes and reads")
final class VersionGateController {

  static final String FENCING_TOKEN_HEADER = "X-Fencing-Token";
  static final String CHECKSUM_HEADER = "X-Checksum-SHA256";
  static final String SNAPSHOT_VERSION_HEADER = "X-Snapshot-Version";
  static final String ACTIVE_VERSION_HEADER = "X-Active-Version";
  static final String SNAPSHOT_STALE_HEADER = "X-Snapshot-Stale";

  private final VersionGateService service;

  VersionGateController(VersionGateService service) {
    this.service = service;
  }

  @PostMapping(path = "/resources", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Register a coordinated resource and its explicit policies")
  ResponseEntity<Resource> registerResource(@Valid @RequestBody RegisterResourceRequest request) {
    Resource resource = service.registerResource(request.toCommand());
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{resourceId}")
            .buildAndExpand(resource.resourceId())
            .toUri();
    return ResponseEntity.created(location).body(resource);
  }

  @GetMapping(path = "/resources/{resourceId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Get a registered resource")
  Resource getResource(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId) {
    return service.getResource(resourceId);
  }

  @PostMapping(
      path = "/resources/{resourceId}/write-sessions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Begin a coordinated write")
  ResponseEntity<WriteSession> beginWrite(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @Valid @RequestBody BeginSessionRequest request) {
    WriteSession session = service.beginWrite(request.toWriteCommand(resourceId));
    return createdSession("/write-sessions/{sessionId}", session.sessionId(), session);
  }

  @PostMapping(
      path = "/write-sessions/{sessionId}/renew",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Renew a coordinated write lease")
  WriteSession renewWrite(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken,
      @Valid @RequestBody RenewSessionRequest request) {
    return service.renewWrite(request.toCommand(sessionId, fencingToken));
  }

  @PostMapping(
      path = "/write-sessions/{sessionId}/complete",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Complete a write and immediately activate its version")
  WriteSession completeWrite(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken) {
    return service.completeWrite(sessionCommand(sessionId, fencingToken));
  }

  @PostMapping(
      path = "/write-sessions/{sessionId}/fail",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fail a coordinated write without activating its version")
  WriteSession failWrite(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken,
      @Valid @RequestBody FailWriteRequest request) {
    return service.failWrite(request.toCommand(sessionId, fencingToken));
  }

  @PostMapping(
      path = "/write-sessions/{sessionId}/abandon",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Abandon a coordinated write without activating its version")
  WriteSession abandonWrite(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken) {
    return service.abandonWrite(sessionCommand(sessionId, fencingToken));
  }

  @PostMapping(
      path = "/resources/{resourceId}/live-read-sessions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Begin a coordinated live read")
  ResponseEntity<LiveReadSession> beginLiveRead(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @Valid @RequestBody BeginSessionRequest request) {
    LiveReadSession session = service.beginLiveRead(request.toLiveReadCommand(resourceId));
    return createdSession("/live-read-sessions/{sessionId}", session.sessionId(), session);
  }

  @PostMapping(
      path = "/live-read-sessions/{sessionId}/renew",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Renew a coordinated live-read lease")
  LiveReadSession renewLiveRead(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken,
      @Valid @RequestBody RenewSessionRequest request) {
    return service.renewLiveRead(request.toCommand(sessionId, fencingToken));
  }

  @PostMapping(
      path = "/live-read-sessions/{sessionId}/complete",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Complete a coordinated live read")
  LiveReadSession completeLiveRead(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken) {
    return service.completeLiveRead(sessionCommand(sessionId, fencingToken));
  }

  @PostMapping(
      path = "/live-read-sessions/{sessionId}/abandon",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Abandon a coordinated live read")
  LiveReadSession abandonLiveRead(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken) {
    return service.abandonLiveRead(sessionCommand(sessionId, fencingToken));
  }

  @PostMapping(
      path = "/resources/{resourceId}/snapshot-sessions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Begin externally performed snapshot generation")
  ResponseEntity<SnapshotGenerationSession> beginSnapshot(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @Valid @RequestBody BeginSessionRequest request) {
    SnapshotGenerationSession session =
        service.beginSnapshot(request.toSnapshotCommand(resourceId));
    return createdSession("/snapshot-sessions/{sessionId}", session.sessionId(), session);
  }

  @PostMapping(
      path = "/snapshot-sessions/{sessionId}/renew",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Renew a snapshot-generation lease")
  SnapshotGenerationSession renewSnapshot(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken,
      @Valid @RequestBody RenewSessionRequest request) {
    return service.renewSnapshot(request.toCommand(sessionId, fencingToken));
  }

  @PutMapping(path = "/snapshot-sessions/{sessionId}/snapshot")
  @Operation(summary = "Atomically publish a complete immutable snapshot")
  ResponseEntity<VersionGateStore.SnapshotSubmission> submitSnapshot(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken,
      HttpServletRequest request)
      throws IOException {
    long contentLength = requireContentLength(request);
    String contentType = requireContentType(request.getContentType());
    VersionGateStore.SnapshotSubmission submission =
        service.submitSnapshot(
            new VersionGateService.SubmitSnapshotCommand(
                sessionId,
                fencingToken,
                request.getInputStream(),
                contentLength,
                contentType,
                optionalHeader(
                    request, HttpHeaders.CONTENT_ENCODING, DomainValidation.TEXT_MAX_LENGTH),
                optionalSha256Header(request)));
    StoredSnapshot snapshot = submission.snapshot();
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/resources/{resourceId}/snapshots/versions/{version}")
            .buildAndExpand(snapshot.resourceId(), snapshot.snapshotVersion())
            .toUri();
    return submission.replayed()
        ? ResponseEntity.ok(submission)
        : ResponseEntity.created(location).body(submission);
  }

  @PostMapping(
      path = "/snapshot-sessions/{sessionId}/abort",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Abort snapshot generation without publishing bytes")
  SnapshotGenerationSession abortSnapshot(
      @PathVariable UUID sessionId,
      @RequestHeader(FENCING_TOKEN_HEADER) @Positive long fencingToken) {
    return service.abortSnapshot(sessionCommand(sessionId, fencingToken));
  }

  @GetMapping(path = "/resources/{resourceId}/snapshots/versions/{version}")
  @Operation(summary = "Retrieve an immutable snapshot by exact version")
  ResponseEntity<StreamingResponseBody> getSnapshotByVersion(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @PathVariable @PositiveOrZero long version) {
    return stream(service.getSnapshotByVersion(resourceId, version));
  }

  @GetMapping(path = "/resources/{resourceId}/snapshots/current")
  @Operation(summary = "Retrieve the snapshot for the active version")
  ResponseEntity<StreamingResponseBody> getCurrentSnapshot(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId) {
    return stream(service.getCurrentSnapshot(resourceId));
  }

  @GetMapping(path = "/resources/{resourceId}/snapshots/latest-available")
  @Operation(summary = "Retrieve the highest stored snapshot version")
  ResponseEntity<StreamingResponseBody> getLatestAvailableSnapshot(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId) {
    return stream(service.getLatestAvailableSnapshot(resourceId));
  }

  @GetMapping(path = "/resources/{resourceId}/snapshots/default")
  @Operation(summary = "Retrieve using the resource's configured default selector")
  ResponseEntity<StreamingResponseBody> getDefaultSnapshot(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @RequestParam(required = false) @PositiveOrZero Long version) {
    OptionalLong requestedVersion =
        version == null ? OptionalLong.empty() : OptionalLong.of(version);
    return stream(service.getDefaultSnapshot(resourceId, requestedVersion));
  }

  private ResponseEntity<StreamingResponseBody> stream(VersionGateStore.SnapshotContent content) {
    try {
      VersionGateStore.SnapshotResolution resolution = content.resolution();
      StoredSnapshot snapshot = resolution.snapshot();
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(requireStoredContentType(snapshot.contentType()));
      headers.setContentLength(snapshot.contentLength());
      snapshot
          .contentEncoding()
          .ifPresent(
              value ->
                  headers.set(
                      HttpHeaders.CONTENT_ENCODING,
                      requireSafeResponseHeader(HttpHeaders.CONTENT_ENCODING, value)));
      headers.set(CHECKSUM_HEADER, snapshot.sha256());
      headers.set(SNAPSHOT_VERSION_HEADER, Long.toString(snapshot.snapshotVersion()));
      headers.set(ACTIVE_VERSION_HEADER, Long.toString(resolution.activeVersion()));
      headers.set(SNAPSHOT_STALE_HEADER, Boolean.toString(resolution.stale()));

      StreamingResponseBody body =
          outputStream -> {
            try (content) {
              content.inputStream().transferTo(outputStream);
            }
          };
      return new ResponseEntity<>(body, headers, HttpStatus.OK);
    } catch (RuntimeException | Error failure) {
      closeAfterFailure(content, failure);
      throw failure;
    }
  }

  private static VersionGateService.SessionCommand sessionCommand(
      UUID sessionId, long fencingToken) {
    return new VersionGateService.SessionCommand(sessionId, fencingToken);
  }

  private static <T> ResponseEntity<T> createdSession(String path, UUID sessionId, T session) {
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path(path)
            .buildAndExpand(sessionId)
            .toUri();
    return ResponseEntity.created(location).body(session);
  }

  private static long requireContentLength(HttpServletRequest request) {
    long contentLength = request.getContentLengthLong();
    if (contentLength < 0) {
      throw new HttpApiException(
          HttpStatus.LENGTH_REQUIRED,
          "CONTENT_LENGTH_REQUIRED",
          "Snapshot submission requires a Content-Length header");
    }
    return contentLength;
  }

  private static String requireContentType(String value) {
    if (!StringUtils.hasText(value)) {
      throw unsupportedContentType("Snapshot submission requires a Content-Type header");
    }
    try {
      return MediaType.parseMediaType(value).toString();
    } catch (IllegalArgumentException exception) {
      throw unsupportedContentType("Snapshot Content-Type is invalid");
    }
  }

  private static HttpApiException unsupportedContentType(String detail) {
    return new HttpApiException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_CONTENT_TYPE", detail);
  }

  private static String requireSafeResponseHeader(String name, String value) {
    if (value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
      throw new VersionGateException(
          ErrorCode.STORAGE_FAILURE,
          "Stored " + name + " metadata is not safe for an HTTP response");
    }
    return value;
  }

  private static MediaType requireStoredContentType(String value) {
    try {
      return MediaType.parseMediaType(value);
    } catch (IllegalArgumentException exception) {
      throw new VersionGateException(
          ErrorCode.STORAGE_FAILURE, "Stored Content-Type metadata is invalid", exception);
    }
  }

  private static Optional<String> optionalHeader(
      HttpServletRequest request, String name, int maximumLength) {
    String value = request.getHeader(name);
    if (value == null) {
      return Optional.empty();
    }
    if (!StringUtils.hasText(value)) {
      throw new HttpApiException(
          HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", name + " must not be blank when supplied");
    }
    if (value.length() > maximumLength) {
      throw new HttpApiException(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_FAILED",
          name + " must contain at most " + maximumLength + " characters");
    }
    return Optional.of(value);
  }

  private static Optional<String> optionalSha256Header(HttpServletRequest request) {
    Optional<String> value = optionalHeader(request, CHECKSUM_HEADER, 64);
    if (value.isEmpty()) {
      return value;
    }
    try {
      return Optional.of(DomainValidation.requireSha256(value.get()));
    } catch (IllegalArgumentException exception) {
      throw new HttpApiException(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_FAILED",
          CHECKSUM_HEADER + " must contain 64 hexadecimal characters");
    }
  }

  private static void closeAfterFailure(
      VersionGateStore.SnapshotContent content, Throwable failure) {
    try {
      content.close();
    } catch (Exception cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  record RegisterResourceRequest(
      @NotBlank @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @NotNull @Valid ResourcePoliciesRequest policies) {

    VersionGateService.RegisterResourceCommand toCommand() {
      return new VersionGateService.RegisterResourceCommand(resourceId, policies.toDomain());
    }
  }

  record ResourcePoliciesRequest(
      @NotNull SnapshotSupport snapshotSupport,
      @NotNull MissingCurrentSnapshotPolicy missingCurrentSnapshotPolicy,
      WriterDuringSnapshotPolicy writerDuringSnapshotPolicy,
      SnapshotSelector defaultSnapshotSelector,
      RetrievalDuringWritePolicy retrievalDuringWritePolicy) {

    ResourcePolicies toDomain() {
      return new ResourcePolicies(
          snapshotSupport,
          missingCurrentSnapshotPolicy,
          Optional.ofNullable(writerDuringSnapshotPolicy),
          Optional.ofNullable(defaultSnapshotSelector),
          Optional.ofNullable(retrievalDuringWritePolicy));
    }
  }

  record BeginSessionRequest(@NotBlank String owner, @NotNull @Positive Long leaseSeconds) {

    VersionGateService.BeginWriteCommand toWriteCommand(String resourceId) {
      return new VersionGateService.BeginWriteCommand(
          resourceId, owner, Duration.ofSeconds(leaseSeconds));
    }

    VersionGateService.BeginLiveReadCommand toLiveReadCommand(String resourceId) {
      return new VersionGateService.BeginLiveReadCommand(
          resourceId, owner, Duration.ofSeconds(leaseSeconds));
    }

    VersionGateService.BeginSnapshotCommand toSnapshotCommand(String resourceId) {
      return new VersionGateService.BeginSnapshotCommand(
          resourceId, owner, Duration.ofSeconds(leaseSeconds));
    }
  }

  record RenewSessionRequest(@NotNull @Positive Long leaseSeconds) {

    VersionGateService.RenewSessionCommand toCommand(UUID sessionId, long fencingToken) {
      return new VersionGateService.RenewSessionCommand(
          sessionId, fencingToken, Duration.ofSeconds(leaseSeconds));
    }
  }

  record FailWriteRequest(@NotBlank String reason) {

    VersionGateService.FailWriteCommand toCommand(UUID sessionId, long fencingToken) {
      return new VersionGateService.FailWriteCommand(sessionId, fencingToken, reason);
    }
  }
}
