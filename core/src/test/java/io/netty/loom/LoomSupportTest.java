package io.netty.loom;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertSame;

public class LoomSupportTest {

	@Test
	public void testGetScheduler() {
		record SchedulerContext(Thread.VirtualThreadScheduler fromJDKMethod,
				Thread.VirtualThreadScheduler fromLoomSupport) {
		}
		SchedulerContext schedulerContext = CompletableFuture.supplyAsync(() -> {
			Thread.VirtualThreadScheduler fromJDKMethod = Thread.VirtualThreadScheduler.current();
			Thread.VirtualThreadScheduler fromLoomSupport = LoomSupport.getScheduler(Thread.currentThread());
			return new SchedulerContext(fromJDKMethod, fromLoomSupport);
		}, Executors.newVirtualThreadPerTaskExecutor()).join();
		assertSame(schedulerContext.fromJDKMethod, schedulerContext.fromLoomSupport);
	}
}
