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
package io.netty.loom.topology;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;

import io.netty.loom.scheduler.CarrierTopology;

/**
 * Linux-specific {@link CarrierTopology}. Reads CPU topology from sysfs, pins
 * carriers via {@code sched_setaffinity} (FFM), and groups carriers by shared
 * last-level cache (LLC).
 *
 * <p>
 * Activate via
 * {@code -Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology}
 * or ServiceLoader.
 *
 * <p>
 * Cluster-local stealing can be enabled with
 * {@code -Dio.netty.loom.topology.clusterLocal=true}.
 */
public class LinuxCarrierTopology implements CarrierTopology {

	private static final int CPU_SET_SIZE = 128;
	private static final MethodHandle SCHED_SET_AFFINITY;
	private static final MethodHandle SCHED_GET_AFFINITY;

	static {
		var linker = Linker.nativeLinker();
		var lookup = linker.defaultLookup();
		SCHED_SET_AFFINITY = lookup
				.find("sched_setaffinity").map(sym -> linker.downcallHandle(sym, FunctionDescriptor
						.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)))
				.orElse(null);
		SCHED_GET_AFFINITY = lookup
				.find("sched_getaffinity").map(sym -> linker.downcallHandle(sym, FunctionDescriptor
						.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)))
				.orElse(null);
	}

	private final int[] availableCpus;
	private final int[] carrierToCluster;
	private final int[] carrierToCore;
	private final StealScope scope;

	public LinuxCarrierTopology() {
		this.scope = StealScope
				.valueOf(System.getProperty("io.netty.loom.workstealing.scope", StealScope.GLOBAL.name()));
		this.availableCpus = discoverAvailableCpus();
		var llcGroups = new HashMap<String, Integer>();
		this.carrierToCluster = new int[availableCpus.length];
		this.carrierToCore = new int[availableCpus.length];
		for (int i = 0; i < availableCpus.length; i++) {
			int cpu = availableCpus[i];
			String llcGroup = readLlcGroup(cpu);
			carrierToCluster[i] = llcGroups.computeIfAbsent(llcGroup, k -> llcGroups.size());
			carrierToCore[i] = readInt("/sys/devices/system/cpu/cpu" + cpu + "/topology/core_id", cpu);
		}
	}

	@Override
	public void bindCarrier(int carrierIndex, int carrierCount, Thread carrier) {
		if (carrierIndex < availableCpus.length) {
			setAffinity(availableCpus[carrierIndex]);
		} else {
			System.err.println("WARNING: carrier-" + carrierIndex + " has no CPU to pin to (" + availableCpus.length
					+ " CPUs available, " + carrierCount + " carriers requested). "
					+ "Carrier will float across all available CPUs.");
		}
	}

	@Override
	public int cluster(int carrierIndex) {
		if (carrierIndex >= availableCpus.length) {
			return -carrierIndex;
		}
		return carrierToCluster[carrierIndex];
	}

	@Override
	public int core(int carrierIndex) {
		if (carrierIndex >= availableCpus.length) {
			return -carrierIndex;
		}
		return carrierToCore[carrierIndex];
	}

	@Override
	public StealScope stealScope() {
		return scope;
	}

	/**
	 * Pins the calling thread to the given CPU via
	 * {@code sched_setaffinity(0, ...)}. No-op if FFM binding is unavailable.
	 */
	public static void setAffinity(int cpu) {
		var bits = new BitSet();
		bits.set(cpu);
		setAffinityBitSet(bits);
	}

	/**
	 * Returns the affinity mask of the calling thread, or {@code null} if the FFM
	 * binding is unavailable.
	 */
	public static long[] getAffinity() {
		if (SCHED_GET_AFFINITY == null) {
			return null;
		}
		try (var arena = Arena.ofConfined()) {
			var cpuSet = arena.allocate(CPU_SET_SIZE);
			int rc = (int) SCHED_GET_AFFINITY.invoke(0, (long) CPU_SET_SIZE, cpuSet);
			if (rc < 0) {
				return null;
			}
			long[] mask = new long[CPU_SET_SIZE / Long.BYTES];
			MemorySegment.copy(cpuSet, ValueLayout.JAVA_LONG, 0, mask, 0, mask.length);
			return mask;
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * Sets the affinity mask of the calling thread. Symmetric with
	 * {@link #getAffinity()}.
	 */
	public static void setAffinityMask(long[] mask) {
		setAffinityBitSet(BitSet.valueOf(mask));
	}

	/**
	 * Sets the affinity of the calling thread to the given {@link BitSet}. Uses the
	 * same bit-packed long array layout as glibc's {@code cpu_set_t}.
	 */
	public static void setAffinityBitSet(BitSet cpus) {
		if (SCHED_SET_AFFINITY == null) {
			return;
		}
		try (var arena = Arena.ofConfined()) {
			var cpuSet = arena.allocate(CPU_SET_SIZE);
			cpuSet.fill((byte) 0);
			long[] longs = cpus.toLongArray();
			MemorySegment.copy(longs, 0, cpuSet, ValueLayout.JAVA_LONG, 0,
					Math.min(longs.length, CPU_SET_SIZE / Long.BYTES));
			int rc = (int) SCHED_SET_AFFINITY.invoke(0, (long) CPU_SET_SIZE, cpuSet);
			if (rc < 0) {
				throw new RuntimeException("sched_setaffinity failed: rc=" + rc);
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns the CPU index where the calling thread is currently pinned, or -1.
	 */
	public static int pinnedCpu() {
		var mask = getAffinity();
		if (mask == null) {
			return -1;
		}
		int cpu = -1;
		int count = 0;
		for (int w = 0; w < mask.length; w++) {
			long word = mask[w];
			while (word != 0) {
				int bit = Long.numberOfTrailingZeros(word);
				cpu = w * Long.SIZE + bit;
				count++;
				word &= word - 1;
			}
		}
		return (count == 1) ? cpu : -1;
	}

	private static int[] discoverAvailableCpus() {
		long[] mask = getAffinity();
		if (mask == null) {
			int n = Runtime.getRuntime().availableProcessors();
			int[] fallback = new int[n];
			for (int i = 0; i < n; i++) {
				fallback[i] = i;
			}
			return fallback;
		}
		return BitSet.valueOf(mask).stream().toArray();
	}

	private static String readLlcGroup(int cpu) {
		String base = "/sys/devices/system/cpu/cpu" + cpu + "/cache/";
		String group = String.valueOf(cpu);
		for (int i = 0; i < 10; i++) {
			String dir = base + "index" + i;
			if (!Files.exists(Path.of(dir))) {
				break;
			}
			int level = readInt(dir + "/level", -1);
			if (level >= 3) {
				group = readString(dir + "/shared_cpu_list", group);
			}
		}
		return group;
	}

	private static int readInt(String path, int fallback) {
		try {
			return Integer.parseInt(Files.readString(Path.of(path)).trim());
		} catch (Exception e) {
			return fallback;
		}
	}

	private static String readString(String path, String fallback) {
		try {
			return Files.readString(Path.of(path)).trim();
		} catch (Exception e) {
			return fallback;
		}
	}
}
