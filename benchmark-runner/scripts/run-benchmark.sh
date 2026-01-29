#!/usr/bin/env bash
#
# Benchmark Runner Script
#
# This script orchestrates the full benchmark workflow:
# 1. Start mock HTTP server
# 2. Start handoff HTTP server (with optional profiling)
# 3. Run load generator (wrk/wrk2)
# 4. Optionally collect pidstat and async-profiler data
#
# Copyright 2026 The Netty VirtualThread Scheduler Project
# Licensed under Apache License 2.0

set -euo pipefail

# ============================================================================
# Configuration with defaults
# ============================================================================

# Java configuration
JAVA_HOME="${JAVA_HOME:-}"
JAVA_OPTS="${JAVA_OPTS:--Xms1g -Xmx1g}"

# Mock server configuration
MOCK_PORT="${MOCK_PORT:-8080}"
MOCK_THINK_TIME_MS="${MOCK_THINK_TIME_MS:-1}"
MOCK_THREADS="${MOCK_THREADS:-}"
MOCK_TASKSET="${MOCK_TASKSET:-4,5,6,7}"  # CPUs for mock server

# Handoff server configuration
SERVER_PORT="${SERVER_PORT:-8081}"
SERVER_THREADS="${SERVER_THREADS:-2}"
SERVER_USE_CUSTOM_SCHEDULER="${SERVER_USE_CUSTOM_SCHEDULER:-false}"
SERVER_IO="${SERVER_IO:-epoll}"
SERVER_TASKSET="${SERVER_TASKSET:-2,3}"  # CPUs for handoff server
SERVER_JVM_ARGS="${SERVER_JVM_ARGS:-}"
SERVER_POLLER_MODE="${SERVER_POLLER_MODE:-3}"  # jdk.pollerMode value (1, 2, or 3)
SERVER_FJ_PARALLELISM="${SERVER_FJ_PARALLELISM:-}"  # ForkJoinPool parallelism (empty = JVM default)
SERVER_NO_TIMEOUT="${SERVER_NO_TIMEOUT:-false}"  # Disable HTTP client timeout
SERVER_REACTIVE="${SERVER_REACTIVE:-false}"  # Use reactive handler with Project Reactor

# Load generator configuration
LOAD_GEN_TASKSET="${LOAD_GEN_TASKSET:-0,1}"  # CPUs for load generator
LOAD_GEN_CONNECTIONS="${LOAD_GEN_CONNECTIONS:-100}"
LOAD_GEN_THREADS="${LOAD_GEN_THREADS:-2}"
LOAD_GEN_DURATION="${LOAD_GEN_DURATION:-30s}"
LOAD_GEN_RATE="${LOAD_GEN_RATE:-}"  # Empty = wrk (max throughput), set value = wrk2 (rate limited)
LOAD_GEN_URL="${LOAD_GEN_URL:-http://localhost:8081/fruits}"

# Timing configuration
WARMUP_DURATION="${WARMUP_DURATION:-10s}"
TOTAL_DURATION="${TOTAL_DURATION:-30s}"
MIN_STEADY_STATE_SECONDS=20
PROFILING_DELAY_SECONDS=5
PROFILING_DURATION_SECONDS=10

# Profiling configuration
ENABLE_PROFILER="${ENABLE_PROFILER:-false}"
PROFILER_EVENT="${PROFILER_EVENT:-cpu}"
PROFILER_OUTPUT="${PROFILER_OUTPUT:-profile.html}"
ASYNC_PROFILER_PATH="${ASYNC_PROFILER_PATH:-}"  # Path to async-profiler

# pidstat configuration
ENABLE_PIDSTAT="${ENABLE_PIDSTAT:-false}"
PIDSTAT_INTERVAL="${PIDSTAT_INTERVAL:-1}"
PIDSTAT_OUTPUT="${PIDSTAT_OUTPUT:-pidstat.log}"

# perf stat configuration
ENABLE_PERF_STAT="${ENABLE_PERF_STAT:-false}"
PERF_STAT_OUTPUT="${PERF_STAT_OUTPUT:-perf-stat.txt}"

# Output directory
OUTPUT_DIR="${OUTPUT_DIR:-./benchmark-results}"

# ============================================================================
# Computed paths
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUNNER_JAR="${PROJECT_ROOT}/benchmark-runner/target/benchmark-runner.jar"

