package br.edu.netpulse.server.metrics;

import br.edu.netpulse.common.*;
import java.util.function.Supplier;
import oshi.SystemInfo;
import oshi.hardware.*;

/** Hardware adapter also permits deterministic collector tests without native calls. */
public final class OshiMetricsReader {
  public interface Hardware {
    long[] ticks();

    double cpuBetween(long[] oldTicks);

    long totalMemory();

    long availableMemory();

    double temperature();
  }

  private final Hardware hardware;
  private long[] previous;

  public OshiMetricsReader(Hardware hardware) {
    this.hardware = hardware;
  }

  public static OshiMetricsReader nativeReader() {
    HardwareAbstractionLayer hal = new SystemInfo().getHardware();
    return new OshiMetricsReader(
        new Hardware() {
          public long[] ticks() {
            return hal.getProcessor().getSystemCpuLoadTicks();
          }

          public double cpuBetween(long[] old) {
            return hal.getProcessor().getSystemCpuLoadBetweenTicks(old);
          }

          public long totalMemory() {
            return hal.getMemory().getTotal();
          }

          public long availableMemory() {
            return hal.getMemory().getAvailable();
          }

          public double temperature() {
            return hal.getSensors().getCpuTemperature();
          }
        });
  }

  private static Double safe(Supplier<Double> read) {
    try {
      return read.get();
    } catch (RuntimeException | LinkageError e) {
      System.getLogger(OshiMetricsReader.class.getName())
          .log(System.Logger.Level.DEBUG, "Métrica indisponível", e);
      return null;
    }
  }

  public synchronized SystemSnapshot sample() {
    Double cpu =
        safe(
            () -> {
              long[] current = hardware.ticks();
              Double v = previous == null ? null : hardware.cpuBetween(previous) * 100;
              previous = current.clone();
              return valid(v, 0, 100);
            });
    Double ram =
        safe(
            () -> {
              long total = hardware.totalMemory(), available = hardware.availableMemory();
              return total <= 0 || available < 0 || available > total
                  ? null
                  : 100.0 * (total - available) / total;
            });
    Double temp = safe(() -> valid(hardware.temperature(), 0.01, 150));
    return new SystemSnapshot(System.currentTimeMillis(), cpu, ram, temp, Mode.REAL);
  }

  private static Double valid(Double v, double min, double max) {
    return v != null && Double.isFinite(v) && v >= min && v <= max ? v : null;
  }
}
