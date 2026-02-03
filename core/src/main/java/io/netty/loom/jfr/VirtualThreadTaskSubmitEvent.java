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
package io.netty.loom.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("io.netty.loom.VirtualThreadTaskSubmit")
@Label("Virtual Thread Task Submit")
@Description("Virtual thread submission to the Netty Loom scheduler.")
@Category({"Netty", "Scheduler"})
@Enabled(false)
@StackTrace(false)
public final class VirtualThreadTaskSubmitEvent extends Event {

	private static final EventType EVENT_TYPE = EventType.getEventType(VirtualThreadTaskSubmitEvent.class);

	@Label("Virtual Thread")
	public Thread virtualThread;

	@Label("Submitter Thread")
	public Thread submitterThread;

	@Label("Carrier Thread")
	public Thread carrierThread;

	@Label("Is Poller")
	public boolean isPoller;

	@Label("Is Event Loop")
	public boolean isEventLoop;

	@Label("Immediate")
	public boolean immediate;

	public static boolean isEventEnabled() {
		return EVENT_TYPE.isEnabled();
	}
}
