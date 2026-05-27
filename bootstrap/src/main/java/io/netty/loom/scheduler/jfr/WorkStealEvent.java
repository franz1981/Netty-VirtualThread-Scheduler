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
package io.netty.loom.scheduler.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("io.netty.loom.WorkSteal")
@Label("Work Steal")
@Description("A virtual thread task was stolen from a busy carrier by an idle one.")
@Category({"Netty", "Scheduler"})
@Enabled(false)
@StackTrace(false)
public final class WorkStealEvent extends Event {

	private static final EventType EVENT_TYPE = EventType.getEventType(WorkStealEvent.class);

	@Label("Stolen Virtual Thread")
	public Thread virtualThread;

	@Label("Source Carrier")
	public Thread sourceCarrier;

	@Label("Stealer Carrier")
	public Thread stealerCarrier;

	@Label("Source Queue Depth")
	public int sourceQueueDepth;

	@Label("From Carrier Loop")
	@Description("True if stolen from the carrier loop; false if stolen from the pinned poller via maybeYield.")
	public boolean fromCarrierLoop;

	public static boolean isEventEnabled() {
		return EVENT_TYPE.isEnabled();
	}
}
