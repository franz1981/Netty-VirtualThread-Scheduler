package io.netty.loom;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;

public class MultithreadVirtualEventExecutorGroup  extends MultiThreadIoEventLoopGroup {

   private ThreadFactory threadFactory;
   private final List<VirtualThreadNettyScheduler> schedulers = new ArrayList<>();

   public MultithreadVirtualEventExecutorGroup(int nThreads, IoHandlerFactory ioHandlerFactory) {
      super(nThreads, (Executor) command -> {
         throw new UnsupportedOperationException("this executor is not supposed to be used");
      }, ioHandlerFactory);
   }

   @Override
   protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory,
                                  @SuppressWarnings("unused") Object... args) {
      if (threadFactory == null) {
         threadFactory = newDefaultThreadFactory();
      }
      var scheduler = new VirtualThreadNettyScheduler(threadFactory, ioHandlerFactory, 1024);
      schedulers.add(scheduler);
      return scheduler.ioEventLoop();
   }

}
