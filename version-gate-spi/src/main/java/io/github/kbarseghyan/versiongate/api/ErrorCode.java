package io.github.kbarseghyan.versiongate.api;

/** Stable, storage-neutral error codes exposed by the Java and HTTP contracts. */
public enum ErrorCode {
  /** A caller supplied an invalid command, field, header, or payload shape. */
  VALIDATION_FAILED,

  /** The requested resource registration does not exist. */
  RESOURCE_NOT_FOUND,

  /** A resource with the requested identifier is already registered. */
  RESOURCE_ALREADY_EXISTS,

  /** The resource has no successfully completed write version. */
  ACTIVE_VERSION_NOT_FOUND,

  /** The requested write session does not exist. */
  WRITE_SESSION_NOT_FOUND,

  /** Another write session currently owns the resource. */
  WRITE_ALREADY_ACTIVE,

  /** The requested coordinated live-read session does not exist. */
  LIVE_READ_SESSION_NOT_FOUND,

  /** One or more coordinated live reads block the requested write. */
  LIVE_READ_ACTIVE,

  /** The requested snapshot-generation session does not exist. */
  SNAPSHOT_SESSION_NOT_FOUND,

  /** A snapshot-generation session already exists for the resource version. */
  SNAPSHOT_SESSION_ALREADY_EXISTS,

  /** The resource policy gives an active snapshot-generation session priority. */
  SNAPSHOT_GENERATION_ACTIVE,

  /** Snapshot operations are disabled for the resource. */
  SNAPSHOT_SUPPORT_DISABLED,

  /** A writer requires an immutable snapshot of the active version before admission. */
  CURRENT_SNAPSHOT_REQUIRED,

  /** A writer durably invalidated the snapshot-generation session. */
  SNAPSHOT_INVALIDATED,

  /** The requested immutable snapshot does not exist. */
  SNAPSHOT_NOT_FOUND,

  /** The active version exists but has no immutable stored snapshot. */
  CURRENT_SNAPSHOT_UNAVAILABLE,

  /** Snapshot resolution is rejected because a write is active. */
  WRITE_IN_PROGRESS,

  /** An immutable resource/version snapshot was resubmitted with a different representation. */
  SNAPSHOT_CONFLICT,

  /** Snapshot bytes do not match the declared SHA-256 digest. */
  CHECKSUM_MISMATCH,

  /** The supplied fencing token is not the session's current token. */
  STALE_FENCING_TOKEN,

  /** The session lease expired before the requested operation could commit. */
  LEASE_EXPIRED,

  /** The requested operation is not valid in the session's durable lifecycle state. */
  INVALID_SESSION_TRANSITION,

  /** A storage adapter failed or returned data that violated its contract. */
  STORAGE_FAILURE
}
