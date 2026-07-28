package io.github.kbarseghyan.versiongate.adapter.http;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.application.VersionGateService;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.DomainValidation;
import io.github.kbarseghyan.versiongate.domain.Participant;
import io.github.kbarseghyan.versiongate.domain.Resource;
import io.github.kbarseghyan.versiongate.domain.SnapshotComponent;
import io.github.kbarseghyan.versiongate.domain.SnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.VersionManifest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping
@Tag(name = "Version Gate", description = "Coordinate and publish immutable snapshot versions")
final class VersionGateController {

  static final String FENCING_TOKEN_HEADER = "X-Fencing-Token";
  static final String CHECKSUM_HEADER = "X-Checksum-SHA256";
  static final String SCHEMA_VERSION_HEADER = "X-Schema-Version";
  static final String CAPTURED_AT_HEADER = "X-Captured-At";

  private final VersionGateService service;

  VersionGateController(VersionGateService service) {
    this.service = service;
  }

  @PostMapping(path = "/resources", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Register a versioned resource")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Resource registered"),
    @ApiResponse(responseCode = "400", description = "Request validation failed"),
    @ApiResponse(responseCode = "409", description = "Resource already exists")
  })
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
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Resource returned"),
    @ApiResponse(responseCode = "404", description = "Resource not found")
  })
  Resource getResource(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId) {
    return service.getResource(resourceId);
  }

  @PostMapping(path = "/resources/{resourceId}/builds", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Begin construction of a resource version")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Build created"),
    @ApiResponse(responseCode = "400", description = "Request validation failed"),
    @ApiResponse(responseCode = "404", description = "Resource not found"),
    @ApiResponse(responseCode = "409", description = "A build or version already exists")
  })
  ResponseEntity<Build> beginBuild(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @Valid @RequestBody BeginBuildRequest request) {
    Build build = service.beginBuild(request.toCommand(resourceId));
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest().path("/current").build().toUri();
    return ResponseEntity.created(location).body(build);
  }

  @GetMapping(
      path = "/resources/{resourceId}/builds/current",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Get the current non-terminal build")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Current build returned"),
    @ApiResponse(responseCode = "404", description = "Resource or current build not found")
  })
  Build getCurrentBuild(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId) {
    return service
        .getCurrentBuild(resourceId)
        .orElseThrow(
            () ->
                new VersionGateException(
                    ErrorCode.BUILD_NOT_FOUND, "Resource " + resourceId + " has no current build"));
  }

  @PostMapping(
      path = "/builds/{buildId}/renew",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Renew a build lease")
  Build renewBuild(
      @PathVariable UUID buildId,
      @Parameter(
              name = FENCING_TOKEN_HEADER,
              description = "Current build ownership generation",
              required = true,
              schema = @Schema(type = "integer", format = "int64", minimum = "1"))
          @RequestHeader(FENCING_TOKEN_HEADER)
          long fencingToken,
      @Valid @RequestBody RenewBuildRequest request) {
    return service.renewBuild(request.toCommand(buildId, fencingToken));
  }

  @PostMapping(path = "/builds/{buildId}/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Start the snapshot phase")
  Build startSnapshotPhase(
      @PathVariable UUID buildId,
      @Parameter(
              name = FENCING_TOKEN_HEADER,
              description = "Current build ownership generation",
              required = true,
              schema = @Schema(type = "integer", format = "int64", minimum = "1"))
          @RequestHeader(FENCING_TOKEN_HEADER)
          long fencingToken) {
    return service.startSnapshotPhase(
        new VersionGateService.BuildTokenCommand(buildId, fencingToken));
  }

  @PutMapping(path = "/builds/{buildId}/components/{componentId}")
  @Operation(
      summary = "Stream an immutable snapshot component",
      parameters = {
        @Parameter(
            name = HttpHeaders.CONTENT_LENGTH,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "integer", format = "int64", minimum = "0")),
        @Parameter(
            name = CHECKSUM_HEADER,
            in = ParameterIn.HEADER,
            description = "Optional hexadecimal SHA-256 of the transmitted bytes",
            schema = @Schema(type = "string", pattern = "^[a-fA-F0-9]{64}$")),
        @Parameter(
            name = HttpHeaders.CONTENT_ENCODING,
            in = ParameterIn.HEADER,
            description = "Optional content encoding recorded in the manifest"),
        @Parameter(
            name = SCHEMA_VERSION_HEADER,
            in = ParameterIn.HEADER,
            description = "Optional producer-defined schema version"),
        @Parameter(
            name = CAPTURED_AT_HEADER,
            in = ParameterIn.HEADER,
            description = "Optional snapshot capture time",
            schema = @Schema(type = "string", format = "date-time"))
      },
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content = {
                @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", format = "binary")),
                @Content(
                    mediaType = "application/x-ndjson",
                    schema = @Schema(type = "string", format = "binary")),
                @Content(
                    mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary"))
              }))
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Component stored"),
    @ApiResponse(responseCode = "400", description = "Header or checksum is invalid"),
    @ApiResponse(responseCode = "404", description = "Build or resource not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Component conflicts or build state is invalid"),
    @ApiResponse(responseCode = "411", description = "Content-Length is required"),
    @ApiResponse(responseCode = "412", description = "Fencing token is stale"),
    @ApiResponse(responseCode = "415", description = "Content-Type is unsupported"),
    @ApiResponse(responseCode = "422", description = "Supplied checksum does not match"),
    @ApiResponse(responseCode = "503", description = "Snapshot storage is unavailable")
  })
  ResponseEntity<SnapshotComponent> submitSnapshotComponent(
      @PathVariable UUID buildId,
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String componentId,
      @Parameter(
              name = FENCING_TOKEN_HEADER,
              description = "Current build ownership generation",
              required = true,
              schema = @Schema(type = "integer", format = "int64", minimum = "1"))
          @RequestHeader(FENCING_TOKEN_HEADER)
          long fencingToken,
      HttpServletRequest request)
      throws IOException {
    long contentLength = requireContentLength(request);
    String contentType = requireSupportedContentType(request.getContentType());
    SnapshotComponent component =
        service.submitSnapshotComponent(
            new VersionGateService.SubmitComponentCommand(
                buildId,
                fencingToken,
                componentId,
                request.getInputStream(),
                contentLength,
                contentType,
                optionalHeader(
                    request, HttpHeaders.CONTENT_ENCODING, DomainValidation.TEXT_MAX_LENGTH),
                optionalSha256Header(request),
                optionalHeader(request, SCHEMA_VERSION_HEADER, DomainValidation.TEXT_MAX_LENGTH),
                optionalInstantHeader(request, CAPTURED_AT_HEADER)));
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/resources/{resourceId}/versions/{version}/components/{componentId}")
            .buildAndExpand(component.resourceId(), component.version(), component.componentId())
            .toUri();
    return ResponseEntity.status(HttpStatus.CREATED).location(location).body(component);
  }

  @PostMapping(path = "/builds/{buildId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Complete a build and finalize its manifest")
  VersionManifest completeBuild(
      @PathVariable UUID buildId,
      @Parameter(
              name = FENCING_TOKEN_HEADER,
              description = "Current build ownership generation",
              required = true,
              schema = @Schema(type = "integer", format = "int64", minimum = "1"))
          @RequestHeader(FENCING_TOKEN_HEADER)
          long fencingToken) {
    return service.completeBuild(new VersionGateService.BuildTokenCommand(buildId, fencingToken));
  }

  @PostMapping(path = "/builds/{buildId}/activate", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Atomically activate a completed build")
  Build activateBuild(
      @PathVariable UUID buildId,
      @Parameter(
              name = FENCING_TOKEN_HEADER,
              description = "Current build ownership generation",
              required = true,
              schema = @Schema(type = "integer", format = "int64", minimum = "1"))
          @RequestHeader(FENCING_TOKEN_HEADER)
          long fencingToken) {
    return service.activateBuild(new VersionGateService.BuildTokenCommand(buildId, fencingToken));
  }

  @PostMapping(path = "/builds/{buildId}/abort", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Abort a build")
  Build abortBuild(
      @PathVariable UUID buildId,
      @Parameter(
              name = FENCING_TOKEN_HEADER,
              description = "Current build ownership generation",
              required = true,
              schema = @Schema(type = "integer", format = "int64", minimum = "1"))
          @RequestHeader(FENCING_TOKEN_HEADER)
          long fencingToken) {
    return service.abortBuild(new VersionGateService.BuildTokenCommand(buildId, fencingToken));
  }

  @GetMapping(
      path = "/resources/{resourceId}/versions/active/manifest",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Get the active version manifest")
  VersionManifest getActiveVersion(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId) {
    return service.getActiveVersion(resourceId);
  }

  @GetMapping(
      path = "/resources/{resourceId}/versions/{version}/manifest",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Get a historical version manifest")
  VersionManifest getVersionManifest(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @PathVariable @PositiveOrZero long version) {
    return service.getVersionManifest(resourceId, version);
  }

  @GetMapping(path = "/resources/{resourceId}/versions/{version}/components/{componentId}")
  @Operation(summary = "Stream a snapshot component")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Component stream",
        content = {
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(type = "string", format = "binary")),
          @Content(
              mediaType = "application/x-ndjson",
              schema = @Schema(type = "string", format = "binary")),
          @Content(
              mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
              schema = @Schema(type = "string", format = "binary"))
        }),
    @ApiResponse(responseCode = "404", description = "Component not found"),
    @ApiResponse(responseCode = "503", description = "Snapshot storage is unavailable")
  })
  ResponseEntity<StreamingResponseBody> streamSnapshotComponent(
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @PathVariable @PositiveOrZero long version,
      @PathVariable @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String componentId) {
    VersionGateService.SnapshotDownload download =
        service.streamSnapshotComponent(resourceId, version, componentId);
    try {
      SnapshotComponent component = download.component();
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.parseMediaType(component.contentType()));
      headers.setContentLength(component.size());
      headers.set(CHECKSUM_HEADER, component.sha256());
      component
          .contentEncoding()
          .ifPresent(
              value ->
                  headers.set(
                      HttpHeaders.CONTENT_ENCODING,
                      requireSafeResponseHeader(HttpHeaders.CONTENT_ENCODING, value)));
      component
          .schemaVersion()
          .ifPresent(
              value ->
                  headers.set(
                      SCHEMA_VERSION_HEADER,
                      requireSafeResponseHeader(SCHEMA_VERSION_HEADER, value)));
      headers.set(CAPTURED_AT_HEADER, component.capturedAt().toString());

      StreamingResponseBody body =
          outputStream -> {
            try (var content = download.content()) {
              content.inputStream().transferTo(outputStream);
            }
          };
      return new ResponseEntity<>(body, headers, HttpStatus.OK);
    } catch (RuntimeException | Error failure) {
      try {
        download.close();
      } catch (Exception cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private static long requireContentLength(HttpServletRequest request) {
    long contentLength = request.getContentLengthLong();
    if (contentLength < 0) {
      throw new HttpApiException(
          HttpStatus.LENGTH_REQUIRED,
          "CONTENT_LENGTH_REQUIRED",
          "Snapshot uploads require a Content-Length header");
    }
    return contentLength;
  }

  private static String requireSupportedContentType(String value) {
    if (!StringUtils.hasText(value)) {
      throw unsupportedContentType("Snapshot uploads require a Content-Type header");
    }
    MediaType mediaType;
    try {
      mediaType = MediaType.parseMediaType(value);
    } catch (IllegalArgumentException exception) {
      throw unsupportedContentType("Snapshot Content-Type is invalid");
    }
    String contentType =
        mediaType.getType().toLowerCase(Locale.ROOT)
            + "/"
            + mediaType.getSubtype().toLowerCase(Locale.ROOT);
    if (!VersionGateService.SUPPORTED_CONTENT_TYPES.contains(contentType)) {
      throw unsupportedContentType("Snapshot Content-Type " + value + " is not supported");
    }
    return contentType;
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

  private static Optional<Instant> optionalInstantHeader(HttpServletRequest request, String name) {
    Optional<String> value = optionalHeader(request, name, DomainValidation.TEXT_MAX_LENGTH);
    if (value.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(value.get()));
    } catch (DateTimeParseException exception) {
      throw new HttpApiException(
          HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", name + " must be an ISO-8601 instant");
    }
  }

  record RegisterResourceRequest(
      @NotBlank @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String resourceId,
      @NotNull SnapshotPolicy snapshotPolicy,
      @NotEmpty
          Set<@NotBlank @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String>
              requiredComponentIds,
      @Size(max = DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE)
          List<@Valid ParticipantRequest> participants) {

    RegisterResourceRequest {
      participants = participants == null ? List.of() : List.copyOf(participants);
    }

    VersionGateService.RegisterResourceCommand toCommand() {
      return new VersionGateService.RegisterResourceCommand(
          resourceId,
          snapshotPolicy,
          requiredComponentIds,
          participants.stream().map(ParticipantRequest::toDomain).toList());
    }
  }

  record ParticipantRequest(
      @NotBlank @Pattern(regexp = DomainValidation.IDENTIFIER_PATTERN) String participantId,
      @NotNull URI baseUri) {

    Participant toDomain() {
      return new Participant(participantId, baseUri);
    }
  }

  record BeginBuildRequest(
      @NotBlank @Size(max = DomainValidation.TEXT_MAX_LENGTH) String owner,
      @NotNull @Positive Long leaseSeconds) {

    VersionGateService.BeginBuildCommand toCommand(String resourceId) {
      return new VersionGateService.BeginBuildCommand(
          resourceId, owner, Duration.ofSeconds(leaseSeconds));
    }
  }

  record RenewBuildRequest(@NotNull @Positive Long leaseSeconds) {

    VersionGateService.RenewBuildCommand toCommand(UUID buildId, long fencingToken) {
      return new VersionGateService.RenewBuildCommand(
          buildId, fencingToken, Duration.ofSeconds(leaseSeconds));
    }
  }
}
