package br.edu.netpulse.server.alerts;

import static org.junit.jupiter.api.Assertions.*;

import br.edu.netpulse.common.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AlertHistoryTest {
  @TempDir Path directory;

  private SystemSnapshot sample() {
    return new SystemSnapshot(123, 90.0, 45.0, null, Mode.REAL);
  }

  @Test
  void savesRulesAndEventsAcrossRestart() throws Exception {
    Path file = directory.resolve("alerts.log");
    UUID source = UUID.randomUUID();
    AlertRecord record;
    try (AlertHistory history = new AlertHistory(file)) {
      history.setThreshold(Metric.RAM, 70);
      record = history.append(source, sample(), "ALERT|123|CPU|90|80", 80);
    }
    try (AlertHistory restored = new AlertHistory(file)) {
      assertEquals(70, restored.thresholds().get(Metric.RAM));
      assertEquals(List.of(record), restored.page(0, 50));
      AlertRecord next = restored.append(source, sample(), "RECOVERY|124|CPU|70", 80);
      assertEquals(record.id() + 1, next.id());
    }
    String compacted = Files.readString(file);
    assertTrue(compacted.startsWith("RULES|80.00|70.00|75.00\n"));
    assertTrue(compacted.contains("\n2|124|"));
    try (AlertHistory restoredAgain = new AlertHistory(file)) {
      assertEquals(2, restoredAgain.page(0, 50).size());
      assertEquals("RECOVERY", restoredAgain.page(0, 50).get(0).type());
    }
  }

  @Test
  void boundedRetentionAndStablePagination() {
    try (AlertHistory history = new AlertHistory()) {
      UUID source = UUID.randomUUID();
      for (int i = 0; i < 1100; i++) history.append(source, sample(), "ALERT|123|CPU|90|80", 80);
      assertEquals(1000, history.size());
      List<AlertRecord> first = history.page(0, 50);
      assertEquals(1100, first.get(0).id());
      List<AlertRecord> second = history.page(first.get(49).id(), 50);
      assertEquals(1050, second.get(0).id());
      assertThrows(UnsupportedOperationException.class, () -> first.clear());
      assertThrows(IllegalArgumentException.class, () -> history.page(0, 51));
    }
  }

  @Test
  void compactsJournalAtTheBoundedThreshold() throws Exception {
    Path file = directory.resolve("journal.log");
    UUID source = UUID.randomUUID();
    try (AlertHistory history = new AlertHistory(file)) {
      for (int i = 0; i < 1251; i++)
        history.append(
            source,
            new SystemSnapshot(1000L + i, 90.0, 45.0, null, Mode.REAL),
            "ALERT|" + (1000L + i) + "|CPU|90|80",
            80);
    }
    List<String> lines = Files.readAllLines(file);
    assertEquals(1001, lines.size()); // one rules row and the 1000 retained events
    try (AlertHistory restored = new AlertHistory(file)) {
      assertEquals(1000, restored.size());
      assertEquals(1251, restored.page(0, 1).get(0).id());
      assertEquals(252, Long.parseLong(lines.get(1).split("\\|")[0]));
    }
  }

  @Test
  void refusesCorruptedHistoryInsteadOfOverwritingIt() throws Exception {
    Path file = directory.resolve("alerts.log");
    Files.writeString(file, "invalid\n");
    assertThrows(java.io.IOException.class, () -> new AlertHistory(file));
    assertEquals("invalid\n", Files.readString(file));
  }
}
