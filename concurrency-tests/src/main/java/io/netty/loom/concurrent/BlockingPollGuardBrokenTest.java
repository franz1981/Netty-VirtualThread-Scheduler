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
 * Demonstrates what happens when a poller checks for external work and enters
 * blocking I/O <b>without</b> the {@link BlockingPollGuard} protocol — no
 * sleeping flag, no wakeup suppression, just a plain read of the queue.
 *
 * <h2>What this proves</h2>
 *
 * The (false, true) outcome — the poller sees an empty queue and would block,
 * while the scheduler doesn't call wakeup because it has no sleeping flag to
 * check — is observable. A VT continuation sits in the run queue while the
 * poller sleeps in {@code epoll_wait}. The carrier misses the chance to execute
 * it.
 *
 * <p>
 * Compare with {@link BlockingPollGuardTest} where the volatile sleeping flag
 * and wakeup suppression eliminate this outcome entirely.
 *
 * @see BlockingPollGuardTest
 */
@JCStressTest
@Outcome(id = "true, true", expect = Expect.ACCEPTABLE, desc = "Wakeup called AND poller blocks — safe")
@Outcome(id = "true, false", expect = Expect.ACCEPTABLE, desc = "Wakeup called AND poller won't block — safe")
@Outcome(id = "false, false", expect = Expect.ACCEPTABLE, desc = "No wakeup AND poller won't block — safe")
@Outcome(id = "false, true", expect = Expect.ACCEPTABLE_INTERESTING, desc = "LOST SIGNAL — poller blocks, no wakeup, no guard to protect")
@State
public class BlockingPollGuardBrokenTest {

	/** Models the scheduler's MPSC run queue. */
	volatile boolean externalWorkQueued;

	/** Whether the transport's wakeup was actually called. */
	boolean transportWakeupFired;

	/**
	 * Scheduler: offers a VT continuation, but has no sleeping flag to check —
	 * always calls wakeup unconditionally.
	 *
	 * <p>
	 * Wait — even unconditional wakeup doesn't help here. The problem is that
	 * without a sleeping flag, the wakeup fires BEFORE the poller decides to block.
	 * If the wakeup is edge-triggered (not sticky), it's lost.
	 *
	 * <p>
	 * We model this as: the scheduler enqueues, but does NOT call wakeup at all (no
	 * sleeping flag to trigger it). This is the naive case where the scheduler has
	 * no coordination with the poller.
	 */
	@Actor
	public void schedulerSubmitsExternalWork(ZZ_Result r) {
		externalWorkQueued = true;
		// no guard → no sleeping flag to check → no wakeup
		r.r1 = transportWakeupFired;
	}

	/**
	 * Poller: just checks the queue directly and decides to block if empty. No
	 * sleeping flag advertisement, no guard protocol.
	 */
	@Actor
	public void pollerDecidesBlockOrNot(ZZ_Result r) {
		r.r2 = !externalWorkQueued;
	}
}
