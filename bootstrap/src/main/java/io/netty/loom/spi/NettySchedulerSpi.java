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
package io.netty.loom.spi;

/**
 * SPI for plugging a virtual-thread scheduling strategy into
 * {@link NettyScheduler}.
 *
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader} using the
 * thread-context class loader, following the same pattern as JDBC drivers. This
 * allows the implementation to live inside a Spring Boot fat JAR (or any
 * non-standard class-loader hierarchy) while {@link NettyScheduler} itself
 * remains on the system class path.
 */
public interface NettySchedulerSpi {

	/**
	 * Called once, immediately after discovery, to hand the provider the JDK's
	 * built-in scheduler so it can fall back to it for unrecognised tasks.
	 */
	void init(Thread.VirtualThreadScheduler jdkBuiltinScheduler);

	/**
	 * @see Thread.VirtualThreadScheduler#onStart(Thread.VirtualThreadTask)
	 */
	void onStart(Thread.VirtualThreadTask task);

	/**
	 * @see Thread.VirtualThreadScheduler#onContinue(Thread.VirtualThreadTask)
	 */
	void onContinue(Thread.VirtualThreadTask task);

	/**
	 * Whether this provider expects the JVM to run with per-carrier pollers
	 * ({@code jdk.pollerMode=3}).
	 */
	default boolean expectsPerCarrierPollers() {
		return false;
	}
}
