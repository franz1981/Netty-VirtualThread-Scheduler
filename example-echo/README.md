# example-echo

A tiny HTTP example that shows how to use VirtualMultithreadIoEventLoopGroup and its
`vThreadFactory()` to spawn virtual threads from Netty handlers.

Prerequisites
- A recent Loom-enabled JDK (set `JAVA_HOME` to it).
- Maven (for build and runtime classpath).
- curl for the smoke test; jbang if you want to run the optional wrk/Hyperfoil test.

How to build and run (minimal)

1) Build (from repository root):

```bash
# build quickly (skip tests)
mvn -DskipTests package
```

2) Start the server (adjust `JAVA_HOME`):

```bash
# compute runtime classpath for example-echo
CP_LINE=$(mvn -q -pl example-echo dependency:build-classpath -DincludeScope=runtime -DskipTests | tail -n1)
CP="core/target/netty-virtualthread-core-1.0-SNAPSHOT.jar:example-echo/target/example-echo-1.0-SNAPSHOT.jar:${CP_LINE}"

# start server in background (use your Loom JDK)
export JAVA_HOME=/path/to/loom/build/linux-x86_64-server-release/jdk/
"$JAVA_HOME/bin/java" --enable-preview \
  -Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler \
  -Djdk.pollerMode=3 \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  -cp "$CP" io.netty.loom.example.EchoServer &

# note the PID in $!
```

Quick smoke & load tests

- Simple curl smoke test:

```bash
curl -v http://localhost:8080/
```

- Short wrk/Hyperfoil test via jbang (uses the Hyperfoil catalog):

```bash
# short 5s test
jbang wrk@hyperfoil -t1 -c10 -d5s -R10 --latency http://localhost:8080/
```

Notes
- `-Djdk.pollerMode=3` enables per-carrier pollers (useful for blocking I/O tests). Remove or change if you don't need it.
- Use a Loom-enabled JDK and set `JAVA_HOME` accordingly.
- If anything fails, paste the exact output here and I'll help debug.
