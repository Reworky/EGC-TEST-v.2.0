#!/usr/bin/env bash
# Печатает по 5 самых активных игроков (по числу выполненных квестов, заходившим недавно) с привязанным тегом
# для Brawl Stars / Clash Royale / Clash of Clans - чтобы взять теги для scripts/probe-supercell-api.sh.
# Запускать НА СЕРВЕРЕ:  cd /root/gamebot && git pull && bash scripts/top-tags.sh
# Только чтение (SELECT), бот не останавливается: H2 в режиме AUTO_SERVER пускает второе подключение.
# Драйвер H2 берётся из самого app.jar контейнера, чтобы версия совпала с ботовой; временные файлы - в /tmp.
set -eu

CONTAINER="${GAMEBOT_CONTAINER:-gamebot}"
WORK=/tmp/gb-h2
DB_URL="jdbc:h2:file:/data/game-platform-bot;AUTO_SERVER=TRUE"

rm -rf "$WORK" && mkdir -p "$WORK"
docker cp "$CONTAINER:/app/app.jar" "$WORK/app.jar"
mkdir -p "$WORK/lib"
if command -v unzip >/dev/null 2>&1; then
  unzip -o -q -j "$WORK/app.jar" 'BOOT-INF/lib/h2-*.jar' -d "$WORK/lib"
elif command -v python3 >/dev/null 2>&1; then
  python3 - "$WORK/app.jar" "$WORK/lib" <<'PY'
import sys, zipfile, os
jar, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as z:
    for n in z.namelist():
        if n.startswith("BOOT-INF/lib/h2-") and n.endswith(".jar"):
            with open(os.path.join(out, os.path.basename(n)), "wb") as f:
                f.write(z.read(n))
PY
else
  echo "Нужен unzip или python3 на хосте: apt-get install -y unzip" >&2; exit 1
fi
ls "$WORK/lib" | grep -q '^h2-' || { echo "h2-*.jar не найден в app.jar" >&2; exit 1; }
docker exec "$CONTAINER" rm -rf /tmp/gb-h2
docker cp "$WORK/lib" "$CONTAINER:/tmp/gb-h2"

run_sql() {
  docker exec "$CONTAINER" java -cp '/tmp/gb-h2/*' org.h2.tools.Shell \
    -url "$DB_URL" -user sa -password "" -sql "$1"
}

for spec in "Brawl Stars:brawl_stars_tag" "Clash Royale:clash_royale_tag" "Clash of Clans:clash_of_clans_tag"; do
  name="${spec%%:*}"; col="${spec##*:}"
  echo "== $name"
  run_sql "SELECT $col AS tag, nickname, completed_quests AS quests, last_activity_date AS last_seen FROM app_users WHERE $col IS NOT NULL AND $col <> '' AND last_activity_date >= DATEADD('DAY', -7, CURRENT_DATE) ORDER BY completed_quests DESC LIMIT 5;"
  echo
done

rm -rf "$WORK"
docker exec "$CONTAINER" rm -rf /tmp/gb-h2
