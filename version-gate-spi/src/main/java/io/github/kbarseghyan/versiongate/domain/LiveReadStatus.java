package io.github.kbarseghyan.versiongate.domain;

/** Durable states of a coordinated live-read session. */
public enum LiveReadStatus {
  /** The reader holds a live-data coordination claim. */
  READING,

  /** The reader successfully released its claim. */
  COMPLETED,

  /** The reader explicitly abandoned its claim. */
  ABANDONED,

  /** The authoritative store observed lease expiry and released the claim. */
  EXPIRED;

  /** Returns whether the session no longer blocks writers. */
  public boolean isTerminal() {
    return this != READING;
  }
}
