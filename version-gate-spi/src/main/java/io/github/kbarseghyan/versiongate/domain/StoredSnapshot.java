package io.github.kbarseghyan.versiongate.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable metadata for one complete, visible snapshot payload.
 *
 * @param resourceId owning resource identifier
 * @param snapshotVersion Version Gate coordinator version
 * @param contentLength exact stored byte length
 * @param contentType media type of the exact stored representation
 * @param contentEncoding optional content encoding of the exact stored representation
 * @param sha256 lowercase SHA-256 digest of the exact stored bytes
 * @param storedAt atomic publication timestamp
 */
public record StoredSnapshot(
    String resourceId,
    long snapshotVersion,
    long contentLength,
    String contentType,
    Optional<String> contentEncoding,
    String sha256,
    Instant storedAt) {

  /** Creates validated immutable snapshot metadata. */
  public StoredSnapshot {
    DomainValidation.requireIdentifier(resourceId, "resourceId");
    if (snapshotVersion < 0) {
      throw new IllegalArgumentException("snapshotVersion must not be negative");
    }
    if (contentLength < 0) {
      throw new IllegalArgumentException("contentLength must not be negative");
    }
    DomainValidation.requireNonBlank(contentType, "contentType", DomainValidation.TEXT_MAX_LENGTH);
    contentEncoding = Objects.requireNonNull(contentEncoding, "contentEncoding is required");
    contentEncoding.ifPresent(
        value ->
            DomainValidation.requireNonBlank(
                value, "contentEncoding", DomainValidation.TEXT_MAX_LENGTH));
    sha256 = DomainValidation.requireSha256(sha256);
    Objects.requireNonNull(storedAt, "storedAt is required");
  }

  /** Returns whether every immutable representation-identity field matches another snapshot. */
  public boolean hasSameRepresentationIdentity(StoredSnapshot other) {
    Objects.requireNonNull(other, "other is required");
    return contentLength == other.contentLength
        && contentType.equals(other.contentType)
        && contentEncoding.equals(other.contentEncoding)
        && sha256.equals(other.sha256);
  }
}
