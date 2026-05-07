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
package io.netty.loom;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ServiceLoader;

import io.netty.loom.spi.NettySchedulerSpi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the Spring Boot fat-JAR class-loader isolation problem and proves
 * that {@link ServiceLoader}-based discovery (the SPI fix) works around it.
 *
 * <p>
 * Spring Boot packages application classes under {@code BOOT-INF/classes/} and
 * loads them via {@code LaunchedClassLoader}. The JDK's
 * {@code VirtualThread.loadCustomScheduler()} uses
 * {@code ClassLoader.getSystemClassLoader()} which cannot see those classes.
 * <p>
 * This test simulates the same isolation by creating a child-first
 * {@link URLClassLoader} whose parent <em>blocks</em> {@code io.netty.loom.*}
 * classes, mimicking the system class loader's blindness in a fat-JAR
 * deployment.
 */
public class ClassLoaderIsolationTest {

	/**
	 * A class loader that delegates everything to its parent <em>except</em> core
	 * implementation classes in {@code io.netty.loom.*}. Bootstrap classes in
	 * {@code io.netty.loom.spi.*} are still visible — they live on the system class
	 * path. This models the real Spring Boot deployment: the bootstrap JAR is on
	 * {@code -Xbootclasspath/a:} while implementation classes are inside the fat
	 * JAR.
	 */
	private static final class BlockingParentClassLoader extends ClassLoader {

		BlockingParentClassLoader(ClassLoader parent) {
			super(parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("io.netty.loom.") && !name.startsWith("io.netty.loom.spi.")) {
				throw new ClassNotFoundException("Simulated fat-JAR isolation: " + name);
			}
			return super.loadClass(name, resolve);
		}
	}

	/**
	 * Reproduces the bug: {@code Class.forName} with a restricted parent class
	 * loader cannot find the SPI implementation — exactly what happens in Spring
	 * Boot when the system class loader is used.
	 */
	@Test
	public void classForNameFailsWithRestrictedClassLoader() {
		ClassLoader blocked = new BlockingParentClassLoader(getClass().getClassLoader());
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("io.netty.loom.NettySchedulerProviderImpl", true, blocked));
	}

	/**
	 * Proves the fix: {@code ServiceLoader} with a child-first class loader
	 * (analogous to Spring Boot's {@code LaunchedClassLoader}) discovers the SPI
	 * implementation even though the parent class loader blocks it.
	 */
	@Test
	public void serviceLoaderFindsProviderThroughChildFirstClassLoader() throws Exception {
		ClassLoader blocked = new BlockingParentClassLoader(getClass().getClassLoader());

		URL[] urls = getClasspathUrls();
		try (URLClassLoader childFirst = new ChildFirstClassLoader(urls, blocked)) {
			ServiceLoader<NettySchedulerSpi> loader = ServiceLoader.load(NettySchedulerSpi.class, childFirst);
			var provider = loader.findFirst();
			assertTrue(provider.isPresent(), "ServiceLoader should discover NettySchedulerProviderImpl via child CL");
		}
	}

	private URL[] getClasspathUrls() {
		String classpath = System.getProperty("java.class.path");
		String[] entries = classpath.split(System.getProperty("path.separator"));
		URL[] urls = new URL[entries.length];
		for (int i = 0; i < entries.length; i++) {
			try {
				urls[i] = new java.io.File(entries[i]).toURI().toURL();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		return urls;
	}

	/**
	 * A child-first (parent-last) class loader that mimics Spring Boot's
	 * {@code LaunchedClassLoader}: it tries to load classes from its own URLs
	 * before delegating to the parent.
	 */
	private static final class ChildFirstClassLoader extends URLClassLoader {

		ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);
				if (c != null) {
					return c;
				}
				if (name.startsWith("io.netty.loom.") && !name.startsWith("io.netty.loom.spi.")) {
					try {
						c = findClass(name);
						if (resolve) {
							resolveClass(c);
						}
						return c;
					} catch (ClassNotFoundException _) {
					}
				}
				return super.loadClass(name, resolve);
			}
		}
	}
}
