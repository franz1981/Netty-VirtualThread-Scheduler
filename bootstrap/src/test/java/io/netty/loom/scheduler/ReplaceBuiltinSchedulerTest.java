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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "io.netty.loom.replaceBuiltinScheduler", matches = "true")
class ReplaceBuiltinSchedulerTest {

	private static final MethodHandle CURRENT_CARRIER;
	private static final MethodHandle VIRTUAL_THREAD_TASK;

	static {
		try {
			var lookup = MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup());
			CURRENT_CARRIER = lookup.findStatic(Thread.class, "currentCarrierThread",
					MethodType.methodType(Thread.class));
			VIRTUAL_THREAD_TASK = lookup.findVirtual(Class.forName("java.lang.VirtualThread"), "virtualThreadTask",
					MethodType.methodType(Thread.VirtualThreadTask.class));
		} catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static Thread currentCarrier() {
		try {
			return (Thread) CURRENT_CARRIER.invokeExact();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	private static Thread.VirtualThreadTask virtualThreadTask(Thread vt) {
		try {
			return (Thread.VirtualThreadTask) VIRTUAL_THREAD_TASK.invoke(vt);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	void plainVirtualThreadRunsOnCarrier() throws Exception {
		var carrier = new AtomicReference<Thread>();
		var latch = new CountDownLatch(1);

		Thread.ofVirtual().start(() -> {
			carrier.set(currentCarrier());
			latch.countDown();
		});

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		var group = EventLoopSchedulerGroup.instance();
		boolean found = false;
		for (int i = 0; i < group.size(); i++) {
			if (group.scheduler(i).carrierThread() == carrier.get()) {
				found = true;
				break;
			}
		}
		assertTrue(found, "plain VT must run on a carrier in the group");
	}

	@Test
	void plainVirtualThreadCompletes() throws Exception {
		int count = 100;
		var latch = new CountDownLatch(count);

		for (int i = 0; i < count; i++) {
			Thread.ofVirtual().start(latch::countDown);
		}

		assertTrue(latch.await(10, TimeUnit.SECONDS));
	}

	@Test
	void currentSchedulerReturnsNullForPlainVirtualThread() throws Exception {
		var scheduler = new AtomicReference<EventLoopScheduler>();
		var latch = new CountDownLatch(1);

		Thread.ofVirtual().start(() -> {
			scheduler.set(EventLoopScheduler.currentScheduler());
			latch.countDown();
		});

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertNull(scheduler.get(), "plain VT should not have a currentScheduler (no ScopedValue)");
	}

	@Test
	void factoryVirtualThreadStillHasCurrentScheduler() throws Exception {
		var scheduler = new AtomicReference<EventLoopScheduler>();
		var latch = new CountDownLatch(1);
		var factory = EventLoopSchedulerGroup.instance().virtualThreadFactory();

		factory.newThread(() -> {
			scheduler.set(EventLoopScheduler.currentScheduler());
			latch.countDown();
		}).start();

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertNotNull(scheduler.get(), "factory VT must have a currentScheduler");
	}

	@Test
	void childOfManagedVtInheritsCarrierWhenQueueEmpty() throws Exception {
		var parentScheduler = new AtomicReference<EventLoopScheduler>();
		var childScheduler = new AtomicReference<EventLoopScheduler>();
		var latch = new CountDownLatch(1);
		var factory = EventLoopSchedulerGroup.instance().virtualThreadFactory();

		factory.newThread(() -> {
			parentScheduler.set(EventLoopScheduler.currentScheduler());
			var childLatch = new CountDownLatch(1);
			Thread.ofVirtual().start(() -> {
				var task = virtualThreadTask(Thread.currentThread());
				if (task.attachment() instanceof EventLoopScheduler.SchedulingContext ctx) {
					childScheduler.set(ctx.assignedScheduler());
				}
				childLatch.countDown();
			});
			try {
				childLatch.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			latch.countDown();
		}).start();

		assertTrue(latch.await(10, TimeUnit.SECONDS));
		assertNotNull(parentScheduler.get());
		assertEquals(parentScheduler.get(), childScheduler.get(),
				"child VT should inherit parent's carrier when queue is empty");
	}

	@Test
	void concurrentPlainVirtualThreadsWithYield() throws Exception {
		int count = 1000;
		var latch = new CountDownLatch(count);

		for (int i = 0; i < count; i++) {
			Thread.ofVirtual().start(() -> {
				Thread.yield();
				latch.countDown();
			});
		}

		assertTrue(latch.await(30, TimeUnit.SECONDS), count + " plain VTs must all complete");
	}

	private void assertOnCarrier(Thread carrier) {
		var group = EventLoopSchedulerGroup.instance();
		for (int i = 0; i < group.size(); i++) {
			if (group.scheduler(i).carrierThread() == carrier) {
				return;
			}
		}
		fail("thread not on any carrier in the group: " + carrier.getName());
	}

	@Test
	void plainVtStaysOnCarrierAfterYield() throws Exception {
		var carrierBefore = new AtomicReference<Thread>();
		var carrierAfter = new AtomicReference<Thread>();
		var latch = new CountDownLatch(1);

		Thread.ofVirtual().start(() -> {
			carrierBefore.set(currentCarrier());
			Thread.yield();
			carrierAfter.set(currentCarrier());
			latch.countDown();
		});

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertOnCarrier(carrierBefore.get());
		assertOnCarrier(carrierAfter.get());
	}

	@Test
	void plainVtStaysOnCarrierAfterParkUnpark() throws Exception {
		var carrierBefore = new AtomicReference<Thread>();
		var carrierAfter = new AtomicReference<Thread>();
		var latch = new CountDownLatch(1);

		var vt = Thread.ofVirtual().start(() -> {
			carrierBefore.set(currentCarrier());
			LockSupport.park();
			carrierAfter.set(currentCarrier());
			latch.countDown();
		});

		Thread.sleep(50);
		LockSupport.unpark(vt);

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertOnCarrier(carrierBefore.get());
		assertOnCarrier(carrierAfter.get());
	}

	@Test
	void plainVtStaysOnCarrierAfterSleep() throws Exception {
		var carrierBefore = new AtomicReference<Thread>();
		var carrierAfter = new AtomicReference<Thread>();
		var latch = new CountDownLatch(1);

		Thread.ofVirtual().start(() -> {
			carrierBefore.set(currentCarrier());
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			carrierAfter.set(currentCarrier());
			latch.countDown();
		});

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertOnCarrier(carrierBefore.get());
		assertOnCarrier(carrierAfter.get());
	}

	@Test
	void plainVtStaysOnCarrierAfterMultipleYields() throws Exception {
		var carriers = new AtomicReference<Thread[]>();
		var latch = new CountDownLatch(1);

		Thread.ofVirtual().start(() -> {
			var seen = new Thread[5];
			for (int i = 0; i < seen.length; i++) {
				seen[i] = currentCarrier();
				Thread.yield();
			}
			carriers.set(seen);
			latch.countDown();
		});

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		for (Thread c : carriers.get()) {
			assertOnCarrier(c);
		}
	}
}
