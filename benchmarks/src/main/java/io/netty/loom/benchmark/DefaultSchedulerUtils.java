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
package io.netty.loom.benchmark;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class DefaultSchedulerUtils {

	public static void setupDefaultScheduler(int parallelism) {
		int maxPoolSize = Integer.max(parallelism, 256);
		int minRunnable = Integer.max(parallelism / 2, 1);
		System.setProperty("jdk.virtualThreadScheduler.parallelism", Integer.toString(parallelism));
		System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", Integer.toString(maxPoolSize));
		System.setProperty("jdk.virtualThreadScheduler.minRunnable", Integer.toString(minRunnable));
	}

	public static void validateDefaultSchedulerParallelism(ThreadFactory vtFactory) throws InterruptedException {
		var sharedCounter = new AtomicLong();
		var waitToStart = new CyclicBarrier(2);
		var errors = new LongAdder();
		var completed = new CountDownLatch(2);
		// verify serial execution
		for (int i = 0; i < 2; i++) {
			vtFactory.newThread(() -> {
				try {
					waitToStart.await(10, TimeUnit.SECONDS);
					if (detectContentionFor(sharedCounter, TimeUnit.SECONDS.toNanos(1))) {
						errors.increment();
					}
				} catch (Throwable t) {
					errors.increment();
				} finally {
					completed.countDown();
				}
			}).start();
		}
		completed.await();
		if (errors.sum() != 0) {
			throw new IllegalStateException(
					"The default Loom scheduler appear to have too much parallelism: check if the jdk.virtualThreadScheduler.* properties are still valid!");
		}
	}

	private static boolean detectContentionFor(AtomicLong sharedCounter, long durationNs) {
		long start = System.nanoTime();
		long value = sharedCounter.get();
		while ((System.nanoTime() - start) < durationNs) {
			if (!sharedCounter.compareAndSet(value, value + 1L)) {
				return true;
			}
			value++;
		}
		return false;
	}

}
