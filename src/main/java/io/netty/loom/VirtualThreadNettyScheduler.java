package io.netty.loom;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ManualIoEventLoop;
import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;

public class VirtualThreadNettyScheduler implements Executor {

   private static final long MAX_WAIT_TASKS_NS = TimeUnit.SECONDS.toNanos(1);
   private static final long MAX_RUN_CONTINUATIONS_NS = TimeUnit.SECONDS.toNanos(1);

   private final MpscUnboundedArrayQueue<Runnable> externalContinuations;
   private final ManualIoEventLoop ioEventLoop;
   private final Thread carrierThread;


   public VirtualThreadNettyScheduler(IoEventLoopGroup parent, ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory, int resumedContinuationsExpectedCount) {
      this.externalContinuations = new MpscUnboundedArrayQueue<>(resumedContinuationsExpectedCount);
      this.carrierThread = threadFactory.newThread(this::internalRun);
      this.ioEventLoop = new ManualIoEventLoop(parent, carrierThread, ioHandlerFactory);
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
      var ioEventLoop = this.ioEventLoop;
      while (!ioEventLoop.isShuttingDown()) {
         int workDone = ioEventLoop.runNow();
         workDone += runExternalContinuations(MAX_RUN_CONTINUATIONS_NS);
         if (workDone == 0 && externalContinuations.isEmpty()) {
            ioEventLoop.run(MAX_WAIT_TASKS_NS);
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
   }

   private int runExternalContinuations(long deadlineNs) {
      // TODO: optimize it - we don't need to capture this regardless!
      final long startDrainingNs = System.nanoTime();
      var ready = this.externalContinuations;
      int executed = 0;
      for (; ; ) {
         var continuation = ready.poll();
         if (continuation == null) {
            break;
         }
         try {
            continuation.run();
         } catch (Throwable t) {
            // TODO let's decide what do here
         }
         executed++;
         // TODO optimize it - Do it less frequently e.g. if (executed++ % 8 == 0) { .. increase elapsed time ..}
         long elapsedNs = System.nanoTime() - startDrainingNs;
         if (elapsedNs >= deadlineNs) {
            return executed;
         }
      }
      return executed;
   }

   @Override
   public void execute(Runnable command) {
      // TODO improve it using a reject handler? It's not too strict!?
      if (ioEventLoop.isShuttingDown()) {
         throw new RejectedExecutionException("event loop is shutting down");
      }
      // TODO it would be great if VirtualThreadTask has its attached scheduler here so we can reject
      //      the command if it is not belonging to this VT scheduler
      assert command instanceof Thread.VirtualThreadTask;
      if (ioEventLoop.inEventLoop(Thread.currentThread())) {
         command.run();
      } else {
         externalContinuations.offer(command);
         // wakeup won't happen if we're shutting down!
         ioEventLoop.wakeup();
      }
   }

}
