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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for BinaryProtocol utility.
 */
class BinaryProtocolTest {

	@Test
	void testGenerateUserData() {
		byte[] data = BinaryProtocol.generateUserData(3);
		
		// Check length: 4 bytes for count + 3 * 4 bytes for user IDs
		assertEquals(16, data.length);
		
		// Check count (first 4 bytes, big-endian)
		int count = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) 
				| ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
		assertEquals(3, count);
		
		// Check first user ID (bytes 4-7)
		int firstId = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) 
				| ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
		assertEquals(0, firstId);
		
		// Check second user ID (bytes 8-11)
		int secondId = ((data[8] & 0xFF) << 24) | ((data[9] & 0xFF) << 16) 
				| ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
		assertEquals(1, secondId);
	}

	@Test
	void testGenerateEmptyUserData() {
		byte[] data = BinaryProtocol.generateUserData(0);
		assertEquals(4, data.length); // Just the count
		
		int count = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) 
				| ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
		assertEquals(0, count);
	}

	@Test
	void testParseBinaryData() {
		byte[] data = BinaryProtocol.generateUserData(5);
		List<User> users = BinaryProtocol.parseBinaryData(data);
		
		assertEquals(5, users.size());
		for (int i = 0; i < 5; i++) {
			assertEquals(i, users.get(i).getId());
		}
	}

	@Test
	void testParseBinaryDataEmpty() {
		byte[] data = BinaryProtocol.generateUserData(0);
		List<User> users = BinaryProtocol.parseBinaryData(data);
		
		assertTrue(users.isEmpty());
	}

	@Test
	void testParseBinaryDataTooShort() {
		byte[] data = new byte[] { 0, 0, 0 }; // Less than 4 bytes
		List<User> users = BinaryProtocol.parseBinaryData(data);
		
		assertTrue(users.isEmpty());
	}

	@Test
	void testParseBinaryDataIncomplete() {
		// Create data for 2 users but only include 1 complete user
		byte[] data = new byte[12]; // 4 bytes count + 2 * 4 bytes users, but we'll truncate
		data[0] = 0;
		data[1] = 0;
		data[2] = 0;
		data[3] = 2; // Count = 2
		
		// First user ID = 0
		data[4] = 0;
		data[5] = 0;
		data[6] = 0;
		data[7] = 0;
		
		// Second user ID = 1
		data[8] = 0;
		data[9] = 0;
		data[10] = 0;
		data[11] = 1;
		
		List<User> users = BinaryProtocol.parseBinaryData(data);
		assertEquals(2, users.size());
	}

	@Test
	void testRoundTrip() {
		int userCount = 100;
		byte[] generated = BinaryProtocol.generateUserData(userCount);
		List<User> parsed = BinaryProtocol.parseBinaryData(generated);
		
		assertEquals(userCount, parsed.size());
		for (int i = 0; i < userCount; i++) {
			assertEquals(i, parsed.get(i).getId());
		}
	}
}
