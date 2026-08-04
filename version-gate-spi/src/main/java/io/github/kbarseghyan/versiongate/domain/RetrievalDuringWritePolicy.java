package io.github.kbarseghyan.versiongate.domain;

/** Snapshot-resolution policy while a write is active. */
public enum RetrievalDuringWritePolicy {
  /** Resolve current or latest-available snapshots normally. */
  ALLOW_WHILE_WRITING,

  /** Reject current or latest-available resolution while a write is active. */
  REJECT_IF_WRITING
}
