package io.github.kbarseghyan.versiongate.port;

import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.Participant;
import java.util.Objects;

/**
 * Outbound callback contract for the cooperative coordinated-quiesce protocol.
 *
 * <p>Implementations must use deterministic per-operation action IDs, reject redirects outside the
 * registered destination boundary, apply bounded timeouts, and map transport/protocol failures to
 * {@code PARTICIPANT_FAILURE}. Participants are responsible for action idempotency and terminal
 * release ordering as documented by the protocol.
 */
public interface ParticipantGateway {

  /**
   * Validates a callback destination before the immutable resource registration is persisted.
   *
   * @param participant endpoint proposed for registration
   */
  default void validateRegistration(Participant participant) {
    Objects.requireNonNull(participant, "participant is required");
  }

  /**
   * Requests that the participant protect its writes for the fenced build.
   *
   * @param participant destination participant
   * @param context immutable fenced callback context
   */
  void quiesce(Participant participant, CallbackContext context);

  /**
   * Requests capture after every participant has acknowledged quiescence.
   *
   * @param participant destination participant
   * @param context immutable fenced callback context
   */
  void capture(Participant participant, CallbackContext context);

  /**
   * Releases protected writes after a complete manifest has been finalized.
   *
   * @param participant destination participant
   * @param context immutable fenced callback context
   */
  void resume(Participant participant, CallbackContext context);

  /**
   * Requests best-effort release for a build that was durably failed or abandoned first.
   *
   * @param participant destination participant
   * @param context immutable fenced callback context
   */
  void abort(Participant participant, CallbackContext context);

  /**
   * Immutable callback context shared by all participant operations.
   *
   * @param build complete fenced build state sent with the callback
   */
  record CallbackContext(Build build) {

    /**
     * Creates a validated callback context.
     *
     * @param build complete fenced build state sent with the callback
     */
    public CallbackContext {
      Objects.requireNonNull(build, "build is required");
    }
  }
}
