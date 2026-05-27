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
package io.netty.loom.scheduler;

/**
 * Global pool of carrier threads, each running an {@link EventLoopScheduler}.
 * Created eagerly by {@link NettyScheduler} at JVM startup and never shut down.
 *
 * <p>
 * Size defaults to {@code availableProcessors()} and can be overridden with
 * {@code -Dio.netty.loom.schedulers=N}.
 */
public class EventLoopSchedulerGroup {

	private static final int DEFAULT_SIZE = Integer.getInteger("io.netty.loom.schedulers",
			Runtime.getRuntime().availableProcessors());
	private static final int RESUMED_CONTINUATIONS_EXPECTED_COUNT = Integer
			.getInteger("io.netty.loom.resumed.continuations", 1024);

	private final EventLoopScheduler[] schedulers;

	EventLoopSchedulerGroup(NettyScheduler scheduler) {
		this(DEFAULT_SIZE, scheduler);
	}

	private EventLoopSchedulerGroup(int size, NettyScheduler scheduler) {
		if (size <= 0) {
			throw new IllegalArgumentException("size must be > 0");
		}
		var carrierThreadFactory = Thread.ofPlatform().daemon(true).factory();
		schedulers = new EventLoopScheduler[size];
		for (int i = 0; i < size; i++) {
			schedulers[i] = new EventLoopScheduler(i, carrierThreadFactory, RESUMED_CONTINUATIONS_EXPECTED_COUNT,
					scheduler);
		}
		if (EventLoopScheduler.WORK_STEALING_ENABLED && size > 1) {
			for (int i = 0; i < size; i++) {
				var siblings = new EventLoopScheduler[size - 1];
				int idx = 0;
				for (int j = 0; j < size; j++) {
					if (j != i) {
						siblings[idx++] = schedulers[j];
					}
				}
				schedulers[i].setSiblings(siblings);
			}
		}
	}

	/**
	 * Returns the global singleton. Shortcut for {@link NettyScheduler#group()}.
	 */
	public static EventLoopSchedulerGroup instance() {
		return NettyScheduler.group();
	}

	/** Returns the number of carriers in the pool. */
	public int size() {
		return schedulers.length;
	}

	/** Returns the scheduler for the carrier at the given index. */
	public EventLoopScheduler scheduler(int index) {
		return schedulers[index];
	}

	/**
	 * Returns {@code count} schedulers that have no registered pinned poller, or
	 * {@code null} if not enough are available.
	 */
	public EventLoopScheduler[] availableSchedulers(int count) {
		var result = new EventLoopScheduler[count];
		int found = 0;
		for (int i = 0; i < schedulers.length && found < count; i++) {
			if (!schedulers[i].hasRegisteredPinnedPoller()) {
				result[found++] = schedulers[i];
			}
		}
		return found == count ? result : null;
	}
}
