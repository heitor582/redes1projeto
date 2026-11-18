package br.edu.netpulse.client.ui;

import br.edu.netpulse.client.network.NetPulseConnection;
import br.edu.netpulse.client.network.TelemetryTracker;
import br.edu.netpulse.common.*;
import java.awt.*;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Swing view and controller; can be rendered without creating a native window. */
public final class DashboardPanel extends JPanel {
  private static final String[] METRIC_NOTES = {
    "Utilização total do processador", "Memória física utilizada", "Temperatura do processador"
  };
  private final JTextField
      host = new JTextField(System.getProperty("netpulse.host", "localhost"), 12),
      tcpPort = new JTextField(System.getProperty("netpulse.tcpPort", "5000"), 4),
      udpPort = new JTextField(System.getProperty("netpulse.udpPort", "5000"), 4);
  private final JButton connect = Theme.button("Conectar", true),
      disconnect = Theme.button("Desconectar", false),
      start = Theme.button("Iniciar", true),
      stop = Theme.button("Parar", false),
      applyInterval = Theme.button("Aplicar", false),
      applyThresholds = Theme.button("Aplicar limites", false);
  private final JSpinner interval = new JSpinner(new SpinnerNumberModel(1000, 250, 5000, 250));
  private final JSpinner[] thresholds = {
    new JSpinner(new SpinnerNumberModel(80.0, 0.0, 100.0, 0.5)),
    new JSpinner(new SpinnerNumberModel(85.0, 0.0, 100.0, 0.5)),
    new JSpinner(new SpinnerNumberModel(75.0, 20.0, 100.0, 0.5))
  };
  private final JLabel tcpState = Theme.label("TCP: Desconectado", 12, Theme.MUTED),
      udpState = Theme.label("UDP: Não registrado", 12, Theme.MUTED),
      monitorState = Theme.label("Parado", 12, Theme.MUTED),
      mode = Theme.label("DESCONECTADO", 11, Theme.BLUE),
      hint = Theme.label("Informe o IP do servidor e conecte-se para começar.", 13, Theme.MUTED),
      updated = Theme.label("Nenhuma leitura recebida", 12, Theme.MUTED);
  private final JLabel[] values = {
    Theme.label("—", 44, Theme.TEXT),
    Theme.label("—", 44, Theme.TEXT),
    Theme.label("—", 44, Theme.TEXT)
  };
  private final JLabel[] metricNotes = {
    Theme.label("Aguardando leitura do servidor", 12, Theme.MUTED),
    Theme.label("Aguardando leitura do servidor", 12, Theme.MUTED),
    Theme.label("Aguardando leitura do servidor", 12, Theme.MUTED)
  };
  private final JProgressBar[] bars = {Theme.bar(Theme.BLUE), Theme.bar(Theme.GREEN)};
  private final HistoryChart[] charts = {
    new HistoryChart("CPU", "%", 100, Theme.BLUE),
    new HistoryChart("RAM", "%", 100, Theme.GREEN),
    new HistoryChart("Temperatura", "°C", 150, Theme.AMBER)
  };
  private final DefaultListModel<String> events = new DefaultListModel<>();
  private final ExecutorService connector = Executors.newSingleThreadExecutor();
  private volatile NetPulseConnection connection;
  private int generation;
  private final AlertCenterPanel alertCenter;

