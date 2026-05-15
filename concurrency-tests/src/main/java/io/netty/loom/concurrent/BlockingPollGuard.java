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
package io.netty.loom.concurrent;

import java.util.function.BooleanSupplier;

/**
 * Encapsulates the sleep/wakeup coordination protocol for a poller that wants
 * to block in kernel I/O when idle, while remaining responsive to external
 * events (e.g. virtual thread continuations offered to a scheduler's run
 * queue).
 *
 * <p>
 * Handles the store-barrier-load pattern from <a href=
 * "https://www.scylladb.com/2018/02/15/memory-barriers-seastar-linux/">Seastar</a>:
 * the poller advertises it's about to sleep (volatile store) before checking
 * the external queue (load), with a StoreLoad barrier between the two so the
 * load cannot slip before the advertisement. On the producer side, the
 * symmetric pattern ensures at least one side always sees the other's store.
 *
 * <p>
 * Also suppresses redundant wakeup calls when the poller is actively running —
 * the transport's wakeup (often a syscall like {@code eventfd_write}) is only
 * called through when the poller is actually sleeping.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * var guard = new BlockingPollGuard(scheduler::canBlock, transport::wakeup);
 *
 * scheduler.registerPinnedPoller(guard::wakeup, () -> {
 * 	boolean idle = false;
 * 	while (!shutdown) {
 * 		boolean blocking = guard.enterPoll(idle);
 * 		try {
 * 			if (blocking) {
 * 				events = transport.blockingPoll(fd, buf, maxEvents, timeout);
 * 			} else {
 * 				events = transport.nonBlockingPoll(fd, buf, maxEvents);
 * 			}
 * 		} finally {
 * 			guard.exitPoll();
 * 		}
 * 		scheduler.maybeYield();
 * 		tasks = processTasks();
 * 		scheduler.maybeYield();
 * 		idle = (events == 0 && tasks == 0);
 * 	}
 * });
 * }</pre>
 *
 * @see <a href=
 *      "https://www.scylladb.com/2018/02/15/memory-barriers-seastar-linux/">Seastar
 *      memory barriers</a>
 * @see <a href=
 *      "https://groups.google.com/g/mechanical-sympathy/c/yKQNVFAjui0/m/NAhfyjT-BAAJ">
 *      Mechanical Sympathy: Condition.signal pitfall</a>
 */
public final class BlockingPollGuard {

	private final BooleanSupplier canBlock;
	private final Runnable transportWakeup;
	private volatile boolean sleeping;

	/**
	 * @param canBlock
	 *            returns {@code true} if no external work is pending (e.g.
	 *            {@code scheduler::canBlock})
	 * @param transportWakeup
	 *            interrupts the transport's blocking I/O (e.g.
	 *            {@code eventLoop::wakeup})
	 */
	public BlockingPollGuard(BooleanSupplier canBlock, Runnable transportWakeup) {
		this.canBlock = canBlock;
		this.transportWakeup = transportWakeup;
	}

	/**
	 * Determines whether the poller should enter blocking I/O. Handles the
	 * store-barrier-load protocol internally.
	 *
	 * <p>
	 * Always pair with {@link #exitPoll()} in a {@code finally} block:
	 *
	 * <pre>{@code
	 * boolean blocking = guard.enterPoll(idle);
	 * try {
	 * 	if (blocking)
	 * 		blockingPoll();
	 * 	else
	 * 		nonBlockingPoll();
	 * } finally {
	 * 	guard.exitPoll();
	 * }
	 * }</pre>
	 *
	 * @param idle
	 *            {@code true} if the previous iteration had no I/O events and no
	 *            tasks — a hint that blocking is worthwhile
	 * @return {@code true} if the poller should enter blocking I/O
	 */
	public boolean enterPoll(boolean idle) {
		if (!idle) {
			return false;
		}
		sleeping = true;
		boolean block = false;
		try {
			block = canBlock.getAsBoolean();
		} finally {
			if (!block) {
				sleeping = false;
			}
		}
		return block;
	}

	/**
	 * Clears the sleeping advertisement if it was set by {@link #enterPoll}. Must
	 * be called in a {@code finally} block after every {@code enterPoll} call.
	 */
	public void exitPoll() {
		if (sleeping) {
			sleeping = false;
		}
	}

	/**
	 * Wakeup that suppresses the transport's wakeup syscall when the poller is
	 * actively running (not sleeping). Pass {@code guard::wakeup} to the scheduler
	 * as the wakeup Runnable.
	 */
	public void wakeup() {
		if (sleeping) {
			transportWakeup.run();
		}
	}
}
