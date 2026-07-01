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
MOCK_THREADS="${MOCK_THREADS:-1}"
MOCK_CPUSET="${MOCK_CPUSET:-4,5}"  # CPUs for mock server

# Handoff server configuration
SERVER_PORT="${SERVER_PORT:-8081}"
SERVER_THREADS="${SERVER_THREADS:-2}"
SERVER_IO="${SERVER_IO:-epoll}"
SERVER_CPUSET="${SERVER_CPUSET:-2,3}"  # CPUs for handoff server
SERVER_JVM_ARGS="${SERVER_JVM_ARGS:-}"
SERVER_POLLER_MODE="${SERVER_POLLER_MODE:-}"  # jdk.pollerMode value (1, 2, or 3); empty = JVM default, NETTY_SCHEDULER defaults to 3
SERVER_FJ_PARALLELISM="${SERVER_FJ_PARALLELISM:-}"  # ForkJoinPool parallelism (empty = JVM default)
SERVER_MODE="${SERVER_MODE:-NON_VIRTUAL_NETTY}"  # Server mode: NON_VIRTUAL_NETTY, REACTIVE, VIRTUAL_NETTY
SERVER_MOCKLESS="${SERVER_MOCKLESS:-false}"  # Skip mock server; do Jackson work inline
SERVER_VT_MODE="${SERVER_VT_MODE:-}"  # VT mode: longlived (default) or perreq
SERVER_WS="${SERVER_WS:-false}"  # Enable work stealing
SERVER_TOPOLOGY="${SERVER_TOPOLOGY:-false}"  # Enable LinuxCarrierTopology (CPU pinning + cluster awareness)
SERVER_STICKY="${SERVER_STICKY:-false}"  # Enable stickyAffinity for VT event loops (VIRTUAL_NETTY mode)
SERVER_USE_MPSC="${SERVER_USE_MPSC:-false}"  # Use MPSC virtual thread scheduler instead of FJP

# Load generator configuration
LOAD_GEN_CPUSET="${LOAD_GEN_CPUSET:-0,1}"  # CPUs for load generator
LOAD_GEN_CONNECTIONS="${LOAD_GEN_CONNECTIONS:-100}"
LOAD_GEN_THREADS="${LOAD_GEN_THREADS:-2}"
LOAD_GEN_DURATION="${LOAD_GEN_DURATION:-30s}"
LOAD_GEN_RATE="${LOAD_GEN_RATE:-}"  # Empty = wrk (max throughput), set value = wrk2 (rate limited)
LOAD_GEN_URL="${LOAD_GEN_URL:-http://localhost:8081/fruits}"

# Timing configuration
WARMUP_DURATION="${WARMUP_DURATION:-10s}"
TOTAL_DURATION="${TOTAL_DURATION:-30s}"
MIN_STEADY_STATE_SECONDS=20
PROFILING_DELAY_SECONDS="${PROFILING_DELAY_SECONDS:-10}"
PROFILING_DURATION_SECONDS="${PROFILING_DURATION_SECONDS:-10}"

# Profiling configuration
ENABLE_PROFILER="${ENABLE_PROFILER:-false}"
PROFILER_EVENT="${PROFILER_EVENT:-cpu}"
PROFILER_FORMAT="${PROFILER_FORMAT:-flamegraph}"  # Output format: flamegraph, collapsed, jfr
PROFILER_OUTPUT="${PROFILER_OUTPUT:-profile.html}"
ASYNC_PROFILER_PATH="${ASYNC_PROFILER_PATH:-}"  # Path to async-profiler

# JFR configuration
ENABLE_JFR="${ENABLE_JFR:-false}"
JFR_EVENTS="${JFR_EVENTS:-all}"
JFR_OUTPUT="${JFR_OUTPUT:-netty-loom.jfr}"
JFR_RECORDING_NAME="${JFR_RECORDING_NAME:-netty-loom-benchmark}"
JFR_SETTINGS_FILE="${JFR_SETTINGS_FILE:-}"
JFR_TIMELINE_OUTPUT="${JFR_TIMELINE_OUTPUT:-netty-loom-timeline.jsonl}"

# pidstat configuration
ENABLE_PIDSTAT="${ENABLE_PIDSTAT:-true}"
PIDSTAT_INTERVAL="${PIDSTAT_INTERVAL:-1}"
PIDSTAT_OUTPUT="${PIDSTAT_OUTPUT:-pidstat.log}"
PIDSTAT_MOCK_OUTPUT="${PIDSTAT_MOCK_OUTPUT:-pidstat-mock.log}"
PIDSTAT_LOAD_GEN_OUTPUT="${PIDSTAT_LOAD_GEN_OUTPUT:-pidstat-loadgen.log}"
PIDSTAT_HANDOFF_DETAILED="${PIDSTAT_HANDOFF_DETAILED:-true}"

# perf stat configuration
ENABLE_PERF_STAT="${ENABLE_PERF_STAT:-false}"
PERF_STAT_OUTPUT="${PERF_STAT_OUTPUT:-perf-stat.txt}"
PERF_STAT_ARGS="${PERF_STAT_ARGS:-}"

