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
package io.netty.loom.benchmark;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.nio.NioIoHandler;
import io.netty.loom.VirtualMultithreadIoEventLoopGroup;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.jctools.util.Pow2;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static io.netty.loom.benchmark.DefaultSchedulerUtils.setupDefaultScheduler;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
		"-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false",
		"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler", "-Djdk.pollerMode=3"})
public class SchedulerHandoffBenchmark {

	// default is 1000 rps per user
	@Param({"1"})
	int serviceTimeMs;

	@Param({"512"})
	int requestBytes;

	@Param({"1024"})
	int responseBytes;

	// the total throughput will be roughly concurrency * 1000 / serviceTimeMs
	@Param({"100"})
	int concurrency;

	@Param({"false", "true"})
	boolean nettyScheduler;

	VirtualMultithreadIoEventLoopGroup group;

	Supplier<ThreadFactory> threadFactory;

	BlockingQueue<byte[]> requestQueue;

	@Setup
	public void setup() throws ExecutionException, InterruptedException {
		group = new VirtualMultithreadIoEventLoopGroup(Runtime.getRuntime().availableProcessors(),
				NioIoHandler.newFactory());
		if (nettyScheduler) {
			threadFactory = group::vThreadFactory;
		} else {
			var sameFactory = Thread.ofVirtual().factory();
			threadFactory = () -> sameFactory;
		}
		requestQueue = new MpscBlockingConsumerArrayQueue<>(Pow2.roundToPowerOfTwo(concurrency));
		for (int i = 0; i < concurrency; i++) {
			requestQueue.offer(new byte[requestBytes]);
		}
	}

	@Benchmark
	public void request(Blackhole bh) throws InterruptedException {
		byte[] request = requestQueue.take();
		// write some data into the request
		Arrays.fill(request, (byte) 1);
		// this is handing off this to the loom scheduler
		var el = group.next();
		el.execute(() -> {
			// process the request by reading it
			for (byte b : request) {
				bh.consume(b);
			}
			// off-load the actual processing to a virtual thread
			threadFactory.get().newThread(() -> {
				try {
					// simulate processing time
					TimeUnit.MILLISECONDS.sleep(serviceTimeMs);
					// allocate a response
					int responseBytes = this.responseBytes;
					ByteBuf responseData = ByteBufAllocator.DEFAULT.buffer(responseBytes);
					// write the response content
					for (int i = 0; i < responseBytes; i++) {
						responseData.writeByte(42);
					}
					el.execute(() -> {
						// read the response
						int toRead = this.responseBytes;
						for (int i = 0; i < toRead; i++) {
							bh.consume(responseData.getByte(i));
						}
						responseData.release();
						// offer it just at the end
						requestQueue.add(request);
					});
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}).start();
		});
	}

	@TearDown
	public void shutdown() throws ExecutionException, InterruptedException {
		group.shutdownGracefully().get();
	}
}
