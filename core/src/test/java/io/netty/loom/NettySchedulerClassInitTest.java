package io.netty.loom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class NettySchedulerClassInitTest {

	@Test
	public void testNettySchedulerClassInitialization() {
		assertTrue(NettyScheduler.isAvailable());
	}
}
