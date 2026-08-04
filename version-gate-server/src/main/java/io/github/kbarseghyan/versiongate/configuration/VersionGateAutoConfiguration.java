package io.github.kbarseghyan.versiongate.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Spring Boot entry point that composes the core and selected adapter artifacts.
 *
 * <p>The auto-configuration intentionally fails fast when the application supplies no compatible
 * {@code VersionGateStore} adapter.
 */
@AutoConfiguration
@Import(VersionGateConfiguration.class)
public class VersionGateAutoConfiguration {

  /** Creates the Spring Boot auto-configuration entry point. */
  public VersionGateAutoConfiguration() {}
}
