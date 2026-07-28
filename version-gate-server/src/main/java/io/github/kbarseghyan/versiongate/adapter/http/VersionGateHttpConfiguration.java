package io.github.kbarseghyan.versiongate.adapter.http;

import io.github.kbarseghyan.versiongate.application.VersionGateService;
import io.github.kbarseghyan.versiongate.configuration.VersionGateProperties;
import io.github.kbarseghyan.versiongate.port.ParticipantGateway;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the core HTTP API and the default participant callback implementation.
 *
 * <p>An assembled distribution may replace the callback behavior with its own {@code
 * ParticipantGateway} bean.
 */
@Configuration(proxyBeanMethods = false)
public class VersionGateHttpConfiguration {

  /** Creates the HTTP bean configuration. */
  public VersionGateHttpConfiguration() {}

  @Bean
  @ConditionalOnMissingBean(ParticipantGateway.class)
  ParticipantGateway participantGateway(
      @Qualifier("participantHttpClient") HttpClient participantHttpClient,
      JsonMapper jsonMapper,
      VersionGateProperties properties) {
    return new HttpParticipantGateway(participantHttpClient, jsonMapper, properties);
  }

  @Bean
  VersionGateController versionGateController(VersionGateService service) {
    return new VersionGateController(service);
  }

  @Bean
  VersionGateExceptionHandler versionGateExceptionHandler() {
    return new VersionGateExceptionHandler();
  }
}
