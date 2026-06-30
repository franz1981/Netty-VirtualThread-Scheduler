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
import io.netty.loom.scheduler.EventLoopScheduler;

import java.util.concurrent.atomic.AtomicBoolean;

public class ManualIoEventLoopTask extends ManualIoEventLoop implements Runnable {

	private static final long NETTY_ASYNC_TASKS_NS = EventLoopScheduler.YIELD_DURATION_NS;

	private final AtomicBoolean pollerRunning;

	public ManualIoEventLoopTask(IoEventLoopGroup parent, Thread owningThread, IoHandlerFactory factory) {
		this(parent, owningThread, factory, new AtomicBoolean(false));
	}

	private ManualIoEventLoopTask(IoEventLoopGroup parent, Thread owningThread, IoHandlerFactory factory,
			AtomicBoolean pollerRunning) {
		super(parent, owningThread,
				ioExecutor -> new AwakeAwareIoHandler(pollerRunning, factory.newHandler(ioExecutor)));
		this.pollerRunning = pollerRunning;
	}

	@Override
	public void run() {
		pollerRunning.set(true);
		int events = 0;
		while (!isShuttingDown()) {
			if (events == 0) {
				pollerRunning.set(false);
				events = run(0, NETTY_ASYNC_TASKS_NS);
				pollerRunning.set(true);
			} else {
				Thread.yield();
				events = runNow(NETTY_ASYNC_TASKS_NS);
			}
		}
		pollerRunning.set(false);
		while (!isTerminated()) {
			runNow();
			Thread.yield();
		}
	}
}
