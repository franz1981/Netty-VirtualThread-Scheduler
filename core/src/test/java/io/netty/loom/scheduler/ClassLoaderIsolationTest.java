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
package io.netty.loom.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the bootstrap module split: scheduling classes
 * ({@code io.netty.loom.scheduler.*}) live alongside the scheduler entry point
 * ({@code io.netty.loom.scheduler.NettyScheduler}) in the bootstrap module.
 * This ensures the classloader singleton problem is solved by design — all
 * scheduling state (singletons, {@code ScopedValue}, {@code instanceof} checks)
 * share the same classloader regardless of how many app classloaders exist.
 *
 * <p>
 * The Netty integration layer ({@code io.netty.loom.*}) remains in core. Even
 * if core is loaded by a restricted classloader that cannot see
 * {@code io.netty.loom.*} classes, the scheduler infrastructure is unaffected.
 */
public class ClassLoaderIsolationTest {

	/**
	 * A class loader that blocks Netty integration classes in
	 * {@code io.netty.loom.*} but allows bootstrap classes in
	 * {@code io.netty.loom.scheduler.*}. This models the real deployment: the
	 * bootstrap JAR is on the system classpath while Netty integration classes are
	 * inside the app JAR.
	 */
	private static final class BlockingParentClassLoader extends ClassLoader {

		BlockingParentClassLoader(ClassLoader parent) {
			super(parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("io.netty.loom.") && !name.startsWith("io.netty.loom.scheduler.")) {
				throw new ClassNotFoundException("Simulated fat-JAR isolation: " + name);
			}
			return super.loadClass(name, resolve);
		}
	}

	/**
	 * Netty integration classes (core module) are blocked by the restricted
	 * classloader — exactly what happens in app servers with per-deployment
	 * classloaders.
	 */
	@Test
	public void nettyIntegrationClassesBlockedByRestrictedClassLoader() {
		ClassLoader blocked = new BlockingParentClassLoader(getClass().getClassLoader());
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("io.netty.loom.VirtualIoNativePollerEventLoopGroup", true, blocked));
	}

	/**
	 * Scheduler classes (bootstrap module) are visible even through the restricted
	 * classloader — they are on the system classpath.
	 */
	@Test
	public void schedulerClassesVisibleThroughRestrictedClassLoader() throws Exception {
		ClassLoader blocked = new BlockingParentClassLoader(getClass().getClassLoader());
		Class<?> schedulerClass = Class.forName("io.netty.loom.scheduler.EventLoopScheduler", true, blocked);
		assertNotNull(schedulerClass);
		Class<?> entryPointClass = Class.forName("io.netty.loom.scheduler.NettyScheduler", true, blocked);
		assertNotNull(entryPointClass);
	}

	/**
	 * {@link EventLoopScheduler} is loaded by the same classloader as
	 * {@link NettyScheduler}, proving {@code instanceof} checks cannot fail due to
	 * classloader mismatch.
	 */
	@Test
	public void schedulerSharesClassLoaderWithEntryPoint() {
		assertSame(NettyScheduler.class.getClassLoader(), EventLoopScheduler.class.getClassLoader());
	}
}
