package io.github.kbarseghyan.versiongate.api;

/** Stable, storage-neutral error codes exposed by the Java and HTTP contracts. */
public enum ErrorCode {
  /** A caller supplied an invalid command, field, header, or payload shape. */
  VALIDATION_FAILED,

  /** The requested resource registration does not exist. */
  RESOURCE_NOT_FOUND,

  /** The requested candidate build does not exist. */
  BUILD_NOT_FOUND,

  /** The requested public version does not exist or is not active. */
  VERSION_NOT_FOUND,

  /** The requested snapshot component does not exist. */
  COMPONENT_NOT_FOUND,

  /** A resource with the requested identifier is already registered. */
  RESOURCE_ALREADY_EXISTS,

  /** A non-terminal build already exists for the resource. */
  BUILD_ALREADY_EXISTS,

  /** The requested target version is already in use. */
  VERSION_ALREADY_EXISTS,

  /** An immutable component identity was reused with different content. */
  COMPONENT_CONFLICT,

  /** Snapshot bytes do not match the declared SHA-256 digest. */
  CHECKSUM_MISMATCH,

  /** A build cannot complete because one or more required components are absent. */
  INCOMPLETE_SNAPSHOT,

  /** The requested operation is not valid in the build's current lifecycle state. */
  INVALID_BUILD_TRANSITION,

  /** The supplied fencing token is not the build's current token. */
  STALE_FENCING_TOKEN,

  /** The build lease expired before the requested operation could commit. */
  LEASE_EXPIRED,

  /** Compare-and-set activation failed because the active version changed. */
  ACTIVATION_CONFLICT,

  /** A coordinated participant callback or protocol invariant failed. */
  PARTICIPANT_FAILURE,

  /** Authoritative component metadata refers to a payload that is absent. */
  SNAPSHOT_OBJECT_MISSING,

  /** A storage adapter failed or returned data that violated its contract. */
  STORAGE_FAILURE
}
