package io.github.kbarseghyan.versiongate.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable progress for one participant in one coordinated build.
 *
 * @param buildId build whose callback progress is recorded
 * @param participantId registered participant identifier
 * @param status latest durable protocol status
 * @param detail optional diagnostic detail about the latest status
 * @param updatedAt timestamp of the latest durable update
 */
public record ParticipantState(
    UUID buildId,
    String participantId,
    ParticipantStatus status,
    Optional<String> detail,
    Instant updatedAt) {

  /**
   * Creates a validated participant progress record.
   *
   * @param buildId build whose callback progress is recorded
   * @param participantId registered participant identifier
   * @param status latest durable protocol status
   * @param detail optional diagnostic detail about the latest status
   * @param updatedAt timestamp of the latest durable update
   */
  public ParticipantState {
    Objects.requireNonNull(buildId, "buildId is required");
    DomainValidation.requireIdentifier(participantId, "participantId");
    Objects.requireNonNull(status, "status is required");
    detail = Objects.requireNonNull(detail, "detail is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
  }
}
