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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Lock-free bitmap tracking which carriers are currently idle (parked).
 *
 * <p>
 * Carriers call {@link #markIdle(int)} before parking and
 * {@link #markActive(int)} after waking. External submitters call
 * {@link #findIdle()} to locate an idle carrier without waking a busy one.
 *
 * <p>
 * Internally a {@code long[]} where each word covers 64 carrier IDs. The
 * typical case (≤64 carriers) uses a single word. Scales to 1024+ carriers with
 * O(N/64) scan.
 */
final class IdleCarrierTracker {

	private static final VarHandle WORDS = MethodHandles.arrayElementVarHandle(long[].class);

	private final long[] words;
	private final int capacity;

	IdleCarrierTracker(int carrierCount) {
		this.capacity = carrierCount;
		this.words = new long[(carrierCount + 63) >>> 6];
	}

	/**
	 * Marks the carrier as idle. Called by the carrier thread before parking.
	 */
	void markIdle(int carrierId) {
		int wordIndex = carrierId >>> 6;
		long bit = 1L << carrierId; // only lower 6 bits used by shift
		long prev;
		do {
			prev = (long) WORDS.getVolatile(words, wordIndex);
		} while (!WORDS.compareAndSet(words, wordIndex, prev, prev | bit));
	}

	/**
	 * Marks the carrier as active. Called by the carrier thread after waking.
	 */
	void markActive(int carrierId) {
		int wordIndex = carrierId >>> 6;
		long bit = 1L << carrierId;
		long prev;
		do {
			prev = (long) WORDS.getVolatile(words, wordIndex);
		} while (!WORDS.compareAndSet(words, wordIndex, prev, prev & ~bit));
	}

	/**
	 * Returns the ID of any idle carrier, or -1 if none. Does not remove the
	 * carrier from the idle set — the carrier clears its own bit on wake.
	 */
	int findIdle() {
		for (int w = 0; w < words.length; w++) {
			long word = (long) WORDS.getVolatile(words, w);
			if (word != 0) {
				return (w << 6) + Long.numberOfTrailingZeros(word);
			}
		}
		return -1;
	}

	/**
	 * Returns true if the given carrier is currently marked idle.
	 */
	boolean isIdle(int carrierId) {
		int wordIndex = carrierId >>> 6;
		long bit = 1L << carrierId;
		return ((long) WORDS.getVolatile(words, wordIndex) & bit) != 0;
	}

	boolean wakeFirstIdle(EventLoopSchedulerGroup group, EventLoopScheduler victim) {
		for (int w = 0; w < words.length; w++) {
			long word = (long) WORDS.getVolatile(words, w);
			while (word != 0) {
				int bit = Long.numberOfTrailingZeros(word);
				int carrierId = (w << 6) + bit;
				if (carrierId < capacity && group.scheduler(carrierId).wakeupAsSearcher(victim)) {
					return true;
				}
				word &= ~(1L << bit);
			}
		}
		return false;
	}
}
