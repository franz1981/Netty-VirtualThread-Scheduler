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
import java.util.concurrent.TimeUnit;
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
 * kernel I/O and cooperates with the carrier loop via {@link #maybeYield()} and
 * {@link #canBlock()}.
 */
public final class EventLoopScheduler {

	private static final VarHandle PINNED_POLLER_WAKEUP;

	static {
		try {
			PINNED_POLLER_WAKEUP = MethodHandles.lookup().findVarHandle(EventLoopScheduler.class, "pinnedPollerWakeup",
					Runnable.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	static final long YIELD_DURATION_NS = TimeUnit.MICROSECONDS
			.toNanos(Integer.getInteger("io.netty.loom.yield.us", 10));

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

		SchedulingContext(long vThreadId, EventLoopScheduler eventLoopScheduler, VThreadType type) {
			this.vThreadId = vThreadId;
			this.eventLoopScheduler = eventLoopScheduler;
			this.type = type;
		}

		/**
		 * Returns the scheduler for the current thread, or {@code null} if the calling
		 * thread is not the VT that owns this context. Use this when reading from the
		 * ScopedValue — the thread ID check prevents inherited contexts from leaking to
		 * child VTs.
		 */
		public EventLoopScheduler scheduler() {
			return (Thread.currentThread().threadId() == vThreadId) ? eventLoopScheduler : null;
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
	public boolean hasRegisteredPinnedPoller() {
		return pinnedPollerWakeup != null;
	}

	/**
	 * Registers a pinned poller on this carrier. The poller runs as a virtual
	 * thread with affinity to this carrier (not on the carrier thread itself). At
	 * most one poller per carrier; throws if one is already registered.
	 *
	 * <p>
	 * The poller must: (1) call {@link #maybeYield()} between phases to yield CPU
	 * time to external VTs, (2) use {@link #canBlock()} before entering blocking
	 * I/O and ensure no wakeup signal is lost in the race window between the check
	 * and the blocking call — either via a permit-based mechanism (sticky wakeup,
	 * e.g. eventfd or {@link java.util.concurrent.locks.LockSupport#unpark}) or a
	 * lock-based rendezvous where the check and wait are inside the same lock, and
	 * (3) eventually return from {@code body} so the slot can be freed.
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
	 * Called by the pinned poller between phases. Yields the carrier if external
	 * virtual threads have work queued, allowing the carrier loop to drain them
	 * before the poller resumes.
	 */
	// TODO work stealing: add boolean hadWork parameter.
	// When hadWork=false AND hasSiblingStealableWork(), set a needPreempt flag
	// and yield. The carrier loop checks needPreempt to steal before re-mounting
	// the poller. canBlock() also reads needPreempt to prevent re-entering
	// blocking I/O during steal mode.
	public void maybeYield() {
		if (shouldPreemptPoller()) {
			Thread.yield();
		}
	}

	private void virtualThreadSchedulerLoop() {
		while (true) {
			int count = runExternalContinuations(YIELD_DURATION_NS);
			if (!runPinnedContinuation() && count == 0) {
				parkedCarrierThread = carrierThread;
				if (canParkScheduler()) {
					LockSupport.park();
				}
				parkedCarrierThread = null;
			}
		}
	}

	private boolean shouldPreemptPoller() {
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
	public boolean canBlock() {
		return runQueue.isEmpty();
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
	public int externalContinuationsCount() {
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
		return runQueue.isEmpty() && pinnedContinuationToRun == null;
	}

	private int runExternalContinuations(long deadlineNs) {
		var event = SchedulerJfrUtil.beginVirtualThreadTaskRunsEvent();
		final long startDrainingNs = System.nanoTime();
		int queueDepthBefore = event != null ? runQueue.size() : 0;
		var ready = this.runQueue;
		int runContinuations = 0;
		for (;;) {
			var task = ready.poll();
			if (task == null) {
				break;
			}
			runContinuations++;
			runContinuation(task);
			long elapsedNs = System.nanoTime() - startDrainingNs;
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
			if (EventLoopScheduler.currentThreadSchedulerContext().scheduler() != this) {
				var poller = pinnedPollerWakeup;
				if (poller != null) {
					poller.run();
				}
				LockSupport.unpark(parkedCarrierThread);
			}
		}
		return true;
	}

	private void runContinuation(Thread.VirtualThreadTask task) {
		var event = SchedulerJfrUtil.beginVirtualThreadTaskRunEvent();
		if (event == null) {
			task.run();
			return;
		}
		var context = (SchedulingContext) task.attachment();
		boolean isPinned = context.type == VThreadType.PINNED_POLLER;
		boolean isPoller = context.type == VThreadType.JDK_POLLER;
		task.run();
		SchedulerJfrUtil.commitVirtualThreadTaskRunEvent(event, carrierThread, task.thread(), isPoller, isPinned);
	}
}
