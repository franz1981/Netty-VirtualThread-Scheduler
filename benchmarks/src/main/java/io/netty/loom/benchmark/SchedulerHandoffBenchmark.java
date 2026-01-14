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
import io.netty.channel.IoEventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.loom.VirtualMultithreadIoEventLoopGroup;
import io.netty.util.concurrent.FastThreadLocal;
import org.HdrHistogram.Histogram;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.util.Pow2;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.Supplier;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Benchmark)
public class SchedulerHandoffBenchmark {

	public enum IO {
		EPOLL, NIO, IO_URING
	}

	// default is 1000 rps per user
	@Param({"100"})
	int serviceTimeUs;

	@Param({"512"})
	int requestBytes;

	@Param({"1024"})
	int responseBytes;

	// the total throughput will be roughly concurrency * 1000 / serviceTimeMs
	@Param({"100"})
	int concurrency;

	private static final int EL_THREADS = Integer.getInteger("elThreads", -1);

	@Param({"EPOLL"})
	IO io;

	MultiThreadIoEventLoopGroup group;

	Supplier<ThreadFactory> threadFactory;

	// let's make
	Queue<byte[]> requestQueue;

	private static final CopyOnWriteArrayList<Histogram> histograms = new CopyOnWriteArrayList<>();

	private static final FastThreadLocal<Histogram> rttHistogram = new FastThreadLocal<>() {
		@Override
		public Histogram initialValue() {
			var histo = new Histogram(3);
			histograms.add(histo);
			return histo;
		}
	};

	@Setup
	public void setup(BenchmarkParams params) throws ExecutionException, InterruptedException {
		if (EL_THREADS <= 0) {
			throw new IllegalStateException("Please set the elThreads system property to a positive integer");
		}
		var ioFactory = switch (io) {
			case NIO -> NioIoHandler.newFactory();
			case IO_URING -> IoUringIoHandler.newFactory();
			case EPOLL -> EpollIoHandler.newFactory();
		};
		if (params.getBenchmark().contains("custom")) {
			var group = new VirtualMultithreadIoEventLoopGroup(EL_THREADS, ioFactory);
			threadFactory = group::vThreadFactory;
			this.group = group;
		} else {
			group = new MultiThreadIoEventLoopGroup(EL_THREADS, ioFactory);
			var sameFactory = Thread.ofVirtual().factory();
			threadFactory = () -> sameFactory;
		}
		requestQueue = new MpscArrayQueue<>(Pow2.roundToPowerOfTwo(concurrency));
		for (int i = 0; i < concurrency; i++) {
			requestQueue.offer(new byte[requestBytes]);
		}
	}

	@Benchmark
	@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
			"-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false",
			"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler", "-Djdk.pollerMode=3",
			"-DelThreads=4"})
	public void customScheduler(Blackhole bh) throws InterruptedException {
		doRequest(bh);
	}

	@Benchmark
	@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
			"-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false", "-DelThreads=4",
			"-Djdk.virtualThreadScheduler.parallelism=4"})
	public void defaultSchedulerFourFjThreads(Blackhole bh) throws InterruptedException {
		doRequest(bh);
	}

	@Benchmark
	@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
			"-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false", "-DelThreads=2",
			"-Djdk.virtualThreadScheduler.parallelism=2"})
	public void defaultSchedulerTwoFjThreads(Blackhole bh) throws InterruptedException {
		doRequest(bh);
	}

	// just burn a full core on this!
	private void doRequest(Blackhole bh) {
		byte[] request = spinWaitRequest();
		long startRequest = System.nanoTime();
		// write some data into the request
		Arrays.fill(request, (byte) 1);
		// this is handing off this to the loom scheduler
		var el = group.next();
		el.execute(() -> {
			// This is running in a Netty event loop thread
			// process the request by reading it
			for (byte b : request) {
				bh.consume(b);
			}
			// off-load the actual (blocking) processing to a virtual thread
			threadFactory.get().newThread(() -> {
				blockingProcess(bh, el, startRequest, request);
			}).start();
		});
	}

	// this is required to make sure NONE of the fine grain ops like writeByte won't
	// be inlined
	@CompilerControl(CompilerControl.Mode.DONT_INLINE)
	private void blockingProcess(Blackhole bh, IoEventLoop el, long startRequest, byte[] request) {
		try {
			// simulate processing time:
			// NOTE: if we're using sleep here, the built-in scheduler will use the FJ
			// built-in one
			// but the custom scheduler, nope, see
			// https://github.com/openjdk/loom/blob/3d9e866f60bdebc55b59b9fd40a4898002c35e96/src/java.base/share/classes/java/lang/VirtualThread.java#L1629
			TimeUnit.MICROSECONDS.sleep(serviceTimeUs);
			// allocate a response
			int responseBytes = this.responseBytes;
			ByteBuf responseData = ByteBufAllocator.DEFAULT.buffer(responseBytes);
			// write the response content
			for (int i = 0; i < responseBytes; i++) {
				responseData.writeByte(42);
			}
			el.execute(() -> {
				nonBlockingCompleteProcessing(bh, startRequest, request, responseData);
			});
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	// this is required to make sure NONE of the fine grain ops like writeByte won't
	// be inlined
	@CompilerControl(CompilerControl.Mode.DONT_INLINE)
	private void nonBlockingCompleteProcessing(Blackhole bh, long startRequest, byte[] request, ByteBuf responseData) {
		// read the response
		int toRead = this.responseBytes;
		for (int i = 0; i < toRead; i++) {
			bh.consume(responseData.getByte(i));
		}
		responseData.release();
		// record RTT
		long rttNs = System.nanoTime() - startRequest;
		Histogram histogram = rttHistogram.get();
		histogram.recordValue(rttNs);
		// offer it just at the end
		requestQueue.add(request);
	}

	private byte[] spinWaitRequest() {
		byte[] request = requestQueue.poll();
		while (request == null) {
			Thread.onSpinWait();
			request = requestQueue.poll();
		}
		return request;
	}

	@TearDown
	public void shutdown() throws ExecutionException, InterruptedException {
		// wait for all tasks to complete
		for (int i = 0; i < concurrency; i++) {
			spinWaitRequest();
		}
		group.shutdownGracefully().get();
		// print percentiles of RTT
		Histogram combined = new Histogram(3);
		histograms.forEach(combined::add);
		histograms.clear();
		// Print percentile distribution
		System.out.printf("RTT (µs) - Avg: %.2f, P50: %.2f, P90: %.2f, P99: %.2f, Max: %.2f%n",
				combined.getMean() / 1000.0, combined.getValueAtPercentile(50) / 1000.0,
				combined.getValueAtPercentile(90) / 1000.0, combined.getValueAtPercentile(99) / 1000.0,
				combined.getMaxValue() / 1000.0);
	}
}
