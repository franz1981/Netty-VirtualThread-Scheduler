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

public class VirtualMultithreadManualIoEventLoopGroup extends MultiThreadIoEventLoopGroup {

	private ThreadFactory eventLoopThreadFactory;

	public VirtualMultithreadManualIoEventLoopGroup(int nThreads, IoHandlerFactory factory) {
		super(nThreads, factory);
	}

	@Override
	protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory, Object... args) {
		if (eventLoopThreadFactory == null) {
			eventLoopThreadFactory = newDefaultThreadFactory();
		}
		var manualTask = new ManualIoEventLoopTask(this, null, ioHandlerFactory);
		var newThread = eventLoopThreadFactory.newThread(manualTask);
		manualTask.setOwningThread(newThread);
		newThread.start();
		return manualTask;
	}

	@Override
	protected ThreadFactory newDefaultThreadFactory() {
		return Thread.ofVirtual().name("VirtualIoEventLoop-").roundRobinAffinity().factory();
	}
}
