#!/bin/bash

# Benchmark script for comparing custom NettyScheduler vs default scheduler
# Based on https://github.com/franz1981/quarkus-profiling-workshop/blob/master/scripts/benchmark.sh

set -e

# Default configuration
DURATION=40
WARMUP=16
PROFILING=20
WRK_THREADS=2
CONNECTIONS=10
RATE=0

# Server configuration
HTTP_PORT=8080
BINARY_PORT=9090
BACKEND_HOST=localhost
EVENT_LOOPS=2
THINK_TIME_MS=0
USER_COUNT=100

# Scheduler: "custom" or "default"
SCHEDULER=custom

# CPU affinity (Linux only)
HTTP_SERVER_CPUS="0,1"
BINARY_SERVER_CPUS="2"
LOAD_GEN_CPUS="3,4"

# JVM configuration
JVM_ARGS="-Xmx1g -Xms1g -XX:+UseParallelGC"
ENABLE_PROFILER=false
PROFILER_EVENT=cpu
PROFILER_FORMAT=html

# Profiler options
ENABLE_PIDSTAT=true

die() {
    echo "$*"
    exit 1
}

Help() {
    echo "Benchmark script for Netty VirtualThread Scheduler comparison"
    echo ""
    echo "Syntax: benchmark.sh [OPTIONS]"
    echo "options:"
    echo "h    Display this help"
    echo "d    Duration of load test in seconds (default: 40)"
    echo "c    Number of connections (default: 10)"
    echo "r    Request rate (0 for unlimited, default: 0)"
    echo "s    Scheduler type: 'custom' or 'default' (default: custom)"
    echo "e    Event loops for HTTP server (default: 2)"
    echo "t    Think time in ms for binary server (default: 0)"
    echo "p    Enable async-profiler (default: false)"
    echo ""
}

while getopts "hd:c:r:s:e:t:p" option; do
    case $option in
        h) Help; exit;;
        d) DURATION=${OPTARG};;
        c) CONNECTIONS=${OPTARG};;
        r) RATE=${OPTARG};;
        s) SCHEDULER=${OPTARG};;
        e) EVENT_LOOPS=${OPTARG};;
        t) THINK_TIME_MS=${OPTARG};;
        p) ENABLE_PROFILER=true;;
        *) echo "Invalid option: -${OPTARG}"; Help; exit 1;;
    esac
done

# Calculate warmup and profiling durations
WARMUP=$((DURATION*2/5))
PROFILING=$((DURATION/2))

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "===== Benchmark Configuration ====="
echo "Duration: ${DURATION}s (Warmup: ${WARMUP}s, Profiling: ${PROFILING}s)"
echo "Connections: ${CONNECTIONS}"
echo "Request rate: ${RATE} req/s (0 = unlimited)"
echo "Scheduler: ${SCHEDULER}"
echo "HTTP server - Port: ${HTTP_PORT}, Event loops: ${EVENT_LOOPS}"
echo "Binary server - Port: ${BINARY_PORT}, Think time: ${THINK_TIME_MS}ms, Users: ${USER_COUNT}"
echo "Profiler: ${ENABLE_PROFILER}"
echo "=================================="

# Check if jbang is available
if ! command -v jbang &> /dev/null; then
    echo "Installing jbang tools..."
    curl -Ls https://sh.jbang.dev | bash -s - app setup
    export PATH="$HOME/.jbang/bin:$PATH"
fi

# Install required tools
echo "===== Installing required tools ====="
jbang app install wrk@hyperfoil || true
jbang app install wrk2@hyperfoil || true
if [ "$ENABLE_PROFILER" = true ]; then
    jbang app install ap-loader@jvm-profiling-tools/ap-loader || true
fi

# Build the project if needed
if [ ! -f "$PROJECT_DIR/target/http-server.jar" ] || [ ! -f "$PROJECT_DIR/target/binary-server.jar" ]; then
    echo "===== Building benchmark suite ====="
    cd "$PROJECT_DIR/.."
    mvn clean install -DskipTests -pl benchmark-suite -am
    cd "$SCRIPT_DIR"
fi

# Set JVM arguments for virtual threads
VTHREAD_ARGS="--enable-preview -Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler"

# Function to start server with CPU affinity
start_server() {
    local name=$1
    local jar=$2
    local cpus=$3
    shift 3
    local java_args="$@"
    
    if command -v taskset >/dev/null 2>&1 && [ -n "$cpus" ]; then
        echo "Starting $name with CPU affinity: $cpus"
        taskset -c $cpus java $JVM_ARGS $VTHREAD_ARGS $java_args -jar "$PROJECT_DIR/target/$jar" &
    else
        echo "Starting $name (no CPU affinity)"
        java $JVM_ARGS $VTHREAD_ARGS $java_args -jar "$PROJECT_DIR/target/$jar" &
    fi
    
    eval "${name}_pid=$!"
}

