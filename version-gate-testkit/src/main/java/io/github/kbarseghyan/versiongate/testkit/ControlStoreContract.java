package io.github.kbarseghyan.versiongate.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.BuildStatus;
import io.github.kbarseghyan.versiongate.domain.SnapshotPolicy;
import io.github.kbarseghyan.versiongate.port.ControlStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reusable minimum semantic contract for {@link ControlStore} implementations.
 *
 * <p>Adapter tests extend this class and construct a fresh store backed by the supplied
 * deterministic clock. Adapters may add stronger integration tests for isolation, crash recovery,
 * migrations, and their storage-authoritative clock.
 */
public abstract class ControlStoreContract {

  private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
  private static final Duration LEASE = Duration.ofMinutes(5);

  private MutableClock clock;
  private ControlStore store;

  /**
   * Creates an empty control store for one contract-test invocation.
   *
   * @param clock deterministic authoritative clock for the test store
   * @return empty store instance
   */
  protected abstract ControlStore createControlStore(Clock clock);

  @BeforeEach
  final void initializeContractStore() {
    clock = new MutableClock(NOW);
    store = createControlStore(clock);
  }

  @Test
  final void allocatesMonotonicCoordinatorVersionsAndFencingTokens() {
    registerClientResource();

    Build first = store.beginBuild("catalog", "first-owner", LEASE);
    Build abandoned = store.abortBuild(first.buildId(), first.fencingToken());
    Build second = store.beginBuild("catalog", "second-owner", LEASE);

    assertEquals(BuildStatus.ABANDONED, abandoned.status());
    assertTrue(first.targetVersion() >= 0);
    assertTrue(second.targetVersion() > first.targetVersion());
    assertTrue(second.fencingToken() > first.fencingToken());
    assertNull(first.baseActiveVersion());
    assertNull(second.baseActiveVersion());
  }

  @Test
  final void rejectsASecondLiveBuildWithoutAllocatingClientChosenState() {
    registerClientResource();
    Build first = store.beginBuild("catalog", "first-owner", LEASE);

    VersionGateException failure =
        assertThrows(
            VersionGateException.class, () -> store.beginBuild("catalog", "second-owner", LEASE));

    assertEquals(ErrorCode.BUILD_ALREADY_EXISTS, failure.code());
    assertEquals(first, store.findCurrentBuild("catalog").orElseThrow());
  }

  @Test
  final void expiresTheCurrentBuildUsingTheAdapterClock() {
    registerClientResource();
    Build build = store.beginBuild("catalog", "owner", Duration.ofSeconds(10));

    clock.advance(Duration.ofSeconds(10));

    assertTrue(store.findCurrentBuild("catalog").isEmpty());
    assertEquals(BuildStatus.ABANDONED, store.findBuild(build.buildId()).orElseThrow().status());
  }

  private void registerClientResource() {
    store.registerResource("catalog", SnapshotPolicy.CLIENT_MANAGED, Set.of("products"), List.of());
  }
}
