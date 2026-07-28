package io.github.kbarseghyan.versiongate.port;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Storage-neutral contract for immutable snapshot payloads.
 *
 * <p>Implementations live in official or third-party adapter modules. They must stream payloads
 * without materializing the entire content in memory, enforce immutable logical keys, and validate
 * SHA-256 over the exact stored representation (after any content encoding). Backend SDK types,
 * checksums, and exceptions must not cross this boundary.
 *
 * <p>Expected conflicts are reported through {@code VersionGateException} and its stable error
 * codes. Backend availability or integrity failures use {@code STORAGE_FAILURE}; a missing
 * authoritative payload uses {@code SNAPSHOT_OBJECT_MISSING}.
 */
public interface SnapshotStore {

  /**
   * Streams and immutably stores an upload.
   *
   * <p>The implementation calculates SHA-256 and checks any expected checksum. A first successful
   * write returns {@code alreadyExisted=false}. Replaying the same logical key and bytes returns
   * the original reference with {@code alreadyExisted=true}; different bytes for the key report
   * {@code COMPONENT_CONFLICT}. A success means the complete payload is durable and its bytes,
   * length, and checksum have been verified. The adapter must consume exactly {@code
   * contentLength}; a short or longer body is {@code VALIDATION_FAILED}. The caller retains
   * ownership of the input stream.
   *
   * @param upload immutable upload request and stream
   * @return authoritative reference and replay indicator
   */
  StoredObject uploadImmutable(Upload upload);

  /**
   * Verifies that the referenced immutable payload exists and matches its exact length and SHA-256.
   *
   * <p>This is an integrity check used before completion and activation, not a metadata-only
   * existence probe. It returns normally only for a fully verified object.
   *
   * @param objectReference exact logical key, digest, and length to verify
   */
  void verify(ObjectReference objectReference);

  /**
   * Opens an integrity-protected streaming read for a previously verified reference.
   *
   * <p>The returned content must describe the exact stored representation. The caller closes it.
   * Before exposing bytes, implementations must use a trustworthy authenticated storage guarantee
   * or complete verification against the reference; an on-the-fly digest that can expose a corrupt
   * prefix is insufficient. Disk staging is permitted, but implementations must still stream with
   * bounded heap and document latency, capacity, and cleanup requirements.
   *
   * @param objectReference exact logical key, digest, and length to open
   * @return integrity-protected content stream and its authoritative representation metadata
   */
  ObjectContent open(ObjectReference objectReference);

  /**
   * Deletes an unreferenced failed-build payload.
   *
   * <p>Deletion is idempotent when the payload is already absent. Adapters must never infer
   * reachability themselves; the application invokes this only after durable terminalization. An
   * adapter must not delete a present payload whose length or SHA-256 differs from the supplied
   * reference.
   *
   * @param objectReference exact logical key, digest, and length eligible for deletion
   */
  void delete(ObjectReference objectReference);

  /**
   * Streaming request to store one immutable logical object.
   *
   * @param objectKey storage-neutral logical key
   * @param inputStream stream of the exact representation; ownership remains with the caller
   * @param contentLength exact number of bytes the adapter must consume
   * @param contentType media type of the exact stored representation
   * @param contentEncoding optional content encoding of the stored representation
   * @param expectedSha256 optional expected digest of the exact stored bytes
   */
  record Upload(
      String objectKey,
      InputStream inputStream,
      long contentLength,
      String contentType,
      Optional<String> contentEncoding,
      Optional<String> expectedSha256) {

    /**
     * Creates a validated immutable-upload request.
     *
     * @param objectKey storage-neutral logical key
     * @param inputStream stream of the exact representation
     * @param contentLength exact number of bytes the adapter must consume
     * @param contentType media type of the exact stored representation
     * @param contentEncoding optional content encoding of the stored representation
     * @param expectedSha256 optional expected digest of the exact stored bytes
     */
    public Upload {
      Objects.requireNonNull(objectKey, "objectKey is required");
      if (objectKey.isBlank()) {
        throw new IllegalArgumentException("objectKey must not be blank");
      }
      Objects.requireNonNull(inputStream, "inputStream is required");
      if (contentLength < 0) {
        throw new IllegalArgumentException("contentLength must not be negative");
      }
      Objects.requireNonNull(contentType, "contentType is required");
      if (contentType.isBlank()) {
        throw new IllegalArgumentException("contentType must not be blank");
      }
      contentEncoding = Objects.requireNonNull(contentEncoding, "contentEncoding is required");
      expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256 is required");
      expectedSha256 =
          expectedSha256.map(
              value -> {
                if (!value.matches("[a-fA-F0-9]{64}")) {
                  throw new IllegalArgumentException(
                      "expectedSha256 must contain 64 hexadecimal" + " characters");
                }
                return value.toLowerCase(java.util.Locale.ROOT);
              });
    }
  }

