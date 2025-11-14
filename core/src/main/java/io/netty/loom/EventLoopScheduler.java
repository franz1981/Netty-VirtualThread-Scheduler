package io.netty.loom;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ManualIoEventLoop;
import io.netty.util.concurrent.FastThreadLocalThread;

public class EventLoopScheduler {

   private static final long MAX_WAIT_TASKS_NS = TimeUnit.HOURS.toNanos(1);
   // These are the soft-guaranteed yield times for the event loop whilst Thread.yield() is called.
   // Based on the status of the event loop (resuming from blocking or non-blocking, controlled by the running flag)
   // a different limit is applied.
   private static final long RUNNING_YIELD_US = TimeUnit.MICROSECONDS.toNanos(Integer.getInteger("io.netty.loom.running.yield.us", 1));
   private static final long IDLE_YIELD_US = TimeUnit.MICROSECONDS.toNanos(Integer.getInteger("io.netty.loom.idle.yield.us", 1));
   // This is required to allow sub-pollers to run on the correct scheduler
   private static final ScopedValue<AtomicReference<EventLoopScheduler>> CURRENT_SCHEDULER = ScopedValue.newInstance();
   private static final AtomicReference<EventLoopScheduler> EMPTY_REFERENCE = new AtomicReference<>();
   private final MpscUnboundedStream<Runnable> runQueue;
   private final ManualIoEventLoop ioEventLoop;
   private final Thread eventLoopThread;
   private final Thread carrierThread;
   private volatile Thread parkedCarrierThread;
   private volatile Runnable eventLoopContinuatioToRun;
   private final ThreadFactory vThreadFactory;
   private final AtomicBoolean running;
   private final AtomicReference<EventLoopScheduler> schedulerReference;

   public EventLoopScheduler(IoEventLoopGroup parent, ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory, int resumedContinuationsExpectedCount) {
      schedulerReference = new AtomicReference<>(this);
      running = new AtomicBoolean(false);
      runQueue = new MpscUnboundedStream<>(resumedContinuationsExpectedCount);
      carrierThread = threadFactory.newThread(this::virtualThreadSchedulerLoop);
      var rawVTFactory = Thread.ofVirtual().factory();
      vThreadFactory = runnable ->
              NettyScheduler.assignUnstarted(rawVTFactory.newThread(
                      () -> ScopedValue.where(CURRENT_SCHEDULER, schedulerReference).run(runnable)), schedulerReference);
      eventLoopThread = vThreadFactory.newThread(() -> FastThreadLocalThread.runWithFastThreadLocal(this::nettyEventLoop));
      ioEventLoop = new ManualIoEventLoop(parent, eventLoopThread,
            ioExecutor -> new AwakeAwareIoHandler(running, ioHandlerFactory.newHandler(ioExecutor)));
      carrierThread.start();
   }

   int externalContinuationsCount() {
      return runQueue.size();
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
            running.set(true);
         }
      }
      return ioEventLoop.runNow(RUNNING_YIELD_US) == 0;
   }

   private boolean runEventLoopContinuation() {
      assert Thread.currentThread() == carrierThread;
      var eventLoopContinuation = this.eventLoopContinuatioToRun;
      if (eventLoopContinuation != null) {
         this.eventLoopContinuatioToRun = null;
         eventLoopContinuation.run();
         return true;
      }
      return false;
   }

   private void virtualThreadSchedulerLoop() {
      // start the event loop thread
      var eventLoop = this.ioEventLoop;
      eventLoopThread.start();
      assert eventLoopContinuatioToRun != null;
      // we keep on running until the event loop is shutting-down
      while (!eventLoop.isTerminated()) {
         // if the event loop was idle, we apply a different limit to the yield time
         final boolean eventLoopRunning = running.get();
         final long yieldDurationNs = eventLoopRunning ? RUNNING_YIELD_US : IDLE_YIELD_US;
         int count = runExternalContinuations(yieldDurationNs);
         if (!runEventLoopContinuation() && count == 0) {
            // nothing to run, including the event loop: we can park
            parkedCarrierThread = carrierThread;
            if (canBlock()) {
               LockSupport.park();
            }
            parkedCarrierThread = null;
         }
      }
      // make sure the event loop thread is fully terminated
      // TODO verify that isAlive works!
      while (eventLoopThread.isAlive()) {
         runExternalContinuations(RUNNING_YIELD_US);
         runEventLoopContinuation();
      }
      schedulerReference.set(null);
      runQueue.close();
      // StoreLoad barrier
      while (!runQueue.isEmpty()) {
         runExternalContinuations(IDLE_YIELD_US);
      }
   }

   private boolean canBlock() {
      return runQueue.isEmpty() && eventLoopContinuatioToRun == null;
   }

   private int runExternalContinuations(long deadlineNs) {
      final long startDrainingNs = System.nanoTime();
      var ready = this.runQueue;
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

   private boolean eventLoopContinuation(Thread.VirtualThreadTask task) {
      if (eventLoopContinuatioToRun != null) {
         assert task.thread() != eventLoopThread;
         return false;
      }
      if (task.thread() == eventLoopThread) {
         eventLoopContinuatioToRun = task;
         return true;
      }
      return false;
   }

   public boolean execute(Thread.VirtualThreadTask task) {
      boolean isEventLoopContinuation = eventLoopContinuation(task);
      if (!isEventLoopContinuation) {
        if (!runQueue.offer(task)) {
            return false;
        }
      }
      if (!ioEventLoop.inEventLoop(Thread.currentThread())) {
          // TODO: if we have access to the scheduler brought by the continuation,
          //       we could skip the wakeup if matches with this.
          ioEventLoop.wakeup();
          LockSupport.unpark(parkedCarrierThread);
      } else if (!isEventLoopContinuation && !running.get()) {
          assert eventLoopContinuatioToRun == null;
          // since the event loop was blocked, it's fine if we try to consume some continuations, if any
          Thread.yield();
      }
      return true;
   }

   public static AtomicReference<EventLoopScheduler> currentRef() {
      var ref = CURRENT_SCHEDULER.orElse(EMPTY_REFERENCE);
      return ref == EMPTY_REFERENCE? null : ref;
   }
}
