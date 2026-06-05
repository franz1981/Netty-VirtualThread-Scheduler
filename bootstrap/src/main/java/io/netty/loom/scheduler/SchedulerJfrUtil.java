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
package io.netty.loom.scheduler;

import io.netty.loom.scheduler.jfr.*;

final class SchedulerJfrUtil {

	private SchedulerJfrUtil() {
	}

	public static VirtualThreadTaskRunsEvent beginVirtualThreadTaskRunsEvent() {
		if (!VirtualThreadTaskRunsEvent.isEventEnabled()) {
			return null;
		}
		var event = new VirtualThreadTaskRunsEvent();
		event.begin();
		return event;
	}

	public static VirtualThreadTaskRunEvent beginVirtualThreadTaskRunEvent() {
		if (!VirtualThreadTaskRunEvent.isEventEnabled()) {
			return null;
		}
		var event = new VirtualThreadTaskRunEvent();
		event.begin();
		return event;
	}

	public static void commitVirtualThreadTaskRunsEvent(VirtualThreadTaskRunsEvent event, Thread carrierThread,
			int tasksExecuted, int queueDepthBefore, int queueDepthAfter) {
		event.end();
		event.carrierThread = carrierThread;
		event.tasksExecuted = tasksExecuted;
		event.queueDepthBefore = queueDepthBefore;
		event.queueDepthAfter = queueDepthAfter;
		event.commit();
	}

	public static void commitVirtualThreadTaskRunEvent(VirtualThreadTaskRunEvent event, Thread carrierThread,
			Thread virtualThread, boolean isPoller, boolean isPinnedPoller) {
		event.end();
		event.carrierThread = carrierThread;
		event.virtualThread = virtualThread;
		event.isPoller = isPoller;
		event.isPinnedPoller = isPinnedPoller;
		event.commit();
	}

	public static void commitVirtualThreadTaskSubmitEvent(Thread.VirtualThreadTask task, Thread submitterThread,
			Thread carrierThread, boolean isPoller, boolean isPinnedPoller) {
		var event = new VirtualThreadTaskSubmitEvent();
		event.virtualThread = task.thread();
		event.submitterThread = submitterThread;
		event.carrierThread = carrierThread;
		event.isPoller = isPoller;
		event.isPinnedPoller = isPinnedPoller;
		event.commit();
	}

	public static WorkStealEvent beginWorkStealEvent() {
		if (!WorkStealEvent.isEventEnabled()) {
			return null;
		}
		var event = new WorkStealEvent();
		event.begin();
		return event;
	}

	public static void commitWorkStealEvent(WorkStealEvent event, Thread.VirtualThreadTask task, Thread sourceCarrier,
			Thread stealerCarrier, int sourceQueueDepth, boolean fromCarrierLoop) {
		event.end();
		event.virtualThread = task.thread();
		event.sourceCarrier = sourceCarrier;
		event.stealerCarrier = stealerCarrier;
		event.sourceQueueDepth = sourceQueueDepth;
		event.fromCarrierLoop = fromCarrierLoop;
		event.directed = false;
		event.commit();
	}

	public static void commitDirectedStealEvent(WorkStealEvent event, Thread.VirtualThreadTask task,
			Thread sourceCarrier, Thread stealerCarrier, int sourceQueueDepth, boolean inline) {
		event.end();
		event.virtualThread = task.thread();
		event.sourceCarrier = sourceCarrier;
		event.stealerCarrier = stealerCarrier;
		event.sourceQueueDepth = sourceQueueDepth;
		event.fromCarrierLoop = inline;
		event.directed = true;
		event.commit();
	}

}