# Cleanup function
cleanup() {
    echo "===== Cleaning up ====="
    [ -n "$http_server_pid" ] && kill $http_server_pid 2>/dev/null || true
    [ -n "$binary_server_pid" ] && kill $binary_server_pid 2>/dev/null || true
    [ -n "$wrk_pid" ] && kill $wrk_pid 2>/dev/null || true
    [ -n "$pidstat_pid" ] && kill $pidstat_pid 2>/dev/null || true
}

trap cleanup EXIT INT TERM

# Start binary server first
echo "===== Starting Binary Server ====="
start_server binary_server binary-server.jar "$BINARY_SERVER_CPUS" \
    -DBINARY_PORT=$BINARY_PORT \
    -DTHINK_TIME_MS=$THINK_TIME_MS \
    -DUSER_COUNT=$USER_COUNT

sleep 2

# Verify binary server is running
if ! nc -z localhost $BINARY_PORT 2>/dev/null; then
    die "Binary server failed to start on port $BINARY_PORT"
fi
echo "Binary server is running (PID: $binary_server_pid)"

# Start HTTP server
echo "===== Starting HTTP Server ====="
start_server http_server http-server.jar "$HTTP_SERVER_CPUS" \
    -DHTTP_PORT=$HTTP_PORT \
    -DBACKEND_HOST=$BACKEND_HOST \
    -DBACKEND_PORT=$BINARY_PORT \
    -DSCHEDULER=$SCHEDULER \
    -DEVENT_LOOPS=$EVENT_LOOPS

sleep 2

# Verify HTTP server is running
if ! nc -z localhost $HTTP_PORT 2>/dev/null; then
    die "HTTP server failed to start on port $HTTP_PORT"
fi
echo "HTTP server is running (PID: $http_server_pid)"

# Show CPU affinity if available
if command -v taskset >/dev/null 2>&1; then
    echo "===== CPU Affinity ====="
    echo "HTTP server: $(taskset -cp $http_server_pid 2>/dev/null || echo 'N/A')"
    echo "Binary server: $(taskset -cp $binary_server_pid 2>/dev/null || echo 'N/A')"
fi

# Start load generator
FULL_URL="http://localhost:${HTTP_PORT}/"
echo "===== Starting Load Generator ====="
echo "Target URL: $FULL_URL"

if [ "$RATE" != "0" ]; then
    echo "Starting fixed rate test at ${RATE} req/s"
    LOAD_CMD="jbang wrk2@hyperfoil -R ${RATE} -c ${CONNECTIONS} -t ${WRK_THREADS} -d ${DURATION}s ${FULL_URL}"
else
    echo "Starting all-out test"
    LOAD_CMD="jbang wrk@hyperfoil -c ${CONNECTIONS} -t ${WRK_THREADS} -d ${DURATION}s ${FULL_URL}"
fi

if command -v taskset >/dev/null 2>&1 && [ -n "$LOAD_GEN_CPUS" ]; then
    taskset -c $LOAD_GEN_CPUS $LOAD_CMD &
else
    $LOAD_CMD &
fi
wrk_pid=$!

# Wait for warmup
echo "===== Warming up for ${WARMUP}s ====="
sleep $WARMUP

# Start monitoring
NOW=$(date "+%y%m%d_%H_%M_%S")
RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"
RESULT_PREFIX="${RESULTS_DIR}/${NOW}_${SCHEDULER}"

echo "===== Starting monitoring and profiling ====="

# Start pidstat if available (Linux only)
if [ "$ENABLE_PIDSTAT" = true ] && command -v pidstat >/dev/null 2>&1; then
    echo "Starting pidstat for HTTP server (PID: $http_server_pid)"
    pidstat -p $http_server_pid 1 > "${RESULT_PREFIX}_pidstat.txt" &
    pidstat_pid=$!
fi

# Start profiler if enabled
if [ "$ENABLE_PROFILER" = true ]; then
    echo "Starting async-profiler for ${PROFILING}s"
    jbang ap-loader@jvm-profiling-tools/ap-loader profiler \
        -e ${PROFILER_EVENT} -t -d ${PROFILING} \
        -f "${RESULT_PREFIX}_${PROFILER_EVENT}.${PROFILER_FORMAT}" \
        $http_server_pid &
    profiler_pid=$!
fi

# Continue monitoring for the profiling duration
sleep $PROFILING

# Stop pidstat
if [ -n "$pidstat_pid" ]; then
    kill -TERM $pidstat_pid 2>/dev/null || true
fi

echo "===== Waiting for load test to complete ====="
wait $wrk_pid || true

# Wait for profiler to complete
if [ "$ENABLE_PROFILER" = true ] && [ -n "$profiler_pid" ]; then
    wait $profiler_pid || true
fi

echo "===== Benchmark completed ====="
echo "Results saved to: ${RESULT_PREFIX}*"
ls -lh "${RESULT_PREFIX}"* 2>/dev/null || true

# Cleanup will be handled by trap
