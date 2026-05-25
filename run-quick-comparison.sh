#!/bin/bash
# Quick comparison: baseline vs WS aggressive vs FJP
# CPU-bound (2 cores) and I/O-bound (8 cores), max TPS + fixed rate
set -euo pipefail

export JAVA_HOME=/home/forked_franz/CLionProjects/loom/build/linux-x86_64-server-release/jdk
RUNS=${1:-3}

kill_ports() { fuser -k 8080/tcp 8081/tcp 2>/dev/null || true; sleep 3; }

run() {
  local label=$1 mode=$2 heap=$3 threads=$4 conns=$5 mock_ms=$6
  local scpu=$7 mcpu=$8 lcpu=$9 mt=${10} rate=${11:-}
  shift 11; local extra="${1:-}"
  local -a jvm=(); [[ -n "$extra" ]] && jvm=(--jvm-args "$extra")
  local -a rarg=(); [[ -n "$rate" ]] && rarg=(--rate "$rate")

  kill_ports
  local dir="/tmp/quick-$$/${label}"
  JAVA_OPTS="$heap" bash benchmark-runner/scripts/run-benchmark.sh \
    --mode "$mode" --io epoll --threads "$threads" \
    --warmup 10s --total-duration 40s --no-pidstat --perf-stat \
    --connections "$conns" --load-threads 4 --load-cpuset "$lcpu" \
    --server-cpuset "$scpu" --mock-cpuset "$mcpu" \
    --mock-threads "$mt" --mock-think-time "$mock_ms" \
    "${rarg[@]}" "${jvm[@]}" --output-dir "$dir" 2>&1 > /dev/null

  local rps=$(grep "Requests/sec" "$dir/wrk-results.txt" 2>/dev/null | awk '{print $2}')
  local p50=$(grep "50.000%" "$dir/wrk-results.txt" 2>/dev/null | awk '{print $2}')
  local p99=$(grep "99.000%" "$dir/wrk-results.txt" 2>/dev/null | awk '{print $2}')
  local tc=$(grep 'task-clock' "$dir/perf-stat.txt" 2>/dev/null | awk '{print $1}')
  local el=$(grep 'seconds time elapsed' "$dir/perf-stat.txt" 2>/dev/null | awk '{print $1}')
  local cpus=$(python3 -c "print(f'{float(\"${tc}\".replace(\",\",\"\"))/float(\"${el}\")/1000:.2f}')" 2>/dev/null || echo "?")
  printf "  %-25s %8s  p50=%-8s p99=%-10s CPUs=%s\n" "$label" "${rps:-?}" "${p50:-?}" "${p99:-?}" "$cpus"
}

WS_AGGR="-Dio.netty.loom.workstealing.enabled=true -Dio.netty.loom.workstealing.steal.queue=2 -Dio.netty.loom.workstealing.wake.queue=8"

echo "=== CPU-bound (2 cores, 1ms mock, 100 conns) ==="
echo ""
echo "--- Max TPS ---"
for r in $(seq 1 $RUNS); do
  run "baseline-max-r$r"   NETTY_SCHEDULER   "-Xms4g -Xmx4g" 2 100 1 2,3 6,7 0,1,4,5 2 "" ""
  run "ws-aggr-max-r$r"    NETTY_SCHEDULER   "-Xms4g -Xmx4g" 2 100 1 2,3 6,7 0,1,4,5 2 "" "$WS_AGGR"
  run "spin256-max-r$r"    NETTY_SCHEDULER   "-Xms4g -Xmx4g" 2 100 1 2,3 6,7 0,1,4,5 2 "" "-Dio.netty.loom.idleSpins=256"
  run "fjp-max-r$r"        NON_VIRTUAL_NETTY "-Xms4g -Xmx4g" 2 100 1 2,3 6,7 0,1,4,5 2 "" ""
done

echo ""
echo "--- Fixed rate 50K ---"
for r in $(seq 1 $RUNS); do
  run "baseline-50k-r$r"   NETTY_SCHEDULER   "-Xms4g -Xmx4g" 2 100 1 2,3 6,7 0,1,4,5 2 50000 ""
  run "ws-aggr-50k-r$r"    NETTY_SCHEDULER   "-Xms4g -Xmx4g" 2 100 1 2,3 6,7 0,1,4,5 2 50000 "$WS_AGGR"
  run "spin256-50k-r$r"    NETTY_SCHEDULER   "-Xms4g -Xmx4g" 2 100 1 2,3 6,7 0,1,4,5 2 50000 "-Dio.netty.loom.idleSpins=256"
  run "fjp-50k-r$r"        NON_VIRTUAL_NETTY "-Xms4g -Xmx4g" 2 100 1 2,3 6,7 0,1,4,5 2 50000 ""
done

echo ""
echo "=== I/O-bound (8 cores, 30ms mock, 10K conns) ==="
echo ""
echo "--- Max TPS ---"
for r in $(seq 1 $RUNS); do
  run "io-baseline-max-r$r" NETTY_SCHEDULER   "-Xms8g -Xmx8g" 8 10000 30 8,9,10,11,12,13,14,15 4,5,6,7 0,1,2,3 4 "" ""
  run "io-ws-aggr-max-r$r"  NETTY_SCHEDULER   "-Xms8g -Xmx8g" 8 10000 30 8,9,10,11,12,13,14,15 4,5,6,7 0,1,2,3 4 "" "$WS_AGGR"
  run "io-fjp-max-r$r"      NON_VIRTUAL_NETTY "-Xms8g -Xmx8g" 8 10000 30 8,9,10,11,12,13,14,15 4,5,6,7 0,1,2,3 4 "" ""
done

echo ""
echo "Done!"
