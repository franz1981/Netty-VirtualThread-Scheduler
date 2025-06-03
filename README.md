# Netty VirtualThread Scheduler

## Introduction
This project provides an advanced integration between Java Virtual Threads (Project Loom) and Netty's event loop, enabling seamless execution of blocking operations within the Netty event loop itself. Unlike standard approaches, which require offloading blocking tasks to external thread pools (incurring multiple thread hand-offs), this scheduler allows blocking code to run directly on the event loop's carrier thread, leveraging the virtual thread execution model.

## Motivation
In traditional Netty applications, blocking operations (e.g., JDBC, file I/O) must not run on the event loop. The usual workaround is:
1. Offload the blocking task to an external thread pool (often using the default virtual thread scheduler).
2. Once complete, hand control back to the Netty event loop to continue processing (e.g., send a response).

This process involves at least two thread hand-offs, which not only increases latency and complexity, but also wastes CPU cycles due to waking up both the external thread pool and the event loop again. Additionally, moving data between threads harms cache locality, reducing cache friendliness and overall performance.

## Technical Approach
- The Netty event loop runs as a special, long-lived virtual thread.
- Blocking operations issued from the event loop are executed as continuations, scheduled to run on the same platform thread (the "carrier")—unless work-stealing occurs (not implemented at the moment).
- Any virtual thread created from the event loop will, by default, run on the same carrier platform thread (again, unless work-stealing is introduced).
- This enables blocking libraries to be used transparently, without extra thread pools or hand-offs.

## Comparison Table

| Aspect                | Standard Netty + Loom (Default)         | Netty with VirtualThread Scheduler (This Project) |
|-----------------------|-----------------------------------------|--------------------------------------------------|
| Blocking Operation    | Offloaded to external thread pool       | Runs as continuation on event loop carrier        |
| Thread Hand-offs      | 2+                                      | 0                                                |
| Cache Friendliness    | Poor (data moves between threads)        | High (data stays on carrier thread)               |
| CPU Wakeups           | More (wakes both pools)                  | Fewer (single carrier thread)                     |

## Architecture Diagrams

```
Standard Netty + Loom (Default):

┌──────────────┐   offload   ┌────────────────────────────┐   callback   ┌──────────────┐
│  EventLoop   │───────────▶│ Virtual Thread (Scheduler) │────────────▶│  EventLoop   │
│ (OS Thread)  │             │ (External Thread Pool)     │              │ (OS Thread)  │
└──────────────┘             └────────────────────────────┘              └──────────────┘

Netty with VirtualThread Scheduler:

┌────────────────────────────────────────────┐
│         Platform Thread (Carrier)          │
│  ┌──────────────────────────────────────┐  │
│  │   EventLoop (Virtual Thread)         │  │
│  │   (runs on carrier platform thread)  │  │
│  │   ────────────────────────────────   │  │
│  │   Blocking Operation                 │  │
│  │   (Continuation, same platform)      │  │
│  └──────────────────────────────────────┘  │
└────────────────────────────────────────────┘
```

## Build and Run

This project uses Maven for build and dependency management.

1. **Build the project:**
   ```sh
   mvn clean install
   ```
2. **Run Benchmarks:**
   ```sh
   cd benchmarks
   mvn clean install
   java -jar target/benchmarks.jar
   ```

## Prerequisites
- Java 21 or newer (with Loom support)
- Maven 3.6+

## References
- [Project Loom (OpenJDK)](https://openjdk.org/projects/loom/)
- [Netty Project](https://netty.io/)

---
For more details, see the source code and benchmark results in the respective modules.
