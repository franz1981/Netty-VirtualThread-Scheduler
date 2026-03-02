package io.netty.loom;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class AffinityTest {

    private static final MethodHandle CURRENT_CARRIER;

    static {
        try {
            CURRENT_CARRIER = MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup()).findStatic(Thread.class, "currentCarrierThread", MethodType.methodType(Thread.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Thread currentCarrier() {
        try {
            return (Thread) CURRENT_CARRIER.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    enum ParkStrategy {
        YIELD,
        TIMED_PARK,
        MIXED
    }

    private static void park(ParkStrategy strategy, long iteration) {
        switch (strategy) {
            case YIELD -> Thread.yield();
            case TIMED_PARK -> LockSupport.parkNanos(1);
            case MIXED -> {
                if ((iteration & 1) == 0) {
                    Thread.yield();
                } else {
                    LockSupport.parkNanos(1);
                }
            }
        }
    }

    private record VThreadStats(IdentityHashMap<Thread, AtomicLong> carrierAttempts,
                                 AtomicLong totalSwitches,
                                 AtomicLong totalIterations) {
        VThreadStats() {
            this(new IdentityHashMap<>(), new AtomicLong(), new AtomicLong());
        }
    }

    static Stream<Arguments> parameters() {
        var strategies = ParkStrategy.values();
        var builder = Stream.<Arguments>builder();
        for (var strategy : strategies) {
            builder.add(Arguments.of(strategy, true));
            builder.add(Arguments.of(strategy, false));
        }
        return builder.build();
    }

    @ParameterizedTest(name = "{0} affinity={1}")
    @MethodSource("parameters")
    public void testAffinityConvergence(ParkStrategy strategy, boolean affinity) throws InterruptedException {
        int n = Runtime.getRuntime().availableProcessors();
        var builder = Thread.ofVirtual();
        if (affinity) {
            builder.roundRobinAffinity();
        }
        var factory = builder.factory();
        Map<Thread, VThreadStats> perVThread = new ConcurrentHashMap<>();
        var stop = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            threads.add(factory.newThread(() -> {
                var stats = new VThreadStats();
                perVThread.put(Thread.currentThread(), stats);
                for (long iter = 0; !stop.get(); iter++) {
                    Thread beforePark = currentCarrier();
                    park(strategy, iter);
                    long iterations = stats.totalIterations.get() + 1;
                    stats.totalIterations.lazySet(iterations);
                    Thread afterPark = currentCarrier();
                    var counter = stats.carrierAttempts.computeIfAbsent(afterPark, t -> new AtomicLong());
                    counter.lazySet(counter.get() + 1);
                    if (afterPark != beforePark) {
                        stats.totalSwitches.lazySet(stats.totalSwitches.get() + 1);
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        Thread.sleep(10_000);
        stop.set(true);
        for (var thread : threads) {
            thread.join(5_000);
        }

        System.out.println("Strategy: " + strategy + ", affinity=" + affinity + " (" + n + " virtual threads, "
                + perVThread.size() + " reported)");
        long globalSwitches = 0;
        long globalIterations = 0;
        for (var entry : perVThread.entrySet()) {
            var stats = entry.getValue();
            long iterations = stats.totalIterations.get();
            if (stats.carrierAttempts.isEmpty()) {
                System.out.printf("%s => never scheduled%n", entry.getKey().getName());
                continue;
            }
            var dominant = stats.carrierAttempts.entrySet().stream()
                    .max(Map.Entry.comparingByValue((a, b) -> Long.compare(a.get(), b.get())))
                    .orElseThrow();
            long switches = stats.totalSwitches.get();
            globalSwitches += switches;
            globalIterations += iterations;
            System.out.printf("%s => dominant: %s (%d/%d = %.4f%% affinity, %d carriers used, switches: %d/%d = %.6f)%n",
                    entry.getKey().getName(),
                    dominant.getKey().getName(),
                    dominant.getValue().get(), iterations,
                    iterations > 0 ? 100.0 * dominant.getValue().get() / iterations : 0.0,
                    stats.carrierAttempts.size(),
                    switches, iterations,
                    iterations > 0 ? (double) switches / iterations : 0.0);
        }
        System.out.printf("%nGlobal switches: %d / %d = %.6f%n%n", globalSwitches, globalIterations,
                globalIterations > 0 ? (double) globalSwitches / globalIterations : 0.0);
        System.out.flush();
    }

}
