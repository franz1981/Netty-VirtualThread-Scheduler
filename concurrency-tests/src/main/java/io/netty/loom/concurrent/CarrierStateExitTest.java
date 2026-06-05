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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.ZI_Result;

/**
 * Validates the carrier's exit from PARKED state: the carrier must atomically
 * consume any signal written by {@code wakeupAsSearcher}.
 *
 * <p>
 * The race: a carrier exits PARKED while a signaler concurrently CAS's the
 * state to SEARCHING. With the correct implementation ({@code getAndSet}), the
 * carrier always sees the SEARCHING value if the CAS succeeded. With a split
 * read+write ({@code getAcquire} then {@code setRelease}), the CAS can land
 * between them and get overwritten — the carrier reads PARKED, the signaler
 * thinks it woke the carrier, and the nSearching decrement is lost.
 *
 * <p>
 * FORBIDDEN: signaler CAS succeeded AND carrier read PARKED. That means the
 * signal was accepted but lost — nSearching leaks.
 *
 * @see CarrierStateExitBrokenTest
 */
@JCStressTest
@Outcome(id = "true, 2", expect = Expect.ACCEPTABLE, desc = "CAS ok, carrier saw SEARCHING — signal delivered")
@Outcome(id = "false, 1", expect = Expect.ACCEPTABLE, desc = "CAS failed, carrier saw PARKED — spurious wake, signaler retries")
@Outcome(id = "false, 0", expect = Expect.ACCEPTABLE, desc = "CAS failed, carrier already consumed PARKED via getAndSet — signaler retries")
@Outcome(id = "true, 1", expect = Expect.FORBIDDEN, desc = "SIGNAL LOST — CAS succeeded but carrier read PARKED, nSearching leaks")
@State
public class CarrierStateExitTest {

	private static final int RUNNING = 0;
	private static final int PARKED = 1;
	private static final int SEARCHING = 2;

	private static final VarHandle STATE;
	static {
		try {
			STATE = MethodHandles.lookup().findVarHandle(CarrierStateExitTest.class, "carrierState", int.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@SuppressWarnings("unused")
	volatile int carrierState = PARKED;

	/**
	 * Carrier thread: atomically reads and resets state to RUNNING (the fix).
	 */
	@Actor
	public void carrierExitsParked(ZI_Result r) {
		r.r2 = (int) STATE.getAndSet(this, RUNNING);
	}

	/**
	 * Signaler thread: CAS(PARKED, SEARCHING) — models wakeupAsSearcher.
	 */
	@Actor
	public void signalerWakesAsSearcher(ZI_Result r) {
		r.r1 = STATE.compareAndSet(this, PARKED, SEARCHING);
	}
}
