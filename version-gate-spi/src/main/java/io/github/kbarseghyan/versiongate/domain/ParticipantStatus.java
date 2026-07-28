package io.github.kbarseghyan.versiongate.domain;

/** Persisted participant progress used to make coordinated callbacks retryable. */
public enum ParticipantStatus {
  /** No callback has yet been acknowledged for the build. */
  PENDING,

  /** The participant acknowledged protection of its writes. */
  QUIESCED,

  /** The participant acknowledged the request to capture its snapshot. */
  CAPTURE_REQUESTED,

  /** The participant acknowledged release after successful completion. */
  RESUMED,

  /** The participant acknowledged release after build termination. */
  ABORTED,

  /** The latest non-terminal participant operation failed and may be retried. */
  FAILED
}
