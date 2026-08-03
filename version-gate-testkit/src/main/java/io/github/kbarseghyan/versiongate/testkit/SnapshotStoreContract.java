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
import java.io.InputStream;
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
 * adapters should add integration coverage for conditional-write concurrency, crash recovery,
 * exact-version deletion, and provider integrity guarantees.
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
  final void exactRepresentationReplayReturnsTheOriginalObject() {
    byte[] bytes = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String key = "snapshots/catalog/1/products";
    SnapshotStore.StoredObject first =
        store.uploadImmutable(upload(key, bytes, "application/json", Optional.of("gzip")));
    SnapshotStore.StoredObject replay =
        store.uploadImmutable(upload(key, bytes, "application/json", Optional.of("gzip")));

    assertFalse(first.alreadyExisted());
    assertTrue(replay.alreadyExisted());
    assertEquals(first.reference(), replay.reference());
  }

  @Test
  final void differentBytesAtAnExistingKeyConflict() {
    byte[] bytes = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String key = "snapshots/catalog/1/products";
    store.uploadImmutable(upload(key, bytes));

    byte[] changed = bytes.clone();
    changed[0]++;
    VersionGateException failure =
        assertThrows(VersionGateException.class, () -> store.uploadImmutable(upload(key, changed)));
    assertEquals(ErrorCode.COMPONENT_CONFLICT, failure.code());
  }

  @Test
  final void differentContentTypeAtAnExistingKeyConflictsEvenWhenBytesMatch() {
    byte[] bytes = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String key = "snapshots/catalog/1/products";
    store.uploadImmutable(upload(key, bytes, "application/json", Optional.empty()));

    VersionGateException failure =
        assertThrows(
            VersionGateException.class,
            () ->
                store.uploadImmutable(
                    upload(key, bytes, "application/octet-stream", Optional.empty())));

    assertEquals(ErrorCode.COMPONENT_CONFLICT, failure.code());
  }

  @Test
  final void differentContentEncodingAtAnExistingKeyConflictsEvenWhenBytesMatch() {
    byte[] bytes = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String key = "snapshots/catalog/1/products";
    store.uploadImmutable(upload(key, bytes, "application/json", Optional.empty()));

    VersionGateException failure =
        assertThrows(
            VersionGateException.class,
            () ->
                store.uploadImmutable(upload(key, bytes, "application/json", Optional.of("gzip"))));

    assertEquals(ErrorCode.COMPONENT_CONFLICT, failure.code());
  }

  @Test
  final void bodyValidationAndChecksumPrecedeExistingRepresentationConflict() {
    byte[] bytes = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String key = "snapshots/catalog/1/products";
    store.uploadImmutable(upload(key, bytes));

    assertCode(
        ErrorCode.VALIDATION_FAILED,
        () ->
            store.uploadImmutable(
                upload(
                    key,
                    bytes,
                    bytes.length + 1L,
                    "application/json",
                    Optional.of("gzip"),
                    Optional.of(sha256(bytes)))));
    assertCode(
        ErrorCode.VALIDATION_FAILED,
        () ->
            store.uploadImmutable(
                upload(
                    key,
                    bytes,
                    bytes.length - 1L,
                    "application/json",
                    Optional.of("gzip"),
                    Optional.of(sha256(bytes)))));
    assertCode(
        ErrorCode.CHECKSUM_MISMATCH,
        () ->
            store.uploadImmutable(
                upload(
                    key,
                    bytes,
                    bytes.length,
                    "application/json",
                    Optional.of("gzip"),
                    Optional.of("0".repeat(64)))));

    SnapshotStore.StoredObject replay = store.uploadImmutable(upload(key, bytes));
    assertTrue(replay.alreadyExisted());
  }

  @Test
  final void storedContentCanBeVerifiedAndStreamedWithoutChangingItsIdentity() throws IOException {
    byte[] bytes = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    TrackingInputStream input = new TrackingInputStream(bytes);
    SnapshotStore.StoredObject stored =
        store.uploadImmutable(
            upload(
                "snapshots/catalog/1/products",
                input,
                bytes.length,
                "application/json",
                Optional.of("gzip"),
                Optional.of(sha256(bytes))));

    assertFalse(input.closed);

    store.verify(stored.reference());
    try (SnapshotStore.ObjectContent content = store.open(stored.reference())) {
      assertArrayEquals(bytes, content.inputStream().readAllBytes());
      assertEquals(bytes.length, content.contentLength());
      assertEquals(sha256(bytes), content.sha256());
      assertEquals("application/json", content.contentType());
      assertEquals(Optional.of("gzip"), content.contentEncoding());
    }
  }

  @Test
  final void deleteIsIdempotentButNeverDeletesAReferenceThatFailsVerification() {
    byte[] bytes = "snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    SnapshotStore.StoredObject stored =
        store.uploadImmutable(upload("snapshots/catalog/1/products", bytes));
    SnapshotStore.ObjectReference wrongReference =
        new SnapshotStore.ObjectReference(
            stored.reference().objectKey(), "0".repeat(64), stored.reference().size());

    assertCode(ErrorCode.STORAGE_FAILURE, () -> store.delete(wrongReference));
    store.verify(stored.reference());
    store.delete(stored.reference());
    store.delete(stored.reference());
    assertCode(ErrorCode.SNAPSHOT_OBJECT_MISSING, () -> store.verify(stored.reference()));
  }

  private static SnapshotStore.Upload upload(String key, byte[] bytes) {
    return upload(key, bytes, "application/octet-stream", Optional.empty());
  }

  private static SnapshotStore.Upload upload(
      String key, byte[] bytes, String contentType, Optional<String> contentEncoding) {
    return upload(
        key,
        new ByteArrayInputStream(bytes),
        bytes.length,
        contentType,
        contentEncoding,
        Optional.of(sha256(bytes)));
  }

  private static SnapshotStore.Upload upload(
      String key,
      byte[] bytes,
      long contentLength,
      String contentType,
      Optional<String> contentEncoding,
      Optional<String> expectedSha256) {
    return upload(
        key,
        new ByteArrayInputStream(bytes),
        contentLength,
        contentType,
        contentEncoding,
        expectedSha256);
  }

  private static SnapshotStore.Upload upload(
      String key,
      InputStream inputStream,
      long contentLength,
      String contentType,
      Optional<String> contentEncoding,
      Optional<String> expectedSha256) {
    return new SnapshotStore.Upload(
        key, inputStream, contentLength, contentType, contentEncoding, expectedSha256);
  }

  private static void assertCode(ErrorCode expected, Runnable invocation) {
    VersionGateException failure = assertThrows(VersionGateException.class, invocation::run);
    assertEquals(expected, failure.code());
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {

    private boolean closed;

    private TrackingInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
