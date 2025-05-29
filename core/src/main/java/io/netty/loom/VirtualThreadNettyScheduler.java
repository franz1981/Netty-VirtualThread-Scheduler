package io.netty.loom;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ManualIoEventLoop;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;

public class VirtualThreadNettyScheduler implements Executor {

   private static final long MAX_WAIT_TASKS_NS = TimeUnit.HOURS.toNanos(1);
   private static final long MAX_RUN_NS = TimeUnit.MICROSECONDS.toNanos(Integer.getInteger("io.netty.loom.run.us", 1));

   private final MpscUnboundedArrayQueue<Runnable> externalContinuations;
   private final ManualIoEventLoop ioEventLoop;
   private final Thread eventLoopThread;
   private final Thread carrierThread;
   private volatile Thread parkedCarrierThread;
   private volatile Runnable eventLoopContinuation;
   private final CountDownLatch eventLoopContinuationAvailable;
   private final ThreadFactory vThreadFactory;
   private final AtomicBoolean running;

   public VirtualThreadNettyScheduler(IoEventLoopGroup parent, ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory, int resumedContinuationsExpectedCount) {
      this.running = new AtomicBoolean(false);
      this.externalContinuations = new MpscUnboundedArrayQueue<>(resumedContinuationsExpectedCount);
      this.carrierThread = threadFactory.newThread(this::virtualThreadSchedulerLoop);
      var builder = LoomSupport.setVirtualThreadFactoryScheduler(Thread.ofVirtual(), this);
      this.vThreadFactory = builder.factory();
      this.eventLoopThread = builder.unstarted(
            // this is enabling the adaptive allocator to use unshared magazines
            () -> FastThreadLocalThread.runWithFastThreadLocal(this::nettyEventLoop));
      this.ioEventLoop = new ManualIoEventLoop(parent, eventLoopThread,
            ioExecutor -> new AwakeAwareIoHandler(running, ioHandlerFactory.newHandler(ioExecutor)));
      // we can start the carrier only after all the fields are initialized
      eventLoopContinuationAvailable = new CountDownLatch(1);
      carrierThread.start();
      // TODO we cannot make the virtual thread factory available until the event loop v thread is started:
      //      we can save this if we can query the virtual thread associated with a continuation
      try {
         eventLoopContinuationAvailable.await();
      } catch (InterruptedException e) {
         throw new RuntimeException(e);
      }
   }

   public ThreadFactory virtualThreadFactory() {
      return vThreadFactory;
   }

   public Thread eventLoopThread() {
      return eventLoopThread;
   }

   public ManualIoEventLoop ioEventLoop() {
      return ioEventLoop;
   }

   private void nettyEventLoop() {
      running.set(true);
      assert ioEventLoop.inEventLoop(Thread.currentThread()) && Thread.currentThread().isVirtual();
      boolean canBlock = false;
      while (!ioEventLoop.isShuttingDown()) {
         if (canBlock) {
            canBlock = blockingRunIO();
         } else {
            canBlock = ioEventLoop.runNow(MAX_RUN_NS) == 0;
         }
         Thread.yield();
         // try running leftover write tasks before checking for I/O tasks
         canBlock &= ioEventLoop.runNonBlockingTasks(MAX_RUN_NS) == 0;
         Thread.yield();
      }
      // we are shutting down, it shouldn't take long so let's spin a bit :P
      while (!ioEventLoop.isTerminated()) {
         ioEventLoop.runNow();
      }
   }

   private boolean blockingRunIO() {
      // try to go to sleep waiting for I/O tasks
      running.set(false);
      // StoreLoad barrier: see https://www.scylladb.com/2018/02/15/memory-barriers-seastar-linux/
      try {
         if (!canBlock()) {
            return false;
         }
         return ioEventLoop.run(MAX_WAIT_TASKS_NS, MAX_RUN_NS) == 0;
      } finally{
         running.set(true);
      }
   }

   private void virtualThreadSchedulerLoop() {
      // start the event loop thread
      var eventLoop = this.ioEventLoop;
      eventLoopThread.start();
      // we expect here the continuation to be set up already
      var eventLoopContinuation = this.eventLoopContinuation;
      assert eventLoopContinuation != null && eventLoopContinuationAvailable.getCount() == 0;
      // we keep on running until the event loop is shutting-down
      while (!eventLoop.isTerminated()) {
         int count = runExternalContinuations(MAX_RUN_NS);
         if (count < 0) {
            // run the event loop asap
            eventLoopContinuation.run();
         } else if (count == 0) {
            // nothing to run, including the event loop: we can park
            parkedCarrierThread = carrierThread;
            if (canBlock()) {
               LockSupport.park();
            }
            parkedCarrierThread = null;
         }
      }
      while (!externalContinuations.isEmpty()) {
         // we still have continuations to run, let's run them
         int count = runExternalContinuations(MAX_RUN_NS);
         if (count < 0) {
            // this shouldn't happen really, but better to be safe
            eventLoopContinuation.run();
         }
      }
   }

   private boolean canBlock() {
      return externalContinuations.isEmpty();
   }

   /**
    * Return the number of continuations run or < 0 if the event loop need to take precedence and run.
    */
   private int runExternalContinuations(long deadlineNs) {
      assert eventLoopContinuation != null;
      final long startDrainingNs = System.nanoTime();
      var ready = this.externalContinuations;
      final var eventLoopContinuation = this.eventLoopContinuation;
      int runContinuations = 0;
      for (; ; ) {
         var continuation = ready.poll();
         if (continuation == null) {
            break;
         }
         if (continuation == eventLoopContinuation) {
            return -1;
         }
         continuation.run();
         runContinuations++;
         long elapsedNs = System.nanoTime() - startDrainingNs;
         if (elapsedNs >= deadlineNs) {
            return runContinuations;
         }
      }
      return runContinuations;
   }

   @Override
   public void execute(Runnable command) {
      if (ioEventLoop.isTerminated()) {
         throw new RejectedExecutionException("event loop is shutting down");
      }
      // The default scheduler won't shut down, but Netty's event loop can!
      Runnable eventLoopContinuation = this.eventLoopContinuation;
      if (eventLoopContinuation == null) {
         setEventLoopContinuation(command);
      }
      externalContinuations.offer(command);
      if (!ioEventLoop.inEventLoop(Thread.currentThread())) {
         // TODO: if we have access to the scheduler brought by the continuation,
         //       we could skip the wakeup if matches with this.
         ioEventLoop.wakeup();
         LockSupport.unpark(parkedCarrierThread);
      }
   }

   private void setEventLoopContinuation(Runnable command) {
      // this is the first command, we need to set the continuation
      this.eventLoopContinuation = command;
      // we need to notify the event loop that we have a continuation
      eventLoopContinuationAvailable.countDown();
   }

}
