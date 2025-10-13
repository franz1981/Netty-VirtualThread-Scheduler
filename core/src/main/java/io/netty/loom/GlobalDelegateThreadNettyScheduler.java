package io.netty.loom;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Global scheduler — provides execution for virtual threads that do not specify their own scheduler.
 * Note: this is not a real scheduler, but merely a proxy/dispatcher for schedulers.
 * 1. Platform threads → delegate to the built-in ForkJoin scheduler.
 * 2. Virtual threads derived from a VirtualThreadNettyScheduler → continue using that same VirtualThreadNettyScheduler.
 * 3. Other virtual threads → use the default JDK-provided scheduler.
 * 4. Virtual threads spawned by case (2) → still use the originating VirtualThreadNettyScheduler.
 * 5. Poller:
 *      - VTHREAD_POLLERS → not relevant here; simply use the JDK’s built-in scheduler.
 *      - POLLER_PER_CARRIER → initialized lazily upon first network I/O usage,
 *        so as long as this GlobalDelegateThreadNettyScheduler correctly “inherits”
 *        the actual underlying scheduler, it will work properly.
 */

public class GlobalDelegateThreadNettyScheduler implements Thread.VirtualThreadScheduler {

    final Thread.VirtualThreadScheduler jdkBuildinScheduler;

    final ConcurrentHashMap<Thread, Thread.VirtualThreadScheduler> internalSchedulerMappings = new ConcurrentHashMap<>();

    public GlobalDelegateThreadNettyScheduler(Thread.VirtualThreadScheduler jdkBuildinScheduler) {
        this.jdkBuildinScheduler = jdkBuildinScheduler;
    }

    @Override
    public void execute(Thread vthread, Runnable task) {

        // When scheduling virtual threads, `GlobalDelegateThreadNettyScheduler::execute` will not be invoked concurrently for the same virtual thread,
        // so it’s safe to replace `computeIfAbsent` with a `get + put` approach to reduce overhead.
        Thread.VirtualThreadScheduler internalScheduler = internalSchedulerMappings.get(vthread);
        if (internalScheduler == null) {
            internalScheduler = determineScheduler(vthread);
            internalSchedulerMappings.put(vthread, internalScheduler);
        }

        internalScheduler.execute(vthread, () -> {
            try {
                task.run();
            } finally {
                if (vthread.getState() == Thread.State.TERMINATED) {
                    internalSchedulerMappings.remove(vthread);
                }
            }
        });
    }

    private Thread.VirtualThreadScheduler determineScheduler(Thread thread) {
        // platform thread
        if (!Thread.currentThread().isVirtual()) {
            return jdkBuildinScheduler;
        }
        VirtualThreadNettyScheduler current = VirtualThreadNettyScheduler.current();
        // The current thread was spawned from a specific VirtualThreadNettyScheduler,
        // so we continue using that scheduler.
        if (current != null) {
            return current;
        }
        Thread callerThread = Thread.currentThread();
        Thread.VirtualThreadScheduler parentScheduler = internalSchedulerMappings.get(callerThread);
        if (parentScheduler != null) {
            return parentScheduler;
        }

        // The current thread was spawned from an unknown scheduler that is not managed by GlobalDelegateThreadNettyScheduler,
        // so we directly use the parent’s scheduler instead to avoid potential stack overflow.
        return VirtualThreadNettyScheduler.current();
    }
}

