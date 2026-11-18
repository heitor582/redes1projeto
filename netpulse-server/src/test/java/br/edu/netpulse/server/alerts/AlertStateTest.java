package br.edu.netpulse.server.alerts;

import static org.junit.jupiter.api.Assertions.*;

import br.edu.netpulse.common.*;
import java.util.*;
import org.junit.jupiter.api.*;

class AlertStateTest {
  private final Map<Metric, Double> thresholds = new EnumMap<>(Metric.class);

  AlertStateTest() {
    for (Metric m : Metric.values()) thresholds.put(m, m.defaultThreshold);
  }

  private SystemSnapshot s(Double cpu, Double temp) {
    return new SystemSnapshot(100, cpu, 10.0, temp, Mode.REAL);
  }

  @Test
  void crossingSuppressionHysteresisAndUnavailable() {
    AlertState state = new AlertState();
    assertTrue(state.evaluate(s(79.0, null), thresholds).isEmpty());
    assertEquals(1, state.evaluate(s(80.0, null), thresholds).size());
    assertTrue(state.evaluate(s(90.0, null), thresholds).isEmpty());
    assertTrue(state.evaluate(s(null, null), thresholds).isEmpty());
    assertTrue(state.evaluate(s(79.0, null), thresholds).isEmpty());
    assertTrue(state.evaluate(s(78.0, null), thresholds).get(0).startsWith("RECOVERY"));
    assertEquals(1, state.evaluate(s(81.0, null), thresholds).size());
  }

  @Test
  void thresholdChangesAndSensorReturns() {
    AlertState state = new AlertState();
    assertTrue(state.evaluate(s(40.0, null), thresholds).isEmpty());
    thresholds.put(Metric.CPU, 30.0);
    assertEquals(1, state.evaluate(s(40.0, null), thresholds).size());
    thresholds.put(Metric.CPU, 50.0);
    assertTrue(
        state.evaluate(s(40.0, 80.0), thresholds).stream().anyMatch(e -> e.startsWith("RECOVERY")));
    assertTrue(state.evaluate(s(40.0, 80.0), thresholds).isEmpty());
  }
}
