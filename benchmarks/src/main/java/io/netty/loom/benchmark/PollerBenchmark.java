package io.netty.loom.benchmark;

import static io.netty.loom.benchmark.DefaultSchedulerUtils.setupDefaultScheduler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import io.netty.loom.LoomSupport;
import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = { "--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
                             "-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false" })
public class PollerBenchmark {

   /**
    * Beware tuning these values to force the sub-pollers to be used.<br>
    * Uses the scheduler stats related read/write tasks executed to determine how many times the sub-pollers were used.
    */
   @Param({ "131072" })
   public int bytes;
   @Param({ "131072" })
   public int receiveBufferSize;
   @Param({ "131072" })
   public int sendBufferSize;
   @Param({ "0" })
   public int port;
   private Queue<Runnable> blockingReadTasks;
   private Queue<Runnable> blockingWriteTasks;
   private ServerSocket serverAcceptor;
   private Socket serverSocket;
   private Socket clientSocket;
   private InputStream clientIn;
   private OutputStream serverOut;
   private long readBytes;
   private long writtenBytes;
   private byte[] inData;
   private byte[] outData;
   private ThreadFactory readThreadFactory;
   private ThreadFactory writeThreadFactory;
   private Runnable serverWrite;
   private Runnable clientRead;
   private volatile Thread carrierParked;

   @AuxCounters
   @State(Scope.Thread)
   public static class SchedulerStats {
      public long tasksExecuted;
      public long readTasks;
      public long writeTasks;
   }

   static {
      // this is necessary to make sure the Pooler used sits on a single threaded FJ pool
      setupDefaultScheduler(1);
   }


   @Setup
   public void init() throws IOException, InterruptedException, BrokenBarrierException {
      DefaultSchedulerUtils.validateDefaultSchedulerParallelism(Thread.ofVirtual().factory());
      serverAcceptor = new ServerSocket(0);
      var connectionsEstablished = new CyclicBarrier(3);
      Thread.ofVirtual().start(() -> {
         try {
            clientSocket = new Socket("localhost", serverAcceptor.getLocalPort());
            connectionsEstablished.await();
         } catch (Throwable e) {
            throw new RuntimeException(e);
         }
      });
      Thread.ofVirtual().start(() -> {
         try {
            serverSocket = serverAcceptor.accept();
            connectionsEstablished.await();
         } catch (Throwable e) {
            throw new RuntimeException(e);
         }
      });
      connectionsEstablished.await();
      inData = new byte[bytes];
      outData = new byte[bytes];
      Arrays.fill(inData, (byte) 42);
      // TODO we could size it to always save any GC to happen!
      blockingReadTasks = new MpscUnboundedArrayQueue<>(1024);
      blockingWriteTasks = new MpscUnboundedArrayQueue<>(1024);
      readThreadFactory = LoomSupport.setVirtualThreadFactoryScheduler(Thread.ofVirtual(), task -> {
         blockingReadTasks.add(task);
         LockSupport.unpark(carrierParked);
      }).factory();
      writeThreadFactory = LoomSupport.setVirtualThreadFactoryScheduler(Thread.ofVirtual(), task -> {
         blockingWriteTasks.add(task);
         LockSupport.unpark(carrierParked);
      }).factory();
      clientIn = clientSocket.getInputStream();
      serverOut = serverSocket.getOutputStream();
      clientSocket.setTcpNoDelay(true);
      clientSocket.setReceiveBufferSize(receiveBufferSize);
      serverSocket.setTcpNoDelay(true);
      serverSocket.setSendBufferSize(sendBufferSize);
      serverWrite = () -> {
         try {
             serverOut.write(outData);
            writtenBytes += bytes;
         } catch (Throwable e) {
            throw new RuntimeException(e);
         }
      };
      clientRead = () -> {
         try {
            readBytes += clientIn.readNBytes(inData, 0, bytes);
         } catch (IOException e) {
            throw new RuntimeException(e);
         }
      };
   }

   @Benchmark
   public byte[] readWrite(SchedulerStats stats) {
      // reset counters
      int bytes = this.bytes;
      readBytes = 0;
      writtenBytes = 0;
      // since we use a FIFO task q, the read should happen first, blocking
      readThreadFactory.newThread(clientRead).start();
      writeThreadFactory.newThread(serverWrite).start();
      for (; ; ) {
         // always read from both queues, as the read and write tasks can be interleaved
         Runnable readTask = blockingReadTasks.poll();
         Runnable writeTask = blockingWriteTasks.poll();
         if (readTask != null) {
            stats.tasksExecuted++;
            stats.readTasks++;
            readTask.run();
         }
         if (writeTask != null) {
            stats.tasksExecuted++;
            stats.writeTasks++;
            writeTask.run();
         }
         // if we have read all the bytes, we can stop
         if (readBytes == bytes && writtenBytes == bytes) {
            break;
         }
         if (canBlock()) {
            // if we can block, let's try to sleep
            trySleep();
         }
      }
      return inData;
   }

   private void trySleep() {
      carrierParked = Thread.currentThread();
      try {
         if (canBlock()) {
            LockSupport.park();
         }
      } finally {
         carrierParked = null;
      }
   }

   private boolean canBlock() {
      return blockingReadTasks.isEmpty() && blockingWriteTasks.isEmpty();
   }


   @TearDown
   public void cleanup() throws Exception {
      clientSocket.close();
      serverSocket.close();
      serverAcceptor.close();
   }
}
