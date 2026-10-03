#!/usr/bin/env bash
# Установка ops-бота на сервер (один раз, от root): cd /root/gamebot && git pull && bash scripts/install-ops-bot.sh
# Повторный запуск безопасен (обновляет скрипт и юнит, файл с токеном не трогает).
# Перед первым запуском: создайте бота в @BotFather, узнайте свой Telegram ID (бот @userinfobot), затем пропишите
# TOKEN и ID в /etc/gamebot-ops.env (скрипт создаст файл-заготовку) и запустите установку ещё раз.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Запустите от root"; exit 1; }
REPO=/root/gamebot
ENV_FILE=/etc/gamebot-ops.env

command -v python3 >/dev/null || { echo "Нужен python3 (apt install python3)"; exit 1; }
install -m 0755 "$REPO/scripts/ops-bot.py" /usr/local/bin/gamebot-ops-bot

if [ ! -f "$ENV_FILE" ]; then
  umask 077
  cat > "$ENV_FILE" <<'ENVF'
# Токен нового бота из @BotFather и ваш числовой Telegram ID. Файл читает только root.
OPS_BOT_TOKEN=
OPS_OWNER_ID=
ENVF
  chmod 600 "$ENV_FILE"
  echo "Создан $ENV_FILE - впишите в него OPS_BOT_TOKEN и OPS_OWNER_ID, затем запустите установку снова."
  exit 0
fi
chmod 600 "$ENV_FILE"
if ! grep -qE '^OPS_BOT_TOKEN=.+' "$ENV_FILE" || ! grep -qE '^OPS_OWNER_ID=[0-9]+' "$ENV_FILE"; then
  echo "В $ENV_FILE не заполнены OPS_BOT_TOKEN и/или OPS_OWNER_ID."
  exit 1
fi

cat > /etc/systemd/system/gamebot-ops-bot.service <<'UNIT'
[Unit]
Description=gamebot ops bot (управление сервером из Telegram)
After=network-online.target docker.service
Wants=network-online.target

[Service]
EnvironmentFile=/etc/gamebot-ops.env
ExecStart=/usr/bin/python3 /usr/local/bin/gamebot-ops-bot
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable gamebot-ops-bot.service
systemctl restart gamebot-ops-bot.service
sleep 2
systemctl --no-pager --lines=5 status gamebot-ops-bot.service || true
echo "Готово. Напишите вашему ops-боту /start. Журнал: journalctl -u gamebot-ops-bot -f"
