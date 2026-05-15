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

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Event loop group for transports that don't pin the carrier (NIO, LOCAL). Each
 * event loop runs as a virtual thread with carrier affinity — NIO parks via
 * Loom, freeing the carrier for other work.
 *
 * <p>
 * Defaults to the global {@link EventLoopSchedulerGroup} carriers. Pass a
 * custom {@link java.util.concurrent.ThreadFactory} to use a different
 * scheduler (e.g. ForkJoinPool for benchmarking baselines).
 *
 * <p>
 * Use {@link VirtualIoPollerEventLoopGroup} for native transports (EPOLL,
 * IO_URING) that need poller pinning.
 *
 * @see VirtualIoPollerEventLoopGroup
 */
public class VirtualIoEventLoopGroup extends MultiThreadIoEventLoopGroup {

	private int childIndex;

	/**
	 * Creates a group with {@code nThreads} event loops on the global scheduler
	 * carriers.
	 */
	public VirtualIoEventLoopGroup(int nThreads, IoHandlerFactory factory) {
		super(nThreads, (Executor) null, factory);
	}

	/**
	 * Creates a group with {@code nThreads} event loops using the given thread
	 * factory.
	 */
	public VirtualIoEventLoopGroup(int nThreads, IoHandlerFactory factory, ThreadFactory threadFactory) {
		super(nThreads, (Executor) null, factory, threadFactory);
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