# ============================================================================
# Helper functions
# ============================================================================

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

error() {
    log "ERROR: $*" >&2
    exit 1
}

parse_duration_to_seconds() {
    local duration="$1"
    local value="${duration%[smhd]}"
    local unit="${duration: -1}"

    case "$unit" in
        s) echo "$value" ;;
        m) echo $((value * 60)) ;;
        h) echo $((value * 3600)) ;;
        d) echo $((value * 86400)) ;;
        *) echo "$duration" ;;  # Assume seconds if no unit
    esac
}

validate_config() {
    local warmup_secs=$(parse_duration_to_seconds "$WARMUP_DURATION")
    local total_secs=$(parse_duration_to_seconds "$TOTAL_DURATION")
    local steady_state_secs=$((total_secs - warmup_secs))

    if [[ $warmup_secs -ge $total_secs ]]; then
        error "Warmup duration ($WARMUP_DURATION = ${warmup_secs}s) must be less than total duration ($TOTAL_DURATION = ${total_secs}s)"
    fi
    if [[ $steady_state_secs -lt $MIN_STEADY_STATE_SECONDS ]]; then
        error "Steady-state duration (${steady_state_secs}s) must be at least ${MIN_STEADY_STATE_SECONDS}s (got WARMUP_DURATION=$WARMUP_DURATION, TOTAL_DURATION=$TOTAL_DURATION)"
    fi

    if [[ -z "$JAVA_HOME" ]]; then
        error "JAVA_HOME must be set"
    fi

    if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
        error "Java executable not found at $JAVA_HOME/bin/java"
    fi

    if [[ "$ENABLE_PROFILER" == "true" && -z "$ASYNC_PROFILER_PATH" ]]; then
        error "ASYNC_PROFILER_PATH must be set when ENABLE_PROFILER=true"
    fi

    log "Configuration validated"
    log "  Warmup: $WARMUP_DURATION (${warmup_secs}s)"
    log "  Total:  $TOTAL_DURATION (${total_secs}s)"
    log "  Measurement window: ${steady_state_secs}s"
    log "  Profiling: delay ${PROFILING_DELAY_SECONDS}s, duration ${PROFILING_DURATION_SECONDS}s"
}

check_jbang() {
    if ! command -v jbang &> /dev/null; then
        log "jbang not found, installing..."
        curl -Ls https://sh.jbang.dev | bash -s - app setup
        export PATH="$HOME/.jbang/bin:$PATH"
    fi
    log "jbang version: $(jbang --version)"
}

wait_for_server() {
    local url="$1"
    local name="$2"
    local max_attempts="${3:-30}"
    local attempt=0

    log "Waiting for $name at $url..."
    while [[ $attempt -lt $max_attempts ]]; do
        if curl -s -o /dev/null -w "%{http_code}" "$url" | grep -q "200"; then
            log "$name is ready"
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 1
    done

    error "$name failed to start within ${max_attempts}s"
}

build_taskset_cmd() {
    local cpus="$1"
    if [[ -n "$cpus" ]]; then
        echo "taskset -c $cpus"
    else
        echo ""
    fi
}

cleanup() {
    log "Cleaning up..."

    # Kill mock server
    if [[ -n "${MOCK_PID:-}" ]]; then
        log "Stopping mock server (PID: $MOCK_PID)"
        kill "$MOCK_PID" 2>/dev/null || true
        wait "$MOCK_PID" 2>/dev/null || true
    fi

    # Kill handoff server
    if [[ -n "${SERVER_PID:-}" ]]; then
        log "Stopping handoff server (PID: $SERVER_PID)"
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi

    # Kill pidstat
    if [[ -n "${PIDSTAT_PID:-}" ]]; then
        log "Stopping pidstat (PID: $PIDSTAT_PID)"
        kill "$PIDSTAT_PID" 2>/dev/null || true
    fi

    # Kill perf stat (should already be done, but clean up just in case)
    if [[ -n "${PERF_STAT_PID:-}" ]]; then
        log "Stopping perf stat (PID: $PERF_STAT_PID)"
        kill "$PERF_STAT_PID" 2>/dev/null || true
    fi

    log "Cleanup complete"
}

trap cleanup EXIT

# ============================================================================
# Build JARs if needed
# ============================================================================

