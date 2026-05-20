/*
 * Copyright 2025 The Netty VirtualThread Scheduler Project
 *
 * The Netty VirtualThread Scheduler Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
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
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;

import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Timeout(10)
public class VirtualIoPollerEventLoopGroupTest {

	// Transport enumeration to drive tests across available Netty transports.
	private enum Transport {
		NIO, EPOLL, IO_URING, LOCAL;

		boolean isLocal() {
			return this == LOCAL;
		}

		boolean isAvailable() {
			return switch (this) {
				case NIO -> true;
				case EPOLL -> Epoll.isAvailable();
				case IO_URING -> IoUring.isAvailable();
				case LOCAL -> true;
				default -> false;
			};
		}

		IoHandlerFactory handlerFactory() {
			return switch (this) {
				case NIO -> NioIoHandler.newFactory();
				case EPOLL -> EpollIoHandler.newFactory();
				case IO_URING -> IoUringIoHandler.newFactory();
				case LOCAL -> LocalIoHandler.newFactory();
				default -> throw new IllegalStateException();
			};
		}

		Class<? extends io.netty.channel.ServerChannel> serverChannelClass() {
			return switch (this) {
				case NIO -> NioServerSocketChannel.class;
				case EPOLL -> EpollServerSocketChannel.class;
				case IO_URING -> IoUringServerSocketChannel.class;
				case LOCAL -> throw new IllegalStateException(
						"LOCAL transport does not provide a ServerChannel class for real networking");
				default -> throw new IllegalStateException();
			};
		}
	}

	private static Stream<Arguments> transportsForNetworking() {
		return Stream.of(Transport.values()).filter(t -> !t.isLocal() && t.isAvailable()).map(Arguments::of);
	}

	private static Stream<Arguments> transportsAllowLocal() {
		return Stream.of(Transport.values()).filter(Transport::isAvailable).map(Arguments::of);
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsForNetworking")
	void processHttpRequestWithVirtualThreadOnManualNettyEventLoop(Transport transport) throws InterruptedException {
		assumeTrue(transport.isAvailable());
		// avoid LOCAL for real networking tests
		assumeTrue(!transport.isLocal());
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory())) {
			// create a simple http request server
			InetSocketAddress inetAddress = new InetSocketAddress(8080);
			CountDownLatch sendResponse = new CountDownLatch(1);
			var bootstrap = new ServerBootstrap().group(group).channel(transport.serverChannelClass())
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
												var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
														HttpResponseStatus.OK, contentBytes);
												response.headers()
														.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
														.set(HttpHeaderNames.CONTENT_LENGTH,
																contentBytes.readableBytes())
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
			Channel channel = bootstrap.bind(inetAddress).sync().channel();
			// use a http client to send a random request and check the response
			try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
				var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:8080"))
						.header("Content-Type", "text/plain").GET().build();
				var httpResponseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
				sendResponse.countDown();
				var httpResponse = httpResponseFuture.join();
				assertEquals(200, httpResponse.statusCode());
				assertEquals("HELLO!", httpResponse.body());
			}
			channel.close().await();
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	void virtualEventExecutorGroupCorrectlySetEventExecutor(Transport transport)
			throws ExecutionException, InterruptedException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory())) {
			var ioEventLoop = group.next();
			assertInstanceOf(EventExecutor.class, ioEventLoop);
			assertTrue(group.submit(() -> ThreadExecutorMap.currentExecutor() == ioEventLoop).get());
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsForNetworking")
	void busyYieldMakeEveryoneToProgress(Transport transport) throws InterruptedException {
		assumeTrue(transport.isAvailable());
		assumeTrue(!transport.isLocal());
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory())) {
			// create a simple http request server
			InetSocketAddress inetAddress = new InetSocketAddress(8080);
			CountDownLatch sendResponse = new CountDownLatch(1);
			AtomicBoolean secondVThreadHasDone = new AtomicBoolean(false);
			AtomicInteger yields = new AtomicInteger();
			CyclicBarrier bothDone = new CyclicBarrier(2);
			var bootstrap = new ServerBootstrap().group(group).channel(transport.serverChannelClass())
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
													var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
															HttpResponseStatus.OK, contentBytes);
													response.headers()
															.set(HttpHeaderNames.CONTENT_TYPE,
																	HttpHeaderValues.TEXT_PLAIN)
															.set(HttpHeaderNames.CONTENT_LENGTH,
																	contentBytes.readableBytes())
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
			Channel channel = bootstrap.bind(inetAddress).sync().channel();
			// use a http client to send a random request and check the response
			try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
				var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:8080"))
						.header("Content-Type", "text/plain").GET().build();
				var httpResponseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
				sendResponse.countDown();
				var httpResponse = httpResponseFuture.join();
				assertEquals(200, httpResponse.statusCode());
				assertEquals("HELLO!", httpResponse.body());
				// assert yields more than 1
				assertNotEquals(0, yields.get());
			}
			channel.close().await();
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	void saveWakeupsOnVirtualThreads(Transport transport) throws InterruptedException, ExecutionException {
		assumeTrue(transport.isAvailable());
		assumeTrue(!transport.isLocal());
		var wakeupCounter = new AtomicInteger();
		IoHandlerFactory baseFactory = transport.handlerFactory();
		IoHandlerFactory counterHandlerFactory = ioExecutor -> {
			var ioHandler = baseFactory.newHandler(ioExecutor);
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
		try (var group = new VirtualIoPollerEventLoopGroup(1, counterHandlerFactory)) {
			// create a simple http request server
			InetSocketAddress inetAddress = new InetSocketAddress(8080);
			var innerVThreadCreationFromVThread = new CompletableFuture<Integer>();
			var innerWriteFromVThread = new CompletableFuture<Integer>();
			var bootstrap = new ServerBootstrap().group(group).channel(transport.serverChannelClass())
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
												var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
														HttpResponseStatus.OK, contentBytes);
												response.headers()
														.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
														.set(HttpHeaderNames.CONTENT_LENGTH,
																contentBytes.readableBytes())
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
			Channel channel = bootstrap.bind(inetAddress).sync().channel();
			// use a http client to send a random request and check the response
			try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
				var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:8080"))
						.header("Content-Type", "text/plain").GET().build();
				client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
			}
			assertEquals(0, innerVThreadCreationFromVThread.get().intValue());
			assertEquals(0, innerWriteFromVThread.get().intValue());
			channel.close().await();
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	void schedulerIsNotInheritedWithThreadOfVirtual(Transport transport)
			throws InterruptedException, ExecutionException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory())) {
			final var expectedScheduler = group.submit(() -> EventLoopScheduler.currentScheduler()).get();
			assertNotNull(expectedScheduler);
			final var vThreadFactory = group.submit(group::vThreadFactory).get();
			var groupEventLoopScheduler = new CompletableFuture<EventLoopScheduler>();
			var noEventLoopScheduler = new CompletableFuture<>();
			vThreadFactory.newThread(() -> {
				Thread.ofVirtual().start(() -> noEventLoopScheduler.complete(EventLoopScheduler.currentScheduler()));
				groupEventLoopScheduler.complete(EventLoopScheduler.currentScheduler());
			}).start();
			assertEquals(expectedScheduler, groupEventLoopScheduler.get());
			assertNull(noEventLoopScheduler.get());
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	void schedulerIsInheritedByForkedVTFromTheRightFactory(Transport transport)
			throws InterruptedException, ExecutionException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory())) {
			final var expectedEventLoopScheduler = group.submit(() -> EventLoopScheduler.currentScheduler()).get();
			assertNotNull(expectedEventLoopScheduler);
			final var vThreadFactory = group.submit(group::vThreadFactory).get();
			var forkInheritedScheduler = new CompletableFuture<EventLoopScheduler>();
			vThreadFactory.newThread(() -> {
				try (var scope = StructuredTaskScope.open(allSuccessfulOrThrow(),
						cf -> cf.withThreadFactory(vThreadFactory))) {
					var task = scope.fork(() -> EventLoopScheduler.currentScheduler());
					scope.join();
					forkInheritedScheduler.complete(task.get());
				} catch (InterruptedException e) {
					forkInheritedScheduler.completeExceptionally(e);
				}
			}).start();
			assertEquals(expectedEventLoopScheduler, forkInheritedScheduler.get());
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	void schedulerIsNotInheritedByForkedVT(Transport transport) throws InterruptedException, ExecutionException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory())) {
			final var vThreadFactory = group.submit(group::vThreadFactory).get();
			var schedulerRef = new CompletableFuture<EventLoopScheduler>();
			vThreadFactory.newThread(() -> {
				try (var scope = StructuredTaskScope.open(allSuccessfulOrThrow())) {
					var task = scope.fork(() -> EventLoopScheduler.currentScheduler());
					scope.join();
					schedulerRef.complete(task.get());
				} catch (InterruptedException e) {
					schedulerRef.completeExceptionally(e);
				}
			}).start();
			assertNull(schedulerRef.get());
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	void virtualThreadCanMakeProgressEvenIfEventLoopIsClosed(Transport transport)
			throws InterruptedException, ExecutionException, BrokenBarrierException {
		assumeTrue(!EventLoopScheduler.WORK_STEALING_ENABLED, "work-stealing shutdown interaction needs investigation");
		var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory());
		final var barrier = new CyclicBarrier(2);
		final var vThreadFactory = group.submit(group::vThreadFactory).get();
		vThreadFactory.newThread(() -> {
			try {
				group.close();
				barrier.await();
			} catch (Throwable e) {
				// ignore
			}
		}).start();
		barrier.await();
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	void eventLoopSchedulerCanMakeProgressIfTheEventLoopIsBlocked(Transport transport)
			throws BrokenBarrierException, InterruptedException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory())) {
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
			allBlocked.await();
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	void testFairness(Transport transport) throws ExecutionException, InterruptedException {
		final long V_TASK_DURATION_NS = TimeUnit.MILLISECONDS.toNanos(100);
		int tasks = 4;
		var interleavingVirtualThreads = new AtomicBoolean(false);
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory())) {
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
		}
		assertFalse(interleavingVirtualThreads.get());
	}

	private static void spinWait(long nanos) {
		final long start = System.nanoTime();
		while ((System.nanoTime() - start) < nanos) {
			Thread.onSpinWait();
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	void testPlatformThreadSpawnsVirtualThreads(Transport transport) throws ExecutionException, InterruptedException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory());
				var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var scheduler = executor.submit(() -> EventLoopScheduler.currentScheduler());
			assertNull(scheduler.get());
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	@EnabledIfSystemProperty(named = "jdk.pollerMode", matches = "3")
	void testBlockingIO(Transport transport) throws IOException, InterruptedException, ExecutionException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory());
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
				var readCompleted = new CompletableFuture<Void>();
				group.execute(() -> {
					var readerThread = group.vThreadFactory().newThread(() -> {
						var eventLoopScheduler = EventLoopScheduler.currentScheduler();
						assertEquals(0, eventLoopScheduler.runnableCount());
						try {
							serverIn.read();
							readCompleted.complete(null);
						} catch (Throwable e) {
							readCompleted.completeExceptionally(e);
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

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	@EnabledIfSystemProperty(named = "jdk.pollerMode", matches = "3")
	void testShutdownSchedulerOnBlockingIO(Transport transport)
			throws IOException, InterruptedException, ExecutionException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory());
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
				var schedulerRef = new AtomicReference<EventLoopScheduler>();
				var firstReadCompleted = new CompletableFuture<Void>();
				var secondReadCompleted = new CompletableFuture<Void>();
				var readerVThreadPromise = new CompletableFuture<Thread>();
				group.execute(() -> {
					var readerThread = group.vThreadFactory().newThread(() -> {
						schedulerRef.lazySet(EventLoopScheduler.currentScheduler());
						var eventLoopScheduler = EventLoopScheduler.currentScheduler();
						assertEquals(0, eventLoopScheduler.runnableCount());
						try {
							serverIn.read();
							firstReadCompleted.complete(null);
							try {
								serverIn.read();
								secondReadCompleted.complete(null);
							} catch (Throwable e) {
								secondReadCompleted.completeExceptionally(e);
							}
						} catch (Throwable e) {
							firstReadCompleted.completeExceptionally(e);
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
				group.close();
				// unblock the client and expect the read to complete
				clientOut.write(1);
				firstReadCompleted.join();
				// it has to be waiting on read
				while (readerVThread.getState() != Thread.State.WAITING) {
					Thread.sleep(1);
				}
				clientOut.write(1);
				secondReadCompleted.join();
			}
		}
	}

	@ParameterizedTest(name = "{index} => transport={0}")
	@MethodSource("transportsAllowLocal")
	@EnabledIfSystemProperty(named = "jdk.pollerMode", matches = "3")
	void testShutdownSchedulerOnLongBlockingIO(Transport transport)
			throws IOException, InterruptedException, ExecutionException {
		int bytesToWrite = 16;
		try (var group = new VirtualIoPollerEventLoopGroup(1, transport.handlerFactory());
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
				var schedulerRef = new AtomicReference<EventLoopScheduler>();
				var readCompleted = new CompletableFuture<byte[]>();;
				var readerVThreadPromise = new CompletableFuture<Thread>();
				group.execute(() -> {
					var readerThread = group.vThreadFactory().newThread(() -> {
						schedulerRef.lazySet(EventLoopScheduler.currentScheduler());
						var eventLoopScheduler = EventLoopScheduler.currentScheduler();
						assertEquals(0, eventLoopScheduler.runnableCount());
						try {
							byte[] data = serverIn.readNBytes(bytesToWrite);
							readCompleted.complete(data);
						} catch (Throwable e) {
							readCompleted.completeExceptionally(e);
						}
					});
					readerVThreadPromise.complete(readerThread);
					readerThread.start();

				});
				var readerVThread = readerVThreadPromise.get();
				byte[] toWrite = new byte[bytesToWrite];
				int shutDownAt = toWrite.length / 2;
				for (int i = 0; i < toWrite.length; i++) {
					toWrite[i] = (byte) i;
				}
				// it has to be waiting on read
				while (readerVThread.getState() != Thread.State.WAITING) {
					Thread.sleep(1);
				}
				for (int i = 0; i < toWrite.length; i++) {
					Thread.sleep(50); // make sure the read is parked
					byte b = toWrite[i];
					// shutdown whilst the read is parked
					if (i == shutDownAt) {
						group.close();
					}
					clientOut.write(b);
				}
				assertArrayEquals(toWrite, readCompleted.join());
			}
		}
	}

	@Test
	void pollerSlotIsFreedAfterGroupClose() throws InterruptedException, ExecutionException {
		var first = new VirtualIoPollerEventLoopGroup(1, NioIoHandler.newFactory());
		var scheduler = first.submit(() -> EventLoopScheduler.currentScheduler()).get();
		first.close();
		assertFalse(scheduler.hasRegisteredPinnedPoller());
		try (var second = new VirtualIoPollerEventLoopGroup(1, NioIoHandler.newFactory())) {
			var response = second.submit(() -> "alive").get();
			assertEquals("alive", response);
		}
	}

	@Test
	void registerPinnedPollerCompletionStageCompletesOnTermination() throws InterruptedException, ExecutionException {
		var available = EventLoopSchedulerGroup.instance().availableSchedulers(1);
		assumeTrue(available != null, "no free scheduler slots available");
		var scheduler = available[0];
		var termination = scheduler.registerPinnedPoller(() -> {
		}, () -> {
		});
		termination.toCompletableFuture().join();
		assertFalse(scheduler.hasRegisteredPinnedPoller());
	}

	@Test
	void registerPinnedPollerCompletionStageCompletesOnException() throws InterruptedException, ExecutionException {
		var available = EventLoopSchedulerGroup.instance().availableSchedulers(1);
		assumeTrue(available != null, "no free scheduler slots available");
		var scheduler = available[0];
		var termination = scheduler.registerPinnedPoller(() -> {
		}, () -> {
			throw new RuntimeException("poller crashed");
		});
		termination.toCompletableFuture().join();
		assertFalse(scheduler.hasRegisteredPinnedPoller());
	}

	@Test
	void twoGroupsSharingPool() throws InterruptedException, ExecutionException {
		try (var groupA = new VirtualIoPollerEventLoopGroup(1, NioIoHandler.newFactory());
				var groupB = new VirtualIoPollerEventLoopGroup(1, NioIoHandler.newFactory())) {
			var schedulerA = groupA.submit(() -> EventLoopScheduler.currentScheduler()).get();
			var schedulerB = groupB.submit(() -> EventLoopScheduler.currentScheduler()).get();
			assertNotSame(schedulerA, schedulerB);
		}
	}

	@Test
	void vThreadFactoryReturnsAssignedSchedulerFactory() throws InterruptedException, ExecutionException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, NioIoHandler.newFactory())) {
			var scheduler = group.submit(() -> EventLoopScheduler.currentScheduler()).get();
			var factory = group.submit(() -> group.vThreadFactory()).get();
			assertSame(scheduler.virtualThreadFactory(), factory);
		}
	}

	@Test
	void vThreadFactoryMappingShouldReturnNullIfNoneIsFound() throws InterruptedException, ExecutionException {
		try (var otherGroup = new VirtualIoPollerEventLoopGroup(1, LocalIoHandler.newFactory());
				var group = new VirtualIoPollerEventLoopGroup(1, NioIoHandler.newFactory())) {
			var otherEventLoop = otherGroup.next();
			assertNull(group.vThreadFactoryOf(otherEventLoop));
		}
	}

	@Test
	void vThreadFactoryMappingShouldReturnTheRightOne() throws InterruptedException, ExecutionException {
		try (var group = new VirtualIoPollerEventLoopGroup(1, NioIoHandler.newFactory())) {
			var ioEventLoop = (IoEventLoop) group.iterator().next();
			var eventLoopScheduler = group.submit(() -> EventLoopScheduler.currentScheduler()).get();
			assertSame(eventLoopScheduler.virtualThreadFactory(), group.vThreadFactoryOf(ioEventLoop));
		}
	}

	@Test
	@Timeout(15)
	@EnabledIfSystemProperty(named = "io.netty.loom.workstealing.enabled", matches = "true")
	void runningSchedulerDiffersFromHomeWhenStolen() throws Exception {
		var group = EventLoopSchedulerGroup.instance();
		assumeTrue(group.size() >= 2);
		var schedulerA = group.scheduler(0);
		var schedulerB = group.scheduler(1);
		assumeTrue(!schedulerA.hasRegisteredPinnedPoller() && !schedulerB.hasRegisteredPinnedPoller());
		var factoryA = schedulerA.virtualThreadFactory();
		var factoryB = schedulerB.virtualThreadFactory();
		var blockerA = new AtomicBoolean(true);
		var blockerB = new AtomicBoolean(true);
		var startedA = new CountDownLatch(1);
		var startedB = new CountDownLatch(1);
		var runningRef = new CompletableFuture<EventLoopScheduler>();
		factoryA.newThread(() -> {
			startedA.countDown();
			while (blockerA.get()) {
				Thread.onSpinWait();
			}
		}).start();
		factoryB.newThread(() -> {
			startedB.countDown();
			while (blockerB.get()) {
				Thread.onSpinWait();
			}
		}).start();
		assertTrue(startedA.await(2, TimeUnit.SECONDS));
		assertTrue(startedB.await(2, TimeUnit.SECONDS));
		try {
			awaitUnresponsive(schedulerA);
			factoryA.newThread(() -> {
				runningRef.complete(EventLoopScheduler.currentThreadSchedulerContext().runningScheduler());
			}).start();
			blockerB.set(false);
			var running = runningRef.get(5, TimeUnit.SECONDS);
			assertSame(schedulerB, running, "stolen VT should report running on scheduler B");
		} finally {
			blockerA.set(false);
			blockerB.set(false);
		}
	}

	@Test
	@Timeout(15)
	@EnabledIfSystemProperty(named = "io.netty.loom.workstealing.enabled", matches = "true")
	void stolenVTChildRunsOnHomeScheduler() throws Exception {
		var group = EventLoopSchedulerGroup.instance();
		assumeTrue(group.size() >= 2);
		var schedulerA = group.scheduler(0);
		var schedulerB = group.scheduler(1);
		assumeTrue(!schedulerA.hasRegisteredPinnedPoller() && !schedulerB.hasRegisteredPinnedPoller());
		var factoryA = schedulerA.virtualThreadFactory();
		var factoryB = schedulerB.virtualThreadFactory();
		var blockerA = new AtomicBoolean(true);
		var blockerB = new AtomicBoolean(true);
		var startedA = new CountDownLatch(1);
		var startedB = new CountDownLatch(1);
		var childHome = new CompletableFuture<EventLoopScheduler>();
		factoryA.newThread(() -> {
			startedA.countDown();
			while (blockerA.get()) {
				Thread.onSpinWait();
			}
		}).start();
		factoryB.newThread(() -> {
			startedB.countDown();
			while (blockerB.get()) {
				Thread.onSpinWait();
			}
		}).start();
		assertTrue(startedA.await(2, TimeUnit.SECONDS));
		assertTrue(startedB.await(2, TimeUnit.SECONDS));
		try {
			awaitUnresponsive(schedulerA);
			factoryA.newThread(() -> {
				factoryA.newThread(() -> {
					childHome.complete(EventLoopScheduler.currentScheduler());
				}).start();
			}).start();
			blockerB.set(false);
			var child = childHome.get(5, TimeUnit.SECONDS);
			assertSame(schedulerA, child, "child VT must run on home scheduler A");
		} finally {
			blockerA.set(false);
			blockerB.set(false);
		}
	}

	@Test
	@Timeout(15)
	@EnabledIfSystemProperty(named = "io.netty.loom.workstealing.enabled", matches = "true")
	void pollerStealViaMaybeYield() throws Exception {
		try (var group = new VirtualIoPollerEventLoopGroup(2, NioIoHandler.newFactory())) {
			var pair = group.submit(() -> new Object[]{EventLoopScheduler.currentScheduler(), group.vThreadFactory()})
					.get();
			var schedulerA = (EventLoopScheduler) pair[0];
			var factoryA = (java.util.concurrent.ThreadFactory) pair[1];
			var blocker = new AtomicBoolean(true);
			var blockerStarted = new CountDownLatch(1);
			var runningRef = new CompletableFuture<EventLoopScheduler>();
			factoryA.newThread(() -> {
				blockerStarted.countDown();
				while (blocker.get()) {
					Thread.onSpinWait();
				}
			}).start();
			assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
			try {
				awaitUnresponsive(schedulerA);
				factoryA.newThread(() -> {
					runningRef.complete(EventLoopScheduler.currentThreadSchedulerContext().runningScheduler());
				}).start();
				var running = runningRef.get(5, TimeUnit.SECONDS);
				assertNotSame(schedulerA, running,
						"VT should be stolen by the idle poller carrier via maybeYield(false)");
			} finally {
				blocker.set(false);
			}
		}
	}

	@Test
	@Timeout(15)
	void workStealingDisabledNoInterference() throws Exception {
		assumeTrue(!EventLoopScheduler.WORK_STEALING_ENABLED);
		var group = EventLoopSchedulerGroup.instance();
		assumeTrue(group.size() >= 2);
		var schedulerA = group.scheduler(0);
		var schedulerB = group.scheduler(1);
		assumeTrue(!schedulerA.hasRegisteredPinnedPoller() && !schedulerB.hasRegisteredPinnedPoller());
		var factoryA = schedulerA.virtualThreadFactory();
		var blocker = new AtomicBoolean(true);
		var blockerStarted = new CountDownLatch(1);
		var targetHome = new CompletableFuture<EventLoopScheduler>();
		factoryA.newThread(() -> {
			blockerStarted.countDown();
			while (blocker.get()) {
				Thread.onSpinWait();
			}
		}).start();
		assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
		factoryA.newThread(() -> {
			targetHome.complete(EventLoopScheduler.currentScheduler());
		}).start();
		blocker.set(false);
		var home = targetHome.get(5, TimeUnit.SECONDS);
		assertSame(schedulerA, home, "with WS disabled, VT must run on home scheduler");
	}

	@Test
	@Timeout(15)
	@EnabledIfSystemProperty(named = "io.netty.loom.workstealing.enabled", matches = "true")
	void busyPollerWithIoDoesNotSteal() throws Exception {
		try (var group = new VirtualIoPollerEventLoopGroup(2, NioIoHandler.newFactory())) {
			var pair = group.submit(() -> new Object[]{EventLoopScheduler.currentScheduler(), group.vThreadFactory()})
					.get();
			var schedulerA = (EventLoopScheduler) pair[0];
			var factoryA = (java.util.concurrent.ThreadFactory) pair[1];
			var bootstrap = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
								@Override
								public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
									ctx.writeAndFlush(msg);
								}
							});
						}
					});
			Channel server = bootstrap.bind(0).sync().channel();
			int port = ((java.net.InetSocketAddress) server.localAddress()).getPort();
			var blocker = new AtomicBoolean(true);
			var blockerStarted = new CountDownLatch(1);
			var targetHome = new CompletableFuture<EventLoopScheduler>();
			factoryA.newThread(() -> {
				blockerStarted.countDown();
				while (blocker.get()) {
					Thread.onSpinWait();
				}
			}).start();
			assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
			try (var client = new Socket("localhost", port)) {
				var out = client.getOutputStream();
				var keepWriting = new AtomicBoolean(true);
				Thread.ofPlatform().daemon(true).start(() -> {
					try {
						while (keepWriting.get()) {
							out.write(1);
							out.flush();
						}
					} catch (IOException _) {
					}
				});
				awaitUnresponsive(schedulerA);
				factoryA.newThread(() -> {
					targetHome.complete(EventLoopScheduler.currentScheduler());
				}).start();
				blocker.set(false);
				var home = targetHome.get(5, TimeUnit.SECONDS);
				assertSame(schedulerA, home,
						"poller with I/O should not steal — VT runs on home A after blocker releases");
				keepWriting.set(false);
			}
			server.close().sync();
		}
	}

	@Test
	@Timeout(15)
	@EnabledIfSystemProperty(named = "io.netty.loom.workstealing.enabled", matches = "true")
	void carrierWithDescheduledPollerCanSteal() throws Exception {
		var group = EventLoopSchedulerGroup.instance();
		assumeTrue(group.size() >= 2);
		var schedulerA = group.scheduler(0);
		var schedulerB = group.scheduler(1);
		assumeTrue(!schedulerA.hasRegisteredPinnedPoller() && !schedulerB.hasRegisteredPinnedPoller());
		var spinnerA = new AtomicBoolean(true);
		var spinnerB = new AtomicBoolean(true);
		var pollerLatch = new CountDownLatch(1);
		var pollersStarted = new CountDownLatch(2);
		var vtRan = new CompletableFuture<EventLoopScheduler>();
		var pollerATermination = schedulerA.registerPinnedPoller(() -> {
		}, () -> {
			pollersStarted.countDown();
			while (spinnerA.get()) {
				Thread.onSpinWait();
			}
			try {
				pollerLatch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		var pollerBTermination = schedulerB.registerPinnedPoller(() -> {
		}, () -> {
			pollersStarted.countDown();
			while (spinnerB.get()) {
				Thread.onSpinWait();
			}
		});
		try {
			assertTrue(pollersStarted.await(2, TimeUnit.SECONDS));
			awaitUnresponsive(schedulerB);
			schedulerB.virtualThreadFactory().newThread(() -> {
				vtRan.complete(EventLoopScheduler.currentThreadSchedulerContext().runningScheduler());
			}).start();
			spinnerA.set(false);
			var running = vtRan.get(5, TimeUnit.SECONDS);
			assertSame(schedulerA, running, "carrier A (with descheduled poller) should steal VT from overloaded B");
		} finally {
			spinnerA.set(false);
			spinnerB.set(false);
			pollerLatch.countDown();
			pollerATermination.toCompletableFuture().get(5, TimeUnit.SECONDS);
			pollerBTermination.toCompletableFuture().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	@Timeout(15)
	@EnabledIfSystemProperty(named = "io.netty.loom.workstealing.enabled", matches = "true")
	void parkedCarrierWithoutPollerIsWokenToSteal() throws Exception {
		var group = EventLoopSchedulerGroup.instance();
		assumeTrue(group.size() >= 2);
		var schedulerA = group.scheduler(0);
		var schedulerB = group.scheduler(1);
		assumeTrue(!schedulerA.hasRegisteredPinnedPoller() && !schedulerB.hasRegisteredPinnedPoller());
		var parkedLatch = new CountDownLatch(1);
		var spinnerStarted = new CountDownLatch(1);
		var spinnerB = new AtomicBoolean(true);
		var vtRan = new CompletableFuture<EventLoopScheduler>();
		schedulerA.virtualThreadFactory().newThread(() -> {
			parkedLatch.countDown();
		}).start();
		assertTrue(parkedLatch.await(2, TimeUnit.SECONDS));
		while (schedulerA.carrierThread().getState() != Thread.State.WAITING) {
			Thread.onSpinWait();
		}
		schedulerB.virtualThreadFactory().newThread(() -> {
			spinnerStarted.countDown();
			while (spinnerB.get()) {
				Thread.onSpinWait();
			}
		}).start();
		assertTrue(spinnerStarted.await(2, TimeUnit.SECONDS));
		try {
			awaitUnresponsive(schedulerB);
			schedulerB.virtualThreadFactory().newThread(() -> {
				vtRan.complete(EventLoopScheduler.currentThreadSchedulerContext().runningScheduler());
			}).start();
			var running = vtRan.get(5, TimeUnit.SECONDS);
			assertSame(schedulerA, running, "parked carrier A should be woken to steal VT from unresponsive B");
		} finally {
			spinnerB.set(false);
		}
	}

	@Test
	@Timeout(15)
	@EnabledIfSystemProperty(named = "io.netty.loom.workstealing.enabled", matches = "true")
	void parkedCarrierWithDescheduledPollerIsWokenToSteal() throws Exception {
		var group = EventLoopSchedulerGroup.instance();
		assumeTrue(group.size() >= 2);
		var schedulerA = group.scheduler(0);
		var schedulerB = group.scheduler(1);
		assumeTrue(!schedulerA.hasRegisteredPinnedPoller() && !schedulerB.hasRegisteredPinnedPoller());
		var pollerLatch = new CountDownLatch(1);
		var pollerStarted = new CountDownLatch(1);
		var spinnerStarted = new CountDownLatch(1);
		var spinnerB = new AtomicBoolean(true);
		var vtRan = new CompletableFuture<EventLoopScheduler>();
		var pollerTermination = schedulerA.registerPinnedPoller(() -> {
		}, () -> {
			pollerStarted.countDown();
			try {
				pollerLatch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			assertTrue(pollerStarted.await(2, TimeUnit.SECONDS));
			while (schedulerA.carrierThread().getState() != Thread.State.WAITING) {
				Thread.onSpinWait();
			}
			schedulerB.virtualThreadFactory().newThread(() -> {
				spinnerStarted.countDown();
				while (spinnerB.get()) {
					Thread.onSpinWait();
				}
			}).start();
			assertTrue(spinnerStarted.await(2, TimeUnit.SECONDS));
			awaitUnresponsive(schedulerB);
			schedulerB.virtualThreadFactory().newThread(() -> {
				vtRan.complete(EventLoopScheduler.currentThreadSchedulerContext().runningScheduler());
			}).start();
			var running = vtRan.get(5, TimeUnit.SECONDS);
			assertSame(schedulerA, running, "parked carrier A with descheduled poller should be woken to steal from B");
		} finally {
			spinnerB.set(false);
			pollerLatch.countDown();
			pollerTermination.toCompletableFuture().get(5, TimeUnit.SECONDS);
		}
	}

	private static void awaitUnresponsive(EventLoopScheduler scheduler) throws InterruptedException {
		long thresholdMs = Long.getLong("io.netty.loom.workstealing.unresponsive.ms", 200);
		Thread.sleep(thresholdMs + 5);
		assertTrue(scheduler.isUnresponsive(System.nanoTime()), "scheduler should be unresponsive after threshold");
	}
}
