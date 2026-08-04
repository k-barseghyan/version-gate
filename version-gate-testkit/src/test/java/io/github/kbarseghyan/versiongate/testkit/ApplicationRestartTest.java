package io.github.kbarseghyan.versiongate.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
  void newApplicationInstanceContinuesFromTheSameDurableStoreState() {
    MutableClock clock = new MutableClock(Instant.parse("2030-01-02T03:04:05Z"));
    InMemoryVersionGateStore store = new InMemoryVersionGateStore(clock);
    VersionGateService firstApplication = new VersionGateService(store, Duration.ofHours(1), 1024);
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
            new VersionGateService.BeginWriteCommand("catalog", "writer", Duration.ofMinutes(5)));
    firstApplication.completeWrite(
        new VersionGateService.SessionCommand(write.sessionId(), write.fencingToken()));

    VersionGateService restartedApplication =
        new VersionGateService(store, Duration.ofHours(1), 1024);
    var read =
        restartedApplication.beginLiveRead(
            new VersionGateService.BeginLiveReadCommand(
                "catalog", "reader", Duration.ofMinutes(5)));

    assertEquals(1L, restartedApplication.getResource("catalog").activeVersion());
    assertEquals(1, read.boundVersion());
  }
}
