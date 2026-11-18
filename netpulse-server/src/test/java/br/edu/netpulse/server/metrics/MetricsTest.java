package br.edu.netpulse.server.metrics;

import static org.junit.jupiter.api.Assertions.*;

import br.edu.netpulse.common.*;
import org.junit.jupiter.api.*;

class MetricsTest {
  static class FakeHardware implements OshiMetricsReader.Hardware {
    double temperature = 0, load = .25;
    long total = 1000, available = 400;
    boolean fail;

    public long[] ticks() {
      return new long[8];
    }

    public double cpuBetween(long[] old) {
      return load;
    }

    public long totalMemory() {
      return total;
    }

    public long availableMemory() {
      return available;
    }

    public double temperature() {
      if (fail) throw new IllegalStateException("sensor ausente");
      return temperature;
    }
  }

  @Test
  void factorySelectsPlatformsAndSimulation() {
    String[] names = {"Windows 11", "Linux", "Mac OS X", "Darwin"};
    String[] platforms = {"Windows", "Linux", "macOS", "macOS"};
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      MetricsStrategy strategy =
          MetricsStrategyFactory.create(
              Mode.REAL, () -> name, () -> new OshiMetricsReader(new FakeHardware()), 42);
      assertInstanceOf(OshiMetricsStrategy.class, strategy);
      assertTrue(strategy.sourceDescription().startsWith(platforms[i] + "; OSHI/JNA;"));
    }
    assertInstanceOf(
        SimulatedMetricsStrategy.class,
        MetricsStrategyFactory.create(
            Mode.SIMULATED,
            () -> "Plan9",
            () -> {
              throw new AssertionError();
            },
            42));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricsStrategyFactory.create(
                Mode.REAL,
                () -> "Plan9",
                () -> {
                  throw new AssertionError();
                },
                42));
  }

  @Test
  void firstCpuUnavailableThenMachineLoad() {
    FakeHardware h = new FakeHardware();
    OshiMetricsReader r = new OshiMetricsReader(h);
    assertNull(r.sample().cpuPercent());
    SystemSnapshot s = r.sample();
    assertEquals(25, s.cpuPercent());
    assertEquals(60, s.ramPercent());
    assertNull(s.temperatureCelsius());
  }

  @Test
  void badSensorsAndMemoryDoNotInventValues() {
    FakeHardware h = new FakeHardware();
    OshiMetricsReader r = new OshiMetricsReader(h);
    r.sample();
    for (double t : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 200}) {
      h.temperature = t;
      assertNull(r.sample().temperatureCelsius());
    }
    h.fail = true;
    assertNull(r.sample().temperatureCelsius());
    h.fail = false;
    h.temperature = 48;
    assertEquals(48, r.sample().temperatureCelsius());
    h.total = 0;
    assertNull(r.sample().ramPercent());
    h.total = 100;
    h.available = 101;
    assertNull(r.sample().ramPercent());
    h.load = Double.NaN;
    assertNull(r.sample().cpuPercent());
  }

  @Test
  void simulationDeterministicBoundedGradual() {
    MetricsStrategy a = new SimulatedMetricsStrategy(42), b = new SimulatedMetricsStrategy(42);
    SystemSnapshot old = null;
    for (int i = 0; i < 2000; i++) {
      SystemSnapshot x = a.sample(), y = b.sample();
      assertEquals(x.cpuPercent(), y.cpuPercent());
      assertEquals(x.ramPercent(), y.ramPercent());
      assertEquals(x.temperatureCelsius(), y.temperatureCelsius());
      assertEquals(Mode.SIMULATED, x.mode());
      assertTrue(x.temperatureCelsius() >= 20 && x.temperatureCelsius() <= 100);
      if (old != null) {
        assertTrue(Math.abs(x.cpuPercent() - old.cpuPercent()) <= 5.00001);
        assertTrue(Math.abs(x.ramPercent() - old.ramPercent()) <= 2.00001);
        assertTrue(Math.abs(x.temperatureCelsius() - old.temperatureCelsius()) <= 1.50001);
      }
      old = x;
    }
  }
}
