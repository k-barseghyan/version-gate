package io.github.kbarseghyan.versiongate.testkit;

import io.github.kbarseghyan.versiongate.port.ControlStore;
import java.time.Clock;

final class InMemoryControlStoreContractTest extends ControlStoreContract {

  @Override
  protected ControlStore createControlStore(Clock clock) {
    return new InMemoryControlStore(clock);
  }
}
