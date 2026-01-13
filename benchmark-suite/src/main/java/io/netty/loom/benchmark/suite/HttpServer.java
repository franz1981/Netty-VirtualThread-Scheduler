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
package io.netty.loom.benchmark.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.loom.VirtualMultithreadIoEventLoopGroup;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

/**
 * HTTP frontend server that offloads request processing to virtual threads.
 * Supports both custom NettyScheduler and default virtual thread scheduler.
 */
public class HttpServer {

	private static final int DEFAULT_PORT = 8080;
	private static final int DEFAULT_EVENT_LOOPS = 2;
	private static final String DEFAULT_BACKEND_HOST = "localhost";
	private static final int DEFAULT_BACKEND_PORT = 9090;
	private static final String DEFAULT_SCHEDULER = "custom"; // or "default"

	private static final AttributeKey<BinaryClient> BINARY_CLIENT_KEY = AttributeKey.valueOf("binaryClient");

	public static void main(String[] args) throws Exception {
		int port = getIntProperty("HTTP_PORT", DEFAULT_PORT);
		int eventLoops = getIntProperty("EVENT_LOOPS", DEFAULT_EVENT_LOOPS);
		String backendHost = System.getProperty("BACKEND_HOST", System.getenv().getOrDefault("BACKEND_HOST", DEFAULT_BACKEND_HOST));
		int backendPort = getIntProperty("BACKEND_PORT", DEFAULT_BACKEND_PORT);
		String scheduler = System.getProperty("SCHEDULER", System.getenv().getOrDefault("SCHEDULER", DEFAULT_SCHEDULER));

		System.out.println("Starting HTTP Server on port " + port);
		System.out.println("Event loops: " + eventLoops);
		System.out.println("Backend: " + backendHost + ":" + backendPort);
		System.out.println("Scheduler: " + scheduler);

		boolean useCustomScheduler = "custom".equalsIgnoreCase(scheduler);

		EventLoopGroup group;
		ThreadFactory virtualThreadFactory;

		if (useCustomScheduler) {
			VirtualMultithreadIoEventLoopGroup customGroup = new VirtualMultithreadIoEventLoopGroup(eventLoops,
					NioIoHandler.newFactory());
			group = customGroup;
			virtualThreadFactory = customGroup.vThreadFactory();
			System.out.println("Using custom NettyScheduler");
		} else {
			group = new MultiThreadIoEventLoopGroup(eventLoops, NioIoHandler.newFactory());
			virtualThreadFactory = Thread.ofVirtual().factory();
			System.out.println("Using default virtual thread scheduler");
		}

		ObjectMapper objectMapper = new ObjectMapper();

		try {
			var bootstrap = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(new HttpServerCodec());
							ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
								@Override
								public void channelRead(ChannelHandlerContext ctx, Object msg) {
									if (msg instanceof HttpRequest) {
										HttpRequest request = (HttpRequest) msg;

										// Get or create the binary client for this connection
										BinaryClient binaryClient = ctx.channel().attr(BINARY_CLIENT_KEY).get();
										if (binaryClient == null) {
											binaryClient = new BinaryClient(backendHost, backendPort);
											ctx.channel().attr(BINARY_CLIENT_KEY).set(binaryClient);
										}

										BinaryClient finalBinaryClient = binaryClient;

										// Offload to virtual thread
										virtualThreadFactory.newThread(() -> {
											try {
												// Issue blocking request to binary server
												byte[] binaryData = finalBinaryClient.fetchUserData();

												// Parse binary data into User instances
												List<User> users = parseBinaryData(binaryData);

												// Serialize to JSON using Jackson
												ByteBuf content = ctx.alloc().buffer();
												try (ByteBufOutputStream out = new ByteBufOutputStream(content)) {
													objectMapper.writeValue(out, users);
												}

												// Create and send HTTP response
												var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
														HttpResponseStatus.OK, content);
												response.headers()
														.set(HttpHeaderNames.CONTENT_TYPE,
																HttpHeaderValues.APPLICATION_JSON)
														.set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
														.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

												ctx.writeAndFlush(response);
											} catch (Exception e) {
												e.printStackTrace();
												sendErrorResponse(ctx, e);
											}
										}).start();

										// Release the request immediately
										ReferenceCountUtil.release(msg);
									} else {
										ReferenceCountUtil.release(msg);
									}
								}

								@Override
								public void channelInactive(ChannelHandlerContext ctx) throws Exception {
									// Close binary client when connection closes
									BinaryClient binaryClient = ctx.channel().attr(BINARY_CLIENT_KEY).get();
									if (binaryClient != null) {
										binaryClient.close();
									}
									super.channelInactive(ctx);
								}

								@Override
								public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
									cause.printStackTrace();
									ctx.close();
								}
							});
						}
					});

			Channel ch = bootstrap.bind(port).sync().channel();
			System.out.println("HTTP server started on http://localhost:" + port + "/");

			ch.closeFuture().sync();
		} finally {
			group.shutdownGracefully();
		}
	}

	private static List<User> parseBinaryData(byte[] data) {
		if (data.length < 4) {
			return new ArrayList<>();
		}

		// Read count (big-endian)
		int count = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

		List<User> users = new ArrayList<>(count);
		for (int i = 0; i < count && (4 + (i + 1) * 4) <= data.length; i++) {
			int offset = 4 + i * 4;
			int id = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
					| ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
			users.add(new User(id));
		}

		return users;
	}

	private static void sendErrorResponse(ChannelHandlerContext ctx, Exception e) {
		ByteBuf content = ctx.alloc().buffer();
		content.writeCharSequence("Internal Server Error: " + e.getMessage(), io.netty.util.CharsetUtil.UTF_8);

		var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR,
				content);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
				.set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
				.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

		ctx.writeAndFlush(response).addListener(f -> ctx.close());
	}

	private static int getIntProperty(String name, int defaultValue) {
		String value = System.getProperty(name, System.getenv(name));
		if (value != null) {
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException e) {
				System.err.println("Invalid value for " + name + ": " + value + ", using default: " + defaultValue);
			}
		}
		return defaultValue;
	}

	/**
	 * Blocking HTTP client for communicating with the binary backend server.
	 */
	static class BinaryClient {
		private final String host;
		private final int port;
		private java.net.Socket socket;
		private java.io.OutputStream out;
		private java.io.InputStream in;

		BinaryClient(String host, int port) {
			this.host = host;
			this.port = port;
		}

		synchronized byte[] fetchUserData() throws IOException {
			if (socket == null || socket.isClosed()) {
				connect();
			}

			try {
				// Send a dummy request (4 bytes of zeros for length-prefixed protocol)
				byte[] request = new byte[] { 0, 0, 0, 0 };
				out.write(request);
				out.flush();

				// Read response length (4 bytes, big-endian)
				byte[] lengthBytes = new byte[4];
				int read = 0;
				while (read < 4) {
					int n = in.read(lengthBytes, read, 4 - read);
					if (n < 0) {
						throw new IOException("Connection closed while reading length");
					}
					read += n;
				}

				int length = ((lengthBytes[0] & 0xFF) << 24) | ((lengthBytes[1] & 0xFF) << 16)
						| ((lengthBytes[2] & 0xFF) << 8) | (lengthBytes[3] & 0xFF);

				// Read response data
				byte[] data = new byte[length];
				read = 0;
				while (read < length) {
					int n = in.read(data, read, length - read);
					if (n < 0) {
						throw new IOException("Connection closed while reading data");
					}
					read += n;
				}

				return data;
			} catch (IOException e) {
				// On error, close and try to reconnect on next request
				close();
				throw e;
			}
		}

		private void connect() throws IOException {
			socket = new java.net.Socket(host, port);
			socket.setTcpNoDelay(true);
			socket.setKeepAlive(true);
			out = socket.getOutputStream();
			in = socket.getInputStream();
		}

		synchronized void close() {
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					// Ignore
				}
				socket = null;
				out = null;
				in = null;
			}
		}
	}
}
