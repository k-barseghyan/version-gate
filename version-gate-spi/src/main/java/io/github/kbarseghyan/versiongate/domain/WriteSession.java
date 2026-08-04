package io.github.kbarseghyan.versiongate.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable durable view of one exclusive leased and fenced write session.
 *
 * @param sessionId stable session identifier
 * @param resourceId owning resource identifier
 * @param allocatedVersion monotonically allocated coordinator version
 * @param baseActiveVersion active version observed at admission, or {@code null}
 * @param status durable session status
 * @param owner caller-defined diagnostic owner
 * @param fencingToken positive token guarding session mutations
 * @param leaseExpiresAt exclusive upper bound of the lease
 * @param failureReason bounded failure reason, present exactly for failed sessions
 * @param createdAt creation timestamp
 * @param updatedAt latest persisted update timestamp
 */
public record WriteSession(
    UUID sessionId,
    String resourceId,
    long allocatedVersion,
    Long baseActiveVersion,
    WriteStatus status,
    String owner,
    long fencingToken,
    Instant leaseExpiresAt,
    Optional<String> failureReason,
    Instant createdAt,
    Instant updatedAt) {

  /** Creates a validated immutable write-session view. */
  public WriteSession {
    Objects.requireNonNull(sessionId, "sessionId is required");
    DomainValidation.requireIdentifier(resourceId, "resourceId");
    if (allocatedVersion < 0) {
      throw new IllegalArgumentException("allocatedVersion must not be negative");
    }
    if (baseActiveVersion != null && baseActiveVersion < 0) {
      throw new IllegalArgumentException("baseActiveVersion must not be negative");
    }
    if (baseActiveVersion != null && allocatedVersion <= baseActiveVersion) {
      throw new IllegalArgumentException("allocatedVersion must be greater than baseActiveVersion");
    }
    Objects.requireNonNull(status, "status is required");
    DomainValidation.requireNonBlank(owner, "owner", DomainValidation.TEXT_MAX_LENGTH);
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken must be positive");
    }
    Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt is required");
    failureReason = Objects.requireNonNull(failureReason, "failureReason is required");
    failureReason.ifPresent(
        reason ->
            DomainValidation.requireNonBlank(
                reason, "failureReason", DomainValidation.TEXT_MAX_LENGTH));
    if ((status == WriteStatus.FAILED) != failureReason.isPresent()) {
      throw new IllegalArgumentException("failureReason must be present exactly for FAILED status");
    }
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
