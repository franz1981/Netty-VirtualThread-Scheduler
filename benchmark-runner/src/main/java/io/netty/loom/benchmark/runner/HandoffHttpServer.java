/*
 * Copyright 2026 The Netty VirtualThread Scheduler Project
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
package io.netty.loom.benchmark.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.loom.VirtualMultithreadIoEventLoopGroup;
import io.netty.loom.VirtualMultithreadManualIoEventLoopGroup;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * HTTP server that demonstrates handoff benchmark patterns.
 * <p>
 * Processing flow: 1. Receive HTTP request on Netty event loop 2. Hand off to
 * virtual thread (optionally using custom scheduler) 3. Make blocking HTTP call
 * to mock server using JDK HttpClient 4. Parse JSON response with Jackson into
 * Fruit objects 5. Re-encode to JSON and write back to client via event loop
 * <p>
 * Usage: java -cp benchmark-runner.jar
 * io.netty.loom.benchmark.runner.HandoffHttpServer \ --port 8081 \ --mock-url
 * --io epoll
 */
public class HandoffHttpServer {

	public enum IO {
		EPOLL, NIO, IO_URING
	}

	public enum Mode {
		NON_VIRTUAL_NETTY, REACTIVE, VIRTUAL_NETTY, NETTY_SCHEDULER
	}

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final ByteBuf HEALTH_RESPONSE = Unpooled
			.unreleasableBuffer(Unpooled.copiedBuffer("OK", CharsetUtil.UTF_8));

	// Same JSON as MockHttpServer.CACHED_RESPONSE — used in mockless mode
	private static final byte[] CACHED_JSON_BYTES = """
			{
			  "fruits": [
			    {"name": "Apple", "color": "Red", "price": 1.20},
			    {"name": "Banana", "color": "Yellow", "price": 0.50},
			    {"name": "Orange", "color": "Orange", "price": 0.80},
			    {"name": "Grape", "color": "Purple", "price": 2.00},
			    {"name": "Mango", "color": "Yellow", "price": 1.50},
			    {"name": "Strawberry", "color": "Red", "price": 3.00},
			    {"name": "Blueberry", "color": "Blue", "price": 4.00},
			    {"name": "Pineapple", "color": "Yellow", "price": 2.50},
			    {"name": "Watermelon", "color": "Green", "price": 5.00},
			    {"name": "Kiwi", "color": "Brown", "price": 1.00}
			  ]
			}
			""".getBytes(java.nio.charset.StandardCharsets.UTF_8);

	private final int port;
	private final String mockUrl;
	private final int threads;
	private final IO io;
	private final boolean silent;
	private final boolean mockless;
	private final Mode mode;

	private MultiThreadIoEventLoopGroup workerGroup;
	private Channel serverChannel;
	private Supplier<ThreadFactory> threadFactorySupplier;

	public HandoffHttpServer(int port, String mockUrl, int threads, IO io, boolean silent, boolean mockless,
			Mode mode) {
		this.port = port;
		this.mockUrl = mockUrl;
		this.threads = threads;
		this.io = io;
		this.silent = silent;
		this.mockless = mockless;
		this.mode = mode;
	}

