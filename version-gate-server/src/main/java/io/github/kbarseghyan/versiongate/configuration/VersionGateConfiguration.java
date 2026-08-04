package io.github.kbarseghyan.versiongate.configuration;

import io.github.kbarseghyan.versiongate.adapter.http.VersionGateHttpConfiguration;
import io.github.kbarseghyan.versiongate.application.VersionGateService;
import io.github.kbarseghyan.versiongate.port.VersionGateStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Composes the server around the authoritative store supplied by a selected adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VersionGateProperties.class)
@Import(VersionGateHttpConfiguration.class)
public class VersionGateConfiguration {

  /** Creates the server bean configuration. */
  public VersionGateConfiguration() {}

  @Bean
  @ConditionalOnMissingBean(VersionGateService.class)
  VersionGateService versionGateService(VersionGateStore store, VersionGateProperties properties) {
    return new VersionGateService(
        store, properties.maximumLease(), properties.maximumSnapshotSize().toBytes());
  }
}
