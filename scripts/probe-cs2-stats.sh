#!/usr/bin/env bash
# Живая проверка Steam Web API для CS2: какие поля статистики реально отдаёт аккаунт игрока (оружие, карты, урон, last_match_* и т.д.).
# Нужна перед новой пачкой квестов: имена полей не гадаем, сверяем с живым ответом. Только чтение. Запускать НА СЕРВЕРЕ:
#   cd /root/gamebot && git pull && bash scripts/probe-cs2-stats.sh 7656119XXXXXXXXXX      (SteamID64)
#   cd /root/gamebot && git pull && bash scripts/probe-cs2-stats.sh 123456789              (Telegram ID игрока с привязанным Steam)
# Ключ берётся из окружения контейнера и никуда не печатается. Профиль Steam и «Статистика игр» игрока должны быть открыты.
set -u
ARG="${1:?укажите SteamID64 (17 цифр) или Telegram ID игрока}"
cd "$(dirname "$0")/.."
if [ "${#ARG}" -ge 16 ]; then
  STEAM="$ARG"
else
  TMP=$(mktemp /tmp/probe-cs2.XXXXXX.sql)
  trap 'rm -f "$TMP"' EXIT
  echo "SELECT 'STEAM=' || cs2_steam_id64 FROM app_users WHERE telegram_id = $ARG;" > "$TMP"
  STEAM=$(bash scripts/run-sql.sh "$TMP" | grep -o 'STEAM=[0-9]\+' | head -1 | cut -d= -f2)
  if [ -z "$STEAM" ]; then echo "У игрока не привязан Steam (или игрок не найден)."; exit 0; fi
fi
KEY=$(docker exec gamebot printenv STEAM_API_KEY 2>/dev/null)
if [ -z "$KEY" ]; then echo "STEAM_API_KEY в контейнере не найден."; exit 1; fi
BODY=$(curl -s --max-time 25 "https://api.steampowered.com/ISteamUserStats/GetUserStatsForGame/v2/?appid=730&key=$KEY&steamid=$STEAM")
if ! echo "$BODY" | grep -q '"stats"'; then
  echo "Steam не вернул статистику (закрытый профиль или нет игры). Ответ (без ключа):"; echo "$BODY" | head -c 400; echo; exit 0
fi
OUT=/tmp/cs2-stats-$STEAM.txt
echo "$BODY" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for s in sorted(d['playerstats']['stats'], key=lambda x: x['name']):
    print(s['name'], s['value'])
" > "$OUT"
echo "Всего полей: $(wc -l < "$OUT"). Полный список сохранён: $OUT"
echo
echo "=== last_match_* (последний матч) ==="; grep '^last_match_' "$OUT"
echo; echo "=== Убийства по оружию (total_kills_*) ==="; grep '^total_kills_' "$OUT" | grep -v 'total_kills_headshot\|total_kills_enemy\|total_kills_against\|total_kills_hip\|total_kills_knife_fight' 
echo; echo "=== Убийства ножом, с открытого огня, снайперы ==="; grep -E '^total_kills_(knife|enemy_blinded|enemy_weapon|against_zoomed_sniper|hip)' "$OUT"
echo; echo "=== Карты (total_wins_map_*, total_rounds_map_*) ==="; grep -E '^total_(wins|rounds)_map_' "$OUT"
echo; echo "=== Урон, деньги, доминирования, прочее ==="; grep -E '^total_(damage_done|money_earned|dominations|revenges|rescued_hostages|broken_windows|contribution_score|wins_pistolround|gun_game_rounds_won|weapons_donated|rounds_played|shots_hit|shots_fired|time_played)' "$OUT"
