package io.github.kbarseghyan.versiongate.testkit;

import io.github.kbarseghyan.versiongate.port.SnapshotStore;

final class InMemorySnapshotStoreContractTest extends SnapshotStoreContract {

  @Override
  protected SnapshotStore createSnapshotStore() {
    return new InMemorySnapshotStore();
  }
}