build_jars() {
    log "Building project JARs..."

    cd "$PROJECT_ROOT"


    if [[ ! -f "$RUNNER_JAR" ]]; then
        log "Building benchmark-runner module..."
        JAVA_HOME="$JAVA_HOME" mvn package -pl benchmark-runner -am -DskipTests -q
    fi

    log "JARs ready"
}

# ============================================================================
# Start Mock Server
# ============================================================================

start_mock_server() {
    log "Starting mock HTTP server..."

    local taskset_cmd=$(build_taskset_cmd "$MOCK_TASKSET")
    local java_cmd="$JAVA_HOME/bin/java"

    local mock_threads_arg=""
    if [[ -n "$MOCK_THREADS" ]]; then
        mock_threads_arg="--threads $MOCK_THREADS"
    fi

    local cmd="$taskset_cmd $java_cmd $JAVA_OPTS -cp $RUNNER_JAR \
        io.netty.loom.benchmark.runner.MockHttpServer \
        --port $MOCK_PORT --think-time $MOCK_THINK_TIME_MS $mock_threads_arg --silent"

    log "Mock server command: $cmd"

    $cmd &
    MOCK_PID=$!

    wait_for_server "http://localhost:$MOCK_PORT/health" "Mock server"
}

# ============================================================================
# Start Handoff Server
# ============================================================================

start_handoff_server() {
    log "Starting handoff HTTP server..."

    local taskset_cmd=$(build_taskset_cmd "$SERVER_TASKSET")
    local java_cmd="$JAVA_HOME/bin/java"

    # Build JVM args
    local jvm_args="--add-opens=java.base/java.lang=ALL-UNNAMED"
    jvm_args="$jvm_args -XX:+UnlockExperimentalVMOptions"
    jvm_args="$jvm_args -XX:-DoJVMTIVirtualThreadTransitions"
    jvm_args="$jvm_args -Djdk.trackAllThreads=false"

    if [[ "$SERVER_USE_CUSTOM_SCHEDULER" == "true" ]]; then
        jvm_args="$jvm_args -Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler"
        jvm_args="$jvm_args -Djdk.pollerMode=$SERVER_POLLER_MODE"
    fi

    if [[ -n "$SERVER_FJ_PARALLELISM" ]]; then
        jvm_args="$jvm_args -Djdk.virtualThreadScheduler.parallelism=$SERVER_FJ_PARALLELISM"
    fi

    # Add debug non-safepoints if profiling is enabled
    if [[ "$ENABLE_PROFILER" == "true" ]]; then
        jvm_args="$jvm_args -XX:+UnlockDiagnosticVMOptions"
        jvm_args="$jvm_args -XX:+DebugNonSafepoints"
    fi

    # Add custom JVM args
    if [[ -n "$SERVER_JVM_ARGS" ]]; then
        jvm_args="$jvm_args $SERVER_JVM_ARGS"
    fi

    local cmd="$taskset_cmd $java_cmd $JAVA_OPTS $jvm_args -cp $RUNNER_JAR \
        io.netty.loom.benchmark.runner.HandoffHttpServer \
        --port $SERVER_PORT \
        --mock-url http://localhost:$MOCK_PORT/fruits \
        --threads $SERVER_THREADS \
        --use-custom-scheduler $SERVER_USE_CUSTOM_SCHEDULER \
        --io $SERVER_IO \
        --no-timeout $SERVER_NO_TIMEOUT \
        --reactive $SERVER_REACTIVE \
        --silent"

    log "Handoff server command: $cmd"

    $cmd &
    SERVER_PID=$!

    wait_for_server "http://localhost:$SERVER_PORT/health" "Handoff server"
}

# ============================================================================
# Warmup Phase
# ============================================================================

run_warmup() {
    local warmup_secs=$(parse_duration_to_seconds "$WARMUP_DURATION")

    if [[ $warmup_secs -eq 0 ]]; then
        log "Skipping warmup (duration is 0)"
        return
    fi

    log "Running warmup for $WARMUP_DURATION..."

    local taskset_cmd=$(build_taskset_cmd "$LOAD_GEN_TASKSET")

    # Use wrk for warmup (no rate limiting)
    $taskset_cmd jbang wrk@hyperfoil \
        -t "$LOAD_GEN_THREADS" \
        -c "$LOAD_GEN_CONNECTIONS" \
        -d "$WARMUP_DURATION" \
        "$LOAD_GEN_URL" > /dev/null 2>&1 || true

    log "Warmup complete"
}

