#!/bin/bash

# Docker-based benchmark runner
# Usage: ./docker-benchmark.sh [custom|default] [duration] [connections]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

SCHEDULER=${1:-custom}
DURATION=${2:-60}
CONNECTIONS=${3:-20}

echo "======================================"
echo "Docker-based Benchmark"
echo "======================================"
echo "Scheduler: $SCHEDULER"
echo "Duration: ${DURATION}s"
echo "Connections: $CONNECTIONS"
echo ""

# Build if needed
if [ ! -f "$PROJECT_DIR/target/http-server.jar" ]; then
    echo "Building benchmark suite..."
    cd "$PROJECT_DIR/.."
    mvn clean package -DskipTests -pl benchmark-suite -am
    cd "$SCRIPT_DIR"
fi

# Build Docker images
echo "Building Docker images..."
cd "$PROJECT_DIR"
docker compose -f docker/docker-compose.yml build

# Start servers
echo "Starting servers..."
if [ "$SCHEDULER" = "custom" ]; then
    docker compose -f docker/docker-compose.yml up -d binary-server http-server-custom
    HTTP_PORT=8080
else
    docker compose -f docker/docker-compose.yml up -d binary-server http-server-default
    HTTP_PORT=8081
fi

# Wait for servers to be ready
echo "Waiting for servers to be ready..."
sleep 5

# Check if servers are running
if ! docker compose -f docker/docker-compose.yml ps | grep -q "Up"; then
    echo "ERROR: Servers failed to start"
    docker compose -f docker/docker-compose.yml logs
    docker compose -f docker/docker-compose.yml down
    exit 1
fi

# Wait for HTTP endpoint to be available
echo "Checking HTTP server availability..."
for i in {1..30}; do
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:$HTTP_PORT/ | grep -q "200\|500"; then
        echo "HTTP server is ready"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "ERROR: HTTP server did not become available"
        docker compose -f docker/docker-compose.yml logs
        docker compose -f docker/docker-compose.yml down
        exit 1
    fi
    sleep 1
done

# Ensure wrk is installed
if ! command -v jbang &> /dev/null; then
    echo "Installing jbang..."
    curl -Ls https://sh.jbang.dev | bash -s - app setup
    export PATH="$HOME/.jbang/bin:$PATH"
fi

jbang app install wrk@hyperfoil || true

# Run benchmark
echo ""
echo "Running benchmark for ${DURATION}s with ${CONNECTIONS} connections..."
echo "URL: http://localhost:$HTTP_PORT/"
echo ""

jbang wrk@hyperfoil -c $CONNECTIONS -t 2 -d ${DURATION}s http://localhost:$HTTP_PORT/

# Show container stats
echo ""
echo "======================================"
echo "Container Resource Usage"
echo "======================================"
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" \
    $(docker compose -f docker/docker-compose.yml ps -q)

# Cleanup
echo ""
echo "Stopping containers..."
docker compose -f docker/docker-compose.yml down

echo ""
echo "Benchmark complete!"
