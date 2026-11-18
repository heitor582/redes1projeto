package br.edu.netpulse.common;

import java.util.*;

/** A server-wide history entry retains the server execution and data mode. */
public record AlertRecord(
    long id,
    long timestamp,
    UUID serverRun,
    Mode mode,
    String type,
    Metric metric,
    double value,
    double threshold) {
  public AlertRecord {
    if (id <= 0 || timestamp <= 0) throw new IllegalArgumentException("INVALID_EVENT_ID");
    Objects.requireNonNull(serverRun);
    Objects.requireNonNull(mode);
    Objects.requireNonNull(metric);
    if (!Set.of("ALERT", "RECOVERY").contains(type))
      throw new IllegalArgumentException("INVALID_EVENT_TYPE");
    metric.validateThreshold(threshold);
    new SystemSnapshot(
        timestamp,
        metric == Metric.CPU ? value : null,
        metric == Metric.RAM ? value : null,
        metric == Metric.TEMP ? value : null,
        mode);
  }

  public String encode() {
    return id
        + "|"
        + timestamp
        + "|"
        + serverRun
        + "|"
        + mode
        + "|"
        + type
        + "|"
        + metric
        + "|"
        + Protocol.exactDecimal(value)
        + "|"
        + Protocol.exactDecimal(threshold);
  }

  public static AlertRecord decode(String text) {
    String[] f = Protocol.fields(text);
    Protocol.count(f, 8);
    return new AlertRecord(
        Protocol.positive(f[0]),
        Protocol.positive(f[1]),
        Protocol.uuid(f[2]),
        Mode.valueOf(f[3]),
        f[4],
        Metric.valueOf(f[5]),
        Protocol.number(f[6]),
        Protocol.number(f[7]));
  }
}
