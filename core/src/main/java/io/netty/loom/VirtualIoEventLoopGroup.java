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

import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.loom.scheduler.EventLoopSchedulerGroup;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight event loop group for loom-friendly transports (NIO). Each event
 * loop runs as a regular virtual thread — no pinned poller registration, no
 * anti-steal guarantees.
 *
 * <p>
 * Prefer {@link VirtualIoNioPollerEventLoopGroup} for NIO when work stealing is
 * enabled — it registers a pinned poller for priority scheduling and prevents
 * the event loop from being stolen.
 *
 * <p>
 * Defaults to the global {@link EventLoopSchedulerGroup} carriers. Pass a
 * custom {@link java.util.concurrent.ThreadFactory} to use a different
 * scheduler (e.g. ForkJoinPool for benchmarking baselines).
 *
 * @see VirtualIoNioPollerEventLoopGroup
 * @see VirtualIoNativePollerEventLoopGroup
 */
public class VirtualIoEventLoopGroup extends MultiThreadIoEventLoopGroup {

	private int childIndex;

	/**
	 * Creates a group with {@code nThreads} event loops using the given thread
	 * factory.
	 */
	public VirtualIoEventLoopGroup(int nThreads, IoHandlerFactory factory, ThreadFactory threadFactory) {
		super(nThreads, (Executor) null, factory, threadFactory);
	}

	@Override
	public void close() {
		try {
			shutdownGracefully(0, 0, TimeUnit.SECONDS).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable _) {
		}
	}

	@Override
	protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory, Object... args) {
		ThreadFactory tf;
		if (args.length > 0 && args[0] instanceof ThreadFactory factory) {
			tf = factory;
		} else {
			// NIO/LOCAL don't pin — round-robin across carriers for locality
			var group = EventLoopSchedulerGroup.instance();
			tf = group.scheduler(childIndex++ % group.size()).virtualThreadFactory();
		}
		var manualTask = new ManualIoEventLoopTask(this, null, ioHandlerFactory);
		var newThread = tf.newThread(manualTask);
		manualTask.setOwningThread(newThread);
		newThread.start();
		return manualTask;
	}
}
