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

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ManualIoEventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.loom.spi.NettyScheduler;
import io.netty.util.concurrent.FastThreadLocalThread;

/**
 * Event loop group for native transports (EPOLL, IO_URING). Registers a pinned
 * poller on each carrier — the Netty event loop runs as a virtual thread with
 * affinity to the carrier, doing kernel I/O directly.
 *
 * <p>
 * Use {@link VirtualIoNioPollerEventLoopGroup} for NIO transports that don't
 * pin the carrier during blocking I/O.
 *
 * @see VirtualIoNioPollerEventLoopGroup
 */
public class VirtualIoNativePollerEventLoopGroup extends MultiThreadIoEventLoopGroup {

	private static final long MAX_WAIT_TASKS_NS = TimeUnit.HOURS.toNanos(1);
	private static final int IDLE_SPINS = Integer.getInteger("io.netty.loom.idleSpins", 0);
	private Map<IoEventLoop, EventLoopScheduler> eventSchedulerMappings;
	private EventLoopScheduler[] assignedById;
	private EventLoopScheduler[] assignedRoundRobin;
	private CompletionStage<Void>[] pollerTerminations;
	private int assignedCount;
	private final AtomicInteger nextAssigned = new AtomicInteger();

	/**
	 * Creates a group using all carriers in the global
	 * {@link EventLoopSchedulerGroup}.
	 */
	public VirtualIoNativePollerEventLoopGroup(IoHandlerFactory ioHandlerFactory) {
		this(EventLoopSchedulerGroup.instance().size(), ioHandlerFactory);
	}

	/**
	 * Creates a group using {@code nThreads} carriers that have no registered
	 * poller. Throws if not enough free carriers are available.
	 */
	public VirtualIoNativePollerEventLoopGroup(int nThreads, IoHandlerFactory ioHandlerFactory) {
		this(resolveSchedulers(nThreads, EventLoopSchedulerGroup.instance()), ioHandlerFactory);
	}

	private VirtualIoNativePollerEventLoopGroup(EventLoopScheduler[] schedulers, IoHandlerFactory ioHandlerFactory) {
		super(schedulers.length, (Executor) command -> {
			throw new UnsupportedOperationException("this executor is not supposed to be used");
		}, ioHandlerFactory, (Object) schedulers);
	}

	private static EventLoopScheduler[] resolveSchedulers(int nThreads, EventLoopSchedulerGroup group) {
		var schedulers = group.availableSchedulers(nThreads);
		if (schedulers == null) {
			throw new IllegalStateException(
					"need " + nThreads + " free schedulers but not enough available in group of " + group.size());
		}
		return schedulers;
	}

	private static void validateNettyAvailability() {
		if (!NettyScheduler.isAvailable()) {
			if (!NettyScheduler.isInstalled()) {
				throw new IllegalStateException(
						"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.spi.NettyScheduler is required to use VirtualIoNativePollerEventLoopGroup");
			}
			throw new IllegalStateException(
					"NettyScheduler bootstrap is installed but no NettySchedulerSpi provider was found. "
							+ "Ensure the core module JAR is visible to the thread-context class loader "
							+ "and META-INF/services/io.netty.loom.spi.NettySchedulerSpi is present.");
		}
	}

	/**
	 * Return a {@link java.util.concurrent.ThreadFactory} that creates virtual
	 * threads tied to the specified {@link IoEventLoop}'s
	 * {@link EventLoopScheduler}. Returns {@code null} if the provided event loop
	 * is not associated with this group.
	 */
	public java.util.concurrent.ThreadFactory vThreadFactoryOf(IoEventLoop eventLoop) {
		EventLoopScheduler scheduler = eventSchedulerMappings.get(eventLoop);
		if (scheduler == null) {
			return null;
		}
		return scheduler.virtualThreadFactory();
	}

	/**
	 * Return a {@link java.util.concurrent.ThreadFactory} that creates virtual
	 * threads tied to an {@link EventLoopScheduler} of this group.
	 */
	public java.util.concurrent.ThreadFactory vThreadFactory() {
		var scheduler = EventLoopScheduler.currentScheduler();
		if (scheduler != null && scheduler.id() < assignedById.length && assignedById[scheduler.id()] == scheduler) {
			return scheduler.virtualThreadFactory();
		}
		int index = nextAssigned.getAndIncrement() % assignedRoundRobin.length;
		return assignedRoundRobin[index].virtualThreadFactory();
	}

