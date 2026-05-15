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

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.ZZ_Result;

/**
 * Verifies the {@link BlockingPollGuard} invariant: if external work is
 * enqueued, it is always consumed — either the poller sees it (won't block) or
 * the wakeup fires (will interrupt the blocking call).
 *
 * <h2>Why this matters</h2>
 *
 * A transport (epoll, NIO) handles its own I/O events and task wakeups
 * internally — those don't need the guard. The guard coordinates the
 * <em>external</em> signal: a virtual thread continuation submitted to the
 * scheduler's MPSC queue. If the poller blocks and the wakeup doesn't fire, the
 * carrier misses a chance to drain the run queue and execute the continuation.
 *
 * <h2>What this tests</h2>
 *
 * We test the <em>moment of commitment</em>: the poller sets its sleeping flag
 * and checks the queue; the producer enqueues work and checks the sleeping
 * flag. The guard's store-barrier-load protocol guarantees at least one side
 * sees the other's store.
 *
 * <h2>Why exitPoll is omitted</h2>
 *
 * In real code, a blocking I/O call (e.g. {@code epoll_wait}) sits between
 * {@code enterPoll} and {@code exitPoll}. The blocking call holds the sleeping
 * flag open while the poller is actually blocked, so the producer can see it
 * and fire the wakeup.
 *
 * <p>
 * JCStress can't model a blocking call — actors run to completion instantly.
 * Calling {@code exitPoll} would write {@code sleeping=false} immediately after
 * {@code enterPoll} wrote {@code sleeping=true}. The producer would see the
 * second write (false) instead of the first (true), missing the window where
 * the poller was actually sleeping. This creates a false "missed wakeup" that
 * doesn't occur in real code where the blocking call takes time.
 *
 * <p>
 * By omitting {@code exitPoll}, the test keeps the sleeping flag true when the
 * poller commits to blocking, which is exactly the state during a real blocking
 * I/O call. The test verifies the entry decision: did the guard's protocol
 * ensure at least one side saw the other's store?
 *
 * <p>
 * The FORBIDDEN outcome: the poller didn't see the work AND no wakeup was
 * fired. The poller is stuck sleeping with a VT continuation in the queue.
 *
 * @see BlockingPollGuardBrokenTest
 */
@JCStressTest
@Outcome(id = "true, true", expect = Expect.ACCEPTABLE, desc = "Wakeup fired AND poller saw work — redundant wakeup, safe")
@Outcome(id = "true, false", expect = Expect.ACCEPTABLE, desc = "Wakeup fired, poller didn't see work — wakeup will interrupt blocking I/O, safe")
@Outcome(id = "false, true", expect = Expect.ACCEPTABLE, desc = "No wakeup, poller saw work — no wakeup needed, safe")
@Outcome(id = "false, false", expect = Expect.FORBIDDEN, desc = "MISSED WAKEUP — poller didn't see work AND no wakeup, VT continuation starves")
@State
public class BlockingPollGuardTest {

	/** Models the scheduler's MPSC run queue. */
	volatile boolean externalWorkQueued;

	/** Whether the transport's wakeup (e.g. eventfd_write) was actually called. */
	boolean transportWakeupFired;

	/** The actual guard under test. */
	final BlockingPollGuard guard = new BlockingPollGuard(() -> !externalWorkQueued, () -> transportWakeupFired = true);

	/**
	 * Scheduler: a foreign thread offers a VT continuation to the run queue, then
	 * calls the guard's wakeup.
	 *
	 * @param r
	 *            r1 = true if the transport wakeup was called
	 */
	@Actor
	public void schedulerSubmitsExternalWork(ZZ_Result r) {
		externalWorkQueued = true;
		guard.wakeup();
		r.r1 = transportWakeupFired;
	}

	/**
	 * Poller: calls the guard's enterPoll. If not blocking (saw work), consumes it.
	 * exitPoll is omitted — see class javadoc for why.
	 *
	 * @param r
	 *            r2 = true if the poller consumed the work
	 */
	@Actor
	public void pollerDecidesBlockOrNot(ZZ_Result r) {
		boolean blocking = guard.enterPoll(true);
		if (!blocking) {
			externalWorkQueued = false;
			r.r2 = true;
		}
	}
}
