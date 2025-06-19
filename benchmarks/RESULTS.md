This repo's branch is created to test the performance of custom pollers as implemented in https://github.com/franz1981/loom/tree/custom_poller.

Running the existing PollerBenchmark vs https://github.com/franz1981/loom/commit/1ffa937aaf819b04b7dc1c0dd09f03fc1c3e61fa we get the following results

HW: `AMD Ryzen 9 7950X 16-Core Processor` 
OS: `Linux fedora.fritz.box 6.14.9-300.fc42.x86_64 #1 SMP PREEMPT_DYNAMIC Thu May 29 14:27:53 UTC 2025 x86_64 GNU/Linux`

TuneD network-latency profile with turbo boost disabled (i.e. 4.5 GHz fixed clock speed):
```
Benchmark                                (bytes)  (customPoller)  (customPollerSpinWait)  (port)  (receiveBufferSize)  (sendBufferSize)   Mode  Cnt    Score    Error   Units
PollerBenchmark.readWrite                    128           false                   false       0               131072            131072  thrpt   10   44.041 ±  0.405  ops/ms
PollerBenchmark.readWrite                    128           false                    true       0               131072            131072  thrpt   10   54.011 ±  3.598  ops/ms
PollerBenchmark.readWrite                    128            true                   false       0               131072            131072  thrpt   10   53.138 ±  1.037  ops/ms
PollerBenchmark.readWrite                    128            true                    true       0               131072            131072  thrpt   10   67.331 ±  0.945  ops/ms
PollerBenchmark.readWrite                   1024           false                   false       0               131072            131072  thrpt   10   44.159 ±  0.329  ops/ms
PollerBenchmark.readWrite                   1024           false                    true       0               131072            131072  thrpt   10   54.303 ±  1.337  ops/ms
PollerBenchmark.readWrite                   1024            true                   false       0               131072            131072  thrpt   10   53.227 ±  0.650  ops/ms
PollerBenchmark.readWrite                   1024            true                    true       0               131072            131072  thrpt   10   67.466 ±  0.604  ops/ms
PollerBenchmark.readWrite                 128000           false                   false       0               131072            131072  thrpt   10   28.805 ±  0.538  ops/ms
PollerBenchmark.readWrite                 128000           false                    true       0               131072            131072  thrpt   10   35.292 ±  0.415  ops/ms
PollerBenchmark.readWrite                 128000            true                   false       0               131072            131072  thrpt   10   35.248 ±  1.582  ops/ms
PollerBenchmark.readWrite                 128000            true                    true       0               131072            131072  thrpt   10   35.838 ±  0.492  ops/ms
```
which shows that for small write/read sizes the custom poller is faster than the existing one, because it saves the hand-off from the master poller InnocuousThread to the sub-poller running in the default scheduler thread.
This is happening because with small read/write the additional signaling overhead is visible enough compared to the read/write costs.

For bigger I/O (e.g. 128000), the way the custom scheduler wait its tasks (including unparking the blocked reader) e.g. parking or spin waiting determines how impactful the signaling is.

In term of dynamic:
1. `customPoller = false`: master poller unpark the sub-poller virtual thread in the default scheduler thread, which unpark the reader in the custom scheduler thread
2. `customPoller = true`: master poller unpark the sub-poller virtual thread in the custom scheduler thread, which submit (without awaking the carrier) the reader to resume

This means that the sub-poller performs more CPU intensive work (i.e. its poll loop + syscalls epoll_ctl), but it saves awaking/signaling the custom scheduler carrier thread, similar to what
the default scheduler does OOTTB (see https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/ForkJoinPool.java#L2616-L2619).

One evidence of this is in the number of cpu migrations and CPU time spent in the benchmark without custom poller and 128 bytes:
```
           6725.84 msec task-clock                       #    1.028 CPUs utilized             
            516559      context-switches                 #   76.802 K/sec                     
              9389      cpu-migrations                   #    1.396 K/sec                     

       6.544261113 seconds time elapsed

       5.705851000 seconds user
       6.983435000 seconds sys
```
with custom poller:
```
           6137.69 msec task-clock                       #    0.938 CPUs utilized             
            428093      context-switches                 #   69.748 K/sec                     
               397      cpu-migrations                   #   64.682 /sec 

       6.542441819 seconds time elapsed

       5.061246000 seconds user
       6.120752000 seconds sys
```
which shows that for with 128 bytes the custom poller is 30% faster with 91% of cpu usage (`task-clock`).
