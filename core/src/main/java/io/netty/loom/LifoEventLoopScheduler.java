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
import io.netty.loom.jfr.VirtualThreadTaskSubmitEvent;
import io.netty.util.concurrent.FastThreadLocalThread;

import static io.netty.loom.SchedulerJfrUtil.commitVirtualThreadTaskSubmitEvent;

/**
 * A variant of EventLoopScheduler with LIFO execution semantics for tasks
 * scheduled from the Netty event loop thread.
 *
 * <h3>Scheduling Behavior:</h3>
 * <ul>
 * <li><b>Single local handoff:</b> The event loop thread hands off one local
 * continuation at a time for execution on the carrier.</li>
 * <li><b>Always yield:</b> execute() called from the event loop ALWAYS yields,
 * not only when idle.</li>
 * <li><b>FIFO for external tasks:</b> Tasks from external threads accumulate in
 * the MPSC queue, then are drained in FIFO order.</li>
 * <li><b>Local-only on runIO/runNonBlockingTasks:</b> while the event loop is
 * running, only ONE local task is executed per yield.</li>
 * </ul>
 *
 * <h3>JFR Events:</h3> Exposes the same JFR events as EventLoopScheduler,
 * implemented identically.
 */
final class LifoEventLoopScheduler implements EventLoopScheduler {

	private static final long MAX_WAIT_TASKS_NS = TimeUnit.HOURS.toNanos(1);
	// These are the soft-guaranteed yield times for the event loop whilst
	// Thread.yield() is called.
	// Based on the status of the event loop (resuming from blocking or
	// non-blocking, controlled by the running flag)
	// a different limit is applied.
	private static final long RUNNING_YIELD_US = TimeUnit.MICROSECONDS
			.toNanos(Integer.getInteger("io.netty.loom.running.yield.us", 1));
	private final MpscUnboundedStream<Thread.VirtualThreadTask> runQueue;
	private Thread.VirtualThreadTask ioTask;
	private final ManualIoEventLoop ioEventLoop;
	private final Thread eventLoopThread;
	private final Thread carrierThread;
	private volatile Thread parkedCarrierThread;
	private volatile Thread.VirtualThreadTask eventLoopContinuatioToRun;
	private final ThreadFactory vThreadFactory;
	private final AtomicBoolean eventLoopIsRunning;
	private final SharedRef sharedRef;