  /**
   * Exact identity and integrity metadata for an immutable stored payload.
   *
   * @param objectKey storage-neutral logical key
   * @param sha256 64-character lowercase digest of the stored bytes
   * @param size exact stored length in bytes
   */
  record ObjectReference(String objectKey, String sha256, long size) {

    /**
     * Creates a validated exact object reference.
     *
     * @param objectKey storage-neutral logical key
     * @param sha256 64-character lowercase digest of the stored bytes
     * @param size exact stored length in bytes
     */
    public ObjectReference {
      Objects.requireNonNull(objectKey, "objectKey is required");
      if (objectKey.isBlank()) {
        throw new IllegalArgumentException("objectKey must not be blank");
      }
      Objects.requireNonNull(sha256, "sha256 is required");
      if (!sha256.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException(
            "sha256 must contain 64 lowercase hexadecimal characters");
      }
      if (size < 0) {
        throw new IllegalArgumentException("size must not be negative");
      }
    }
  }

  /**
   * Result of an immutable upload.
   *
   * @param reference authoritative reference for the stored payload
   * @param alreadyExisted whether an identical immutable object existed before this call
   */
  record StoredObject(ObjectReference reference, boolean alreadyExisted) {

    /**
     * Creates an immutable-upload result.
     *
     * @param reference authoritative reference for the stored payload
     * @param alreadyExisted whether an identical object existed before the call
     */
    public StoredObject {
      Objects.requireNonNull(reference, "reference is required");
    }
  }

  /**
   * Closeable streaming representation of an immutable stored payload.
   *
   * @param inputStream integrity-protected stream; closed by {@link #close()}
   * @param contentLength exact number of readable bytes
   * @param contentType media type of the exact stored representation
   * @param contentEncoding optional content encoding of the stored representation
   * @param sha256 64-character lowercase digest of the readable bytes
   */
  record ObjectContent(
      InputStream inputStream,
      long contentLength,
      String contentType,
      Optional<String> contentEncoding,
      String sha256)
      implements AutoCloseable {

    /**
     * Creates validated streaming content.
     *
     * @param inputStream integrity-protected representation stream
     * @param contentLength exact number of readable bytes
     * @param contentType media type of the exact stored representation
     * @param contentEncoding optional content encoding of the stored representation
     * @param sha256 64-character lowercase digest of the readable bytes
     */
    public ObjectContent {
      Objects.requireNonNull(inputStream, "inputStream is required");
      if (contentLength < 0) {
        throw new IllegalArgumentException("contentLength must not be negative");
      }
      Objects.requireNonNull(contentType, "contentType is required");
      if (contentType.isBlank()) {
        throw new IllegalArgumentException("contentType must not be blank");
      }
      contentEncoding = Objects.requireNonNull(contentEncoding, "contentEncoding is required");
      Objects.requireNonNull(sha256, "sha256 is required");
      if (!sha256.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException(
            "sha256 must contain 64 lowercase hexadecimal characters");
      }
    }

    /**
     * Closes the underlying content stream.
     *
     * @throws IOException when the underlying stream cannot be closed
     */
    @Override
    public void close() throws IOException {
      inputStream.close();
    }
  }
}
