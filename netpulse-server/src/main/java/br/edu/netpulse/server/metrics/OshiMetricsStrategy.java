package br.edu.netpulse.server.metrics;

import br.edu.netpulse.common.SystemSnapshot;

public final class OshiMetricsStrategy implements MetricsStrategy {
  private final OshiMetricsReader reader;
  private final String platform;

  public OshiMetricsStrategy(OshiMetricsReader reader, String platform) {
    this.reader = reader;
    this.platform = platform;
  }

  @Override
  public SystemSnapshot sample() {
    return reader.sample();
  }

  @Override
  public String sourceDescription() {
    String sensorNotes =
        switch (platform) {
          case "Windows" -> "temperatura depende dos sensores/drivers disponíveis";
          case "Linux" -> "temperatura depende de hwmon/thermal e permissões";
          case "macOS" -> "temperatura depende do hardware e acesso aos sensores";
          default -> "temperatura depende dos sensores disponíveis";
        };
    return platform + "; OSHI/JNA; " + sensorNotes;
  }
}
