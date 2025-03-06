package io.netty.loom;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ManualIoEventLoop;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue;
import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;

public class VirtualThreadNettyScheduler implements Executor {

   private static final long MAX_WAIT_TASKS_NS = TimeUnit.SECONDS.toNanos(1);

   private final MpscUnboundedArrayQueue<Runnable> resumedContinuations;
   private final ArrayDeque<Runnable> readyToExecuteContinuations;
   private final ManualIoEventLoop ioEventLoop;
   private final MessagePassingQueue.Consumer<Runnable> drainReadyToExecuteContinuations;
   private final Thread carrierThread;
   private Runnable eventExecutorAwareRun;


   public VirtualThreadNettyScheduler(ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory, int resumedContinuationsExpectedCount) {
      this.resumedContinuations = new MpscUnboundedArrayQueue<>(resumedContinuationsExpectedCount);
      this.readyToExecuteContinuations = new ArrayDeque<>();
      // TODO check on Netty what we can do to improve here :"(
      var carrierThread = threadFactory.newThread(() -> {
         eventExecutorAwareRun.run();
      });
      this.ioEventLoop = new ManualIoEventLoop(carrierThread, ioHandlerFactory);
      // TODO This is to make sure that the event executor is aware of the current event executor,
      //      necessary for utils method like ThreadExecutorMap.currentExecutor() used on allocators pooling
      this.eventExecutorAwareRun = ThreadExecutorMap.apply(this::internalRun, ioEventLoop);
      drainReadyToExecuteContinuations = readyToExecuteContinuations::add;
      this.carrierThread = carrierThread;
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
      // TODO maybe it is worthy to make this scheduler's state dependent on the event loop state:
      //      if the event loop is shutting down, we should also shut down this scheduler
      //      but this will requires to keep on draining the continuations right after the event loop is terminated
      while (!ioEventLoop.isShuttingDown()) {
         int workDone = ioEventLoop.runNow();
         workDone += runReadyContinuations();
         if (workDone == 0) {
            ioEventLoop.run(MAX_WAIT_TASKS_NS);
         }
      }
      while (!ioEventLoop.isTerminated()) {
         ioEventLoop.runNow();
         runReadyContinuations();
      }
      for (; ; ) {
         if (runReadyContinuations() == 0) {
            break;
         }
      }
   }

   private int runReadyContinuations() {
      moveContinuationsIntoReadyQueue();
      var ready = this.readyToExecuteContinuations;
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
      }
      return executed;
   }

   private void moveContinuationsIntoReadyQueue() {
      var drainIntoReadyQ = drainReadyToExecuteContinuations;
      // this can happen in a more batchy way
      do {
         resumedContinuations.drain(drainIntoReadyQ);
      } while (!resumedContinuations.isEmpty());
   }

   @Override
   public void execute(Runnable command) {
      // TODO it would be great if VirtualThreadTask has its attached scheduler here so we can reject
      //      the command if it is not belonging to this VT scheduler
      assert command instanceof Thread.VirtualThreadTask;
      if (ioEventLoop.inEventLoop(Thread.currentThread())) {
         if (readyToExecuteContinuations.isEmpty()) {
            // mo need to check for reentrancy here since vt are not inEventLoop!
            command.run();
         } else {
            // just add it to the array deque without any need to wake up the event loop
            readyToExecuteContinuations.add(command);
         }
      } else {
         // this can be both happening from a virtual thread or just a regular thread (not this event loop)
         resumedContinuations.offer(command);
         // wakeup won't happen if we're shutting down!
         ioEventLoop.wakeup();
      }
   }

}
