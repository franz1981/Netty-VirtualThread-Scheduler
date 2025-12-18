# Netty VirtualThread Scheduler

## Introduction
This project provides an integration between Java Virtual Threads (Project Loom) and Netty's event loop, enabling low-overhead execution of blocking or CPU-bound work started from the Netty event loop without the usual extra thread hand-offs.

At a high level:
- Each Netty event loop is backed by a virtual thread (the "event loop virtual thread").
- Each such virtual thread is executed on a dedicated carrier platform thread.
- Virtual threads created from the event-loop-specific ThreadFactory returned by the group will be associated with the same EventLoopScheduler and, when possible, will run on the same carrier platform thread as the event loop.

This allows code that must block (for example, blocking I/O or synchronous library calls) to be executed without moving work between unrelated threads, reducing wake-ups and improving cache locality compared to offloading to an external thread pool.

## Key behavior (user-facing)
- Create a `VirtualMultithreadIoEventLoopGroup` like any other Netty `EventLoopGroup`. It behaves like a `MultiThreadIoEventLoopGroup` from the Netty API, but the event loops are driven by virtual threads and coordinated with carrier platform threads by a per-event-loop `EventLoopScheduler`.
- Call `group.vThreadFactory()` to obtain a `ThreadFactory` that creates virtual threads tied to an `EventLoopScheduler` of the group. When those virtual threads block, the scheduler parks them and resumes them later using the carrier thread.
- Virtual threads created via the group's `vThreadFactory()` will attempt to inherit the scheduler and run with low-overhead handoffs back to the event loop when continuing work.
- Virtual threads created with `Thread.ofVirtual().factory()` (the JVM default factory) do NOT automatically inherit the group's scheduler.
- The library requires a custom global virtual thread scheduler implementation to be installed: set `-Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler` (or use the convenience helpers in the code/tests that set up the scheduler).
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

The easiest way to get started is using the provided dev container configuration, which uses the same Shipilev Loom container image as our CI:

1. Install [Docker](https://www.docker.com/products/docker-desktop) and [VS Code](https://code.visualstudio.com/)
2. Install the [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) in VS Code
3. Open this repository in VS Code
4. When prompted, click "Reopen in Container" (or run the command "Dev Containers: Reopen in Container")

The dev container will automatically:
- Use the `shipilev/openjdk:loom` Docker image with a fresh Loom build
- Install Maven and other dependencies
- Configure the Java environment

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
  `-Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler`

- Some blocking I/O integrations rely on per-carrier pollers. The code checks `jdk.pollerMode` and expects value `3` for per-carrier pollers. This can be controlled via JVM flags or defaults depending on your JVM version.

- Use `group.vThreadFactory()` from inside Netty handlers if you want the spawned virtual thread to be associated with the same event loop scheduler as the handler's event loop.

- If you need the virtual thread to inherit the scheduler when forking tasks via StructuredTaskScope, pass the group's `vThreadFactory()` to the scope's `withThreadFactory(...)`.

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
  -Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler

## References
- Project Loom (OpenJDK): https://openjdk.org/projects/loom/
- Netty Project: https://netty.io/

---
For more details, see the source code and benchmark results in the respective modules.
