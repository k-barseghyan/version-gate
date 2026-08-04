package io.github.kbarseghyan.versiongate.domain;

/** Writer-admission policy when the active version has no stored snapshot. */
public enum MissingCurrentSnapshotPolicy {
  /** Admit the writer and permit a gap in stored snapshot versions. */
  ALLOW_GAP,

  /** Reject the writer until the active version has a stored snapshot. */
  REQUIRE_CURRENT_SNAPSHOT
}
