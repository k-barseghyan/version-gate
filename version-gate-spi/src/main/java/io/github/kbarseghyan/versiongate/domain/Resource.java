package io.github.kbarseghyan.versiongate.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable registration and current active pointer for a logical versioned data set.
 *
 * @param resourceId stable registration identifier
 * @param snapshotPolicy consistency protocol used to capture snapshots
 * @param requiredComponentIds immutable set required to complete every version
 * @param participants immutable callback endpoints used by coordinated capture
 * @param activeVersion currently published version, or {@code null} before first activation
 * @param createdAt registration timestamp
 * @param updatedAt timestamp of the latest persisted resource update
 */
public record Resource(
    String resourceId,
    SnapshotPolicy snapshotPolicy,
    Set<String> requiredComponentIds,
    List<Participant> participants,
    Long activeVersion,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * Creates a validated immutable resource view.
   *
   * @param resourceId stable registration identifier
   * @param snapshotPolicy consistency protocol used to capture snapshots
   * @param requiredComponentIds immutable set required to complete every version
   * @param participants immutable callback endpoints used by coordinated capture
   * @param activeVersion currently published version, or {@code null}
   * @param createdAt registration timestamp
   * @param updatedAt timestamp of the latest persisted resource update
   */
  public Resource {
    DomainValidation.requireIdentifier(resourceId, "resourceId");
    Objects.requireNonNull(snapshotPolicy, "snapshotPolicy is required");
    requiredComponentIds = Set.copyOf(requiredComponentIds);
    if (requiredComponentIds.isEmpty()) {
      throw new IllegalArgumentException("at least one required component is required");
    }
    requiredComponentIds.forEach(
        componentId -> DomainValidation.requireIdentifier(componentId, "componentId"));
    participants = List.copyOf(participants);
    if (participants.size() > DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE) {
      throw new IllegalArgumentException(
          "participants must contain at most "
              + DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE
              + " entries");
    }
    long distinctParticipants =
        participants.stream().map(Participant::participantId).distinct().count();
    if (distinctParticipants != participants.size()) {
      throw new IllegalArgumentException("participant IDs must be unique");
    }
    if (snapshotPolicy == SnapshotPolicy.CLIENT_MANAGED && !participants.isEmpty()) {
      throw new IllegalArgumentException(
          "CLIENT_MANAGED resources cannot register quiescence participants");
    }
    if (snapshotPolicy == SnapshotPolicy.COORDINATED_QUIESCE && participants.isEmpty()) {
      throw new IllegalArgumentException(
          "COORDINATED_QUIESCE resources require at least one participant");
    }
    if (activeVersion != null && activeVersion < 0) {
      throw new IllegalArgumentException("activeVersion must not be negative");
    }
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
  }
}
