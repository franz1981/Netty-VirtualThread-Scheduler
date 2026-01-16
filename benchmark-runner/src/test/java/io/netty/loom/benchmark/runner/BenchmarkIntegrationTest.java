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
package io.netty.loom.benchmark.runner;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies MockHttpServer and HandoffHttpServer work
 * correctly together with different configurations.
 * <p>
 * Tests cover:
 * <ul>
 * <li>NIO I/O with default scheduler</li>
 * <li>NIO I/O with custom scheduler</li>
 * <li>EPOLL I/O with default scheduler</li>
 * <li>EPOLL I/O with custom scheduler</li>
 * </ul>
 */
class BenchmarkIntegrationTest {

	private static final AtomicInteger PORT_COUNTER = new AtomicInteger(19000);

	private MockHttpServer mockServer;
	private HandoffHttpServer handoffServer;
	private int mockPort;
	private int handoffPort;

	static Stream<Arguments> serverConfigurations() {
		return Stream.of(
				// IO type, use custom scheduler, description
				Arguments.of(HandoffHttpServer.IO.NIO, false, "NIO with default scheduler"),
				Arguments.of(HandoffHttpServer.IO.NIO, true, "NIO with custom scheduler"),
				Arguments.of(HandoffHttpServer.IO.EPOLL, false, "EPOLL with default scheduler"),
				Arguments.of(HandoffHttpServer.IO.EPOLL, true, "EPOLL with custom scheduler"));
	}

	void startServers(HandoffHttpServer.IO ioType, boolean useCustomScheduler) throws Exception {
		// Use unique ports for each test to avoid conflicts
		mockPort = PORT_COUNTER.getAndIncrement();
		handoffPort = PORT_COUNTER.getAndIncrement();

		// Start mock server with minimal think time for fast tests
		mockServer = new MockHttpServer(mockPort, 0, 1);
		mockServer.start();

		// Wait for mock server to be ready
		await().atMost(5, TimeUnit.SECONDS).until(() -> {
			try {
				return given().port(mockPort).when().get("/health").statusCode() == 200;
			} catch (Exception e) {
				return false;
			}
		});

		// Start handoff server with specified configuration
		handoffServer = new HandoffHttpServer(handoffPort, "http://localhost:" + mockPort + "/fruits", 1,
				useCustomScheduler, ioType);
		handoffServer.start();

		// Wait for handoff server to be ready
		await().atMost(5, TimeUnit.SECONDS).until(() -> {
			try {
				return given().port(handoffPort).when().get("/health").statusCode() == 200;
			} catch (Exception e) {
				return false;
			}
		});
	}

	@AfterEach
	void stopServers() {
		if (handoffServer != null) {
			handoffServer.stop();
			handoffServer = null;
		}
		if (mockServer != null) {
			mockServer.stop();
			mockServer = null;
		}
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("serverConfigurations")
	void mockServerHealthEndpoint(HandoffHttpServer.IO ioType, boolean useCustomScheduler, String description)
			throws Exception {
		startServers(ioType, useCustomScheduler);
		given().port(mockPort).when().get("/health").then().statusCode(200).body(equalTo("OK"));
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("serverConfigurations")
	void mockServerFruitsEndpoint(HandoffHttpServer.IO ioType, boolean useCustomScheduler, String description)
			throws Exception {
		startServers(ioType, useCustomScheduler);
		given().port(mockPort).when().get("/fruits").then().statusCode(200).contentType(ContentType.JSON)
				.body("fruits", hasSize(10)).body("fruits[0].name", equalTo("Apple"))
				.body("fruits[0].color", equalTo("Red")).body("fruits[0].price", equalTo(1.20f));
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("serverConfigurations")
	void handoffServerHealthEndpoint(HandoffHttpServer.IO ioType, boolean useCustomScheduler, String description)
			throws Exception {
		startServers(ioType, useCustomScheduler);
		given().port(handoffPort).when().get("/health").then().statusCode(200).body(equalTo("OK"));
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("serverConfigurations")
	void handoffServerFruitsEndpoint(HandoffHttpServer.IO ioType, boolean useCustomScheduler, String description)
			throws Exception {
		startServers(ioType, useCustomScheduler);
		given().port(handoffPort).when().get("/fruits").then().statusCode(200).contentType(ContentType.JSON)
				.body("fruits", hasSize(10)).body("fruits[0].name", equalTo("Apple"))
				.body("fruits[0].color", equalTo("Red"));
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("serverConfigurations")
	void handoffServerRootEndpoint(HandoffHttpServer.IO ioType, boolean useCustomScheduler, String description)
			throws Exception {
		startServers(ioType, useCustomScheduler);
		given().port(handoffPort).when().get("/").then().statusCode(200).contentType(ContentType.JSON).body("fruits",
				hasSize(10));
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("serverConfigurations")
	void handoffServer404ForUnknownPath(HandoffHttpServer.IO ioType, boolean useCustomScheduler, String description)
			throws Exception {
		startServers(ioType, useCustomScheduler);
		given().port(handoffPort).when().get("/unknown").then().statusCode(404);
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("serverConfigurations")
	void handoffServerReturnsAllFruits(HandoffHttpServer.IO ioType, boolean useCustomScheduler, String description)
			throws Exception {
		startServers(ioType, useCustomScheduler);

		List<String> fruitNames = given().port(handoffPort).when().get("/fruits").then().statusCode(200).extract()
				.jsonPath().getList("fruits.name", String.class);

		assertEquals(10, fruitNames.size());
		assertTrue(fruitNames.contains("Apple"));
		assertTrue(fruitNames.contains("Banana"));
		assertTrue(fruitNames.contains("Kiwi"));
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("serverConfigurations")
	void handoffServerHandlesMultipleRequests(HandoffHttpServer.IO ioType, boolean useCustomScheduler,
			String description) throws Exception {
		startServers(ioType, useCustomScheduler);

		// Send multiple requests to verify server handles concurrent load
		for (int i = 0; i < 10; i++) {
			given().port(handoffPort).when().get("/fruits").then().statusCode(200).body("fruits", hasSize(10));
		}
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("serverConfigurations")
	void verifyEndToEndJsonParsing(HandoffHttpServer.IO ioType, boolean useCustomScheduler, String description)
			throws Exception {
		startServers(ioType, useCustomScheduler);

		// This test verifies the complete flow:
		// 1. HandoffHttpServer receives request
		// 2. Makes blocking call to MockHttpServer
		// 3. Parses JSON with Jackson into Fruit objects
		// 4. Re-encodes and returns

		FruitsResponse response = given().port(handoffPort).when().get("/fruits").then().statusCode(200).extract()
				.as(FruitsResponse.class);

		assertNotNull(response);
		assertNotNull(response.fruits());
		assertEquals(10, response.fruits().size());

		Fruit apple = response.fruits().stream().filter(f -> "Apple".equals(f.name())).findFirst().orElse(null);

		assertNotNull(apple);
		assertEquals("Red", apple.color());
		assertEquals(1.20, apple.price(), 0.01);
	}
}
