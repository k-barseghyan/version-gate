package io.github.kbarseghyan.versiongate.domain;

/** Selects an immutable stored snapshot without acquiring a live-data coordination claim. */
public enum SnapshotSelector {
  /** Resolve one caller-supplied coordinator version. */
  BY_VERSION,

  /** Resolve the snapshot whose version equals the active version. */
  CURRENT,

  /** Resolve the highest stored coordinator version, which may be stale. */
  LATEST_AVAILABLE
}
