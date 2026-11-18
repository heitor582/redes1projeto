package br.edu.netpulse.server;

import br.edu.netpulse.common.Mode;
import br.edu.netpulse.server.alerts.AlertHistory;
import br.edu.netpulse.server.metrics.*;
import br.edu.netpulse.server.network.NetPulseServer;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
  public static void main(String[] args) {
    try {
      Map<String, String> flags = new HashMap<>();
      for (String arg : args) {
        if (arg.equals("--help")) {
          System.out.println(
              "NetPulse: --mode=real|simulated --tcp-port=5000 --udp-port=5000 --bind=0.0.0.0"
                  + " --seed=42 --history-file=data/alerts.log");
          return;
        }
        String[] p = arg.split("=", 2);
        if (p.length != 2
            || !Set.of("--mode", "--tcp-port", "--udp-port", "--bind", "--seed", "--history-file")
                .contains(p[0])) throw new IllegalArgumentException("Argumento inválido: " + arg);
        flags.put(p[0], p[1]);
      }
      Mode mode = Mode.valueOf(flags.getOrDefault("--mode", "real").toUpperCase(Locale.ROOT));
      MetricsStrategy strategy =
          MetricsStrategyFactory.create(
              mode,
              () -> System.getProperty("os.name"),
              OshiMetricsReader::nativeReader,
              Long.parseLong(flags.getOrDefault("--seed", "42")));
      AlertHistory history =
          new AlertHistory(
              java.nio.file.Path.of(flags.getOrDefault("--history-file", "data/alerts.log")));
      NetPulseServer server;
      try {
        server =
            new NetPulseServer(
                InetAddress.getByName(flags.getOrDefault("--bind", "0.0.0.0")),
                port(flags.getOrDefault("--tcp-port", "5000")),
                port(flags.getOrDefault("--udp-port", "5000")),
                strategy,
                mode,
                MetricsStrategyFactory.platform(System.getProperty("os.name")),
                history);
      } catch (Exception e) {
        history.close();
        throw e;
      }
      Runtime.getRuntime().addShutdownHook(new Thread(server::close, "netpulse-shutdown"));
      server.start();
      System.out.printf(
          "NetPulse %s | TCP %d | UDP %d | %s%n",
          mode, server.tcpPort(), server.udpPort(), strategy.sourceDescription());
      new CountDownLatch(1).await();
    } catch (Exception e) {
      System.err.println("Não foi possível iniciar NetPulse: " + e.getMessage());
      System.exit(1);
    }
  }

  private static int port(String text) {
    int p = Integer.parseInt(text);
    if (p < 1 || p > 65535) throw new IllegalArgumentException("Porta deve estar entre 1 e 65535");
    return p;
  }
}
