package io.github.kbarseghyan.versiongate.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable registration and active-version pointer for one coordinated resource.
 *
 * @param resourceId stable resource identifier
 * @param policies immutable resource-scoped business policies
 * @param activeVersion last successfully completed write version, or {@code null} before the first
 *     successful write
 * @param createdAt registration timestamp
 * @param updatedAt latest persisted update timestamp
 */
public record Resource(
    String resourceId,
    ResourcePolicies policies,
    Long activeVersion,
    Instant createdAt,
    Instant updatedAt) {

  /** Creates a validated immutable resource view. */
  public Resource {
    DomainValidation.requireIdentifier(resourceId, "resourceId");
    Objects.requireNonNull(policies, "policies are required");
    if (activeVersion != null && activeVersion < 0) {
      throw new IllegalArgumentException("activeVersion must not be negative");
    }
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
  }
}
