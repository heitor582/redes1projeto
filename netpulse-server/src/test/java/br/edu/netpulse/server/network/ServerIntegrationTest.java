package br.edu.netpulse.server.network;

import static org.junit.jupiter.api.Assertions.*;

import br.edu.netpulse.common.*;
import br.edu.netpulse.server.metrics.MetricsStrategy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ServerIntegrationTest {
  private NetPulseServer server;

  @BeforeEach
  void start() throws Exception {
    server =
        new NetPulseServer(
            InetAddress.getLoopbackAddress(),
            0,
            0,
            new MetricsStrategy() {
              public SystemSnapshot sample() {
                return Snapshot.VALUE;
              }

              public String sourceDescription() {
                return "test";
              }
            },
            Mode.SIMULATED,
            "Linux");
    server.start();
  }

  private static class Snapshot {
    static final SystemSnapshot VALUE = new SystemSnapshot(1234, 45.0, 50.0, null, Mode.SIMULATED);
  }

  @AfterEach
  void stop() {
    server.close();
    assertTrue(server.terminated());
  }

  class Peer implements AutoCloseable {
    final Socket tcp = new Socket(InetAddress.getLoopbackAddress(), server.tcpPort());
    final DatagramSocket udp = new DatagramSocket();
    final InputStream in;
    final UUID id;
    long request;

    Peer() throws Exception {
      tcp.setSoTimeout(2500);
      in = new BufferedInputStream(tcp.getInputStream());
      Protocol.writeLine(tcp.getOutputStream(), "HELLO|1");
      String[] w = Protocol.fields(Protocol.readLine(in));
      assertEquals("WELCOME", w[0]);
      id = Protocol.uuid(w[1]);
      udp.connect(InetAddress.getLoopbackAddress(), server.udpPort());
      udp.setSoTimeout(2500);
    }

    void register() throws Exception {
      send("REGISTER|" + id);
      assertEquals("REGISTERED|" + id, receive());
    }

    void send(String s) throws Exception {
      byte[] b = s.getBytes(StandardCharsets.UTF_8);
      udp.send(new DatagramPacket(b, b.length));
    }

    String receive() throws Exception {
      byte[] b = new byte[1200];
      DatagramPacket p = new DatagramPacket(b, b.length);
      udp.receive(p);
      return Protocol.decode(Arrays.copyOf(b, p.getLength()));
    }

    Protocol.Telemetry metric() throws Exception {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < end) {
        String s = receive();
        if (s.startsWith("METRIC|")) return Protocol.telemetry(s);
      }
      throw new AssertionError("sem telemetria");
    }

    String command(String cmd) throws Exception {
      long id = ++request;
      Protocol.writeLine(tcp.getOutputStream(), "REQ|" + id + "|" + cmd);
      String s;
      do {
        s = Protocol.readLine(in);
      } while (s != null && !s.startsWith("RES|" + id + "|"));
      assertNotNull(s);
      return s;
    }

    List<AlertRecord> page(String command) throws Exception {
      long requestId = ++request;
      Protocol.writeLine(tcp.getOutputStream(), "REQ|" + requestId + "|" + command);
      List<AlertRecord> result = new ArrayList<>();
      while (true) {
        String line = Protocol.readLine(in);
        assertNotNull(line);
        if (line.startsWith("ENTRY|" + requestId + "|")) {
          String[] f = Protocol.fields(line);
          result.add(AlertRecord.decode(String.join("|", Arrays.copyOfRange(f, 2, 10))));
        } else if (line.startsWith("RES|" + requestId + "|")) {
          assertTrue(line.contains("|OK|"));
          return result;
        }
      }
    }

    void drain() throws Exception {
      udp.setSoTimeout(70);
      try {
        while (true) receive();
      } catch (SocketTimeoutException expected) {
        /* bounded drain */
      } finally {
        udp.setSoTimeout(2500);
      }
    }

    public void close() throws IOException {
      tcp.close();
      udp.close();
    }
  }

  @Test
  void threeClientsHaveIndependentIntervalsStopAndLifecycle() throws Exception {
    try (Peer a = new Peer();
        Peer b = new Peer();
        Peer c = new Peer()) {
      for (Peer p : List.of(a, b, c)) {
        p.register();
        assertTrue(p.command("START").contains("OK|STATE=STARTED"));
      }
      assertTrue(a.command("SET_INTERVAL|250").contains("OK|INTERVAL=250"));
      assertTrue(b.command("SET_INTERVAL|1000").contains("OK"));
      assertTrue(c.command("SET_INTERVAL|2000").contains("OK"));
      assertTrue(b.command("GET_STATUS").contains("INTERVAL=1000"));
      assertTrue(c.command("GET_STATUS").contains("INTERVAL=2000"));
      a.metric();
      a.metric();
      long begin = System.nanoTime();
      a.metric();
      long fast = System.nanoTime() - begin;
      b.metric();
      b.drain();
      begin = System.nanoTime();
      b.metric();
      long slow = System.nanoTime() - begin;
      assertTrue(slow > fast, "intervalos reais distintos");
      assertTrue(a.command("STOP").contains("STATE=STOPPED"));
      a.drain();
      a.udp.setSoTimeout(400);
      assertThrows(SocketTimeoutException.class, a::receive);
      assertNotNull(b.metric());
      assertNotNull(c.metric());
      a.register();
      assertTrue(a.command("GET_STATUS").contains("STATE=STOPPED"));
      c.close();
      assertTrue(b.command("GET_STATUS").contains("OK"));
    }
    await(() -> server.sessionCount() == 0);
  }

  @Test
  void registrationIsolationAndGlobalAlertsReachAllClients() throws Exception {
    try (Peer a = new Peer();
        Peer b = new Peer()) {
      a.send("REGISTER|" + UUID.randomUUID());
      a.udp.setSoTimeout(200);
      assertThrows(SocketTimeoutException.class, a::receive);
      a.udp.setSoTimeout(2500);
      a.register();
      assertTrue(a.command("GET_STATUS").contains("UDP=REGISTERED"));
      assertTrue(b.command("GET_STATUS").contains("UDP=UNREGISTERED"));
      assertTrue(a.command("SET_INTERVAL|249").contains("ERROR|INVALID_INTERVAL"));
      assertTrue(a.command("SET_THRESHOLD|CPU|101").contains("ERROR|INVALID_THRESHOLD"));
      assertTrue(a.command("SET_THRESHOLD|CPU|30").contains("OK"));
      assertEquals("CONFIG|CPU|30.00", Protocol.readLine(b.in));
      assertTrue(Protocol.readLine(b.in).startsWith("ALERT|")); // Even B is STOPPED.
      assertTrue(b.command("GET_STATUS").contains("CPU=30.00"));
      List<AlertRecord> active = a.page("GET_ACTIVE_ALERTS");
      assertEquals(1, active.size());
      assertEquals(active, b.page("GET_ACTIVE_ALERTS"));
      List<AlertRecord> history = a.page("GET_ALERTS|0|50");
      assertEquals(1, history.size());
      assertEquals(history, b.page("GET_ALERTS|0|50"));
      assertTrue(a.command("SET_THRESHOLD|CPU|60").contains("OK"));
      assertEquals("CONFIG|CPU|60.00", Protocol.readLine(b.in));
      assertTrue(Protocol.readLine(b.in).startsWith("RECOVERY|"));
      assertTrue(a.page("GET_ACTIVE_ALERTS").isEmpty());
      assertTrue(b.page("GET_ACTIVE_ALERTS").isEmpty());
      history = b.page("GET_ALERTS|0|50");
      assertEquals(2, history.size());
      assertEquals("RECOVERY", history.get(0).type());
      assertEquals(1, a.page("GET_ALERTS|" + history.get(0).id() + "|50").size());
      assertTrue(a.command("GET_ALERTS|0|51").contains("ERROR|INVALID_HISTORY_LIMIT"));
      try (Peer later = new Peer()) {
        assertEquals(history, later.page("GET_ALERTS|0|50"));
        assertTrue(later.command("GET_STATUS").contains("CPU=60.00"));
      }
      assertTrue(a.command("GET_STATUS").contains("INTERVAL=1000"));
    }
  }

  @Test
  void acceptsFiveClientsAndRejectsInvalidHandshake() throws Exception {
    List<Peer> peers = new ArrayList<>();
    try {
      for (int i = 0; i < 5; i++) peers.add(new Peer());
      for (Peer p : peers) assertTrue(p.command("GET_STATUS").contains("STATE=STOPPED"));
    } finally {
      for (Peer p : peers) p.close();
    }
    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.tcpPort())) {
      s.setSoTimeout(2000);
      Protocol.writeLine(s.getOutputStream(), "HELLO|2");
      assertEquals("ERROR|INVALID_HANDSHAKE", Protocol.readLine(s.getInputStream()));
    }
  }

  @Test
  void closedSessionCannotRegister() throws Exception {
    Peer p = new Peer();
    UUID id = p.id;
    p.tcp.close();
    await(() -> server.sessionCount() == 0);
    try {
      p.send("REGISTER|" + id);
      p.udp.setSoTimeout(200);
      assertThrows(SocketTimeoutException.class, p::receive);
    } finally {
      p.close();
    }
  }

  private static void await(java.util.function.BooleanSupplier condition) {
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          while (!condition.getAsBoolean()) new CountDownLatch(1).await(20, TimeUnit.MILLISECONDS);
        });
  }
}
