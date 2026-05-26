//usr/bin/env jbang

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses bpftrace wakeup trace output and produces a human-readable summary.
 *
 * Usage: jbang SummarizeWakeupTrace.java <server-threads.txt> <wakeup-trace.txt>
 *
 * Input 1: jcmd Thread.print dump for all benchmark components (server + mock).
 *          Sections separated by "=== Component (PID: N) ===" headers.
 * Input 2: bpftrace aggregation output with @by_waker_target and @by_stack maps.
 *          Format: @by_waker_target[waker_comm, waker_pid, target_tid]: count
 *                  @by_stack[waker_comm, waker_pid, target_tid, <kernel stack>]: count
 *
 * Wakeup types classified by kernel stack frames:
 *   eventfd_write       → eventfd (Master-Poller submitting VT, wakeIdleSibling)
 *   sock_def_readable   → network (TCP packet on epoll fd)
 *   __wake_up_sync      → network (socket wakeup path)
 *   futex_wake          → futex (JVM internal)
 *   hrtimer_wakeup      → timer
 */
public class SummarizeWakeupTrace {

    enum WakeupType { EVENTFD, NETWORK, FUTEX, TIMER, OTHER }

    record WakerKey(String name, String pid) {
        @Override public String toString() { return name + "[" + pid + "]"; }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: jbang SummarizeWakeupTrace.java <server-threads.txt> <wakeup-trace.txt>");
            System.exit(1);
        }

        var tidToName = parseThreadDump(Path.of(args[0]));
        var pidToComponent = parsePidComponents(Path.of(args[0]));
        var traceLines = Files.readAllLines(Path.of(args[1]));

        var targetTotals = new LinkedHashMap<String, Long>();
        var targetByType = new HashMap<String, EnumMap<WakeupType, Long>>();
        var wakerTargetCounts = new HashMap<String, Long>();
        long grandTotal = 0;

        // Parse @by_waker_target[waker_comm, waker_pid, target_tid]: count
        var wtPattern = Pattern.compile("@by_waker_target\\[([^,]+), (\\d+), (\\d+)\\]: (\\d+)");
        for (String line : traceLines) {
            Matcher m = wtPattern.matcher(line);
            if (m.matches()) {
                String wakerComm = m.group(1);
                String wakerPid = m.group(2);
                String targetTid = m.group(3);
                long count = Long.parseLong(m.group(4));

                String targetName = tidToName.getOrDefault(targetTid, targetTid);
                String wakerLabel = resolveWaker(wakerComm, wakerPid, pidToComponent, tidToName);

                targetTotals.merge(targetName, count, Long::sum);
                wakerTargetCounts.merge(wakerLabel + " → " + targetName, count, Long::sum);
                grandTotal += count;
            }
        }

        // Parse @by_stack[waker_comm, waker_pid, target_tid, <stack>]: count
        var stackStartPattern = Pattern.compile("@by_stack\\[([^,]+), (\\d+), (\\d+),");
        var stackEndPattern = Pattern.compile("\\]: (\\d+)");
        String curTargetTid = null;
        boolean inStack = false;
        var curStack = new StringBuilder();

        for (String line : traceLines) {
            Matcher startM = stackStartPattern.matcher(line);
            if (startM.find()) {
                curTargetTid = startM.group(3);
                inStack = true;
                curStack.setLength(0);
                continue;
            }

            if (inStack) {
                Matcher endM = stackEndPattern.matcher(line);
                if (endM.find()) {
                    long count = Long.parseLong(endM.group(1));
                    String name = tidToName.getOrDefault(curTargetTid, curTargetTid);
                    WakeupType type = classifyStack(curStack.toString());

                    targetByType
                            .computeIfAbsent(name, k -> new EnumMap<>(WakeupType.class))
                            .merge(type, count, Long::sum);
                    inStack = false;
                } else {
                    curStack.append(' ').append(line.trim());
                }
            }
        }

        // Print summary table
        System.out.printf("=== Wakeup Summary (%d total) ===%n%n", grandTotal);
        System.out.printf("%-30s %8s %8s %8s %8s %8s %8s%n",
                "Target thread", "total", "eventfd", "network", "futex", "timer", "other");
        System.out.printf("%-30s %8s %8s %8s %8s %8s %8s%n",
                "-----", "-----", "-----", "-----", "-----", "-----", "-----");

        targetTotals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> {
                    String name = e.getKey();
                    var types = targetByType.getOrDefault(name, new EnumMap<>(WakeupType.class));
                    System.out.printf("%-30s %8d %8d %8d %8d %8d %8d%n", name,
                            e.getValue(),
                            types.getOrDefault(WakeupType.EVENTFD, 0L),
                            types.getOrDefault(WakeupType.NETWORK, 0L),
                            types.getOrDefault(WakeupType.FUTEX, 0L),
                            types.getOrDefault(WakeupType.TIMER, 0L),
                            types.getOrDefault(WakeupType.OTHER, 0L));
                });

        // Print top waker→target pairs
        System.out.printf("%n=== Top Waker → Target ===%n%n");
        wakerTargetCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(15)
                .forEach(e -> System.out.printf("  %8d  %s%n", e.getValue(), e.getKey()));
    }

    static String resolveWaker(String comm, String pid, Map<String, String> pidToComponent,
                               Map<String, String> tidToName) {
        String component = pidToComponent.get(pid);
        if (component != null) {
            return comm + " (" + component + ")";
        }
        return comm + " [" + pid + "]";
    }

    static WakeupType classifyStack(String stack) {
        if (stack.contains("eventfd_write")) return WakeupType.EVENTFD;
        if (stack.contains("sock_def_readable")) return WakeupType.NETWORK;
        if (stack.contains("__wake_up_sync")) return WakeupType.NETWORK;
        if (stack.contains("futex_wake")) return WakeupType.FUTEX;
        if (stack.contains("hrtimer_wakeup")) return WakeupType.TIMER;
        return WakeupType.OTHER;
    }

    static Map<String, String> parseThreadDump(Path path) throws IOException {
        var tidToName = new HashMap<String, String>();
        var bracketPattern = Pattern.compile("^\"([^\"]+)\".*\\[(\\d+)\\]");
        var nidPattern = Pattern.compile("^\"([^\"]+)\".*nid=(\\d+)");

        for (String line : Files.readAllLines(path)) {
            Matcher m = bracketPattern.matcher(line);
            if (m.find()) {
                tidToName.put(m.group(2), m.group(1));
                continue;
            }
            m = nidPattern.matcher(line);
            if (m.find()) {
                tidToName.put(m.group(2), m.group(1));
            }
        }
        return tidToName;
    }

    static Map<String, String> parsePidComponents(Path path) throws IOException {
        var pidToComponent = new HashMap<String, String>();
        var headerPattern = Pattern.compile("=== (\\w+) \\(PID: (\\d+)\\) ===");

        for (String line : Files.readAllLines(path)) {
            Matcher m = headerPattern.matcher(line);
            if (m.find()) {
                pidToComponent.put(m.group(2), m.group(1));
            }
        }
        return pidToComponent;
    }
}
