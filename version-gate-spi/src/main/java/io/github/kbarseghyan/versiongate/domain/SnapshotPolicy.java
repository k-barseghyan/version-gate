package io.github.kbarseghyan.versiongate.domain;

/** Selects client-managed capture or the cooperative participant callback protocol. */
public enum SnapshotPolicy {
  /** The client controls consistency and submits components without participant callbacks. */
  CLIENT_MANAGED,

  /** Version Gate coordinates quiesce, capture, and release callbacks with participants. */
  COORDINATED_QUIESCE
}
