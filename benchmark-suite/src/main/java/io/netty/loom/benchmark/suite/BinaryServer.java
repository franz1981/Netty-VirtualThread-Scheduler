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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.TimeUnit;

/**
 * Binary backend server that responds to length-prefixed requests.
 * Generates binary data representing User instances with configurable think time.
 */
public class BinaryServer {

	private static final int DEFAULT_PORT = 9090;
	private static final int DEFAULT_THINK_TIME_MS = 0;
	private static final int DEFAULT_USER_COUNT = 100;

	public static void main(String[] args) throws Exception {
		int port = getIntProperty("BINARY_PORT", DEFAULT_PORT);
		int thinkTimeMs = getIntProperty("THINK_TIME_MS", DEFAULT_THINK_TIME_MS);
		int userCount = getIntProperty("USER_COUNT", DEFAULT_USER_COUNT);

		System.out.println("Starting Binary Server on port " + port);
		System.out.println("Think time: " + thinkTimeMs + "ms");
		System.out.println("User count: " + userCount);

		// Pre-generate the response to make the server very light
		byte[] cachedResponse = BinaryProtocol.generateUserData(userCount);

		// Use single event loop for minimal overhead
		try (var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())) {
			var bootstrap = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline()
									// Length field decoder: 4-byte length prefix
									.addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4))
									// Length field prepender: 4-byte length prefix
									.addLast(new LengthFieldPrepender(4))
									.addLast(new ChannelInboundHandlerAdapter() {
										@Override
										public void channelRead(ChannelHandlerContext ctx, Object msg) {
											// Release the incoming request immediately
											ReferenceCountUtil.release(msg);

											if (thinkTimeMs > 0) {
												// Schedule response after think time
												ctx.executor().schedule(() -> {
													ByteBuf response = ctx.alloc().buffer(cachedResponse.length);
													response.writeBytes(cachedResponse);
													ctx.writeAndFlush(response);
												}, thinkTimeMs, TimeUnit.MILLISECONDS);
											} else {
												// Immediate response
												ByteBuf response = ctx.alloc().buffer(cachedResponse.length);
												response.writeBytes(cachedResponse);
												ctx.writeAndFlush(response);
											}
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
			System.out.println("Binary server started on port " + port);

			ch.closeFuture().sync();
		}
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
}
