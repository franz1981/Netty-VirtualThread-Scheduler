package io.netty.loom;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultithreadVirtualEventExecutorGroupTest {

   @Test
   void processHttpRequestWithVirtualThreadOnManualNettyEventLoop() throws InterruptedException {
      var group = new MultithreadVirtualEventExecutorGroup(1, NioIoHandler.newFactory());
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
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter(){

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
      group.shutdown();
   }

   @Test
   void virtualEventExecutorGroupCorrectlySetEventExecutor() throws ExecutionException, InterruptedException {
      var group = new MultithreadVirtualEventExecutorGroup(1, NioIoHandler.newFactory());
      var ioEventLoop = group.next();
      assertInstanceOf(EventExecutor.class, ioEventLoop);
      assertTrue(group.submit(() -> ThreadExecutorMap.currentExecutor() == ioEventLoop).get());
      group.shutdown();
   }

   @Test
   void busyYieldMakeEveryoneToProgress() throws InterruptedException {
      var group = new MultithreadVirtualEventExecutorGroup(1, NioIoHandler.newFactory());
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
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter(){

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
      group.shutdown();
   }
}
