#!/bin/sh
# Kill ALL lingering java processes before starting (orphans from --network host restarts)
PORT="${SERVER_PORT:-8090}"
echo "[entrypoint] Killing any lingering java processes on port ${PORT}..."
fuser -k "${PORT}/tcp" 2>/dev/null || true
kill -9 $(pgrep -f "java -jar" 2>/dev/null) 2>/dev/null || true
sleep 2
echo "[entrypoint] Starting bot..."
exec java -jar /app/app.jar
