package io.github.kbarseghyan.versiongate.domain;

/** Whether a resource supports snapshot generation and policy-based snapshot retrieval. */
public enum SnapshotSupport {
  /** The resource uses only coordinated writes and live reads. */
  DISABLED,

  /** The resource also supports immutable snapshot generation and retrieval. */
  ENABLED
}
