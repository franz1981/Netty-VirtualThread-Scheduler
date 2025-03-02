package io.netty.loom;

import java.util.IdentityHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;

public class MultithreadVirtualEventExecutorGroup extends MultiThreadIoEventLoopGroup {

   public static final int RESUMED_CONTINUATIONS_EXPECTED_COUNT = Integer.getInteger("io.netty.loom.resumed.continuations", 1024);
   private ThreadFactory threadFactory;
   private IdentityHashMap<Thread, VirtualThreadNettyScheduler> schedulers;
   private final FastThreadLocal<ThreadFactory> v_thread_factory = new FastThreadLocal<>() {
      @Override
      protected ThreadFactory initialValue() {
         var scheduler = schedulers.get(Thread.currentThread());
         if (scheduler == null) {
            return null;
         }
         return Thread.ofVirtual().scheduler(scheduler).factory();
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
      if (threadFactory == null) {
         threadFactory = newDefaultThreadFactory();
      }
      var scheduler = new VirtualThreadNettyScheduler(threadFactory, ioHandlerFactory, RESUMED_CONTINUATIONS_EXPECTED_COUNT);
      if (schedulers == null) {
         schedulers = new IdentityHashMap<>();
      }
      schedulers.put(scheduler.getCarrierThread(), scheduler);
      return scheduler.ioEventLoop();
   }

}
