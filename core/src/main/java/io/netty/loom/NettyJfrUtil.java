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
package io.netty.loom;

import io.netty.loom.jfr.*;

final class NettyJfrUtil {

	private NettyJfrUtil() {
	}

	public static NettyRunIoEvent beginRunIoEvent() {
		if (!NettyRunIoEvent.isEventEnabled()) {
			return null;
		}
		var event = new NettyRunIoEvent();
		event.begin();
		return event;
	}

	public static void commitRunIoEvent(NettyRunIoEvent event, Thread carrierThread, boolean ranBlocking,
			int ioEventsHandled) {
		event.end();
		event.eventLoopThread = Thread.currentThread();
		event.carrierThread = carrierThread;
		event.canBlock = ranBlocking;
		event.ioEventsHandled = ioEventsHandled;
		event.commit();
	}

}
