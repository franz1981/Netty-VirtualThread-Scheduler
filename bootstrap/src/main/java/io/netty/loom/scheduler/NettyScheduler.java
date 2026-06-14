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
package io.netty.loom.scheduler;

/**
 * Custom virtual thread scheduler loaded by the JDK via
 * {@code -Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler}.
 *
 * <p>
 * Eagerly creates the {@link EventLoopSchedulerGroup carrier pool} at
 * construction time — if the JDK is told to use this scheduler, the carriers
 * are needed.
 *
 * <p>
 * Routes virtual thread tasks to their home {@link EventLoopScheduler} when one
 * is assigned (via {@link EventLoopScheduler.SchedulingContext} attachment),
 * otherwise falls back to the JDK's built-in scheduler.
 *
 * <p>
 * When {@code jdk.pollerMode=3} (per-carrier pollers), JDK-internal read-poller
 * threads inherit the caller's scheduler so they stay on the same carrier.
 */
public class NettyScheduler implements Thread.VirtualThreadScheduler {

	static final boolean REPLACE_BUILTIN_SCHEDULER = Boolean
			.parseBoolean(System.getProperty("io.netty.loom.replaceBuiltinScheduler", "false"));

	private static volatile NettyScheduler INSTANCE;

	private final Thread.VirtualThreadScheduler jdkBuiltinScheduler;
	private final EventLoopSchedulerGroup group;
	private final boolean perCarrierPollers;
	private final java.util.concurrent.ConcurrentHashMap<Thread, Thread.VirtualThreadTask> adoptedTasks = REPLACE_BUILTIN_SCHEDULER
			? new java.util.concurrent.ConcurrentHashMap<>()
			: null;

	public NettyScheduler(Thread.VirtualThreadScheduler jdkBuiltinScheduler) {
		this.jdkBuiltinScheduler = jdkBuiltinScheduler;
		this.perCarrierPollers = Integer.getInteger("jdk.pollerMode", -1) == 3;
		this.group = new EventLoopSchedulerGroup(this);
		INSTANCE = this;
	}

	public static NettyScheduler instance() {
		return ensureInstalled();
	}

	/**
	 * Returns the global carrier pool, or throws if the scheduler is not installed.
	 */
	public static EventLoopSchedulerGroup group() {
		var scheduler = ensureInstalled();
		if (scheduler == null) {
			throw new IllegalStateException(
					"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler is required");
		}
		return scheduler.group;
	}

	@Override
	public void onStart(Thread.VirtualThreadTask task) {
		if (task.attachment() instanceof EventLoopScheduler.SchedulingContext context) {
			var scheduler = context.assignedScheduler();
			if (scheduler != null) {
				scheduler.execute(task);
				return;
			}
			task.attach(null);
		} else {
			if (perCarrierPollers && Thread.currentThread().isVirtual()) {
				var scheduler = EventLoopScheduler.currentThreadSchedulerContext().runningScheduler();
				if (scheduler != null && task.thread().getName().endsWith("-Read-Poller")) {
					task.attach(new EventLoopScheduler.SchedulingContext(task.thread().threadId(), scheduler,
							EventLoopScheduler.VThreadType.JDK_POLLER));
					scheduler.execute(task);
					return;
				}
			}
			if (REPLACE_BUILTIN_SCHEDULER) {
				var scheduler = group.selectScheduler();
				var context = new EventLoopScheduler.SchedulingContext(task.thread().threadId(), scheduler,
						EventLoopScheduler.VThreadType.ADOPTED);
				task.attach(context);
				adoptedTasks.put(task.thread(), task);
				scheduler.execute(task);
				return;
			}
		}
		jdkBuiltinScheduler.onStart(task);
	}

	@Override
	public void onContinue(Thread.VirtualThreadTask task) {
		if (task.attachment() instanceof EventLoopScheduler.SchedulingContext context) {
			var scheduler = context.assignedScheduler();
			if (scheduler != null) {
				scheduler.execute(task);
				return;
			}
			task.attach(null);
		}
		jdkBuiltinScheduler.onContinue(task);
	}

	private static NettyScheduler ensureInstalled() {
		var instance = INSTANCE;
		if (instance != null) {
			return instance;
		}
		Thread.ofVirtual().unstarted(() -> {
		});
		return INSTANCE;
	}

	public static boolean isAvailable() {
		return ensureInstalled() != null;
	}

	EventLoopScheduler.SchedulingContext lookupAdoptedContext(Thread thread) {
		if (adoptedTasks == null) {
			return null;
		}
		var task = adoptedTasks.get(thread);
		if (task != null && task.attachment() instanceof EventLoopScheduler.SchedulingContext ctx) {
			return ctx;
		}
		return null;
	}

	void removeAdopted(Thread thread) {
		if (adoptedTasks != null) {
			adoptedTasks.remove(thread);
		}
	}
}
