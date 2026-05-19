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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.CountDownLatch;

import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression tests for {@link VirtualIoEventLoopGroup}.
 *
 * <h2>Why these tests exist</h2>
 *
 * {@link VirtualIoEventLoopGroup} drives each NIO event loop as a
 * {@link ManualIoEventLoopTask} — a virtual thread that spins in a tight
 * non-blocking loop ({@code select(0)} + {@code runNonBlockingTasks}). Because
 * the loop never blocks, the VT never unmounts from the carrier on its own.
 *
 * <p>
 * Without an explicit {@code Thread.yield()} between loop iterations, any
 * virtual thread submitted to the same carrier's run-queue would starve: the
 * {@link ManualIoEventLoopTask} would hold the carrier forever while the
 * waiting continuation sits in the queue.
 *
 * <p>
 * {@link ManualIoEventLoopTask#run()} calls
 * {@link EventLoopScheduler#maybeYield()} which yields the carrier when the
 * run-queue is non-empty. The tests below verify this cooperative hand-off.
 */
@Timeout(10)
public class VirtualIoEventLoopGroupTest {

	/**
	 * Reproduces the starvation hang: a VT started from inside the Netty event
	 * loop must be able to make progress even though the
	 * {@link ManualIoEventLoopTask} itself never parks.
	 *
	 * <p>
	 * Scenario:
	 * <ol>
	 * <li>A {@link VirtualIoEventLoopGroup} with a single NIO thread is created.
	 * Its event loop runs as a {@link ManualIoEventLoopTask} VT on one
	 * carrier.</li>
	 * <li>A Netty task submitted via {@code group.execute()} runs inside
	 * {@code runNonBlockingTasks()} and starts a new VT on the <em>same</em>
	 * carrier via {@link EventLoopScheduler#virtualThreadFactory()}.</li>
	 * <li>The VT's continuation lands in the carrier's run-queue. For it to run,
	 * the {@link ManualIoEventLoopTask} must yield the carrier.</li>
	 * <li>If {@link ManualIoEventLoopTask} never calls {@code Thread.yield()} the
	 * VT starves and the latch never reaches zero — the test times out.</li>
	 * </ol>
	 */
	@Test
	void vtStartedFromInsideEventLoopMakesProgress() throws InterruptedException {
		try (var group = new VirtualIoEventLoopGroup(1, NioIoHandler.newFactory())) {
			var vtCompleted = new CountDownLatch(1);
			group.execute(() -> {
				var scheduler = EventLoopScheduler.currentScheduler();
				assertNotNull(scheduler, "ManualIoEventLoopTask must run with a scheduler context");
				scheduler.virtualThreadFactory().newThread(vtCompleted::countDown).start();
			});
			// Without maybeYield() in ManualIoEventLoopTask the VT above can never
			// be mounted and this await hangs until @Timeout fires.
			vtCompleted.await();
		}
	}
}
