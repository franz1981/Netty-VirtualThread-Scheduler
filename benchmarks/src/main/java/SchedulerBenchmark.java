package io.netty.benchmark;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import io.netty.channel.nio.NioIoHandler;
import io.netty.loom.MultithreadVirtualEventExecutorGroup;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class SchedulerBenchmark {

   private MultithreadVirtualEventExecutorGroup executorGroup;

   @Setup
   public void setup() {
      executorGroup = new MultithreadVirtualEventExecutorGroup(
            Runtime.getRuntime().availableProcessors(), NioIoHandler.newFactory());
   }

   @Benchmark
   public void benchmarkTaskSubmission() throws Exception {
      // TODO
   }

   @TearDown
   public void tearDown() {
      executorGroup.shutdownGracefully();
   }
}