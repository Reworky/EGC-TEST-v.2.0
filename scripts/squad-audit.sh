#!/usr/bin/env bash
# Выгрузки для проверки метрик по отрядам: каждый scripts/squad-audit/*.sql -> CSV (разделитель ;, UTF-8) в /root/squad-audit/. Только чтение.
# Запуск НА СЕРВЕРЕ: cd /root/gamebot && git pull && bash scripts/squad-audit.sh      (один запрос: bash scripts/squad-audit.sh b1)
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=/root/squad-audit
mkdir -p "$OUT"
TMP=$(mktemp /tmp/squad-audit.XXXXXX.sql)
trap 'rm -f "$TMP"' EXIT
for f in scripts/squad-audit/${1:-}*.sql; do
  name=$(basename "$f" .sql)
  q=$(grep -v '^--' "$f" | tr '\n' ' ' | sed "s/'/''/g")
  echo "CALL CSVWRITE('/out/${name}.csv', '${q}', 'charset=UTF-8 fieldSeparator=;');" >> "$TMP"
done
docker cp gamebot:/app/app.jar /root/app.jar
docker run --rm --network gamebot-net \
  -v /root/gamebot/data:/data -v /root/app.jar:/app.jar:ro -v "$TMP":/query.sql:ro \
  maven:3.9.8-eclipse-temurin-21 bash -c "cd /tmp && jar xf /app.jar BOOT-INF/lib/h2-2.2.224.jar && java -cp BOOT-INF/lib/h2-2.2.224.jar org.h2.tools.RunScript -url 'jdbc:h2:file:/data/game-platform-bot;AUTO_SERVER=TRUE' -user sa -password '' -script /query.sql -showResults" 2>&1 | grep -v -E "^(Successfully copied|warning)"
# H2 в режиме AUTO_SERVER выполняет запрос в процессе бота, поэтому CSVWRITE пишет внутрь контейнера gamebot (/out) - забираем оттуда.
docker cp gamebot:/out/. "$OUT"/ && docker exec gamebot rm -rf /out
echo "--- готовые файлы (строк с заголовком) ---"
wc -l "$OUT"/*.csv
