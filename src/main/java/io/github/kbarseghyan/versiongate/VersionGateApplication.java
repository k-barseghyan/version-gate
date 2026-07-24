package io.github.kbarseghyan.versiongate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Optional bootstrap for a classpath that also contains contract-compliant storage adapters.
 *
 * <p>The core artifact alone intentionally fails startup because it provides no storage beans.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class VersionGateApplication {

  /** Creates the optional Spring Boot bootstrap configuration. */
  public VersionGateApplication() {}

  /**
   * Starts an optional assembled distribution.
   *
   * @param args Spring Boot command-line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(VersionGateApplication.class, args);
  }
}
