package br.edu.netpulse.server.alerts;

import br.edu.netpulse.common.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded shared history. Disk work runs outside the sampler/session locks. */
public final class AlertHistory implements AutoCloseable {
  public static final int CAPACITY = 1000;
  private final Deque<AlertRecord> entries = new ArrayDeque<>();
  private final EnumMap<Metric, Double> thresholds = new EnumMap<>(Metric.class);

  {
    for (Metric m : Metric.values()) thresholds.put(m, m.defaultThreshold);
  }

  private long nextId = 1, revision, savedRevision;
  private long configurationRevision, savedConfigurationRevision;
  private long persistedLastId, persistedEntryCount;
  private static final long JOURNAL_LIMIT = CAPACITY + 250;
  private boolean closed;
  private final Path file;
  private final ScheduledExecutorService persistence;
  private FileChannel lockChannel;
  private FileLock fileLock;

  public AlertHistory() {
    file = null;
    persistence = null;
  }

  public AlertHistory(Path path) throws IOException {
    file = path.toAbsolutePath();
    Files.createDirectories(file.getParent());
    try {
      lockHistoryFile();
      loadHistory();
    } catch (IOException | RuntimeException e) {
      releaseLock();
      throw new IOException("Não foi possível abrir histórico " + file + ": " + e.getMessage(), e);
    }
    persistence = Executors.newSingleThreadScheduledExecutor();
    persistence.scheduleWithFixedDelay(this::saveSafely, 500, 500, TimeUnit.MILLISECONDS);
  }

