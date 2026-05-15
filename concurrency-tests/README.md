# Concurrency Tests

JCStress tests verifying the sleep/wakeup coordination protocol used by the pinned poller `BlockingPollGuard`.

## What these tests verify

When a pinned poller decides whether to enter blocking I/O (e.g. `epoll_wait`), and a producer concurrently submits external work (a VT continuation to the scheduler's run queue), the two sides must not miss each other. A missed wakeup means: the poller blocks while work sits in the queue and no wakeup fires — the carrier misses a chance to drain the run queue.

The tests model the [Seastar store-barrier-load pattern](https://www.scylladb.com/2018/02/15/memory-barriers-seastar-linux/) used by [`BlockingPollGuard`](src/main/java/io/netty/loom/concurrent/BlockingPollGuard.java):

```
Poller (consumer):                     Scheduler (producer):
  sleeping = true   [volatile store]     externalWork = true  [volatile store]
  StoreLoad barrier                      StoreLoad barrier
  read externalWork [volatile load]      read sleeping        [volatile load]
  if empty → block                       if sleeping → wakeup
```

The symmetric store-barrier-load ensures at least one side always sees the other's store. See the [pinned poller documentation](../README.md#writing-a-custom-pinned-poller) for how this fits into the scheduler's API.

## Tests

| Test | Guard? | Expected |
|------|--------|----------|
| [`BlockingPollGuardTest`](src/main/java/io/netty/loom/concurrent/BlockingPollGuardTest.java) | Yes | `(false, false)` FORBIDDEN — work is always seen or wakeup fires |
| [`BlockingPollGuardBrokenTest`](src/main/java/io/netty/loom/concurrent/BlockingPollGuardBrokenTest.java) | No | `(false, true)` INTERESTING — poller blocks with no wakeup |

### Why exitPoll is omitted from the guard test

In real code, a blocking I/O call (`epoll_wait`) sits between `enterPoll` and `exitPoll`. The blocking call holds the sleeping flag open while the poller is actually blocked, so the producer can see it and fire the wakeup.

JCStress can't model a blocking call — actors run to completion instantly. Calling `exitPoll` would write `sleeping=false` immediately after `enterPoll` wrote `sleeping=true`. The producer would see the second write (false) instead of the first (true), missing the window where the poller was actually sleeping. This creates a false "missed wakeup" that doesn't occur in real code.

By omitting `exitPoll`, the test keeps the sleeping flag true when the poller commits to blocking — exactly the state during a real blocking I/O call.

## BlockingPollGuard

[`BlockingPollGuard`](src/main/java/io/netty/loom/concurrent/BlockingPollGuard.java) encapsulates the store-barrier-load protocol so users don't implement it themselves. See the class javadoc for the API and usage example.

## How to build and run

```bash
export JAVA_HOME=/path/to/loom-jdk

# build the uber-JAR
mvn -B package --file concurrency-tests/pom.xml -P '!dev'

# run all tests
$JAVA_HOME/bin/java --enable-preview -jar concurrency-tests/target/jcstress.jar

# run only the guard tests
$JAVA_HOME/bin/java --enable-preview -jar concurrency-tests/target/jcstress.jar -t "BlockingPollGuard"

# verbose output (per-fork results)
$JAVA_HOME/bin/java --enable-preview -jar concurrency-tests/target/jcstress.jar -t "BlockingPollGuard" -v
```

The HTML report is generated at `results/index.html`.

## Expected results

**With guard (`BlockingPollGuardTest`):**

```
        RESULT     SAMPLES     FREQ      EXPECT  DESCRIPTION
  false, false           0    0.00%   Forbidden  MISSED WAKEUP — poller didn't see work AND no wakeup
   false, true  75,481,609   43.88%  Acceptable  No wakeup, poller saw work — no wakeup needed
   true, false  95,723,282   55.65%  Acceptable  Wakeup fired, poller didn't see work — wakeup will interrupt
    true, true     804,600    0.47%  Acceptable  Wakeup fired AND poller saw work — redundant wakeup
```

`(false, false)` = 0 observations. Work is always either seen by the poller or the wakeup fires.

**Without guard (`BlockingPollGuardBrokenTest`):**

```
        RESULT      SAMPLES     FREQ       EXPECT  DESCRIPTION
  false, false   26,658,256    5.81%   Acceptable  No wakeup AND poller won't block — safe
   false, true  432,163,395   94.19%  Interesting  LOST SIGNAL — poller blocks, no wakeup
   true, false            0    0.00%   Acceptable  Wakeup called AND poller won't block — safe
    true, true            0    0.00%   Acceptable  Wakeup called AND poller blocks — safe
```

`(false, true)` = 94% of observations. The poller blocks with a VT continuation in the queue and no wakeup fires — the carrier misses the chance to execute the continuation.

## Background reading

- [Seastar memory barriers](https://www.scylladb.com/2018/02/15/memory-barriers-seastar-linux/) — the symmetric store-barrier-load pattern
- [Mechanical-sympathy discussion](https://groups.google.com/g/mechanical-sympathy/c/yKQNVFAjui0/m/NAhfyjT-BAAJ) — why `Condition.signal()` deadlocks when it arrives before `Condition.await()`
- [Viktor Klang's actor](https://gist.github.com/viktorklang/2557678) — the atomic-flag-with-recheck pattern
- [JCTools blocking strategies](https://github.com/JCTools/JCTools/tree/64c94eded4bf68e0e70ac73f50617db77a842f84/jctools-experimental/src/main/java/org/jctools/queues/blocking) — `TakeStrategy` interface separating queue from blocking mechanism