  public DashboardPanel() {
    super(new BorderLayout(24, 0));
    setBackground(Theme.BACKGROUND);
    setBorder(new EmptyBorder(24, 24, 24, 24));
    alertCenter =
        new AlertCenterPanel(() -> connection, () -> generation, this::log, this::activity);
    JScrollPane sidebarScroll = new JScrollPane(sidebar());
    sidebarScroll.setBorder(null);
    sidebarScroll.setOpaque(false);
    sidebarScroll.getViewport().setOpaque(false);
    sidebarScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    sidebarScroll.setPreferredSize(new Dimension(242, 650));
    sidebarScroll.getVerticalScrollBar().setUnitIncrement(16);
    add(sidebarScroll, BorderLayout.WEST);
    JPanel main = Theme.panel(new BorderLayout(0, 20));
    JPanel header = Theme.panel(new BorderLayout());
    JPanel heading = Theme.vertical();
    heading.add(Theme.label("Visão geral", 28, Theme.TEXT));
    heading.add(Box.createVerticalStrut(6));
    heading.add(hint);
    header.add(heading, BorderLayout.CENTER);
    mode.setBorder(new EmptyBorder(8, 14, 8, 14));
    mode.setOpaque(true);
    mode.setBackground(Theme.BLUE_TINT);
    header.add(mode, BorderLayout.EAST);
    main.add(header, BorderLayout.NORTH);
    JPanel content = Theme.panel(new BorderLayout(0, 18));
    JPanel metrics = Theme.panel(new GridLayout(1, 3, 16, 0));
    for (int i = 0; i < 3; i++) metrics.add(metricCard(i));
    content.add(metrics, BorderLayout.CENTER);
    content.add(alertCenter, BorderLayout.SOUTH);
    main.add(content, BorderLayout.CENTER);
    JPanel footer = Theme.panel(new BorderLayout());
    footer.add(updated, BorderLayout.WEST);
    footer.add(
        Theme.label("NETPULSE  /  MONITORAMENTO REMOTO", 10, Theme.MUTED), BorderLayout.EAST);
    main.add(footer, BorderLayout.SOUTH);
    add(main, BorderLayout.CENTER);
    connect.addActionListener(e -> connect());
    disconnect.addActionListener(e -> disconnect());
    start.addActionListener(e -> send("START"));
    stop.addActionListener(e -> send("STOP"));
    applyInterval.addActionListener(
        e -> {
          if (commit(interval)) send("SET_INTERVAL|" + interval.getValue());
        });
    applyThresholds.addActionListener(
        e -> {
          for (JSpinner spinner : thresholds) if (!commit(spinner)) return;
          for (int i = 0; i < 3; i++)
            send("SET_THRESHOLD|" + Metric.values()[i] + "|" + thresholds[i].getValue());
        });
    controls(false);
  }

  private boolean commit(JSpinner spinner) {
    try {
      spinner.commitEdit();
      return true;
    } catch (java.text.ParseException e) {
      log("Valor inválido. Confira o intervalo ou limite informado.");
      return false;
    }
  }

  private JPanel sidebar() {
    JPanel sidebar = Theme.vertical();
    sidebar.setPreferredSize(new Dimension(232, 752));
    sidebar.add(Theme.label("netpulse", 29, Theme.TEXT));
    sidebar.add(Box.createVerticalStrut(3));
    sidebar.add(Theme.label("Seu servidor, em tempo real.", 12, Theme.MUTED));
    sidebar.add(Box.createVerticalStrut(28));
    sidebar.add(Theme.label("CONEXÃO", 10, Theme.MUTED));
    sidebar.add(Box.createVerticalStrut(12));
    sidebar.add(field("IP ou nome do servidor", host));
    sidebar.add(Box.createVerticalStrut(10));
    JPanel ports = Theme.panel(new GridLayout(1, 2, 10, 0));
    ports.add(field("Porta TCP", tcpPort));
    ports.add(field("Porta UDP", udpPort));
    ports.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
    sidebar.add(ports);
    sidebar.add(Box.createVerticalStrut(7));
    sidebar.add(Theme.label("Outro computador? Use o IP dele.", 11, Theme.MUTED));
    sidebar.add(Theme.label("localhost é apenas para teste local.", 11, Theme.MUTED));
    sidebar.add(Box.createVerticalStrut(14));
    sidebar.add(buttonRow(connect, disconnect));
    sidebar.add(Box.createVerticalStrut(16));
    sidebar.add(tcpState);
    sidebar.add(Box.createVerticalStrut(6));
    sidebar.add(udpState);
    sidebar.add(Box.createVerticalStrut(26));
    sidebar.add(Theme.label("MONITORAMENTO", 10, Theme.MUTED));
    sidebar.add(Box.createVerticalStrut(12));
    sidebar.add(buttonRow(start, stop));
    sidebar.add(Box.createVerticalStrut(10));
    sidebar.add(monitorState);
    sidebar.add(Box.createVerticalStrut(15));
    Theme.styleSpinner(interval);
    sidebar.add(field("Intervalo de envio · ms", interval));
    sidebar.add(Box.createVerticalStrut(8));
    sidebar.add(buttonRow(applyInterval));
    sidebar.add(Box.createVerticalStrut(24));
    sidebar.add(Theme.label("LIMITES GLOBAIS DO SERVIDOR", 10, Theme.MUTED));
    sidebar.add(Box.createVerticalStrut(12));
    JPanel limits = Theme.panel(new GridLayout(1, 3, 8, 0));
    String[] names = {"CPU %", "RAM %", "Temp. °C"};
    for (int i = 0; i < 3; i++) {
      Theme.styleSpinner(thresholds[i]);
      limits.add(field(names[i], thresholds[i]));
    }
    limits.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
    sidebar.add(limits);
    sidebar.add(Box.createVerticalStrut(10));
    sidebar.add(buttonRow(applyThresholds));
    sidebar.add(Box.createVerticalGlue());
    for (Component c : sidebar.getComponents())
      if (c instanceof JComponent component) component.setAlignmentX(Component.LEFT_ALIGNMENT);
    return sidebar;
  }