# ============================================================================
# Start Profiler
# ============================================================================

start_profiler() {
    if [[ "$ENABLE_PROFILER" != "true" ]]; then
        return
    fi

    log "Scheduling async-profiler for handoff server (PID: $SERVER_PID)..."

    local asprof="$ASYNC_PROFILER_PATH/bin/asprof"
    local output_file="$OUTPUT_DIR/$PROFILER_OUTPUT"

    if [[ ! -x "$asprof" ]]; then
        error "async-profiler not found at $asprof"
    fi

    (
        sleep "$PROFILING_DELAY_SECONDS"
        "$asprof" --threads -e "$PROFILER_EVENT" -o flamegraph -d "$PROFILING_DURATION_SECONDS" -f "$output_file" "$SERVER_PID"
    ) &
    PROFILER_PID=$!

    log "Async-profiler scheduled after ${PROFILING_DELAY_SECONDS}s for ${PROFILING_DURATION_SECONDS}s"
    log "Profiler output: $output_file"
}

stop_profiler() {
    if [[ "$ENABLE_PROFILER" != "true" ]]; then
        return
    fi

    if [[ -n "${PROFILER_PID:-}" ]]; then
        wait "$PROFILER_PID" 2>/dev/null || true
    fi
}

# ============================================================================
# Start pidstat
# ============================================================================

start_pidstat() {
    if [[ "$ENABLE_PIDSTAT" != "true" ]]; then
        return
    fi

    log "Starting pidstat for handoff server (PID: $SERVER_PID)..."

    local output_file="$OUTPUT_DIR/$PIDSTAT_OUTPUT"

    # add -t to see all threads
    pidstat -p "$SERVER_PID" "$PIDSTAT_INTERVAL" > "$output_file" 2>&1 &
    PIDSTAT_PID=$!

    log "pidstat running (PID: $PIDSTAT_PID)"
}

stop_pidstat() {
    if [[ "$ENABLE_PIDSTAT" != "true" ]]; then
        return
    fi

    if [[ -n "${PIDSTAT_PID:-}" ]]; then
        log "Stopping pidstat..."
        kill "$PIDSTAT_PID" 2>/dev/null || true
        wait "$PIDSTAT_PID" 2>/dev/null || true
        log "pidstat output: $OUTPUT_DIR/$PIDSTAT_OUTPUT"
    fi
}

# ============================================================================
# Start perf stat
# ============================================================================

start_perf_stat() {
    if [[ "$ENABLE_PERF_STAT" != "true" ]]; then
        return
    fi

    log "Starting perf stat for handoff server (PID: $SERVER_PID)..."

    local output_file="$OUTPUT_DIR/$PERF_STAT_OUTPUT"
    local profiling_delay_ms=$((PROFILING_DELAY_SECONDS * 1000))
    local profiling_duration_ms=$((PROFILING_DURATION_SECONDS * 1000))

    perf stat -p "$SERVER_PID" -o "$output_file" -D "$profiling_delay_ms" --timeout "$profiling_duration_ms" &
    PERF_STAT_PID=$!

    log "perf stat running (PID: $PERF_STAT_PID) after ${PROFILING_DELAY_SECONDS}s for ${PROFILING_DURATION_SECONDS}s"
}

# Note: perf stat stops automatically after the sleep duration, no explicit stop needed

# ============================================================================
# Run Load Test
# ============================================================================

