package br.edu.netpulse.server.network;

import br.edu.netpulse.common.*;
import br.edu.netpulse.server.alerts.AlertMonitor;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded single-writer queue prevents slow TCP peers from blocking global sampling. */
final class ClientSession implements AutoCloseable {
  final UUID id = UUID.randomUUID();
  final Socket socket;
  private final ArrayBlockingQueue<String> outgoing = new ArrayBlockingQueue<>(128);
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AlertMonitor monitor;
  boolean started;
  int interval = 1000;
  InetSocketAddress endpoint;
  long sequence, lastSent;

  ClientSession(Socket socket, AlertMonitor monitor) {
    this.socket = socket;
    this.monitor = monitor;
  }

  boolean send(String line) {
    if (closed.get()) return false;
    if (outgoing.offer(line)) return true;
    close();
    return false;
  }

  void writeLoop() {
    try {
      while (!closed.get()) {
        String line = outgoing.poll(500, TimeUnit.MILLISECONDS);
        if (line != null) Protocol.writeLine(socket.getOutputStream(), line);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      System.getLogger(getClass().getName())
          .log(System.Logger.Level.DEBUG, "Saída TCP encerrada", e);
    } finally {
      close();
    }
  }

  synchronized String command(Protocol.Request r, SystemSnapshot snapshot) {
    if (r.command().equals("GET_ALERTS") || r.command().equals("GET_ACTIVE_ALERTS")) {
      List<AlertRecord> page =
          r.command().equals("GET_ACTIVE_ALERTS")
              ? monitor.active()
              : monitor.history(
                  Protocol.cursor(r.args().get(0)), Protocol.historyLimit(r.args().get(1)));
      for (AlertRecord entry : page) send(Protocol.historyEntry(r.id(), entry));
      String result =
          (r.command().equals("GET_ACTIVE_ALERTS") ? "ACTIVE=" : "HISTORY=") + page.size();
      send(Protocol.response(r.id(), true, result));
      return result;
    }
    String result =
        switch (r.command()) {
          case "START" -> {
            started = true;
            lastSent = 0;
            yield "STATE=STARTED";
          }
          case "STOP" -> {
            started = false;
            yield "STATE=STOPPED";
          }
          case "SET_INTERVAL" -> {
            interval = Protocol.interval(r.args().get(0));
            yield "INTERVAL=" + interval;
          }
          case "SET_THRESHOLD" -> {
            Metric m = Metric.valueOf(r.args().get(0));
            monitor.threshold(m, m.validateThreshold(Protocol.number(r.args().get(1))), snapshot);
            yield "THRESHOLD=" + m + "," + Protocol.exactDecimal(monitor.thresholds().get(m));
          }
          case "GET_STATUS" ->
              "STATE="
                  + (started ? "STARTED" : "STOPPED")
                  + ";INTERVAL="
                  + interval
                  + ";CPU="
                  + Protocol.exactDecimal(monitor.thresholds().get(Metric.CPU))
                  + ";RAM="
                  + Protocol.exactDecimal(monitor.thresholds().get(Metric.RAM))
                  + ";TEMP="
                  + Protocol.exactDecimal(monitor.thresholds().get(Metric.TEMP))
                  + ";UDP="
                  + (endpoint == null ? "UNREGISTERED" : "REGISTERED");
          default -> throw new IllegalArgumentException("UNKNOWN_COMMAND");
        };
    send(Protocol.response(r.id(), true, result));

    return result;
  }

  public void close() {
    if (closed.compareAndSet(false, true))
      try {
        socket.close();
      } catch (IOException e) {
        System.getLogger(getClass().getName()).log(System.Logger.Level.DEBUG, "Fechamento TCP", e);
      }
  }
}
