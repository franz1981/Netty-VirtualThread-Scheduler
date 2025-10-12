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

public class VirtualThreadNettyScheduler implements Thread.VirtualThreadScheduler {

   private static final long MAX_WAIT_TASKS_NS = TimeUnit.HOURS.toNanos(1);
   // These are the soft-guaranteed yield times for the event loop whilst Thread.yield() is called.
   // Based on the status of the event loop (resuming from blocking or non-blocking, controlled by the running flag)
   // a different limit is applied.
   private static final long RUNNING_YIELD_US = TimeUnit.MICROSECONDS.toNanos(Integer.getInteger("io.netty.loom.running.yield.us", 1));
   private static final long IDLE_YIELD_US = TimeUnit.MICROSECONDS.toNanos(Integer.getInteger("io.netty.loom.idle.yield.us", 1));
   private static final ScopedValue<Thread.VirtualThreadScheduler> CURRENT_SCHEDULER = ScopedValue.newInstance();
   private static final Thread.VirtualThreadScheduler EMPTY = new Thread.VirtualThreadScheduler() {
      @Override
      public void execute(Thread vthread, Runnable command) {
         throw new RejectedExecutionException("VirtualThreadScheduler is empty");
      }
   };

   private final MpscUnboundedArrayQueue<Runnable> externalContinuations;
   private final ManualIoEventLoop ioEventLoop;
   private final Thread eventLoopThread;
   private final Thread carrierThread;
   private volatile Thread parkedCarrierThread;
   private volatile Runnable eventLoopContinuation;
   private volatile boolean submittedEventLoopContinuation;
   private final CountDownLatch eventLoopContinuationAvailable;
   private final ThreadFactory vThreadFactory;
   private final AtomicBoolean running;

   public VirtualThreadNettyScheduler(IoEventLoopGroup parent, ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory, int resumedContinuationsExpectedCount) {
      this.running = new AtomicBoolean(false);
      this.externalContinuations = new MpscUnboundedArrayQueue<>(resumedContinuationsExpectedCount);
      this.carrierThread = threadFactory.newThread(this::virtualThreadSchedulerLoop);
      var builder = Thread.ofVirtual().scheduler(this);
      ThreadFactory rawVTFactory = builder.factory();
      this.vThreadFactory = (r) -> rawVTFactory.newThread(() -> ScopedValue.where(CURRENT_SCHEDULER, this).run(r));
      this.eventLoopThread = vThreadFactory.newThread(
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
         canBlock = runIO(canBlock);
         Thread.yield();
         // try running leftover write tasks before checking for I/O tasks
         canBlock &= ioEventLoop.runNonBlockingTasks(RUNNING_YIELD_US) == 0;
         Thread.yield();
      }
      // we are shutting down, it shouldn't take long so let's spin a bit :P
      while (!ioEventLoop.isTerminated()) {
         ioEventLoop.runNow();
         Thread.yield();
      }
   }

   private boolean runIO(boolean canBlock) {
      if (canBlock) {
         // try to go to sleep waiting for I/O tasks
         running.set(false);
         // StoreLoad barrier: see https://www.scylladb.com/2018/02/15/memory-barriers-seastar-linux/
         if (canBlock()) {
            try {
               return ioEventLoop.run(MAX_WAIT_TASKS_NS, RUNNING_YIELD_US) == 0;
            } finally {
               running.set(true);
            }
         } else {
            running.set(false);
         }
      }
      return ioEventLoop.runNow(RUNNING_YIELD_US) == 0;
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
         // if the event loop was idle, we apply a different limit to the yield time
         final boolean eventLoopRunning = running.get();
         final long yieldDurationNs = eventLoopRunning ? RUNNING_YIELD_US : IDLE_YIELD_US;
         int count = runExternalContinuations(yieldDurationNs);
         if (submittedEventLoopContinuation) {
            submittedEventLoopContinuation = false;
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
      while (!canBlock()) {
         // we still have continuations to run, let's run them
         runExternalContinuations(RUNNING_YIELD_US);
         if (submittedEventLoopContinuation) {
            submittedEventLoopContinuation = false;
            eventLoopContinuation.run();
         }
      }
   }

   private boolean canBlock() {
      return externalContinuations.isEmpty() && !submittedEventLoopContinuation;
   }

   private int runExternalContinuations(long deadlineNs) {
      assert eventLoopContinuation != null;
      final long startDrainingNs = System.nanoTime();
      var ready = this.externalContinuations;
      int runContinuations = 0;
      for (; ; ) {
         var continuation = ready.poll();
         if (continuation == null) {
            break;
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
   public void execute(Thread vthread, Runnable command) {
      if (ioEventLoop.isTerminated()) {
         throw new RejectedExecutionException("event loop is shutting down");
      }
      // The default scheduler won't shut down, but Netty's event loop can!
      Runnable eventLoopContinuation = this.eventLoopContinuation;
      if (eventLoopContinuation == null) {
         eventLoopContinuation = setEventLoopContinuation(command);
      }
      if (eventLoopContinuation == command) {
         submittedEventLoopContinuation = true;
      } else {
         externalContinuations.offer(command);
      }
      if (!inEventLoop(Thread.currentThread())) {
         // TODO: if we have access to the scheduler brought by the continuation,
         //       we could skip the wakeup if matches with this.
         ioEventLoop.wakeup();
         LockSupport.unpark(parkedCarrierThread);
      } else if (eventLoopContinuation != command && !running.get()) {
         // since the event loop was blocked, it's fine if we try to consume some continuations, if any
         Thread.yield();
      }
   }

   private boolean inEventLoop(Thread thread) {
       if (!thread.isVirtual()) {
           return ioEventLoop.inEventLoop(thread);
       }

       return CURRENT_SCHEDULER.orElse(EMPTY) == this;
   }

   private Runnable setEventLoopContinuation(Runnable command) {
      // this is the first command, we need to set the continuation
      this.eventLoopContinuation = command;
      // we need to notify the event loop that we have a continuation
      eventLoopContinuationAvailable.countDown();
      return command;
   }

   public static VirtualThreadNettyScheduler current() {
       Thread.VirtualThreadScheduler virtualThreadScheduler = CURRENT_SCHEDULER.orElse(EMPTY);
       return virtualThreadScheduler == EMPTY ? null : (VirtualThreadNettyScheduler) virtualThreadScheduler;
   }

}