  private JPanel field(String text, JComponent input) {
    JPanel p = Theme.vertical();
    p.add(Theme.label(text, 12, Theme.MUTED));
    p.add(Box.createVerticalStrut(7));
    if (input instanceof JTextField f) Theme.styleField(f);
    input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
    input.setPreferredSize(new Dimension(85, 36));
    input.setAlignmentX(Component.LEFT_ALIGNMENT);
    p.add(input);
    p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
    return p;
  }

  private JPanel buttonRow(JButton... buttons) {
    JPanel p = Theme.panel(new GridLayout(1, buttons.length, 8, 0));
    for (JButton b : buttons) p.add(b);
    p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
    p.setPreferredSize(new Dimension(230, 36));
    return p;
  }

  private JPanel metricCard(int i) {
    JPanel card = new Theme.Card(new BorderLayout(0, 18));
    card.setBorder(new EmptyBorder(20, 18, 18, 18));
    JPanel top = Theme.vertical();
    String[] names = {"PROCESSADOR", "MEMÓRIA RAM", "TEMPERATURA"};
    Color[] colors = {Theme.BLUE, Theme.GREEN, Theme.AMBER};
    top.add(Theme.label(names[i], 11, colors[i]));
    top.add(Box.createVerticalStrut(17));
    JPanel number = Theme.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    number.add(values[i]);
    JLabel unit = Theme.label(i == 2 ? "  °C" : "  %", 18, Theme.MUTED);
    number.add(unit);
    number.setAlignmentX(Component.LEFT_ALIGNMENT);
    top.add(number);
    top.add(Box.createVerticalStrut(8));
    top.add(metricNotes[i]);
    top.add(Box.createVerticalStrut(16));
    if (i < 2) {
      bars[i].setAlignmentX(Component.LEFT_ALIGNMENT);
      top.add(bars[i]);
    } else {
      JLabel info = Theme.label("Depende do sensor do servidor", 11, Theme.MUTED);
      top.add(info);
    }
    card.add(top, BorderLayout.NORTH);
    card.add(charts[i], BorderLayout.CENTER);
    return card;
  }

