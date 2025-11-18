package io.netty.loom.example;

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
import io.netty.loom.VirtualMultithreadIoEventLoopGroup;

import java.util.concurrent.ThreadFactory;

public class EchoServer {

	public static void main(String[] args) throws Exception {
		// simple echo server on port 8080
		try (var group = new VirtualMultithreadIoEventLoopGroup(1, NioIoHandler.newFactory())) {
			var bootstrap = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(new HttpServerCodec());
							ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
								@Override
								public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
									if (msg instanceof DefaultHttpRequest) {
										ThreadFactory vtf = group.vThreadFactory();
										vtf.newThread(() -> {
											try {
												// blocking work placeholder
												Thread.sleep(50);
												var content = ctx.alloc().directBuffer("HELLO".length());
												content.writeCharSequence("HELLO", CharsetUtil.US_ASCII);
												var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
														HttpResponseStatus.OK, content);
												response.headers()
														.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
														.set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
														.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
												// write the response; the inbound request is not needed by the virtual
												// thread, so it is safe to release it immediately after scheduling.
												ctx.writeAndFlush(response);
											} catch (InterruptedException e) {
												Thread.currentThread().interrupt();
											}
										}).start();
										// release the inbound message immediately: the virtual thread does not use it
										ReferenceCountUtil.release(msg);
									} else {
										ReferenceCountUtil.release(msg);
									}
								}
							});
						}
					});

			Channel ch = bootstrap.bind(8080).sync().channel();
			System.out.println("Echo server started on http://localhost:8080/");

			// keep server running until the channel is closed (by external shutdown)
			ch.closeFuture().sync();
		}
	}
}
