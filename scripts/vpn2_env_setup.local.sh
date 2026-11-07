#!/bin/bash
# Разовая настройка .env на сервере бота для отдельного бота EGC VPN. Запускать на Mac владельца: bash scripts/vpn2_env_setup.local.sh
# Токен вводится скрыто и нигде не печатается; логин/пароль панели Москвы берутся из ~/.egc-vpn-moscow.env. Заменяются только строки, которые скрипт пишет сам (токен, панель, тестеры; остальные VPN2_* не трогаются), .env копируется в .env.bak.<время>.
set -euo pipefail

SRV=root@72.56.71.88
ENVF=/root/gamebot/.env
SRC="$HOME/.egc-vpn-moscow.env"
[ -f "$SRC" ] || { echo "Нет файла $SRC"; exit 1; }

get() { grep -E "^$1=" "$SRC" | head -1 | cut -d= -f2- | sed -e "s/^['\"]//" -e "s/['\"]$//"; }
PANEL_USER=$(get XUI_USERNAME)
PANEL_PASS=$(get XUI_PASSWORD)
PANEL_PORT=$(get XUI_PANEL_PORT)
PANEL_BASE=$(get XUI_WEB_BASE_PATH)
PANEL_BASE=${PANEL_BASE#/}
for v in PANEL_USER PANEL_PASS PANEL_PORT PANEL_BASE; do
  [ -n "${!v}" ] || { echo "В $SRC не нашёл значение для $v"; exit 1; }
done

printf "Вставьте токен бота @egc_vpn_bot от BotFather (ввод не отображается) и нажмите Enter: "
read -rs TOKEN
echo
case "$TOKEN" in
  *:*) ;;
  *) echo "Это не похоже на токен (должно быть вида 123456:ABC...). Ничего не записано."; exit 1 ;;
esac

BLOCK=$(cat <<EOF
VPN2_ENABLED=true
VPN2_OPEN=false
VPN2_BOT_TOKEN=$TOKEN
VPN2_BOT_USERNAME=egc_vpn_bot
VPN2_XUI_URL=https://5.42.100.143:$PANEL_PORT/$PANEL_BASE
VPN2_XUI_USER=$PANEL_USER
VPN2_XUI_PASS=$PANEL_PASS
VPN2_XUI_INBOUND_ID=2
VPN2_SUB_BASE=https://5.42.100.143:2096/lux5k17lnwtn21v2
VPN2_TEST_IDS=${VPN2_TEST_IDS:-631884742}
EOF
)

ssh "$SRV" "cp $ENVF $ENVF.bak.\$(date +%s) && sed -i -E '/^(VPN2_ENABLED|VPN2_OPEN|VPN2_BOT_TOKEN|VPN2_BOT_USERNAME|VPN2_XUI_URL|VPN2_XUI_USER|VPN2_XUI_PASS|VPN2_XUI_INBOUND_ID|VPN2_SUB_BASE|VPN2_TEST_IDS)=/d' $ENVF && tail -c1 $ENVF | read -r _ || echo >> $ENVF"
printf '%s\n' "$BLOCK" | ssh "$SRV" "cat >> $ENVF"
echo "Готово. Записаны переменные (значения скрыты):"
ssh "$SRV" "grep '^VPN2_' $ENVF | sed -E 's/=.*/=***/'"
