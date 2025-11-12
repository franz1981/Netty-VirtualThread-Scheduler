package io.netty.loom;

import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global scheduler — provides execution for virtual threads that do not specify their own scheduler.
 * Note: this is not a real scheduler, but merely a proxy/dispatcher for schedulers.
 * 1. Platform threads → delegate to the built-in ForkJoin scheduler.
 * 2. Virtual threads derived from a VirtualThreadNettyScheduler → continue using that same VirtualThreadNettyScheduler.
 * 3. Other virtual threads → use the default JDK-provided scheduler.
 * 4. Virtual threads spawned by case (2) → still use the originating VirtualThreadNettyScheduler.
 * 5. Poller:
 * - VTHREAD_POLLERS → not relevant here; simply use the JDK’s built-in scheduler.
 * - POLLER_PER_CARRIER → initialized lazily upon first network I/O usage,
 * so as long as this GlobalDelegateThreadNettyScheduler correctly “inherits”
 * the actual underlying scheduler, it will work properly.
 */

public class GlobalDelegateThreadNettyScheduler implements Thread.VirtualThreadScheduler {

    private static GlobalDelegateThreadNettyScheduler INSTANCE;

    final Thread.VirtualThreadScheduler jdkBuildinScheduler;

    final ConcurrentHashMap<Thread, AtomicReference<VirtualThreadNettyScheduler>> unstartedThreads = new ConcurrentHashMap<>();

    public GlobalDelegateThreadNettyScheduler(Thread.VirtualThreadScheduler jdkBuildinScheduler) {
        this.jdkBuildinScheduler = jdkBuildinScheduler;
        INSTANCE = this;
        VarHandle.storeStoreFence();
    }

    @Override
    public void onStart(Thread.VirtualThreadTask virtualThreadTask) {
        // TODO this is not great for 2 reasons:
        // 1. we are doing a remove on a concurrent map even for v threads which are not really interesting to us
        // 2. if a vThread will never start, it will leak here forever
        var assignedSchedulerRef = unstartedThreads.remove(virtualThreadTask.thread());
        if (assignedSchedulerRef == null) {
            // TODO per carrier sub-pollers goes here, but we want them to inherit the scheduler from the caller context
        }
        var scheduler = assignedSchedulerRef != null ? assignedSchedulerRef.get() : null;
        if (scheduler != null) {
            // attach the assigned scheduler to the task
            virtualThreadTask.attach(assignedSchedulerRef);
            if (scheduler.execute(virtualThreadTask)) {
                return;
            }
            // the v thread has been rejected by its assigned scheduler, clean it up and fallback to JDK
            virtualThreadTask.attach(null);
        }
        jdkBuildinScheduler.onStart(virtualThreadTask);
    }

    @Override
    public void onContinue(Thread.VirtualThreadTask virtualThreadTask) {

    }

    // just for benchmark
    public Thread.VirtualThreadScheduler getJdkBuildinScheduler() {
        return jdkBuildinScheduler;
    }

    static Thread assignUnstarted(Thread unstarted, AtomicReference<VirtualThreadNettyScheduler> schedulerRef) {
        INSTANCE.unstartedThreads.put(unstarted, schedulerRef);
        return unstarted;
    }
}

