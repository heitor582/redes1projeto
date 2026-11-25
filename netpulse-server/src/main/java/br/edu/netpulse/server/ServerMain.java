package br.edu.netpulse.server;

import br.edu.netpulse.common.Mode;
import br.edu.netpulse.server.alerts.AlertHistory;
import br.edu.netpulse.server.metrics.*;
import br.edu.netpulse.server.network.NetPulseServer;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
  private static final Set<String> OPTIONS =
      Set.of("--mode", "--tcp-port", "--udp-port", "--bind", "--seed", "--history-file");
  private static final String USAGE =
      "NetPulse: --mode=real|simulated --tcp-port=5000 --udp-port=5000 --bind=0.0.0.0"
          + " --seed=42 --history-file=data/alerts.log";

  public static void main(String[] args) {
    try {
      Map<String, String> flags = new HashMap<>();
      for (String arg : args) {
        if (arg.equals("--help")) {
          System.out.println(USAGE);
          return;
        }
        readOption(arg, flags);
      }
      runServer(flags);
    } catch (Exception e) {
      System.err.println("Não foi possível iniciar NetPulse: " + e.getMessage());
      System.exit(1);
    }
  }

  private static void readOption(String argument, Map<String, String> flags) {
    String[] parts = argument.split("=", 2);
    if (parts.length != 2 || !OPTIONS.contains(parts[0])) {
      throw new IllegalArgumentException("Argumento inválido: " + argument);
    }
    flags.put(parts[0], parts[1]);
  }

  private static void runServer(Map<String, String> flags) throws IOException, InterruptedException {
    Mode mode = Mode.valueOf(flags.getOrDefault("--mode", "real").toUpperCase(Locale.ROOT));
    long seed = Long.parseLong(flags.getOrDefault("--seed", "42"));
    MetricsStrategy strategy = MetricsStrategyFactory.create(
        mode, () -> System.getProperty("os.name"), OshiMetricsReader::nativeReader, seed);
    NetPulseServer server = createServer(flags, mode, strategy);

    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "netpulse-shutdown"));
    server.start();
    System.out.printf(
        "NetPulse %s | TCP %d | UDP %d | %s%n",
        mode, server.tcpPort(), server.udpPort(), strategy.sourceDescription());
    // A thread principal aguarda. No encerramento da JVM, o hook fecha o servidor.
    new CountDownLatch(1).await();
  }

  private static NetPulseServer createServer(
      Map<String, String> flags, Mode mode, MetricsStrategy strategy) throws IOException {
    InetAddress bind = InetAddress.getByName(flags.getOrDefault("--bind", "0.0.0.0"));
    int tcpPort = port(flags.getOrDefault("--tcp-port", "5000"));
    int udpPort = port(flags.getOrDefault("--udp-port", "5000"));
    String platform = MetricsStrategyFactory.platform(System.getProperty("os.name"));
    Path historyFile = Path.of(flags.getOrDefault("--history-file", "data/alerts.log"));
    AlertHistory history = new AlertHistory(historyFile);
    try {
      return new NetPulseServer(bind, tcpPort, udpPort, strategy, mode, platform, history);
    } catch (IOException | RuntimeException e) {
      history.close();
      throw e;
    }
  }

  private static int port(String text) {
    int p = Integer.parseInt(text);
    if (p < 1 || p > 65535) throw new IllegalArgumentException("Porta deve estar entre 1 e 65535");
    return p;
  }
}
