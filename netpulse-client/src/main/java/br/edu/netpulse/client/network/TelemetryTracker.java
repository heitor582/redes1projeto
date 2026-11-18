package br.edu.netpulse.client.network;

import br.edu.netpulse.common.*;
import java.util.UUID;

/** Ordering and loss detection use local monotonic time, never remote wall clock. */
public final class TelemetryTracker {
  private final UUID session;
  private final Mode mode;
  private long sequence, lastReceived, startedAt;
  private boolean started;

  public TelemetryTracker(UUID session, Mode mode) {
    this.session = session;
    this.mode = mode;
  }

  public synchronized boolean accept(Protocol.Telemetry metric, long now) {
    if (!session.equals(metric.session())
        || mode != metric.snapshot().mode()
        || metric.sequence() <= sequence) return false;
    sequence = metric.sequence();
    if (!started) return false;
    lastReceived = now;
    return true;
  }

  public synchronized void started(boolean value, long now) {
    if (value && !started) {
      startedAt = now;
      lastReceived = 0;
    }
    started = value;
  }

  public synchronized boolean receiving() {
    return started && lastReceived != 0;
  }

  public synchronized boolean stale(long now, int interval) {
    return started
        && now - (lastReceived == 0 ? startedAt : lastReceived)
            > Math.max(4000L, 3L * interval) * 1_000_000L;
  }
}
