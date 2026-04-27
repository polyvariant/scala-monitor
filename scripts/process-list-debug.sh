#!/usr/bin/env bash
PIDS=$(jps | awk '{print $1}' | paste -sd'|')
echo "=== jps ==="
jps
echo "=== ps ==="
ps -eo pid=,%mem=,rss=,args= -ww | grep -E "^\s*($PIDS)\s"
