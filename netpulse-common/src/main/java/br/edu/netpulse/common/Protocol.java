package br.edu.netpulse.common;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Strict UTF-8, fixed field counts, no escaping: separators/control characters are forbidden. */
public final class Protocol {
  public static final int MAX_LINE = 4096, MAX_DATAGRAM = 1199;

  private Protocol() {}

  public record Request(long id, String command, List<String> args) {
    public Request {
      args = List.copyOf(args);
    }
  }

  public record Telemetry(UUID session, long sequence, SystemSnapshot snapshot) {}

  public sealed interface ServerEvent permits AlertEvent, ConfigurationEvent, HistoryEntryEvent {}

  public record AlertEvent(
      String type, long timestamp, Metric metric, double value, Double threshold)
      implements ServerEvent {}

  public record ConfigurationEvent(Metric metric, double threshold) implements ServerEvent {}

  public record HistoryEntryEvent(long requestId, AlertRecord record) implements ServerEvent {}

  public static String response(long requestId, boolean ok, String payload) {
    return "RES|" + requestId + "|" + (ok ? "OK" : "ERROR") + "|" + payload;
  }

  public static String configuration(Metric metric, double threshold) {
    return "CONFIG|" + metric + "|" + exactDecimal(metric.validateThreshold(threshold));
  }

  public static String alert(
      String type, long timestamp, Metric metric, double value, Double threshold) {
    if (!type.equals("ALERT") && !type.equals("RECOVERY"))
      throw new IllegalArgumentException("INVALID_EVENT_TYPE");
    if (timestamp <= 0) throw new IllegalArgumentException("INVALID_TIMESTAMP");
    Double cpu = metric == Metric.CPU ? value : null,
        ram = metric == Metric.RAM ? value : null,
        temp = metric == Metric.TEMP ? value : null;
    new SystemSnapshot(timestamp, cpu, ram, temp, Mode.REAL);
    String event = type + "|" + timestamp + "|" + metric + "|" + decimal(value);
    if (type.equals("ALERT")) {
      metric.validateThreshold(Objects.requireNonNull(threshold));
      return event + "|" + decimal(threshold);
    }
    if (threshold != null) metric.validateThreshold(threshold);
    return event;
  }

  public static String historyEntry(long requestId, AlertRecord record) {
    if (requestId <= 0) throw new IllegalArgumentException("INVALID_REQUEST_ID");
    return "ENTRY|" + requestId + "|" + record.encode();
  }

  public static ServerEvent serverEvent(String line) {
    String[] f = fields(line);
    return switch (f[0]) {
      case "CONFIG" -> {
        count(f, 3);
        Metric metric = Metric.valueOf(f[1]);
        yield new ConfigurationEvent(metric, metric.validateThreshold(number(f[2])));
      }
      case "ALERT", "RECOVERY" -> {
        count(f, f[0].equals("ALERT") ? 5 : 4);
        long timestamp = positive(f[1]);
        Metric metric = Metric.valueOf(f[2]);
        double value = number(f[3]);
        Double threshold = f.length == 5 ? metric.validateThreshold(number(f[4])) : null;
        if (f[0].equals("ALERT") && threshold == null)
          throw new IllegalArgumentException("INVALID_ALERT");
        Double cpu = metric == Metric.CPU ? value : null,
            ram = metric == Metric.RAM ? value : null,
            temp = metric == Metric.TEMP ? value : null;
        new SystemSnapshot(timestamp, cpu, ram, temp, Mode.REAL);
        yield new AlertEvent(f[0], timestamp, metric, value, threshold);
      }
      case "ENTRY" -> {
        count(f, 10);
        yield new HistoryEntryEvent(
            positive(f[1]), AlertRecord.decode(String.join("|", Arrays.copyOfRange(f, 2, 10))));
      }
      default -> throw new IllegalArgumentException("UNKNOWN_EVENT");
    };
  }

