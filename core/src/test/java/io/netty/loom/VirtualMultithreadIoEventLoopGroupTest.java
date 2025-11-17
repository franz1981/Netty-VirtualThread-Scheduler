package io.netty.loom;

import static java.util.concurrent.StructuredTaskScope.Joiner.allSuccessfulOrThrow;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.IoRegistration;
import io.netty.channel.local.LocalIoHandler;

import org.junit.jupiter.api.Test;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.ThreadExecutorMap;
import org.junit.jupiter.api.Timeout;

public class VirtualMultithreadIoEventLoopGroupTest {

   @Test
   void processHttpRequestWithVirtualThreadOnManualNettyEventLoop() throws InterruptedException {
      var group = new VirtualMultithreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      // create a simple http request server
      InetSocketAddress inetAddress = new InetSocketAddress(8080);
      CountDownLatch sendResponse = new CountDownLatch(1);
      var bootstrap = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {

               @Override
               protected void initChannel(SocketChannel ch) {
                  ch.pipeline().addLast(new HttpServerCodec());
                  // Netty is going to create a new one for each connection
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                     @Override
                     public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof DefaultHttpRequest) {
                           group.vThreadFactory().newThread(() -> {
                              try {
                                 sendResponse.await();
                                 var contentBytes = ctx.alloc().directBuffer("HELLO!".length());
                                 contentBytes.writeCharSequence("HELLO!", CharsetUtil.US_ASCII);
                                 var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, contentBytes);
                                 response.headers()
                                       .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
                                       .set(HttpHeaderNames.CONTENT_LENGTH, contentBytes.readableBytes())
                                       .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                                 ctx.writeAndFlush(response, ctx.voidPromise());
                              } catch (InterruptedException e) {
                                 Thread.currentThread().interrupt();
                              }
                           }).start();
                        }
                        ReferenceCountUtil.release(msg);
                     }
                  });
               }
            });
      Channel channel = bootstrap.bind(inetAddress)
            .sync().channel();
      // use a http client to send a random request and check the response
      try (var client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build()) {
         var request = HttpRequest.newBuilder()
               .uri(URI.create("http://localhost:8080"))
               .header("Content-Type", "text/plain")
               .GET().build();
         var httpResponseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
         sendResponse.countDown();
         var httpResponse = httpResponseFuture.join();
         assertEquals(200, httpResponse.statusCode());
         assertEquals("HELLO!", httpResponse.body());
      }
      channel.close().await();
      group.shutdownGracefully();
   }

   @Test
   void virtualEventExecutorGroupCorrectlySetEventExecutor() throws ExecutionException, InterruptedException {
      var group = new VirtualMultithreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      var ioEventLoop = group.next();
      assertInstanceOf(EventExecutor.class, ioEventLoop);
      assertTrue(group.submit(() -> ThreadExecutorMap.currentExecutor() == ioEventLoop).get());
      group.shutdownGracefully();
   }

   @Test
   void busyYieldMakeEveryoneToProgress() throws InterruptedException {
      var group = new VirtualMultithreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      // create a simple http request server
      InetSocketAddress inetAddress = new InetSocketAddress(8080);
      CountDownLatch sendResponse = new CountDownLatch(1);
      AtomicBoolean secondVThreadHasDone = new AtomicBoolean(false);
      AtomicInteger yields = new AtomicInteger();
      CyclicBarrier bothDone = new CyclicBarrier(2);
      var bootstrap = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {

               @Override
               protected void initChannel(SocketChannel ch) {
                  ch.pipeline().addLast(new HttpServerCodec());
                  // Netty is going to create a new one for each connection
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                     @Override
                     public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof DefaultHttpRequest) {
                           var vthreadFactory = group.vThreadFactory();
                           vthreadFactory.newThread(() -> {
                              try {
                                 sendResponse.await();
                                 vthreadFactory.newThread(() -> {
                                    try {
                                       Thread.sleep(1000);
                                    } catch (InterruptedException e) {
                                       // ignore
                                    } finally {
                                       secondVThreadHasDone.lazySet(true);
                                    }
                                    try {
                                       bothDone.await();
                                    } catch (BrokenBarrierException | InterruptedException e) {
                                       // ignore
                                    }
                                 }).start();
                                 while (!secondVThreadHasDone.get()) {
                                    yields.lazySet(yields.get() + 1);
                                    Thread.yield();
                                 }
                                 try {
                                    bothDone.await();
                                    var contentBytes = ctx.alloc().directBuffer("HELLO!".length());
                                    contentBytes.writeCharSequence("HELLO!", CharsetUtil.US_ASCII);
                                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, contentBytes);
                                    response.headers()
                                          .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
                                          .set(HttpHeaderNames.CONTENT_LENGTH, contentBytes.readableBytes())
                                          .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                                    ctx.writeAndFlush(response, ctx.voidPromise());
                                 } catch (BrokenBarrierException e) {
                                    // ignore
                                 }
                              } catch (InterruptedException e) {
                                 Thread.currentThread().interrupt();
                              } finally {

                              }
                           }).start();


                        }
                        ReferenceCountUtil.release(msg);
                     }
                  });
               }
            });
      Channel channel = bootstrap.bind(inetAddress)
            .sync().channel();
      // use a http client to send a random request and check the response
      try (var client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build()) {
         var request = HttpRequest.newBuilder()
               .uri(URI.create("http://localhost:8080"))
               .header("Content-Type", "text/plain")
               .GET().build();
         var httpResponseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
         sendResponse.countDown();
         var httpResponse = httpResponseFuture.join();
         assertEquals(200, httpResponse.statusCode());
         assertEquals("HELLO!", httpResponse.body());
         // assert yields more than 1
         assertNotEquals(0, yields.get());
      }
      channel.close().await();
      group.shutdownGracefully();
   }

   @Test
   void saveWakeupsOnVirtualThreads() throws InterruptedException, ExecutionException {
      var wakeupCounter = new AtomicInteger();
      var nioFactory = NioIoHandler.newFactory();
      IoHandlerFactory counterHandlerFactory = ioExecutor ->  {
         var ioHandler = nioFactory.newHandler(ioExecutor);
         return new IoHandler() {

            @Override
            public void initialize() {
               ioHandler.initialize();
            }

            @Override
            public int run(IoHandlerContext context) {
               return ioHandler.run(context);
            }

            @Override
            public void prepareToDestroy() {
               ioHandler.prepareToDestroy();
            }

            @Override
            public void destroy() {
               ioHandler.destroy();
            }

            @Override
            public IoRegistration register(IoHandle handle) throws Exception {
               return ioHandler.register(handle);
            }

            @Override
            public void wakeup() {
               wakeupCounter.incrementAndGet();
               ioHandler.wakeup();
            }

            @Override
            public boolean isCompatible(Class<? extends IoHandle> handleType) {
               return ioHandler.isCompatible(handleType);
            }
         };
      };
      var group = new VirtualMultithreadIoEventLoopGroup(1, counterHandlerFactory);
      // create a simple http request server
      InetSocketAddress inetAddress = new InetSocketAddress(8080);
      var innerVThreadCreationFromVThread = new CompletableFuture<Integer>();
      var innerWriteFromVThread = new CompletableFuture<Integer>();
      var bootstrap = new ServerBootstrap()
              .group(group)
              .channel(NioServerSocketChannel.class)
              .childHandler(new ChannelInitializer<SocketChannel>() {

                 @Override
                 protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    // Netty is going to create a new one for each connection
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                       @Override
                       public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                          if (msg instanceof DefaultHttpRequest) {
                             var factory = group.vThreadFactory();
                             factory.newThread(() -> {
                                final int beforeInner = wakeupCounter.get();
                                factory.newThread(() -> {
                                    var contentBytes = ctx.alloc().directBuffer("HELLO!".length());
                                    contentBytes.writeCharSequence("HELLO!", CharsetUtil.US_ASCII);
                                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, contentBytes);
                                    response.headers()
                                            .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
                                            .set(HttpHeaderNames.CONTENT_LENGTH, contentBytes.readableBytes())
                                            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                                    final int beforeWrite = wakeupCounter.get();
                                    ctx.writeAndFlush(response, ctx.voidPromise());
                                    final int afterWrite = wakeupCounter.get();
                                    innerWriteFromVThread.complete(afterWrite - beforeWrite);
                                }).start();
                                final int afterInner = wakeupCounter.get();
                                innerVThreadCreationFromVThread.complete(afterInner - beforeInner);
                             }).start();
                          }
                          ReferenceCountUtil.release(msg);
                       }
                    });
                 }
              });
      Channel channel = bootstrap.bind(inetAddress)
              .sync().channel();
      // use a http client to send a random request and check the response
      try (var client = HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1).build()) {
         var request = HttpRequest.newBuilder()
                 .uri(URI.create("http://localhost:8080"))
                 .header("Content-Type", "text/plain")
                 .GET().build();
         client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
      }
      assertEquals(0, innerVThreadCreationFromVThread.get().intValue());
      assertEquals(0, innerWriteFromVThread.get().intValue());
      channel.close().await();
      group.shutdownGracefully();
   }

    @Test
    void schedulerIsNotInheritedWithThreadOfVirtual() throws InterruptedException, ExecutionException {
        try (var group = new VirtualMultithreadIoEventLoopGroup(1, LocalIoHandler.newFactory())) {
            final var expectedScheduler = group.submit(() -> EventLoopScheduler.currentThreadSchedulerContext().scheduler().get()).get();
            assertNotNull(expectedScheduler);
            final var vThreadFactory = group.submit(group::vThreadFactory).get();
            var groupEventLoopScheduler = new CompletableFuture<EventLoopScheduler>();
            var noEventLoopScheduler = new CompletableFuture<>();
            vThreadFactory.newThread(() -> {
                Thread.ofVirtual().start(() -> noEventLoopScheduler.complete(EventLoopScheduler.currentThreadSchedulerContext().scheduler()));
                groupEventLoopScheduler.complete(EventLoopScheduler.currentThreadSchedulerContext().scheduler().get());
            }).start();
            assertEquals(expectedScheduler, groupEventLoopScheduler.get());
            assertNull(noEventLoopScheduler.get());
        }
    }

    @Test
    void schedulerIsInheritedByForkedVTFromTheRightFactory() throws InterruptedException, ExecutionException {
        try (var group = new VirtualMultithreadIoEventLoopGroup(1, LocalIoHandler.newFactory())) {
            final var expectedEventLoopScheduler = group.submit(() -> EventLoopScheduler.currentThreadSchedulerContext().scheduler().get()).get();
            assertNotNull(expectedEventLoopScheduler);
            final var vThreadFactory = group.submit(group::vThreadFactory).get();
            var forkInheritedScheduler = new CompletableFuture<EventLoopScheduler>();
            vThreadFactory.newThread(() -> {
                try (var scope = StructuredTaskScope.open(allSuccessfulOrThrow(),
                        cf -> cf.withThreadFactory(vThreadFactory))) {
                    var task = scope.fork(() -> EventLoopScheduler.currentThreadSchedulerContext().scheduler().get());
                    scope.join();
                    forkInheritedScheduler.complete(task.get());
                } catch (InterruptedException e) {
                    forkInheritedScheduler.completeExceptionally(e);
                }
            }).start();
            assertEquals(expectedEventLoopScheduler, forkInheritedScheduler.get());
        }
    }

    @Test
    void schedulerIsNotInheritedByForkedVT() throws InterruptedException, ExecutionException {
        try (var group = new VirtualMultithreadIoEventLoopGroup(1, LocalIoHandler.newFactory())) {
            final var vThreadFactory = group.submit(group::vThreadFactory).get();
            var schedulerRef = new CompletableFuture<EventLoopScheduler.SharedRef>();
            vThreadFactory.newThread(() -> {
                try (var scope = StructuredTaskScope.open(allSuccessfulOrThrow())) {
                    var task = scope.fork(() -> EventLoopScheduler.currentThreadSchedulerContext().scheduler());
                    scope.join();
                    schedulerRef.complete(task.get());
                } catch (InterruptedException e) {
                    schedulerRef.completeExceptionally(e);
                }
            }).start();
            assertNull(schedulerRef.get());
        }
    }

    @Test
    @Timeout(10)
    void schedulerIsNotLeakingIfItsThreadFactoryOutliveIt() throws InterruptedException, ExecutionException {
        ThreadFactory vThreadFactory;
        WeakReference<EventLoopScheduler> schedulerWeakRef;
        EventLoopScheduler.SharedRef schedulerRef;
        try (var group = new VirtualMultithreadIoEventLoopGroup(1, LocalIoHandler.newFactory())) {
            vThreadFactory = group.submit(group::vThreadFactory).get();
            schedulerRef = group.submit(() -> EventLoopScheduler.currentThreadSchedulerContext().scheduler()).get();
            schedulerWeakRef = new WeakReference<>(schedulerRef.get());
            assertNotNull(schedulerRef.get());
            assertNotNull(schedulerWeakRef.get());
        }
        while (schedulerRef.get() != null) {
            Thread.yield();
        }
        while (schedulerWeakRef.get() != null) {
            System.gc();
            System.runFinalization();
            Thread.sleep(100);
        }
        // we can still run virtual threads from the factory without the scheduler
        var schedulerRefPromise = new CompletableFuture<EventLoopScheduler.SharedRef>();
        vThreadFactory.newThread(() -> schedulerRefPromise.complete(EventLoopScheduler.currentThreadSchedulerContext().scheduler())).start();
        assertSame(schedulerRef, schedulerRefPromise.get());
        assertNull(schedulerRefPromise.get().get());
    }

    @Test
    void virtualThreadCanMakeProgressEvenIfEventLoopIsClosed() throws InterruptedException, ExecutionException, BrokenBarrierException, TimeoutException {
        var group = new VirtualMultithreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        final var barrier = new CyclicBarrier(2);
        final var vThreadFactory = group.submit(group::vThreadFactory).get();
        vThreadFactory.newThread(() -> {
            try {
                group.shutdownGracefully().get();
                barrier.await();
            } catch (Throwable e) {
                // ignore
            }
        }).start();
        barrier.await(5, TimeUnit.SECONDS);
    }

   @Test
   void eventLoopSchedulerCanMakeProgressIfTheEventLoopIsBlocked() throws BrokenBarrierException, InterruptedException, TimeoutException {
      var group = new VirtualMultithreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      var allBlocked = new CyclicBarrier(3);
      group.execute(() -> {
         group.vThreadFactory().newThread(() -> {
            try {
               allBlocked.await();
            } catch (Throwable t) {
            }
         }).start();
         try {
            allBlocked.await();
         } catch (Throwable e) {
         }
      });
      try {
         allBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS);
      } finally {
         group.shutdownGracefully();
      }
   }

   @Test
   void testFairness() throws ExecutionException, InterruptedException {
      final long V_TASK_DURATION_NS = TimeUnit.MILLISECONDS.toNanos(100);
      int tasks = 4;
      var group = new VirtualMultithreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      var interleavingVirtualThreads = new AtomicBoolean(false);

      var nonBlockingTasksCompleted = new CountDownLatch(tasks);
      group.submit(() -> {
         var counter = new AtomicInteger();
         for (int i = 0; i < tasks; i++) {
            group.vThreadFactory().newThread(() -> {
               spinWait(V_TASK_DURATION_NS);
               int count = counter.incrementAndGet();
               group.execute(() -> {
                  if (counter.get() != count) {
                     interleavingVirtualThreads.set(true);
                  }
                  nonBlockingTasksCompleted.countDown();
               });
            }).start();
         }
      }).get();
      nonBlockingTasksCompleted.await();
      group.shutdownGracefully();
      assertFalse(interleavingVirtualThreads.get());
   }

    private static void spinWait(long nanos) {
        final long start = System.nanoTime();
        while ((System.nanoTime() - start) < nanos) {
            Thread.onSpinWait();
        }
    }

    @Test
    void testPlatformThreadSpawnsVirtualThreads() throws ExecutionException, InterruptedException {
        try (var group = new VirtualMultithreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = executor.submit(() -> EventLoopScheduler.currentThreadSchedulerContext().scheduler());
            assertNull(scheduler.get());
        }
    }

   @Test
   void testBlockingIO() throws IOException, InterruptedException, ExecutionException {
      assumeTrue(NettyScheduler.perCarrierPollers());
      try (var group = new VirtualMultithreadIoEventLoopGroup(1, NioIoHandler.newFactory());
           var serverAcceptor = new ServerSocket(0)) {
           var serverSocketPromise = new CompletableFuture<Socket>();
           Thread.ofVirtual().start(() -> {
              try {
                  serverSocketPromise.complete(serverAcceptor.accept());
              } catch (Throwable e) {
                  // complete exceptionally
                  serverSocketPromise.completeExceptionally(e);
              }
           });
          try (var clientSocket = new Socket("localhost", serverAcceptor.getLocalPort());
               var serverSocket = serverSocketPromise.join();
               var clientOut = clientSocket.getOutputStream();
               var serverIn = serverSocket.getInputStream()) {
               var readerVThreadPromise = new CompletableFuture<Thread>();
               group.execute(() -> {
                  var readerThread = group.vThreadFactory().newThread(() -> {
                      var eventLoopScheduler = EventLoopScheduler.currentThreadSchedulerContext().scheduler().get();
                      assertEquals(0, eventLoopScheduler.externalContinuationsCount());
                      try {
                          serverIn.read();
                      } catch (IOException e) {
                          throw new RuntimeException(e);
                      }
                  });
                  readerVThreadPromise.complete(readerThread);
                  readerThread.start();

              });
              var readerVThread = readerVThreadPromise.get();
              // it has to be waiting on read
              while (readerVThread.getState() != Thread.State.WAITING) {
                  Thread.sleep(1);
              }
              clientOut.write(1);
              readerVThread.join();
          }
      }
   }
}
