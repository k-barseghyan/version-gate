package io.github.kbarseghyan.versiongate.configuration;

import io.github.kbarseghyan.versiongate.domain.DomainValidation;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Server configuration contract; storage adapters define their own independent namespaces.
 *
 * @param maximumLease largest lease duration accepted by application use cases
 * @param maximumComponentSize largest accepted component representation
 * @param maximumParticipantsPerResource configured coordinated-callback fan-out limit
 * @param expiredBuildSweepInterval delay between background expiry scans
 * @param participant outbound coordinated-callback settings
 */
@ConfigurationProperties("version-gate")
public record VersionGateProperties(
    @DefaultValue("PT1H") Duration maximumLease,
    @DefaultValue("1GB") DataSize maximumComponentSize,
    @DefaultValue("8") int maximumParticipantsPerResource,
    @DefaultValue("PT30S") Duration expiredBuildSweepInterval,
    @DefaultValue ParticipantProperties participant) {

  /**
   * Creates validated server configuration.
   *
   * @param maximumLease largest lease duration accepted by application use cases
   * @param maximumComponentSize largest accepted component representation
   * @param maximumParticipantsPerResource configured coordinated-callback fan-out limit
   * @param expiredBuildSweepInterval delay between background expiry scans
   * @param participant outbound coordinated-callback settings
   */
  public VersionGateProperties {
    requirePositive(maximumLease, "maximumLease");
    Objects.requireNonNull(maximumComponentSize, "maximumComponentSize is required");
    if (maximumComponentSize.toBytes() <= 0) {
      throw new IllegalArgumentException("maximumComponentSize must be positive");
    }
    if (maximumParticipantsPerResource <= 0
        || maximumParticipantsPerResource > DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE) {
      throw new IllegalArgumentException(
          "maximumParticipantsPerResource must be between 1 and "
              + DomainValidation.MAX_PARTICIPANTS_PER_RESOURCE);
    }
    requirePositive(expiredBuildSweepInterval, "expiredBuildSweepInterval");
    Objects.requireNonNull(participant, "participant is required");
  }

  /**
   * Outbound HTTP settings for coordinated participant callbacks.
   *
   * @param connectTimeout maximum time allowed to establish a callback connection
   * @param requestTimeout maximum time allowed for one callback request
   * @param allowedBaseUris exact callback base-URI allowlist; empty denies every destination
   * @param allowHttp whether explicitly allowlisted plain-HTTP destinations are permitted
   */
  public record ParticipantProperties(
      @DefaultValue("PT5S") Duration connectTimeout,
      @DefaultValue("PT30S") Duration requestTimeout,
      @DefaultValue Set<String> allowedBaseUris,
      @DefaultValue("false") boolean allowHttp) {

    /**
     * Creates validated participant-callback configuration.
     *
     * @param connectTimeout maximum time allowed to establish a callback connection
     * @param requestTimeout maximum time allowed for one callback request
     * @param allowedBaseUris exact callback base-URI allowlist
     * @param allowHttp whether explicitly allowlisted plain-HTTP destinations are permitted
     */
    public ParticipantProperties {
      requirePositive(connectTimeout, "participant.connectTimeout");
      requirePositive(requestTimeout, "participant.requestTimeout");
      allowedBaseUris =
          allowedBaseUris == null
              ? Set.of()
              : allowedBaseUris.stream()
                  .map(String::trim)
                  .filter(value -> !value.isEmpty())
                  .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
  }

  private static void requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name + " is required");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
