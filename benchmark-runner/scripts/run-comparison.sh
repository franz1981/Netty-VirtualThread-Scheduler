#!/bin/bash
#
# Comparison Benchmark: MPSC scheduler vs FJP (VIRTUAL_NETTY mode)
#
# Runs both schedulers at multiple rate levels + max throughput,
# with multiple rounds per level. Produces a results summary.
#
# Usage:
#   ./run-comparison.sh [OPTIONS]
#
# Options:
#   --rates <list>         Comma-separated rates + "max" (default: 30000,40000,50000,60000,max)
#   --rounds <n>           Rounds per rate level (default: 3)
#   --output-dir <dir>     Output directory (default: /tmp/cpu-bound-comparison)
#   --schedulers <list>    Comma-separated: mpsc,fjp (default: mpsc,fjp)
#   --server-cpuset <c>    Server CPUs (default: 3,4)
#   --load-cpuset <c>      Load gen CPUs (default: 0,1,2)
#   --mock-cpuset <c>      Mock server CPUs (default: 5,6,7)
#   --threads <n>          Event loop threads (default: 2)
#   --connections <n>      Connections (default: 100)
#   --mock-think-time <ms> Mock delay ms (default: 1)
#   --heap <size>          Heap size (default: 1g)
#   --drain-budget-us <n>  MPSC drain budget (default: JVM default = 50)
#   --help                 Show this help
#
# Copyright 2026 The Netty VirtualThread Scheduler Project
# Licensed under Apache License 2.0

set -euo pipefail

# ============================================================================
# Defaults
# ============================================================================

RATES="30000,40000,50000,60000,max"
ROUNDS=3
OUTPUT_DIR="/tmp/cpu-bound-comparison"
SCHEDULERS="mpsc,fjp"
SERVER_CPUSET="3,4"
LOAD_CPUSET="0,1,2"
MOCK_CPUSET="5,6,7"
THREADS=2
CONNECTIONS=100
MOCK_THINK_TIME=1
MOCK_THREADS=3
LOAD_THREADS=3
HEAP="1g"
DRAIN_BUDGET_US=""

JAVA_HOME="${JAVA_HOME:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ============================================================================
# Parse args
# ============================================================================

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rates)            RATES="$2"; shift 2 ;;
        --rounds)           ROUNDS="$2"; shift 2 ;;
        --output-dir)       OUTPUT_DIR="$2"; shift 2 ;;
        --schedulers)       SCHEDULERS="$2"; shift 2 ;;
        --server-cpuset)    SERVER_CPUSET="$2"; shift 2 ;;
        --load-cpuset)      LOAD_CPUSET="$2"; shift 2 ;;
        --mock-cpuset)      MOCK_CPUSET="$2"; shift 2 ;;
        --threads)          THREADS="$2"; shift 2 ;;
        --connections)      CONNECTIONS="$2"; shift 2 ;;
        --mock-think-time)  MOCK_THINK_TIME="$2"; shift 2 ;;
        --mock-threads)     MOCK_THREADS="$2"; shift 2 ;;
        --load-threads)     LOAD_THREADS="$2"; shift 2 ;;
        --heap)             HEAP="$2"; shift 2 ;;
        --drain-budget-us)  DRAIN_BUDGET_US="$2"; shift 2 ;;
        --help|-h)
            head -30 "$0" | grep "^#" | sed 's/^# \?//'
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

if [[ -z "$JAVA_HOME" ]]; then
    echo "ERROR: JAVA_HOME must be set"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

# ============================================================================
# Run benchmarks
# ============================================================================

IFS=',' read -ra RATE_LIST <<< "$RATES"
IFS=',' read -ra SCHED_LIST <<< "$SCHEDULERS"

