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

import java.util.ServiceLoader;

/**
 * Thin bootstrap scheduler loaded by the JDK via
 * {@code -Djdk.virtualThreadScheduler.implClass=io.netty.loom.spi.NettyScheduler}.
 *
 * <p>
 * This class must live on the <em>system</em> class path (or boot class path)
 * because {@code VirtualThread.loadCustomScheduler()} uses
 * {@code ClassLoader.getSystemClassLoader()}. It has <b>no</b> Netty
 * dependencies — all real scheduling logic is discovered at runtime via
 * {@link ServiceLoader} from the thread-context class loader, exactly like JDBC
 * discovers drivers.
 */
public class NettyScheduler implements Thread.VirtualThreadScheduler {

	static volatile NettyScheduler INSTANCE;

	private final Thread.VirtualThreadScheduler jdkBuiltinScheduler;
	private final NettySchedulerSpi provider;

	public NettyScheduler(Thread.VirtualThreadScheduler jdkBuiltinScheduler) {
		INSTANCE = this;
		this.jdkBuiltinScheduler = jdkBuiltinScheduler;

		ClassLoader tccl = Thread.currentThread().getContextClassLoader();
		if (tccl == null) {
			tccl = ClassLoader.getSystemClassLoader();
		}
		ServiceLoader<NettySchedulerSpi> loader = ServiceLoader.load(NettySchedulerSpi.class, tccl);
		NettySchedulerSpi found = loader.findFirst().orElse(null);
		if (found != null) {
			found.init(jdkBuiltinScheduler);
		}
		this.provider = found;
	}

	public static NettyScheduler instance() {
		return ensureInstalled();
	}

	@Override
	public void onStart(Thread.VirtualThreadTask task) {
		if (provider != null) {
			provider.onStart(task);
		} else {
			jdkBuiltinScheduler.onStart(task);
		}
	}

	@Override
	public void onContinue(Thread.VirtualThreadTask task) {
		if (provider != null) {
			provider.onContinue(task);
		} else {
			jdkBuiltinScheduler.onContinue(task);
		}
	}

	private static NettyScheduler ensureInstalled() {
		var instance = INSTANCE;
		if (instance != null) {
			return instance;
		}
		Thread.ofVirtual().unstarted(() -> {
		});
		return INSTANCE;
	}

	public static boolean isAvailable() {
		var instance = ensureInstalled();
		return instance != null && instance.provider != null;
	}

	public static boolean isInstalled() {
		return ensureInstalled() != null;
	}
}
