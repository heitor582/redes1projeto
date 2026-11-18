package br.edu.netpulse.client.network;

import static org.junit.jupiter.api.Assertions.*;

import br.edu.netpulse.common.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TelemetryTrackerTest {
  @Test
  void discardsDuplicatesOldForeignAndWrongMode() {
    UUID id = UUID.randomUUID();
    TelemetryTracker t = new TelemetryTracker(id, Mode.REAL);
    t.started(true, 1);
    SystemSnapshot s = new SystemSnapshot(1, 30.0, null, null, Mode.REAL);
    assertTrue(t.accept(new Protocol.Telemetry(id, 2, s), 2));
    assertFalse(t.accept(new Protocol.Telemetry(id, 2, s), 3));
    assertFalse(t.accept(new Protocol.Telemetry(id, 1, s), 4));
    assertFalse(t.accept(new Protocol.Telemetry(UUID.randomUUID(), 3, s), 5));
    assertFalse(
        t.accept(
            new Protocol.Telemetry(id, 3, new SystemSnapshot(1, null, null, null, Mode.SIMULATED)),
            6));
    assertTrue(t.accept(new Protocol.Telemetry(id, 3, s), 7));
  }

  @Test
  void lossUsesLocalTimeOnlyWhileStarted() {
    TelemetryTracker t = new TelemetryTracker(UUID.randomUUID(), Mode.REAL);
    assertFalse(t.stale(99_000_000_000L, 1000));
    t.started(true, 1_000_000_000L);
    assertFalse(t.stale(4_000_000_000L, 1000));
    assertTrue(t.stale(6_000_000_000L, 1000));
    assertFalse(t.stale(6_000_000_000L, 2000));
    t.started(false, 7_000_000_000L);
    assertFalse(t.stale(99_000_000_000L, 1000));
  }
}
