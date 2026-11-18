package br.edu.netpulse.client.network;

import br.edu.netpulse.common.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** UI-independent transport. Listener callbacks run on network threads. */
public final class NetPulseConnection implements AutoCloseable {
  public interface Listener {
    void telemetry(SystemSnapshot snapshot);

    void event(String line);

    void state(String tcp, String udp, String monitoring);

    void error(String message);
  }

  public record Welcome(UUID session, Mode mode, String platform) {}

  private record Pending(
      CompletableFuture<String> future,
      long deadline,
      java.util.List<AlertRecord> records,
      int limit,
      boolean active) {}

  private final Listener listener;
  private final Socket tcp = new Socket();
  private DatagramSocket udp;
  private InputStream input;
  private Welcome welcome;
  private TelemetryTracker tracker;
  private final ExecutorService readers = Executors.newFixedThreadPool(2);
  private final ThreadPoolExecutor writer =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64));
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile boolean monitoring, registered;
  private volatile int interval = 1000;
  private volatile long lastRegistrationAck;
  private long connectedAt;

  public NetPulseConnection(Listener listener) {
    this.listener = listener;
  }

  public Welcome connect(String host, int tcpPort, int udpPort) throws IOException {
    try {
      if (host.isBlank() || tcpPort < 1 || tcpPort > 65535 || udpPort < 1 || udpPort > 65535)
        throw new IllegalArgumentException("Endereço ou porta inválidos");
      InetAddress address = InetAddress.getByName(host);
      tcp.connect(new InetSocketAddress(address, tcpPort), 4000);
      tcp.setSoTimeout(4000);
      tcp.setTcpNoDelay(true);
      input = new BufferedInputStream(tcp.getInputStream());
      Protocol.writeLine(tcp.getOutputStream(), "HELLO|1");
      String[] f = Protocol.fields(Protocol.readLine(input));
      Protocol.count(f, 4);
      if (!f[0].equals("WELCOME")
          || !Set.of("Windows", "Linux", "macOS", "Unsupported").contains(f[3]))
        throw new IOException("Handshake inválido");
      welcome = new Welcome(Protocol.uuid(f[1]), Mode.valueOf(f[2]), f[3]);
      tracker = new TelemetryTracker(welcome.session(), welcome.mode());
      udp = new DatagramSocket();
      udp.connect(address, udpPort);
      tcp.setSoTimeout(0);
      connectedAt = System.nanoTime();
      if (closed.get()) throw new IOException("Conexão cancelada");
      readers.submit(this::tcpLoop);
      readers.submit(this::udpLoop);
      timer.scheduleWithFixedDelay(this::register, 0, 10, TimeUnit.SECONDS);
      timer.scheduleWithFixedDelay(this::checkHealth, 1, 1, TimeUnit.SECONDS);
      state();
      return welcome;
    } catch (IOException | RuntimeException e) {
      if (udp != null) udp.close();
      close();
      if (e instanceof IOException io) throw io;
      throw new IOException("Protocolo/endereço inválido: " + e.getMessage(), e);
    }
  }

  public CompletableFuture<java.util.List<AlertRecord>> alertHistory(long before, int limit) {
    java.util.List<AlertRecord> records = new ArrayList<>();
    return submit("GET_ALERTS|" + before + "|" + limit, records, limit, false)
        .thenApply(ignored -> java.util.List.copyOf(records));
  }

  public CompletableFuture<java.util.List<AlertRecord>> activeAlerts() {
    java.util.List<AlertRecord> records = new ArrayList<>();
    return submit("GET_ACTIVE_ALERTS", records, 3, true)
        .thenApply(ignored -> java.util.List.copyOf(records));
  }

  public CompletableFuture<String> command(String command) {
    return submit(command, null, 0, false);
  }

  private CompletableFuture<String> submit(
      String command, java.util.List<AlertRecord> records, int limit, boolean active) {
    CompletableFuture<String> future = new CompletableFuture<>();
    long id = ids.incrementAndGet();
    try {
      Protocol.request("REQ|" + id + "|" + command);
      if (closed.get() || welcome == null) throw new IOException("TCP desconectado");
      if (pending.size() >= 64) throw new IOException("Muitos comandos pendentes");
      pending.put(
          id,
          new Pending(
              future, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), records, limit, active));
      writer.execute(
          () -> {
            try {
              Protocol.writeLine(tcp.getOutputStream(), "REQ|" + id + "|" + command);
            } catch (IOException e) {
              fail("Falha ao enviar comando: " + e.getMessage());
            }
          });
    } catch (IOException | RuntimeException e) {
      pending.remove(id);
      future.completeExceptionally(e);
    }
    return future;
  }

  private void tcpLoop() {
    try {
      String line;
      while (!closed.get() && (line = Protocol.readLine(input)) != null) handleTcp(line);
      if (!closed.get()) fail("Servidor encerrou a conexão TCP");
    } catch (IOException | RuntimeException e) {
      if (!closed.get()) fail("Falha TCP/formato inválido: " + e.getMessage());
    }
  }

  private void handleTcp(String line) throws IOException {
    String[] f = Protocol.fields(line);
    switch (f[0]) {
      case "RES" -> {
        Protocol.count(f, 4);
        long id = Protocol.positive(f[1]);
        if (!f[2].equals("OK") && !f[2].equals("ERROR")) throw new IOException("Resposta inválida");
        Pending p = pending.remove(id);
        if (p == null) return;
        try {
          if (f[2].equals("ERROR")) p.future().completeExceptionally(new IOException(f[3]));
          else {
            if (p.records() != null) {
              String expected = (p.active() ? "ACTIVE=" : "HISTORY=") + p.records().size();
              if (!f[3].equals(expected))
                throw new IllegalArgumentException("INVALID_HISTORY_RESPONSE");
            } else applyStatus(f[3]);
            p.future().complete(f[3]);
          }
        } catch (RuntimeException e) {
          p.future().completeExceptionally(e);
          throw e;
        }
      }
      default -> {
        Protocol.ServerEvent event;
        try {
          event = Protocol.serverEvent(line);
        } catch (IllegalArgumentException e) {
          throw new IOException("Mensagem TCP inválida: " + e.getMessage(), e);
        }
        if (event instanceof Protocol.ConfigurationEvent || event instanceof Protocol.AlertEvent) {
          listener.event(line);
        } else if (event instanceof Protocol.HistoryEntryEvent entryEvent) {
          Pending p = pending.get(entryEvent.requestId());
          if (p == null) return;
          if (p.records() == null || p.records().size() >= p.limit())
            throw new IOException("Resposta de histórico inesperada/excessiva");
          AlertRecord record = entryEvent.record();
          if (!p.active()
              && !p.records().isEmpty()
              && record.id() >= p.records().get(p.records().size() - 1).id())
            throw new IOException("Histórico fora de ordem");
          p.records().add(record);
        }
      }
    }
  }

  private void applyStatus(String status) {
    for (String entry : status.split(";")) {
      String[] pair = entry.split("=", -1);
      if (pair.length != 2) throw new IllegalArgumentException("INVALID_STATUS");
      switch (pair[0]) {
        case "STATE" -> {
          if (!Set.of("STARTED", "STOPPED").contains(pair[1]))
            throw new IllegalArgumentException("INVALID_STATE");
          monitoring = pair[1].equals("STARTED");
          tracker.started(monitoring, System.nanoTime());
        }
        case "INTERVAL" -> interval = Protocol.interval(pair[1]);
        case "CPU", "RAM", "TEMP" ->
            Metric.valueOf(pair[0]).validateThreshold(Protocol.number(pair[1]));
        case "THRESHOLD" -> {
          String[] t = pair[1].split(",", -1);
          if (t.length != 2) throw new IllegalArgumentException("INVALID_THRESHOLD");
          Metric.valueOf(t[0]).validateThreshold(Protocol.number(t[1]));
        }
        case "UDP" -> {
          if (!Set.of("REGISTERED", "UNREGISTERED").contains(pair[1]))
            throw new IllegalArgumentException("INVALID_UDP_STATE");
        }
        default -> throw new IllegalArgumentException("INVALID_STATUS");
      }
    }
    state();
  }

  private void udpLoop() {
    byte[] b = new byte[Protocol.MAX_DATAGRAM + 1];
    while (!closed.get())
      try {
        DatagramPacket p = new DatagramPacket(b, b.length);
        udp.receive(p);
        if (p.getLength() > Protocol.MAX_DATAGRAM)
          throw new IllegalArgumentException("Datagrama excessivo");
        String line = Protocol.decode(Arrays.copyOf(b, p.getLength()));
        if (line.startsWith("REGISTERED|")) {
          String[] f = Protocol.fields(line);
          Protocol.count(f, 2);
          if (!Protocol.uuid(f[1]).equals(welcome.session())) continue;
          registered = true;
          lastRegistrationAck = System.nanoTime();
          state();
        } else {
          Protocol.Telemetry metric = Protocol.telemetry(line);
          if (tracker.accept(metric, System.nanoTime())) {
            listener.telemetry(metric.snapshot());
            state();
          }
        }
      } catch (PortUnreachableException e) {
        if (!closed.get()) {
          listener.error("Porta UDP inacessível; verifique servidor e firewall");
        }
      } catch (IllegalArgumentException | IOException e) {
        if (!closed.get()) {
          listener.error("Datagrama inválido ou falha UDP: " + e.getMessage());
          if (e instanceof SocketException) return;
        }
      }
  }

  private void register() {
    if (closed.get()) return;
    try {
      byte[] b = ("REGISTER|" + welcome.session()).getBytes(StandardCharsets.UTF_8);
      udp.send(new DatagramPacket(b, b.length));
    } catch (IOException e) {
      if (!closed.get()) listener.error("Falha ao registrar UDP: " + e.getMessage());
    }
  }

  private void checkHealth() {
    long now = System.nanoTime();
    for (Map.Entry<Long, Pending> e : pending.entrySet())
      if (now > e.getValue().deadline() && pending.remove(e.getKey(), e.getValue())) {
        e.getValue()
            .future()
            .completeExceptionally(
                new TimeoutException(
                    "Confirmação TCP não recebida; reconecte para sincronizar o estado"));
        fail("Timeout de comando TCP");
        return;
      }
    if (registered && now - lastRegistrationAck > TimeUnit.SECONDS.toNanos(25)) registered = false;
    state();
  }

  private void state() {
    if (closed.get()) {
      listener.state("Desconectado", "Não registrado", "Parado");
      return;
    }
    boolean registrationFailed =
        !registered && System.nanoTime() - connectedAt > TimeUnit.SECONDS.toNanos(4);
    listener.state(
        "Conectado",
        registered
            ? "Registrado"
            : registrationFailed
                ? "Registro sem confirmação (verifique UDP/firewall)"
                : "Registrando",
        !monitoring
            ? "Parado"
            : tracker.stale(System.nanoTime(), interval)
                ? "Sem telemetria"
                : tracker.receiving() ? "Recebendo telemetria" : "Aguardando telemetria");
  }

  private void fail(String message) {
    listener.error(message);
    close();
  }

  public Welcome welcome() {
    return welcome;
  }

  public boolean isClosed() {
    return closed.get();
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      tcp.close();
    } catch (IOException e) {
      listener.error("Falha ao fechar TCP: " + e.getMessage());
    }
    if (udp != null) udp.close();
    readers.shutdownNow();
    writer.shutdownNow();
    timer.shutdownNow();
    pending
        .values()
        .forEach(p -> p.future().completeExceptionally(new IOException("Desconectado")));
    pending.clear();
    listener.state("Desconectado", "Não registrado", "Parado");
  }

  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long end = System.nanoTime() + unit.toNanos(timeout);
    for (ExecutorService e : List.of(readers, writer, timer))
      if (!e.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS))
        return false;
    return true;
  }
}
