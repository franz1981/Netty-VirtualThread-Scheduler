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

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for encoding and decoding binary data representing User instances.
 * Format: 4-byte count followed by N * 4-byte user IDs (big-endian).
 */
public final class BinaryProtocol {

	private BinaryProtocol() {
		// Utility class
	}

	/**
	 * Generate binary data representing N User instances.
	 * Format: 4-byte count followed by N * 4-byte user IDs.
	 */
	public static byte[] generateUserData(int count) {
		byte[] data = new byte[4 + count * 4]; // 4 bytes for count + 4 bytes per user
		// Write count (big-endian)
		data[0] = (byte) (count >>> 24);
		data[1] = (byte) (count >>> 16);
		data[2] = (byte) (count >>> 8);
		data[3] = (byte) count;

		// Write user IDs
		for (int i = 0; i < count; i++) {
			int offset = 4 + i * 4;
			data[offset] = (byte) (i >>> 24);
			data[offset + 1] = (byte) (i >>> 16);
			data[offset + 2] = (byte) (i >>> 8);
			data[offset + 3] = (byte) i;
		}

		return data;
	}

	/**
	 * Parse binary data into User instances.
	 */
	public static List<User> parseBinaryData(byte[] data) {
		if (data.length < 4) {
			return new ArrayList<>();
		}

		// Read count (big-endian)
		int count = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

		List<User> users = new ArrayList<>(count);
		for (int i = 0; i < count && (4 + (i + 1) * 4) <= data.length; i++) {
			int offset = 4 + i * 4;
			int id = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
					| ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
			users.add(new User(id));
		}

		return users;
	}
}
