package io.github.kbarseghyan.versiongate.support;

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

  public void set(Instant newInstant) {
    instant.set(Objects.requireNonNull(newInstant));
  }

  public Instant advance(Duration duration) {
    Objects.requireNonNull(duration);
    return instant.updateAndGet(current -> current.plus(duration));
  }
}
