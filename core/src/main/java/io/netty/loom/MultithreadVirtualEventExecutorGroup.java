package io.netty.loom;

import java.util.IdentityHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SingleThreadIoEventLoop;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;

public class MultithreadVirtualEventExecutorGroup extends MultiThreadIoEventLoopGroup {

   private static final boolean NETTY_SIMPLE_SCHEDULER = Boolean.getBoolean("io.netty.loom.simple.scheduler");
   private static final int RESUMED_CONTINUATIONS_EXPECTED_COUNT = Integer.getInteger("io.netty.loom.resumed.continuations", 1024);
   private ThreadFactory threadFactory;
   private IdentityHashMap<Thread, Executor> schedulers;
   private final FastThreadLocal<ThreadFactory> v_thread_factory = new FastThreadLocal<>() {
      @Override
      protected ThreadFactory initialValue() {
         var scheduler = schedulers.get(Thread.currentThread());
         if (scheduler == null) {
            return null;
         }
         return LoomSupport.setVirtualThreadFactoryScheduler(Thread.ofVirtual(), scheduler).factory();
      }
   };

   public MultithreadVirtualEventExecutorGroup(int nThreads, IoHandlerFactory ioHandlerFactory) {
      super(nThreads, (Executor) command -> {
         throw new UnsupportedOperationException("this executor is not supposed to be used");
      }, ioHandlerFactory);
   }

   public ThreadFactory vThreadFactory() {
      if (!(Thread.currentThread() instanceof FastThreadLocalThread)) {
         // TODO think how to implement this if is from an existing virtual thread we own:
         //      maybe we should use ScopedValue?
         throw new IllegalStateException("this method should be called from event loop fast thread local threads");
      }
      return v_thread_factory.get();
   }

   @Override
   protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory,
                                  @SuppressWarnings("unused") Object... args) {
      final Thread carrierThread;
      if (schedulers == null) {
         schedulers = new IdentityHashMap<>();
      }
      if (NETTY_SIMPLE_SCHEDULER) {
         var eventLoop = new SingleThreadIoEventLoop(this, executor, ioHandlerFactory);
         try {
            eventLoop.submit(new Runnable() {
               @Override
               public void run() {
                  schedulers.put(Thread.currentThread(), eventLoop);
               }
            }).get();
            return eventLoop;
         } catch (Throwable e) {
            throw new RuntimeException(e);
         }
      } else {
         if (threadFactory == null) {
            threadFactory = newDefaultThreadFactory();
         }
         var customScheduler = new VirtualThreadNettyScheduler(this, threadFactory, ioHandlerFactory, RESUMED_CONTINUATIONS_EXPECTED_COUNT);
         schedulers.put(customScheduler.getCarrierThread(), customScheduler);
         return customScheduler.ioEventLoop();
      }
   }

}
