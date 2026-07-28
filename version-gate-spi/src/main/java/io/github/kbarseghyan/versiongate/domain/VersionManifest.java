package io.github.kbarseghyan.versiongate.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, deterministically ordered description of one complete resource version.
 *
 * @param resourceId owning resource identifier
 * @param version completed candidate version
 * @param buildId build that finalized this manifest
 * @param baseActiveVersion active version observed when the build began, or {@code null}
 * @param completedAt manifest finalization timestamp
 * @param components non-empty components, exposed in component-identifier order
 */
public record VersionManifest(
    String resourceId,
    long version,
    UUID buildId,
    Long baseActiveVersion,
    Instant completedAt,
    List<SnapshotComponent> components) {

  /**
   * Creates a validated, deterministically ordered immutable manifest.
   *
   * @param resourceId owning resource identifier
   * @param version completed candidate version
   * @param buildId build that finalized this manifest
   * @param baseActiveVersion active version observed when the build began, or {@code null}
   * @param completedAt manifest finalization timestamp
   * @param components non-empty components
   */
  public VersionManifest {
    DomainValidation.requireIdentifier(resourceId, "resourceId");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    Objects.requireNonNull(buildId, "buildId is required");
    if (baseActiveVersion != null && baseActiveVersion < 0) {
      throw new IllegalArgumentException("baseActiveVersion must not be negative");
    }
    if (baseActiveVersion != null && version <= baseActiveVersion) {
      throw new IllegalArgumentException("version must be greater than baseActiveVersion");
    }
    Objects.requireNonNull(completedAt, "completedAt is required");
    Objects.requireNonNull(components, "components are required");
    components =
        components.stream().sorted(Comparator.comparing(SnapshotComponent::componentId)).toList();
    if (components.isEmpty()) {
      throw new IllegalArgumentException("a manifest requires at least one component");
    }
    HashSet<String> componentIds = new HashSet<>();
    for (SnapshotComponent component : components) {
      if (!component.buildId().equals(buildId)
          || !component.resourceId().equals(resourceId)
          || component.version() != version) {
        throw new IllegalArgumentException(
            "every manifest component must match its build, resource, and version");
      }
      if (!componentIds.add(component.componentId())) {
        throw new IllegalArgumentException("manifest component IDs must be unique");
      }
    }
  }
}
