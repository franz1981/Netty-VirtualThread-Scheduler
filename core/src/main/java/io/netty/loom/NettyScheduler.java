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

/**
 * Global Netty scheduler proxy for virtual threads.
 *
 * <p>
 * Inheritance rule (exact): a newly started virtual thread inherits the
 * caller's {@code EventLoopScheduler} only when both conditions are true:
 * <ol>
 * <li>{@code jdk.pollerMode} is {@code 3} (per-carrier pollers); and</li>
 * <li>the thread performing the start/poller I/O is itself running under an
 * {@code EventLoopScheduler} (i.e.
 * {@code EventLoopScheduler.currentThreadSchedulerContext().scheduler()}
 * returns a non-null {@code SharedRef}).</li>
 * </ol>
 *
 * <p>
 * The current implementation only attempts scheduler inheritance for
 * poller-created virtual threads (recognized by the {@code "-Read-Poller"} name
 * suffix). If either condition above is not met (or the thread kind is
 * unrecognized) the virtual thread falls back to the default JDK scheduler.
 *
 * <p>
 * This class is a proxy/dispatcher and does not implement a standalone
 * scheduling policy. See {@link EventLoopScheduler} for details about scheduler
 * attachment and execution.
 */

public class NettyScheduler implements Thread.VirtualThreadScheduler {

	static volatile NettyScheduler INSTANCE;

	private final Thread.VirtualThreadScheduler jdkBuildinScheduler;

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
		if (virtualThreadTask.attachment() instanceof EventLoopScheduler.SchedulingContext context) {
			var eventLoop = context.schedulerRef.get();
			if (eventLoop != null && eventLoop.execute(virtualThreadTask)) {
				return;
			}
			// the v thread has been rejected by its assigned scheduler or its scheduler is
			// gone
			virtualThreadTask.attach(null);
		} else {
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
							virtualThreadTask.attach(new EventLoopScheduler.SchedulingContext(
									virtualThreadTask.thread().threadId(), schedulerRef, true));
							if (scheduler.execute(virtualThreadTask)) {
								return;
							}
							virtualThreadTask.attach(null);
						}
					}
				}
			}
		}
		jdkBuildinScheduler.onStart(virtualThreadTask);
	}

	@Override
	public void onContinue(Thread.VirtualThreadTask virtualThreadTask) {
		if (virtualThreadTask.attachment() instanceof EventLoopScheduler.SchedulingContext context) {
			var eventLoop = context.schedulerRef.get();
			if (eventLoop != null && eventLoop.execute(virtualThreadTask)) {
				return;
			}
			// the v thread has been rejected by its assigned scheduler or its scheduler is
			// gone
			virtualThreadTask.attach(null);
		}
		jdkBuildinScheduler.onContinue(virtualThreadTask);
	}

	public static boolean perCarrierPollers() {
		return ensureInstalled().perCarrierPollers;
	}

	public static boolean isAvailable() {
		return ensureInstalled() != null;
	}
}
