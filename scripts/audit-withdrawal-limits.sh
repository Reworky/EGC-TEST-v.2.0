#!/usr/bin/env bash
# Аудит «маленькие ли лимиты на вывод»: гоняет все запросы из scripts/audit-limits/*.sql по боевой базе (только чтение) и продолжает при ошибке одного из них.
# Запуск НА СЕРВЕРЕ: cd /root/gamebot && git pull && bash scripts/audit-withdrawal-limits.sh > /root/audit-limits.txt 2>&1; затем показать файл / прислать его содержимое.
# В выводе нет токенов и личных данных: только счётчики и распределения.
cd "$(dirname "$0")/.." || exit 1
for f in scripts/audit-limits/*.sql; do
  echo
  echo "################ $(basename "$f") ################"
  if ! bash scripts/run-sql.sh "$f" 2>&1 | grep -v -E "^(Successfully copied|WARN|warning)"; then
    echo "!! ошибка в $f"
  fi
done
