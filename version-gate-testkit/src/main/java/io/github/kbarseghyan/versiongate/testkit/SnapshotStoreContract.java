package io.github.kbarseghyan.versiongate.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.port.SnapshotStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reusable minimum semantic contract for {@link SnapshotStore} implementations.
 *
 * <p>Adapter tests extend this class and construct an empty store for each invocation. Provider
 * adapters should add integration coverage for multipart cleanup, crash recovery, and provider
 * integrity guarantees.
 */
public abstract class SnapshotStoreContract {

  private SnapshotStore store;

  /**
   * Creates an empty snapshot store for one contract-test invocation.
   *
   * @return empty store instance
   */
  protected abstract SnapshotStore createSnapshotStore();

  @BeforeEach
  final void initializeContractStore() {
    store = createSnapshotStore();
  }

  @Test
  final void immutableReplayReturnsTheOriginalObjectAndDifferentBytesConflict() {
    byte[] bytes = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String key = "snapshots/catalog/1/products";
    SnapshotStore.StoredObject first = store.uploadImmutable(upload(key, bytes));
    SnapshotStore.StoredObject replay = store.uploadImmutable(upload(key, bytes));

    assertFalse(first.alreadyExisted());
    assertTrue(replay.alreadyExisted());
    assertEquals(first.reference(), replay.reference());

    byte[] changed = bytes.clone();
    changed[0]++;
    VersionGateException failure =
        assertThrows(VersionGateException.class, () -> store.uploadImmutable(upload(key, changed)));
    assertEquals(ErrorCode.COMPONENT_CONFLICT, failure.code());
  }

  @Test
  final void storedContentCanBeVerifiedAndStreamedWithoutChangingItsIdentity() throws IOException {
    byte[] bytes = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    SnapshotStore.StoredObject stored =
        store.uploadImmutable(upload("snapshots/catalog/1/products", bytes));

    store.verify(stored.reference());
    try (SnapshotStore.ObjectContent content = store.open(stored.reference())) {
      assertArrayEquals(bytes, content.inputStream().readAllBytes());
      assertEquals(bytes.length, content.contentLength());
      assertEquals(sha256(bytes), content.sha256());
    }
  }

  private static SnapshotStore.Upload upload(String key, byte[] bytes) {
    return new SnapshotStore.Upload(
        key,
        new ByteArrayInputStream(bytes),
        bytes.length,
        "application/octet-stream",
        Optional.empty(),
        Optional.of(sha256(bytes)));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
