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
 * Lock-free bitmap tracking which carriers in a cluster are currently idle.
 * Accepts global carrier IDs and maps them to compact cluster-local indices
 * internally. The bitmap is sized to the cluster member count — no wasted bits.
 */
final class IdleCarrierTracker {

	private static final VarHandle WORDS = MethodHandles.arrayElementVarHandle(long[].class);

	private final EventLoopScheduler[] members;
	private final int[] idToIndex;
	private final int[] indexToId;
	private final long[] words;
	private final int capacity;

	IdleCarrierTracker(EventLoopScheduler[] members) {
		this.members = members;
		this.capacity = members.length;
		this.words = new long[(capacity + 63) >>> 6];
		this.indexToId = new int[capacity];
		int maxId = 0;
		for (int i = 0; i < capacity; i++) {
			indexToId[i] = members[i].id();
			maxId = Math.max(maxId, members[i].id());
		}
		this.idToIndex = new int[maxId + 1];
		java.util.Arrays.fill(idToIndex, -1);
		for (int i = 0; i < capacity; i++) {
			idToIndex[members[i].id()] = i;
		}
	}

	IdleCarrierTracker(int carrierCount) {
		this.members = null;
		this.idToIndex = null;
		this.indexToId = null;
		this.capacity = carrierCount;
		this.words = new long[(carrierCount + 63) >>> 6];
	}

	void markIdle(int carrierId) {
		int idx = toIndex(carrierId);
		int wordIndex = idx >>> 6;
		long bit = 1L << idx;
		long prev;
		do {
			prev = (long) WORDS.getVolatile(words, wordIndex);
		} while (!WORDS.compareAndSet(words, wordIndex, prev, prev | bit));
	}

	void markActive(int carrierId) {
		int idx = toIndex(carrierId);
		int wordIndex = idx >>> 6;
		long bit = 1L << idx;
		long prev;
		do {
			prev = (long) WORDS.getVolatile(words, wordIndex);
		} while (!WORDS.compareAndSet(words, wordIndex, prev, prev & ~bit));
	}

	/**
	 * Returns the global ID of any idle carrier, or -1 if none.
	 */
	int findIdle() {
		for (int w = 0; w < words.length; w++) {
			long word = (long) WORDS.getVolatile(words, w);
			if (word != 0) {
				int idx = (w << 6) + Long.numberOfTrailingZeros(word);
				return idx < capacity ? toId(idx) : -1;
			}
		}
		return -1;
	}

	boolean isIdle(int carrierId) {
		int idx = toIndex(carrierId);
		int wordIndex = idx >>> 6;
		long bit = 1L << idx;
		return ((long) WORDS.getVolatile(words, wordIndex) & bit) != 0;
	}

	boolean wakeFirstIdle(EventLoopScheduler victim) {
		for (int w = 0; w < words.length; w++) {
			long word = (long) WORDS.getVolatile(words, w);
			while (word != 0) {
				int bit = Long.numberOfTrailingZeros(word);
				int idx = (w << 6) + bit;
				if (idx < capacity && members[idx].wakeupAsSearcher(victim)) {
					return true;
				}
				word &= ~(1L << bit);
			}
		}
		return false;
	}

	private int toIndex(int carrierId) {
		return idToIndex != null ? idToIndex[carrierId] : carrierId;
	}

	private int toId(int index) {
		return indexToId != null ? indexToId[index] : index;
	}
}
