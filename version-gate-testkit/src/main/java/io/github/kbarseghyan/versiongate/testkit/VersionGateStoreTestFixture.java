package io.github.kbarseghyan.versiongate.testkit;

import io.github.kbarseghyan.versiongate.port.VersionGateStore;
import java.time.Duration;

/** Adapter-neutral fixture capabilities required by {@link VersionGateStoreContract}. */
public interface VersionGateStoreTestFixture {

  /** Returns the fresh authoritative store under test. */
  VersionGateStore store();

  /**
   * Advances the store's authoritative test time.
   *
   * <p>A database adapter should implement this through its database test mechanism; this contract
   * does not imply an injected coordinator-JVM clock in production.
   */
  void advanceAuthoritativeTime(Duration duration);
}
