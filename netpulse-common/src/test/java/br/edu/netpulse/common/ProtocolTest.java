package br.edu.netpulse.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class ProtocolTest {
  @Test
  void snapshotIsImmutableAndRejectsSentinels() {
    SystemSnapshot s = new SystemSnapshot(1, null, 0.0, null, Mode.REAL);
    assertTrue(SystemSnapshot.class.isRecord());
    assertEquals(SystemSnapshot.Availability.UNAVAILABLE, s.availability(Metric.CPU));
    assertEquals(0, s.ramPercent());
    for (double v : new double[] {-1, 101, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(
          IllegalArgumentException.class, () -> new SystemSnapshot(1, v, 50.0, null, Mode.REAL));
    assertThrows(
        IllegalArgumentException.class, () -> new SystemSnapshot(1, 10.0, 10.0, 0.0, Mode.REAL));
  }

  @Test
  void udpRoundTripAndLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      UUID id = UUID.randomUUID();
      SystemSnapshot s = new SystemSnapshot(100, 42.25, 58.0, null, Mode.REAL);
      String line = Protocol.telemetry(id, 1, s);
      assertTrue(line.endsWith("42.25|58.00|NA"));
      assertEquals(new Protocol.Telemetry(id, 1, s), Protocol.telemetry(line));
      assertTrue(line.length() < 1200);
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  void commandsValidateExactFieldsAndRanges() {
    for (String cmd :
        List.of(
            "START",
            "STOP",
            "GET_STATUS",
            "SET_INTERVAL|250",
            "SET_INTERVAL|5000",
            "SET_THRESHOLD|TEMP|20",
            "SET_THRESHOLD|CPU|100")) assertEquals(7, Protocol.request("REQ|7|" + cmd).id());
    for (String cmd :
        List.of(
            "START|extra",
            "STOP|",
            "SET_INTERVAL|249",
            "SET_INTERVAL|5001",
            "SET_THRESHOLD|CPU|NaN",
            "SET_THRESHOLD|RAM|101",
            "SET_THRESHOLD|TEMP|19",
            "SET_THRESHOLD|DISK|30",
            "UNKNOWN"))
      assertThrows(IllegalArgumentException.class, () -> Protocol.request("REQ|7|" + cmd), cmd);
  }

  @Test
  void malformedDatagrams() {
    String prefix = "METRIC|" + UUID.randomUUID() + "|1|123|REAL|";
    for (String s :
        List.of(
            prefix + "NaN|0|NA",
            prefix + "0|0|0",
            prefix + "0|0|NA|extra",
            prefix + "101|0|NA",
            prefix + "0||NA"))
      assertThrows(IllegalArgumentException.class, () -> Protocol.telemetry(s));
    assertThrows(IllegalArgumentException.class, () -> Protocol.uuid("1-1-1-1-1"));
  }

  @Test
  void utf8AndFraming() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Protocol.writeLine(out, "INFO|ação");
    assertEquals("INFO|ação", Protocol.readLine(new ByteArrayInputStream(out.toByteArray())));
    assertThrows(IOException.class, () -> Protocol.decode(new byte[] {(byte) 0xc3, 0x28}));
    assertThrows(
        IOException.class,
        () ->
            Protocol.readLine(
                new ByteArrayInputStream(
                    ("x".repeat(4097) + "\n").getBytes(StandardCharsets.UTF_8))));
    assertThrows(
        EOFException.class,
        () ->
            Protocol.readLine(
                new ByteArrayInputStream("HELLO|1".getBytes(StandardCharsets.UTF_8))));
    assertThrows(IllegalArgumentException.class, () -> Protocol.fields("REQ|1|START\r"));
  }

  @Test
  void historyCommandsAndRecordsAreValidated() {
    AlertRecord record =
        new AlertRecord(1, 123, UUID.randomUUID(), Mode.REAL, "ALERT", Metric.RAM, 72.0, 70.0);
    assertEquals(record, AlertRecord.decode(record.encode()));
    AlertRecord tiny =
        new AlertRecord(
            2, 123, UUID.randomUUID(), Mode.REAL, "ALERT", Metric.CPU, 0.0000002, 0.0000001);
    assertEquals(tiny, AlertRecord.decode(tiny.encode()));
    assertEquals("GET_ALERTS", Protocol.request("REQ|1|GET_ALERTS|0|50").command());
    assertEquals("GET_ACTIVE_ALERTS", Protocol.request("REQ|2|GET_ACTIVE_ALERTS").command());
    for (String command :
        List.of(
            "GET_ALERTS|-1|20",
            "GET_ALERTS|0|0",
            "GET_ALERTS|0|51",
            "GET_ALERTS|0|20|extra",
            "GET_ACTIVE_ALERTS|extra"))
      assertThrows(IllegalArgumentException.class, () -> Protocol.request("REQ|1|" + command));
    assertThrows(
        IllegalArgumentException.class, () -> AlertRecord.decode(record.encode() + "|extra"));
  }
}
