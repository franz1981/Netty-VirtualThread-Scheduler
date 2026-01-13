#!/bin/bash

# Comparison benchmark script that runs both schedulers and compares results
# Usage: ./compare.sh [duration] [connections]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Default parameters
DURATION=${1:-60}
CONNECTIONS=${2:-20}
RATE=${3:-0}

RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"

NOW=$(date "+%y%m%d_%H_%M_%S")

echo "======================================"
echo "Running Scheduler Comparison Benchmark"
echo "======================================"
echo "Duration: ${DURATION}s"
echo "Connections: ${CONNECTIONS}"
echo "Rate: ${RATE} req/s (0 = unlimited)"
echo ""

# Run benchmark with custom scheduler
echo "===== Running with CUSTOM scheduler ====="
"$SCRIPT_DIR/benchmark.sh" -s custom -d "$DURATION" -c "$CONNECTIONS" -r "$RATE" \
    2>&1 | tee "${RESULTS_DIR}/${NOW}_custom.log"

echo ""
echo "Waiting 10 seconds before next run..."
sleep 10

# Run benchmark with default scheduler
echo "===== Running with DEFAULT scheduler ====="
"$SCRIPT_DIR/benchmark.sh" -s default -d "$DURATION" -c "$CONNECTIONS" -r "$RATE" \
    2>&1 | tee "${RESULTS_DIR}/${NOW}_default.log"

echo ""
echo "======================================"
echo "Comparison Complete"
echo "======================================"
echo ""
echo "Results saved to:"
echo "  Custom:  ${RESULTS_DIR}/${NOW}_custom.log"
echo "  Default: ${RESULTS_DIR}/${NOW}_default.log"
echo ""

# Extract and compare key metrics if wrk output is available
if command -v awk &> /dev/null; then
    echo "===== Performance Summary ====="
    echo ""
    
    extract_metrics() {
        local file=$1
        local scheduler=$2
        
        echo "--- $scheduler Scheduler ---"
        
        # Extract requests/sec
        local rps=$(grep "Requests/sec:" "$file" 2>/dev/null | awk '{print $2}')
        [ -n "$rps" ] && echo "Throughput: $rps req/s"
        
        # Extract latency
        local lat_avg=$(grep "Latency" "$file" 2>/dev/null | head -1 | awk '{print $2}')
        [ -n "$lat_avg" ] && echo "Avg Latency: $lat_avg"
        
        # Extract transfer/sec
        local transfer=$(grep "Transfer/sec:" "$file" 2>/dev/null | awk '{print $2, $3}')
        [ -n "$transfer" ] && echo "Transfer: $transfer"
        
        echo ""
    }
    
    extract_metrics "${RESULTS_DIR}/${NOW}_custom.log" "Custom"
    extract_metrics "${RESULTS_DIR}/${NOW}_default.log" "Default"
fi

echo "For detailed analysis, review the pidstat output files:"
ls -lh "${RESULTS_DIR}/${NOW}"*pidstat.txt 2>/dev/null || echo "No pidstat files found"
