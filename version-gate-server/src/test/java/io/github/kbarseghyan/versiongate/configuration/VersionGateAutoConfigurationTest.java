package io.github.kbarseghyan.versiongate.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.kbarseghyan.versiongate.application.VersionGateService;
import io.github.kbarseghyan.versiongate.port.ControlStore;
import io.github.kbarseghyan.versiongate.port.SnapshotStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class VersionGateAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(VersionGateAutoConfiguration.class)
          .withBean(JsonMapper.class, () -> JsonMapper.builder().findAndAddModules().build());

  @Test
  void composesTheCoreFromExternalPortBeansWithoutComponentScanning() {
    contextRunner
        .withBean(ControlStore.class, () -> mock(ControlStore.class))
        .withBean(SnapshotStore.class, () -> mock(SnapshotStore.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(VersionGateService.class);
              assertThat(context).hasBean("versionGateController");
              assertThat(context).hasBean("versionGateExceptionHandler");
              assertThat(context).hasBean("participantGateway");

              VersionGateProperties properties = context.getBean(VersionGateProperties.class);
              assertThat(properties.maximumLease()).isEqualTo(Duration.ofHours(1));
              assertThat(properties.maximumComponentSize().toBytes())
                  .isEqualTo(1024L * 1024 * 1024);
              assertThat(properties.maximumParticipantsPerResource()).isEqualTo(8);
              assertThat(properties.participant().allowedBaseUris()).isEmpty();
            });
  }

  @Test
  void failsFastWhenAStoragePortIsMissing() {
    contextRunner
        .withBean(ControlStore.class, () -> mock(ControlStore.class))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("SnapshotStore");
            });
  }
}
