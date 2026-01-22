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
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.IoEventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Pure Netty non-blocking HTTP handler.
 * <p>
 * Each handler instance owns exactly ONE HTTP client connection that runs on
 * the same event loop as the server connection it handles. This ensures
 * complete thread affinity - all operations for a given server connection
 * happen on a single event loop thread.
 * <p>
 * Uses pure Netty async API - no external reactive libraries.
 */
public class ReactiveHandoffHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final ByteBuf HEALTH_RESPONSE = Unpooled
			.unreleasableBuffer(Unpooled.copiedBuffer("OK", CharsetUtil.UTF_8));

	private final String mockUrl;
	private final URI parsedUri;
	private final Class<? extends SocketChannel> channelClass;
	private final boolean noTimeout;

	// This handler owns exactly ONE client channel
	private Channel clientChannel;

	// Callback for the current pending request (single-threaded, no need for
	// atomic)
	private ResponseCallback pendingCallback;

	public ReactiveHandoffHandler(String mockUrl, boolean noTimeout, Class<? extends SocketChannel> channelClass) {
		this.mockUrl = mockUrl;
		this.channelClass = channelClass;
		this.noTimeout = noTimeout;
		try {
			this.parsedUri = new URI(mockUrl);
		} catch (Exception e) {
			throw new RuntimeException("Invalid mock URL: " + mockUrl, e);
		}
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		// Get the event loop for this server channel
		IoEventLoop eventLoop = (IoEventLoop) ctx.channel().eventLoop();

		// Assert we're on the event loop
		if (!eventLoop.inEventLoop()) {
			throw new IllegalStateException("channelActive not executing on expected event loop");
		}

		String host = parsedUri.getHost();
		int port = parsedUri.getPort() > 0 ? parsedUri.getPort() : 80;

		// Create a single client connection bound to THIS event loop
		Bootstrap bootstrap = new Bootstrap().group(eventLoop) // Critical: use the SAME event loop
				.channel(channelClass) // Use same channel type as server
				.option(ChannelOption.SO_KEEPALIVE, true).option(ChannelOption.TCP_NODELAY, true)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, noTimeout ? 0 : 30000)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ChannelPipeline p = ch.pipeline();
						// Add read timeout handler if timeout is enabled
						if (!noTimeout) {
							p.addLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS));
						}
						p.addLast(new HttpClientCodec());
						p.addLast(new HttpObjectAggregator(65536));
						// Add permanent response handler
						p.addLast(new ClientResponseHandler());
					}
				});

		// Connect and store the client channel - this handler owns it exclusively
		// We need to wait for the connection to complete before accepting requests
		ChannelFuture connectFuture = bootstrap.connect(new InetSocketAddress(host, port));

		// Disable auto-read until client connection is established
		ctx.channel().config().setAutoRead(false);

		connectFuture.addListener((ChannelFuture future) -> {
			if (future.isSuccess()) {
				this.clientChannel = future.channel();
				// Re-enable auto-read now that client is connected
				ctx.channel().config().setAutoRead(true);
			} else {
				System.err.println("Failed to connect client channel: " + future.cause().getMessage());
				ctx.close();
			}
		});

		super.channelActive(ctx);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
		String uri = request.uri();
		boolean keepAlive = HttpUtil.isKeepAlive(request);
		IoEventLoop eventLoop = (IoEventLoop) ctx.channel().eventLoop();

		// Assert we're on the event loop
		if (!eventLoop.inEventLoop()) {
			throw new IllegalStateException("channelRead0 not executing on expected event loop");
		}

		if (uri.equals("/health")) {
			sendResponse(ctx, HEALTH_RESPONSE.duplicate(), HttpHeaderValues.TEXT_PLAIN, keepAlive);
			return;
		}

		if (uri.equals("/") || uri.startsWith("/fruits")) {
			// Process asynchronously using pure Netty
			processAsync(ctx, eventLoop, keepAlive);
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

	private void processAsync(ChannelHandlerContext serverCtx, IoEventLoop eventLoop, boolean keepAlive) {
		if (clientChannel == null || !clientChannel.isActive()) {
			sendErrorResponse(serverCtx, new IllegalStateException("Client channel not ready"));
			return;
		}

		// Check no request is in flight
		if (pendingCallback != null) {
			sendErrorResponse(serverCtx, new IllegalStateException("Request already in flight"));
			return;
		}
		pendingCallback = new ResponseCallback(serverCtx, eventLoop, keepAlive);

		// Create HTTP request
		String path = parsedUri.getRawPath();
		FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
				path != null ? path : "/");
		httpRequest.headers().set(HttpHeaderNames.HOST, parsedUri.getHost());
		httpRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

		// Send the HTTP request (use void promise - any errors will trigger
		// exceptionCaught)
		clientChannel.writeAndFlush(httpRequest, clientChannel.voidPromise());
	}

	private void sendResponse(ChannelHandlerContext ctx, ByteBuf content, AsciiString contentType, boolean keepAlive) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
		response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

		if (keepAlive) {
			response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
			ctx.writeAndFlush(response);
		} else {
			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		}
	}

	private void sendErrorResponse(ChannelHandlerContext ctx, Throwable error) {
		ByteBuf content = Unpooled.copiedBuffer("{\"error\":\"" + error.getMessage() + "\"}", CharsetUtil.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				HttpResponseStatus.INTERNAL_SERVER_ERROR, content);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
		response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
		ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();
		ctx.close();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		// Close our owned client channel
		if (clientChannel != null && clientChannel.isActive()) {
			clientChannel.close();
		}
		super.channelInactive(ctx);
	}

	/**
	 * Permanent handler in the client pipeline that dispatches responses to
	 * callbacks.
	 */
	private class ClientResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
		@Override
		protected void channelRead0(ChannelHandlerContext clientCtx, FullHttpResponse httpResponse) {
			// Get the pending callback
			ResponseCallback callback = pendingCallback;
			pendingCallback = null;
			if (callback != null) {
				callback.onResponse(httpResponse);
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext clientCtx, Throwable cause) {
			// Get the pending callback
			ResponseCallback callback = pendingCallback;
			pendingCallback = null;
			if (callback != null) {
				callback.onError(cause);
			}
		}
	}

	/**
	 * Callback that processes HTTP response and sends result back to server client.
	 */
	private static class ResponseCallback {
		private final ChannelHandlerContext serverCtx;
		private final IoEventLoop eventLoop;
		private final boolean keepAlive;

		ResponseCallback(ChannelHandlerContext serverCtx, IoEventLoop eventLoop, boolean keepAlive) {
			this.serverCtx = serverCtx;
			this.eventLoop = eventLoop;
			this.keepAlive = keepAlive;
		}

		void onResponse(FullHttpResponse httpResponse) {
			// Assert we're on the event loop
			if (!eventLoop.inEventLoop()) {
				throw new IllegalStateException("Response callback not executing on expected event loop");
			}

			try {
				// Parse JSON directly from ByteBuf's InputStream (no intermediate byte[]
				// allocation)
				ByteBuf content = httpResponse.content();
				FruitsResponse fruitsResponse;
				try (var is = new io.netty.buffer.ByteBufInputStream(content)) {
					fruitsResponse = OBJECT_MAPPER.readValue((java.io.InputStream) is, FruitsResponse.class);
				}

				// Re-encode to JSON bytes
				byte[] responseBytes = OBJECT_MAPPER.writeValueAsBytes(fruitsResponse);

				// Send response back to server client
				ByteBuf responseContent = Unpooled.wrappedBuffer(responseBytes);
				FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
						responseContent);
				response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
				response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseContent.readableBytes());

				if (keepAlive) {
					response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
					serverCtx.writeAndFlush(response);
				} else {
					serverCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
				}
			} catch (Exception e) {
				onError(e);
			}
		}

		void onError(Throwable error) {
			// Assert we're on the event loop
			if (!eventLoop.inEventLoop()) {
				throw new IllegalStateException("Error callback not executing on expected event loop");
			}

			System.err.println("Error processing request: " + error.getMessage());
			error.printStackTrace();

			ByteBuf content = Unpooled.copiedBuffer("{\"error\":\"" + error.getMessage() + "\"}", CharsetUtil.UTF_8);
			FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.INTERNAL_SERVER_ERROR, content);
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
			response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
			serverCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		}
	}
}
