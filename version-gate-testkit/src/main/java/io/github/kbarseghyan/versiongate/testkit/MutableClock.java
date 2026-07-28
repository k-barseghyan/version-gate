package io.github.kbarseghyan.versiongate.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** A deterministic, thread-safe clock for semantic adapter and application tests. */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> instant;
  private final ZoneId zone;

  /**
   * Creates a UTC clock at the supplied instant.
   *
   * @param initialInstant initial clock value
   */
  public MutableClock(Instant initialInstant) {
    this(new AtomicReference<>(Objects.requireNonNull(initialInstant)), ZoneOffset.UTC);
  }

  private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
    this.instant = instant;
    this.zone = Objects.requireNonNull(zone);
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId requestedZone) {
    return new MutableClock(instant, requestedZone);
  }

  @Override
  public Instant instant() {
    return instant.get();
  }

  /**
   * Sets the current instant.
   *
   * @param newInstant replacement clock value
   */
  public void set(Instant newInstant) {
    instant.set(Objects.requireNonNull(newInstant));
  }

  /**
   * Advances the clock atomically.
   *
   * @param duration amount to add
   * @return resulting instant
   */
  public Instant advance(Duration duration) {
    Objects.requireNonNull(duration);
    return instant.updateAndGet(current -> current.plus(duration));
  }
}
