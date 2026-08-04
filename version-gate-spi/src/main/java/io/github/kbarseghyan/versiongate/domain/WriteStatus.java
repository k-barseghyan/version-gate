package io.github.kbarseghyan.versiongate.domain;

/** Durable states of a coordinated write session. */
public enum WriteStatus {
  /** The owner may mutate distributed data under the current lease and fence. */
  WRITING,

  /** Successful completion atomically activated the allocated version. */
  COMPLETED,

  /** The owner reported failure without activating the allocated version. */
  FAILED,

  /** The owner explicitly abandoned the write without activation. */
  ABANDONED,

  /** The authoritative store observed lease expiry without activation. */
  EXPIRED;

  /** Returns whether the session can no longer make lifecycle progress. */
  public boolean isTerminal() {
    return this != WRITING;
  }
}