	private static PollerResult createNettyPoller(EventLoopScheduler scheduler,
			VirtualIoNativePollerEventLoopGroup parent, IoHandlerFactory ioHandlerFactory) {
		var pollerRunning = new AtomicBoolean(false);

		var eventLoop = new ManualIoEventLoop(parent, null,
				ioExecutor -> new AwakeAwareIoHandler(pollerRunning, ioHandlerFactory.newHandler(ioExecutor))) {
			@Override
			public boolean canBlock() {
				return scheduler.canBlock();
			}
		};

		var termination = scheduler.registerPinnedPoller(() -> {
			if (!pollerRunning.get()) {
				eventLoop.wakeup();
				return true;
			}
			return false;
		}, () -> {
			eventLoop.setOwningThread(Thread.currentThread());
			FastThreadLocalThread.runWithFastThreadLocal(() -> pinningEventLoop(scheduler, eventLoop, pollerRunning));
		});
		return new PollerResult(eventLoop, termination);
	}

	private record PollerResult(ManualIoEventLoop eventLoop, CompletionStage<Void> termination) {
	}

	private static void pinningEventLoop(EventLoopScheduler scheduler, ManualIoEventLoop ioEventLoop,
			AtomicBoolean pollerRunning) {
		pollerRunning.set(true);
		assert ioEventLoop.inEventLoop(Thread.currentThread()) && Thread.currentThread().isVirtual();
		boolean canBlock = false;
		int idleSpins = 0;
		while (!ioEventLoop.isShuttingDown()) {
			int ioEvents = runIO(scheduler, ioEventLoop, canBlock, pollerRunning);
			boolean hadVtWork = scheduler.maybeYield(ioEvents > 0);
			if (ioEvents > 0 || hadVtWork) {
				idleSpins = 0;
				canBlock = false;
			} else if (IDLE_SPINS != 0 && (IDLE_SPINS < 0 || ++idleSpins <= IDLE_SPINS)) {
				Thread.onSpinWait();
			} else {
				canBlock = true;
			}
		}
		while (!ioEventLoop.isTerminated()) {
			ioEventLoop.runNow();
			scheduler.maybeYield(true);
		}
		pollerRunning.set(false);
	}

	private static int runIO(EventLoopScheduler scheduler, ManualIoEventLoop ioEventLoop, boolean canBlock,
			AtomicBoolean pollerRunning) {
		var event = SchedulerJfrUtil.beginRunIoEvent();
		int ioEventsHandled;
		boolean ranBlocking = false;
		if (canBlock) {
			pollerRunning.set(false);
			if (scheduler.canBlock()) {
				ranBlocking = true;
				try {
					ioEventsHandled = ioEventLoop.run(MAX_WAIT_TASKS_NS, EventLoopScheduler.YIELD_DURATION_NS);
				} finally {
					pollerRunning.set(true);
				}
			} else {
				pollerRunning.set(true);
				ioEventsHandled = ioEventLoop.runNow(EventLoopScheduler.YIELD_DURATION_NS);
			}
		} else {
			ioEventsHandled = ioEventLoop.runNow(EventLoopScheduler.YIELD_DURATION_NS);
		}
		if (event != null) {
			SchedulerJfrUtil.commitRunIoEvent(event, scheduler.carrierThread(), ranBlocking, ioEventsHandled);
		}
		return ioEventsHandled;
	}

	private static int runNonBlockingTasks(EventLoopScheduler scheduler, ManualIoEventLoop ioEventLoop,
			long deadlineNs) {
		var event = SchedulerJfrUtil.beginRunTasksEvent();
		if (event == null) {
			return ioEventLoop.runNonBlockingTasks(deadlineNs);
		}
		int queueDepthBefore = scheduler.runnableCount();
		int tasksHandled = ioEventLoop.runNonBlockingTasks(deadlineNs);
		int queueDepthAfter = scheduler.runnableCount();
		SchedulerJfrUtil.commitRunTasksEvent(event, scheduler.carrierThread(), tasksHandled, queueDepthBefore,
				queueDepthAfter);
		return tasksHandled;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory,
			@SuppressWarnings("unused") Object... args) {
		validateNettyAvailability();
		var schedulers = (EventLoopScheduler[]) args[0];
		if (eventSchedulerMappings == null) {
			eventSchedulerMappings = new IdentityHashMap<>(schedulers.length);
			assignedRoundRobin = schedulers;
			assignedById = new EventLoopScheduler[maxId(schedulers) + 1];
			pollerTerminations = new CompletionStage[schedulers.length];
			for (var s : schedulers) {
				assignedById[s.id()] = s;
			}
		}
		var scheduler = schedulers[assignedCount];
		var result = createNettyPoller(scheduler, this, ioHandlerFactory);
		pollerTerminations[assignedCount] = result.termination();
		assignedCount++;
		eventSchedulerMappings.put(result.eventLoop(), scheduler);
		return result.eventLoop();
	}

	private static int maxId(EventLoopScheduler[] schedulers) {
		int max = 0;
		for (var s : schedulers) {
			if (s.id() > max) {
				max = s.id();
			}
		}
		return max;
	}

	@Override
	public void close() {
		try {
			shutdownGracefully(0, 0, TimeUnit.SECONDS).get();
			for (var termination : pollerTerminations) {
				if (termination != null) {
					termination.toCompletableFuture().join();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable _) {
		}
	}
}