run_load_test() {
    local warmup_secs=$(parse_duration_to_seconds "$WARMUP_DURATION")
    local total_secs=$(parse_duration_to_seconds "$TOTAL_DURATION")
    local test_secs=$((total_secs - warmup_secs))

    log "Running load test for ${test_secs}s..."

    local taskset_cmd=$(build_taskset_cmd "$LOAD_GEN_TASKSET")
    local output_file="$OUTPUT_DIR/wrk-results.txt"

    if [[ -n "$LOAD_GEN_RATE" ]]; then
        # Use wrk2 with rate limiting
        log "Using wrk2 with rate: $LOAD_GEN_RATE req/s"

        $taskset_cmd jbang wrk2@hyperfoil \
            -t "$LOAD_GEN_THREADS" \
            -c "$LOAD_GEN_CONNECTIONS" \
            -d "${test_secs}s" \
            -R "$LOAD_GEN_RATE" \
            --latency \
            "$LOAD_GEN_URL" 2>&1 | tee "$output_file"
    else
        # Use wrk for max throughput
        log "Using wrk for max throughput"

        $taskset_cmd jbang wrk@hyperfoil \
            -t "$LOAD_GEN_THREADS" \
            -c "$LOAD_GEN_CONNECTIONS" \
            -d "${test_secs}s" \
            "$LOAD_GEN_URL" 2>&1 | tee "$output_file"
    fi

    log "Load test complete"
    log "Results saved to: $output_file"
}

# ============================================================================
# Print Configuration Summary
# ============================================================================

print_config() {
    local warmup_secs=$(parse_duration_to_seconds "$WARMUP_DURATION")
    local total_secs=$(parse_duration_to_seconds "$TOTAL_DURATION")
    local steady_state_secs=$((total_secs - warmup_secs))

    log "=============================================="
    log "Benchmark Configuration"
    log "=============================================="
    log ""
    log "Mock Server:"
    log "  Port:           $MOCK_PORT"
    log "  Think Time:     ${MOCK_THINK_TIME_MS}ms"
    log "  Threads:        ${MOCK_THREADS:-<auto>}"
    log "  CPU Affinity:   ${MOCK_TASKSET:-<none>}"
    log ""
    log "Handoff Server:"
    log "  Port:           $SERVER_PORT"
    log "  Threads:        $SERVER_THREADS"
    log "  Reactive:       $SERVER_REACTIVE"
    log "  Custom Sched:   $SERVER_USE_CUSTOM_SCHEDULER"
    log "  I/O Type:       $SERVER_IO"
    log "  No Timeout:     $SERVER_NO_TIMEOUT"
    log "  Poller Mode:    $SERVER_POLLER_MODE"
    log "  FJ Parallelism: ${SERVER_FJ_PARALLELISM:-<default>}"
    log "  CPU Affinity:   ${SERVER_TASKSET:-<none>}"
    log "  Extra JVM Args: ${SERVER_JVM_ARGS:-<none>}"
    log ""
    log "Load Generator:"
    log "  Connections:    $LOAD_GEN_CONNECTIONS"
    log "  Threads:        $LOAD_GEN_THREADS"
    log "  Rate:           ${LOAD_GEN_RATE:-<max throughput>}"
    log "  CPU Affinity:   ${LOAD_GEN_TASKSET:-<none>}"
    log ""
    log "Timing:"
    log "  Warmup:         $WARMUP_DURATION"
    log "  Total:          $TOTAL_DURATION"
    log "  Steady State:   ${steady_state_secs}s (min ${MIN_STEADY_STATE_SECONDS}s)"
    log "  Profiling:      delay ${PROFILING_DELAY_SECONDS}s, duration ${PROFILING_DURATION_SECONDS}s"
    log ""
    log "Profiling:"
    log "  Enabled:        $ENABLE_PROFILER"
    if [[ "$ENABLE_PROFILER" == "true" ]]; then
        log "  Event:          $PROFILER_EVENT"
        log "  Output:         $PROFILER_OUTPUT"
    fi
    log ""
    log "pidstat:"
    log "  Enabled:        $ENABLE_PIDSTAT"
    if [[ "$ENABLE_PIDSTAT" == "true" ]]; then
        log "  Interval:       ${PIDSTAT_INTERVAL}s"
        log "  Output:         $PIDSTAT_OUTPUT"
    fi
    log ""
    log "perf stat:"
    log "  Enabled:        $ENABLE_PERF_STAT"
    if [[ "$ENABLE_PERF_STAT" == "true" ]]; then
        log "  Output:         $PERF_STAT_OUTPUT"
    fi
    log ""
    log "Output Directory: $OUTPUT_DIR"
    log "=============================================="
}

# ============================================================================
# Main
# ============================================================================

