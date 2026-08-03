package io.github.kbarseghyan.versiongate.testkit;

import io.github.kbarseghyan.versiongate.domain.Build;
import java.time.Duration;
import java.time.Instant;

final class InMemoryControlStoreContractTest extends ControlStoreContract {

  @Override
  protected ControlStoreTestFixture createControlStoreFixture() {
    MutableClock clock = new MutableClock(Instant.parse("2030-01-02T03:04:05Z"));
    InMemoryControlStore store = new InMemoryControlStore(clock);
    return new ControlStoreTestFixture() {
      @Override
      public InMemoryControlStore store() {
        return store;
      }

      @Override
      public void advanceAuthoritativeTime(Duration duration) {
        clock.advance(duration);
      }

      @Override
      public void corruptActivationPreconditions(Build readyBuild) {
        store.corruptActivationPreconditionsForTest(readyBuild);
      }
    };
  }
}
