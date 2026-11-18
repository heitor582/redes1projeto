package br.edu.netpulse.client.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.*;

final class Theme {
  static final Color BACKGROUND = new Color(13, 18, 28),
      SURFACE = new Color(21, 29, 42),
      TEXT = new Color(231, 237, 246),
      MUTED = new Color(147, 162, 183),
      BORDER = new Color(42, 55, 73),
      BLUE = new Color(107, 155, 255),
      GREEN = new Color(67, 210, 173),
      AMBER = new Color(245, 185, 95),
      BLUE_TINT = new Color(30, 49, 79);

  static Font font(int size, int style) {
    return new Font(Font.SANS_SERIF, style, size);
  }

  static JLabel label(String text, int size, Color color) {
    JLabel label = new JLabel(text);
    label.setFont(font(size, size >= 16 ? Font.BOLD : Font.PLAIN));
    label.setForeground(color);
    label.setAlignmentX(Component.LEFT_ALIGNMENT);
    return label;
  }

  static JPanel panel(LayoutManager layout) {
    JPanel p = new JPanel(layout);
    p.setOpaque(false);
    return p;
  }

  static JPanel vertical() {
    JPanel p = panel(null);
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    return p;
  }

  static JButton button(String text, boolean primary) {
    JButton b =
        new JButton(text) {
          @Override
          protected void paintComponent(Graphics g) {
            Graphics2D c = (Graphics2D) g.create();
            c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = primary ? BLUE : new Color(32, 44, 61);
            c.setColor(!isEnabled() ? SURFACE : getModel().isRollover() ? base.brighter() : base);
            c.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            if (isFocusOwner()) {
              c.setColor(TEXT);
              c.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 12, 12);
            }
            c.dispose();
            super.paintComponent(g);
          }
        };
    b.setUI(
        new BasicButtonUI() {
          @Override
          protected void paintText(Graphics g, AbstractButton button, Rectangle rect, String text) {
            g.setColor(button.isEnabled() ? button.getForeground() : MUTED);
            g.setFont(button.getFont());
            g.drawString(text, rect.x, rect.y + g.getFontMetrics().getAscent());
          }
        });
    b.setContentAreaFilled(false);
    b.setOpaque(false);
    b.setBorder(new EmptyBorder(9, 10, 9, 10));
    b.setFont(font(12, Font.BOLD));
    b.setForeground(primary ? BACKGROUND : TEXT);
    b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    return b;
  }

  static void styleField(JTextField f) {
    f.setUI(new BasicTextFieldUI());
    f.setBackground(SURFACE);
    f.setForeground(TEXT);
    f.setCaretColor(BLUE);
    f.setFont(font(13, Font.PLAIN));
    f.setBorder(new CompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(7, 9, 7, 9)));
  }

  static void styleSpinner(JSpinner s) {
    s.setUI(new BasicSpinnerUI());
    s.setBorder(new LineBorder(BORDER, 1, true));
    s.setBackground(SURFACE);
    JFormattedTextField f = ((JSpinner.DefaultEditor) s.getEditor()).getTextField();
    styleField(f);
    f.setBorder(new EmptyBorder(6, 6, 6, 6));
    for (Component c : s.getComponents())
      if (c instanceof JButton b) {
        b.setBackground(BORDER);
        b.setForeground(TEXT);
      }
  }

  static JProgressBar bar(Color accent) {
    JProgressBar p = new JProgressBar(0, 100);
    p.setUI(new BasicProgressBarUI());
    p.setForeground(accent);
    p.setBackground(BORDER);
    p.setBorderPainted(false);
    p.setPreferredSize(new Dimension(200, 4));
    p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
    return p;
  }

  static final class Card extends JPanel {
    Card(LayoutManager layout) {
      super(layout);
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D c = (Graphics2D) g.create();
      c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      c.setColor(SURFACE);
      c.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
      c.setColor(BORDER);
      c.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
      c.dispose();
      super.paintComponent(g);
    }
  }
}
