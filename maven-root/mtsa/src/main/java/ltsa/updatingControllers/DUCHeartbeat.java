package ltsa.updatingControllers;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DUCHeartbeat {

    private static final boolean CONFIGURED =
            Boolean.parseBoolean(System.getProperty("duc.heartbeat", "false"));
    private static final long INTERVAL_MS =
            Math.max(1L, Long.getLong("duc.heartbeat.intervalSec", 600L)) * 1000L;
    private static final String FILE_PATH =
            System.getProperty("duc.heartbeat.file", "duc_heartbeat.log");
    private static final boolean APPEND =
            Boolean.parseBoolean(System.getProperty("duc.heartbeat.append", "false"));

    private static final Object LOCK = new Object();
    private static final ConcurrentMap<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private static volatile boolean active = false;
    private static volatile boolean running = false;
    private static volatile String mode = "unknown";
    private static volatile String runName = "unknown";
    private static volatile String phase = "idle";
    private static volatile long runStartedAt = 0L;
    private static volatile long phaseStartedAt = 0L;

    private static PrintWriter writer;
    private static Thread thread;

    private DUCHeartbeat() {
    }

    public static boolean isEnabled() {
        return CONFIGURED && active;
    }

    public static void start(String newMode, String newRunName) {
        if (!CONFIGURED) {
            return;
        }

        synchronized (LOCK) {
            if (active) {
                stopLocked("restarted");
            }

            mode = sanitize(newMode);
            runName = sanitize(newRunName);
            phase = "starting";
            runStartedAt = System.currentTimeMillis();
            phaseStartedAt = runStartedAt;
            COUNTERS.clear();

            try {
                File file = new File(FILE_PATH);
                File parent = file.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                writer = new PrintWriter(new FileWriter(file, APPEND));
            } catch (IOException e) {
                System.err.println("Failed to open DUC heartbeat file: " + e.getMessage());
                writer = null;
                active = false;
                running = false;
                return;
            }

            active = true;
            running = true;
            writeSnapshotLocked("start");

            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runHeartbeatLoop();
                }
            }, "DUC-Heartbeat");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public static void stop(String status) {
        if (!CONFIGURED) {
            return;
        }

        Thread toJoin;
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            writeSnapshotLocked("finish status=" + sanitize(status));
            running = false;
            toJoin = thread;
            if (toJoin != null) {
                toJoin.interrupt();
            }
        }

        if (toJoin != null && toJoin != Thread.currentThread()) {
            try {
                toJoin.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (LOCK) {
            closeWriterLocked();
            active = false;
            thread = null;
        }
    }

    public static void beginPhase(String newPhase) {
        if (!isEnabled()) {
            return;
        }
        synchronized (LOCK) {
            phase = sanitize(newPhase);
            phaseStartedAt = System.currentTimeMillis();
            COUNTERS.clear();
            writeSnapshotLocked("phase");
        }
    }

    public static void setCounter(String name, long value) {
        if (!isEnabled()) {
            return;
        }
        COUNTERS.computeIfAbsent(sanitize(name), key -> new AtomicLong()).set(value);
    }

    public static void addCounter(String name, long delta) {
        if (!isEnabled() || delta == 0L) {
            return;
        }
        COUNTERS.computeIfAbsent(sanitize(name), key -> new AtomicLong()).addAndGet(delta);
    }

    public static void incrementCounter(String name) {
        addCounter(name, 1L);
    }

    private static void runHeartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                // stop() interrupts this thread to finish promptly.
            }
            if (running) {
                synchronized (LOCK) {
                    writeSnapshotLocked("heartbeat");
                }
            }
        }
    }

    private static void stopLocked(String status) {
        writeSnapshotLocked("finish status=" + sanitize(status));
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        closeWriterLocked();
        active = false;
        thread = null;
    }

    private static void writeSnapshotLocked(String event) {
        if (writer == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();

        StringBuilder sb = new StringBuilder();
        sb.append(DATE_FORMAT.format(new Date(now)));
        sb.append(" [DUC-Heartbeat]");
        sb.append(" event=\"").append(escape(event)).append("\"");
        sb.append(" mode=\"").append(escape(mode)).append("\"");
        sb.append(" run=\"").append(escape(runName)).append("\"");
        sb.append(" phase=\"").append(escape(phase)).append("\"");
        sb.append(" elapsedSec=").append((now - runStartedAt) / 1000L);
        sb.append(" phaseElapsedSec=").append((now - phaseStartedAt) / 1000L);
        sb.append(" heapUsedMB=").append(toMegabytes(heapUsed));
        sb.append(" heapMaxMB=").append(toMegabytes(heapMax));

        Map<String, AtomicLong> sorted = new TreeMap<>(COUNTERS);
        for (Map.Entry<String, AtomicLong> entry : sorted.entrySet()) {
            sb.append(' ');
            sb.append(entry.getKey()).append('=').append(entry.getValue().get());
        }

        writer.println(sb.toString());
        writer.flush();
    }

    private static void closeWriterLocked() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    private static long toMegabytes(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private static String sanitize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        return value.trim();
    }

    private static String escape(String value) {
        return sanitize(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
