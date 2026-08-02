#!/bin/bash
set -e

cd /root/gamebot
git pull
docker build -t gamebot .
docker stop gamebot 2>/dev/null || true
docker rm gamebot 2>/dev/null || true
docker run -d \
  --name gamebot \
  --restart unless-stopped \
  --network gamebot-net \
  -p 8090:8090 \
  -v /root/gamebot/data:/data \
  --env-file /root/gamebot/.env \
  gamebot
docker logs gamebot --tail 20 -f
