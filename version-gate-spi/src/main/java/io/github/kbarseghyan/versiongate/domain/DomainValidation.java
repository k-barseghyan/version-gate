package io.github.kbarseghyan.versiongate.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Shared validation rules for values that cross the public SPI. */
public final class DomainValidation {

  /** Maximum number of characters accepted by a public identifier. */
  public static final int IDENTIFIER_MAX_LENGTH = 128;

  /** Maximum number of characters accepted by an idempotency key. */
  public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

  /** Maximum number of characters accepted by bounded free-text fields. */
  public static final int TEXT_MAX_LENGTH = 255;

  /**
   * Regular expression for path-safe public identifiers.
   *
   * <p>An identifier starts with an ASCII letter or digit and then contains only ASCII letters,
   * digits, dots, underscores, or hyphens.
   */
  public static final String IDENTIFIER_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

  /** Case-sensitive grammar for client-supplied idempotency keys. */
  public static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

  private static final Pattern IDENTIFIER = Pattern.compile(IDENTIFIER_PATTERN);
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(IDEMPOTENCY_KEY_PATTERN);
  private static final Pattern SHA_256 = Pattern.compile("[a-fA-F0-9]{64}");

  private DomainValidation() {}

  /**
   * Requires a non-blank, path-safe identifier.
   *
   * @param value identifier to validate
   * @param name field name used in failure messages
   * @return the original valid value
   * @throws NullPointerException when {@code value} is {@code null}
   * @throws IllegalArgumentException when the value is blank or has an invalid shape
   */
  public static String requireIdentifier(String value, String name) {
    requireNonBlank(value, name);
    if (!IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
    }
    return value;
  }

  /**
   * Requires a bounded, case-sensitive idempotency key without normalizing it.
   *
   * @param value idempotency key to validate
   * @return the original valid key
   */
  public static String requireIdempotencyKey(String value) {
    requireNonBlank(value, "idempotencyKey");
    if (!IDEMPOTENCY_KEY.matcher(value).matches()) {
      throw new IllegalArgumentException("idempotencyKey must match " + IDEMPOTENCY_KEY.pattern());
    }
    return value;
  }

  /**
   * Requires text containing at least one non-whitespace character.
   *
   * @param value text to validate
   * @param name field name used in failure messages
   * @return the original valid value
   * @throws NullPointerException when {@code value} is {@code null}
   * @throws IllegalArgumentException when the value is blank
   */
  public static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name + " is required");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  /**
   * Requires bounded text containing at least one non-whitespace character.
   *
   * @param value text to validate
   * @param name field name used in failure messages
   * @param maximumLength maximum accepted number of Java characters
   * @return the original valid value
   * @throws NullPointerException when {@code value} is {@code null}
   * @throws IllegalArgumentException when the value is blank or exceeds the maximum length
   */
  public static String requireNonBlank(String value, String name, int maximumLength) {
    requireNonBlank(value, name);
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    return value;
  }

  /**
   * Requires a complete hexadecimal SHA-256 digest and normalizes it to lowercase.
   *
   * @param value digest to validate
   * @return normalized 64-character lowercase hexadecimal digest
   * @throws NullPointerException when the value is {@code null}
   * @throws IllegalArgumentException when the value is not a complete SHA-256 digest
   */
  public static String requireSha256(String value) {
    Objects.requireNonNull(value, "sha256 is required");
    if (!SHA_256.matcher(value).matches()) {
      throw new IllegalArgumentException("sha256 must contain 64 hexadecimal characters");
    }
    return value.toLowerCase();
  }
}