main() {
    # Parse command line arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --help|-h)
                cat << 'EOF'
Benchmark Runner Script

Usage: ./run-benchmark.sh [OPTIONS]

Environment Variables (can also be set via command line options):

Mock Server:
  MOCK_PORT                 Mock server port (default: 8080)
  MOCK_THINK_TIME_MS        Response delay in ms (default: 1)
  MOCK_THREADS              Number of threads (default: auto = available processors)
  MOCK_TASKSET              CPU affinity range (default: "4,5,6,7")

Handoff Server:
  SERVER_PORT               Server port (default: 8081)
  SERVER_THREADS            Number of event loop threads (default: 2)
  SERVER_REACTIVE           Use reactive handler with Reactor (default: false)
  SERVER_USE_CUSTOM_SCHEDULER  Use custom Netty scheduler (default: false)
  SERVER_IO                 I/O type: epoll, nio, or io_uring (default: epoll)
  SERVER_NO_TIMEOUT         Disable HTTP client timeout (default: false)
  SERVER_TASKSET            CPU affinity range (default: "2,3")
  SERVER_JVM_ARGS           Additional JVM arguments
  SERVER_POLLER_MODE        jdk.pollerMode value: 1, 2, or 3 (default: 3)
  SERVER_FJ_PARALLELISM     ForkJoinPool parallelism (empty = JVM default)

Load Generator:
  LOAD_GEN_CONNECTIONS      Number of connections (default: 100)
  LOAD_GEN_THREADS          Number of threads (default: 2)
  LOAD_GEN_DURATION         Test duration (default: 30s)
  LOAD_GEN_RATE             Target rate for wrk2 (empty = use wrk)
  LOAD_GEN_TASKSET          CPU affinity range (default: "0,1")

Timing:
  WARMUP_DURATION           Warmup duration (default: 10s)
  TOTAL_DURATION            Total test duration (default: 30s, must keep steady state >= 20s)

Profiling:
  ENABLE_PROFILER           Enable async-profiler (default: false)
  ASYNC_PROFILER_PATH       Path to async-profiler installation
  PROFILER_EVENT            Profiler event type (default: cpu)
  PROFILER_OUTPUT           Profiler output file (default: profile.html)
  Note: profiling starts after 5s and runs for 10s.

pidstat:
  ENABLE_PIDSTAT            Enable pidstat collection (default: false)
  PIDSTAT_INTERVAL          Collection interval in seconds (default: 1)
  PIDSTAT_OUTPUT            Output file (default: pidstat.log)

perf stat:
  ENABLE_PERF_STAT          Enable perf stat collection (default: false)
  PERF_STAT_OUTPUT          Output file (default: perf-stat.txt)

General:
  JAVA_HOME                 Path to Java installation (required)
  OUTPUT_DIR                Output directory (default: ./benchmark-results)

Examples:

  # Basic run with custom scheduler
  JAVA_HOME=/path/to/jdk SERVER_USE_CUSTOM_SCHEDULER=true ./run-benchmark.sh

  # Run with CPU pinning and profiling
  JAVA_HOME=/path/to/jdk \
  MOCK_TASKSET="0" \
  SERVER_TASKSET="1-2" \
  LOAD_GEN_TASKSET="3" \
  ENABLE_PROFILER=true \
  ASYNC_PROFILER_PATH=/path/to/async-profiler \
  ./run-benchmark.sh

  # Rate-limited test with wrk2
  JAVA_HOME=/path/to/jdk \
  LOAD_GEN_RATE=10000 \
  TOTAL_DURATION=60s \
  WARMUP_DURATION=15s \
  ./run-benchmark.sh

  # Reactive handler test
  JAVA_HOME=/path/to/jdk \
  SERVER_REACTIVE=true \
  SERVER_THREADS=2 \
  ./run-benchmark.sh

EOF
                exit 0
                ;;
            *)
                error "Unknown option: $1"
                ;;
        esac
        shift
    done

    # Validate configuration
    validate_config

    # Print configuration
    print_config

    # Create output directory
    mkdir -p "$OUTPUT_DIR"

    # Check jbang
    check_jbang

    # Build JARs
    build_jars

    # Start servers
    start_mock_server
    start_handoff_server

    # Run warmup (no profiling/pidstat)
    run_warmup

    # Start monitoring after warmup
    start_profiler
    start_pidstat
    start_perf_stat

    # Run actual load test
    run_load_test

    # Stop monitoring
    stop_profiler
    stop_pidstat

    log "Benchmark complete!"
    log "Results in: $OUTPUT_DIR"
}

main "$@"