	public void start() throws InterruptedException {
		var ioHandlerFactory = switch (io) {
			case NIO -> NioIoHandler.newFactory();
			case EPOLL -> EpollIoHandler.newFactory();
			case IO_URING -> IoUringIoHandler.newFactory();
		};

		final Class<? extends ServerSocketChannel> serverChannelClass;
		final Class<? extends io.netty.channel.socket.SocketChannel> clientChannelClass;

		switch (mode) {
			case VIRTUAL_NETTY -> {
				var group = new VirtualMultithreadManualIoEventLoopGroup(threads, NioIoHandler.newFactory());
				workerGroup = group;
				var defaultFactory = Thread.ofVirtual().factory();
				threadFactorySupplier = () -> defaultFactory;
				serverChannelClass = NioServerSocketChannel.class;
				clientChannelClass = io.netty.channel.socket.nio.NioSocketChannel.class;
			}
			case NETTY_SCHEDULER -> {
				serverChannelClass = switch (io) {
					case NIO -> NioServerSocketChannel.class;
					case EPOLL -> EpollServerSocketChannel.class;
					case IO_URING -> IoUringServerSocketChannel.class;
				};
				clientChannelClass = switch (io) {
					case NIO -> io.netty.channel.socket.nio.NioSocketChannel.class;
					case EPOLL -> io.netty.channel.epoll.EpollSocketChannel.class;
					case IO_URING -> io.netty.channel.uring.IoUringSocketChannel.class;
				};
				var group = new VirtualMultithreadIoEventLoopGroup(threads, ioHandlerFactory);
				threadFactorySupplier = group::vThreadFactory;
				workerGroup = group;
			}
			default -> {
				serverChannelClass = switch (io) {
					case NIO -> NioServerSocketChannel.class;
					case EPOLL -> EpollServerSocketChannel.class;
					case IO_URING -> IoUringServerSocketChannel.class;
				};
				clientChannelClass = switch (io) {
					case NIO -> io.netty.channel.socket.nio.NioSocketChannel.class;
					case EPOLL -> io.netty.channel.epoll.EpollSocketChannel.class;
					case IO_URING -> io.netty.channel.uring.IoUringSocketChannel.class;
				};
				workerGroup = new MultiThreadIoEventLoopGroup(threads, ioHandlerFactory);
				var defaultFactory = Thread.ofVirtual().factory();
				threadFactorySupplier = () -> defaultFactory;
			}
		}
		ServerBootstrap b = new ServerBootstrap();
		b.group(workerGroup).channel(serverChannelClass).childOption(ChannelOption.TCP_NODELAY, true)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ChannelPipeline p = ch.pipeline();
						p.addLast(new HttpServerCodec());
						p.addLast(new HttpObjectAggregator(65536));
						if (mode == Mode.REACTIVE) {
							p.addLast(new ReactiveHandoffHandler(mockUrl, clientChannelClass));
						} else {
							p.addLast(new HandoffHandler());
						}
					}
				});

		serverChannel = b.bind(port).sync().channel();
		if (!silent) {
			System.out.printf("Handoff HTTP Server started on port %d%n", port);
			System.out.printf("  Mode: %s%n", switch (mode) {
				case NON_VIRTUAL_NETTY -> "Non-Virtual Netty (platform IO + VT blocking)";
				case REACTIVE -> "Reactive (pure async, no VTs)";
				case VIRTUAL_NETTY -> "Virtual Netty (IO loops as VTs on ForkJoinPool)";
				case NETTY_SCHEDULER -> "Netty Scheduler (platform IO + Netty VT scheduler)";
			});
			System.out.printf("  Mockless: %s%n", mockless);
			if (!mockless) {
				System.out.printf("  Mock URL: %s%n", mockUrl);
			}
			System.out.printf("  Threads: %d%n", threads);
			System.out.printf("  I/O: %s%n", mode == Mode.VIRTUAL_NETTY ? "NIO (forced)" : io);
		}
	}

	public void stop() {
		if (serverChannel != null) {
			serverChannel.close();
		}
		if (workerGroup != null) {
			workerGroup.shutdownGracefully();
		}
		if (!silent) {
			System.out.println("Server stopped");
		}
	}

	public void awaitTermination() throws InterruptedException {
		serverChannel.closeFuture().sync();
	}

	private static final RequestConfig NO_TIMEOUT_HTTP_REQUEST_CONFIG = RequestConfig.custom()
			.setResponseTimeout(Timeout.DISABLED) // infinite block
			.build();

	private class HandoffHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

		private final CloseableHttpClient httpClient;
		private ExecutorService orderedExecutorService;

		HandoffHandler() {
			if (mockless) {
				httpClient = null;
			} else {
				ConnectionKeepAliveStrategy keepAliveStrategy = (HttpResponse response,
						HttpContext context) -> TimeValue.NEG_ONE_MILLISECOND;
				BasicHttpClientConnectionManager cm = new BasicHttpClientConnectionManager();
				httpClient = HttpClientBuilder.create().setConnectionManager(cm)
						.setDefaultRequestConfig(NO_TIMEOUT_HTTP_REQUEST_CONFIG).setConnectionManagerShared(false)
						.setKeepAliveStrategy(keepAliveStrategy).build();
			}
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			orderedExecutorService = Executors.newSingleThreadExecutor(threadFactorySupplier.get());
			super.channelActive(ctx);
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
			String uri = request.uri();
			boolean keepAlive = HttpUtil.isKeepAlive(request);
			IoEventLoop eventLoop = (IoEventLoop) ctx.channel().eventLoop();

			if (uri.equals("/health")) {
				sendResponse(ctx, HEALTH_RESPONSE.duplicate(), HttpHeaderValues.TEXT_PLAIN, keepAlive);
				return;
			}

			if (uri.equals("/") || uri.startsWith("/fruits")) {
				// Hand off to virtual thread for processing
				if (mockless) {
					orderedExecutorService.execute(() -> {
						doMocklessProcessing(ctx, eventLoop, keepAlive);
					});
				} else {
					orderedExecutorService.execute(() -> {
						doBlockingProcessing(ctx, eventLoop, keepAlive);
					});
				}
				return;
			}

			// 404 for unknown paths
			ByteBuf content = Unpooled.copiedBuffer("Not Found", CharsetUtil.UTF_8);
			FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
					content);
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
			response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		}

		private void doBlockingProcessing(ChannelHandlerContext ctx, IoEventLoop eventLoop, boolean keepAlive) {
			try {
				// 1. Make blocking HTTP call to mock server
				HttpGet httpGet = new HttpGet(mockUrl);
				try (CloseableHttpResponse httpResponse = httpClient.execute(httpGet)) {
					HttpEntity entity = httpResponse.getEntity();
					if (entity == null)
						throw new IOException("No response entity");
					try (InputStream is = entity.getContent()) {
						// 2. Parse JSON into Fruit objects using Jackson
						FruitsResponse fruitsResponse = OBJECT_MAPPER.readValue(is, FruitsResponse.class);
						// 3. Re-encode to JSON bytes
						byte[] responseBytes = OBJECT_MAPPER.writeValueAsBytes(fruitsResponse);
						// 4. Post write back to event loop (non-blocking)
						eventLoop.execute(() -> {
							ByteBuf content = Unpooled.wrappedBuffer(responseBytes);
							sendResponse(ctx, content, HttpHeaderValues.APPLICATION_JSON, keepAlive);
						});
					}
					EntityUtils.consumeQuietly(entity);
				}
			} catch (Throwable e) {
				eventLoop.execute(() -> {
					ByteBuf content = Unpooled.copiedBuffer("{\"error\":\"" + e.getMessage() + "\"}",
							CharsetUtil.UTF_8);
					FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
							HttpResponseStatus.INTERNAL_SERVER_ERROR, content);
					response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
					response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
					ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
				});
			}
		}

		private void doMocklessProcessing(ChannelHandlerContext ctx, IoEventLoop eventLoop, boolean keepAlive) {
			try {
				// Same Jackson work as doBlockingProcessing, without the HTTP call
				FruitsResponse fruitsResponse = OBJECT_MAPPER.readValue(CACHED_JSON_BYTES, FruitsResponse.class);
				byte[] responseBytes = OBJECT_MAPPER.writeValueAsBytes(fruitsResponse);
				eventLoop.execute(() -> {
					ByteBuf content = Unpooled.wrappedBuffer(responseBytes);
					sendResponse(ctx, content, HttpHeaderValues.APPLICATION_JSON, keepAlive);
				});
			} catch (Throwable e) {
				eventLoop.execute(() -> {
					ByteBuf content = Unpooled.copiedBuffer("{\"error\":\"" + e.getMessage() + "\"}",
							CharsetUtil.UTF_8);
					FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
							HttpResponseStatus.INTERNAL_SERVER_ERROR, content);
					response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
					response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
					ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
				});
			}
		}

		private void sendResponse(ChannelHandlerContext ctx, ByteBuf content, AsciiString contentType,
				boolean keepAlive) {
			FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
					content);
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
			response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

			if (keepAlive) {
				response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
				ctx.writeAndFlush(response);
			} else {
				ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ctx.close();
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			try {
				orderedExecutorService.execute(() -> {
					try {
						if (httpClient != null) {
							httpClient.close();
						}
					} catch (IOException e) {
					} finally {
						orderedExecutorService.shutdown();
					}
				});
			} finally {
				super.channelInactive(ctx);
			}
		}
	}

	public static void main(String[] args) throws InterruptedException {
		int port = 8081;
		String mockUrl = "http://localhost:8080/fruits";
		int threads = 1;
		IO io = IO.EPOLL;
		boolean silent = false;
		boolean mockless = false;
		Mode mode = Mode.NON_VIRTUAL_NETTY;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--port" -> port = Integer.parseInt(args[++i]);
				case "--mock-url" -> mockUrl = args[++i];
				case "--threads" -> threads = Integer.parseInt(args[++i]);
				case "--io" -> io = IO.valueOf(args[++i].toUpperCase());
				case "--silent" -> silent = true;
				case "--mockless" -> mockless = true;
				case "--mode" -> mode = Mode.valueOf(args[++i].toUpperCase());
				case "--help" -> {
					printUsage();
					return;
				}
			}
		}

		HandoffHttpServer server = new HandoffHttpServer(port, mockUrl, threads, io, silent, mockless, mode);
		server.start();

		// Shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

		server.awaitTermination();
	}

	private static void printUsage() {
		System.out.println("""
				Usage: java -cp benchmark-runner.jar io.netty.loom.benchmark.runner.HandoffHttpServer [options]

				Options:
				  --port <port>                  HTTP port (default: 8081)
				  --mock-url <url>               Mock server URL (default: http://localhost:8080/fruits)
				  --threads <n>                  Number of event loop threads (default: 1)
				  --io <epoll|nio|io_uring>      I/O type (default: epoll)
				  --mockless                     Skip mock server; do Jackson work inline (default: off)
				  --mode <mode>                  Server mode (default: virtual_thread)
				  --silent                       Suppress output messages
				  --help                         Show this help

				Modes:
				  NON_VIRTUAL_NETTY (default): Platform thread IO + virtual thread blocking work
				  REACTIVE: Pure Netty async, no virtual threads
				  VIRTUAL_NETTY: Netty IO event loops as VTs on ForkJoinPool
				  NETTY_SCHEDULER: Platform thread IO + Netty custom VT scheduler
				""");
	}
}
