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
package io.netty.loom.example;

import static java.util.concurrent.StructuredTaskScope.Joiner.allSuccessfulOrThrow;

import java.util.concurrent.StructuredTaskScope;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.loom.VirtualIoNativePollerEventLoopGroup;
import io.netty.loom.scheduler.EventLoopScheduler;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * HTTP server on Netty with epoll pinned pollers. Demonstrates blocking virtual
 * thread handlers with carrier affinity and structured concurrency.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>{@code GET /} — blocking handler (50ms sleep), returns VT + carrier
 * info</li>
 * <li>{@code GET /parallel} — {@link StructuredTaskScope} fork/join, both tasks
 * on the same carrier</li>
 * <li>anything else — 404</li>
 * </ul>
 */
public class EchoServer {

	public static void main(String[] args) throws Exception {
		try (var group = new VirtualIoNativePollerEventLoopGroup(EpollIoHandler.newFactory())) {
			var bootstrap = new ServerBootstrap().group(group).channel(EpollServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(new HttpServerCodec());
							ch.pipeline().addLast(new HttpObjectAggregator(65536));
							ch.pipeline().addLast(new RequestHandler(group));
						}
					});

			Channel ch = bootstrap.bind(8080).sync().channel();
			System.out.println("Echo server started on http://localhost:8080/");
			System.out.println("  GET /         — blocking handler with carrier info");
			System.out.println("  GET /parallel — structured concurrency on same carrier");
			ch.closeFuture().sync();
		}
	}

	static class RequestHandler extends ChannelInboundHandlerAdapter {

		private final VirtualIoNativePollerEventLoopGroup group;

		RequestHandler(VirtualIoNativePollerEventLoopGroup group) {
			this.group = group;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (msg instanceof FullHttpRequest request) {
				try {
					group.vThreadFactory().newThread(() -> handleRequest(ctx, request)).start();
				} catch (Throwable t) {
					ReferenceCountUtil.release(request);
					throw t;
				}
			} else {
				ReferenceCountUtil.release(msg);
			}
		}

		private static void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
			try {
				String uri = request.uri();
				String body = switch (uri) {
					case "/" -> handleRoot();
					case "/parallel" -> handleParallel();
					default -> null;
				};

				if (body == null) {
					sendResponse(ctx, request, HttpResponseStatus.NOT_FOUND, "Not Found\n");
				} else {
					sendResponse(ctx, request, HttpResponseStatus.OK, body);
				}
			} catch (Exception e) {
				sendResponse(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.toString() + "\n");
			} finally {
				ReferenceCountUtil.release(request);
			}
		}

		private static String handleRoot() throws InterruptedException {
			var scheduler = EventLoopScheduler.currentScheduler();
			Thread.sleep(50);
			return "HELLO from " + Thread.currentThread() + " on carrier " + scheduler.carrierThread().getName()
					+ " (scheduler " + scheduler.id() + ")\n";
		}

		private static String handleParallel() throws Exception {
			var scheduler = EventLoopScheduler.currentScheduler();
			var factory = scheduler.virtualThreadFactory();
			try (var scope = StructuredTaskScope.open(allSuccessfulOrThrow(), cfg -> cfg.withThreadFactory(factory))) {
				var taskA = scope.fork(() -> {
					Thread.sleep(50);
					return "A from " + Thread.currentThread();
				});
				var taskB = scope.fork(() -> {
					Thread.sleep(50);
					return "B from " + Thread.currentThread();
				});
				scope.join();
				return taskA.get() + " | " + taskB.get() + "\n";
			}
		}

		private static void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
				String body) {
			var content = ctx.alloc().buffer();
			content.writeCharSequence(body, CharsetUtil.US_ASCII);
			var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
					.set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
			if (HttpUtil.isKeepAlive(request)) {
				response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
			}
			ctx.writeAndFlush(response);
			if (!HttpUtil.isKeepAlive(request)) {
				ctx.close();
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			cause.printStackTrace();
			ctx.close();
		}
	}
}
