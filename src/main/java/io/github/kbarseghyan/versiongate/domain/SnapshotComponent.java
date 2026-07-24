package io.github.kbarseghyan.versiongate.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative metadata for one immutable payload in a candidate version.
 *
 * @param buildId build that captured the payload
 * @param resourceId owning resource identifier
 * @param version candidate version containing the component
 * @param componentId required component identifier
 * @param objectKey storage-neutral immutable logical key
 * @param contentType media type of the exact stored representation
 * @param contentEncoding optional content encoding of the stored representation
 * @param sha256 lowercase SHA-256 digest of the exact stored bytes
 * @param size exact stored length in bytes
 * @param schemaVersion optional caller-defined payload schema version
 * @param capturedAt instant at which the payload was captured
 */
public record SnapshotComponent(
    UUID buildId,
    String resourceId,
    long version,
    String componentId,
    String objectKey,
    String contentType,
    Optional<String> contentEncoding,
    String sha256,
    long size,
    Optional<String> schemaVersion,
    Instant capturedAt) {

  /**
   * Creates validated authoritative component metadata.
   *
   * @param buildId build that captured the payload
   * @param resourceId owning resource identifier
   * @param version candidate version containing the component
   * @param componentId required component identifier
   * @param objectKey storage-neutral immutable logical key
   * @param contentType media type of the exact stored representation
   * @param contentEncoding optional content encoding of the stored representation
   * @param sha256 SHA-256 digest of the exact stored bytes
   * @param size exact stored length in bytes
   * @param schemaVersion optional caller-defined payload schema version
   * @param capturedAt instant at which the payload was captured
   */
  public SnapshotComponent {
    Objects.requireNonNull(buildId, "buildId is required");
    DomainValidation.requireIdentifier(resourceId, "resourceId");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    DomainValidation.requireIdentifier(componentId, "componentId");
    DomainValidation.requireNonBlank(objectKey, "objectKey");
    DomainValidation.requireNonBlank(contentType, "contentType");
    contentEncoding = Objects.requireNonNull(contentEncoding, "contentEncoding is required");
    contentEncoding.ifPresent(
        value ->
            DomainValidation.requireNonBlank(
                value, "contentEncoding", DomainValidation.TEXT_MAX_LENGTH));
    sha256 = DomainValidation.requireSha256(sha256);
    if (size < 0) {
      throw new IllegalArgumentException("size must not be negative");
    }
    schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion is required");
    schemaVersion.ifPresent(
        value ->
            DomainValidation.requireNonBlank(
                value, "schemaVersion", DomainValidation.TEXT_MAX_LENGTH));
    Objects.requireNonNull(capturedAt, "capturedAt is required");
  }
}
