package io.github.kbarseghyan.versiongate.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable view of one fenced candidate-version lifecycle.
 *
 * @param buildId stable identifier for this build attempt
 * @param resourceId registered resource identifier
 * @param targetVersion candidate version produced by the build
 * @param baseActiveVersion active version observed when the build began, or {@code null}
 * @param status current persisted lifecycle state
 * @param owner caller-defined owner used for diagnostics
 * @param fencingToken positive token that guards every build mutation
 * @param leaseExpiresAt exclusive upper bound of the build's current lease
 * @param createdAt creation timestamp
 * @param updatedAt latest persisted update timestamp
 */
public record Build(
    UUID buildId,
    String resourceId,
    long targetVersion,
    Long baseActiveVersion,
    BuildStatus status,
    String owner,
    long fencingToken,
    Instant leaseExpiresAt,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * Creates a validated immutable build view.
   *
   * @param buildId stable identifier for this build attempt
   * @param resourceId registered resource identifier
   * @param targetVersion candidate version produced by the build
   * @param baseActiveVersion active version observed when the build began, or {@code null}
   * @param status current persisted lifecycle state
   * @param owner caller-defined owner used for diagnostics
   * @param fencingToken positive token that guards every build mutation
   * @param leaseExpiresAt exclusive upper bound of the build's current lease
   * @param createdAt creation timestamp
   * @param updatedAt latest persisted update timestamp
   */
  public Build {
    Objects.requireNonNull(buildId, "buildId is required");
    DomainValidation.requireIdentifier(resourceId, "resourceId");
    if (targetVersion < 0) {
      throw new IllegalArgumentException("targetVersion must not be negative");
    }
    if (baseActiveVersion != null && baseActiveVersion < 0) {
      throw new IllegalArgumentException("baseActiveVersion must not be negative");
    }
    if (baseActiveVersion != null && targetVersion <= baseActiveVersion) {
      throw new IllegalArgumentException("targetVersion must be greater than baseActiveVersion");
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

  /**
   * Tests the lease using an inclusive expiry boundary.
   *
   * @param instant instant to compare with the lease deadline
   * @return {@code true} when {@code instant} is equal to or after the deadline
   */
  public boolean leaseExpiredAt(Instant instant) {
    return !leaseExpiresAt.isAfter(instant);
  }
}
