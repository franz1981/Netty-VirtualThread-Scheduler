# example-echo

A tiny HTTP example that shows how to use VirtualMultithreadIoEventLoopGroup and its
`vThreadFactory()` to spawn virtual threads from Netty handlers.

Prerequisites
- A recent Loom-enabled JDK (set `JAVA_HOME` to it).
- Maven (for build and runtime classpath).
- curl for the smoke test; jbang if you want to run the optional wrk/Hyperfoil test.

How to build and run (minimal)

Note: This project requires a Loom-enabled JDK for both building and running because
the code targets a recent Java release with preview features. Set `JAVA_HOME` to your
Loom JDK before running the build and the server.

1) Build (from repository root)

```bash
# point to a Loom-enabled JDK first
export JAVA_HOME=/path/to/loom/build/linux-x86_64-server-release/jdk/

# build the example-echo module and produce the shaded (uber) jar
mvn -DskipTests -pl example-echo -am package
```

The shade plugin in `example-echo/pom.xml` creates an executable jar in
`example-echo/target/` (artifact name: `example-echo-<version>.jar`).

2) Start the server (using the same Loom JDK)

```bash
"$JAVA_HOME/bin/java" --enable-preview \
  -Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler \
  -jar example-echo/target/example-echo-1.0-SNAPSHOT.jar &

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
jbang wrk@hyperfoil -t1 -c10 -d5s http://localhost:8080/
```

Notes
- `-Djdk.pollerMode=3` is optional; only add it if you need per-carrier pollers for blocking I/O tests.
- Use a Loom-enabled JDK and set `JAVA_HOME` accordingly before build and run.
- If anything fails, paste the exact output here and I'll help debug.
