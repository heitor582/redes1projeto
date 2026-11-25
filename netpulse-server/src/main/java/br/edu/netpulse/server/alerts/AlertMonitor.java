package br.edu.netpulse.server.alerts;

import br.edu.netpulse.common.*;
import java.util.*;
import java.util.function.Consumer;

/** One set of rules and one evaluator for the monitored server, regardless of client count. */
public final class AlertMonitor {
  private final UUID serverRun = UUID.randomUUID();
  private final AlertHistory history;
  private final AlertState state = new AlertState();
  private final EnumMap<Metric, AlertRecord> active = new EnumMap<>(Metric.class);
  private final Consumer<String> broadcast;

  public AlertMonitor(AlertHistory history, Consumer<String> broadcast) {
    this.history = history;
    this.broadcast = broadcast;
  }

  public synchronized void evaluate(SystemSnapshot snapshot) {
    for (String event : state.evaluate(snapshot, history.thresholds())) {
      Metric metric = Metric.valueOf(Protocol.fields(event)[2]);
      AlertRecord record =
          history.append(serverRun, snapshot, event, history.thresholds().get(metric));
      if (record.type().equals("ALERT")) active.put(metric, record);
      else active.remove(metric);
      // Quando os clientes receberem o evento, já poderão consultar o histórico e os ativos.
      broadcast.accept(event);
    }
  }

  public synchronized void threshold(Metric metric, double value, SystemSnapshot snapshot) {
    history.setThreshold(metric, value);
    broadcast.accept(Protocol.configuration(metric, value));
    if (snapshot != null) evaluate(snapshot);
  }

  public synchronized Map<Metric, Double> thresholds() {
    return history.thresholds();
  }

  public synchronized List<AlertRecord> history(long before, int limit) {
    return history.page(before, limit);
  }

  public synchronized List<AlertRecord> active() {
    return List.copyOf(active.values());
  }
}
