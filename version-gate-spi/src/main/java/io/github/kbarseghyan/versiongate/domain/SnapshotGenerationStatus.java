package io.github.kbarseghyan.versiongate.domain;

/** Durable states of an external snapshot-generation session. */
public enum SnapshotGenerationStatus {
  /** The provider may aggregate and submit a snapshot under the current lease and fence. */
  GENERATING,

  /** A complete immutable snapshot was atomically published. */
  PUBLISHED,

  /** The provider explicitly terminated generation without publishing. */
  ABORTED,

  /** An admitted writer durably invalidated generation before publication. */
  INVALIDATED,

  /** The authoritative store observed lease expiry before publication. */
  EXPIRED;

  /** Returns whether the session can no longer publish a snapshot. */
  public boolean isTerminal() {
    return this != GENERATING;
  }
}
