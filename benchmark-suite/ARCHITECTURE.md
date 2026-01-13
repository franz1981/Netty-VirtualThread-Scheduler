# Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Benchmark Suite Architecture                    │
└─────────────────────────────────────────────────────────────────────────┘

┌──────────────────┐
│  Load Generator  │  (wrk/wrk2 via jbang)
│  - N connections │  - Configurable rate
│  - HTTP requests │  - Monitors latency/throughput
└────────┬─────────┘
         │ HTTP
         │ GET /
         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    HTTP Frontend Server (Server 1)                       │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Netty Event Loop (Configurable: 1-N event loops)              │   │
│  │  ┌────────────────────────────────────────────────────────┐    │   │
│  │  │  HTTP Handler                                          │    │   │
│  │  │  - Receives HTTP request                              │    │   │
│  │  │  - Get/Create BinaryClient for this connection        │    │   │
│  │  │  - Offload to Virtual Thread                          │    │   │
│  │  └────────────────┬───────────────────────────────────────┘    │   │
│  └───────────────────┼────────────────────────────────────────────┘   │
│                      │                                                  │
│  ┌───────────────────▼────────────────────────────────────────┐        │
│  │  Virtual Thread (per request)                             │        │
│  │  ┌──────────────────────────────────────────────────────┐ │        │
│  │  │  Scheduler Selection (SCHEDULER env var):           │ │        │
│  │  │  • custom:  NettyScheduler (VirtualMultithread...)  │ │        │
│  │  │             → Runs on same carrier as event loop    │ │        │
│  │  │             → Better cache locality                 │ │        │
│  │  │  • default: Thread.ofVirtual().factory()            │ │        │
│  │  │             → Standard JVM scheduler                │ │        │
│  │  └──────────────────────────────────────────────────────┘ │        │
│  │                                                            │        │
│  │  1. Use persistent BinaryClient (blocking Socket)         │        │
│  │  2. Send binary request to backend                        │        │
│  │  3. Blocking read of binary response                      │        │
│  │  4. Parse binary → List<User>                             │        │
│  │  5. Jackson serialize to JSON in ByteBuf                  │        │
│  │  6. Write HTTP response to client                         │        │
│  └────────────────────┬───────────────────────────────────────┘        │
└─────────────────────────┼────────────────────────────────────────────────┘
                          │ Binary Protocol
                          │ (Length-Prefixed)
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                   Binary Backend Server (Server 2)                       │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Netty Event Loop (Single loop for minimal overhead)           │   │
│  │  ┌────────────────────────────────────────────────────────┐    │   │
│  │  │  Binary Handler (LengthFieldBasedFrameDecoder)        │    │   │
│  │  │  - Receive binary request                             │    │   │
│  │  │  - Optional think time (configurable delay)           │    │   │
│  │  │  - Send cached binary response                        │    │   │
│  │  │                                                        │    │   │
│  │  │  Binary Format:                                       │    │   │
│  │  │  ┌────────────────┬───────────────────────────┐      │    │   │
│  │  │  │ 4-byte length  │  Payload (User data)      │      │    │   │
│  │  │  └────────────────┴───────────────────────────┘      │    │   │
│  │  │                                                        │    │   │
│  │  │  Payload Format:                                      │    │   │
│  │  │  ┌────────────┬─────────┬─────────┬─────────┐        │    │   │
│  │  │  │ Count (4B) │ ID₁(4B) │ ID₂(4B) │ ...     │        │    │   │
│  │  │  └────────────┴─────────┴─────────┴─────────┘        │    │   │
│  │  └────────────────────────────────────────────────────────┘    │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                          Monitoring & Profiling                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │
│  │   pidstat    │  │    perf      │  │async-profiler│                  │
│  │  (CPU/Mem)   │  │  (optional)  │  │  (optional)  │                  │
│  └──────────────┘  └──────────────┘  └──────────────┘                  │
│          │                 │                  │                          │
│          └─────────────────┴──────────────────┘                          │
│                            │                                             │
│                ┌───────────▼────────────┐                                │
│                │  Results Directory     │                                │
│                │  - pidstat.txt         │                                │
│                │  - flamegraphs         │                                │
│                │  - wrk output          │                                │
│                └────────────────────────┘                                │
└─────────────────────────────────────────────────────────────────────────┘

Configuration Flow:
─────────────────

Environment Variables → Servers:
  HTTP_PORT         → HttpServer port (default: 8080)
  BACKEND_HOST      → Binary server hostname
  BACKEND_PORT      → Binary server port (default: 9090)
  SCHEDULER         → "custom" or "default" (default: custom)
  EVENT_LOOPS       → Number of event loops (default: 2)
  THINK_TIME_MS     → Binary server delay (default: 0)
  USER_COUNT        → Number of users in response (default: 100)

Script Parameters → benchmark.sh:
  -d DURATION       → Test duration in seconds
  -c CONNECTIONS    → Concurrent connections
  -r RATE          → Request rate (0 = unlimited)
  -s SCHEDULER     → "custom" or "default"
  -e EVENT_LOOPS   → Event loops for HTTP server
  -t THINK_TIME    → Binary server think time
  -p               → Enable async-profiler

Data Flow:
──────────

1. HTTP Request (Load Generator)
   ↓
2. Event Loop receives request (HttpServer)
   ↓
3. Offload to Virtual Thread (Scheduler-dependent)
   ↓
4. Blocking call to Binary Server (BinaryClient over Socket)
   ↓
5. Binary Server responds (length-prefixed User data)
   ↓
6. Parse binary → List<User>
   ↓
7. Jackson: List<User> → JSON → ByteBuf
   ↓
8. HTTP Response with JSON content
   ↓
9. Load Generator receives response

Scheduler Comparison:
────────────────────

Custom NettyScheduler:
  ┌─────────────────────────┐
  │ Carrier Thread (Fixed)  │
  │  ├─ Event Loop VThread  │
  │  └─ Request VThreads    │ ← Same carrier = better locality
  └─────────────────────────┘

Default Scheduler:
  ┌─────────────────────────┐
  │ Carrier Thread Pool     │
  │  ├─ Event Loop VThread  │
  │  └─ Request VThreads    │ ← May migrate = more switches
  └─────────────────────────┘
```

## Key Points

1. **HTTP Server runs with configurable scheduler**
   - Custom: Uses `VirtualMultithreadIoEventLoopGroup` 
   - Default: Uses `MultiThreadIoEventLoopGroup` + standard VThreads

2. **Each HTTP connection gets a persistent BinaryClient**
   - Avoids reconnection overhead
   - Simulates realistic long-lived connections

3. **Virtual threads perform blocking I/O**
   - This is where scheduler differences matter
   - Custom scheduler keeps VThread on same carrier

4. **Binary protocol is very simple**
   - Minimal overhead on backend
   - Focus is on frontend scheduler performance

5. **Monitoring captures the differences**
   - pidstat shows CPU usage patterns
   - async-profiler shows execution patterns
   - wrk shows throughput/latency differences
