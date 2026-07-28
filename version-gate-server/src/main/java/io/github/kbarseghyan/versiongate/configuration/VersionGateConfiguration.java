package io.github.kbarseghyan.versiongate.configuration;

import io.github.kbarseghyan.versiongate.adapter.http.VersionGateHttpConfiguration;
import io.github.kbarseghyan.versiongate.application.VersionGateService;
import io.github.kbarseghyan.versiongate.port.ControlStore;
import io.github.kbarseghyan.versiongate.port.ParticipantGateway;
import io.github.kbarseghyan.versiongate.port.SnapshotStore;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composes the server around storage-port beans supplied by selected adapters.
 *
 * <p>The assembled server must provide exactly one compatible {@code ControlStore} and {@code
 * SnapshotStore}. Placeholder adapter modules intentionally provide neither.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VersionGateProperties.class)
@EnableScheduling
@Import(VersionGateHttpConfiguration.class)
public class VersionGateConfiguration {

  /** Creates the server bean configuration. */
  public VersionGateConfiguration() {}

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(name = "participantHttpClient")
  HttpClient participantHttpClient(VersionGateProperties properties) {
    return HttpClient.newBuilder()
        .connectTimeout(properties.participant().connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(VersionGateService.class)
  VersionGateService versionGateService(
      ControlStore controlStore,
      SnapshotStore snapshotStore,
      ParticipantGateway participantGateway,
      Clock clock,
      VersionGateProperties properties) {
    return new VersionGateService(
        controlStore,
        snapshotStore,
        participantGateway,
        clock,
        properties.maximumLease(),
        properties.maximumComponentSize().toBytes(),
        properties.maximumParticipantsPerResource());
  }

  @Bean
  ExpiredBuildSweeper expiredBuildSweeper(VersionGateService service) {
    return new ExpiredBuildSweeper(service);
  }
}
