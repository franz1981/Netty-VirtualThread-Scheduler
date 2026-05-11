# Netty VirtualThread Scheduler

## Introduction
This project provides an integration between Java Virtual Threads (Project Loom) and Netty's event loop, enabling low-overhead execution of blocking or CPU-bound work started from the Netty event loop without the usual extra thread hand-offs.

At a high level:
- Each Netty event loop is backed by a virtual thread (the "event loop virtual thread").
- Each such virtual thread is executed on a dedicated carrier platform thread.
- Virtual threads created from the event-loop-specific ThreadFactory returned by the group will be associated with the same EventLoopScheduler and, when possible, will run on the same carrier platform thread as the event loop.
- You can select FIFO scheduling when constructing the group by passing `EventLoopSchedulerType` (default is FIFO).

This allows code that must block (for example, blocking I/O or synchronous library calls) to be executed without moving work between unrelated threads, reducing wake-ups and improving cache locality compared to offloading to an external thread pool.

## Key behavior (user-facing)
- Create a `VirtualMultithreadIoEventLoopGroup` like any other Netty `EventLoopGroup`. It behaves like a `MultiThreadIoEventLoopGroup` from the Netty API, but the event loops are driven by virtual threads and coordinated with carrier platform threads by a per-event-loop `EventLoopScheduler` (FIFO).
- Call `group.vThreadFactory()` to obtain a `ThreadFactory` that creates virtual threads tied to an `EventLoopScheduler` of the group. When those virtual threads block, the scheduler parks them and resumes them later using the carrier thread.
- Virtual threads created via the group's `vThreadFactory()` will attempt to inherit the scheduler and run with low-overhead handoffs back to the event loop when continuing work.
- Virtual threads created with `Thread.ofVirtual().factory()` (the JVM default factory) do NOT automatically inherit the group's scheduler.
- The library requires a custom global virtual thread scheduler implementation to be installed: set `-Djdk.virtualThreadScheduler.implClass=io.netty.loom.spi.NettyScheduler` (or use the convenience helpers in the code/tests that set up the scheduler).
- Blocking I/O support that relies on per-carrier pollers currently depends on the JVM poller mode (the code checks `jdk.pollerMode==3`). See notes below.

## When to use this
- You have code that must perform blocking operations from a Netty handler and you want to avoid an extra thread hand-off.
- You want fewer CPU wake-ups and better cache locality by keeping related work on the same carrier platform thread.

Caveats:
- This project leverages experimental JVM features (Project Loom) and assumes recent Java versions (Java 21+ recommended).
- You must install the Netty-specific scheduler (see above) for `VirtualMultithreadIoEventLoopGroup` to be usable.

## Usage example (simple)

See the runnable example and step-by-step instructions in the example module:

- [`example-echo/README.md`](example-echo/README.md)

(That README contains the minimal build and run commands, including how to start the example server and run a quick curl smoke test.)

## About the Loom build used

This work targets Project Loom features and was developed and tested against very recent OpenJDK / Loom builds. 

### Option 1: Dev Containers (Recommended for local development)

The easiest way to get started is using the provided dev container configuration, which uses the same Shipilev Loom container image as our CI.

#### Using with VS Code

