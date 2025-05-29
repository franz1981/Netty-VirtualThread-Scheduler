package io.netty.loom.benchmark;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.loom.LoomSupport;
import io.netty.loom.MultithreadVirtualEventExecutorGroup;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = { "--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
                             "-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false" })
@State(Scope.Thread)
public class SchedulerBenchmark {

   private static void setupDefaultScheduler(int parallelism) {
      int maxPoolSize = Integer.max(parallelism, 256);
      int minRunnable = Integer.max(parallelism / 2, 1);
      System.setProperty("jdk.virtualThreadScheduler.parallelism", Integer.toString(parallelism));
      System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", Integer.toString(maxPoolSize));
      System.setProperty("jdk.virtualThreadScheduler.minRunnable", Integer.toString(minRunnable));
   }

   static {
      setupDefaultScheduler(1);
   }

   private EventLoopGroup executorGroup;
   private AtomicInteger counter;
   @Param({ "100" })
   private int tasks;
   @Param({ "10" })
   private long durationUs;
   private long durationNs;
   @Param({ "Netty", "Default" })
   public LoomScheduler scheduler;
   @Param({ "NIO"})
   public HandlerType handlerType;
   private ThreadFactory vtFactory;
   @Param({ "false", "true" })
   private boolean spinWaitResponse;
   private Semaphore requestsSemaphore;

   public enum LoomScheduler {
      Netty,
      Default
   }

   public enum HandlerType {
      LOCAL,
      NIO
   }

   @Setup
   public void setup() throws ExecutionException, InterruptedException {
      IoHandlerFactory factory = switch (handlerType) {
         case LOCAL -> LocalIoHandler.newFactory();
         case NIO -> NioIoHandler.newFactory();
      };
      counter = new AtomicInteger();
      durationNs = TimeUnit.MICROSECONDS.toNanos(durationUs);
      switch (scheduler) {
         case Netty:
            var virtualGroup = new MultithreadVirtualEventExecutorGroup(1, factory);
            executorGroup = virtualGroup;
            LoomSupport.checkSupported();
            vtFactory = executorGroup.submit(virtualGroup::vThreadFactory).get();
            break;
         case Default:
            executorGroup = new MultiThreadIoEventLoopGroup(1, factory);
            vtFactory = Thread.ofVirtual().factory();
            validateDefaultSchedulerParallelism();
            break;
         default:
            throw new IllegalArgumentException("Unknown threading mode: " + scheduler);
      }
      if (!spinWaitResponse) {
         requestsSemaphore = new Semaphore(0);
      }
   }

   private void validateDefaultSchedulerParallelism() throws InterruptedException {
      var sharedCounter = new AtomicLong();
      var waitToStart = new CyclicBarrier(2);
      var errors = new LongAdder();
      var completed = new CountDownLatch(2);
      // verify serial execution
      for (int i = 0; i < 2; i++) {
         vtFactory.newThread(() -> {
            try {
               waitToStart.await(10, TimeUnit.SECONDS);
               if (detectContentionFor(sharedCounter, TimeUnit.SECONDS.toNanos(1))) {
                  errors.increment();
               }
            } catch (Throwable t) {
               errors.increment();
            } finally {
               completed.countDown();
            }
         }).start();
      }
      completed.await();
      if (errors.sum() != 0) {
         throw new IllegalStateException("The default Loom scheduler appear to have too much parallelism: check if the jdk.virtualThreadScheduler.* properties are still valid!");
      }
   }

   private static boolean detectContentionFor(AtomicLong sharedCounter, long durationNs) {
      long start = System.nanoTime();
      long value = sharedCounter.get();
      while ((System.nanoTime() - start) < durationNs) {
         if (!sharedCounter.compareAndSet(value, value + 1L)) {
            return true;
         }
         value++;
      }
      return false;
   }

   @AuxCounters(AuxCounters.Type.EVENTS)
   @State(Scope.Thread)
   public static class TaskDuration {

      private final Histogram taskDuration = new Histogram(3);

      @Setup(Level.Iteration)
      public void reset() {
         taskDuration.reset();
      }

      public double responseTimeAvg() {
         return taskDuration.getMean();
      }

      public long responseTimeP50() {
         return taskDuration.getValueAtPercentile(50);
      }

      public long responseTimeP90() {
         return taskDuration.getValueAtPercentile(90);
      }

      public long responseTimeP99() {
         return taskDuration.getValueAtPercentile(99);
      }

      public long responseTimeMin() {
         return taskDuration.getMinValue();
      }

      public long responseTimeMax() {
         return taskDuration.getMaxValue();
      }

      public long totalSamples() {
         return taskDuration.getTotalCount();
      }

   }

   @Benchmark
   public void batchRTT(TaskDuration duration) throws InterruptedException {
      final var count = this.counter;
      count.lazySet(0);
      for (int i = 0; i < tasks; i++) {
         long start = System.nanoTime();
         executorGroup.execute(() -> {
            vtFactory.newThread(() -> {
               // busy time is always accessed within a virtual thread
               performCpuWork(durationNs);
               executorGroup.execute(() -> {
                  long elapsedTimeNs = System.nanoTime() - start;
                  // response time is recorded from the event loop
                  duration.taskDuration.recordValue(elapsedTimeNs);
                  if (count.incrementAndGet() == tasks) {
                     // the last to increment the counter will release the semaphore
                     if (!spinWaitResponse) {
                        requestsSemaphore.release();
                     }
                  }
               });
            }).start();
         });
      }
      if (spinWaitResponse) {
         while (counter.get() < tasks) {
            Thread.onSpinWait();
         }
      } else {
         requestsSemaphore.acquire();
      }
   }

   private static long performCpuWork(long durationNs) {
      if (durationNs <= 0) {
         return 0;
      }
      long startCpuWork = System.nanoTime();
      long elapsedNs = 0;
      while ((elapsedNs = (System.nanoTime() - startCpuWork)) < durationNs) {
         Thread.onSpinWait();
      }
      return elapsedNs;
   }

   @TearDown
   public void tearDown() {
      executorGroup.shutdownGracefully();
   }
}