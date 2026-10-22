#!/usr/bin/env bash
# Диагностика журнала боёв Brawl Stars: что API реально отдаёт по каждому бою (режим, тип, место, результат, боец игрока).
# Запускать НА СЕРВЕРЕ (ключ Supercell привязан к его IP):
#   cd /root/gamebot && git pull && bash scripts/probe-brawl-battlelog.sh '#P8GGLROP2'
# Токен берётся из окружения контейнера gamebot и НИКУДА не печатается. Выводятся только публичные данные боёв этого тега.
set -u

CONTAINER="${GAMEBOT_CONTAINER:-gamebot}"
TAG="${1:?укажите тег игрока, например '#P8GGLROP2'}"
TAG="${TAG#\#}"
TOKEN="$(docker exec "$CONTAINER" printenv BRAWL_STARS_API_TOKEN 2>/dev/null)"
[ -z "$TOKEN" ] && { echo "токен BRAWL_STARS_API_TOKEN не найден в окружении контейнера $CONTAINER"; exit 1; }

curl -s -m 20 -H "Authorization: Bearer $TOKEN" "https://api.brawlstars.com/v1/players/%23$TAG/battlelog" | TAG="$TAG" python3 -c '
import json, os, sys
try:
    d = json.load(sys.stdin)
except Exception as e:
    print("не JSON:", str(e)[:120]); sys.exit(0)
if "reason" in d or "message" in d:
    print("ОШИБКА API:", d.get("reason"), "-", str(d.get("message"))[:160]); sys.exit(0)
tag = os.environ["TAG"].upper()
items = d.get("items", [])
print("боёв в ответе:", len(items))
print("(в журнале время UTC; ключи battle - что лежит в самом бою)\n")
for it in items[:14]:
    b = it.get("battle", {})
    me = None
    for p in b.get("players", []):
        if p.get("tag", "").replace("#", "").upper().replace("O", "0") == tag.replace("O", "0"):
            me = p
    for team in b.get("teams", []):
        for p in team:
            if p.get("tag", "").replace("#", "").upper().replace("O", "0") == tag.replace("O", "0"):
                me = p
    print(it.get("battleTime"), "|", b.get("mode"), "|", b.get("type"), "| rank=", b.get("rank"), "| result=", b.get("result"),
          "| trophyChange=", b.get("trophyChange"), "| боец=", (me or {}).get("brawler", {}).get("name"), "| игрок найден=", me is not None)
    print("    ключи battle:", ", ".join(b.keys()))
    pl = b.get("players", [])
    if pl:
        print("    ключи игрока:", ", ".join(pl[0].keys()))
'
