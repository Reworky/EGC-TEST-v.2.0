#!/usr/bin/env bash
# Диагностика: какие поля реально отдают API Supercell для профиля игрока (Brawl Stars / Clash Royale / Clash of Clans).
# Запускать НА СЕРВЕРЕ (ключи Supercell привязаны к его IP):
#   cd /root/gamebot && git pull && bash scripts/probe-supercell-api.sh BS_TAG CR_TAG COC_TAG
# Теги - любые публичные, например свои (с # или без; "-" пропускает игру).
# Токены берутся из окружения контейнера gamebot и НИКУДА не печатаются - выводится только структура ответа
# (имена полей, значения простых полей, число элементов в списках, ачивки). Данные профиля публичные.
set -u

CONTAINER="${GAMEBOT_CONTAINER:-gamebot}"
BS_TAG="${1:--}"
CR_TAG="${2:--}"
COC_TAG="${3:--}"

env_of() { docker exec "$CONTAINER" printenv "$1" 2>/dev/null; }

summarize() {
python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception as e:
    print("  не JSON:", str(e)[:120]); sys.exit(0)
if "reason" in d or "message" in d:
    print("  ОШИБКА API:", d.get("reason"), "-", str(d.get("message"))[:160]); sys.exit(0)
for k, v in d.items():
    if isinstance(v, list):
        print("  [%s] список, элементов: %d" % (k, len(v)))
        if k == "achievements":
            for a in v:
                print("      ачивка %-32s value=%s target=%s stars=%s" % (a.get("name"), a.get("value"), a.get("target"), a.get("stars")))
        elif v and isinstance(v[0], dict):
            print("      ключи элемента:", ", ".join(v[0].keys()))
            print("      пример:", json.dumps(v[0], ensure_ascii=False)[:230])
    elif isinstance(v, dict):
        print("  {%s} = %s" % (k, json.dumps(v, ensure_ascii=False)[:230]))
    else:
        print("  %s = %s" % (k, json.dumps(v, ensure_ascii=False)[:100]))
'
}

fetch() { # name url token tag
  local name="$1" url="$2" token="$3" tag="$4"
  [ "$tag" = "-" ] && { echo "== $name: пропуск"; return; }
  [ -z "$token" ] && { echo "== $name: токен не найден в окружении контейнера $CONTAINER"; return; }
  tag="${tag#\#}"
  echo "== $name (#$tag)"
  curl -s -m 20 -H "Authorization: Bearer $token" "$url/players/%23$tag" | summarize
  echo
}

fetch "Brawl Stars"   "https://api.brawlstars.com/v1"   "$(env_of BRAWL_STARS_API_TOKEN)"   "$BS_TAG"
fetch "Clash Royale"  "https://api.clashroyale.com/v1"  "$(env_of CLASH_ROYALE_API_TOKEN)"  "$CR_TAG"
fetch "Clash of Clans" "https://api.clashofclans.com/v1" "$(env_of CLASH_OF_CLANS_API_TOKEN)" "$COC_TAG"
