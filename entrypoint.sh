#!/bin/sh
# Kill ALL lingering java processes before starting (orphans from --network host restarts)
echo "[entrypoint] Killing any lingering java processes..."
fuser -k 8080/tcp 2>/dev/null || true
kill -9 $(pgrep -f "java -jar" 2>/dev/null) 2>/dev/null || true
sleep 2
echo "[entrypoint] Starting bot..."
exec java -jar /app/app.jar