TOTAL=$(( ${#SCHED_LIST[@]} * ${#RATE_LIST[@]} * ROUNDS ))
RUN=0

log() { echo "[$(date '+%H:%M:%S')] $*"; }

for sched in "${SCHED_LIST[@]}"; do
    for rate in "${RATE_LIST[@]}"; do
        for round in $(seq 1 "$ROUNDS"); do
            RUN=$((RUN + 1))
            dir="$OUTPUT_DIR/${sched}-${rate}-r${round}"
            log "[$RUN/$TOTAL] $sched rate=$rate round=$round → $dir"

            rate_arg=""
            if [[ "$rate" != "max" ]]; then
                rate_arg="--rate $rate"
            fi

            sched_args=""
            if [[ "$sched" == "mpsc" ]]; then
                sched_args="--mpsc-scheduler --sticky"
                if [[ -n "$DRAIN_BUDGET_US" ]]; then
                    sched_args="$sched_args --drain-budget-us $DRAIN_BUDGET_US"
                fi
            else
                sched_args="--sticky --poller-mode 3"
            fi

            JAVA_OPTS="-Xms${HEAP} -Xmx${HEAP} -XX:+AlwaysPreTouch" \
            bash "$SCRIPT_DIR/run-benchmark.sh" \
                --mode VIRTUAL_NETTY $sched_args $rate_arg \
                --vt-mode perreq --warmup 20s --total-duration 50s \
                --no-pidstat --perf-stat \
                --connections "$CONNECTIONS" \
                --load-threads "$LOAD_THREADS" --load-cpuset "$LOAD_CPUSET" \
                --server-cpuset "$SERVER_CPUSET" --threads "$THREADS" \
                --mock-cpuset "$MOCK_CPUSET" --mock-threads "$MOCK_THREADS" \
                --mock-think-time "$MOCK_THINK_TIME" \
                --output-dir "$dir" > /dev/null 2>&1 || true

            # extract key metrics
            if [[ -f "$dir/wrk-results.txt" ]]; then
                rps=$(grep "Requests/sec" "$dir/wrk-results.txt" | awk '{print $2}')
                p50=$(grep "50.000%" "$dir/wrk-results.txt" | awk '{print $2}')
                p90=$(grep "90.000%" "$dir/wrk-results.txt" | awk '{print $2}')
                p99=$(grep "99.000%" "$dir/wrk-results.txt" | awk '{print $2}')
            else
                rps="FAIL"; p50="-"; p90="-"; p99="-"
            fi
            if [[ -f "$dir/perf-stat.txt" ]]; then
                task_clock=$(grep "task-clock" "$dir/perf-stat.txt" | awk '{print $1}' | tr -d ',')
                elapsed=$(grep "seconds time elapsed" "$dir/perf-stat.txt" | awk '{print $1}')
                if [[ -n "$task_clock" && -n "$elapsed" ]]; then
                    cpu_util=$(echo "scale=2; $task_clock / ($elapsed * 1000)" | bc)
                else
                    cpu_util="-"
                fi
                cs=$(grep "context-switches" "$dir/perf-stat.txt" | awk '{print $1}' | tr -d ',')
            else
                cpu_util="-"; cs="-"
            fi

            log "  → req/s=$rps p50=$p50 p90=$p90 p99=$p99 cpu=${cpu_util} cs=$cs"
        done
    done
done

# ============================================================================
# Summary
# ============================================================================

log ""
log "=============================================="
log "Summary"
log "=============================================="

SUMMARY="$OUTPUT_DIR/summary.csv"
echo "scheduler,rate,round,req_s,p50_ms,p90_ms,p99_ms,cpu_util,context_switches" > "$SUMMARY"

for sched in "${SCHED_LIST[@]}"; do
    for rate in "${RATE_LIST[@]}"; do
        for round in $(seq 1 "$ROUNDS"); do
            dir="$OUTPUT_DIR/${sched}-${rate}-r${round}"
            rps="-"; p50="-"; p90="-"; p99="-"; cpu_util="-"; cs="-"

            if [[ -f "$dir/wrk-results.txt" ]]; then
                rps=$(grep "Requests/sec" "$dir/wrk-results.txt" | awk '{print $2}' || echo "-")
                p50=$(grep "50.000%" "$dir/wrk-results.txt" | awk '{print $2}' || echo "-")
                p90=$(grep "90.000%" "$dir/wrk-results.txt" | awk '{print $2}' || echo "-")
                p99=$(grep "99.000%" "$dir/wrk-results.txt" | awk '{print $2}' || echo "-")
            fi
            if [[ -f "$dir/perf-stat.txt" ]]; then
                task_clock=$(grep "task-clock" "$dir/perf-stat.txt" | awk '{print $1}' | tr -d ',' || echo "")
                elapsed=$(grep "seconds time elapsed" "$dir/perf-stat.txt" | awk '{print $1}' || echo "")
                if [[ -n "$task_clock" && -n "$elapsed" ]]; then
                    cpu_util=$(echo "scale=2; $task_clock / ($elapsed * 1000)" | bc 2>/dev/null || echo "-")
                fi
                cs=$(grep "context-switches" "$dir/perf-stat.txt" | awk '{print $1}' | tr -d ',' || echo "-")
            fi

            echo "$sched,$rate,$round,$rps,$p50,$p90,$p99,$cpu_util,$cs" >> "$SUMMARY"
        done
    done
done

log "Results CSV: $SUMMARY"
log "Run directories: $OUTPUT_DIR/{mpsc,fjp}-{rate}-r{1..$ROUNDS}/"
log ""
log "Done!"
