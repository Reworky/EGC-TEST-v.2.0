#!/usr/bin/env bash
# Сводка ошибок бота и мини-аппа. Только читает логи, ничего не меняет.
# Запуск на сервере: bash /root/gamebot/scripts/check-errors.sh [часы, по умолчанию 24]
H="${1:-24}"
LOGS="docker logs --since ${H}h gamebot"
NGINX_LOG="${NGINX_LOG:-/var/log/nginx/access.log}"
norm() { sed -E 's/[0-9]{6,}/N/g'; }

echo "=== Контейнер ==="
docker inspect -f 'запущен: {{.State.StartedAt}} | статус: {{.State.Status}} | рестартов: {{.RestartCount}}' gamebot 2>&1
echo "(логи показывают только время с последнего запуска контейнера; после деплоя счётчики начинаются заново)"

echo; echo "=== Бот: ERROR/WARN за ${H}ч (сверху самые частые) ==="
$LOGS 2>&1 | grep -E " (ERROR|WARN) " \
  | sed -E 's/^[^ ]+ +(ERROR|WARN) +[0-9]+ --- \[[^]]*\] /\1 /' | norm | cut -c1-170 | sort | uniq -c | sort -rn | head -25

echo; echo "=== Бот: типы исключений ==="
$LOGS 2>&1 | grep -E "^[a-zA-Z0-9_.]+(Exception|Error)" | cut -c1-140 | norm | sort | uniq -c | sort -rn | head -15

echo; echo "=== Бот: причины «Failed to process update» (кроме «бот заблокирован игроком») ==="
$LOGS 2>&1 | grep -A40 "Failed to process update" | grep -E "Caused by" | grep -v "bot was blocked by the user" \
  | cut -c1-200 | norm | sort | uniq -c | sort -rn | head -10
echo "(заблокировали бота: $($LOGS 2>&1 | grep -c 'bot was blocked by the user') раз, это норма)"

echo; echo "=== Регистрация: падения из-за ника (после фикса должно быть 0) ==="
echo "IDX_APP_USERS_NICKNAME: $($LOGS 2>&1 | grep -c IDX_APP_USERS_NICKNAME)"
echo "«2 results» по нику: $($LOGS 2>&1 | grep -c 'Query did not return a unique result')"

echo; echo "=== Реклама: AdsGram бот-реклама (ответ не 200) ==="
$LOGS 2>&1 | grep "advbot returned" | sed -E 's/tgid=[0-9]+/tgid=N/' | cut -c1-240 | sort | uniq -c | sort -rn | head -5

echo; echo "=== Мини-апп: сбои сервера в логе бота ==="
$LOGS 2>&1 | grep -E "Request processing failed|Servlet.service\(\)" | norm | cut -c1-220 | sort | uniq -c | sort -rn | head -10
echo "(пусто = сбоев нет)"

echo; echo "=== Мини-апп: ответы API 4xx/5xx за сегодня (nginx, без 401 и сканеров) ==="
if [ -r "$NGINX_LOG" ]; then
  grep "$(date +%d/%b/%Y)" "$NGINX_LOG" | awk '$9 ~ /^(4|5)/ && $9 != 401 && $7 ~ /^\/api/ {print $9, $7}' \
    | cut -d'?' -f1 | sed -E 's#/[0-9]+#/N#g' | sort | uniq -c | sort -rn | head -20
  echo "(502 = бот был недоступен, обычно короткое окно рестарта; 404 на /api/profile = регистрация не завершена)"
else
  echo "нет доступа к $NGINX_LOG (задайте NGINX_LOG=путь)"
fi
