/*
 * Copyright 2025 The Netty VirtualThread Scheduler Project
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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ManualIoEventLoop;
import io.netty.loom.jfr.NettyRunIoEvent;
import io.netty.loom.jfr.NettyRunNonBlockingTasksEvent;
import io.netty.loom.jfr.VirtualThreadTaskRunsEvent;
import io.netty.loom.jfr.VirtualThreadTaskRunEvent;
import io.netty.loom.jfr.VirtualThreadTaskSubmitEvent;
import io.netty.util.concurrent.FastThreadLocalThread;

public class EventLoopScheduler {

	/**
	 * A shared reference to an EventLoopScheduler, which can be cleared when the
	 * scheduler is fully terminated.
	 */
	public static final class SharedRef {

		private volatile EventLoopScheduler ref;

		private SharedRef(EventLoopScheduler ref) {
			this.ref = ref;
		}

		public EventLoopScheduler get() {
			return ref;
		}
	}

	/**
	 * We currently lack a way to query the current thread's assigned scheduler:
	 * this is a workaround using ScopedValues.<br>
	 * The same functionality could have been achieved (in a less hacky way) using
	 * ThreadLocals, which are way more expensive, for VirtualThreads.<br>
	 * Read sub-poller(s) sadly won't work as expected with this, because are not
	 * created using the event loop scheduler factory.
	 */
	public static final class SchedulingContext {

		long vThreadId;
		final boolean isPoller;
		final SharedRef schedulerRef;

		SchedulingContext(long vThreadId, SharedRef schedulerRef, boolean isPoller) {
			this.vThreadId = vThreadId;
			this.schedulerRef = schedulerRef;
			this.isPoller = isPoller;
		}

		SchedulingContext(SharedRef schedulerRef) {
			this(-1, schedulerRef, false);
		}

		/**
		 * Get the assigned scheduler for the current thread, or null if this thread is
		 * not assigned to any scheduler.
		 */
		public SharedRef scheduler() {
			// TODO consider returning EMPTY_SCHEDULER_REF instead of null
			return (Thread.currentThread().threadId() == vThreadId) ? schedulerRef : null;
		}
	}

	private static final long MAX_WAIT_TASKS_NS = TimeUnit.HOURS.toNanos(1);
	// These are the soft-guaranteed yield times for the event loop whilst
	// Thread.yield() is called.
	// Based on the status of the event loop (resuming from blocking or
	// non-blocking, controlled by the running flag)
	// a different limit is applied.
	private static final long RUNNING_YIELD_US = TimeUnit.MICROSECONDS
			.toNanos(Integer.getInteger("io.netty.loom.running.yield.us", 1));
	private static final long IDLE_YIELD_US = TimeUnit.MICROSECONDS
			.toNanos(Integer.getInteger("io.netty.loom.idle.yield.us", 1));
	// This is required to allow sub-pollers to run on the correct scheduler
	private static final ScopedValue<SchedulingContext> CURRENT_SCHEDULER = ScopedValue.newInstance();
	private static final SchedulingContext EMPTY_SCHEDULER_CONTEXT = new SchedulingContext(-1, null, false);
	private final MpscUnboundedStream<Thread.VirtualThreadTask> runQueue;
	private final ManualIoEventLoop ioEventLoop;
	private final Thread eventLoopThread;
	private final Thread carrierThread;
	private volatile Thread parkedCarrierThread;
	private volatile Thread.VirtualThreadTask eventLoopContinuatioToRun;
	private final ThreadFactory vThreadFactory;
	private final AtomicBoolean eventLoopIsRunning;
	private final SharedRef sharedRef;

	public EventLoopScheduler(IoEventLoopGroup parent, ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory,
			int resumedContinuationsExpectedCount) {
		sharedRef = new SharedRef(this);
		eventLoopIsRunning = new AtomicBoolean(false);
		runQueue = new MpscUnboundedStream<>(resumedContinuationsExpectedCount);
		carrierThread = threadFactory.newThread(this::virtualThreadSchedulerLoop);
		vThreadFactory = newEventLoopSchedulerFactory(sharedRef);
		eventLoopThread = vThreadFactory
				.newThread(() -> FastThreadLocalThread.runWithFastThreadLocal(this::nettyEventLoop));
		ioEventLoop = new ManualIoEventLoop(parent, eventLoopThread,
				ioExecutor -> new AwakeAwareIoHandler(eventLoopIsRunning, ioHandlerFactory.newHandler(ioExecutor))) {
			@Override
			public boolean canBlock() {
				return runQueue.isEmpty();
			}
		};
		carrierThread.start();
	}

	private static ThreadFactory newEventLoopSchedulerFactory(SharedRef sharedRef) {
		// in the future we could create a SchedulerAssignment object a with modifiable
		// SharedRef into
		// and share it between the thread attachment and the SchedulingContext,
		// enabling
		// work-stealing to change it for both
		var unstartedBuilder = Thread.ofVirtual();
		NettyScheduler scheduler = NettyScheduler.INSTANCE;
		return runnable -> {
			var schedulingContext = new SchedulingContext(sharedRef);
			var vTask = scheduler.newThread(unstartedBuilder, null, () -> runWithContext(runnable, schedulingContext));
			schedulingContext.vThreadId = vTask.thread().threadId();
			vTask.attach(schedulingContext);
			return vTask.thread();
		};
	}

	private static void runWithContext(Runnable runnable, SchedulingContext schedulingContext) {
		ScopedValue.where(CURRENT_SCHEDULER, schedulingContext).run(runnable);
	}

	int externalContinuationsCount() {
		return runQueue.size();
	}

	public ThreadFactory virtualThreadFactory() {
		return vThreadFactory;
	}

	Thread carrierThread() {
		return carrierThread;
	}

	public ManualIoEventLoop ioEventLoop() {
		return ioEventLoop;
	}

	private void nettyEventLoop() {
		eventLoopIsRunning.set(true);
		assert ioEventLoop.inEventLoop(Thread.currentThread()) && Thread.currentThread().isVirtual();
		boolean canBlock = false;
		while (!ioEventLoop.isShuttingDown()) {
			canBlock = runIO(canBlock);
			if (!runQueue.isEmpty()) {
				Thread.yield();
			}
			// try running leftover write tasks before checking for I/O tasks
			canBlock &= runNonBlockingTasks(RUNNING_YIELD_US) == 0;
			if (!runQueue.isEmpty()) {
				Thread.yield();
			}
		}
		// we are shutting down, it shouldn't take long so let's spin a bit :P
		while (!ioEventLoop.isTerminated()) {
			ioEventLoop.runNow();
			if (!runQueue.isEmpty()) {
				Thread.yield();
			}
		}
	}

	private boolean runIO(boolean canBlock) {
		NettyRunIoEvent event = beginRunIoEvent();
		int ioEventsHandled;
		boolean ranBlocking = false;
		if (canBlock) {
			// try to go to sleep waiting for I/O tasks
			eventLoopIsRunning.set(false);
			// StoreLoad barrier: see
			// https://www.scylladb.com/2018/02/15/memory-barriers-seastar-linux/
			if (canBlock()) {
				ranBlocking = true;
				try {
					ioEventsHandled = ioEventLoop.run(MAX_WAIT_TASKS_NS, RUNNING_YIELD_US);
				} finally {
					eventLoopIsRunning.set(true);
				}
			} else {
				eventLoopIsRunning.set(true);
				ioEventsHandled = ioEventLoop.runNow(RUNNING_YIELD_US);
			}
		} else {
			ioEventsHandled = ioEventLoop.runNow(RUNNING_YIELD_US);
		}
		if (event != null) {
			commitRunIoEvent(event, ranBlocking, ioEventsHandled);
		}
		return ioEventsHandled == 0;
	}

	private int runNonBlockingTasks(long deadlineNs) {
		NettyRunNonBlockingTasksEvent event = beginRunNonBlockingTasksEvent();
		if (event == null) {
			return ioEventLoop.runNonBlockingTasks(deadlineNs);
		}
		int tasksHandled = ioEventLoop.runNonBlockingTasks(deadlineNs);
		commitRunNonBlockingTasksEvent(event, tasksHandled);
		return tasksHandled;
	}

	private boolean runEventLoopContinuation() {
		assert Thread.currentThread() == carrierThread;
		var eventLoopContinuation = this.eventLoopContinuatioToRun;
		if (eventLoopContinuation != null) {
			this.eventLoopContinuatioToRun = null;
			runContinuation(eventLoopContinuation, -1);
			return true;
		}
		return false;
	}

	private void virtualThreadSchedulerLoop() {
		// start the event loop thread
		var eventLoop = this.ioEventLoop;
		eventLoopThread.start();
		assert eventLoopContinuatioToRun != null;
		// we keep on running until the event loop is shutting-down
		while (!eventLoop.isTerminated()) {
			// if the event loop was idle, we apply a different limit to the yield time
			final boolean eventLoopRunning = eventLoopIsRunning.get();
			final long yieldDurationNs = eventLoopRunning ? RUNNING_YIELD_US : IDLE_YIELD_US;
			int count = runExternalContinuations(yieldDurationNs);
			if (!runEventLoopContinuation() && count == 0) {
				// nothing to run, including the event loop: we can park
				parkedCarrierThread = carrierThread;
				if (canBlock()) {
					LockSupport.park();
				}
				parkedCarrierThread = null;
			}
		}
		// make sure the event loop thread is fully terminated and all tasks are run
		while (eventLoopThread.isAlive() || !canBlock()) {
			runExternalContinuations(RUNNING_YIELD_US);
			runEventLoopContinuation();
		}
		// the event loop should be fully terminated
		sharedRef.ref = null;
		runQueue.close();
		// StoreLoad barrier
		while (!runQueue.isEmpty()) {
			runExternalContinuations(IDLE_YIELD_US);
		}
	}

	private boolean canBlock() {
		return runQueue.isEmpty() && eventLoopContinuatioToRun == null;
	}

	private int runExternalContinuations(long deadlineNs) {
		VirtualThreadTaskRunsEvent event = beginVirtualThreadTaskRunsEvent();
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
			runContinuation(task, runContinuations);
			long elapsedNs = System.nanoTime() - startDrainingNs;
			if (elapsedNs >= deadlineNs) {
				break;
			}
		}
		if (event != null) {
			int queueDepthAfter = runQueue.size();
			commitVirtualThreadTaskRunsEvent(event, runContinuations, queueDepthBefore, queueDepthAfter);
		}
		return runContinuations;
	}

	private boolean rescheduleEventLoop(Thread.VirtualThreadTask task) {
		if (eventLoopContinuatioToRun != null) {
			assert task.thread() != eventLoopThread;
			return false;
		}
		if (task.thread() == eventLoopThread) {
			eventLoopContinuatioToRun = task;
			return true;
		}
		return false;
	}

	public boolean execute(Thread.VirtualThreadTask task) {
		var currentThread = Thread.currentThread();
		var context = (SchedulingContext) task.attachment();
		boolean submitEventEnabled = VirtualThreadTaskSubmitEvent.isEventEnabled();
		boolean eventLoopTask = rescheduleEventLoop(task);
		if (!eventLoopTask) {
			if (!runQueue.offer(task)) {
				return false;
			}
		}
		if (submitEventEnabled) {
			commitVirtualThreadTaskSubmitEvent(task, currentThread, context.isPoller);
		}
		if (currentThread != eventLoopThread) {
			// currentThread == carrierThread iff
			// - event loop start
			// - Thread::yield within this scheduler
			if (currentThread != carrierThread) {
				// this is checking for "local" submissions
				if (currentThreadSchedulerContext().scheduler() != sharedRef) {
					ioEventLoop.wakeup();
					LockSupport.unpark(parkedCarrierThread);
				}
			}
		} else if (!eventLoopTask && !eventLoopIsRunning.get()) {
			// the event loop thread is allowed to give up cycles to consume external
			// continuations
			// whilst is just woken up for I/O
			assert eventLoopContinuatioToRun == null;
			if (!runQueue.isEmpty()) {
				Thread.yield();
			}
		}
		return true;
	}

	private void runContinuation(Thread.VirtualThreadTask task, int tasksExecutedInDrain) {
		VirtualThreadTaskRunEvent event = beginVirtualThreadTaskRunEvent();
		if (event == null) {
			task.run();
			return;
		}
		var context = (SchedulingContext) task.attachment();
		boolean isPoller = context.isPoller;
		task.run();
		commitVirtualThreadTaskRunEvent(event, task, isPoller, tasksExecutedInDrain);
	}

	private NettyRunIoEvent beginRunIoEvent() {
		if (!NettyRunIoEvent.isEventEnabled()) {
			return null;
		}
		var event = new NettyRunIoEvent();
		event.begin();
		return event;
	}

	private void commitRunIoEvent(NettyRunIoEvent event, boolean ranBlocking, int ioEventsHandled) {
		event.end();
		event.eventLoopThread = Thread.currentThread();
		event.carrierThread = carrierThread;
		event.canBlock = ranBlocking;
		event.ioEventsHandled = ioEventsHandled;
		event.commit();
	}

	private NettyRunNonBlockingTasksEvent beginRunNonBlockingTasksEvent() {
		if (!NettyRunNonBlockingTasksEvent.isEventEnabled()) {
			return null;
		}
		var event = new NettyRunNonBlockingTasksEvent();
		event.begin();
		return event;
	}

	private void commitRunNonBlockingTasksEvent(NettyRunNonBlockingTasksEvent event, int tasksHandled) {
		event.end();
		event.eventLoopThread = Thread.currentThread();
		event.carrierThread = carrierThread;
		event.tasksHandled = tasksHandled;
		event.commit();
	}

	private VirtualThreadTaskRunsEvent beginVirtualThreadTaskRunsEvent() {
		if (!VirtualThreadTaskRunsEvent.isEventEnabled()) {
			return null;
		}
		var event = new VirtualThreadTaskRunsEvent();
		event.begin();
		return event;
	}

	private void commitVirtualThreadTaskRunsEvent(VirtualThreadTaskRunsEvent event, int tasksExecuted,
			int queueDepthBefore, int queueDepthAfter) {
		event.end();
		event.carrierThread = carrierThread;
		event.tasksExecuted = tasksExecuted;
		event.queueDepthBefore = queueDepthBefore;
		event.queueDepthAfter = queueDepthAfter;
		event.commit();
	}

	private void commitVirtualThreadTaskSubmitEvent(Thread.VirtualThreadTask task, Thread submitterThread,
			boolean isPoller) {
		var event = new VirtualThreadTaskSubmitEvent();
		event.virtualThread = task.thread();
		event.submitterThread = submitterThread;
		event.carrierThread = carrierThread;
		event.isPoller = isPoller;
		event.commit();
	}

	private VirtualThreadTaskRunEvent beginVirtualThreadTaskRunEvent() {
		if (!VirtualThreadTaskRunEvent.isEventEnabled()) {
			return null;
		}
		var event = new VirtualThreadTaskRunEvent();
		event.begin();
		return event;
	}

	private void commitVirtualThreadTaskRunEvent(VirtualThreadTaskRunEvent event, Thread.VirtualThreadTask task,
			boolean isPoller, int tasksExecutedInDrain) {
		event.end();
		event.virtualThread = task.thread();
		event.carrierThread = carrierThread;
		event.tasksExecutedInDrain = tasksExecutedInDrain;
		event.isPoller = isPoller;
		event.commit();
	}

	/**
	 * Get the current thread's associated EventLoopScheduler context, or an empty
	 * one if none is associated.
	 */
	public static SchedulingContext currentThreadSchedulerContext() {
		return CURRENT_SCHEDULER.orElse(EMPTY_SCHEDULER_CONTEXT);
	}
}
