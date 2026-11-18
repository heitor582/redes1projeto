package br.edu.netpulse.client.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public final class DashboardFrame extends JFrame {
  public DashboardFrame() {
    super("NetPulse — Monitoramento remoto");
    DashboardPanel panel = new DashboardPanel();
    setContentPane(panel);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    setMinimumSize(new Dimension(1080, 760));
    setSize(1240, 820);
    setLocationRelativeTo(null);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            panel.close();
            dispose();
          }
        });
  }
}
