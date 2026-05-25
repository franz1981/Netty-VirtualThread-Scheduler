#!/bin/bash
# Trace carrier thread migrations and correlate with JFR queue depth.
#
# Usage: ./trace-migrations.sh <server_pid> <duration_seconds> <output_dir>
#
# Produces:
#   migrations.log  - timestamped migration events (from bpftrace)
#   netty-loom.jfr  - JFR with VirtualThreadTaskRuns (queue depth)
#   correlation.txt - merged timeline showing migrations alongside queue depth changes

set -euo pipefail

PID=${1:?Usage: $0 <server_pid> <duration_seconds> <output_dir>}
DURATION=${2:-5}
OUTDIR=${3:-/tmp/migration-trace}
JAVA_HOME="${JAVA_HOME:?JAVA_HOME must be set}"

mkdir -p "$OUTDIR"

# Find carrier thread TIDs
echo "Finding carrier threads for PID $PID..."
TIDS=()
NAMES=()
for tid_dir in /proc/$PID/task/*/; do
    tid=$(basename "$tid_dir")
    name=$(cat "/proc/$PID/task/$tid/comm" 2>/dev/null || true)
    if [[ "$name" == Thread-* ]]; then
        TIDS+=("$tid")
        NAMES+=("$name")
        echo "  $name (tid=$tid)"
    fi
done

if [ ${#TIDS[@]} -eq 0 ]; then
    echo "ERROR: no carrier threads found"
    exit 1
fi

# Build bpftrace filter
FILTER=""
for tid in "${TIDS[@]}"; do
    [ -n "$FILTER" ] && FILTER="$FILTER || "
    FILTER="${FILTER}args->pid == $tid"
done

# Start JFR recording
echo "Starting JFR recording (${DURATION}s)..."
"$JAVA_HOME/bin/jcmd" "$PID" JFR.start \
    name=migration-trace \
    settings=/dev/stdin \
    duration="${DURATION}s" \
    filename="$OUTDIR/netty-loom.jfr" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0">
  <event name="io.netty.loom.VirtualThreadTaskRuns">
    <setting name="enabled">true</setting>
  </event>
  <event name="io.netty.loom.NettyRunIo">
    <setting name="enabled">true</setting>
  </event>
  <event name="io.netty.loom.VirtualThreadTaskSubmit">
    <setting name="enabled">true</setting>
  </event>
</configuration>
EOF

# Run bpftrace to capture migrations + context switches with timestamps
echo "Tracing migrations for ${DURATION}s..."
sudo timeout $((DURATION + 2)) bpftrace -e "
tracepoint:sched:sched_migrate_task /$FILTER/ {
    printf(\"MIGRATE %lld tid=%d %s cpu=%d->%d\\n\",
        nsecs, args->pid, args->comm, args->orig_cpu, args->dest_cpu);
}
tracepoint:sched:sched_switch /$FILTER/ {
    if (args->prev_pid != args->next_pid) {
        printf(\"SWITCH_OUT %lld tid=%d %s cpu=%d prev_state=%d\\n\",
            nsecs, args->prev_pid, args->prev_comm, cpu, args->prev_state);
    }
}
tracepoint:sched:sched_switch /$(echo "$FILTER" | sed 's/args->pid/args->next_pid/g')/ {
    printf(\"SWITCH_IN %lld tid=%d %s cpu=%d\\n\",
        nsecs, args->next_pid, args->next_comm, cpu);
}
" > "$OUTDIR/migrations.log" 2>&1 || true

echo "Waiting for JFR to complete..."
sleep $((DURATION + 2))

# Extract JFR queue depth timeline
echo "Extracting JFR data..."
"$JAVA_HOME/bin/jfr" print --json \
    --events "io.netty.loom.VirtualThreadTaskRuns" \
    "$OUTDIR/netty-loom.jfr" 2>/dev/null | python3 -c "
import sys, json
from datetime import datetime

data = json.load(sys.stdin)
events = data['recording']['events']

def parse_ts(ts_str):
    base = ts_str[:19]
    frac = ts_str[19:]
    sign_pos = frac.index('+') if '+' in frac[1:] else frac.index('-', 1)
    frac_str = frac[1:sign_pos] if frac[0] == '.' else '0'
    frac_str = frac_str.ljust(9, '0')[:9]
    dt = datetime.strptime(base, '%Y-%m-%dT%H:%M:%S')
    return int(dt.timestamp() * 1_000_000_000) + int(frac_str)

for e in events:
    v = e['values']
    ts = parse_ts(v['startTime'])
    carrier = v.get('carrierThread', {})
    cname = carrier.get('javaName', '?') if carrier else '?'
    qdepth = v.get('queueDepthBefore', -1)
    tasks = v.get('tasksExecuted', 0)
    print(f'DRAIN {ts} carrier={cname} queueBefore={qdepth} tasks={tasks}')
" > "$OUTDIR/jfr-drains.log" 2>/dev/null

# Merge and correlate
echo "Correlating..."
sort -k2 -n "$OUTDIR/migrations.log" "$OUTDIR/jfr-drains.log" > "$OUTDIR/correlation.txt" 2>/dev/null

echo ""
echo "=== Summary ==="
migrate_count=$(grep -c "^MIGRATE" "$OUTDIR/migrations.log" 2>/dev/null || echo 0)
switch_out=$(grep -c "^SWITCH_OUT" "$OUTDIR/migrations.log" 2>/dev/null || echo 0)
drain_count=$(grep -c "^DRAIN" "$OUTDIR/jfr-drains.log" 2>/dev/null || echo 0)
echo "Migrations: $migrate_count"
echo "Context switches out: $switch_out"
echo "JFR drain events: $drain_count"
echo ""
echo "Output: $OUTDIR/"
echo "  migrations.log   - kernel scheduling events"
echo "  jfr-drains.log   - JFR queue depth timeline"
echo "  correlation.txt  - merged timeline (sort by timestamp)"
