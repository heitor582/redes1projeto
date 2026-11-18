package br.edu.netpulse.client;

import br.edu.netpulse.client.ui.DashboardFrame;
import java.awt.GraphicsEnvironment;
import javax.swing.*;

public final class ClientMain {
  public static void main(String[] args) {
    if (args.length == 1 && args[0].equals("--help")) {
      System.out.println(
          "NetPulse Swing: java -jar netpulse-client-1.0.0.jar. Configure hostname e portas na"
              + " interface.");
      return;
    }
    if (GraphicsEnvironment.isHeadless()) {
      System.err.println("NetPulse requer ambiente gráfico para abrir o cliente Swing.");
      return;
    }
    SwingUtilities.invokeLater(() -> new DashboardFrame().setVisible(true));
  }
}
