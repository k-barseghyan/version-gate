package io.github.kbarseghyan.versiongate.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Independent, immutable policies applied to one resource.
 *
 * @param snapshotSupport whether the resource supports snapshots
 * @param missingCurrentSnapshotPolicy writer admission when the active snapshot is absent
 * @param writerDuringSnapshotPolicy writer conflict policy, present only when snapshots are enabled
 * @param defaultSnapshotSelector default retrieval selector, present only when snapshots are
 *     enabled
 * @param retrievalDuringWritePolicy retrieval conflict policy, present only when snapshots are
 *     enabled
 */
public record ResourcePolicies(
    SnapshotSupport snapshotSupport,
    MissingCurrentSnapshotPolicy missingCurrentSnapshotPolicy,
    Optional<WriterDuringSnapshotPolicy> writerDuringSnapshotPolicy,
    Optional<SnapshotSelector> defaultSnapshotSelector,
    Optional<RetrievalDuringWritePolicy> retrievalDuringWritePolicy) {

  /** Creates and validates one supported policy combination. */
  public ResourcePolicies {
    Objects.requireNonNull(snapshotSupport, "snapshotSupport is required");
    Objects.requireNonNull(
        missingCurrentSnapshotPolicy, "missingCurrentSnapshotPolicy is required");
    writerDuringSnapshotPolicy =
        Objects.requireNonNull(
            writerDuringSnapshotPolicy, "writerDuringSnapshotPolicy is required");
    defaultSnapshotSelector =
        Objects.requireNonNull(defaultSnapshotSelector, "defaultSnapshotSelector is required");
    retrievalDuringWritePolicy =
        Objects.requireNonNull(
            retrievalDuringWritePolicy, "retrievalDuringWritePolicy is required");

    if (snapshotSupport == SnapshotSupport.DISABLED) {
      if (missingCurrentSnapshotPolicy != MissingCurrentSnapshotPolicy.ALLOW_GAP) {
        throw new IllegalArgumentException("snapshot-disabled resources must use ALLOW_GAP");
      }
      if (writerDuringSnapshotPolicy.isPresent()
          || defaultSnapshotSelector.isPresent()
          || retrievalDuringWritePolicy.isPresent()) {
        throw new IllegalArgumentException(
            "snapshot-disabled resources cannot configure snapshot policies");
      }
    } else {
      if (writerDuringSnapshotPolicy.isEmpty()
          || defaultSnapshotSelector.isEmpty()
          || retrievalDuringWritePolicy.isEmpty()) {
        throw new IllegalArgumentException(
            "snapshot-enabled resources require all snapshot policies");
      }
      if (writerDuringSnapshotPolicy.orElseThrow() == WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT
          && missingCurrentSnapshotPolicy != MissingCurrentSnapshotPolicy.ALLOW_GAP) {
        throw new IllegalArgumentException("INVALIDATE_SNAPSHOT is valid only with ALLOW_GAP");
      }
    }
  }
}
