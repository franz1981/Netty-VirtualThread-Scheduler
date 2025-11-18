package io.netty.loom;

import io.netty.loom.EventLoopScheduler.SharedRef;

import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global scheduler — provides execution for virtual threads that do not specify
 * their own scheduler. Note: this is not a real scheduler, but merely a
 * proxy/dispatcher for schedulers. 1. Platform threads → delegate to the
 * built-in ForkJoin scheduler. 2. Virtual threads derived from a
 * VirtualThreadNettyScheduler → continue using that same
 * VirtualThreadNettyScheduler. 3. Other virtual threads → use the default
 * JDK-provided scheduler. 4. Virtual threads spawned by case (2) → still use
 * the originating VirtualThreadNettyScheduler. 5. Poller: - VTHREAD_POLLERS →
 * not relevant here; simply use the JDK’s built-in scheduler. -
 * POLLER_PER_CARRIER → initialized lazily upon first network I/O usage, so as
 * long as this GlobalDelegateThreadNettyScheduler correctly “inherits” the
 * actual underlying scheduler, it will work properly.
 */

public class NettyScheduler implements Thread.VirtualThreadScheduler {

	private static volatile NettyScheduler INSTANCE;

	private final Thread.VirtualThreadScheduler jdkBuildinScheduler;

	private final ConcurrentHashMap<Thread, SharedRef> unstartedThreads = new ConcurrentHashMap<>();

	private final boolean perCarrierPollers;

	private static NettyScheduler ensureInstalled() {
		var instance = INSTANCE;
		if (instance != null) {
			return instance;
		}
		Thread.ofVirtual().unstarted(new Runnable() {
			@Override
			public void run() {

			}
		});
		// we expect VirtualThread clinit to have loaded it by now
		return INSTANCE;
	}

	public NettyScheduler(Thread.VirtualThreadScheduler jdkBuildinScheduler) {
		this.jdkBuildinScheduler = jdkBuildinScheduler;
		perCarrierPollers = Integer.getInteger("jdk.pollerMode", -1) == 3;
		INSTANCE = this;
	}

	public boolean expectsPerCarrierPollers() {
		return perCarrierPollers;
	}

	Thread.VirtualThreadScheduler jdkBuildinScheduler() {
		return jdkBuildinScheduler;
	}

	@Override
	public void onStart(Thread.VirtualThreadTask virtualThreadTask) {
		// TODO this is not great for 2 reasons:
		// 1. we are doing a remove on a concurrent map even for v threads which are not
		// really interesting to us
		// 2. if a vThread will never start, it will leak here forever
		// HINT: if we had a VirtualThreadTask::Of(VirtualThread) method, we could
		// perform the assignment BEFORE calling this
		// on the vThread factory
		// or the vThreadFactory could provide in its build method something to access
		// the VirtualThreadTask of an unstarted VirtualThread
		var assignedSchedulerRef = unstartedThreads.remove(virtualThreadTask.thread());
		if (assignedSchedulerRef == null) {
			if (perCarrierPollers) {
				// Read-Poller threads should always inherit the event loop scheduler from the
				// caller thread
				if (Thread.currentThread().isVirtual()) {
					// TODO
					// https://github.com/openjdk/loom/blob/12ddf39bb59252a8274d8b937bd075b2a6dbc3f8/src/java.base/share/classes/java/lang/VirtualThread.java#L270C18-L270C33
					// in theory should be easy to provide a VirtualThreadTask::current method to
					// avoid the ScopedValue lookup
					var schedulerRef = EventLoopScheduler.currentThreadSchedulerContext().scheduler();
					// See
					// https://github.com/openjdk/loom/blob/12ddf39bb59252a8274d8b937bd075b2a6dbc3f8/src/java.base/share/classes/sun/nio/ch/Poller.java#L723C48-L723C59
					if (schedulerRef != null) {
						var scheduler = schedulerRef.get();
						if (scheduler != null && virtualThreadTask.thread().getName().endsWith("-Read-Poller")) {
							virtualThreadTask.attach(schedulerRef);
							if (scheduler.execute(virtualThreadTask)) {
								return;
							}
							virtualThreadTask.attach(null);
						}
					}
				}
			}
		} else {
			var scheduler = assignedSchedulerRef.get();
			if (scheduler != null) {
				// attach the assigned scheduler to the task
				virtualThreadTask.attach(assignedSchedulerRef);
				if (scheduler.execute(virtualThreadTask)) {
					return;
				}
			}
			// the v thread has been rejected by its assigned scheduler or its scheduler is
			// gone
			virtualThreadTask.attach(null);
		}
		jdkBuildinScheduler.onStart(virtualThreadTask);
	}

	@Override
	public void onContinue(Thread.VirtualThreadTask virtualThreadTask) {
		var attachment = virtualThreadTask.attachment();
		if (attachment instanceof SharedRef ref) {
			var assignedScheduler = ref.get();
			if (assignedScheduler != null) {
				if (assignedScheduler.execute(virtualThreadTask)) {
					return;
				}
			}
			// the v thread has been rejected by its assigned scheduler or its scheduler is
			// gone
			virtualThreadTask.attach(null);
		}
		jdkBuildinScheduler.onContinue(virtualThreadTask);
	}

	static Thread assignUnstarted(Thread unstarted, SharedRef ref) {
		ensureInstalled().unstartedThreads.put(unstarted, ref);
		return unstarted;
	}

	public static boolean perCarrierPollers() {
		return ensureInstalled().perCarrierPollers;
	}

	public static boolean isAvailable() {
		return ensureInstalled() != null;
	}
}
