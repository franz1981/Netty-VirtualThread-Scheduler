# example-echo

HTTP server on Netty with epoll pinned pollers. Demonstrates blocking virtual
thread handlers with carrier affinity and structured concurrency.

## What it shows

- **Blocking handlers on virtual threads** — each HTTP request is dispatched to a VT
  from the scheduler's factory. The VT can block freely (the carrier runs other VTs
  while it sleeps).
- **Carrier affinity** — each connection is handled by the carrier whose poller
  accepted it. The handler VT stays on that carrier.
- **Structured concurrency** — the `/parallel` endpoint forks two tasks using
  `StructuredTaskScope` with the carrier's thread factory. Both tasks run on the
  same carrier.

## Endpoints

| Path | Description |
|---|---|
| `GET /` | Blocking handler (50ms sleep), returns VT name + carrier info |
| `GET /parallel` | `StructuredTaskScope` fork/join — two tasks on the same carrier |

## Build and run

```bash
export JAVA_HOME=/path/to/loom/build/linux-x86_64-server-release/jdk

# build from repository root
mvn -DskipTests package

# run
cd example-echo
DEPS=$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)
"$JAVA_HOME/bin/java" --enable-preview --enable-native-access=ALL-UNNAMED \
  -Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler \
  -cp "target/classes:$DEPS" io.netty.loom.example.EchoServer
```

## Expected output

```
Echo server started on http://localhost:8080/
  GET /         — blocking handler with carrier info
  GET /parallel — structured concurrency on same carrier
```

### Blocking handler — each connection lands on a different carrier

Netty round-robins accepted connections across the event loop group's pollers.
Each poller is pinned to a carrier. `group.vThreadFactory()` detects the current
carrier (via `EventLoopScheduler.currentScheduler()`) and returns that carrier's
factory — so the handler VT inherits the connection's carrier affinity.

```
$ curl http://localhost:8080/
HELLO from VirtualThread[#107]/runnable@Thread-1 on carrier Thread-1 (scheduler 1)

$ curl http://localhost:8080/
HELLO from VirtualThread[#109]/runnable@Thread-2 on carrier Thread-2 (scheduler 2)

$ curl http://localhost:8080/
HELLO from VirtualThread[#111]/runnable@Thread-3 on carrier Thread-3 (scheduler 3)
```

### Structured concurrency — both tasks on the same carrier

```
$ curl http://localhost:8080/parallel
A from VirtualThread[#114]/runnable@Thread-4 | B from VirtualThread[#115]/runnable@Thread-4
```

Both forked tasks run on Thread-4 — carrier affinity preserved through `StructuredTaskScope`.
