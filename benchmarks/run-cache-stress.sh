#!/bin/bash
# Cache stress benchmark: compare vanilla FJP vs NettyScheduler across NUMA configurations.
#
# Usage: ./benchmarks/run-cache-stress.sh [single|cross|all]
#   single  — one NUMA node (CPUs 0-7, physical cores only)
#   cross   — both NUMA nodes (CPUs 0-15, physical cores only)
#   all     — run both configurations
#
# Prerequisites:
#   export JAVA_HOME=/path/to/loom/jdk
#   mvn -pl benchmarks -am package -DskipTests

set -euo pipefail

JAVA="${JAVA_HOME:?Set JAVA_HOME}/bin/java"
JAR="benchmarks/target/benchmarks.jar"
BENCH="CacheStressBenchmark"
COMMON_ARGS="--enable-preview --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Djdk.trackAllThreads=false"
NETTY_ARGS="-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler"
WS_ARGS="-Dio.netty.loom.workstealing.enabled=true"
JMH_ARGS="-wi 3 -i 5 -f 2 -t 1"

MODE="${1:-all}"
RESULTS_DIR="benchmarks/results/cache-stress-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

run_bench() {
    local label="$1"
    local taskset_cpus="$2"
    local jvm_args="$3"
    local out="$RESULTS_DIR/${label}.txt"

    echo "=== $label ==="
    echo "  taskset: $taskset_cpus"
    echo "  jvm_args: $jvm_args"
    echo "  output: $out"
    echo ""

    taskset -c "$taskset_cpus" "$JAVA" $jvm_args \
        -jar "$JAR" "$BENCH" $JMH_ARGS \
        -jvmArgs "$COMMON_ARGS $jvm_args" \
        2>&1 | tee "$out"

    echo ""
}

run_single_numa() {
    echo ">>> Single NUMA node (CPUs 0-7) <<<"
    echo ""

    run_bench "single-fjp" "0-7" "$COMMON_ARGS"

    run_bench "single-netty" "0-7" "$COMMON_ARGS $NETTY_ARGS"

    run_bench "single-netty-ws" "0-7" "$COMMON_ARGS $NETTY_ARGS $WS_ARGS"
}

run_cross_numa() {
    echo ">>> Cross NUMA (CPUs 0-15, physical cores from both nodes) <<<"
    echo ""

    run_bench "cross-fjp" "0-15" "$COMMON_ARGS"

    run_bench "cross-netty" "0-15" "$COMMON_ARGS $NETTY_ARGS"

    run_bench "cross-netty-ws" "0-15" "$COMMON_ARGS $NETTY_ARGS $WS_ARGS"

    run_bench "cross-netty-ws-local" "0-15" "$COMMON_ARGS $NETTY_ARGS $WS_ARGS -Dio.netty.loom.workstealing.scope=CLUSTER_LOCAL"
}

case "$MODE" in
    single) run_single_numa ;;
    cross)  run_cross_numa ;;
    all)    run_single_numa; run_cross_numa ;;
    *)      echo "Usage: $0 [single|cross|all]"; exit 1 ;;
esac

echo "Results in: $RESULTS_DIR"
