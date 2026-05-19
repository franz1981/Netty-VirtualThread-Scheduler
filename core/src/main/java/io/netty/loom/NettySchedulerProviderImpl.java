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

import io.netty.loom.spi.NettySchedulerSpi;

/**
 * Default {@link NettySchedulerSpi} implementation that integrates
 * virtual-thread scheduling with Netty event loops.
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
 */
public class NettySchedulerProviderImpl implements NettySchedulerSpi {

	private Thread.VirtualThreadScheduler jdkBuiltinScheduler;
	private final boolean perCarrierPollers;

	public NettySchedulerProviderImpl() {
		this.perCarrierPollers = Integer.getInteger("jdk.pollerMode", -1) == 3;
	}

	@Override
	public void init(Thread.VirtualThreadScheduler jdkBuiltinScheduler) {
		this.jdkBuiltinScheduler = jdkBuiltinScheduler;
		EventLoopSchedulerGroup.init();
	}

	@Override
	public boolean expectsPerCarrierPollers() {
		return perCarrierPollers;
	}

	@Override
	public void onStart(Thread.VirtualThreadTask virtualThreadTask) {
		if (virtualThreadTask.attachment() instanceof EventLoopScheduler.SchedulingContext context) {
			var eventLoop = context.eventLoopScheduler;
			if (eventLoop != null && eventLoop.execute(virtualThreadTask)) {
				return;
			}
			virtualThreadTask.attach(null);
		} else {
			if (perCarrierPollers) {
				if (Thread.currentThread().isVirtual()) {
					var scheduler = EventLoopScheduler.currentThreadSchedulerContext().runningScheduler();
					if (scheduler != null) {
						if (virtualThreadTask.thread().getName().endsWith("-Read-Poller")) {
							virtualThreadTask.attach(
									new EventLoopScheduler.SchedulingContext(virtualThreadTask.thread().threadId(),
											scheduler, EventLoopScheduler.VThreadType.JDK_POLLER));
							if (scheduler.execute(virtualThreadTask)) {
								return;
							}
							virtualThreadTask.attach(null);
						}
					}
				}
			}
		}
		jdkBuiltinScheduler.onStart(virtualThreadTask);
	}

	@Override
	public void onContinue(Thread.VirtualThreadTask virtualThreadTask) {
		if (virtualThreadTask.attachment() instanceof EventLoopScheduler.SchedulingContext context) {
			var eventLoop = context.eventLoopScheduler;
			if (eventLoop != null && eventLoop.execute(virtualThreadTask)) {
				return;
			}
			virtualThreadTask.attach(null);
		}
		jdkBuiltinScheduler.onContinue(virtualThreadTask);
	}
}
