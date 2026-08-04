package io.github.kbarseghyan.versiongate.configuration;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Server limits; resource-scoped business policies are supplied explicitly when a resource is
 * registered.
 *
 * @param maximumLease largest lease duration accepted by application use cases
 * @param maximumSnapshotSize largest accepted immutable snapshot representation
 */
@ConfigurationProperties("version-gate")
public record VersionGateProperties(
    @DefaultValue("PT1H") Duration maximumLease,
    @DefaultValue("1GB") DataSize maximumSnapshotSize) {

  /** Creates validated server limits. */
  public VersionGateProperties {
    Objects.requireNonNull(maximumLease, "maximumLease is required");
    if (maximumLease.isZero() || maximumLease.isNegative()) {
      throw new IllegalArgumentException("maximumLease must be positive");
    }
    Objects.requireNonNull(maximumSnapshotSize, "maximumSnapshotSize is required");
    if (maximumSnapshotSize.toBytes() <= 0) {
      throw new IllegalArgumentException("maximumSnapshotSize must be positive");
    }
  }
}
