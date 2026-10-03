#!/usr/bin/env bash
# Запускает .sql-файл по боевой базе H2 через контейнер с Java (сам бот при этом не останавливается).
# Использование на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/audit-withdrawals.sql
# Файлы из репозитория только читают базу; перед запуском чужого/нового файла посмотрите, что в нём.
set -euo pipefail
FILE=$(readlink -f "${1:?укажите путь к .sql файлу}")
docker cp gamebot:/app/app.jar /root/app.jar
docker run --rm --network gamebot-net \
  -v /root/gamebot/data:/data -v /root/app.jar:/app.jar:ro -v "$FILE":/query.sql:ro \
  maven:3.9.8-eclipse-temurin-21 bash -c "cd /tmp && jar xf /app.jar BOOT-INF/lib/h2-2.2.224.jar && java -cp BOOT-INF/lib/h2-2.2.224.jar org.h2.tools.RunScript -url 'jdbc:h2:file:/data/game-platform-bot;AUTO_SERVER=TRUE' -user sa -password '' -script /query.sql -showResults"
