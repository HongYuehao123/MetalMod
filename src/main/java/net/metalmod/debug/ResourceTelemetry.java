package net.metalmod.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in, bounded CPU API attribution during the measured portion of an F8 capture.
 * Bytes are logical payload/attachment footprint, never measured GPU traffic. Direct engine
 * writes through mapped ByteBuffers and native-only FX/presentation work are outside this census.
 */
public final class ResourceTelemetry {
    private static final int MAX_KEYS = 512;
    private static final Key OVERFLOW = new Key("overflow", "<additional resource keys>", "mixed");
    private static final Map<Key, long[]> totals = new LinkedHashMap<>();
    private static volatile long startedAt;

    private ResourceTelemetry() {}

    public static synchronized void start(boolean enabled) {
        startedAt = 0;
        totals.clear();
        if (enabled) startedAt = System.nanoTime();
    }

    /** Zero when disabled: no timer, key allocation, locking or file I/O on that path. */
    public static long begin() { return startedAt == 0 ? 0 : System.nanoTime(); }

    public static void end(long start, String operation, String label, Object format, long bytes) {
        if (start == 0) return;
        long elapsed = System.nanoTime() - start;
        add(start, operation, label, format, bytes, elapsed);
    }

    /** Attachment declarations have no separately timed API call; their wall time is zero. */
    public static void attachment(long start, String operation, String label, Object format, long bytes) {
        if (start != 0) add(start, operation, label, format, bytes, 0);
    }

    private static synchronized void add(long start, String operation, String label, Object format,
                                         long bytes, long nanos) {
        // Ignore work begun in a previous capture or before this capture's first full interval.
        if (startedAt == 0 || start < startedAt) return;
        Key key = new Key(operation, bounded(label), bounded(String.valueOf(format)));
        long[] values = totals.get(key);
        if (values == null) {
            if (totals.size() >= MAX_KEYS) key = OVERFLOW;
            values = totals.computeIfAbsent(key, ignored -> new long[3]);
        }
        values[0]++;
        values[1] += Math.max(0, bytes);
        values[2] += Math.max(0, nanos);
    }

    private static String bounded(String text) {
        if (text == null) return "unnamed";
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    public static synchronized Snapshot stop() {
        boolean enabled = startedAt != 0;
        startedAt = 0;
        List<Row> rows = totals.entrySet().stream().map(e -> new Row(e.getKey().operation,
                e.getKey().label, e.getKey().format, e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList();
        totals.clear();
        return new Snapshot(enabled, rows);
    }

    private record Key(String operation, String label, String format) {}
    public record Row(String operation, String label, String format, long calls, long bytes, long apiNanos) {}
    public record Snapshot(boolean enabled, List<Row> rows) {
        public void write(Path directory) throws IOException {
            if (!enabled) return;
            StringBuilder csv = new StringBuilder("operation,label,format,calls,logical_bytes,cpu_api_ns\n");
            for (Row row : rows) csv.append(quote(row.operation())).append(',').append(quote(row.label()))
                    .append(',').append(quote(row.format())).append(',').append(row.calls()).append(',')
                    .append(row.bytes()).append(',').append(row.apiNanos()).append('\n');
            Files.writeString(directory.resolve("resources.csv"), csv);
            Files.writeString(directory.resolve("INSTRUMENTED.txt"),
                    "Resource attribution enabled. CPU API wall time, not GPU duration or bandwidth.\n"
                    + "Capture totals; pass attachment rows count declarations with zero separate API time.\n"
                    + "Mapped engine writes and native-only FX/presentation operations are excluded.\n"
                    + "Logical bytes exclude row padding; clear bytes are attachment footprints.\n"
                    + "Labels truncated to 160 chars; excess keys merge into overflow after 512 keys.\n"
                    + "Use a separate uninstrumented launch for throughput claims.\n");
        }
    }

    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
}
