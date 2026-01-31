//usr/bin/env jbang

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public class JfrToTimeline {
    private static final String[] NETTY_EVENTS = {
            "io.netty.loom.NettyRunIo",
            "io.netty.loom.NettyRunNonBlockingTasks",
            "io.netty.loom.VirtualThreadTaskRuns",
            "io.netty.loom.VirtualThreadTaskRun",
            "io.netty.loom.VirtualThreadTaskSubmit"
    };

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        if (config == null) {
            usage();
            System.exit(1);
            return;
        }

        if (!Files.isRegularFile(config.input)) {
            throw new IllegalArgumentException("Input JFR file not found: " + config.input);
        }
        if (config.output.getParent() != null) {
            Files.createDirectories(config.output.getParent());
        }

        Map<String, Integer> eventIndex = new HashMap<>();
        for (int i = 0; i < NETTY_EVENTS.length; i++) {
            eventIndex.put(NETTY_EVENTS[i], i);
        }

        long minMicros = Long.MAX_VALUE;
        long maxMicros = Long.MIN_VALUE;
        long eventCount = 0;

        try (RecordingFile recording = new RecordingFile(config.input);
             LineWriter writer = new LineWriter(new BufferedOutputStream(Files.newOutputStream(config.output)))) {
            writeHeader(writer);
            LongHashSet threadsSeen = new LongHashSet(256);

            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                Integer idx = eventIndex.get(event.getEventType().getName());
                if (idx == null) {
                    continue;
                }

                RecordedThread carrierThread = getThread(event, "carrierThread");
                if (carrierThread == null) {
                    carrierThread = getThread(event, "eventLoopThread");
                }
                if (carrierThread == null) {
                    carrierThread = getThread(event, "submitterThread");
                }

                long tid = carrierThread != null ? threadId(carrierThread) : 0L;
                if (carrierThread != null && threadsSeen.add(tid)) {
                    writeThreadMeta(writer, tid, threadName(carrierThread));
                }

                long startMicros = toMicros(event.getStartTime());
                long durMicros = Math.max(0L, Duration.between(event.getStartTime(), event.getEndTime()).toNanos() / 1_000L);
                long endMicros = startMicros + durMicros;
                if (startMicros < minMicros) {
                    minMicros = startMicros;
                }
                if (endMicros > maxMicros) {
                    maxMicros = endMicros;
                }
                eventCount++;

                writeEventLine(writer, event, tid, idx, startMicros, durMicros);
            }

            if (minMicros == Long.MAX_VALUE) {
                minMicros = 0L;
                maxMicros = 0L;
            }
            writeFooter(writer, minMicros, maxMicros, eventCount);
        }
    }

    private static void usage() {
        System.out.println("Usage: jbang JfrToTimeline.java --input <file.jfr> --output <timeline.jsonl>");
    }

    private static void writeHeader(LineWriter writer) throws IOException {
        writer.writeAscii("{\"t\":\"h\",\"v\":1,\"unit\":\"us\",\"events\":[");
        for (int i = 0; i < NETTY_EVENTS.length; i++) {
            if (i > 0) {
                writer.writeAscii(",");
            }
            writer.writeJsonString(shortName(NETTY_EVENTS[i]));
        }
        writer.writeAscii("]}");
        writer.writeLineEnd();
    }

    private static void writeThreadMeta(LineWriter writer, long tid, String name) throws IOException {
        writer.writeAscii("{\"t\":\"m\",\"tid\":");
        writer.writeLong(tid);
        writer.writeAscii(",\"name\":");
        writer.writeJsonString(name);
        writer.writeAscii("}");
        writer.writeLineEnd();
    }

    private static void writeEventLine(LineWriter writer,
                                       RecordedEvent event,
                                       long tid,
                                       int eventId,
                                       long startMicros,
                                       long durMicros) throws IOException {
        writer.writeAscii("{\"t\":\"e\",\"tid\":");
        writer.writeLong(tid);
        writer.writeAscii(",\"n\":");
        writer.writeLong(eventId);
        writer.writeAscii(",\"s\":");
        writer.writeLong(startMicros);
        writer.writeAscii(",\"d\":");
        writer.writeLong(durMicros);

        RecordedThread virtualThread = getThread(event, "virtualThread");
        if (virtualThread != null) {
            writer.writeAscii(",\"v\":");
            writer.writeLong(threadId(virtualThread));
        }
        if (event.hasField("isPoller") && event.getBoolean("isPoller")) {
            writer.writeAscii(",\"p\":1");
        }
        if (event.hasField("canBlock") && event.getBoolean("canBlock")) {
            writer.writeAscii(",\"b\":1");
        }

        writer.writeAscii("}");
        writer.writeLineEnd();
    }

    private static void writeFooter(LineWriter writer, long minMicros, long maxMicros, long count) throws IOException {
        writer.writeAscii("{\"t\":\"f\",\"min\":");
        writer.writeLong(minMicros);
        writer.writeAscii(",\"max\":");
        writer.writeLong(maxMicros);
        writer.writeAscii(",\"count\":");
        writer.writeLong(count);
        writer.writeAscii("}");
        writer.writeLineEnd();
    }

    private static String shortName(String fullName) {
        int index = fullName.lastIndexOf('.');
        return index >= 0 ? fullName.substring(index + 1) : fullName;
    }

    private static long toMicros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private static RecordedThread getThread(RecordedEvent event, String field) {
        if (!event.hasField(field)) {
            return null;
        }
        Object value = event.getValue(field);
        if (value instanceof RecordedThread) {
            return (RecordedThread) value;
        }
        return null;
    }

    private static long threadId(RecordedThread thread) {
        long javaId = thread.getJavaThreadId();
        if (javaId > 0L) {
            return javaId;
        }
        long osId = thread.getOSThreadId();
        return osId > 0L ? osId : 0L;
    }

    private static String threadName(RecordedThread thread) {
        String name = thread.getJavaName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        name = thread.getOSName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return "unknown";
    }

    private static final class Config {
        final Path input;
        final Path output;

        private Config(Path input, Path output) {
            this.input = input;
            this.output = output;
        }

        static Config parse(String[] args) {
            Path input = null;
            Path output = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--input".equals(arg) && i + 1 < args.length) {
                    input = Path.of(args[++i]);
                } else if ("--output".equals(arg) && i + 1 < args.length) {
                    output = Path.of(args[++i]);
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    return null;
                } else {
                    return null;
                }
            }

            if (input == null || output == null) {
                return null;
            }

            return new Config(input, output);
        }
    }

    private static final class LineWriter implements Closeable {
        private static final int BUFFER_SIZE = 1 << 16;
        private final OutputStream out;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private int position;

        LineWriter(OutputStream out) {
            this.out = out;
        }

        void writeAscii(String value) throws IOException {
            for (int i = 0; i < value.length(); i++) {
                writeByte((byte) value.charAt(i));
            }
        }

        void writeLong(long value) throws IOException {
            if (value == 0) {
                writeByte((byte) '0');
                return;
            }
            if (value < 0) {
                writeByte((byte) '-');
                value = -value;
            }
            char[] digits = new char[20];
            int idx = 0;
            while (value > 0) {
                long q = value / 10;
                int r = (int) (value - q * 10);
                digits[idx++] = (char) ('0' + r);
                value = q;
            }
            for (int i = idx - 1; i >= 0; i--) {
                writeByte((byte) digits[i]);
            }
        }

        void writeJsonString(String value) throws IOException {
            if (value == null) {
                writeAscii("null");
                return;
            }
            writeByte((byte) '"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"':
                        writeAscii("\\\"");
                        break;
                    case '\\':
                        writeAscii("\\\\");
                        break;
                    case '\b':
                        writeAscii("\\b");
                        break;
                    case '\f':
                        writeAscii("\\f");
                        break;
                    case '\n':
                        writeAscii("\\n");
                        break;
                    case '\r':
                        writeAscii("\\r");
                        break;
                    case '\t':
                        writeAscii("\\t");
                        break;
                    default:
                        if (c < 0x20 || c >= 0x7f) {
                            writeUnicodeEscape(c);
                        } else {
                            writeByte((byte) c);
                        }
                        break;
                }
            }
            writeByte((byte) '"');
        }

        void writeLineEnd() throws IOException {
            writeByte((byte) '\n');
        }

        private void writeUnicodeEscape(char c) throws IOException {
            writeAscii("\\u");
            writeHex((c >> 12) & 0xF);
            writeHex((c >> 8) & 0xF);
            writeHex((c >> 4) & 0xF);
            writeHex(c & 0xF);
        }

        private void writeHex(int value) throws IOException {
            int v = value & 0xF;
            char c = (char) (v < 10 ? ('0' + v) : ('A' + (v - 10)));
            writeByte((byte) c);
        }

        private void writeByte(byte value) throws IOException {
            if (position == buffer.length) {
                flush();
            }
            buffer[position++] = value;
        }

        private void flush() throws IOException {
            if (position > 0) {
                out.write(buffer, 0, position);
                position = 0;
            }
        }

        @Override
        public void close() throws IOException {
            flush();
            out.close();
        }
    }

    private static final class LongHashSet {
        private static final long EMPTY = Long.MIN_VALUE;
        private long[] table;
        private int mask;
        private int size;
        private int resizeAt;

        LongHashSet(int expectedSize) {
            int capacity = 1;
            while (capacity < expectedSize * 2) {
                capacity <<= 1;
            }
            table = new long[capacity];
            for (int i = 0; i < table.length; i++) {
                table[i] = EMPTY;
            }
            mask = capacity - 1;
            resizeAt = (int) (capacity * 0.7);
        }

        boolean add(long value) {
            if (value == EMPTY) {
                return false;
            }
            if (size >= resizeAt) {
                rehash();
            }
            int slot = mix(value) & mask;
            while (true) {
                long current = table[slot];
                if (current == EMPTY) {
                    table[slot] = value;
                    size++;
                    return true;
                }
                if (current == value) {
                    return false;
                }
                slot = (slot + 1) & mask;
            }
        }

        private void rehash() {
            long[] oldTable = table;
            int newCapacity = oldTable.length << 1;
            table = new long[newCapacity];
            for (int i = 0; i < table.length; i++) {
                table[i] = EMPTY;
            }
            mask = newCapacity - 1;
            resizeAt = (int) (newCapacity * 0.7);
            size = 0;
            for (long value : oldTable) {
                if (value != EMPTY) {
                    add(value);
                }
            }
        }

        private static int mix(long value) {
            long x = value;
            x ^= (x >>> 33);
            x *= 0xff51afd7ed558ccdL;
            x ^= (x >>> 33);
            x *= 0xc4ceb9fe1a85ec53L;
            x ^= (x >>> 33);
            return (int) x;
        }
    }
}
