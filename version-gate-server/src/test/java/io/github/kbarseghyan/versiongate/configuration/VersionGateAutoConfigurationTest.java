package io.github.kbarseghyan.versiongate.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.kbarseghyan.versiongate.application.VersionGateService;
import io.github.kbarseghyan.versiongate.port.VersionGateStore;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class VersionGateAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(VersionGateAutoConfiguration.class);

  @Test
  void composesTheFourFlowServerFromExactlyOneAuthoritativeStore() {
    contextRunner
        .withBean(VersionGateStore.class, () -> mock(VersionGateStore.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(VersionGateStore.class);
              assertThat(context).hasSingleBean(VersionGateService.class);
              assertThat(context).hasBean("versionGateController");
              assertThat(context).hasBean("versionGateExceptionHandler");
              assertThat(context).doesNotHaveBean("participantGateway");
              assertThat(context).doesNotHaveBean("participantHttpClient");
              assertThat(context).doesNotHaveBean("expiredBuildSweeper");
              assertThat(context).doesNotHaveBean(Clock.class);
              assertThat(context).doesNotHaveBean(HttpClient.class);

              VersionGateProperties properties = context.getBean(VersionGateProperties.class);
              assertThat(properties.maximumLease()).isEqualTo(Duration.ofHours(1));
              assertThat(properties.maximumSnapshotSize().toBytes()).isEqualTo(1024L * 1024 * 1024);
            });
  }

  @Test
  void bindsExplicitTechnicalLimits() {
    contextRunner
        .withBean(VersionGateStore.class, () -> mock(VersionGateStore.class))
        .withPropertyValues(
            "version-gate.maximum-lease=PT10M", "version-gate.maximum-snapshot-size=64MB")
        .run(
            context -> {
              VersionGateProperties properties = context.getBean(VersionGateProperties.class);
              assertThat(properties.maximumLease()).isEqualTo(Duration.ofMinutes(10));
              assertThat(properties.maximumSnapshotSize().toBytes()).isEqualTo(64L * 1024 * 1024);
            });
  }

  @Test
  void failsFastWhenTheAuthoritativeStoreIsMissing() {
    contextRunner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasMessageContaining("VersionGateStore");
        });
  }

  @Test
  void failsFastWhenMoreThanOneAuthoritativeStoreIsSupplied() {
    contextRunner
        .withBean("firstStore", VersionGateStore.class, () -> mock(VersionGateStore.class))
        .withBean("secondStore", VersionGateStore.class, () -> mock(VersionGateStore.class))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("VersionGateStore");
            });
  }
}
