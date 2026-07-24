package io.github.kbarseghyan.versiongate.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Contract exception used by the core and external adapters for expected failures.
 *
 * <p>Adapters should translate vendor exceptions at their boundary and preserve one of the stable
 * {@link ErrorCode} values. Messages and details must not contain credentials or other secrets.
 * Detail keys use lower camel case, may not collide with standard Problem Detail fields, and values
 * are restricted to recursively JSON-safe immutable scalars, collections, and maps.
 */
public class VersionGateException extends RuntimeException {

  private static final Pattern DETAIL_KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
  private static final Set<String> RESERVED_PROBLEM_FIELDS =
      Set.of("type", "title", "status", "detail", "instance", "code", "correlationId");

  /** Stable machine-readable category. */
  private final ErrorCode code;

  /** Immutable structured context safe to expose to contract consumers. */
  private final Map<String, Object> details;

  /**
   * Creates an expected contract failure without structured details or a cause.
   *
   * @param code stable machine-readable error code
   * @param message human-readable failure detail
   */
  public VersionGateException(ErrorCode code, String message) {
    this(code, message, Map.of(), null);
  }

  /**
   * Creates an expected contract failure with structured details.
   *
   * @param code stable machine-readable error code
   * @param message human-readable failure detail
   * @param details client-safe lower-camel-case keys and recursively JSON-safe immutable values
   */
  public VersionGateException(ErrorCode code, String message, Map<String, Object> details) {
    this(code, message, details, null);
  }

  /**
   * Creates an expected contract failure caused by a lower-level failure.
   *
   * @param code stable machine-readable error code
   * @param message human-readable failure detail
   * @param cause originating failure
   */
  public VersionGateException(ErrorCode code, String message, Throwable cause) {
    this(code, message, Map.of(), cause);
  }

  /**
   * Creates an expected contract failure with structured details and a cause.
   *
   * @param code stable machine-readable error code
   * @param message human-readable failure detail
   * @param details client-safe lower-camel-case keys and recursively JSON-safe immutable values
   * @param cause originating failure, or {@code null} when none exists
   */
  public VersionGateException(
      ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code is required");
    this.details = copyDetails(details);
  }

  /**
   * Returns the stable machine-readable error code.
   *
   * @return error code
   */
  public ErrorCode code() {
    return code;
  }

  /**
   * Returns immutable structured context for the failure.
   *
   * @return unmodifiable detail map, possibly empty
   */
  public Map<String, Object> details() {
    return details;
  }

  private static Map<String, Object> copyDetails(Map<String, Object> source) {
    Objects.requireNonNull(source, "details are required");
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "detail key is required");
          if (!DETAIL_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                "detail key must be lower camel case and contain at most 64 characters");
          }
          if (RESERVED_PROBLEM_FIELDS.contains(key)) {
            throw new IllegalArgumentException(
                "detail key " + key + " is reserved by the Problem Detail contract");
          }
          copy.put(key, copyDetailValue(value));
        });
    return Map.copyOf(copy);
  }

  private static Object copyDetailValue(Object value) {
    Objects.requireNonNull(value, "detail value is required");
    if (value instanceof String
        || value instanceof Boolean
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Float
        || value instanceof Double
        || value instanceof BigInteger
        || value instanceof BigDecimal) {
      return value;
    }
    if (value instanceof Set<?> set) {
      return set.stream()
          .map(VersionGateException::copyDetailValue)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    if (value instanceof List<?> list) {
      return list.stream().map(VersionGateException::copyDetailValue).toList();
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(VersionGateException::copyDetailValue).toList();
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach(
          (key, nestedValue) -> {
            if (!(key instanceof String stringKey)) {
              throw new IllegalArgumentException("nested detail map keys must be strings");
            }
            copy.put(stringKey, copyDetailValue(nestedValue));
          });
      return Map.copyOf(copy);
    }
    throw new IllegalArgumentException(
        "detail values must contain only JSON-safe immutable scalars, collections, or maps");
  }
}
