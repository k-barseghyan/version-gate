package io.github.kbarseghyan.versiongate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Bootstrap for the assembled Version Gate server.
 *
 * <p>Startup intentionally fails when the selected adapter modules provide no compatible storage
 * beans.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class VersionGateApplication {

  /** Creates the Spring Boot bootstrap configuration. */
  public VersionGateApplication() {}

  /**
   * Starts the assembled distribution.
   *
   * @param args Spring Boot command-line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(VersionGateApplication.class, args);
  }
}
