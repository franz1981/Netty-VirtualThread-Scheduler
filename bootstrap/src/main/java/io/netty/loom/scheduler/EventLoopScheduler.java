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
 * {@link #maybeYield(boolean)} and {@link #canParkPoller()}.
 */
public final class EventLoopScheduler {

	private static final VarHandle PINNED_POLLER_WAKEUP;
	private static final VarHandle CONSUMER_TICKET;
	private static final VarHandle CONSUMER_SERVING;
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
	 *   carrier:       handleSearchWake on SEARCHING wake -> stoppedSearching
	 *   Invariant: every tryStartSearcher success is matched by exactly one
	 *   stoppedSearching -- either from signalWorkFor (no idle found) or from
	 *   handleSearchWake (in the woken carrier or poller VT).
	 */
	// @formatter:on
	private static final int RUNNING = 0;
	private static final int PARKED = 1;
	static final int SEARCHING = 2;

	static {
		try {
			var lookup = MethodHandles.lookup();
			PINNED_POLLER_WAKEUP = lookup.findVarHandle(EventLoopScheduler.class, "pinnedPollerWakeup", Runnable.class);
			CONSUMER_TICKET = lookup.findVarHandle(EventLoopScheduler.class, "consumerTicket", int.class);
			CONSUMER_SERVING = lookup.findVarHandle(EventLoopScheduler.class, "consumerServing", int.class);
			CARRIER_STATE = lookup.findVarHandle(EventLoopScheduler.class, "carrierState", int.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	public static final long YIELD_DURATION_NS = TimeUnit.MICROSECONDS
			.toNanos(Integer.getInteger("io.netty.loom.yield.us", 50));
	static final boolean WORK_STEALING_ENABLED = Boolean
			.parseBoolean(System.getProperty("io.netty.loom.workstealing.enabled", "false"));

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
	private volatile Runnable pinnedPollerWakeup;
	@SuppressWarnings("FieldMayBeFinal")
	private volatile int consumerTicket;
	@SuppressWarnings("FieldMayBeFinal")
	private volatile int consumerServing;
	private volatile EventLoopScheduler[] siblings;
	EventLoopSchedulerGroup group;
	ClusterState clusterState;
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

	int carrierState() {
		return (int) CARRIER_STATE.getAcquire(this);
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
	 * yield CPU time to external VTs, (2) use {@link #tryParkPoller()} (or
	 * {@link #canParkPoller()} + {@link #tryPark()}) before entering blocking I/O
	 * and ensure no wakeup signal is lost in the race window between the check and
	 * the blocking call — either via a permit-based mechanism (sticky wakeup, e.g.
	 * eventfd or {@link java.util.concurrent.locks.LockSupport#unpark}) or a
	 * lock-based rendezvous where the check and wait are inside the same lock, and
	 * (3) eventually return from {@code body} so the slot can be freed.
	 *
	 * @param wakeup
	 *            called from any thread to interrupt the poller's blocking I/O
	 *            (e.g. eventfd write); must be thread-safe and idempotent. Only
	 *            called after the scheduler has CAS'd the carrier state to PARKED —
	 *            never called spuriously.
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
		wakeup();
		return termination;
	}

	/**
	 * Scheduling checkpoint for the pinned poller. Must be called between phases of
	 * the poller loop. Yields the carrier if external VTs have work queued. When no
	 * I/O work was done, the scheduler may steal from an overloaded sibling.
	 *
	 * @param hadIoWork
	 *            true if the poller processed I/O events or tasks since the last
	 *            call; false if idle
	 */
	public boolean maybeYield(boolean hadIoWork) {
		assert isValidPinnedPoller();
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
			onCarrierStart = null;
		}
		while (true) {
			if (WORK_STEALING_ENABLED && (int) CARRIER_STATE.getAcquire(this) >= SEARCHING) {
				unparkFromCarrier();
			}
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

				if (tryPark()) {
					LockSupport.park();
					unparkFromCarrier();
				} else if ((int) CARRIER_STATE.getAcquire(this) != RUNNING) {
					unparkFromCarrier();
				}
			} else if (WORK_STEALING_ENABLED && hasRunnableContinuations()) {
				signalWork();
			}
		}
	}

	private boolean hasRunnableContinuations() {
		return !runQueue.isEmpty();
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
	public boolean canParkPoller() {
		assert isValidPinnedPoller();
		return !hasRunnableContinuations() && (int) CARRIER_STATE.getAcquire(this) < SEARCHING;
	}

	/**
	 * Poller entry point: pre-check + park in one call. Returns true if the carrier
	 * transitioned to PARKED and the caller should enter blocking I/O.
	 *
	 * <p>
	 * <b>Critical:</b> the caller must enter blocking I/O immediately after this
	 * returns true, and must call {@link #unpark()} immediately after waking from
	 * I/O. No blocking operations, {@code Thread.yield()}, or any other
	 * descheduling point is allowed between {@code tryParkPoller()} and the
	 * blocking I/O call, or between the I/O return and {@link #unpark()}. If the
	 * poller VT is descheduled in either window, the carrier loop may reset the
	 * PARKED state to RUNNING, causing subsequent wakeup signals to be lost
	 * (CAS(PARKED,RUNNING) fails because the state is already RUNNING).
	 */
	public boolean tryParkPoller() {
		return canParkPoller() && tryPark();
	}

	/**
	 * Transitions the carrier to PARKED + marks idle in the bitmap + re-checks.
	 * Used by both the carrier loop (before LockSupport.park) and the poller
	 * (before blocking I/O). Returns true if the transition succeeded and the
	 * caller should block.
	 */
	private boolean tryPark() {
		if (!CARRIER_STATE.compareAndSet(this, RUNNING, PARKED)) {
			return false;
		}
		var cs = clusterState;
		if (cs != null) {
			cs.idleTracker().markIdle(id);
		}
		if (!canParkScheduler()) {
			if (cs != null) {
				cs.idleTracker().markActive(id);
			}
			int rolledBack = (int) CARRIER_STATE.getAndSet(this, RUNNING);
			if (rolledBack >= SEARCHING) {
				handleSearchWake(rolledBack, Thread.currentThread() == carrierThread);
			}
			return false;
		}
		return true;
	}

	/**
	 * Exits PARKED state atomically — markActive + getAndSet(RUNNING). Handles
	 * SEARCHING wakeState internally (directed steal + chain).
	 */
	public void unpark() {
		unparkImpl(false);
	}

	void unparkFromCarrier() {
		unparkImpl(true);
	}

	private void unparkImpl(boolean inline) {
		var cs = clusterState;
		if (cs != null) {
			cs.idleTracker().markActive(id);
		}
		int wakeState = (int) CARRIER_STATE.getAndSet(this, RUNNING);
		if (wakeState >= SEARCHING) {
			handleSearchWake(wakeState, inline);
		}
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

	/**
	 * Handles a SEARCHING wakeState: directed steal + chain propagation. Stolen
	 * task is enqueued for the carrier to drain on the next yield.
	 */
	private void handleSearchWake(int wakeState, boolean inline) {
		var cs = clusterState;
		var victim = group.scheduler(wakeState - SEARCHING);
		var task = victim.hasRunnableContinuations() ? victim.tryStealOne() : null;
		if (cs != null) {
			cs.stoppedSearching();
		}
		if (task != null) {
			var event = SchedulerJfrUtil.beginWorkStealEvent();
			int sourceQueueDepth = event != null ? victim.runnableCount() : 0;
			if (victim.hasRunnableContinuations()) {
				signalWorkFor(victim);
			}
			if (inline) {
				runContinuation(task);
			} else {
				runQueue.offer(task);
			}
			if (event != null) {
				SchedulerJfrUtil.commitDirectedStealEvent(event, task, victim.carrierThread, carrierThread,
						sourceQueueDepth, inline);
			}
		}
	}

	boolean wakeup() {
		if (CARRIER_STATE.compareAndSet(this, PARKED, RUNNING)) {
			LockSupport.unpark(carrierThread);
			var poller = pinnedPollerWakeup;
			if (poller != null) {
				poller.run();
			}
			return true;
		}
		return false;
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

	boolean wakeupAsSearcher(EventLoopScheduler victim) {
		if (CARRIER_STATE.compareAndSet(this, PARKED, SEARCHING + victim.id)) {
			LockSupport.unpark(carrierThread);
			var poller = pinnedPollerWakeup;
			if (poller != null) {
				poller.run();
			}
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
			victim = siblings[a].hasRunnableContinuations() ? siblings[a] : null;
		} else {
			int b = rng.nextInt(len - 1);
			if (b >= a) {
				b++;
			}
			var sa = siblings[a];
			var sb = siblings[b];
			boolean helpA = sa.hasRunnableContinuations();
			boolean helpB = sb.hasRunnableContinuations();
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
