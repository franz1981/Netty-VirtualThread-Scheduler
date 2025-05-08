package io.netty.loom;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ManualIoEventLoop;
import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;

public class VirtualThreadNettyScheduler implements Executor {

   private static final long MAX_WAIT_TASKS_NS = TimeUnit.SECONDS.toNanos(1);
   private static final long MAX_RUN_CONTINUATIONS_NS = TimeUnit.MICROSECONDS.toNanos(Integer.getInteger("io.netty.loom.run.us", 1));

   private final MpscUnboundedArrayQueue<Runnable> externalContinuations;
   private final ManualIoEventLoop ioEventLoop;
   private final Thread carrierThread;
   private final AtomicBoolean running;

   public VirtualThreadNettyScheduler(IoEventLoopGroup parent, ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory, int resumedContinuationsExpectedCount) {
      this.running = new AtomicBoolean(false);
      this.externalContinuations = new MpscUnboundedArrayQueue<>(resumedContinuationsExpectedCount);
      this.carrierThread = threadFactory.newThread(this::internalRun);
      this.ioEventLoop = new ManualIoEventLoop(parent, carrierThread,
              ioExecutor -> new AwakeAwareIoHandler(running, ioHandlerFactory.newHandler(ioExecutor)));
      // we can start the carrier only after all the fields are initialized
      carrierThread.start();
   }

   public Thread getCarrierThread() {
      return carrierThread;
   }

   public ManualIoEventLoop ioEventLoop() {
      return ioEventLoop;
   }

   private void internalRun() {
      running.set(true);
      try {
         var ioEventLoop = this.ioEventLoop;
         while (!ioEventLoop.isShuttingDown()) {
            int workDone = ioEventLoop.runNow();
            workDone += runExternalContinuations(MAX_RUN_CONTINUATIONS_NS);
            if (workDone == 0 && externalContinuations.isEmpty()) {
               running.set(false);
               // StoreLoad barrier: see https://www.scylladb.com/2018/02/15/memory-barriers-seastar-linux/
               // this is necessary to make sure that we won't lose any continuations not paired by a wakeup!
               try {
                  if (externalContinuations.isEmpty()) {
                     ioEventLoop.run(MAX_WAIT_TASKS_NS);
                  }
               } finally {
                  running.set(true);
               }
            }
         }
         while (!ioEventLoop.isTerminated()) {
            ioEventLoop.runNow();
            runExternalContinuations(MAX_RUN_CONTINUATIONS_NS);
         }
         // TODO fix it
         while (!externalContinuations.isEmpty()) {
            runExternalContinuations(MAX_RUN_CONTINUATIONS_NS);
         }
      } finally {
         running.set(false);
      }
   }

   private int runExternalContinuations(long deadlineNs) {
      final long startDrainingNs = System.nanoTime();
      var ready = this.externalContinuations;
      int executed = 0;
      for (; ; ) {
         var continuation = ready.poll();
         if (continuation == null) {
            break;
         }
         continuation.run();
         executed++;
         long elapsedNs = System.nanoTime() - startDrainingNs;
         if (elapsedNs >= deadlineNs) {
            return executed;
         }
      }
      return executed;
   }

   @Override
   public void execute(Runnable command) {
      if (ioEventLoop.isShuttingDown()) {
         throw new RejectedExecutionException("event loop is shutting down");
      }
      externalContinuations.offer(command);
      if (!ioEventLoop.inEventLoop(Thread.currentThread())) {
         ioEventLoop.wakeup();
      }
   }

}
