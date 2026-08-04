package io.github.kbarseghyan.versiongate.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable durable view of one leased and fenced external snapshot-generation session.
 *
 * @param sessionId stable session identifier
 * @param resourceId owning resource identifier
 * @param snapshotVersion active coordinator version bound at admission
 * @param status durable session status
 * @param owner caller-defined diagnostic provider or owner
 * @param fencingToken positive token guarding session mutations
 * @param leaseExpiresAt exclusive upper bound of the lease
 * @param createdAt creation timestamp
 * @param updatedAt latest persisted update timestamp
 */
public record SnapshotGenerationSession(
    UUID sessionId,
    String resourceId,
    long snapshotVersion,
    SnapshotGenerationStatus status,
    String owner,
    long fencingToken,
    Instant leaseExpiresAt,
    Instant createdAt,
    Instant updatedAt) {

  /** Creates a validated immutable snapshot-generation-session view. */
  public SnapshotGenerationSession {
    Objects.requireNonNull(sessionId, "sessionId is required");
    DomainValidation.requireIdentifier(resourceId, "resourceId");
    if (snapshotVersion < 0) {
      throw new IllegalArgumentException("snapshotVersion must not be negative");
    }
    Objects.requireNonNull(status, "status is required");
    DomainValidation.requireNonBlank(owner, "owner", DomainValidation.TEXT_MAX_LENGTH);
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken must be positive");
    }
    Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
    if (!leaseExpiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("leaseExpiresAt must be after createdAt");
    }
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
  }

  /** Returns whether the supplied authoritative instant is at or after the lease deadline. */
  public boolean leaseExpiredAt(Instant instant) {
    Objects.requireNonNull(instant, "instant is required");
    return !leaseExpiresAt.isAfter(instant);
  }
}
