package br.edu.netpulse.server.metrics;

import br.edu.netpulse.common.*;
import java.util.Random;

public final class SimulatedMetricsStrategy implements MetricsStrategy {
  private final Random random;
  private double cpu = 45, ram = 55, temp = 50;

  public SimulatedMetricsStrategy(long seed) {
    random = new Random(seed);
  }

  private double step(double n, double delta, double min, double max) {
    return Math.max(min, Math.min(max, n + (random.nextDouble() * 2 - 1) * delta));
  }

  public synchronized SystemSnapshot sample() {
    cpu = step(cpu, 5, 0, 100);
    ram = step(ram, 2, 0, 100);
    temp = step(temp, 1.5, 20, 100);
    return new SystemSnapshot(System.currentTimeMillis(), cpu, ram, temp, Mode.SIMULATED);
  }

  public String sourceDescription() {
    return "SIMULATED; passeio aleatório, seed explícita";
  }
}
