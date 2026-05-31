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
 * Demonstrates the broken pattern: split {@code getAcquire} +
 * {@code setRelease} at the carrier's exit from PARKED state. The signaler's
 * CAS can land between the two operations, and {@code setRelease(RUNNING)}
 * overwrites the SEARCHING value.
 *
 * <p>
 * This test SHOULD produce the FORBIDDEN outcome {@code (true, 1)} — proving
 * the split read+write is unsafe. Compare with {@link CarrierStateExitTest}.
 */
@JCStressTest
@Outcome(id = "true, 2", expect = Expect.ACCEPTABLE, desc = "CAS ok, carrier saw SEARCHING — signal delivered")
@Outcome(id = "false, 1", expect = Expect.ACCEPTABLE, desc = "CAS failed, carrier saw PARKED — spurious wake, signaler retries")
@Outcome(id = "false, 0", expect = Expect.ACCEPTABLE, desc = "CAS failed, carrier read PARKED then wrote RUNNING — signaler retries")
@Outcome(id = "true, 1", expect = Expect.ACCEPTABLE_INTERESTING, desc = "SIGNAL LOST — CAS succeeded but carrier read PARKED, nSearching leaks")
@State
public class CarrierStateExitBrokenTest {

	private static final int RUNNING = 0;
	private static final int PARKED = 1;
	private static final int SEARCHING = 2;

	private static final VarHandle STATE;
	static {
		try {
			STATE = MethodHandles.lookup().findVarHandle(CarrierStateExitBrokenTest.class, "carrierState", int.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@SuppressWarnings("unused")
	volatile int carrierState = PARKED;

	/**
	 * Carrier thread: split read + write (the bug). The CAS from the signaler can
	 * land between getAcquire and setRelease.
	 */
	@Actor
	public void carrierExitsParkedBroken(ZI_Result r) {
		r.r2 = (int) STATE.getAcquire(this);
		STATE.setRelease(this, RUNNING);
	}

	/**
	 * Signaler thread: CAS(PARKED, SEARCHING) — models wakeupAsSearcher.
	 */
	@Actor
	public void signalerWakesAsSearcher(ZI_Result r) {
		r.r1 = STATE.compareAndSet(this, PARKED, SEARCHING);
	}
}
