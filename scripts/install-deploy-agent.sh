#!/usr/bin/env bash
# Установка агента выкладки на сервер (один раз, от root): cd /root/gamebot && git pull && bash scripts/install-deploy-agent.sh
# Повторный запуск безопасен (обновляет агента и юниты). Запускайте сразу ПОСЛЕ обычного deploy: скрипт запомнит текущий
# коммит репозитория как «сейчас работает».
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Запустите от root"; exit 1; }
REPO=/root/gamebot
DIR=$REPO/data/deploy

install -m 0755 "$REPO/scripts/deploy-agent.sh" /usr/local/bin/gamebot-deploy-agent
mkdir -p "$DIR"
chmod 1777 "$DIR"   # бот (внутри контейнера) кладёт сюда заявку; удалять чужие файлы нельзя (sticky)
[ -f "$DIR/deployed_commit" ] || git -C "$REPO" log -1 --format='%h %s' > "$DIR/deployed_commit"

cat > /etc/systemd/system/gamebot-deploy.service <<'UNIT'
[Unit]
Description=gamebot deploy agent (обработка заявки от бота)

[Service]
Type=oneshot
ExecStart=/usr/local/bin/gamebot-deploy-agent run
TimeoutStartSec=1800
UNIT

cat > /etc/systemd/system/gamebot-deploy.path <<'UNIT'
[Unit]
Description=Следит за заявкой выкладки от бота

[Path]
PathChanged=/root/gamebot/data/deploy/request
Unit=gamebot-deploy.service

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/gamebot-deploy-check.service <<'UNIT'
[Unit]
Description=gamebot: проверка новых коммитов

[Service]
Type=oneshot
ExecStart=/usr/local/bin/gamebot-deploy-agent check
UNIT

cat > /etc/systemd/system/gamebot-deploy-check.timer <<'UNIT'
[Unit]
Description=gamebot: проверка новых коммитов раз в 2 минуты

[Timer]
OnBootSec=1min
OnUnitActiveSec=2min

[Install]
WantedBy=timers.target
UNIT

systemctl daemon-reload
systemctl enable --now gamebot-deploy.path gamebot-deploy-check.timer
/usr/local/bin/gamebot-deploy-agent check
echo "Готово. Агент установлен. Сейчас работает: $(cat "$DIR/deployed_commit")"
echo "Ожидают выкладки: $(wc -l < "$DIR/pending.txt") коммит(ов)"
