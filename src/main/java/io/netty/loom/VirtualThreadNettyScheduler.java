package io.netty.loom;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.LockSupport;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ManualIoEventLoop;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue;
import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;

public class VirtualThreadNettyScheduler implements Executor, Runnable, AutoCloseable {

   private static final long MAX_WAIT_TASKS_NS = TimeUnit.SECONDS.toNanos(1);
   private static final int STATE_CREATED = 0;
   private static final int STATE_RUNNING = 1;
   private static final int STATE_CLOSING = 2;
   private static final int STATE_CLOSED = 3;

   private static final AtomicIntegerFieldUpdater<VirtualThreadNettyScheduler> STATE_UPDATER =
         AtomicIntegerFieldUpdater.newUpdater(VirtualThreadNettyScheduler.class, "state");
   private final MpscUnboundedArrayQueue<Runnable> resumedContinuations;
   private final ArrayDeque<Runnable> readyToExecuteContinuations;
   private final ManualIoEventLoop ioEventLoop;
   private final MessagePassingQueue.Consumer<Runnable> drainReadyToExecuteContinuations;
   private volatile int state;

   public VirtualThreadNettyScheduler(ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory, int resumedContinuationsExpectedCount) {
      this.resumedContinuations = new MpscUnboundedArrayQueue<>(resumedContinuationsExpectedCount);
      this.readyToExecuteContinuations = new ArrayDeque<>();
      this.ioEventLoop = new ManualIoEventLoop(threadFactory.newThread(this), ioHandlerFactory);
      this.state = STATE_CREATED;
      drainReadyToExecuteContinuations = readyToExecuteContinuations::add;
   }

   public ManualIoEventLoop ioEventLoop() {
      return ioEventLoop;
   }

   @Override
   public void run() {
      if (!STATE_UPDATER.compareAndSet(this, STATE_CREATED, STATE_RUNNING)) {
         throw new IllegalStateException("Scheduler requires to be in CREATED state in order to start, but was " + state);
      }
      var ioEventLoop = this.ioEventLoop;
      // TODO maybe it is worthy to make this scheduler's state dependent on the event loop state:
      //      if the event loop is shutting down, we should also shut down this scheduler
      //      but this will requires to keep on draining the continuations right after the event loop is terminated
      while (state == STATE_RUNNING) {
         int workDone = ioEventLoop.runNow();
         workDone += runReadyContinuations();
         if (workDone == 0) {
            ioEventLoop.run(MAX_WAIT_TASKS_NS);
         }
      }
      assert state == STATE_CLOSING;
      try {
         ioEventLoop.shutdown();
         while (!ioEventLoop.isTerminated()) {
            ioEventLoop.runNow();
            runReadyContinuations();
         }
         for (; ; ) {
            if (runReadyContinuations() == 0) {
               break;
            }
         }
      } finally {
         state = STATE_CLOSED;
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
      if (state >= STATE_CLOSING) {
         throw new RejectedExecutionException("Scheduler is closed");
      }
      if (ioEventLoop.inEventLoop(Thread.currentThread())) {
         // just add it to the array deque without any need to wake up the event loop
         readyToExecuteContinuations.add(command);
      } else {
         // this can be both happening from a virtual thread or just a regular thread (not this event loop)
         resumedContinuations.offer(command);
         ioEventLoop.wakeup();
      }
   }

   @Override
   public void close() {
      for (; ; ) {
         int currentState = state;
         switch (currentState) {
            case STATE_CREATED:
               if (STATE_UPDATER.compareAndSet(this, STATE_CREATED, STATE_CLOSED)) {
                  return;
               }
               break;
            case STATE_RUNNING:
               STATE_UPDATER.compareAndSet(this, STATE_RUNNING, STATE_CLOSING);
               break;
            case STATE_CLOSING:
               LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
               break;
            case STATE_CLOSED:
               return;
            default:
               throw new IllegalStateException("Unexpected state: " + currentState);
         }
      }
   }

}
