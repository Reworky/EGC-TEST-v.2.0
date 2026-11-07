#!/bin/bash
# Задать ОДНУ переменную VPN2_* в .env сервера бота, не трогая остальные (с Mac владельца).
# Примеры:
#   bash scripts/vpn2_env_set.local.sh VPN2_CRYPTOBOT_TOKEN        (токен CryptoBot, ввод скрыт)
#   bash scripts/vpn2_env_set.local.sh VPN2_CARD_DETAILS           (реквизиты для перевода, текст как показать игроку)
#   bash scripts/vpn2_env_set.local.sh VPN2_EXC_PAY_ENABLED true   (включить оплату EXC)
# Перед записью .env копируется в .env.bak.<время>. После записи нужен деплой (перезапуск контейнера).
set -euo pipefail

KEY="${1:?Укажите имя переменной, например VPN2_CRYPTOBOT_TOKEN}"
if ! [[ "$KEY" =~ ^VPN2_[A-Z0-9_]+$ ]]; then
  echo "Можно менять только переменные VPN2_*"
  exit 1
fi
VALUE="${2:-}"
if [ -z "$VALUE" ]; then
  case "$KEY" in
    *TOKEN*|*SECRET*|*PASS*) printf "Значение %s (ввод не отображается), затем Enter: " "$KEY"; read -rs VALUE; echo ;;
    *) printf "Значение %s, затем Enter: " "$KEY"; read -r VALUE ;;
  esac
fi
[ -n "$VALUE" ] || { echo "Пустое значение, ничего не записано."; exit 1; }

SRV=root@72.56.71.88
ENVF=/root/gamebot/.env
ssh "$SRV" "cp $ENVF $ENVF.bak.\$(date +%s) && sed -i '/^$KEY=/d' $ENVF && (tail -c1 $ENVF | read -r _ || echo >> $ENVF)"
printf '%s=%s\n' "$KEY" "$VALUE" | ssh "$SRV" "cat >> $ENVF"
echo "Записано: $KEY (значение скрыто). Теперь сделайте деплой, чтобы бот подхватил."
