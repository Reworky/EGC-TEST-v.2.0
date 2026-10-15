#!/usr/bin/env bash
# Диагностика «квест Clash Royale не засчитывается»: по Telegram ID игрока показывает тег, базу и прогресс заявок (только чтение)
# и сразу запрашивает у API Supercell, что он реально отдаёт по этому тегу. Запускать НА СЕРВЕРЕ:
#   cd /root/gamebot && git pull && bash scripts/check-player-royale.sh 5031454177
# Токен берётся из окружения контейнера и никуда не печатается.
set -u
TG="${1:?укажите Telegram ID игрока}"
cd "$(dirname "$0")/.."
TMP=$(mktemp /tmp/check-royale.XXXXXX.sql)
trap 'rm -f "$TMP"' EXIT

sed "s/5031454177/$TG/g" scripts/check-player-royale.sql > "$TMP"
bash scripts/run-sql.sh "$TMP"

echo "SELECT 'TAG=' || clash_royale_tag FROM app_users WHERE telegram_id = $TG;" > "$TMP"
TAG=$(bash scripts/run-sql.sh "$TMP" | grep -o 'TAG=[#A-Z0-9]\+' | head -1 | cut -d= -f2 | tr -d '#')
if [ -z "$TAG" ]; then
  echo "У игрока не привязан тег Clash Royale (или игрок не найден)."
  exit 0
fi
echo "=== Ответ API Clash Royale по тегу #$TAG ==="
bash scripts/probe-supercell-api.sh - "$TAG" -
