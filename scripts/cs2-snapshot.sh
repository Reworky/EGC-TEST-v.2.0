#!/usr/bin/env bash
# Замер статистики CS2 по всем привязанным Steam-аккаунтам: что именно Steam обновляет. Базу бота только читает.
# Запуск НА СЕРВЕРЕ: cd /root/gamebot && git pull origin main && bash scripts/cs2-snapshot.sh
#   первый запуск - базовый замер, следующие - сравнение с ним; «bash scripts/cs2-snapshot.sh reset» - начать заново.
# Замеры лежат в /root/cs2-snapshots (вне репозитория). SteamID и ключ в вывод не попадают.
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=/root/cs2-snapshots
mkdir -p "$DIR"
chmod 700 "$DIR"
KEY=$(docker exec gamebot printenv STEAM_API_KEY 2>/dev/null || true)
if [ -z "$KEY" ]; then echo "STEAM_API_KEY в контейнере не найден."; exit 1; fi
TMP=$(mktemp /tmp/cs2-ids.XXXXXX.sql)
IDS=$(mktemp /tmp/cs2-ids.XXXXXX.txt)
trap 'rm -f "$TMP" "$IDS"' EXIT
echo "SELECT 'STEAM=' || cs2steam_id64 FROM app_users WHERE cs2steam_id64 IS NOT NULL ORDER BY cs2linked_at DESC NULLS LAST LIMIT 300;" > "$TMP"
bash scripts/run-sql.sh "$TMP" | grep -o 'STEAM=[0-9]\+' | cut -d= -f2 | sort -u > "$IDS" || true
if [ ! -s "$IDS" ]; then echo "Привязанные Steam-аккаунты не найдены."; exit 1; fi
CS2_KEY="$KEY" python3 -I scripts/cs2-snapshot.py "$IDS" "$DIR" "${1:-}"
