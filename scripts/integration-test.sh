#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
MONITOR="${1:-$PROJECT_DIR/scala-monitor}"

if [ ! -f "$MONITOR" ]; then
  echo "ERROR: $MONITOR not found. Build first: scala-cli --power package . -o $MONITOR"
  exit 1
fi

chmod +x "$MONITOR"

cleanup() {
  echo "--- cleanup ---"
  [ -n "${SCALA_CLI_PID:-}" ] && kill "$SCALA_CLI_PID" 2>/dev/null || true
  [ -n "${SBT_PID:-}" ] && kill "$SBT_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  rm -rf /tmp/sleep-sbt /tmp/Sleep.scala
}
trap cleanup EXIT

# --- Launch scala-cli process ---
echo "--- launching scala-cli process ---"
printf '@main def sleep(): Unit = Thread.sleep(30000)\n' > /tmp/Sleep.scala
scala-cli run /tmp/Sleep.scala &
SCALA_CLI_PID=$!

# --- Launch sbt ---
echo "--- launching sbt ---"
rm -rf /tmp/sleep-sbt
mkdir -p /tmp/sleep-sbt/project
cat > /tmp/sleep-sbt/build.sbt <<'SBT'
name := "sleep"
SBT
cat > /tmp/sleep-sbt/project/build.properties <<'PROPS'
sbt.version=1.10.11
PROPS
(cd /tmp/sleep-sbt && sbt -Dsbt.log.noformat=true --allow-empty "eval Thread.sleep(30000)") &
SBT_PID=$!

echo "--- waiting 5s for processes to start ---"
sleep 5

# --- Diagnostics ---
echo "--- ps diagnostic: looking for sbt/scala-cli ---"
ps aux | grep -E 'xsbt.boot.Boot|sbt-launch|Sleep' | grep -v grep || echo "  (none found)"

echo "--- checking sbt is alive ---"
if kill -0 "$SBT_PID" 2>/dev/null; then
  echo "  sbt PID $SBT_PID is alive"
else
  echo "  sbt PID $SBT_PID is DEAD"
fi

echo "--- checking scala-cli is alive ---"
if kill -0 "$SCALA_CLI_PID" 2>/dev/null; then
  echo "  scala-cli PID $SCALA_CLI_PID is alive"
else
  echo "  scala-cli PID $SCALA_CLI_PID is DEAD"
fi

# --- Run scala-monitor ---
echo ""
echo "=== scala-monitor output ==="
OUTPUT=$("$MONITOR")
echo "$OUTPUT"

# --- Assertions ---
echo ""
echo "=== assertions ==="
PASS=true

assert() {
  local label="$1" pattern="$2"
  if echo "$OUTPUT" | grep -q "$pattern"; then
    echo "  PASS: $label"
  else
    echo "  FAIL: $label"
    PASS=false
  fi
}

assert "Bloop detected" "Bloop"
assert "Header present" "SCALA PROCESS MONITOR"
assert "RAM column present" "RAM"
assert "sbt detected" "[Ss]bt"

# Also check --help
if "$MONITOR" --help | grep -q "filter"; then
  echo "  PASS: --help contains filter"
else
  echo "  FAIL: --help missing filter"
  PASS=false
fi

if "$MONITOR" --help | grep -q "tui"; then
  echo "  PASS: --help contains tui"
else
  echo "  FAIL: --help missing tui"
  PASS=false
fi

# Verify one-shot mode still works with -o pid
PID_OUTPUT=$("$MONITOR" -o pid)
if echo "$PID_OUTPUT" | grep -qE '^[0-9]+$'; then
  echo "  PASS: -o pid returns PIDs"
else
  echo "  FAIL: -o pid did not return numeric PIDs"
  echo "        output was: $PID_OUTPUT"
  PASS=false
fi

if $PASS; then
  echo ""
  echo "ALL ASSERTIONS PASSED"
else
  echo ""
  echo "SOME ASSERTIONS FAILED"
  exit 1
fi
