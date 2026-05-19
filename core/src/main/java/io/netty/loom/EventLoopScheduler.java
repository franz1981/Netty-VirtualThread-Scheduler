/*
 * Copyright 2026 The Netty VirtualThread Scheduler Project
 *
 * The Netty VirtualThread Scheduler Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package io.netty.loom;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.jctools.queues.MpscUnboundedArrayQueue;

import io.netty.loom.jfr.VirtualThreadTaskSubmitEvent;
import io.netty.loom.spi.NettyScheduler;

/**
 * A single-carrier virtual thread scheduler with an MPSC run queue. Virtual
 * threads created via {@link #virtualThreadFactory()} have affinity to this
 * carrier.
 *
 * <p>
 * Optionally hosts a <em>pinned poller</em> — a long-running VT that does
 * kernel I/O and cooperates with the carrier loop via
 * {@link #maybeYield(boolean)} and {@link #canBlock()}.
 */
public final class EventLoopScheduler {

	private static final VarHandle PINNED_POLLER_WAKEUP;
	private static final VarHandle CONSUMER_TICKET;
	private static final VarHandle CONSUMER_SERVING;
	private static final VarHandle SCHEDULER_HEARTBEAT;

	static {
		try {
			var lookup = MethodHandles.lookup();
			PINNED_POLLER_WAKEUP = lookup.findVarHandle(EventLoopScheduler.class, "pinnedPollerWakeup", Runnable.class);
			CONSUMER_TICKET = lookup.findVarHandle(EventLoopScheduler.class, "consumerTicket", int.class);
			CONSUMER_SERVING = lookup.findVarHandle(EventLoopScheduler.class, "consumerServing", int.class);
			SCHEDULER_HEARTBEAT = lookup.findVarHandle(EventLoopScheduler.class, "schedulerHeartbeat", long.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	static final long YIELD_DURATION_NS = TimeUnit.MICROSECONDS
			.toNanos(Integer.getInteger("io.netty.loom.yield.us", 10));
	private static final int OVERLOAD_QUEUE_THRESHOLD = Integer.getInteger("io.netty.loom.workstealing.overload.queue",
			10);
	static final boolean WORK_STEALING_ENABLED = Boolean
			.parseBoolean(System.getProperty("io.netty.loom.workstealing.enabled", "false"));
	private static final long UNRESPONSIVE_THRESHOLD_NS = TimeUnit.MILLISECONDS
			.toNanos(Long.getLong("io.netty.loom.workstealing.unresponsive.ms", 200));

	enum VThreadType {
		VT, JDK_POLLER, PINNED_POLLER
	}

	/**
	 * Per-VT scheduling context, accessed via two paths:
	 *
	 * <ul>
	 * <li><b>ScopedValue ({@link #scheduler()})</b> — public API for user code.
	 * Because ScopedValues are inherited by child threads, the thread ID check
	 * prevents a forked VT (e.g. in a {@code StructuredTaskScope} without our
	 * factory) from accidentally using the parent's scheduler. See
	 * {@code VirtualIoPollerEventLoopGroupTest.schedulerIsNotInheritedByForkedVT}.
	 * <li><b>VirtualThreadTask attachment ({@link #eventLoopScheduler})</b> —
	 * internal, used by {@code NettySchedulerProviderImpl.onStart/onContinue}. Safe
	 * to access directly (no ID check) because attachments are only set on
	 * continuations we explicitly created with our factory.
	 * </ul>
	 */
	static final class SchedulingContext {

		long vThreadId;
		final VThreadType type;
		final EventLoopScheduler eventLoopScheduler;
		// Set to the carrier that last mounted this VT (in runContinuation,
		// never cleared). Differs from eventLoopScheduler when the VT was
		// stolen. Used in execute() to skip self-wakeup and in
		// NettySchedulerProviderImpl to route JDK pollers to the running
		// carrier, not the home carrier.
		EventLoopScheduler runningScheduler;

		SchedulingContext(long vThreadId, EventLoopScheduler eventLoopScheduler, VThreadType type) {
			this.vThreadId = vThreadId;
			this.eventLoopScheduler = eventLoopScheduler;
			this.runningScheduler = eventLoopScheduler;
			this.type = type;
		}

		/**
		 * Returns the home scheduler for the current thread, or {@code null} if the
		 * calling thread is not the VT that owns this context.
		 */
		public EventLoopScheduler scheduler() {
			return (Thread.currentThread().threadId() == vThreadId) ? eventLoopScheduler : null;
		}

		/**
		 * Returns the scheduler of the carrier currently running this VT. Differs from
		 * {@link #scheduler()} when the VT was stolen by another carrier.
		 */
		public EventLoopScheduler runningScheduler() {
			if (Thread.currentThread().threadId() != vThreadId) {
				return null;
			}
			return runningScheduler;
		}
	}

	private static final ScopedValue<SchedulingContext> CURRENT_SCHEDULER = ScopedValue.newInstance();
	private static final SchedulingContext EMPTY_SCHEDULER_CONTEXT = new SchedulingContext(-1, null, VThreadType.VT);

	static void runWithContext(Runnable runnable, SchedulingContext schedulingContext) {
		ScopedValue.where(CURRENT_SCHEDULER, schedulingContext).run(runnable);
	}

	/**
	 * Returns the scheduler of the current virtual thread, or {@code null} if none.
	 */
	public static EventLoopScheduler currentScheduler() {
		return currentThreadSchedulerContext().scheduler();
	}

	static SchedulingContext currentThreadSchedulerContext() {
		return CURRENT_SCHEDULER.orElse(EMPTY_SCHEDULER_CONTEXT);
	}

	private final int id;
	private final MpscUnboundedArrayQueue<Thread.VirtualThreadTask> runQueue;
	private final Thread carrierThread;
	private final ThreadFactory vThreadFactory;
	private final ThreadFactory pinnedPollerThreadFactory;
	private volatile Thread parkedCarrierThread;
	private volatile Thread.VirtualThreadTask pinnedContinuationToRun;
	@SuppressWarnings("FieldMayBeFinal")
	private volatile Runnable pinnedPollerWakeup;
	@SuppressWarnings("FieldMayBeFinal")
	private volatile int consumerTicket;
	@SuppressWarnings("FieldMayBeFinal")
	private volatile int consumerServing;
	private volatile EventLoopScheduler[] siblings;
	private volatile AtomicBoolean pollerRunningFlag;
	@SuppressWarnings("FieldMayBeFinal")
	private long schedulerHeartbeat = System.nanoTime();

	EventLoopScheduler(int id, ThreadFactory threadFactory, int resumedContinuationsExpectedCount) {
		this.id = id;
		runQueue = new MpscUnboundedArrayQueue<>(resumedContinuationsExpectedCount);
		vThreadFactory = newVThreadFactory(this, VThreadType.VT);
		pinnedPollerThreadFactory = newVThreadFactory(this, VThreadType.PINNED_POLLER);
		carrierThread = threadFactory.newThread(this::virtualThreadSchedulerLoop);
		carrierThread.setDaemon(true);
		carrierThread.start();
	}

	/**
	 * Returns the index of this scheduler within the
	 * {@link EventLoopSchedulerGroup}.
	 */
	public int id() {
		return id;
	}

	/**
	 * Returns {@code true} if a pinned poller is currently registered on this
	 * carrier.
	 */
	boolean hasRegisteredPinnedPoller() {
		return pinnedPollerWakeup != null;
	}

	void setSiblings(EventLoopScheduler[] siblings) {
		this.siblings = siblings;
	}

	void setPollerRunningFlag(AtomicBoolean flag) {
		this.pollerRunningFlag = flag;
	}

	Thread.VirtualThreadTask tryStealOne() {
		int t = (int) CONSUMER_TICKET.getAcquire(this);
		if (t != (int) CONSUMER_SERVING.getAcquire(this)) {
			return null;
		}
		if (!CONSUMER_TICKET.compareAndSet(this, t, t + 1)) {
			return null;
		}
		try {
			return runQueue.poll();
		} finally {
			CONSUMER_SERVING.setRelease(this, t + 1);
		}
	}

	/**
	 * Registers a pinned poller on this carrier. The poller runs as a virtual
	 * thread with affinity to this carrier (not on the carrier thread itself). At
	 * most one poller per carrier; throws if one is already registered.
	 *
	 * <p>
	 * The poller must: (1) call {@link #maybeYield(boolean)} between phases to
	 * yield CPU time to external VTs, (2) use {@link #canBlock()} before entering
	 * blocking I/O and ensure no wakeup signal is lost in the race window between
	 * the check and the blocking call — either via a permit-based mechanism (sticky
	 * wakeup, e.g. eventfd or
	 * {@link java.util.concurrent.locks.LockSupport#unpark}) or a lock-based
	 * rendezvous where the check and wait are inside the same lock, and (3)
	 * eventually return from {@code body} so the slot can be freed.
	 *
	 * @param wakeup
	 *            called from any thread to interrupt the poller's blocking I/O;
	 *            must be thread-safe
	 * @param body
	 *            the poller loop; the slot is freed when this returns
	 * @return completes when {@code body} exits and the slot is freed
	 */
	public CompletionStage<Void> registerPinnedPoller(Runnable wakeup, Runnable body) {
		if (!PINNED_POLLER_WAKEUP.compareAndSet(this, null, wakeup)) {
			throw new IllegalStateException("poller already registered");
		}
		var termination = new CompletableFuture<Void>();
		var pollerThread = pinnedPollerThreadFactory.newThread(() -> {
			try {
				body.run();
			} finally {
				this.pinnedPollerWakeup = null;
				termination.complete(null);
			}
		});
		pollerThread.start();
		LockSupport.unpark(parkedCarrierThread);
		return termination;
	}

	/**
	 * Scheduling checkpoint for the pinned poller. Must be called between phases of
	 * the poller loop. Yields the carrier if external VTs have work queued, and
	 * updates the scheduler heartbeat so siblings can detect unresponsive carriers.
	 * When no I/O work was done, the scheduler may steal from an overloaded
	 * sibling.
	 *
	 * @param hadIoWork
	 *            true if the poller processed I/O events or tasks since the last
	 *            call; false if idle
	 */
	public boolean maybeYield(boolean hadIoWork) {
		touchHeartbeat(System.nanoTime());
		if (hasRunnableContinuations()) {
			Thread.yield();
			return true;
		}
		if (!hadIoWork && WORK_STEALING_ENABLED && tryStealing(false)) {
			Thread.yield();
			return true;
		}
		return false;
	}

	private void virtualThreadSchedulerLoop() {
		while (true) {
			int count = drainContinuations(YIELD_DURATION_NS);
			if (!runPinnedContinuation() && count == 0) {
				if (WORK_STEALING_ENABLED && tryStealing(true)) {
					continue;
				}
				parkedCarrierThread = carrierThread;
				if (canParkScheduler()) {
					LockSupport.park();
				}
				parkedCarrierThread = null;
			}
		}
	}

	private boolean hasRunnableContinuations() {
		return !runQueue.isEmpty();
	}

	private void touchHeartbeat(long nanos) {
		SCHEDULER_HEARTBEAT.setOpaque(this, nanos);
	}

	/**
	 * Pure query: returns {@code true} if no external work is pending. The poller
	 * should check this before entering blocking I/O — if false, poll non-blocking
	 * instead.
	 *
	 * <p>
	 * This is a snapshot — it can go stale immediately. Between this returning
	 * {@code true} and the actual blocking call, work may arrive and
	 * {@link #registerPinnedPoller wakeup} will fire. The blocking mechanism must
	 * handle this race (see {@link #registerPinnedPoller} for details).
	 */
	public boolean canBlock() {
		return !hasRunnableContinuations();
	}

	private static ThreadFactory newVThreadFactory(EventLoopScheduler scheduler, VThreadType type) {
		var unstartedBuilder = Thread.ofVirtual();
		NettyScheduler nettyScheduler = NettyScheduler.instance();
		return runnable -> {
			var schedulingContext = new SchedulingContext(-1, scheduler, type);
			var vTask = nettyScheduler.newThread(unstartedBuilder, null,
					() -> EventLoopScheduler.runWithContext(runnable, schedulingContext));
			schedulingContext.vThreadId = vTask.thread().threadId();
			vTask.attach(schedulingContext);
			return vTask.thread();
		};
	}

	/**
	 * Returns the number of virtual thread continuations waiting in the run queue.
	 */
	int runnableCount() {
		return runQueue.size();
	}

	/**
	 * Returns a factory that creates virtual threads with affinity to this carrier.
	 */
	public ThreadFactory virtualThreadFactory() {
		return vThreadFactory;
	}

	/** Returns the platform thread backing this carrier. */
	Thread carrierThread() {
		return carrierThread;
	}

	private boolean runPinnedContinuation() {
		assert Thread.currentThread() == carrierThread;
		var continuation = this.pinnedContinuationToRun;
		if (continuation != null) {
			this.pinnedContinuationToRun = null;
			runContinuation(continuation);
			return true;
		}
		return false;
	}

	private boolean canParkScheduler() {
		return !hasRunnableContinuations() && pinnedContinuationToRun == null;
	}

	private int drainContinuations(long deadlineNs) {
		var event = SchedulerJfrUtil.beginVirtualThreadTaskRunsEvent();
		final long startDrainingNs = System.nanoTime();
		touchHeartbeat(startDrainingNs);
		int queueDepthBefore = event != null ? runQueue.size() : 0;
		var ready = this.runQueue;
		int runContinuations = 0;
		for (;;) {
			Thread.VirtualThreadTask task;
			if (WORK_STEALING_ENABLED) {
				if (ready.isEmpty()) {
					break;
				}
				int ticket = acquireConsumer();
				task = ready.poll();
				releaseConsumer(ticket);
			} else {
				task = ready.poll();
			}
			if (task == null) {
				break;
			}
			runContinuations++;
			runContinuation(task);
			long nowNs = System.nanoTime();
			touchHeartbeat(nowNs);
			long elapsedNs = nowNs - startDrainingNs;
			if (elapsedNs >= deadlineNs) {
				break;
			}
		}
		if (event != null) {
			int queueDepthAfter = runQueue.size();
			SchedulerJfrUtil.commitVirtualThreadTaskRunsEvent(event, carrierThread, runContinuations, queueDepthBefore,
					queueDepthAfter);
		}
		return runContinuations;
	}

	private static boolean isPinnedPoller(Thread.VirtualThreadTask task) {
		return ((SchedulingContext) task.attachment()).type == VThreadType.PINNED_POLLER;
	}

	boolean execute(Thread.VirtualThreadTask task) {
		var currentThread = Thread.currentThread();
		var context = (SchedulingContext) task.attachment();
		boolean submitEventEnabled = VirtualThreadTaskSubmitEvent.isEventEnabled();
		boolean pinnedTask = false;
		if (isPinnedPoller(task) && pinnedContinuationToRun == null) {
			pinnedContinuationToRun = task;
			pinnedTask = true;
		}
		if (!pinnedTask) {
			if (!runQueue.offer(task)) {
				return false;
			}
		}
		if (submitEventEnabled) {
			SchedulerJfrUtil.commitVirtualThreadTaskSubmitEvent(task, currentThread, carrierThread,
					context.type == VThreadType.JDK_POLLER, pinnedTask);
		}
		if (currentThread != carrierThread) {
			if (EventLoopScheduler.currentThreadSchedulerContext().runningScheduler() != this) {
				wakeup();
			}
			if (WORK_STEALING_ENABLED && needsHelp(System.nanoTime())) {
				wakeIdleSibling();
			}
		}
		return true;
	}

	void wakeup() {
		var poller = pinnedPollerWakeup;
		if (poller != null) {
			poller.run();
		}
		LockSupport.unpark(parkedCarrierThread);
	}

	private boolean tryWakeup() {
		var poller = pinnedPollerWakeup;
		if (poller != null) {
			var flag = pollerRunningFlag;
			if (flag != null && !flag.get()) {
				poller.run();
				return true;
			}
			return false;
		}
		Thread parked = parkedCarrierThread;
		if (parked != null) {
			LockSupport.unpark(parked);
			return true;
		}
		return false;
	}

	private boolean isUnresponsive(long nowNanos) {
		return (nowNanos - (long) SCHEDULER_HEARTBEAT.getOpaque(this)) > UNRESPONSIVE_THRESHOLD_NS;
	}

	boolean needsHelp(long nowNanos) {
		return isUnresponsive(nowNanos) || runnableCount() > OVERLOAD_QUEUE_THRESHOLD;
	}

	private void wakeIdleSibling() {
		var siblings = this.siblings;
		if (siblings == null) {
			return;
		}
		int len = siblings.length;
		var rng = ThreadLocalRandom.current();
		int a = rng.nextInt(len);
		if (len == 1) {
			siblings[a].tryWakeup();
			return;
		}
		int b = rng.nextInt(len - 1);
		if (b >= a) {
			b++;
		}
		if (!siblings[a].tryWakeup()) {
			siblings[b].tryWakeup();
		}
	}

	private int acquireConsumer() {
		int myTicket = (int) CONSUMER_TICKET.getAndAdd(this, 1);
		while ((int) CONSUMER_SERVING.getAcquire(this) != myTicket) {
			Thread.onSpinWait();
		}
		return myTicket;
	}

	private void releaseConsumer(int ticket) {
		CONSUMER_SERVING.setRelease(this, ticket + 1);
	}

	private boolean tryStealing(boolean fromCarrierLoop) {
		var siblings = this.siblings;
		if (siblings == null) {
			return false;
		}
		long now = System.nanoTime();
		int len = siblings.length;
		var rng = ThreadLocalRandom.current();
		int a = rng.nextInt(len);
		EventLoopScheduler victim;
		if (len == 1) {
			victim = siblings[a].needsHelp(now) ? siblings[a] : null;
		} else {
			int b = rng.nextInt(len - 1);
			if (b >= a) {
				b++;
			}
			var sa = siblings[a];
			var sb = siblings[b];
			boolean helpA = sa.needsHelp(now);
			boolean helpB = sb.needsHelp(now);
			if (helpA && helpB) {
				victim = sa.runnableCount() >= sb.runnableCount() ? sa : sb;
			} else {
				victim = helpA ? sa : helpB ? sb : null;
			}
		}
		if (victim == null || !victim.hasRunnableContinuations()) {
			return false;
		}
		var task = victim.tryStealOne();
		if (task != null) {
			var event = SchedulerJfrUtil.beginWorkStealEvent();
			int sourceQueueDepth = event != null ? victim.runnableCount() : 0;
			runQueue.offer(task);
			if (event != null) {
				SchedulerJfrUtil.commitWorkStealEvent(event, task, victim.carrierThread, carrierThread,
						sourceQueueDepth, fromCarrierLoop);
			}
			return true;
		}
		return false;
	}

	private void runContinuation(Thread.VirtualThreadTask task) {
		var context = (SchedulingContext) task.attachment();
		context.runningScheduler = this;
		var event = SchedulerJfrUtil.beginVirtualThreadTaskRunEvent();
		if (event == null) {
			task.run();
			return;
		}
		boolean isPinned = context.type == VThreadType.PINNED_POLLER;
		boolean isPoller = context.type == VThreadType.JDK_POLLER;
		task.run();
		SchedulerJfrUtil.commitVirtualThreadTaskRunEvent(event, carrierThread, task.thread(), isPoller, isPinned);
	}
}
