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
package io.netty.loom.scheduler;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;

public class StickenssProgress {

	@Test
	public void testProgress() throws InterruptedException {
		testProgress(false);
	}

	private static void testProgress(boolean sticky) throws InterruptedException {
		System.out.println("total cores: " + Runtime.getRuntime().availableProcessors());
		Assertions.assertTrue(!Thread.currentThread().isVirtual());
		// just use booleans to save start/onContinue from sticky threads to be relevant
		AtomicBoolean firstStarted = new AtomicBoolean();
		AtomicBoolean progressed = new AtomicBoolean();
		var factory = Thread.ofVirtual();
		if (sticky) {
			factory.stickyAffinity();
		}
		factory.start(() -> {
			Thread.yield();
			firstStarted.set(true);
			while (!progressed.get()) {
				Thread.yield();
			}
		});
		while (!firstStarted.get()) {
			Thread.onSpinWait();
		}
		System.out.println("first is started: starting the second one");
		Thread secondThread = Thread.ofVirtual().start(() -> {
			progressed.set(true);
		});
		secondThread.join();
	}

}
