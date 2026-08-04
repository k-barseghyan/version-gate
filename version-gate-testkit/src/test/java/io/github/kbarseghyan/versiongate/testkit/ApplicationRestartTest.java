package io.github.kbarseghyan.versiongate.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kbarseghyan.versiongate.application.VersionGateService;
import io.github.kbarseghyan.versiongate.domain.MissingCurrentSnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.ResourcePolicies;
import io.github.kbarseghyan.versiongate.domain.SnapshotSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ApplicationRestartTest {

  @Test
  void reconstructedStoreAndApplicationContinueFromTheSameAuthoritativeState() {
    MutableClock clock = new MutableClock(Instant.parse("2030-01-02T03:04:05Z"));
    InMemoryVersionGateStore.BackingState backingState =
        new InMemoryVersionGateStore.BackingState(clock);
    InMemoryVersionGateStore firstStore = new InMemoryVersionGateStore(backingState);
    VersionGateService firstApplication =
        new VersionGateService(firstStore, Duration.ofHours(1), 1024);
    firstApplication.registerResource(
        new VersionGateService.RegisterResourceCommand(
            "catalog",
            new ResourcePolicies(
                SnapshotSupport.DISABLED,
                MissingCurrentSnapshotPolicy.ALLOW_GAP,
                Optional.empty(),
                Optional.empty(),
                Optional.empty())));
    var write =
        firstApplication.beginWrite(
            new VersionGateService.BeginWriteCommand(
                "catalog", "writer", Duration.ofMinutes(5), "write-request"));

    InMemoryVersionGateStore reopenedStore = new InMemoryVersionGateStore(backingState);
    VersionGateService restartedApplication =
        new VersionGateService(reopenedStore, Duration.ofHours(1), 1024);
    var replayedWrite =
        restartedApplication.beginWrite(
            new VersionGateService.BeginWriteCommand(
                "catalog", "writer", Duration.ofMinutes(5), "write-request"));
    restartedApplication.completeWrite(
        new VersionGateService.SessionCommand(
            replayedWrite.session().sessionId(), replayedWrite.session().fencingToken()));
    var read =
        restartedApplication.beginLiveRead(
            new VersionGateService.BeginLiveReadCommand(
                "catalog", "reader", Duration.ofMinutes(5), "read-request"));

    assertNotSame(firstStore, reopenedStore);
    assertTrue(replayedWrite.replayed());
    assertEquals(write.session().sessionId(), replayedWrite.session().sessionId());
    assertEquals(1L, restartedApplication.getResource("catalog").activeVersion());
    assertEquals(1, read.session().boundVersion());
  }
}
