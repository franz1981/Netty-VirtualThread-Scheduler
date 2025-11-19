/*
 * Copyright © 2024 Francesco Nigro (nigro.fra@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
