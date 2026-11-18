package br.edu.netpulse.client.network;

import static org.junit.jupiter.api.Assertions.*;

import br.edu.netpulse.common.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ConnectionTest {
  @Test
  void singleReaderCorrelatesResponsesAndAsyncEventsAndCloses() throws Exception {
    UUID id = UUID.randomUUID();
    CountDownLatch event = new CountDownLatch(1), metric = new CountDownLatch(1);
    BlockingQueue<String> states = new LinkedBlockingQueue<>();
    ExecutorService peer = Executors.newSingleThreadExecutor();
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        DatagramSocket datagrams = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      datagrams.setSoTimeout(3000);
      listener.setSoTimeout(3000);
      Future<?> simulated =
          peer.submit(
              () -> {
                try (Socket socket = listener.accept()) {
                  socket.setSoTimeout(3000);
                  InputStream in = socket.getInputStream();
                  OutputStream out = socket.getOutputStream();
                  assertEquals("HELLO|1", Protocol.readLine(in));
                  Protocol.writeLine(out, "WELCOME|" + id + "|SIMULATED|Linux");
                  byte[] b = new byte[1200];
                  DatagramPacket p = new DatagramPacket(b, b.length);
                  datagrams.receive(p);
                  byte[] ack =
                      ("REGISTERED|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                  datagrams.send(new DatagramPacket(ack, ack.length, p.getSocketAddress()));
                  Protocol.Request request = Protocol.request(Protocol.readLine(in));
                  assertEquals("START", request.command());
                  Protocol.writeLine(out, "ALERT|123|CPU|90|80");
                  Protocol.writeLine(out, "RES|" + request.id() + "|OK|STATE=STARTED");
                  // Explicit TCP barrier ensures START has been consumed before the UDP sample.
                  Protocol.Request status = Protocol.request(Protocol.readLine(in));
                  Protocol.writeLine(
                      out,
                      "RES|"
                          + status.id()
                          + "|OK|STATE=STARTED;INTERVAL=1000;CPU=80;RAM=85;TEMP=75;UDP=REGISTERED");
                  byte[] sample =
                      Protocol.telemetry(
                              id, 1, new SystemSnapshot(123, 90.0, 40.0, null, Mode.SIMULATED))
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                  datagrams.send(new DatagramPacket(sample, sample.length, p.getSocketAddress()));
                  assertNull(Protocol.readLine(in));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      NetPulseConnection client =
          new NetPulseConnection(
              new NetPulseConnection.Listener() {
                public void telemetry(SystemSnapshot s) {
                  metric.countDown();
                }

                public void event(String s) {
                  event.countDown();
                }

                public void state(String a, String b, String c) {
                  states.offer(a);
                }

                public void error(String e) {
                  states.offer(e);
                }
              });
      try {
        assertEquals(
            id,
            client
                .connect("localhost", listener.getLocalPort(), datagrams.getLocalPort())
                .session());
        assertEquals("STATE=STARTED", client.command("START").get(3, TimeUnit.SECONDS));
        client.command("GET_STATUS").get(3, TimeUnit.SECONDS);
        assertTrue(event.await(3, TimeUnit.SECONDS));
        assertTrue(metric.await(3, TimeUnit.SECONDS));
      } finally {
        client.close();
      }
      assertTrue(client.awaitTermination(3, TimeUnit.SECONDS));
      simulated.get(3, TimeUnit.SECONDS);
    } finally {
      peer.shutdownNow();
      assertTrue(peer.awaitTermination(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void refusedConnectionCleansExecutors() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    NetPulseConnection c =
        new NetPulseConnection(
            new NetPulseConnection.Listener() {
              public void telemetry(SystemSnapshot s) {}

              public void event(String s) {}

              public void state(String a, String b, String d) {}

              public void error(String e) {}
            });
    assertThrows(IOException.class, () -> c.connect("localhost", port, port));
    assertTrue(c.isClosed());
    assertTrue(c.awaitTermination(2, TimeUnit.SECONDS));
  }

  @Test
  void historyResponsesCoexistWithLiveEvents() throws Exception {
    ExecutorService peer = Executors.newSingleThreadExecutor();
    CountDownLatch alert = new CountDownLatch(1);
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        DatagramSocket udp = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(3000);
      AlertRecord record =
          new AlertRecord(1, 123, UUID.randomUUID(), Mode.REAL, "ALERT", Metric.RAM, 72, 70);
      Future<?> task =
          peer.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  socket.setSoTimeout(3000);
                  InputStream in = socket.getInputStream();
                  OutputStream out = socket.getOutputStream();
                  assertEquals("HELLO|1", Protocol.readLine(in));
                  Protocol.writeLine(out, "WELCOME|" + UUID.randomUUID() + "|REAL|macOS");
                  for (int i = 0; i < 2; i++) {
                    Protocol.Request request = Protocol.request(Protocol.readLine(in));
                    Protocol.writeLine(out, "ENTRY|" + request.id() + "|" + record.encode());
                    Protocol.writeLine(out, "ALERT|123|RAM|72|70");
                    Protocol.writeLine(
                        out,
                        "RES|"
                            + request.id()
                            + "|OK|"
                            + (request.command().equals("GET_ACTIVE_ALERTS")
                                ? "ACTIVE=1"
                                : "HISTORY=1"));
                  }
                  assertNull(Protocol.readLine(in));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      NetPulseConnection client =
          new NetPulseConnection(
              new NetPulseConnection.Listener() {
                public void telemetry(SystemSnapshot s) {}

                public void event(String s) {
                  alert.countDown();
                }

                public void state(String a, String b, String c) {}

                public void error(String error) {}
              });
      try {
        client.connect("localhost", server.getLocalPort(), udp.getLocalPort());
        assertEquals(List.of(record), client.activeAlerts().get(3, TimeUnit.SECONDS));
        assertEquals(List.of(record), client.alertHistory(0, 50).get(3, TimeUnit.SECONDS));
        assertTrue(alert.await(3, TimeUnit.SECONDS));
      } finally {
        client.close();
      }
      assertTrue(client.awaitTermination(3, TimeUnit.SECONDS));
      task.get(3, TimeUnit.SECONDS);
    } finally {
      peer.shutdownNow();
      assertTrue(peer.awaitTermination(3, TimeUnit.SECONDS));
    }
  }
}