  private void lockHistoryFile() throws IOException {
    lockChannel =
        FileChannel.open(
            file.resolveSibling(file.getFileName() + ".lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    fileLock = lockChannel.tryLock();
    if (fileLock == null)
      throw new IOException("Histórico já utilizado por outro servidor: " + file);
  }

  private void loadHistory() throws IOException {
    if (!Files.exists(file)) return;
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      String line;
      while ((line = Protocol.readLine(in)) != null) {
        if (line.startsWith("RULES|")) loadThresholds(line);
        else loadRecord(line);
      }
    }
  }

  private void loadThresholds(String line) {
    String[] f = Protocol.fields(line);
    Protocol.count(f, 4);
    for (int i = 0; i < 3; i++) {
      Metric metric = Metric.values()[i];
      thresholds.put(metric, metric.validateThreshold(Protocol.number(f[i + 1])));
    }
  }

  private void loadRecord(String line) throws IOException {
    AlertRecord record = AlertRecord.decode(line);
    if (record.id() <= persistedLastId) throw new IOException("IDs fora de ordem no histórico");
    persistedEntryCount++;
    entries.addLast(record);
    if (entries.size() > CAPACITY) entries.removeFirst();
    nextId = Math.addExact(record.id(), 1);
    persistedLastId = record.id();
  }


  public synchronized AlertRecord append(
      UUID session, SystemSnapshot snapshot, String event, double threshold) {
    if (closed) throw new IllegalStateException("Histórico encerrado");
    String[] f = Protocol.fields(event);
    Metric metric = Metric.valueOf(f[2]);
    AlertRecord record =
        new AlertRecord(
            nextId++,
            Protocol.positive(f[1]),
            session,
            snapshot.mode(),
            f[0],
            metric,
            Protocol.number(f[3]),
            threshold);
    entries.addLast(record);
    if (entries.size() > CAPACITY) entries.removeFirst();
    revision++;
    return record;
  }

  public synchronized Map<Metric, Double> thresholds() {
    return Map.copyOf(thresholds);
  }

  public synchronized void setThreshold(Metric metric, double value) {
    metric.validateThreshold(value);
    if (Double.compare(thresholds.put(metric, value), value) != 0) {
      configurationRevision++;
      revision++;
    }
  }

  public synchronized List<AlertRecord> page(long before, int limit) {
    if (before < 0 || limit < 1 || limit > 50)
      throw new IllegalArgumentException("INVALID_HISTORY_LIMIT");
    List<AlertRecord> page = new ArrayList<>();
    Iterator<AlertRecord> iterator = entries.descendingIterator();
    while (iterator.hasNext() && page.size() < limit) {
      AlertRecord record = iterator.next();
      if (before == 0 || record.id() < before) page.add(record);
    }
    return List.copyOf(page);
  }

  private void saveSafely() {
    try {
      flush();
    } catch (IOException e) {
      System.getLogger(getClass().getName())
          .log(
              System.Logger.Level.ERROR,
              "Falha ao salvar histórico; registros permanecem na memória e haverá nova tentativa",
              e);
    }
  }

  public synchronized int size() {
    return entries.size();
  }

  /** Only the persistence worker, or close after its termination, calls this method. */
  private void flush() throws IOException {
    if (file == null) return;
    List<AlertRecord> copy;
    long version;
    String rules;
    long savedLastId;
    long savedCount;
    long configVersion;
    boolean compact;
    synchronized (this) {
      if (revision == savedRevision) return;
      // Captura eventos e regras juntos. O disco é acessado depois de liberar o lock.
      copy = List.copyOf(entries);
      version = revision;
      configVersion = configurationRevision;
      savedLastId = persistedLastId;
      savedCount = persistedEntryCount;
      rules =
          "RULES|"
              + Protocol.exactDecimal(thresholds.get(Metric.CPU))
              + "|"
              + Protocol.exactDecimal(thresholds.get(Metric.RAM))
              + "|"
              + Protocol.exactDecimal(thresholds.get(Metric.TEMP));
    }
    long persistedThrough = savedLastId;
    List<AlertRecord> additions =
        copy.stream().filter(entry -> entry.id() > persistedThrough).toList();
    long latestId = copy.isEmpty() ? savedLastId : copy.get(copy.size() - 1).id();
    // Uma lacuna indica que a retenção descartou eventos que ainda estavam pendentes.
    // Nesse caso, reescreve o arquivo com os eventos que ainda estão em memória.
    compact =
        !Files.exists(file)
            || configVersion != savedConfigurationRevision
            || savedCount + additions.size() > JOURNAL_LIMIT
            || (!additions.isEmpty() && additions.get(0).id() != savedLastId + 1);
    if (compact) {
      rewriteHistory(rules, copy);
      savedCount = copy.size();
      savedLastId = latestId;
    } else if (!additions.isEmpty()) {
      try (OutputStream out =
          new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.APPEND))) {
        for (AlertRecord entry : additions)
          out.write((entry.encode() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      savedCount += additions.size();
      savedLastId = latestId;
    }
    synchronized (this) {
      // Alterações recebidas durante a escrita ficam pendentes para a próxima gravação.
      savedRevision = version;
      savedConfigurationRevision = configVersion;
      persistedEntryCount = savedCount;
      persistedLastId = savedLastId;
    }
  }

  private void rewriteHistory(String rules, List<AlertRecord> copy) throws IOException {
    Path temp = Files.createTempFile(file.getParent(), "netpulse-alerts-", ".tmp");
    try {
      try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
        out.write((rules + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (AlertRecord entry : copy)
          out.write((entry.encode() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      moveIntoPlace(temp);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private void moveIntoPlace(Path temp) throws IOException {
    try {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void releaseLock() throws IOException {
    try {
      if (fileLock != null) fileLock.release();
    } finally {
      if (lockChannel != null) lockChannel.close();
    }
  }

  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    if (persistence != null) {
      persistence.shutdown();
      try {
        if (!persistence.awaitTermination(5, TimeUnit.SECONDS)) {
          System.getLogger(getClass().getName())
              .log(
                  System.Logger.Level.ERROR,
                  "Persistência ainda ocupada; não é seguro iniciar outro escritor");
          return;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        System.getLogger(getClass().getName())
            .log(System.Logger.Level.ERROR, "Encerramento do histórico interrompido", e);
        return;
      }
    }
    saveSafely();
    try {
      releaseLock();
    } catch (IOException e) {
      System.getLogger(getClass().getName())
          .log(System.Logger.Level.WARNING, "Falha ao liberar arquivo de histórico", e);
    }
  }
}
