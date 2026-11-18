package br.edu.netpulse.server.alerts;

import br.edu.netpulse.common.*;
import java.util.*;

/** One hysteresis latch per metric; callers define the shared or session scope. */
public final class AlertState {
  private final EnumMap<Metric, Boolean> active = new EnumMap<>(Metric.class);

  public List<String> evaluate(SystemSnapshot snapshot, Map<Metric, Double> thresholds) {
    List<String> events = new ArrayList<>();
    for (Metric metric : Metric.values()) {
      Double value = snapshot.value(metric);
      if (value == null) continue;
      double threshold = thresholds.get(metric);
      boolean high = active.getOrDefault(metric, false);
      if (!high && value >= threshold) {
        active.put(metric, true);
        events.add(
            Protocol.alert("ALERT", snapshot.timestampEpochMillis(), metric, value, threshold));
      } else if (high && value <= threshold - 2) {
        active.put(metric, false);
        events.add(
            Protocol.alert("RECOVERY", snapshot.timestampEpochMillis(), metric, value, threshold));
      }
    }
    return events;
  }
}