1. Install [Docker](https://www.docker.com/products/docker-desktop) and [VS Code](https://code.visualstudio.com/)
2. Install the [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) in VS Code
3. Open this repository in VS Code
4. When prompted, click "Reopen in Container" (or run the command "Dev Containers: Reopen in Container")

#### Using with IntelliJ IDEA

1. Install [Docker](https://www.docker.com/products/docker-desktop) and [IntelliJ IDEA](https://www.jetbrains.com/idea/)
2. Open this repository in IntelliJ IDEA
3. IDEA will detect the `.devcontainer/devcontainer.json` configuration
4. Click "Create Dev Container and Mount Sources" when prompted (or go to **File > Remote Development > Dev Containers**)

#### What the dev container provides

The dev container will automatically:
- Use the `shipilev/openjdk:loom` Docker image with a fresh Loom build
- Install Maven and other dependencies
- Configure the Java environment (JAVA_HOME set to `/opt/jdk`)
- Forward port 8080 for the example-echo server

No need to download or build JDK manually!

### Option 2: Manual Loom JDK Setup

If you don't want to use dev containers, you can manually set up a Loom JDK. A convenient set of prebuilt Loom-enabled JDK images is available from Shipilev's builds:

- https://builds.shipilev.net/openjdk-jdk-loom/

Follow that site to download a suitable JDK image for your platform and point `JAVA_HOME` to the JDK image before running the project. Example:

```sh
export JAVA_HOME=/path/to/loom/build/linux-x86_64-server-release/jdk/
```

If you prefer to build from the OpenJDK `loom` repository yourself, the upstream source is:

- https://github.com/openjdk/loom

If you have a local build of the latest Loom-enabled OpenJDK, point `JAVA_HOME` to that build before running tests and benchmarks. Example:

```sh
export JAVA_HOME=/path/to/your/loom/build/linux-x86_64-server-release/jdk/
mvn clean install
```

## Integration tips and runtime flags
- Install the Netty scheduler as the JVM virtual-thread scheduler with:
  `-Djdk.virtualThreadScheduler.implClass=io.netty.loom.spi.NettyScheduler`

- Some blocking I/O integrations rely on per-carrier pollers. The code checks `jdk.pollerMode` and expects value `3` for per-carrier pollers. This can be controlled via JVM flags or defaults depending on your JVM version.

- Use `group.vThreadFactory()` from inside Netty handlers if you want the spawned virtual thread to be associated with the same event loop scheduler as the handler's event loop.

- If you need the virtual thread to inherit the scheduler when forking tasks via StructuredTaskScope, pass the group's `vThreadFactory()` to the scope's `withThreadFactory(...)`.

## Classloader requirements for fat JARs and application servers

The JVM loads the custom virtual-thread scheduler via the **system classloader**
(`ClassLoader.getSystemClassLoader()`). Many frameworks — Spring Boot, OpenLiberty,
WildFly, and others — use isolated classloaders that hide application JARs from
the system classloader. If you deploy this library inside such a framework, the
JVM will fail to find `NettyScheduler` at startup.

The library is split into two modules to handle this:

| Module | Contains | Must be visible to |
|---|---|---|
| `netty-virtualthread-bootstrap` | `NettyScheduler` shim + `NettySchedulerSpi` interface (no dependencies) | System classloader |
| `netty-virtualthread-core` | Real scheduling logic (`NettySchedulerProviderImpl`) | Thread-context classloader (TCCL) |

`NettyScheduler` discovers the real implementation at runtime via `ServiceLoader`
using the TCCL — the same pattern JDBC uses to discover drivers. As long as the
bootstrap classes are on the system classpath and the core JAR is visible to the
TCCL, discovery works regardless of classloader hierarchy.

### Plain classpath (shade plugin, flat classpath)

No special configuration needed. Both modules are on the same classpath.

### Spring Boot fat JAR

Spring Boot packages dependencies under `BOOT-INF/lib/`, invisible to the system
classloader. Use Multi-Release JAR (MRJAR) entries to place the bootstrap classes
at the outer level of the fat JAR:

```xml
<!-- 1. Mark the JAR as Multi-Release -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-jar-plugin</artifactId>
    <configuration>
        <archive>
            <manifestEntries>
                <Multi-Release>true</Multi-Release>
            </manifestEntries>
        </archive>
    </configuration>
</plugin>

<!-- 2. Unpack bootstrap classes into META-INF/versions/27/ -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>unpack-bootstrap-scheduler</id>
            <phase>prepare-package</phase>
            <goals>
                <goal>unpack</goal>
            </goals>
            <configuration>
                <artifactItems>
                    <artifactItem>
                        <groupId>io.netty.loom</groupId>
                        <artifactId>netty-virtualthread-bootstrap</artifactId>
                        <version>${netty-loom.version}</version>
                        <type>jar</type>
                        <includes>io/netty/loom/spi/**</includes>
                        <outputDirectory>${project.build.outputDirectory}/META-INF/versions/27</outputDirectory>
                    </artifactItem>
                </artifactItems>
            </configuration>
        </execution>
    </executions>
</plugin>

<!-- 3. Exclude bootstrap from BOOT-INF/lib/ to avoid duplicate class definitions -->
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <excludes>
            <exclude>
                <groupId>io.netty.loom</groupId>
                <artifactId>netty-virtualthread-bootstrap</artifactId>
            </exclude>
        </excludes>
    </configuration>
</plugin>
```

The resulting JAR layout:

```
app.jar
├── META-INF/MANIFEST.MF          (Multi-Release: true)
├── META-INF/versions/27/
│   └── io/netty/loom/spi/
│       ├── NettyScheduler.class
│       └── NettySchedulerSpi.class
└── BOOT-INF/lib/
    └── netty-virtualthread-core-*.jar
```

On Java 27+, the system classloader sees the bootstrap classes via MRJAR. The
core implementation is discovered via ServiceLoader through Spring Boot's
`LaunchedClassLoader` (which is set as the TCCL).

Credit: [dreamlike-ocean](https://github.com/dreamlike-ocean) for identifying and
fixing this issue.

### OpenLiberty, WildFly, and other application servers

The same principle applies: the bootstrap JAR must be visible to the system
classloader. The mechanism varies by server:

- **OpenLiberty**: configure the bootstrap JAR as a
  [shared library](https://openliberty.io/docs/latest/class-loader-library-config.html)
  at server scope, or add it via `jvm.options`:
  ```
  -Xbootclasspath/a:/path/to/netty-virtualthread-bootstrap.jar
  ```
- **WildFly**: register the bootstrap JAR as a
  [global module](https://docs.wildfly.org/latest/Developer_Guide.html#global-modules),
  or add it to `JBOSS_MODULEPATH`.
- **Generic**: place the bootstrap JAR on the JVM's `-classpath` or
  `-Xbootclasspath/a:` separately from the application archive. The core JAR
  stays inside the application's deployment unit.

In all cases, `netty-virtualthread-core` should be packaged inside the
application (WAR/EAR) as a normal dependency — the TCCL will be the application
classloader at the point when `NettyScheduler` discovers it.

## JFR events
Netty Loom publishes custom Java Flight Recorder events (disabled by default) for scheduler activity:

- `io.netty.loom.NettyRunIo`
- `io.netty.loom.NettyRunTasks`
- `io.netty.loom.VirtualThreadTaskRuns`
- `io.netty.loom.VirtualThreadTaskRun`
- `io.netty.loom.VirtualThreadTaskSubmit`

The benchmark runner can enable these events selectively via `ENABLE_JFR=true` and `JFR_EVENTS=...`. See `benchmark-runner/README.md` for details.

## Build and Run
This project uses Maven for build and dependency management.

1. Build the project:

```sh
mvn clean install
```

2. Run the benchmarks (optional):

```sh
cd benchmarks
mvn clean install
java -jar target/benchmarks.jar
```

## Prerequisites
- Maven 3.6+
- To use the `VirtualMultithreadIoEventLoopGroup` set the JVM property to install the Netty scheduler:
  -Djdk.virtualThreadScheduler.implClass=io.netty.loom.spi.NettyScheduler

## References
- Project Loom (OpenJDK): https://openjdk.org/projects/loom/
- Netty Project: https://netty.io/

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

All source files include the Apache License header, and license compliance is enforced via the Spotless Maven plugin during the build process.

---
For more details, see the source code and benchmark results in the respective modules.