# perf sched configuration (migration tracing)
ENABLE_PERF_SCHED="${ENABLE_PERF_SCHED:-false}"
PERF_SCHED_OUTPUT="${PERF_SCHED_OUTPUT:-perf-sched.data}"

# Wakeup trace configuration (bpftrace: who wakes carrier threads)
ENABLE_WAKEUP_TRACE="${ENABLE_WAKEUP_TRACE:-false}"

# Output directory
OUTPUT_DIR="${OUTPUT_DIR:-./benchmark-results}"
CONFIG_OUTPUT="${CONFIG_OUTPUT:-benchmark-config.txt}"

# ============================================================================
# Computed paths
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUNNER_JAR="${PROJECT_ROOT}/benchmark-runner/target/benchmark-runner.jar"
JFR_TO_TIMELINE_SCRIPT="${SCRIPT_DIR}/jfr/JfrToTimeline.java"

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

resolve_jfr_events() {
    local selection="$1"
    local -a default_events=(
        "io.netty.loom.NettyRunIo"
        "io.netty.loom.VirtualThreadTaskRuns"
        "io.netty.loom.VirtualThreadTaskRun"
        "io.netty.loom.VirtualThreadTaskSubmit"
    )
    local -A event_map=(
        ["NettyRunIo"]="io.netty.loom.NettyRunIo"
        ["VirtualThreadTaskRuns"]="io.netty.loom.VirtualThreadTaskRuns"
        ["VirtualThreadTaskRun"]="io.netty.loom.VirtualThreadTaskRun"
        ["VirtualThreadTaskSubmit"]="io.netty.loom.VirtualThreadTaskSubmit"
        ["io.netty.loom.NettyRunIo"]="io.netty.loom.NettyRunIo"
        ["io.netty.loom.VirtualThreadTaskRuns"]="io.netty.loom.VirtualThreadTaskRuns"
        ["io.netty.loom.VirtualThreadTaskRun"]="io.netty.loom.VirtualThreadTaskRun"
        ["io.netty.loom.VirtualThreadTaskSubmit"]="io.netty.loom.VirtualThreadTaskSubmit"
    )
    local -a resolved=()

    if [[ "$selection" == "all" ]]; then
        resolved=("${default_events[@]}")
    else
        local -a items=()
        IFS=',' read -r -a items <<< "$selection"
        for item in "${items[@]}"; do
            local trimmed="${item//[[:space:]]/}"
            if [[ -z "$trimmed" ]]; then
                continue
            fi
            if [[ -z "${event_map[$trimmed]+x}" ]]; then
                error "Unknown JFR event selection: $trimmed"
            fi
            resolved+=("${event_map[$trimmed]}")
        done
    fi

    if [[ ${#resolved[@]} -eq 0 ]]; then
        error "JFR_EVENTS resolved to an empty set"
    fi

    printf '%s\n' "${resolved[@]}"
}

write_jfr_settings() {
    if [[ -n "$JFR_SETTINGS_FILE" ]]; then
        if [[ ! -f "$JFR_SETTINGS_FILE" ]]; then
            error "JFR_SETTINGS_FILE not found: $JFR_SETTINGS_FILE"
        fi
        echo "$JFR_SETTINGS_FILE"
        return
    fi

    local repo_settings_path="${SCRIPT_DIR}/jfr/netty-loom.jfc"
    if [[ -f "$repo_settings_path" ]]; then
        echo "$repo_settings_path"
        return
    fi

    local settings_path="$OUTPUT_DIR/netty-loom.jfc"
    local -a events=()

    mapfile -t events < <(resolve_jfr_events "$JFR_EVENTS")

    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        echo '<configuration version="2.0" name="Netty Loom" description="Netty Loom JFR settings" provider="Netty Loom">'
        for event in "${events[@]}"; do
            echo "  <event name=\"$event\">"
            echo '    <setting name="enabled">true</setting>'
            echo '  </event>'
        done
        echo '</configuration>'
    } > "$settings_path"

    echo "$settings_path"
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
    if [[ "$ENABLE_JFR" == "true" ]]; then
        if [[ -n "$JFR_SETTINGS_FILE" ]]; then
            if [[ ! -f "$JFR_SETTINGS_FILE" ]]; then
                error "JFR_SETTINGS_FILE not found: $JFR_SETTINGS_FILE"
            fi
        else
            resolve_jfr_events "$JFR_EVENTS" > /dev/null
        fi
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
    if [[ -n "${PIDSTAT_MOCK_PID:-}" ]]; then
        log "Stopping pidstat for mock server (PID: $PIDSTAT_MOCK_PID)"
        kill "$PIDSTAT_MOCK_PID" 2>/dev/null || true
    fi
    if [[ -n "${PIDSTAT_LOAD_GEN_PID:-}" ]]; then
        log "Stopping pidstat for load generator (PID: $PIDSTAT_LOAD_GEN_PID)"
        kill "$PIDSTAT_LOAD_GEN_PID" 2>/dev/null || true
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

    local taskset_cmd=$(build_taskset_cmd "$MOCK_CPUSET")
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

    local taskset_cmd=$(build_taskset_cmd "$SERVER_CPUSET")
    local java_cmd="$JAVA_HOME/bin/java"

    # Build JVM args
    local jvm_args="--add-opens=java.base/java.lang=ALL-UNNAMED"
    jvm_args="$jvm_args -XX:+UnlockExperimentalVMOptions"
    jvm_args="$jvm_args -XX:-DoJVMTIVirtualThreadTransitions"
    jvm_args="$jvm_args -Djdk.trackAllThreads=false"

    # Mode-specific JVM args
    local poller_mode="$SERVER_POLLER_MODE"
    case "$SERVER_MODE" in
        NETTY_SCHEDULER)
            jvm_args="$jvm_args -Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler"
            # Default pollerMode to 3 for custom scheduler if not explicitly set
            poller_mode="${poller_mode:-3}"
            if [[ "$SERVER_WS" == "true" ]]; then
                jvm_args="$jvm_args -Dio.netty.loom.workstealing.enabled=true"
            fi
            if [[ "$SERVER_TOPOLOGY" == "true" ]]; then
                jvm_args="$jvm_args -Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology"
                jvm_args="$jvm_args --enable-native-access=ALL-UNNAMED"
            fi
            ;;
        VIRTUAL_NETTY)
            if [[ "$SERVER_STICKY" == "true" ]]; then
                jvm_args="$jvm_args -Dio.netty.loom.stickyEventLoops=true"
            fi
            if [[ "$SERVER_USE_MPSC" == "true" ]]; then
                jvm_args="$jvm_args -Djdk.virtualThreadScheduler.useMpsc=true"
                poller_mode="${poller_mode:-4}"
            fi
            ;;
    esac


    # Apply pollerMode if set (explicitly or via mode default)
    if [[ -n "$poller_mode" ]]; then
        jvm_args="$jvm_args -Djdk.pollerMode=$poller_mode"
    fi

    if [[ -n "$SERVER_FJ_PARALLELISM" ]]; then
        jvm_args="$jvm_args -Djdk.virtualThreadScheduler.parallelism=$SERVER_FJ_PARALLELISM"
    fi

    if [[ -n "$SERVER_VT_MODE" ]]; then
        jvm_args="$jvm_args -Dio.netty.loom.benchmark.vtmode=$SERVER_VT_MODE"
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

    local mockless_flag=""
    if [[ "$SERVER_MOCKLESS" == "true" ]]; then
        mockless_flag="--mockless"
    fi

    local cmd="$taskset_cmd $java_cmd $JAVA_OPTS $jvm_args -cp $RUNNER_JAR \
        io.netty.loom.benchmark.runner.HandoffHttpServer \
        --port $SERVER_PORT \
        --mock-url http://localhost:$MOCK_PORT/fruits \
        --threads $SERVER_THREADS \
        --io $SERVER_IO \
        --mode $SERVER_MODE \
        $mockless_flag \
        --silent"

    log "Handoff server command: $cmd"

    $cmd > >(tee "$OUTPUT_DIR/server-output.log") 2>&1 &
    SERVER_PID=$!

    wait_for_server "http://localhost:$SERVER_PORT/health" "Handoff server"

    if grep -q '^CARRIER ' "$OUTPUT_DIR/server-output.log" 2>/dev/null; then
        log "Carriers: $(grep '^CARRIER ' "$OUTPUT_DIR/server-output.log" | tr '\n' '; ')"
    fi
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

    local taskset_cmd=$(build_taskset_cmd "$LOAD_GEN_CPUSET")

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
        # --record-cpu
        "$asprof" --threads -e "$PROFILER_EVENT" -o "$PROFILER_FORMAT" -d "$PROFILING_DURATION_SECONDS" -f "$output_file" "$SERVER_PID"
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

export_jfr_timeline() {
    if [[ "$ENABLE_JFR" != "true" ]]; then
        return
    fi

    local jfr_path="$OUTPUT_DIR/$JFR_OUTPUT"
    local timeline_path="$OUTPUT_DIR/$JFR_TIMELINE_OUTPUT"

    if [[ -z "$timeline_path" ]]; then
        log "JFR timeline export skipped (JFR_TIMELINE_OUTPUT empty)"
        return
    fi
    if [[ ! -f "$jfr_path" ]]; then
        log "JFR timeline export skipped (file not found): $jfr_path"
        return
    fi
    if [[ ! -f "$JFR_TO_TIMELINE_SCRIPT" ]]; then
        log "JFR timeline export skipped (script missing): $JFR_TO_TIMELINE_SCRIPT"
        return
    fi

    log "Exporting JFR to timeline..."
    jbang "$JFR_TO_TIMELINE_SCRIPT" --input "$jfr_path" --output "$timeline_path"
    log "Timeline output: $timeline_path"
}

# ============================================================================
# Start JFR
# ============================================================================

start_jfr() {
    if [[ "$ENABLE_JFR" != "true" ]]; then
        return
    fi

    local jcmd="$JAVA_HOME/bin/jcmd"
    if [[ ! -x "$jcmd" ]]; then
        error "jcmd not found at $jcmd"
    fi

    local settings_path
    settings_path=$(write_jfr_settings)

    local output_file="$OUTPUT_DIR/$JFR_OUTPUT"
    "$jcmd" "$SERVER_PID" JFR.start \
        name="$JFR_RECORDING_NAME" \
        settings="$settings_path" \
        filename="$output_file" \
        delay="${PROFILING_DELAY_SECONDS}s" \
        duration="${PROFILING_DURATION_SECONDS}s" \
        dumponexit=true > /dev/null

    JFR_STARTED=true
    log "JFR scheduled after ${PROFILING_DELAY_SECONDS}s for ${PROFILING_DURATION_SECONDS}s"
    log "JFR output: $output_file"
    log "JFR events enabled: $JFR_EVENTS"
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
    local pidstat_args=()

    if [[ "$PIDSTAT_HANDOFF_DETAILED" == "true" ]]; then
        pidstat_args+=("-u" "-w" "-t" "-l")
    fi

    pidstat "${pidstat_args[@]}" -p "$SERVER_PID" "$PIDSTAT_INTERVAL" > "$output_file" 2>&1 &
    PIDSTAT_PID=$!

    log "pidstat running (PID: $PIDSTAT_PID)"

    if [[ -n "${MOCK_PID:-}" ]]; then
        log "Starting pidstat for mock server (PID: $MOCK_PID)..."

        local mock_output_file="$OUTPUT_DIR/$PIDSTAT_MOCK_OUTPUT"

        pidstat -p "$MOCK_PID" "$PIDSTAT_INTERVAL" > "$mock_output_file" 2>&1 &
        PIDSTAT_MOCK_PID=$!

        log "pidstat running for mock server (PID: $PIDSTAT_MOCK_PID)"
    fi
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

    if [[ -n "${PIDSTAT_MOCK_PID:-}" ]]; then
        log "Stopping pidstat for mock server..."
        kill "$PIDSTAT_MOCK_PID" 2>/dev/null || true
        wait "$PIDSTAT_MOCK_PID" 2>/dev/null || true
        log "pidstat output: $OUTPUT_DIR/$PIDSTAT_MOCK_OUTPUT"
    fi

    if [[ -n "${PIDSTAT_LOAD_GEN_PID:-}" ]]; then
        log "Stopping pidstat for load generator..."
        kill "$PIDSTAT_LOAD_GEN_PID" 2>/dev/null || true
        wait "$PIDSTAT_LOAD_GEN_PID" 2>/dev/null || true
        log "pidstat output: $OUTPUT_DIR/$PIDSTAT_LOAD_GEN_OUTPUT"
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

    perf stat $PERF_STAT_ARGS -p "$SERVER_PID" -o "$output_file" -D "$profiling_delay_ms" --timeout "$profiling_duration_ms" &
    PERF_STAT_PID=$!

    log "perf stat running (PID: $PERF_STAT_PID) after ${PROFILING_DELAY_SECONDS}s for ${PROFILING_DURATION_SECONDS}s"
}

# Note: perf stat stops automatically after the sleep duration, no explicit stop needed

capture_all_threads() {
    if [[ -n "${THREADS_CAPTURED:-}" ]]; then
        return
    fi
    THREADS_CAPTURED=true

    local thread_dump="$OUTPUT_DIR/server-threads.txt"
    {
        echo "=== Server (PID: $SERVER_PID) ==="
        "$JAVA_HOME/bin/jcmd" "$SERVER_PID" Thread.print 2>/dev/null || true
        if [[ -n "${MOCK_PID:-}" ]]; then
            echo ""
            echo "=== Mock (PID: $MOCK_PID) ==="
            "$JAVA_HOME/bin/jcmd" "$MOCK_PID" Thread.print 2>/dev/null || true
        fi
    } > "$thread_dump"
    log "Thread dump: $thread_dump"

    SERVER_TIDS=()
    for tid_dir in /proc/$SERVER_PID/task/*/; do
        SERVER_TIDS+=("$(basename "$tid_dir")")
    done
}

start_perf_sched() {
    if [[ "$ENABLE_PERF_SCHED" != "true" ]]; then
        return
    fi

    log "Starting perf sched record for handoff server (PID: $SERVER_PID)..."

    capture_all_threads

    local output_file="$OUTPUT_DIR/$PERF_SCHED_OUTPUT"
    local profiling_duration=$PROFILING_DURATION_SECONDS

    # Record scheduling events (migrations + context switches) for the server process
    sudo perf sched record -p "$SERVER_PID" -o "$output_file" -- sleep "$profiling_duration" &
    PERF_SCHED_PID=$!

    log "perf sched recording (PID: $PERF_SCHED_PID) for ${profiling_duration}s"
}

stop_perf_sched() {
    if [[ -n "${PERF_SCHED_PID:-}" ]]; then
        wait "$PERF_SCHED_PID" 2>/dev/null || true
        local output_file="$OUTPUT_DIR/$PERF_SCHED_OUTPUT"
        if [[ -f "$output_file" ]]; then
            log "perf sched output: $output_file"

            # Scheduling latency per thread
            local latency="$OUTPUT_DIR/perf-sched-latency.txt"
            sudo perf sched latency -i "$output_file" --sort max > "$latency" 2>/dev/null || true
            log "perf sched latency: $latency"

            # Thread CPU distribution
            local dist="$OUTPUT_DIR/perf-sched-distribution.txt"
            sudo perf sched timehist -i "$output_file" 2>/dev/null | \
                awk 'NR>2 {print $2, $3}' | sort | uniq -c | sort -rn \
                > "$dist" 2>/dev/null || true
            log "perf sched distribution: $dist"

            # All migration events (unfiltered — see perf-sched-carrier-migrations.txt for carrier-only)
            local migrations="$OUTPUT_DIR/perf-sched-migrations.txt"
            sudo perf sched timehist -i "$output_file" --migrations 2>/dev/null | \
                grep "migrated" > "$migrations" 2>/dev/null || true
            local total_mig=$(wc -l < "$migrations" 2>/dev/null || echo 0)
            # Filter migrations of our process threads (by TID)
            local our_mig="$OUTPUT_DIR/perf-sched-our-migrations.txt"
            local tid_pattern=""
            for tid in "${SERVER_TIDS[@]}"; do
                [ -n "$tid_pattern" ] && tid_pattern="$tid_pattern|"
                tid_pattern="${tid_pattern}${tid}"
            done
            if [ -n "$tid_pattern" ]; then
                grep -E "migrated:.*\[($tid_pattern)/" "$migrations" > "$our_mig" 2>/dev/null || true
            fi
            local our_count=$(wc -l < "$our_mig" 2>/dev/null || echo 0)
            log "perf sched migrations: $total_mig total, $our_count for our threads"
            if [[ $our_count -gt 0 ]]; then
                echo "" >> "$our_mig"
                echo "=== Migration triggers (who migrated our threads) ===" >> "$our_mig"
                awk '{print $3}' "$our_mig" | grep -v "^$\|===" | sort | uniq -c | sort -rn >> "$our_mig"
            fi
        fi
    fi
}

# ============================================================================
# Wakeup Trace (bpftrace: who wakes carrier threads)
# ============================================================================

start_wakeup_trace() {
    if [[ "$ENABLE_WAKEUP_TRACE" != "true" ]]; then
        return
    fi

    if ! command -v bpftrace &>/dev/null; then
        log "WARNING: bpftrace not found, skipping wakeup trace"
        return
    fi

    capture_all_threads

    log "Starting wakeup trace for server threads (PID: $SERVER_PID)..."

    # Build bpftrace pid filter from all server TIDs
    local filter=""
    local tid_list=""
    for tid in "${SERVER_TIDS[@]}"; do
        [[ -n "$filter" ]] && filter="$filter || "
        filter="${filter}args->pid == $tid"
        [[ -n "$tid_list" ]] && tid_list="$tid_list,"
        tid_list="${tid_list}$tid"
    done

    log "Server TIDs for wakeup trace: ${#SERVER_TIDS[@]} threads"

    local output="$OUTPUT_DIR/wakeup-trace.txt"
    local profiling_duration=$PROFILING_DURATION_SECONDS

    # bpftrace aggregation: count wakeups by (waker, target, kernel stack).
    # kstack(12) captures enough frames to see eventfd_write, sock_def_readable,
    # futex_wake — stable kernel functions that classify the wakeup type.
    # Compact output at END — no multi-million-line raw file.
    sudo timeout "$profiling_duration" bpftrace -e "
tracepoint:sched:sched_waking /$filter/ {
    @by_waker_target[comm, pid, args->pid] = count();
    @by_stack[comm, pid, args->pid, kstack(12)] = count();
}" > "$output" 2>/dev/null &
    WAKEUP_TRACE_PID=$!

    log "Wakeup trace recording (PID: $WAKEUP_TRACE_PID) for ${profiling_duration}s"
}

stop_wakeup_trace() {
    if [[ -z "${WAKEUP_TRACE_PID:-}" ]]; then
        return
    fi

    wait "$WAKEUP_TRACE_PID" 2>/dev/null || true
    local output="$OUTPUT_DIR/wakeup-trace.txt"

    if [[ ! -s "$output" ]]; then
        log "WARNING: wakeup trace produced no output"
        return
    fi

    # Build TID→thread name map from jcmd Thread.print dump
    local thread_dump="$OUTPUT_DIR/server-threads.txt"
    local summary="$OUTPUT_DIR/wakeup-trace-summary.txt"

    if [[ -f "$thread_dump" ]]; then
        local script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
        jbang "$script_dir/SummarizeWakeupTrace.java" "$thread_dump" "$output" > "$summary"

        log "Wakeup trace summary: $summary"
    fi

    log "Wakeup trace raw: $output"
}

# ============================================================================
# Run Load Test
# ============================================================================

run_load_test() {
    local warmup_secs=$(parse_duration_to_seconds "$WARMUP_DURATION")
    local total_secs=$(parse_duration_to_seconds "$TOTAL_DURATION")
    local test_secs=$((total_secs - warmup_secs))

    log "Running load test for ${test_secs}s..."

    local taskset_cmd=$(build_taskset_cmd "$LOAD_GEN_CPUSET")
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
            "$LOAD_GEN_URL" > >(tee "$output_file") 2>&1 &
    else
        # Use wrk for max throughput
        log "Using wrk for max throughput"

        $taskset_cmd jbang wrk@hyperfoil \
            -t "$LOAD_GEN_THREADS" \
            -c "$LOAD_GEN_CONNECTIONS" \
            -d "${test_secs}s" \
            "$LOAD_GEN_URL" > >(tee "$output_file") 2>&1 &
    fi

    LOAD_GEN_PID=$!

    # Capture load gen threads (Hyperfoil JVM launched by jbang)
    if [[ "$ENABLE_WAKEUP_TRACE" == "true" || "$ENABLE_PERF_SCHED" == "true" ]]; then
        sleep 2
        local loadgen_jvm_pid
        loadgen_jvm_pid=$("$JAVA_HOME/bin/jcmd" 2>/dev/null | grep -i hyperfoil | awk '{print $1}' | head -1)
        if [[ -n "$loadgen_jvm_pid" ]]; then
            local thread_dump="$OUTPUT_DIR/server-threads.txt"
            {
                echo ""
                echo "=== LoadGen (PID: $loadgen_jvm_pid) ==="
                "$JAVA_HOME/bin/jcmd" "$loadgen_jvm_pid" Thread.print 2>/dev/null || true
            } >> "$thread_dump"
            log "Load gen thread dump appended (PID: $loadgen_jvm_pid)"
        fi
    fi

    if [[ "$ENABLE_PIDSTAT" == "true" ]]; then
        log "Starting pidstat for load generator (PID: $LOAD_GEN_PID)..."
        local load_gen_output_file="$OUTPUT_DIR/$PIDSTAT_LOAD_GEN_OUTPUT"
        pidstat -p "$LOAD_GEN_PID" "$PIDSTAT_INTERVAL" > "$load_gen_output_file" 2>&1 &
        PIDSTAT_LOAD_GEN_PID=$!
        log "pidstat running for load generator (PID: $PIDSTAT_LOAD_GEN_PID)"
    fi

    wait "$LOAD_GEN_PID"

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
    log "  CPU Affinity:   ${MOCK_CPUSET:-<none>}"
    log ""
    log "Handoff Server:"
    log "  Port:           $SERVER_PORT"
    log "  Threads:        $SERVER_THREADS"
    log "  Mode:           $SERVER_MODE"
    log "  Mockless:       $SERVER_MOCKLESS"
    log "  I/O Type:       $SERVER_IO"
    local effective_poller="$SERVER_POLLER_MODE"
    if [[ -z "$effective_poller" && "$SERVER_MODE" == "NETTY_SCHEDULER" ]]; then
        effective_poller="3 (default for NETTY_SCHEDULER)"
    elif [[ -z "$effective_poller" && "$SERVER_USE_MPSC" == "true" ]]; then
        effective_poller="4 (default for MPSC scheduler)"
    fi
    log "  Poller Mode:    ${effective_poller:-<JVM default>}"
    log "  FJ Parallelism: ${SERVER_FJ_PARALLELISM:-<default>}"
    log "  CPU Affinity:   ${SERVER_CPUSET:-<none>}"
    log "  VT Mode:        ${SERVER_VT_MODE:-longlived}"
    log "  Sticky ELs:     $SERVER_STICKY"
    log "  MPSC Scheduler: $SERVER_USE_MPSC"
    log "  Extra JVM Args: ${SERVER_JVM_ARGS:-<none>}"
    log ""
    log "Load Generator:"
    log "  Connections:    $LOAD_GEN_CONNECTIONS"
    log "  Threads:        $LOAD_GEN_THREADS"
    log "  Rate:           ${LOAD_GEN_RATE:-<max throughput>}"
    log "  CPU Affinity:   ${LOAD_GEN_CPUSET:-<none>}"
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
    log "JFR:"
    log "  Enabled:        $ENABLE_JFR"
    if [[ "$ENABLE_JFR" == "true" ]]; then
        log "  Events:         $JFR_EVENTS"
        log "  Settings File:  ${JFR_SETTINGS_FILE:-<auto>}"
        log "  Output:         $JFR_OUTPUT"
        log "  Recording Name: $JFR_RECORDING_NAME"
        log "  Delay:          ${PROFILING_DELAY_SECONDS}s"
        log "  Duration:       ${PROFILING_DURATION_SECONDS}s"
        log "  Timeline Output: ${JFR_TIMELINE_OUTPUT:-<disabled>}"
    fi
    log ""
    log "pidstat:"
    log "  Enabled:        $ENABLE_PIDSTAT"
    if [[ "$ENABLE_PIDSTAT" == "true" ]]; then
        log "  Interval:       ${PIDSTAT_INTERVAL}s"
        log "  Output:         $PIDSTAT_OUTPUT"
        log "  Mock Output:    $PIDSTAT_MOCK_OUTPUT"
        log "  Load Gen Output: $PIDSTAT_LOAD_GEN_OUTPUT"
        log "  Handoff Detailed: $PIDSTAT_HANDOFF_DETAILED"
    fi
    log ""
    log "perf stat:"
    log "  Enabled:        $ENABLE_PERF_STAT"
    if [[ "$ENABLE_PERF_STAT" == "true" ]]; then
        log "  Output:         $PERF_STAT_OUTPUT"
        log "  Extra Args:     ${PERF_STAT_ARGS:-<none>}"
    fi
    log ""
    log "Output Directory: $OUTPUT_DIR"
    log "=============================================="
}

# ============================================================================
# Main
# ============================================================================

main() {
    # Parse command line arguments (override env vars)
    while [[ $# -gt 0 ]]; do
        case "$1" in
            # Server
            --mode)             SERVER_MODE="$2"; shift 2 ;;
            --threads)          SERVER_THREADS="$2"; shift 2 ;;
            --mockless)         SERVER_MOCKLESS=true; shift ;;
            --io)               SERVER_IO="$2"; shift 2 ;;
            --poller-mode)      SERVER_POLLER_MODE="$2"; shift 2 ;;
            --fj-parallelism)   SERVER_FJ_PARALLELISM="$2"; shift 2 ;;
            --server-cpuset)    SERVER_CPUSET="$2"; shift 2 ;;
            --jvm-args)         SERVER_JVM_ARGS="$2"; shift 2 ;;
            --vt-mode)          SERVER_VT_MODE="$2"; shift 2 ;;
            --ws)               SERVER_WS="true"; shift ;;
            --topology)         SERVER_TOPOLOGY="true"; shift ;;
            --sticky)           SERVER_STICKY="true"; shift ;;
            --mpsc-scheduler)   SERVER_USE_MPSC="true"; shift ;;
            # Mock
            --mock-port)        MOCK_PORT="$2"; shift 2 ;;
            --mock-think-time)  MOCK_THINK_TIME_MS="$2"; shift 2 ;;
            --mock-threads)     MOCK_THREADS="$2"; shift 2 ;;
            --mock-cpuset)      MOCK_CPUSET="$2"; shift 2 ;;
            # Load generator
            --connections)      LOAD_GEN_CONNECTIONS="$2"; shift 2 ;;
            --load-threads)     LOAD_GEN_THREADS="$2"; shift 2 ;;
            --duration)         LOAD_GEN_DURATION="$2"; shift 2 ;;
            --rate)             LOAD_GEN_RATE="$2"; shift 2 ;;
            --load-cpuset)      LOAD_GEN_CPUSET="$2"; shift 2 ;;
            # Timing
            --warmup)           WARMUP_DURATION="$2"; shift 2 ;;
            --total-duration)   TOTAL_DURATION="$2"; shift 2 ;;
            # Profiling
            --profiler)         ENABLE_PROFILER=true; shift ;;
            --profiler-path)    ASYNC_PROFILER_PATH="$2"; shift 2 ;;
            --profiler-event)   PROFILER_EVENT="$2"; shift 2 ;;
            --jfr)              ENABLE_JFR=true; shift ;;
            --jfr-events)       JFR_EVENTS="$2"; shift 2 ;;
            --perf-stat)        ENABLE_PERF_STAT=true; shift ;;
            --perf-sched)      ENABLE_PERF_SCHED=true; shift ;;
            --wakeup-trace)    ENABLE_WAKEUP_TRACE=true; shift ;;
            --perf-stat-args)   PERF_STAT_ARGS="$2"; shift 2 ;;
            --no-pidstat)       ENABLE_PIDSTAT=false; shift ;;
            # Output
            --output-dir)       OUTPUT_DIR="$2"; shift 2 ;;
            # Help
            --help|-h)
                cat << 'EOF'
Benchmark Runner Script

Usage: ./run-benchmark.sh [OPTIONS]

All options can also be set via environment variables (shown in parentheses).
CLI flags take precedence over environment variables.

Server:
  --mode <mode>             Server mode (SERVER_MODE, default: NON_VIRTUAL_NETTY)
                            Modes: NON_VIRTUAL_NETTY, REACTIVE, VIRTUAL_NETTY, NETTY_SCHEDULER
  --threads <n>             Event loop threads (SERVER_THREADS, default: 2)
  --mockless                Skip mock server, inline Jackson work (SERVER_MOCKLESS)
  --io <type>               I/O type: epoll, nio, io_uring (SERVER_IO, default: epoll)
  --poller-mode <n>         jdk.pollerMode: 1, 2, or 3 (SERVER_POLLER_MODE)
  --fj-parallelism <n>      ForkJoinPool parallelism (SERVER_FJ_PARALLELISM)
  --server-cpuset <cpus>    Server CPU pinning, e.g. "2,3" (SERVER_CPUSET, default: 2,3)
  --vt-mode <mode>          VT mode: longlived or perreq (SERVER_VT_MODE, default: longlived)
  --sticky                  Enable stickyAffinity for VT event loops (SERVER_STICKY, VIRTUAL_NETTY only)
  --mpsc-scheduler          Use MPSC scheduler instead of FJP (SERVER_USE_MPSC, VIRTUAL_NETTY only)
                            Defaults poller mode to 3 (per-carrier sub-pollers)
  --jvm-args <args>         Additional JVM arguments (SERVER_JVM_ARGS)

Mock Server:
  --mock-port <port>        Mock server port (MOCK_PORT, default: 8080)
  --mock-think-time <ms>    Response delay in ms (MOCK_THINK_TIME_MS, default: 1)
  --mock-threads <n>        Number of threads (MOCK_THREADS, default: 1)
  --mock-cpuset <cpus>      Mock server CPU pinning (MOCK_CPUSET, default: 4,5)

Load Generator:
  --connections <n>         Number of connections (LOAD_GEN_CONNECTIONS, default: 100)
  --load-threads <n>        Number of threads (LOAD_GEN_THREADS, default: 2)
  --duration <dur>          Test duration (LOAD_GEN_DURATION, default: 30s)
  --rate <n>                Target rate for wrk2; omit for max throughput (LOAD_GEN_RATE)
  --load-cpuset <cpus>      Load generator CPU pinning (LOAD_GEN_CPUSET, default: 0,1)

Timing:
  --warmup <dur>            Warmup duration (WARMUP_DURATION, default: 10s)
  --total-duration <dur>    Total test duration (TOTAL_DURATION, default: 30s)

Profiling:
  --profiler                Enable async-profiler (ENABLE_PROFILER)
  --profiler-path <path>    Path to async-profiler (ASYNC_PROFILER_PATH)
  --profiler-event <event>  Profiler event type (PROFILER_EVENT, default: cpu)
  --jfr                     Enable JFR events (ENABLE_JFR)
  --jfr-events <events>     Comma-separated JFR events or "all" (JFR_EVENTS, default: all)
  --perf-stat               Enable perf stat (ENABLE_PERF_STAT)
  --perf-stat-args <args>   Extra perf stat arguments (PERF_STAT_ARGS)
  --perf-sched              Enable perf sched recording (ENABLE_PERF_SCHED)
  --wakeup-trace            Trace thread wakeup sources via bpftrace (ENABLE_WAKEUP_TRACE)
                            Outputs wakeup counts by (waker, target, kernel stack).
                            Cross-reference TIDs with server-threads.txt for names.
                            Requires bpftrace + sudo.
  --no-pidstat              Disable pidstat collection (ENABLE_PIDSTAT)

Output:
  --output-dir <dir>        Output directory (OUTPUT_DIR, default: ./benchmark-results)

Environment-only settings:
  JAVA_HOME                 Path to Java installation (required)
  JAVA_OPTS                 JVM options (default: -Xms1g -Xmx1g)
  PROFILING_DELAY_SECONDS   Profiling start delay (default: 10)
  PROFILING_DURATION_SECONDS Profiling duration (default: 10)

Examples:

  # Virtual Netty mode, mockless
  ./run-benchmark.sh --mode virtual_netty --threads 2 --mockless

  # With CPU pinning
  ./run-benchmark.sh --mode netty_scheduler --threads 4 \
    --server-cpuset 2,3 --mock-cpuset 4,5 --load-cpuset 0,1

  # With profiling
  ./run-benchmark.sh --mode netty_scheduler --profiler --profiler-path /path/to/ap

  # Rate-limited test
  ./run-benchmark.sh --rate 10000 --total-duration 60s --warmup 15s

  # JVM args override
  ./run-benchmark.sh --mode virtual_netty --jvm-args "-XX:+PrintGCDetails"
EOF
                exit 0
                ;;
            *)
                error "Unknown option: $1. Use --help for usage."
                ;;
        esac
    done

    # Validate configuration
    validate_config

    # Create output directory
    mkdir -p "$OUTPUT_DIR"

    # Print configuration
    print_config | tee "$OUTPUT_DIR/$CONFIG_OUTPUT"

    # Check jbang
    check_jbang

    # Build JARs
    build_jars

    # Start servers
    if [[ "$SERVER_MOCKLESS" != "true" ]]; then
        start_mock_server
    else
        log "Mockless mode: skipping mock server"
    fi
    start_handoff_server

    # Run warmup (no profiling/pidstat)
    run_warmup

    # Start monitoring after warmup
    start_jfr
    start_profiler
    start_pidstat
    start_perf_stat
    start_perf_sched
    start_wakeup_trace

    # Run actual load test
    run_load_test

    # Stop monitoring
    stop_wakeup_trace
    stop_perf_sched
    stop_profiler
    stop_pidstat
    export_jfr_timeline

    if [[ "$ENABLE_JFR" == "true" ]]; then
        log "JFR output: $OUTPUT_DIR/$JFR_OUTPUT"
        if [[ -n "$JFR_TIMELINE_OUTPUT" ]]; then
            log "Timeline output: $OUTPUT_DIR/$JFR_TIMELINE_OUTPUT"
        fi
    fi

    log "Benchmark complete!"
    log "Results in: $OUTPUT_DIR"
}

main "$@"