	public LifoEventLoopScheduler(IoEventLoopGroup parent, ThreadFactory threadFactory,
			IoHandlerFactory ioHandlerFactory, int resumedContinuationsExpectedCount) {
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
				return LifoEventLoopScheduler.this.noTasks();
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
			var vTask = scheduler.newThread(unstartedBuilder, null,
					() -> EventLoopScheduler.runWithContext(runnable, schedulingContext));
			schedulingContext.vThreadId = vTask.thread().threadId();
			vTask.attach(schedulingContext);
			return vTask.thread();
		};
	}

	@Override
	public int externalContinuationsCount() {
		return runQueue.size();
	}

	@Override
	public ThreadFactory virtualThreadFactory() {
		return vThreadFactory;
	}

	@Override
	public Thread carrierThread() {
		return carrierThread;
	}

	@Override
	public ManualIoEventLoop ioEventLoop() {
		return ioEventLoop;
	}

	private boolean noTasks() {
		return ioTask == null && runQueue.isEmpty();
	}

	private void nettyEventLoop() {
		eventLoopIsRunning.set(true);
		assert ioEventLoop.inEventLoop(Thread.currentThread()) && Thread.currentThread().isVirtual();
		boolean canBlock = false;
		while (!ioEventLoop.isShuttingDown()) {
			canBlock = runIO(canBlock);
			if (!noTasks()) {
				Thread.yield();
			}
			// try running leftover write tasks before checking for I/O tasks
			canBlock &= runNonBlockingTasks(RUNNING_YIELD_US) == 0;
			if (!noTasks()) {
				Thread.yield();
			}
		}
		// we are shutting down, it shouldn't take long so let's spin a bit :P
		while (!ioEventLoop.isTerminated()) {
			ioEventLoop.runNow();
			if (!noTasks()) {
				Thread.yield();
			}
		}
	}

	private boolean runIO(boolean canBlock) {
		var event = SchedulerJfrUtil.beginRunIoEvent();
		int ioEventsHandled;
		boolean ranBlocking = false;
		if (canBlock) {
			// try to go to sleep waiting for I/O tasks
			eventLoopIsRunning.set(false);
			// StoreLoad barrier: see
			// https://www.scylladb.com/2018/02/15/memory-barriers-seastar-linux/
			if (canBlockCarrier()) {
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
			SchedulerJfrUtil.commitRunIoEvent(event, carrierThread, ranBlocking, ioEventsHandled);
		}
		return ioEventsHandled == 0;
	}

	private int runNonBlockingTasks(long deadlineNs) {
		var event = SchedulerJfrUtil.beginRunNonBlockingTasksEvent();
		if (event == null) {
			return ioEventLoop.runNonBlockingTasks(deadlineNs);
		}
		int tasksHandled = ioEventLoop.runNonBlockingTasks(deadlineNs);
		SchedulerJfrUtil.commitRunNonBlockingTasksEvent(event, carrierThread, tasksHandled);
		return tasksHandled;
	}

	private boolean runEventLoopContinuation() {
		assert Thread.currentThread() == carrierThread;
		var eventLoopContinuation = this.eventLoopContinuatioToRun;
		if (eventLoopContinuation != null) {
			this.eventLoopContinuatioToRun = null;
			runContinuation(eventLoopContinuation);
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
			final int count = runTasks();
			if (!runEventLoopContinuation() && count == 0) {
				// nothing to run, including the event loop: we can park
				parkedCarrierThread = carrierThread;
				if (canBlockCarrier()) {
					LockSupport.park();
				}
				parkedCarrierThread = null;
			}
		}
		// make sure the event loop thread is fully terminated and all tasks are run
		while (eventLoopThread.isAlive() || !canBlockCarrier()) {
			runTasks();
			runEventLoopContinuation();
		}
		// the event loop should be fully terminated
		sharedRef.clear();
		runQueue.close();
		// StoreLoad barrier
		while (!runQueue.isEmpty()) {
			runExternalContinuations(RUNNING_YIELD_US);
		}
	}

	private int runTasks() {
		var ioTask = this.ioTask;
		final int count;
		if (ioTask != null) {
			count = 1;
			this.ioTask = null;
			runContinuation(ioTask);
		} else {
			count = runExternalContinuations(RUNNING_YIELD_US);
		}
		return count;
	}

	private boolean canBlockCarrier() {
		return noTasks() && eventLoopContinuatioToRun == null;
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

	@Override
	public boolean execute(Thread.VirtualThreadTask task) {
		var currentThread = Thread.currentThread();
		var context = (SchedulingContext) task.attachment();
		boolean submitEventEnabled = VirtualThreadTaskSubmitEvent.isEventEnabled();
		boolean eventLoopTask = rescheduleEventLoop(task);
		if (!eventLoopTask && currentThread == eventLoopThread) {
			assert ioTask == null;
			ioTask = task;
			if (submitEventEnabled) {
				commitVirtualThreadTaskSubmitEvent(task, currentThread, carrierThread, context.isPoller);
			}
			Thread.yield();
			return true;
		}
		if (!eventLoopTask) {
			if (!runQueue.offer(task)) {
				return false;
			}
		}
		if (submitEventEnabled) {
			commitVirtualThreadTaskSubmitEvent(task, currentThread, carrierThread, context.isPoller);
		}
		if (currentThread != eventLoopThread) {
			// currentThread == carrierThread iff
			// - event loop start
			// - Thread::yield within this scheduler
			if (currentThread != carrierThread) {
				// this is checking for "local" submissions
				if (EventLoopScheduler.currentThreadSchedulerContext().scheduler() != sharedRef) {
					ioEventLoop.wakeup();
					LockSupport.unpark(parkedCarrierThread);
				}
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
		boolean isPoller = context.isPoller;
		task.run();
		SchedulerJfrUtil.commitVirtualThreadTaskRunEvent(event, carrierThread, task, isPoller);
	}

}
