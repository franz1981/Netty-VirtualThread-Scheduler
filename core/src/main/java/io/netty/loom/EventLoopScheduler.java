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

import io.netty.channel.ManualIoEventLoop;

interface EventLoopScheduler {

	/**
	 * A shared reference to an EventLoopScheduler, which can be cleared when the
	 * scheduler is fully terminated.
	 */
	final class SharedRef {

		private volatile EventLoopScheduler ref;

		SharedRef(EventLoopScheduler ref) {
			this.ref = ref;
		}

		public EventLoopScheduler get() {
			return ref;
		}

		void clear() {
			ref = null;
		}
	}

	/**
	 * We currently lack a way to query the current thread's assigned scheduler:
	 * this is a workaround using ScopedValues.<br>
	 * The same functionality could have been achieved (in a less hacky way) using
	 * ThreadLocals, which are way more expensive, for VirtualThreads.<br>
	 * Read sub-poller(s) sadly won't work as expected with this, because are not
	 * created using the event loop scheduler factory.
	 */
	final class SchedulingContext {

		long vThreadId;
		final boolean isPoller;
		final SharedRef schedulerRef;

		SchedulingContext(long vThreadId, SharedRef schedulerRef, boolean isPoller) {
			this.vThreadId = vThreadId;
			this.schedulerRef = schedulerRef;
			this.isPoller = isPoller;
		}

		SchedulingContext(SharedRef schedulerRef) {
			this(-1, schedulerRef, false);
		}

		/**
		 * Get the assigned scheduler for the current thread, or null if this thread is
		 * not assigned to any scheduler.
		 */
		public SharedRef scheduler() {
			// TODO consider returning EMPTY_SCHEDULER_REF instead of null
			return (Thread.currentThread().threadId() == vThreadId) ? schedulerRef : null;
		}
	}

	ScopedValue<SchedulingContext> CURRENT_SCHEDULER = ScopedValue.newInstance();
	SchedulingContext EMPTY_SCHEDULER_CONTEXT = new SchedulingContext(-1, null, false);

	static void runWithContext(Runnable runnable, SchedulingContext schedulingContext) {
		ScopedValue.where(CURRENT_SCHEDULER, schedulingContext).run(runnable);
	}

	/**
	 * Get the current thread's associated EventLoopScheduler context, or an empty
	 * one if none is associated.
	 */
	static SchedulingContext currentThreadSchedulerContext() {
		return CURRENT_SCHEDULER.orElse(EMPTY_SCHEDULER_CONTEXT);
	}

	ThreadFactory virtualThreadFactory();

	ManualIoEventLoop ioEventLoop();

	Thread carrierThread();

	int externalContinuationsCount();

	boolean execute(Thread.VirtualThreadTask task);
}
