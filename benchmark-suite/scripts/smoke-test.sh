#!/bin/bash

# Smoke test script for benchmark suite
# Runs short benchmark tests to verify the suite works correctly
# Suitable for CI/CD validation

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Test configuration - short duration for CI
TEST_DURATION=5
TEST_CONNECTIONS=2
HTTP_PORT=8080
BINARY_PORT=9090

echo "======================================"
echo "Benchmark Suite Smoke Test"
echo "======================================"
echo "Duration: ${TEST_DURATION}s per test"
echo "Connections: ${TEST_CONNECTIONS}"
echo ""

# Check if jbang is available
if ! command -v jbang &> /dev/null; then
    echo "Installing jbang..."
    curl -Ls https://sh.jbang.dev | bash -s - app setup
    export PATH="$HOME/.jbang/bin:$PATH"
fi

# Install wrk if needed
echo "Installing wrk tool..."
jbang app install wrk@hyperfoil 2>&1 | grep -v "already installed" || true

# Build the project if needed
if [ ! -f "$PROJECT_DIR/target/http-server.jar" ] || [ ! -f "$PROJECT_DIR/target/binary-server.jar" ]; then
    echo "Building benchmark suite..."
    cd "$PROJECT_DIR/.."
    mvn clean package -DskipTests -pl benchmark-suite -am -q
    cd "$SCRIPT_DIR"
fi

# Set JVM arguments for virtual threads
VTHREAD_ARGS="--enable-preview -Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler"
JVM_ARGS="-Xmx512m -Xms512m"

# Function to start server
start_server() {
    local name=$1
    local jar=$2
    shift 2
    local java_args="$@"
    
    echo "Starting $name..."
    java $JVM_ARGS $VTHREAD_ARGS $java_args -jar "$PROJECT_DIR/target/$jar" > "/tmp/${name}.log" 2>&1 &
    eval "${name}_pid=$!"
}

# Function to wait for port
wait_for_port() {
    local port=$1
    local max_wait=$2
    local waited=0
    
    while ! nc -z localhost $port 2>/dev/null; do
        sleep 0.5
        waited=$((waited + 1))
        if [ $waited -ge $((max_wait * 2)) ]; then
            return 1
        fi
    done
    return 0
}

# Cleanup function
cleanup() {
    echo ""
    echo "Cleaning up..."
    [ -n "$binary_server_pid" ] && kill $binary_server_pid 2>/dev/null || true
    [ -n "$http_server_pid" ] && kill $http_server_pid 2>/dev/null || true
    wait 2>/dev/null || true
}

trap cleanup EXIT INT TERM

# Run test with custom scheduler
run_test() {
    local scheduler=$1
    echo ""
    echo "===== Testing with $scheduler scheduler ====="
    
    # Start binary server
    start_server binary_server binary-server.jar \
        -DBINARY_PORT=$BINARY_PORT \
        -DTHINK_TIME_MS=0 \
        -DUSER_COUNT=10
    
    if ! wait_for_port $BINARY_PORT 10; then
        echo "ERROR: Binary server failed to start"
        cat /tmp/binary_server.log 2>/dev/null || true
        exit 1
    fi
    echo "Binary server running (PID: $binary_server_pid)"
    
    # Start HTTP server
    start_server http_server http-server.jar \
        -DHTTP_PORT=$HTTP_PORT \
        -DBACKEND_HOST=localhost \
        -DBACKEND_PORT=$BINARY_PORT \
        -DSCHEDULER=$scheduler \
        -DEVENT_LOOPS=1
    
    if ! wait_for_port $HTTP_PORT 10; then
        echo "ERROR: HTTP server failed to start"
        cat /tmp/http_server.log 2>/dev/null || true
        exit 1
    fi
    echo "HTTP server running (PID: $http_server_pid)"
    
    # Give servers a moment to stabilize
    sleep 1
    
    # Run load test
    echo "Running load test..."
    local wrk_output=$(jbang wrk@hyperfoil -c $TEST_CONNECTIONS -t 1 -d ${TEST_DURATION}s http://localhost:$HTTP_PORT/ 2>&1)
    
    # Extract throughput
    local throughput=$(echo "$wrk_output" | grep "Requests/sec:" | awk '{print $2}')
    
    echo "$wrk_output"
    echo ""
    
    if [ -z "$throughput" ]; then
        echo "ERROR: Failed to get throughput measurement"
        exit 1
    fi
    
    # Verify we got some throughput (should be > 0)
    if [ $(echo "$throughput > 0" | bc -l 2>/dev/null || echo "1") -eq 1 ]; then
        echo "✓ Test passed: Throughput = $throughput req/s"
    else
        echo "ERROR: Invalid throughput: $throughput"
        exit 1
    fi
    
    # Stop servers
    kill $http_server_pid 2>/dev/null || true
    kill $binary_server_pid 2>/dev/null || true
    wait 2>/dev/null || true
    http_server_pid=""
    binary_server_pid=""
    
    # Wait a bit between tests
    sleep 2
}

# Run tests
run_test "custom"
run_test "default"

echo ""
echo "======================================"
echo "✓ All smoke tests passed!"
echo "======================================"
