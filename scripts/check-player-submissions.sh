#!/usr/bin/env bash
# Заявки игрока на квесты по Telegram ID (активные статусы + флаги аккаунта). Только чтение.
# Запуск НА СЕРВЕРЕ: cd /root/gamebot && git pull && bash scripts/check-player-submissions.sh 7072660790
set -u
TG="${1:?укажите Telegram ID игрока}"
cd "$(dirname "$0")/.."
TMP=$(mktemp /tmp/check-subs.XXXXXX.sql)
trap 'rm -f "$TMP"' EXIT
sed "s/8866769565/$TG/g" scripts/check-player-submissions.sql > "$TMP"
bash scripts/run-sql.sh "$TMP" 2>&1 | grep -v -E "^(Successfully copied|warning)"
