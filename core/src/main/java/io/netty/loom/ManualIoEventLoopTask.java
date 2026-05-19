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

import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ManualIoEventLoop;

import java.util.concurrent.TimeUnit;

import static io.netty.loom.EventLoopScheduler.currentScheduler;

public class ManualIoEventLoopTask extends ManualIoEventLoop implements Runnable {

	private static final long RUNNING_YIELD_US = TimeUnit.MICROSECONDS
			.toNanos(Integer.getInteger("io.netty.loom.running.yield.us", 1));

	public ManualIoEventLoopTask(IoEventLoopGroup parent, Thread owningThread, IoHandlerFactory factory) {
		super(parent, owningThread, factory);
	}

	@Override
	public void run() {
		var scheduler = currentScheduler();
		while (!isShuttingDown()) {
			run(0, RUNNING_YIELD_US);
			maybeYield(scheduler);
			runNonBlockingTasks(RUNNING_YIELD_US);
			maybeYield(scheduler);
		}
		while (!isTerminated()) {
			runNow();
			maybeYield(scheduler);
		}
	}

	private static void maybeYield(EventLoopScheduler scheduler) {
		if (scheduler != null) {
			scheduler.maybeYield();
		} else {
			Thread.yield();
		}
	}
}
