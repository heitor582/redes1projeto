package br.edu.netpulse.client.ui;

import br.edu.netpulse.common.*;
import br.edu.netpulse.client.network.NetPulseConnection;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.table.DefaultTableModel;

final class AlertCenterPanel extends JPanel {
  private final Supplier<NetPulseConnection> connection;
  private final IntSupplier generation;
  private final Consumer<String> logger;
  private final DefaultTableModel activeModel = tableModel(), historyModel = tableModel();
  private final JButton refresh = Theme.button("Atualizar", false),
      older = Theme.button("Mais antigos", false);
  private final JLabel summary = Theme.label("Alertas globais do servidor", 11, Theme.MUTED);
  private final JTabbedPane tabs = new JTabbedPane();
  private final Timer refreshTimer = new Timer(250, e -> refresh(false));
  private long oldestId;
  private boolean loading, dirty, connected;

  AlertCenterPanel(
      Supplier<NetPulseConnection> connection,
      IntSupplier generation,
      Consumer<String> logger,
      Supplier<JPanel> activity) {
    super(new BorderLayout(0, 10));
    this.connection = connection;
    this.generation = generation;
    this.logger = logger;
    setOpaque(false);
    setBorder(new EmptyBorder(12, 14, 12, 14));
    setPreferredSize(new Dimension(700, 245));
    tabs.setFont(Theme.font(12, Font.PLAIN));
    tabs.setBackground(Theme.SURFACE);
    tabs.setForeground(Theme.TEXT);
    tabs.setUI(tabUi());
    tabs.addTab("Alertas ativos", table(activeModel));
    tabs.addTab("Histórico do servidor", table(historyModel));
    tabs.addTab("Atividade local", activity.get());
    JPanel toolbar = Theme.panel(new BorderLayout());
    toolbar.add(summary, BorderLayout.WEST);
    JPanel actions = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    actions.add(refresh);
    actions.add(older);
    toolbar.add(actions, BorderLayout.EAST);
    add(toolbar, BorderLayout.NORTH);
    add(tabs, BorderLayout.CENTER);
    refreshTimer.setRepeats(false);
    refresh.addActionListener(e -> refresh(false));
    older.addActionListener(
        e -> {
          tabs.setSelectedIndex(1);
          refresh(true);
        });
    setConnected(false);
  }

  private static DefaultTableModel tableModel() {
    return new DefaultTableModel(
        new String[] {"Data / hora", "Evento", "Métrica", "Valor", "Limite", "Origem"}, 0) {
      @Override
      public boolean isCellEditable(int r, int c) {
        return false;
      }
    };
  }

  private BasicTabbedPaneUI tabUi() {
    return new BasicTabbedPaneUI() {
      @Override
      protected void installDefaults() {
        super.installDefaults();
        highlight = Theme.BORDER;
        lightHighlight = Theme.BORDER;
        shadow = Theme.BORDER;
        darkShadow = Theme.BORDER;
        focus = Theme.BLUE;
      }

      @Override
      protected void paintTabBackground(
          Graphics g, int p, int i, int x, int y, int w, int h, boolean selected) {
        g.setColor(selected ? Theme.BLUE_TINT : Theme.BACKGROUND);
        g.fillRect(x, y, w, h);
      }

      @Override
      protected void paintText(
          Graphics g,
          int p,
          Font f,
          FontMetrics m,
          int i,
          String text,
          Rectangle r,
          boolean selected) {
        g.setFont(f);
        g.setColor(selected ? Theme.TEXT : Theme.MUTED);
        g.drawString(text, r.x, r.y + m.getAscent());
      }
    };
  }

  private JScrollPane table(DefaultTableModel model) {
    JTable t = new JTable(model);
    t.setBackground(Theme.SURFACE);
    t.setForeground(Theme.TEXT);
    t.setFont(Theme.font(11, Font.PLAIN));
    t.setRowHeight(26);
    t.setGridColor(Theme.BORDER);
    t.setShowVerticalLines(false);
    t.setSelectionBackground(Theme.BLUE_TINT);
    t.setSelectionForeground(Theme.TEXT);
    t.setFillsViewportHeight(true);
    t.getTableHeader()
        .setDefaultRenderer(
            (list, value, selected, focus, row, column) -> {
              JLabel l = Theme.label(String.valueOf(value), 11, Theme.MUTED);
              l.setOpaque(true);
              l.setBackground(Theme.SURFACE);
              l.setBorder(new EmptyBorder(6, 5, 6, 5));
              return l;
            });
    t.getColumnModel().getColumn(0).setPreferredWidth(135);
    JScrollPane scroll = new JScrollPane(t);
    scroll.setBorder(null);
    scroll.getViewport().setBackground(Theme.SURFACE);
    return scroll;
  }

  private Object[] row(AlertRecord r) {
    return new Object[] {
      Instant.ofEpochMilli(r.timestamp())
          .atZone(ZoneId.systemDefault())
          .format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")),
      r.type().equals("ALERT") ? "ALERTA" : "RECUPERAÇÃO",
      r.metric(),
      Protocol.decimal(r.value()) + (r.metric() == Metric.TEMP ? " °C" : " %"),
      Protocol.decimal(r.threshold()),
      r.mode() == Mode.REAL ? "Real" : "Simulação"
    };
  }

  void setConnected(boolean value) {
    connected = value;
    refresh.setEnabled(value && !loading);
    older.setEnabled(value && !loading && oldestId > 0 && historyModel.getRowCount() < 1000);
  }

  void refresh() {
    refresh(false);
  }

  private void refresh(boolean olderPage) {
    NetPulseConnection current = connection.get();
    if (!connected || current == null || current.isClosed()) return;
    if (loading) {
      if (!olderPage) dirty = true;
      return;
    }
    int token = generation.getAsInt();
    loading = true;
    setConnected(true);
    current
        .activeAlerts()
        .thenCombine(
            current.alertHistory(olderPage ? oldestId : 0, 50),
            (active, recent) -> List.of(active, recent))
        .whenComplete(
            (lists, error) ->
                SwingUtilities.invokeLater(
                    () -> {
                      if (token != generation.getAsInt()) return;
                      loading = false;
                      if (dirty) {
                        dirty = false;
                        refreshTimer.restart();
                      }
                      if (error != null) {
                        logger.accept(
                            "Não foi possível consultar os alertas: " + error.getMessage());
                        setConnected(!current.isClosed());
                        return;
                      }
                      activeModel.setRowCount(0);
                      for (AlertRecord r : lists.get(0)) activeModel.addRow(row(r));
                      if (!olderPage) historyModel.setRowCount(0);
                      for (AlertRecord r : lists.get(1)) {
                        if (historyModel.getRowCount() >= 1000) break;
                        historyModel.addRow(row(r));
                      }
                      oldestId = lists.get(1).size() == 50 ? lists.get(1).get(49).id() : 0;
                      summary.setText(
                          lists.get(0).size()
                              + " ativo(s) · "
                              + historyModel.getRowCount()
                              + " evento(s) carregado(s)");
                      setConnected(!current.isClosed());
                    }));
  }

  void onServerEvent() {
    if (connected) refreshTimer.restart();
  }

  void clear() {
    refreshTimer.stop();
    loading = false;
    dirty = false;
    oldestId = 0;
    activeModel.setRowCount(0);
    historyModel.setRowCount(0);
    summary.setText("Alertas globais do servidor");
  }
}
