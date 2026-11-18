package br.edu.netpulse.server.metrics;

import br.edu.netpulse.common.SystemSnapshot;

public interface MetricsStrategy {
  SystemSnapshot sample();

  String sourceDescription();
}
