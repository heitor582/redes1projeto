package br.edu.netpulse.client.ui;

import java.awt.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** Bounded EDT-owned series. Missing samples remain gaps. */
public final class HistoryChart extends JPanel {
  private final List<Double> points = new ArrayList<>(60);
  private final String unit;
  private final double maximum;
  private final Color color;

  public HistoryChart(String title, String unit, double maximum, Color color) {
    this.unit = unit;
    this.maximum = maximum;
    this.color = color;
    setPreferredSize(new Dimension(230, 240));
    setOpaque(false);
    getAccessibleContext().setAccessibleName("Histórico de " + title);
  }

  public void add(Double value) {
    if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("EDT required");
    if (points.size() == 60) points.remove(0);
    points.add(value);
    repaint();
  }

  public void clear() {
    points.clear();
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D c = (Graphics2D) g.create();
    c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    c.setFont(Theme.font(10, Font.PLAIN));
    int left = 28, top = 22, w = getWidth() - 34, h = getHeight() - 50;
    if (w <= 0 || h <= 0) {
      c.dispose();
      return;
    }
    for (int i = 0; i <= 4; i++) {
      int y = top + h * i / 4;
      c.setColor(Theme.BORDER);
      c.drawLine(left, y, left + w, y);
      c.setColor(Theme.MUTED);
      c.drawString(String.format(java.util.Locale.ROOT, "%.0f", maximum * (4 - i) / 4), 0, y + 4);
    }
    c.drawString(unit, 0, 12);
    c.drawString("60 leituras", left, top + h + 23);
    String now = "agora";
    c.drawString(now, left + w - c.getFontMetrics().stringWidth(now), top + h + 23);
    // Right-align the series so the newest reading always occupies the 'agora' edge.
    int offset = 60 - points.size();
    for (int from = 0; from < points.size(); ) {
      if (points.get(from) == null) {
        from++;
        continue;
      }
      int to = from;
      while (to + 1 < points.size() && points.get(to + 1) != null) to++;
      Path2D line = new Path2D.Double();
      for (int i = from; i <= to; i++) {
        double x = left + (offset + i) * w / 59.0, y = top + h - points.get(i) / maximum * h;
        if (i == from) line.moveTo(x, y);
        else line.lineTo(x, y);
      }
      Path2D area = (Path2D) line.clone();
      area.lineTo(left + (offset + to) * w / 59.0, top + h);
      area.lineTo(left + (offset + from) * w / 59.0, top + h);
      area.closePath();
      c.setPaint(
          new GradientPaint(
              0,
              top,
              new Color(color.getRed(), color.getGreen(), color.getBlue(), 60),
              0,
              top + h,
              new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)));
      c.fill(area);
      c.setColor(color);
      c.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      c.draw(line);
      int x = (int) (left + (offset + to) * w / 59.0),
          y = (int) (top + h - points.get(to) / maximum * h);
      c.fillOval(x - 3, y - 3, 6, 6);
      from = to + 1;
    }
    if (points.stream().noneMatch(java.util.Objects::nonNull)) {
      String empty = points.isEmpty() ? "Aguardando dados" : "Sem leitura disponível";
      int textWidth = c.getFontMetrics().stringWidth(empty);
      c.setColor(Theme.SURFACE);
      c.fillRoundRect(
          left + (w - textWidth) / 2 - 10, top + h / 2 - 15, textWidth + 20, 30, 10, 10);
      c.setColor(Theme.MUTED);
      c.drawString(empty, left + (w - textWidth) / 2, top + h / 2 + 4);
    }
    c.dispose();
  }
}
