package io.github.kbarseghyan.versiongate.adapter.http;

import io.github.kbarseghyan.versiongate.application.VersionGateService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the four-flow Version Gate HTTP API. */
@Configuration(proxyBeanMethods = false)
public class VersionGateHttpConfiguration {

  /** Creates the HTTP bean configuration. */
  public VersionGateHttpConfiguration() {}

  @Bean
  VersionGateController versionGateController(VersionGateService service) {
    return new VersionGateController(service);
  }

  @Bean
  VersionGateExceptionHandler versionGateExceptionHandler() {
    return new VersionGateExceptionHandler();
  }
}
