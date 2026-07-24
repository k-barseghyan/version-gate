package io.github.kbarseghyan.versiongate.domain;

/** Persisted states in the candidate build state machine. */
public enum BuildStatus {
  /** The owner may renew the lease and prepare snapshot production. */
  BUILDING,

  /** Coordinated participants are being asked to protect writes. */
  QUIESCING,

  /** Immutable snapshot components may be submitted. */
  SNAPSHOTTING,

  /** The complete immutable manifest is finalized and awaiting activation. */
  READY,

  /** Activation succeeded and the version is publicly visible. */
  ACTIVE,

  /** The build was durably terminated because processing failed. */
  FAILED,

  /** The build was durably terminated by explicit abort or lease expiry. */
  ABANDONED;

  /**
   * Reports whether no further successful lifecycle progress is possible.
   *
   * @return {@code true} for active, failed, or abandoned builds
   */
  public boolean isTerminal() {
    return this == ACTIVE || this == FAILED || this == ABANDONED;
  }
}
