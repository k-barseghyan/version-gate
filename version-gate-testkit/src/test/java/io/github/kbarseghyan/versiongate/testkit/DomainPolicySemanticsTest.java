package io.github.kbarseghyan.versiongate.testkit;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.kbarseghyan.versiongate.domain.MissingCurrentSnapshotPolicy;
import io.github.kbarseghyan.versiongate.domain.ResourcePolicies;
import io.github.kbarseghyan.versiongate.domain.RetrievalDuringWritePolicy;
import io.github.kbarseghyan.versiongate.domain.SnapshotSelector;
import io.github.kbarseghyan.versiongate.domain.SnapshotSupport;
import io.github.kbarseghyan.versiongate.domain.WriterDuringSnapshotPolicy;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DomainPolicySemanticsTest {

  @Test
  void disabledSnapshotPolicyRejectsEnabledOnlyDimensions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourcePolicies(
                SnapshotSupport.DISABLED,
                MissingCurrentSnapshotPolicy.ALLOW_GAP,
                Optional.of(WriterDuringSnapshotPolicy.BLOCK_WRITER),
                Optional.empty(),
                Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourcePolicies(
                SnapshotSupport.DISABLED,
                MissingCurrentSnapshotPolicy.REQUIRE_CURRENT_SNAPSHOT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void enabledSnapshotPolicyRequiresEveryIndependentDimension() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourcePolicies(
                SnapshotSupport.ENABLED,
                MissingCurrentSnapshotPolicy.ALLOW_GAP,
                Optional.empty(),
                Optional.of(SnapshotSelector.CURRENT),
                Optional.of(RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING)));
  }

  @Test
  void invalidationIsIncompatibleWithRequiredCurrentSnapshot() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourcePolicies(
                SnapshotSupport.ENABLED,
                MissingCurrentSnapshotPolicy.REQUIRE_CURRENT_SNAPSHOT,
                Optional.of(WriterDuringSnapshotPolicy.INVALIDATE_SNAPSHOT),
                Optional.of(SnapshotSelector.CURRENT),
                Optional.of(RetrievalDuringWritePolicy.ALLOW_WHILE_WRITING)));
  }
}
