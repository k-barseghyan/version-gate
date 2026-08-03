package io.github.kbarseghyan.versiongate.testkit;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.port.SnapshotStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/** Thread-safe, deterministic snapshot-store contract implementation for tests. */
public final class InMemorySnapshotStore implements SnapshotStore {

  private static final int COPY_BUFFER_SIZE = 8192;

  private final Map<String, StoredPayload> payloads = new HashMap<>();

  @Override
  public synchronized StoredObject uploadImmutable(Upload upload) {
    StoredPayload candidate = readUpload(upload);
    StoredPayload existing = payloads.get(upload.objectKey());
    if (existing != null) {
      if (!sameRepresentation(existing, candidate)) {
        throw error(
            ErrorCode.COMPONENT_CONFLICT,
            "Snapshot object " + upload.objectKey() + " already has a different representation");
      }
      return new StoredObject(existing.reference(), true);
    }
    payloads.put(upload.objectKey(), candidate);
    return new StoredObject(candidate.reference(), false);
  }

  @Override
  public synchronized void verify(ObjectReference objectReference) {
    requireMatchingPayload(objectReference);
  }

  @Override
  public synchronized ObjectContent open(ObjectReference objectReference) {
    StoredPayload payload = requireMatchingPayload(objectReference);
    return new ObjectContent(
        new ByteArrayInputStream(payload.bytes().clone()),
        payload.bytes().length,
        payload.contentType(),
        payload.contentEncoding(),
        payload.reference().sha256());
  }

  @Override
  public synchronized void delete(ObjectReference objectReference) {
    StoredPayload payload = payloads.get(objectReference.objectKey());
    if (payload == null) {
      return;
    }
    requireMatchingPayload(objectReference);
    payloads.remove(objectReference.objectKey());
  }

  /** Returns the number of immutable objects currently retained by this test store. */
  public synchronized int size() {
    return payloads.size();
  }

  private StoredPayload readUpload(Upload upload) {
    if (upload.contentLength() > Integer.MAX_VALUE) {
      throw error(
          ErrorCode.STORAGE_FAILURE, "In-memory snapshot payload exceeds the test store capacity");
    }
    MessageDigest digest = sha256();
    ByteArrayOutputStream bytes =
        new ByteArrayOutputStream((int) Math.min(upload.contentLength(), COPY_BUFFER_SIZE));
    byte[] buffer = new byte[COPY_BUFFER_SIZE];
    long remaining = upload.contentLength();
    try {
      while (remaining > 0) {
        int read =
            upload.inputStream().read(buffer, 0, (int) Math.min((long) buffer.length, remaining));
        if (read < 0) {
          throw error(
              ErrorCode.VALIDATION_FAILED,
              "Snapshot body ended before its declared Content-Length");
        }
        if (read == 0) {
          int single = upload.inputStream().read();
          if (single < 0) {
            throw error(
                ErrorCode.VALIDATION_FAILED,
                "Snapshot body ended before its declared Content-Length");
          }
          bytes.write(single);
          digest.update((byte) single);
          remaining--;
        } else {
          bytes.write(buffer, 0, read);
          digest.update(buffer, 0, read);
          remaining -= read;
        }
      }
      if (upload.inputStream().read() >= 0) {
        throw error(
            ErrorCode.VALIDATION_FAILED, "Snapshot body exceeds its declared Content-Length");
      }
    } catch (IOException exception) {
      throw new VersionGateException(
          ErrorCode.STORAGE_FAILURE, "Could not read snapshot upload", exception);
    }

    String checksum = HexFormat.of().formatHex(digest.digest());
    upload
        .expectedSha256()
        .ifPresent(
            expected -> {
              if (!expected.matches("[a-fA-F0-9]{64}")) {
                throw error(
                    ErrorCode.VALIDATION_FAILED,
                    "Expected SHA-256 must contain 64 hexadecimal characters");
              }
              if (!checksum.equalsIgnoreCase(expected)) {
                throw error(
                    ErrorCode.CHECKSUM_MISMATCH,
                    "Snapshot body does not match the expected SHA-256");
              }
            });
    byte[] storedBytes = bytes.toByteArray();
    ObjectReference reference =
        new ObjectReference(upload.objectKey(), checksum, storedBytes.length);
    return new StoredPayload(
        reference, storedBytes, upload.contentType(), upload.contentEncoding());
  }

  private StoredPayload requireMatchingPayload(ObjectReference objectReference) {
    StoredPayload payload = payloads.get(objectReference.objectKey());
    if (payload == null) {
      throw error(
          ErrorCode.SNAPSHOT_OBJECT_MISSING,
          "Snapshot object " + objectReference.objectKey() + " is missing");
    }
    ObjectReference stored = payload.reference();
    String actualChecksum = HexFormat.of().formatHex(sha256().digest(payload.bytes()));
    if (stored.size() != payload.bytes().length
        || !stored.sha256().equals(actualChecksum)
        || stored.size() != objectReference.size()
        || !stored.sha256().equals(objectReference.sha256())) {
      throw error(
          ErrorCode.STORAGE_FAILURE,
          "Snapshot object " + objectReference.objectKey() + " failed integrity verification");
    }
    return payload;
  }

  private static boolean sameRepresentation(StoredPayload first, StoredPayload second) {
    return first.reference().size() == second.reference().size()
        && first.reference().sha256().equals(second.reference().sha256())
        && first.contentType().equals(second.contentType())
        && first.contentEncoding().equals(second.contentEncoding())
        && Arrays.equals(first.bytes(), second.bytes());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is not available", impossible);
    }
  }

  private static VersionGateException error(ErrorCode code, String message) {
    return new VersionGateException(code, message);
  }

  private record StoredPayload(
      ObjectReference reference,
      byte[] bytes,
      String contentType,
      Optional<String> contentEncoding) {}
}