  private JPanel activity() {
    JPanel panel = new Theme.Card(new BorderLayout(0, 12));
    panel.setBorder(new EmptyBorder(18, 20, 16, 20));
    panel.setPreferredSize(new Dimension(700, 180));
    JPanel head = Theme.panel(new BorderLayout());
    head.add(Theme.label("Atividade", 16, Theme.TEXT), BorderLayout.WEST);
    head.add(
        Theme.label("Alertas e confirmações · últimas 100 entradas", 11, Theme.MUTED),
        BorderLayout.EAST);
    panel.add(head, BorderLayout.NORTH);
    JList<String> log = new JList<>(events);
    log.setBackground(Theme.SURFACE);
    log.setForeground(Theme.MUTED);
    log.setFont(Theme.font(12, Font.PLAIN));
    log.setFixedCellHeight(27);
    log.setCellRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel label =
                (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            label.setBorder(new EmptyBorder(3, 0, 3, 0));
            if (!selected) {
              label.setBackground(Theme.SURFACE);
              label.setForeground(value.toString().contains("ALERTA") ? Theme.AMBER : Theme.MUTED);
            }
            return label;
          }
        });
    JScrollPane scroll = new JScrollPane(log);
    scroll.setBorder(null);
    scroll.getViewport().setBackground(Theme.SURFACE);
    panel.add(scroll, BorderLayout.CENTER);
    events.addElement("Nenhuma atividade. Conecte-se ao servidor para começar.");
    return panel;
  }

  private void controls(boolean connected) {
    connect.setEnabled(!connected);
    disconnect.setEnabled(connected);
    for (JButton b : new JButton[] {start, stop, applyInterval, applyThresholds})
      b.setEnabled(connected);
    alertCenter.setConnected(connected);
    host.setEnabled(!connected);
    tcpPort.setEnabled(!connected);
    udpPort.setEnabled(!connected);
  }

  private void connect() {
    final int tp, up;
    try {
      tp = Integer.parseInt(tcpPort.getText().trim());
      up = Integer.parseInt(udpPort.getText().trim());
    } catch (NumberFormatException e) {
      log("Portas devem ser números de 1 a 65535");
      return;
    }
    String address = host.getText().trim();
    int token = ++generation;
    controls(false);
    connect.setEnabled(false);
    disconnect.setEnabled(true);
    tcpState.setText("TCP: Conectando...");
    for (HistoryChart chart : charts) chart.clear();
    resetReadings();
    for (JProgressBar bar : bars) bar.setValue(0);
    NetPulseConnection next =
        new NetPulseConnection(
            new NetPulseConnection.Listener() {
              private void ui(Runnable task) {
                SwingUtilities.invokeLater(
                    () -> {
                      if (token == generation) task.run();
                    });
              }

              public void telemetry(SystemSnapshot s) {
                queueSnapshot(s, token);
              }

              public void event(String line) {
                ui(() -> showEvent(line));
              }

              public void error(String message) {
                ui(() -> log(message));
              }

              public void state(String tcp, String udp, String monitoring) {
                ui(
                    () -> {
                      tcpState.setText("TCP: " + tcp);
                      udpState.setText(
                          "UDP: " + (udp.startsWith("Registro sem") ? "Sem confirmação" : udp));
                      udpState.setToolTipText(udp);
                      monitorState.setText(monitoring);
                      controls(tcp.equals("Conectado"));
                      updateConnectionHint(tcp, monitoring);
                    });
              }
            });
    connection = next;
    connector.submit(
        () -> {
          try {
            NetPulseConnection.Welcome w = next.connect(address, tp, up);
            SwingUtilities.invokeLater(
                () -> {
                  if (token != generation || next.isClosed()) return;
                  mode.setText(
                      (w.mode() == Mode.REAL ? "DADOS REAIS" : "SIMULAÇÃO")
                          + " — servidor "
                          + w.platform());
                  log("Conectado. Alertas globais disponíveis; telemetria inicialmente parada.");
                  alertCenter.refresh();
                  next.command("GET_STATUS")
                      .whenComplete(
                          (status, error) ->
                              SwingUtilities.invokeLater(
                                  () -> {
                                    if (token != generation) return;
                                    if (error != null) {
                                      log("Falha ao ler limites globais: " + error.getMessage());
                                      return;
                                    }
                                    for (String field : status.split(";")) {
                                      String[] pair = field.split("=", 2);
                                      for (Metric metric : Metric.values())
                                        if (pair[0].equals(metric.name()))
                                          thresholds[metric.ordinal()].setValue(
                                              Protocol.number(pair[1]));
                                    }
                                  }));
                });
          } catch (IOException e) {
            SwingUtilities.invokeLater(
                () -> {
                  if (token == generation) {
                    controls(false);
                    log(
                        "Falha de conexão (endereço, recusa, timeout ou protocolo): "
                            + e.getMessage());
                  }
                });
          }
        });
  }

  private void disconnect() {
    generation++;
    alertCenter.clear();
    NetPulseConnection old = connection;
    connection = null;
    if (old != null) old.close();
    controls(false);
    tcpState.setText("TCP: Desconectado");
    udpState.setText("UDP: Não registrado");
    monitorState.setText("Parado");
    mode.setText("DESCONECTADO");
    resetReadings();
    updateConnectionHint("Desconectado", "Parado");
  }

  private void send(String cmd) {
    NetPulseConnection current = connection;
    if (current == null) return;
    int token = generation;
    log("Enviado, aguardando confirmação: " + cmd);
    current
        .command(cmd)
        .whenComplete(
            (result, error) ->
                SwingUtilities.invokeLater(
                    () -> {
                      if (token == generation)
                        log(
                            error == null
                                ? "Confirmado: " + result
                                : "Comando não confirmado: " + error.getMessage());
                    }));
  }

  void showSnapshot(SystemSnapshot s) {
    for (int i = 0; i < 3; i++) {
      Double v = s.value(Metric.values()[i]);
      values[i].setText(v == null ? "—" : String.format(java.util.Locale.ROOT, "%.1f", v));
      metricNotes[i].setText(
          v == null
              ? (i == 2 ? "Sensor indisponível no servidor" : "Leitura indisponível no servidor")
              : METRIC_NOTES[i]);
      metricNotes[i].setForeground(v == null ? Theme.AMBER : Theme.MUTED);
      if (i < 2) {
        bars[i].setValue(v == null ? 0 : (int) Math.round(v));
        bars[i].setEnabled(v != null);
      }
      charts[i].add(v);
    }
    updated.setText(
        "Última leitura · "
            + Instant.ofEpochMilli(s.timestampEpochMillis())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    monitorState.setText("Recebendo telemetria");
  }

  private void showEvent(String line) {
    Protocol.ServerEvent event = Protocol.serverEvent(line);
    alertCenter.onServerEvent();
    if (event instanceof Protocol.ConfigurationEvent config) {
      thresholds[config.metric().ordinal()].setValue(config.threshold());
      log(
          "Limite global de "
              + config.metric()
              + " alterado para "
              + Protocol.exactDecimal(config.threshold())
              + " no servidor.");
    } else if (event instanceof Protocol.AlertEvent alert) {
      String time =
          Instant.ofEpochMilli(alert.timestamp())
              .atZone(ZoneId.systemDefault())
              .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
      log(
          time
              + " "
              + (alert.type().equals("ALERT") ? "ALERTA" : "RECUPERAÇÃO")
              + " "
              + alert.metric()
              + ": "
              + Protocol.decimal(alert.value())
              + (alert.threshold() == null
                  ? ""
                  : " (limite " + Protocol.decimal(alert.threshold()) + ")"));
    }
  }

  private void log(String message) {
    if (events.size() == 100) events.remove(0);
    events.addElement(message);
  }

  private record Update(SystemSnapshot snapshot, int generation) {}

  private final java.util.concurrent.atomic.AtomicReference<Update> latestUpdate =
      new java.util.concurrent.atomic.AtomicReference<>();
  private final java.util.concurrent.atomic.AtomicBoolean updateQueued =
      new java.util.concurrent.atomic.AtomicBoolean();

  private void queueSnapshot(SystemSnapshot snapshot, int token) {
    latestUpdate.set(new Update(snapshot, token));
    if (updateQueued.compareAndSet(false, true)) SwingUtilities.invokeLater(this::drainSnapshot);
  }

  private void drainSnapshot() {
    Update update = latestUpdate.getAndSet(null);
    if (update != null && update.generation() == generation) showSnapshot(update.snapshot());
    updateQueued.set(false);
    if (latestUpdate.get() != null && updateQueued.compareAndSet(false, true))
      SwingUtilities.invokeLater(this::drainSnapshot);
  }

  private void resetReadings() {
    latestUpdate.set(null);
    for (int i = 0; i < 3; i++) {
      values[i].setText("—");
      metricNotes[i].setText("Aguardando leitura do servidor");
      metricNotes[i].setForeground(Theme.MUTED);
    }
    updated.setText("Nenhuma leitura recebida");
  }

  private void updateConnectionHint(String tcp, String monitoring) {
    if (!tcp.equals("Conectado"))
      hint.setText("Informe o IP do servidor e conecte-se para começar.");
    else if (monitoring.equals("Parado"))
      hint.setText("Conexão estabelecida. Clique em Iniciar para receber as métricas.");
    else if (monitoring.equals("Sem telemetria"))
      hint.setText("Sem novas leituras. Verifique a porta UDP, o firewall e a rede do servidor.");
    else hint.setText("Monitorando os recursos do computador servidor.");
    tcpState.setForeground(tcp.equals("Conectado") ? Theme.GREEN : Theme.MUTED);
    monitorState.setForeground(monitoring.equals("Sem telemetria") ? Theme.AMBER : Theme.TEXT);
  }

  public void close() {
    disconnect();
    connector.shutdownNow();
  }
}
