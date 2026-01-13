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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the benchmark suite servers.
 */
class BenchmarkSuiteIntegrationTest {

	private static final int BINARY_PORT = 19090;
	private static final int HTTP_PORT = 18080;
	private static final int USER_COUNT = 10;

	private Thread binaryServerThread;
	private Thread httpServerThread;
	private AtomicBoolean binaryServerRunning = new AtomicBoolean(false);
	private AtomicBoolean httpServerRunning = new AtomicBoolean(false);

	@BeforeEach
	void setUp() throws Exception {
		// Ensure ports are available
		assertPortAvailable(BINARY_PORT);
		assertPortAvailable(HTTP_PORT);
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		if (binaryServerThread != null) {
			binaryServerThread.interrupt();
			binaryServerThread.join(2000);
		}
		if (httpServerThread != null) {
			httpServerThread.interrupt();
			httpServerThread.join(2000);
		}
	}

	@Test
	@Timeout(30)
	void testBinaryServerResponds() throws Exception {
		startMockBinaryServer();
		
		// Wait for server to start
		assertTrue(waitForPort(BINARY_PORT, 5000), "Binary server should start");

		// Connect and send request
		try (Socket socket = new Socket("localhost", BINARY_PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			// Send dummy request (4 bytes)
			out.write(new byte[] { 0, 0, 0, 0 });
			out.flush();

			// Read length
			byte[] lengthBytes = new byte[4];
			int read = in.read(lengthBytes);
			assertEquals(4, read);

			int length = ((lengthBytes[0] & 0xFF) << 24) | ((lengthBytes[1] & 0xFF) << 16)
					| ((lengthBytes[2] & 0xFF) << 8) | (lengthBytes[3] & 0xFF);
			assertTrue(length > 0);

			// Read data
			byte[] data = new byte[length];
			int totalRead = 0;
			while (totalRead < length) {
				int n = in.read(data, totalRead, length - totalRead);
				if (n < 0) break;
				totalRead += n;
			}
			assertEquals(length, totalRead);

			// Verify data can be parsed
			List<User> users = BinaryProtocol.parseBinaryData(data);
			assertEquals(USER_COUNT, users.size());
		}
	}

	@Test
	@Timeout(30)
	void testHttpServerWithCustomScheduler() throws Exception {
		System.setProperty("SCHEDULER", "custom");
		System.setProperty("HTTP_PORT", String.valueOf(HTTP_PORT));
		System.setProperty("BACKEND_PORT", String.valueOf(BINARY_PORT));
		System.setProperty("EVENT_LOOPS", "1");

		testHttpServerFlow();
	}

	@Test
	@Timeout(30)
	void testHttpServerWithDefaultScheduler() throws Exception {
		System.setProperty("SCHEDULER", "default");
		System.setProperty("HTTP_PORT", String.valueOf(HTTP_PORT));
		System.setProperty("BACKEND_PORT", String.valueOf(BINARY_PORT));
		System.setProperty("EVENT_LOOPS", "1");

		testHttpServerFlow();
	}

	private void testHttpServerFlow() throws Exception {
		startMockBinaryServer();
		assertTrue(waitForPort(BINARY_PORT, 5000), "Binary server should start");

		startHttpServer();
		assertTrue(waitForPort(HTTP_PORT, 5000), "HTTP server should start");

		// Make HTTP request
		URL url = new URL("http://localhost:" + HTTP_PORT + "/");
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(5000);

		int responseCode = conn.getResponseCode();
		assertEquals(200, responseCode);

		String contentType = conn.getHeaderField("Content-Type");
		assertTrue(contentType.contains("application/json"));

		// Parse JSON response
		ObjectMapper mapper = new ObjectMapper();
		List<User> users = mapper.readValue(conn.getInputStream(), new TypeReference<List<User>>() {});
		
		assertNotNull(users);
		assertEquals(USER_COUNT, users.size());
		for (int i = 0; i < USER_COUNT; i++) {
			assertEquals(i, users.get(i).getId());
		}
	}

	@Test
	void testUserModel() {
		User user = new User(42);
		assertEquals(42, user.getId());

		user.setId(100);
		assertEquals(100, user.getId());
	}

	private void startMockBinaryServer() {
		byte[] cachedResponse = BinaryProtocol.generateUserData(USER_COUNT);
		
		binaryServerThread = new Thread(() -> {
			try (ServerSocket serverSocket = new ServerSocket(BINARY_PORT)) {
				binaryServerRunning.set(true);
				serverSocket.setSoTimeout(1000); // Allow interruption
				
				while (!Thread.currentThread().isInterrupted()) {
					try {
						Socket clientSocket = serverSocket.accept();
						handleBinaryClient(clientSocket, cachedResponse);
					} catch (java.net.SocketTimeoutException e) {
						// Normal timeout, check for interruption
					} catch (IOException e) {
						if (!Thread.currentThread().isInterrupted()) {
							e.printStackTrace();
						}
					}
				}
			} catch (IOException e) {
				if (!Thread.currentThread().isInterrupted()) {
					e.printStackTrace();
				}
			} finally {
				binaryServerRunning.set(false);
			}
		});
		binaryServerThread.setDaemon(true);
		binaryServerThread.start();
	}

	private void handleBinaryClient(Socket socket, byte[] response) {
		try {
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();

			// Read request (4 bytes)
			byte[] request = new byte[4];
			in.read(request);

			// Send length
			byte[] lengthBytes = new byte[4];
			int length = response.length;
			lengthBytes[0] = (byte) (length >>> 24);
			lengthBytes[1] = (byte) (length >>> 16);
			lengthBytes[2] = (byte) (length >>> 8);
			lengthBytes[3] = (byte) length;
			out.write(lengthBytes);

			// Send data
			out.write(response);
			out.flush();
		} catch (IOException e) {
			// Ignore
		} finally {
			try {
				socket.close();
			} catch (IOException e) {
				// Ignore
			}
		}
	}

	private void startHttpServer() {
		httpServerThread = new Thread(() -> {
			try {
				httpServerRunning.set(true);
				HttpServer.main(new String[0]);
			} catch (Exception e) {
				if (!Thread.currentThread().isInterrupted()) {
					e.printStackTrace();
				}
			} finally {
				httpServerRunning.set(false);
			}
		});
		httpServerThread.setDaemon(true);
		httpServerThread.start();
	}

	private boolean waitForPort(int port, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			try (Socket socket = new Socket("localhost", port)) {
				return true;
			} catch (IOException e) {
				Thread.sleep(100);
			}
		}
		return false;
	}

	private void assertPortAvailable(int port) {
		try (ServerSocket serverSocket = new ServerSocket(port)) {
			// Port is available
		} catch (IOException e) {
			fail("Port " + port + " is not available: " + e.getMessage());
		}
	}
}
