//usr/bin/env jbang

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public class JfrToTimeline {
    private static final String[] NETTY_EVENTS = {
            "io.netty.loom.NettyRunIo",
            "io.netty.loom.NettyRunTasks",
            "io.netty.loom.VirtualThreadTaskRuns",
            "io.netty.loom.VirtualThreadTaskRun",
            "io.netty.loom.VirtualThreadTaskSubmit"
    };
    private static final int SUBMIT_EVENT_INDEX = 4;
    private static final String[] NETTY_EVENT_SHORT_NAMES = new String[NETTY_EVENTS.length];

    static {
        for (int i = 0; i < NETTY_EVENTS.length; i++) {
            NETTY_EVENT_SHORT_NAMES[i] = shortName(NETTY_EVENTS[i]);
        }
    }

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

        long minMicros = Long.MAX_VALUE;
        long maxMicros = Long.MIN_VALUE;
        long eventCount = 0;

        try (RecordingFile recording = new RecordingFile(config.input);
             LineWriter writer = new LineWriter(new BufferedOutputStream(Files.newOutputStream(config.output)))) {
            writeHeader(writer);
            LongHashSet threadsSeen = new LongHashSet(256);

            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                int idx = eventIndex(event.getEventType().getName());
                if (idx < 0) {
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

                Instant startTime = event.getStartTime();
                long startMicros = toNanos(startTime);
                long endMicros = startMicros;
                long durMicros;
                if (idx == SUBMIT_EVENT_INDEX) {
                    durMicros = 0L;
                } else {
                    Instant endTime = event.getEndTime();
                    endMicros = toNanos(endTime);
                    durMicros = endMicros - startMicros;
                }
                if (durMicros < 0L) {
                    durMicros = 0L;
                    endMicros = startMicros;
                }
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
        writer.writeAscii("{\"t\":\"h\",\"v\":1,\"unit\":\"ns\",\"events\":[");
        for (int i = 0; i < NETTY_EVENT_SHORT_NAMES.length; i++) {
            if (i > 0) {
                writer.writeAscii(",");
            }
            writer.writeJsonString(NETTY_EVENT_SHORT_NAMES[i]);
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
        if (event.hasField("isPoller")) {
            writer.writeAscii(",\"p\":");
            writer.writeAscii(event.getBoolean("isPoller") ? "1" : "0");
        }
        if (event.hasField("isEventLoop")) {
            writer.writeAscii(",\"el\":");
            writer.writeAscii(event.getBoolean("isEventLoop") ? "1" : "0");
        }
        if (event.hasField("immediate")) {
            writer.writeAscii(",\"im\":");
            writer.writeAscii(event.getBoolean("immediate") ? "1" : "0");
        }
        if (event.hasField("canBlock")) {
            writer.writeAscii(",\"b\":");
            writer.writeAscii(event.getBoolean("canBlock") ? "1" : "0");
        }
        if (event.hasField("tasksExecuted")) {
            writer.writeAscii(",\"te\":");
            writer.writeLong(event.getInt("tasksExecuted"));
        }
        if (event.hasField("queueDepthBefore")) {
            writer.writeAscii(",\"q0\":");
            writer.writeLong(event.getInt("queueDepthBefore"));
        }
        if (event.hasField("queueDepthAfter")) {
            writer.writeAscii(",\"q1\":");
            writer.writeLong(event.getInt("queueDepthAfter"));
        }
        if (event.hasField("ioEventsHandled")) {
            writer.writeAscii(",\"io\":");
            writer.writeLong(event.getInt("ioEventsHandled"));
        }
        if (event.hasField("tasksHandled")) {
            writer.writeAscii(",\"th\":");
            writer.writeLong(event.getInt("tasksHandled"));
        }
        RecordedThread submitterThread = getThread(event, "submitterThread");
        if (submitterThread != null) {
            writer.writeAscii(",\"st\":");
            writer.writeLong(threadId(submitterThread));
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

    private static int eventIndex(String eventName) {
        for (int i = 0; i < NETTY_EVENTS.length; i++) {
            if (NETTY_EVENTS[i].equals(eventName)) {
                return i;
            }
        }
        return -1;
    }

    private static long toNanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
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
        private static final int LONG_BUFFER_SIZE = 20;
        private final OutputStream out;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private final char[] longBuffer = new char[LONG_BUFFER_SIZE];
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
                if (value == Long.MIN_VALUE) {
                    writeAscii("9223372036854775808");
                    return;
                }
                value = -value;
            }
            int idx = 0;
            while (value > 0) {
                long q = value / 10;
                int r = (int) (value - q * 10);
                longBuffer[idx++] = (char) ('0' + r);
                value = q;
            }
            for (int i = idx - 1; i >= 0; i--) {
                writeByte((byte) longBuffer[i]);
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
