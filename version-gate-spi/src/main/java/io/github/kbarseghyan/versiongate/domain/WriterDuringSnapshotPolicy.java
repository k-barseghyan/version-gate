package io.github.kbarseghyan.versiongate.domain;

/** Conflict policy for a writer arriving during snapshot generation. */
public enum WriterDuringSnapshotPolicy {
  /** Reject the writer and allow snapshot generation to continue. */
  BLOCK_WRITER,

  /** Durably invalidate snapshot generation and atomically admit the writer. */
  INVALIDATE_SNAPSHOT
}
