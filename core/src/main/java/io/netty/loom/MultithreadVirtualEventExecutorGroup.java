package io.netty.loom;

import java.util.IdentityHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.util.concurrent.FastThreadLocal;

public class MultithreadVirtualEventExecutorGroup extends MultiThreadIoEventLoopGroup {

   private static final int RESUMED_CONTINUATIONS_EXPECTED_COUNT = Integer.getInteger("io.netty.loom.resumed.continuations", 1024);
   private ThreadFactory threadFactory;
   private IdentityHashMap<Thread, EventLoopScheduler> schedulers;
   private final FastThreadLocal<EventLoopScheduler> v_thread_factory = new FastThreadLocal<>() {
      @Override
      protected EventLoopScheduler initialValue() {
         return schedulers.get(Thread.currentThread());
      }
   };

   public MultithreadVirtualEventExecutorGroup(int nThreads, IoHandlerFactory ioHandlerFactory) {
      super(nThreads, (Executor) command -> {
         throw new UnsupportedOperationException("this executor is not supposed to be used");
      }, ioHandlerFactory);
   }

   public ThreadFactory vThreadFactory() {
      return v_thread_factory.get().virtualThreadFactory();
   }

   @Override
   protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory,
                                  @SuppressWarnings("unused") Object... args) {
      if (schedulers == null) {
         schedulers = new IdentityHashMap<>();
      }
      if (threadFactory == null) {
         threadFactory = newDefaultThreadFactory();
      }
      var customScheduler = new EventLoopScheduler(this, threadFactory, ioHandlerFactory, RESUMED_CONTINUATIONS_EXPECTED_COUNT);
      schedulers.put(customScheduler.eventLoopThread(), customScheduler);
      return customScheduler.ioEventLoop();
   }

}
