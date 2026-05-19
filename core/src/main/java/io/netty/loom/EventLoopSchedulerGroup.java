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

import java.util.concurrent.ThreadFactory;

/**
 * Global pool of carrier threads, each running an {@link EventLoopScheduler}.
 * Created once at SPI init time (or lazily on first access) and never shut
 * down.
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

	private static final EventLoopSchedulerGroup INSTANCE;
	private static final Throwable UNAVAILABILITY_CAUSE;

	static {
		EventLoopSchedulerGroup group = null;
		Throwable cause = null;
		try {
			if (!io.netty.loom.spi.NettyScheduler.isAvailable()) {
				cause = new IllegalStateException(
						"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.spi.NettyScheduler is required");
			} else {
				group = new EventLoopSchedulerGroup(DEFAULT_SIZE);
			}
		} catch (Throwable t) {
			cause = t;
		}
		INSTANCE = group;
		UNAVAILABILITY_CAUSE = cause;
	}

	private static void ensureAvailability() {
		if (UNAVAILABILITY_CAUSE != null) {
			throw new IllegalStateException("EventLoopSchedulerGroup is not available", UNAVAILABILITY_CAUSE);
		}
	}

	/** Returns the global singleton. */
	public static EventLoopSchedulerGroup instance() {
		ensureAvailability();
		return INSTANCE;
	}

	private final EventLoopScheduler[] schedulers;

	private EventLoopSchedulerGroup(int size) {
		if (size <= 0) {
			throw new IllegalArgumentException("size must be > 0");
		}
		var carrierThreadFactory = Thread.ofPlatform().daemon(true).factory();
		schedulers = new EventLoopScheduler[size];
		for (int i = 0; i < size; i++) {
			schedulers[i] = new EventLoopScheduler(i, carrierThreadFactory, RESUMED_CONTINUATIONS_EXPECTED_COUNT);
		}
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
