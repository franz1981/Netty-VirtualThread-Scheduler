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
package io.netty.loom.scheduler;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.jctools.queues.MpscUnboundedArrayQueue;

import io.netty.loom.scheduler.jfr.VirtualThreadTaskSubmitEvent;

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
	private static final VarHandle CARRIER_STATE;

	// @formatter:off
	/*
	 * Carrier park/wake protocol (Viktor Klang mini-actor pattern adapted for
	 * park/unpark with work-stealing signals).
	 *
	 * States: RUNNING(0), PARKED(1), SEARCHING+victimId(2+id).
	 * SEARCHING encodes a directed-steal hint: the carrier should steal
	 * from group.scheduler(wakeState - SEARCHING).
	 *
	 * Entry to idle (carrier thread):
	 *   CAS(RUNNING, PARKED)        -- seq_cst, provides StoreLoad
	 *   markIdle(id)                -- publish in bitmap for wakeFirstIdle
	 *   if (canParkScheduler())     -- RE-CHECK: catches queue/pinned signals
	 *       LockSupport.park()      --   that arrived between last drain and CAS
	 *
	 * Exit from idle (carrier thread):
	 *   markActive(id)
	 *   getAndSet(RUNNING)          -- ATOMIC read+reset, no TOCTOU window
	 *   handle consumed state       -- SEARCHING+X -> directed steal, else drain
	 *
	 * Signals from other threads:
	 *   wakeup():           CAS(PARKED, RUNNING) + unpark
	 *   wakeupAsSearcher(): CAS(PARKED, SEARCHING+X) + unpark
	 *   Both fail if state != PARKED (carrier already waking or running).
	 *
	 * How each signal is caught:
	 *   queue/pinned work -> canParkScheduler re-check (avoids park)
	 *   search request    -> unpark permit + getAndSet at exit
	 *
	 * nSearching (per-cluster, Go wakep-style):
	 *   signalWorkFor: CAS 0->1, wakeFirstIdle, if no idle -> stoppedSearching
	 *   carrier:       searching=true on SEARCHING wake, resetSearching on
	 *                  steal completion or before parking -> stoppedSearching
	 *   Invariant: every tryStartSearcher success is matched by exactly one
	 *   stoppedSearching -- either from signalWorkFor (no idle found) or from
	 *   the woken carrier (resetSearching).
	 */
	// @formatter:on
	private static final int RUNNING = 0;
	private static final int PARKED = 1;
	private static final int SEARCHING = 2;

	static {
		try {
			var lookup = MethodHandles.lookup();
			PINNED_POLLER_WAKEUP = lookup.findVarHandle(EventLoopScheduler.class, "pinnedPollerWakeup",
					BooleanSupplier.class);
			CONSUMER_TICKET = lookup.findVarHandle(EventLoopScheduler.class, "consumerTicket", int.class);
			CONSUMER_SERVING = lookup.findVarHandle(EventLoopScheduler.class, "consumerServing", int.class);
			SCHEDULER_HEARTBEAT = lookup.findVarHandle(EventLoopScheduler.class, "schedulerHeartbeat", long.class);
			CARRIER_STATE = lookup.findVarHandle(EventLoopScheduler.class, "carrierState", int.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	public static final long YIELD_DURATION_NS = TimeUnit.MICROSECONDS
			.toNanos(Integer.getInteger("io.netty.loom.yield.us", 50));
	private static final int WAKE_QUEUE_THRESHOLD = Integer.getInteger("io.netty.loom.workstealing.wake.queue", 8);
	private static final int STEAL_QUEUE_THRESHOLD = Integer.getInteger("io.netty.loom.workstealing.steal.queue", 2);
	static final boolean WORK_STEALING_ENABLED = Boolean
			.parseBoolean(System.getProperty("io.netty.loom.workstealing.enabled", "false"));
	private static final long UNRESPONSIVE_THRESHOLD_NS = TimeUnit.MICROSECONDS
			.toNanos(Long.getLong("io.netty.loom.workstealing.unresponsive.us", 200_000));
	private static final boolean ALWAYS_UNRESPONSIVE = WORK_STEALING_ENABLED && UNRESPONSIVE_THRESHOLD_NS == 0;
	private static final int IDLE_SPINS = Integer.getInteger("io.netty.loom.idleSpinsBeforePark", 0);

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
	 * {@code VirtualIoNativePollerEventLoopGroupTest.schedulerIsNotInheritedByForkedVT}.
	 * <li><b>VirtualThreadTask attachment ({@link #eventLoopScheduler})</b> —
	 * internal, used by {@code NettyScheduler.onStart/onContinue}. Safe to access
	 * directly (no ID check) because attachments are only set on continuations we
	 * explicitly created with our factory.
	 * </ul>
	 */
	static final class SchedulingContext {

		long vThreadId;
		final VThreadType type;
		final EventLoopScheduler eventLoopScheduler;
		// Set to the carrier that last mounted this VT (in runContinuation,
		// never cleared). Differs from eventLoopScheduler when the VT was
		// stolen. Used in execute() to skip self-wakeup and in
		// NettyScheduler.onStart to route JDK pollers to the running
		// carrier, not the home carrier.
		EventLoopScheduler runningScheduler;

		SchedulingContext(long vThreadId, EventLoopScheduler eventLoopScheduler, VThreadType type) {
			this.vThreadId = vThreadId;
			this.eventLoopScheduler = eventLoopScheduler;
			this.runningScheduler = eventLoopScheduler;
			this.type = type;
		}

		void setVThreadId(long id) {
			this.vThreadId = id;
		}

		EventLoopScheduler assignedScheduler() {
			return eventLoopScheduler;
		}

		VThreadType type() {
			return type;
		}

		void mountedOn(EventLoopScheduler carrier) {
			this.runningScheduler = carrier;
		}

		/**
		 * Returns the home scheduler for the current thread, or {@code null} if the
		 * calling thread is not the VT that owns this context.
		 */
		EventLoopScheduler scheduler() {
			return (Thread.currentThread().threadId() == vThreadId) ? eventLoopScheduler : null;
		}

		/**
		 * Returns the scheduler of the carrier currently running this VT. Differs from
		 * {@link #scheduler()} when the VT was stolen by another carrier.
		 */
		EventLoopScheduler runningScheduler() {
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

	/**
	 * Returns the scheduler of the carrier currently running the calling virtual
	 * thread. Differs from {@link #currentScheduler()} when the VT was stolen by
	 * another carrier.
	 */
	public static EventLoopScheduler currentRunningScheduler() {
		return currentThreadSchedulerContext().runningScheduler();
	}

	static SchedulingContext currentThreadSchedulerContext() {
		return CURRENT_SCHEDULER.orElse(EMPTY_SCHEDULER_CONTEXT);
	}

	private final int id;
	private final MpscUnboundedArrayQueue<Thread.VirtualThreadTask> runQueue;
	private final Thread carrierThread;
	private final ThreadFactory vThreadFactory;
	private final ThreadFactory pinnedPollerThreadFactory;
	@SuppressWarnings("FieldMayBeFinal")
	private volatile int carrierState;
	private volatile Thread.VirtualThreadTask pinnedContinuationToRun;
	@SuppressWarnings("FieldMayBeFinal")
	private volatile BooleanSupplier pinnedPollerWakeup;
	@SuppressWarnings("FieldMayBeFinal")
	private volatile int consumerTicket;
	@SuppressWarnings("FieldMayBeFinal")
	private volatile int consumerServing;
	private volatile EventLoopScheduler[] siblings;
	EventLoopSchedulerGroup group;
	ClusterState clusterState;
	private boolean searching;
	@SuppressWarnings("FieldMayBeFinal")
	private long schedulerHeartbeat = System.nanoTime();
	private Runnable onCarrierStart;

	EventLoopScheduler(int id, ThreadFactory threadFactory, int resumedContinuationsExpectedCount,
			NettyScheduler nettyScheduler, Runnable onCarrierStart) {
		this.id = id;
		this.onCarrierStart = onCarrierStart;
		runQueue = new MpscUnboundedArrayQueue<>(resumedContinuationsExpectedCount);
		vThreadFactory = newVThreadFactory(this, VThreadType.VT, nettyScheduler);
		pinnedPollerThreadFactory = newVThreadFactory(this, VThreadType.PINNED_POLLER, nettyScheduler);
		carrierThread = threadFactory.newThread(this::virtualThreadSchedulerLoop);
		carrierThread.setName("carrier-" + id);
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
	 *            must be thread-safe. Must return {@code true} if the poller was
	 *            observed sleeping (signal sent), {@code false} if it was actively
	 *            running (signal suppressed). The scheduler uses this to guide
	 *            work-stealing decisions
	 * @param body
	 *            the poller loop; the slot is freed when this returns
	 * @return completes when {@code body} exits and the slot is freed
	 */
	public CompletionStage<Void> registerPinnedPoller(BooleanSupplier wakeup, Runnable body) {
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
		wakeup();
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
		assert isValidPinnedPoller();
		touchHeartbeat();
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

	private boolean isValidPinnedPoller() {
		SchedulingContext ctx;
		return Thread.currentThread().isVirtual()
				&& ((ctx = currentThreadSchedulerContext()).type() == VThreadType.PINNED_POLLER
						&& ctx.assignedScheduler() == this);
	}

	private void virtualThreadSchedulerLoop() {
		var init = onCarrierStart;
		if (init != null) {
			init.run();
		}
		while (true) {
			int count = drainContinuations(YIELD_DURATION_NS);
			if (!runPinnedContinuation() && count == 0) {
				if (WORK_STEALING_ENABLED && tryStealing(true)) {
					continue;
				}
				if (WORK_STEALING_ENABLED) {
					var sibs = siblings;
					if (sibs != null) {
						for (int spin = sibs.length * 3; spin > 0; spin--) {
							if (!canParkScheduler()) {
								break;
							}
							if (sibs[spin % sibs.length].hasRunnableContinuations()) {
								if (tryStealing(true)) {
									break;
								}
							}
							Thread.onSpinWait();
						}
						if (!canParkScheduler()) {
							continue;
						}
					}
				}
				touchHeartbeat();
				resetSearching();
				if (canParkScheduler() && CARRIER_STATE.compareAndSet(this, RUNNING, PARKED)) {
					var cs = clusterState;
					if (cs != null) {
						cs.idleTracker().markIdle(id);
					}
					if (canParkScheduler()) {
						LockSupport.park();
					}
					if (cs != null) {
						cs.idleTracker().markActive(id);
					}
					int wakeState = (int) CARRIER_STATE.getAndSet(this, RUNNING);
					if (wakeState >= SEARCHING) {
						searching = true;
						var victim = group.scheduler(wakeState - SEARCHING);
						var task = victim.worthStealing() ? victim.tryStealOne() : null;
						if (task != null) {
							resetSearching();
							if (victim.hasRunnableContinuations()) {
								signalWorkFor(victim);
							}
							runContinuation(task);
						}
					}
					touchHeartbeat();
				}
			} else if (WORK_STEALING_ENABLED && hasRunnableContinuations()) {
				signalWork();
			}
		}
	}

	private boolean hasRunnableContinuations() {
		return !runQueue.isEmpty();
	}

	private void touchHeartbeat() {
		if (WORK_STEALING_ENABLED && !ALWAYS_UNRESPONSIVE) {
			SCHEDULER_HEARTBEAT.setOpaque(this, System.nanoTime());
		}
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
		assert isValidPinnedPoller();
		return !hasRunnableContinuations();
	}

	private static ThreadFactory newVThreadFactory(EventLoopScheduler scheduler, VThreadType type,
			NettyScheduler nettyScheduler) {
		var unstartedBuilder = Thread.ofVirtual();
		return runnable -> {
			var schedulingContext = new SchedulingContext(-1, scheduler, type);
			var vTask = nettyScheduler.newThread(unstartedBuilder, null,
					() -> EventLoopScheduler.runWithContext(runnable, schedulingContext));
			schedulingContext.setVThreadId(vTask.thread().threadId());
			vTask.attach(schedulingContext);
			return vTask.thread();
		};
	}

	/**
	 * Returns the number of virtual thread continuations waiting in the run queue.
	 */
	public int runnableCount() {
		return runQueue.size();
	}

	/**
	 * Returns a factory that creates virtual threads with affinity to this carrier.
	 */
	public ThreadFactory virtualThreadFactory() {
		return vThreadFactory;
	}

	/** Returns the platform thread backing this carrier. */
	public Thread carrierThread() {
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
		return ((SchedulingContext) task.attachment()).type() == VThreadType.PINNED_POLLER;
	}

	void execute(Thread.VirtualThreadTask task) {
		var currentThread = Thread.currentThread();
		var context = (SchedulingContext) task.attachment();
		boolean submitEventEnabled = VirtualThreadTaskSubmitEvent.isEventEnabled();
		boolean pinnedTask = false;
		if (isPinnedPoller(task) && pinnedContinuationToRun == null) {
			assert (task.attachment() instanceof SchedulingContext ctx) && ctx.assignedScheduler() == this;
			pinnedContinuationToRun = task;
			pinnedTask = true;
		}
		if (!pinnedTask) {
			runQueue.offer(task);
		}
		if (submitEventEnabled) {
			SchedulerJfrUtil.commitVirtualThreadTaskSubmitEvent(task, currentThread, carrierThread,
					context.type() == VThreadType.JDK_POLLER, pinnedTask);
		}
		// skip for yield/re-enqueue on the same carrier (onContinue path)
		if (currentThread != carrierThread) {
			boolean woke = false;
			if (EventLoopScheduler.currentThreadSchedulerContext().runningScheduler() != this) {
				// external submission — wake the target carrier/poller
				woke = wakeup();
			}
			if (!woke && WORK_STEALING_ENABLED) {
				signalWork();
			}
		}
	}

	boolean wakeup() {
		var poller = pinnedPollerWakeup;
		if (poller != null && poller.getAsBoolean()) {
			return true;
		}
		if (CARRIER_STATE.compareAndSet(this, PARKED, RUNNING)) {
			LockSupport.unpark(carrierThread);
			return true;
		}
		return false;
	}

	private long heartbeat() {
		assert WORK_STEALING_ENABLED : "heartbeat is only valid when work stealing is enabled";
		return (long) SCHEDULER_HEARTBEAT.getOpaque(this);
	}

	public boolean isUnresponsive(long nowNanos) {
		return ALWAYS_UNRESPONSIVE || (nowNanos - heartbeat()) > UNRESPONSIVE_THRESHOLD_NS;
	}

	private boolean isUnresponsive() {
		return ALWAYS_UNRESPONSIVE || (System.nanoTime() - heartbeat()) > UNRESPONSIVE_THRESHOLD_NS;
	}

	private boolean needsHelp() {
		return isUnresponsive() && hasRunnableContinuations();
	}

	boolean worthStealing() {
		return hasRunnableContinuations();
	}

	void signalWork() {
		signalWorkFor(this);
	}

	private void signalWorkFor(EventLoopScheduler victim) {
		var cs = clusterState;
		if (cs == null || siblings == null) {
			return;
		}
		if (!cs.tryStartSearcher()) {
			return;
		}
		if (!cs.idleTracker().wakeFirstIdle(group, victim)) {
			cs.stoppedSearching();
		}
	}

	private void resetSearching() {
		if (searching) {
			searching = false;
			var cs = clusterState;
			if (cs != null) {
				cs.stoppedSearching();
			}
		}
	}

	boolean wakeupAsSearcher(EventLoopScheduler victim) {
		if (CARRIER_STATE.compareAndSet(this, PARKED, SEARCHING + victim.id)) {
			LockSupport.unpark(carrierThread);
			return true;
		}
		return false;
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
		int len = siblings.length;
		var rng = ThreadLocalRandom.current();
		EventLoopScheduler victim;
		int a = rng.nextInt(len);
		if (len == 1) {
			victim = siblings[a].worthStealing() ? siblings[a] : null;
		} else {
			int b = rng.nextInt(len - 1);
			if (b >= a) {
				b++;
			}
			var sa = siblings[a];
			var sb = siblings[b];
			boolean helpA = sa.worthStealing();
			boolean helpB = sb.worthStealing();
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
			resetSearching();
			if (victim.hasRunnableContinuations()) {
				signalWorkFor(victim);
			}
			if (fromCarrierLoop) {
				runContinuation(task);
			} else {
				runQueue.offer(task);
			}
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
		context.mountedOn(this);
		var event = SchedulerJfrUtil.beginVirtualThreadTaskRunEvent();
		if (event == null) {
			task.run();
		} else {
			boolean isPinned = context.type() == VThreadType.PINNED_POLLER;
			boolean isPoller = context.type() == VThreadType.JDK_POLLER;
			task.run();
			SchedulerJfrUtil.commitVirtualThreadTaskRunEvent(event, carrierThread, task.thread(), isPoller, isPinned);
		}
	}
}
