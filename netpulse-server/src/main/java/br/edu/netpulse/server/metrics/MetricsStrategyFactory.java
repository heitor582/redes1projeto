package br.edu.netpulse.server.metrics;

import br.edu.netpulse.common.Mode;
import java.util.*;
import java.util.function.Supplier;

public final class MetricsStrategyFactory {
  private MetricsStrategyFactory() {}

  public static String platform(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.contains("mac") || n.contains("darwin")) return "macOS";
    if (n.startsWith("windows")) return "Windows";
    if (n.contains("linux")) return "Linux";
    return "Unsupported";
  }

  public static MetricsStrategy create(
      Mode mode, Supplier<String> os, Supplier<OshiMetricsReader> reader, long seed) {
    if (mode == Mode.SIMULATED) return new SimulatedMetricsStrategy(seed);
    String platform = platform(os.get());
    if (platform.equals("Unsupported"))
      throw new IllegalArgumentException(
          "Sistema não suportado no modo real. Use --mode=simulated explicitamente.");
    return new OshiMetricsStrategy(reader.get(), platform);
  }
}
