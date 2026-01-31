//usr/bin/env jbang

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public class JfrToTrace {
    private static final String[] NETTY_EVENTS = {
            "io.netty.loom.NettyRunIo",
            "io.netty.loom.NettyRunNonBlockingTasks",
            "io.netty.loom.VirtualThreadTaskRuns",
            "io.netty.loom.VirtualThreadTaskRun",
            "io.netty.loom.VirtualThreadTaskSubmit"
    };

    private static final String PROCESS_NAME = "Netty Loom JFR";
    private static final int DEFAULT_PID = 1;

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

        try (RecordingFile recording = new RecordingFile(config.input);
             TraceWriter writer = new TraceWriter(config.output, config.pid)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (!isNettyEvent(event.getEventType().getName())) {
                    continue;
                }
                writer.writeEvent(event);
            }
        }
    }

    private static void usage() {
        System.out.println("Usage: jbang jfr-to-trace.java --input <file.jfr> --output <trace.json> [--pid <id>]");
    }

    private static boolean isNettyEvent(String eventName) {
        for (String name : NETTY_EVENTS) {
            if (name.equals(eventName)) {
                return true;
            }
        }
        return false;
    }

    private static final class Config {
        final Path input;
        final Path output;
        final int pid;

        private Config(Path input, Path output, int pid) {
            this.input = input;
            this.output = output;
            this.pid = pid;
        }

        static Config parse(String[] args) {
            Path input = null;
            Path output = null;
            int pid = DEFAULT_PID;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--input".equals(arg) && i + 1 < args.length) {
                    input = Path.of(args[++i]);
                } else if ("--output".equals(arg) && i + 1 < args.length) {
                    output = Path.of(args[++i]);
                } else if ("--pid".equals(arg) && i + 1 < args.length) {
                    pid = Integer.parseInt(args[++i]);
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    return null;
                } else {
                    return null;
                }
            }

            if (input == null || output == null) {
                return null;
            }

            return new Config(input, output, pid);
        }
    }

    private static final class TraceWriter implements Closeable {
        private final JsonWriter writer;
        private final int pid;
        private final LongHashSet threadsSeen = new LongHashSet(256);
        private boolean firstEvent = true;

        TraceWriter(Path output, int pid) throws IOException {
            this.writer = new JsonWriter(new BufferedOutputStream(Files.newOutputStream(output)));
            this.pid = pid;
            writer.writeAscii("{\"traceEvents\":[");
            writeProcessName();
        }

        void writeEvent(RecordedEvent event) throws IOException {
            String eventName = shortName(event.getEventType().getName());
            long startMicros = toMicros(event.getStartTime());
            long durMicros = Math.max(0L, Duration.between(event.getStartTime(), event.getEndTime()).toNanos() / 1_000L);

            RecordedThread carrierThread = getThread(event, "carrierThread");
            if (carrierThread == null) {
                carrierThread = getThread(event, "eventLoopThread");
            }
            if (carrierThread == null) {
                carrierThread = getThread(event, "submitterThread");
            }

            long tid = carrierThread != null ? threadId(carrierThread) : 0L;
            if (carrierThread != null) {
                writeThreadNameIfNeeded(tid, threadName(carrierThread));
            }

            writeEventPrefix();
            writer.writeAscii("{\"name\":");
            writer.writeJsonString(eventName);
            writer.writeAscii(",\"cat\":\"netty\",\"ph\":\"X\",\"ts\":");
            writer.writeLong(startMicros);
            writer.writeAscii(",\"dur\":");
            writer.writeLong(durMicros);
            writer.writeAscii(",\"pid\":");
            writer.writeLong(pid);
            writer.writeAscii(",\"tid\":");
            writer.writeLong(tid);
            writer.writeAscii(",\"args\":{");

            boolean wroteArg = false;
            wroteArg = writeThreadArgs("virtualThread", getThread(event, "virtualThread"), wroteArg);
            wroteArg = writeThreadArgs("submitterThread", getThread(event, "submitterThread"), wroteArg);
            wroteArg = writeThreadArgs("eventLoopThread", getThread(event, "eventLoopThread"), wroteArg);

            if (event.hasField("isPoller")) {
                wroteArg = writeBooleanArg("isPoller", event.getBoolean("isPoller"), wroteArg);
            }
            if (event.hasField("canBlock")) {
                wroteArg = writeBooleanArg("canBlock", event.getBoolean("canBlock"), wroteArg);
            }
            if (event.hasField("tasksExecuted")) {
                wroteArg = writeIntArg("tasksExecuted", event.getInt("tasksExecuted"), wroteArg);
            }
            if (event.hasField("queueDepthBefore")) {
                wroteArg = writeIntArg("queueDepthBefore", event.getInt("queueDepthBefore"), wroteArg);
            }
            if (event.hasField("queueDepthAfter")) {
                wroteArg = writeIntArg("queueDepthAfter", event.getInt("queueDepthAfter"), wroteArg);
            }
            if (event.hasField("ioEventsHandled")) {
                wroteArg = writeIntArg("ioEventsHandled", event.getInt("ioEventsHandled"), wroteArg);
            }
            if (event.hasField("tasksHandled")) {
                wroteArg = writeIntArg("tasksHandled", event.getInt("tasksHandled"), wroteArg);
            }

            writer.writeAscii("}}");
        }

        private void writeProcessName() throws IOException {
            writeEventPrefix();
            writer.writeAscii("{\"ph\":\"M\",\"name\":\"process_name\",\"pid\":");
            writer.writeLong(pid);
            writer.writeAscii(",\"args\":{\"name\":");
            writer.writeJsonString(PROCESS_NAME);
            writer.writeAscii("}}");
        }

        private void writeThreadNameIfNeeded(long tid, String name) throws IOException {
            if (!threadsSeen.add(tid)) {
                return;
            }
            writeEventPrefix();
            writer.writeAscii("{\"ph\":\"M\",\"name\":\"thread_name\",\"pid\":");
            writer.writeLong(pid);
            writer.writeAscii(",\"tid\":");
            writer.writeLong(tid);
            writer.writeAscii(",\"args\":{\"name\":");
            writer.writeJsonString(name);
            writer.writeAscii("}}");
        }

        private void writeEventPrefix() throws IOException {
            if (firstEvent) {
                firstEvent = false;
                return;
            }
            writer.writeAscii(",");
        }

        private boolean writeThreadArgs(String baseName, RecordedThread thread, boolean wroteArg) throws IOException {
            if (thread == null) {
                return wroteArg;
            }
            if (wroteArg) {
                writer.writeAscii(",");
            }
            writer.writeJsonString(baseName);
            writer.writeAscii(":");
            writer.writeJsonString(threadName(thread));
            writer.writeAscii(",");
            writer.writeJsonString(baseName + "Id");
            writer.writeAscii(":");
            writer.writeLong(threadId(thread));
            return true;
        }

        private boolean writeBooleanArg(String name, boolean value, boolean wroteArg) throws IOException {
            if (wroteArg) {
                writer.writeAscii(",");
            }
            writer.writeJsonString(name);
            writer.writeAscii(":");
            writer.writeAscii(value ? "true" : "false");
            return true;
        }

        private boolean writeIntArg(String name, int value, boolean wroteArg) throws IOException {
            if (wroteArg) {
                writer.writeAscii(",");
            }
            writer.writeJsonString(name);
            writer.writeAscii(":");
            writer.writeLong(value);
            return true;
        }

        @Override
        public void close() throws IOException {
            writer.writeAscii("]}");
            writer.close();
        }
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

    private static final class JsonWriter implements Closeable {
        private static final int BUFFER_SIZE = 1 << 16;
        private final OutputStream out;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private int position;

        JsonWriter(OutputStream out) {
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
