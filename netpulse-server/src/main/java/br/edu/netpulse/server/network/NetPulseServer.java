package br.edu.netpulse.server.network;

import br.edu.netpulse.common.*;
import br.edu.netpulse.server.alerts.*;
import br.edu.netpulse.server.metrics.MetricsStrategy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class NetPulseServer implements AutoCloseable {
  private final MetricsStrategy strategy;
  private final Mode mode;
  private final AlertHistory history;
  private final AlertMonitor alerts;
  private final String platform;
  private final ServerSocket tcp;
  private final DatagramSocket udp;
  private final ConcurrentHashMap<UUID, ClientSession> sessions = new ConcurrentHashMap<>();
  private final AtomicReference<SystemSnapshot> latest = new AtomicReference<>();
  private final AtomicBoolean running = new AtomicBoolean();
  private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
  private final Semaphore capacity = new Semaphore(16);
  private final ExecutorService loops = Executors.newFixedThreadPool(2);
  private final ExecutorService handlers = Executors.newFixedThreadPool(16);
  private final ExecutorService writers =
      new ThreadPoolExecutor(16, 16, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
  private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor();

  public NetPulseServer(
      InetAddress bind,
      int tcpPort,
      int udpPort,
      MetricsStrategy strategy,
      Mode mode,
      String platform)
      throws IOException {
    this(bind, tcpPort, udpPort, strategy, mode, platform, new AlertHistory());
  }

  public NetPulseServer(
      InetAddress bind,
      int tcpPort,
      int udpPort,
      MetricsStrategy strategy,
      Mode mode,
      String platform,
      AlertHistory history)
      throws IOException {
    this.history = history;
    this.alerts =
        new AlertMonitor(history, line -> sessions.values().forEach(session -> session.send(line)));
    this.strategy = strategy;
    this.mode = mode;
    this.platform = platform;
    tcp = new ServerSocket();
    DatagramSocket created = null;
    try {
      tcp.bind(new InetSocketAddress(bind, tcpPort));
      created = new DatagramSocket(new InetSocketAddress(bind, udpPort));
    } catch (IOException e) {
      tcp.close();
      if (created != null) created.close();
      throw e;
    }
    udp = created;
  }

  public int tcpPort() {
    return tcp.getLocalPort();
  }

  public int udpPort() {
    return udp.getLocalPort();
  }

  public int sessionCount() {
    return sessions.size();
  }

  public SystemSnapshot latest() {
    return latest.get();
  }

  public void start() {
    if (!running.compareAndSet(false, true))
      throw new IllegalStateException("Servidor já iniciado");
    loops.submit(this::acceptLoop);
    loops.submit(this::registrationLoop);
    // A coleta é compartilhada e continua sem clientes. O envio respeita o intervalo de cada sessão.
    sampler.scheduleWithFixedDelay(this::sample, 0, 500, TimeUnit.MILLISECONDS);
    dispatcher.scheduleWithFixedDelay(this::dispatch, 0, 25, TimeUnit.MILLISECONDS);
  }

  private void sample() {
    try {
      SystemSnapshot s = strategy.sample();
      latest.set(s);
      alerts.evaluate(s);
    } catch (RuntimeException | LinkageError e) {
      System.getLogger(getClass().getName())
          .log(System.Logger.Level.WARNING, "Falha de coleta; snapshot indisponível", e);
      latest.set(new SystemSnapshot(System.currentTimeMillis(), null, null, null, mode));
    }
  }

  private void acceptLoop() {
    while (running.get())
      try {
        Socket socket = tcp.accept();
        // O handshake já ocupa uma vaga, pois a leitura do HELLO pode ficar bloqueada.
        if (!capacity.tryAcquire()) {
          socket.close();
          continue;
        }
        connections.add(socket);
        try {
          handlers.submit(() -> handle(socket));
        } catch (RejectedExecutionException e) {
          connections.remove(socket);
          capacity.release();
          socket.close();
        }
      } catch (IOException e) {
        if (running.get())
          System.getLogger(getClass().getName())
              .log(System.Logger.Level.WARNING, "Falha ao aceitar TCP", e);
      }
  }

  private void handle(Socket socket) {
    ClientSession session = null;
    try {
      socket.setSoTimeout(5000);
      socket.setTcpNoDelay(true);
      InputStream in = new BufferedInputStream(socket.getInputStream());
      if (!"HELLO|1".equals(Protocol.readLine(in))) {
        Protocol.writeLine(socket.getOutputStream(), "ERROR|INVALID_HANDSHAKE");
        return;
      }
      session = new ClientSession(socket, alerts);
      ClientSession target = session;
      writers.submit(target::writeLoop);
      // Enfileira o WELCOME antes de expor a sessão às notificações de alertas.
      session.send("WELCOME|" + session.id + "|" + mode + "|" + platform);
      sessions.put(session.id, session);
      socket.setSoTimeout(0);
      String line;
      while (running.get() && (line = Protocol.readLine(in)) != null) {
        try {
          session.command(Protocol.request(line), latest.get());
        } catch (IllegalArgumentException e) {
          long id = 0;
          try {
            String[] f = Protocol.fields(line);
            if (f.length > 1 && f[0].equals("REQ")) id = Protocol.positive(f[1]);
          } catch (IllegalArgumentException invalid) {
            System.getLogger(getClass().getName())
                .log(System.Logger.Level.DEBUG, "Sem requestId válido", invalid);
          }
          session.send(Protocol.response(id, false, Protocol.errorCode(e)));
        }
      }
    } catch (IOException | RejectedExecutionException e) {
      System.getLogger(getClass().getName())
          .log(System.Logger.Level.DEBUG, "Cliente TCP desconectado", e);
    } finally {
      if (session != null) {
        sessions.remove(session.id);
        session.close();
      }
      try {
        socket.close();
      } catch (IOException e) {
        System.getLogger(getClass().getName()).log(System.Logger.Level.DEBUG, "Fechamento", e);
      }
      connections.remove(socket);
      capacity.release();
    }
  }

  private void registrationLoop() {
    byte[] buffer = new byte[Protocol.MAX_DATAGRAM + 1];
    while (running.get())
      try {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        udp.receive(packet);
        if (packet.getLength() > Protocol.MAX_DATAGRAM) continue;
        String[] f =
            Protocol.fields(Protocol.decode(Arrays.copyOf(packet.getData(), packet.getLength())));
        Protocol.count(f, 2);
        if (!f[0].equals("REGISTER")) continue;
        ClientSession session = sessions.get(Protocol.uuid(f[1]));
        if (session == null || session.socket.isClosed()) continue;
        synchronized (session) {
          // O UUID identifica a sessão TCP. A origem deste datagrama indica para onde enviar as métricas.
          session.endpoint = new InetSocketAddress(packet.getAddress(), packet.getPort());
        }
        sendUdp(
            "REGISTERED|" + session.id,
            new InetSocketAddress(packet.getAddress(), packet.getPort()));
      } catch (IllegalArgumentException e) {
        System.getLogger(getClass().getName())
            .log(System.Logger.Level.DEBUG, "Registro UDP inválido", e);
      } catch (IOException e) {
        if (running.get())
          System.getLogger(getClass().getName()).log(System.Logger.Level.DEBUG, "Falha UDP", e);
      }
  }

  private void dispatch() {
    // Usa a mesma amostra para todos os envios desta rodada, mesmo se a coleta publicar outra.
    SystemSnapshot snapshot = latest.get();
    if (snapshot == null) return;
    long now = System.nanoTime();
    for (ClientSession session : sessions.values())
      synchronized (session) {
        if (!session.started
            || session.endpoint == null
            || session.socket.isClosed()
            || now - session.lastSent < TimeUnit.MILLISECONDS.toNanos(session.interval)) continue;
        try {
          sendUdp(Protocol.telemetry(session.id, ++session.sequence, snapshot), session.endpoint);
          session.lastSent = now;
        } catch (IOException e) {
          System.getLogger(getClass().getName())
              .log(System.Logger.Level.DEBUG, "Envio UDP falhou", e);
        }
      }
  }

  private void sendUdp(String message, InetSocketAddress endpoint) throws IOException {
    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
    udp.send(new DatagramPacket(bytes, bytes.length, endpoint));
  }

  public void close() {
    running.set(false);
    try {
      tcp.close();
    } catch (IOException e) {
      System.getLogger(getClass().getName())
          .log(System.Logger.Level.DEBUG, "Fechamento listener", e);
    }
    udp.close();
    sessions.values().forEach(ClientSession::close);
    sessions.clear();
    for (Socket socket : connections)
      try {
        socket.close();
      } catch (IOException e) {
        System.getLogger(getClass().getName())
            .log(System.Logger.Level.DEBUG, "Fechamento cliente", e);
      }
    for (ExecutorService pool : List.of(loops, handlers, writers, sampler, dispatcher))
      pool.shutdownNow();
    for (ExecutorService pool : List.of(loops, handlers, writers, sampler, dispatcher))
      try {
        if (!pool.awaitTermination(3, TimeUnit.SECONDS))
          System.getLogger(getClass().getName())
              .log(System.Logger.Level.WARNING, "Executor ainda encerrando");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    history.close();
  }

  public boolean terminated() {
    return loops.isTerminated()
        && handlers.isTerminated()
        && writers.isTerminated()
        && sampler.isTerminated()
        && dispatcher.isTerminated();
  }
}
