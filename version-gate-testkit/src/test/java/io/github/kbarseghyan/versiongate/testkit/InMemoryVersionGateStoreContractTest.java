package io.github.kbarseghyan.versiongate.testkit;

import java.time.Instant;

final class InMemoryVersionGateStoreContractTest extends VersionGateStoreContract {

  @Override
  protected VersionGateStoreTestFixture createFixture() {
    MutableClock clock = new MutableClock(Instant.parse("2030-01-02T03:04:05Z"));
    InMemoryVersionGateStore store = new InMemoryVersionGateStore(clock);
    return new VersionGateStoreTestFixture() {
      @Override
      public InMemoryVersionGateStore store() {
        return store;
      }

      @Override
      public void advanceAuthoritativeTime(java.time.Duration duration) {
        clock.advance(duration);
      }
    };
  }
}