  public static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int n; (n = in.read()) != -1; ) {
      if (n == '\n') return decode(bytes.toByteArray());
      if (bytes.size() >= MAX_LINE) throw new IOException("MESSAGE_TOO_LONG");
      bytes.write(n);
    }
    if (bytes.size() != 0) throw new EOFException("TRUNCATED_MESSAGE");
    return null;
  }

  public static String decode(byte[] bytes) throws IOException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("INVALID_UTF8", e);
    }
  }

  public static void writeLine(OutputStream out, String line) throws IOException {
    fields(line);
    byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
    out.write(b);
    out.flush();
  }

  public static String[] fields(String line) {
    if (line == null
        || line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE
        || line.isEmpty()
        || line.chars().anyMatch(c -> c < 32 || c == 127))
      throw new IllegalArgumentException("INVALID_MESSAGE");
    String[] f = line.split("\\|", -1);
    if (Arrays.stream(f).anyMatch(String::isEmpty))
      throw new IllegalArgumentException("INVALID_MESSAGE");
    return f;
  }

  public static void count(String[] f, int n) {
    if (f.length != n) throw new IllegalArgumentException("INVALID_FIELDS");
  }

  public static long positive(String text) {
    if (!text.matches("[0-9]{1,19}")) throw new IllegalArgumentException("INVALID_NUMBER");
    long n = Long.parseLong(text);
    if (n <= 0) throw new IllegalArgumentException("INVALID_NUMBER");
    return n;
  }

  public static UUID uuid(String s) {
    UUID id = UUID.fromString(s);
    if (!id.toString().equals(s)) throw new IllegalArgumentException("INVALID_SESSION");
    return id;
  }

  public static double number(String s) {
    if (!s.matches("-?[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException("INVALID_NUMBER");
    double n = Double.parseDouble(s);
    if (!Double.isFinite(n)) throw new IllegalArgumentException("INVALID_NUMBER");
    return n;
  }

  public static int interval(String s) {
    long n = positive(s);
    if (n < 250 || n > 5000) throw new IllegalArgumentException("INVALID_INTERVAL");
    return (int) n;
  }

  public static long cursor(String s) {
    if (s.equals("0")) return 0;
    return positive(s);
  }

  public static int historyLimit(String s) {
    long n = positive(s);
    if (n > 50) throw new IllegalArgumentException("INVALID_HISTORY_LIMIT");
    return (int) n;
  }

  public static Request request(String line) {
    String[] f = fields(line);
    if (f.length < 3 || !f[0].equals("REQ")) throw new IllegalArgumentException("INVALID_REQUEST");
    long id = positive(f[1]);
    switch (f[2]) {
      case "START", "STOP", "GET_STATUS", "GET_ACTIVE_ALERTS" -> count(f, 3);
      case "GET_ALERTS" -> {
        count(f, 5);
        cursor(f[3]);
        historyLimit(f[4]);
      }
      case "SET_INTERVAL" -> {
        count(f, 4);
        interval(f[3]);
      }
      case "SET_THRESHOLD" -> {
        count(f, 5);
        Metric.valueOf(f[3]).validateThreshold(number(f[4]));
      }
      default -> throw new IllegalArgumentException("UNKNOWN_COMMAND");
    }
    return new Request(id, f[2], Arrays.asList(f).subList(3, f.length));
  }

  public static String exactDecimal(double n) {
    java.math.BigDecimal value = java.math.BigDecimal.valueOf(n);
    return value.setScale(Math.max(2, value.scale())).toPlainString();
  }

  public static String decimal(Double n) {
    return n == null ? "NA" : String.format(Locale.ROOT, "%.2f", n);
  }

  public static String telemetry(UUID id, long seq, SystemSnapshot s) {
    if (seq <= 0) throw new IllegalArgumentException("INVALID_SEQUENCE");
    return "METRIC|"
        + id
        + "|"
        + seq
        + "|"
        + s.timestampEpochMillis()
        + "|"
        + s.mode()
        + "|"
        + decimal(s.cpuPercent())
        + "|"
        + decimal(s.ramPercent())
        + "|"
        + decimal(s.temperatureCelsius());
  }

  public static Telemetry telemetry(String line) {
    if (line.getBytes(StandardCharsets.UTF_8).length > MAX_DATAGRAM)
      throw new IllegalArgumentException("DATAGRAM_TOO_LONG");
    String[] f = fields(line);
    count(f, 8);
    if (!f[0].equals("METRIC")) throw new IllegalArgumentException("INVALID_MESSAGE");
    return new Telemetry(
        uuid(f[1]),
        positive(f[2]),
        new SystemSnapshot(
            positive(f[3]), optional(f[5]), optional(f[6]), optional(f[7]), Mode.valueOf(f[4])));
  }

  private static Double optional(String s) {
    return s.equals("NA") ? null : number(s);
  }

  public static String errorCode(RuntimeException e) {
    String s = e.getMessage();
    return s != null && s.matches("[A-Z_]+") ? s : "INVALID_VALUE";
  }
}
