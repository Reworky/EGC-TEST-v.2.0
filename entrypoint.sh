#!/bin/sh
# Kill any process holding port 8080 before starting
PID=$(fuser 8080/tcp 2>/dev/null)
if [ -n "$PID" ]; then
  echo "[entrypoint] Killing stale process on port 8080: $PID"
  kill -9 $PID 2>/dev/null
  sleep 1
fi
exec java -jar /app/app.jar
