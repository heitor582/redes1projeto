package br.edu.netpulse.common;

public enum Metric {
  CPU(0, 100, 80),
  RAM(0, 100, 85),
  TEMP(20, 100, 75);
  public final double minimum, maximum, defaultThreshold;

  Metric(double min, double max, double threshold) {
    minimum = min;
    maximum = max;
    defaultThreshold = threshold;
  }

  public double validateThreshold(double v) {
    if (!Double.isFinite(v) || v < minimum || v > maximum)
      throw new IllegalArgumentException("INVALID_THRESHOLD");
    return v;
  }
}
