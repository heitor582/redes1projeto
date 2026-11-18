package br.edu.netpulse.common;

import java.util.Objects;

/** Null is explicit UNAVAILABLE; NaN, infinity and sentinels are forbidden. */
public record SystemSnapshot(
    long timestampEpochMillis,
    Double cpuPercent,
    Double ramPercent,
    Double temperatureCelsius,
    Mode mode) {
  public enum Availability {
    AVAILABLE,
    UNAVAILABLE
  }

  public SystemSnapshot {
    if (timestampEpochMillis < 0) throw new IllegalArgumentException("INVALID_TIMESTAMP");
    Objects.requireNonNull(mode);
    check(cpuPercent, 0, 100);
    check(ramPercent, 0, 100);
    check(temperatureCelsius, 0.01, 150);
  }

  private static void check(Double n, double min, double max) {
    if (n != null && (!Double.isFinite(n) || n < min || n > max))
      throw new IllegalArgumentException("INVALID_METRIC");
  }

  public Double value(Metric m) {
    return switch (m) {
      case CPU -> cpuPercent;
      case RAM -> ramPercent;
      case TEMP -> temperatureCelsius;
    };
  }

  public Availability availability(Metric m) {
    return value(m) == null ? Availability.UNAVAILABLE : Availability.AVAILABLE;
  }
}
