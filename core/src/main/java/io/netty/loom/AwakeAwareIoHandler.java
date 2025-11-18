package io.netty.loom;

import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoRegistration;

import java.util.concurrent.atomic.AtomicBoolean;

final class AwakeAwareIoHandler implements IoHandler {

	private final IoHandler delegate;
	private final AtomicBoolean running;

	public AwakeAwareIoHandler(AtomicBoolean running, IoHandler delegate) {
		this.running = running;
		this.delegate = delegate;
	}

	@Override
	public void initialize() {
		delegate.initialize();
	}

	@Override
	public int run(IoHandlerContext context) {
		return delegate.run(context);
	}

	@Override
	public void prepareToDestroy() {
		delegate.prepareToDestroy();
	}

	@Override
	public void destroy() {
		delegate.destroy();
	}

	@Override
	public IoRegistration register(IoHandle handle) throws Exception {
		return delegate.register(handle);
	}

	@Override
	public void wakeup() {
		if (!running.get()) {
			delegate.wakeup();
		}
	}

	@Override
	public boolean isCompatible(Class<? extends IoHandle> handleType) {
		return delegate.isCompatible(handleType);
	}
}
