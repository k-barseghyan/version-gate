package io.github.kbarseghyan.versiongate.testkit;

import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.port.ControlStore;
import java.time.Duration;

/**
 * Adapter-specific controls required by the reusable {@link ControlStoreContract}.
 *
 * <p>The fixture deliberately does not expose or inject a JVM clock. Production adapters must
 * obtain authoritative time from their storage system after acquiring required locks. An adapter
 * test may advance that authority through a deterministic test clock, a database-specific test
 * mechanism, or another storage-specific facility.
 */
public interface ControlStoreTestFixture {

  /** Returns the fresh store under test. */
  ControlStore store();

  /**
   * Advances the store's authoritative time by exactly the requested duration.
   *
   * @param duration positive amount by which storage-authoritative time must advance
   */
  void advanceAuthoritativeTime(Duration duration);

  /**
   * Creates the deliberately incoherent state needed to test activation failure precedence.
   *
   * <p>The fixture must leave the supplied build {@code READY}, keep its fence and lease intact,
   * remove its finalized manifest, and make the resource's active version differ from the build's
   * recorded base version. This hook is exclusively for contract testing; it is not an adapter or
   * production-store capability.
   *
   * @param readyBuild persisted ready build whose activation preconditions should be corrupted
   */
  void corruptActivationPreconditions(Build readyBuild);
}
