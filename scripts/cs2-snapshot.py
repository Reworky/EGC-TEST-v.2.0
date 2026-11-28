#!/usr/bin/env python3
"""Замер статистики CS2 в Steam по всем привязанным аккаунтам. Запускается из cs2-snapshot.sh.

Первый запуск сохраняет базовый замер, каждый следующий сравнивает с ним (базу не перезаписывает).
Независимый признак «человек играл» - игровое время CS2 из GetRecentlyPlayedGames: если оно выросло,
а статистика не изменилась, значит Steam не отдаёт статистику. SteamID и ключ не печатаются.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.steampowered.com"
PLAYED_MINUTES = 40  # меньше - мог просто сидеть в меню

# (подпись, какие поля Steam входят в группу)
GROUPS = [
    ("убийства всего", ["total_kills"]),
    ("раунды", ["total_rounds_played"]),
    ("счётчик матчей", ["total_matches_played"]),
    ("победы в матчах", ["total_matches_won"]),
    ("последний матч (last_match_*)", ["last_match_*"]),
    ("урон последнего матча", ["last_match_damage"]),
    ("оружие (AK, M4, AWP)", ["total_kills_ak47", "total_kills_m4a1", "total_kills_awp"]),
    ("раунды на Dust2/Inferno", ["total_wins_map_de_dust2", "total_wins_map_de_inferno"]),
    ("ослеплённые", ["total_kills_enemy_blinded"]),
    ("пистолетные раунды", ["total_wins_pistolround"]),
]


def fetch_json(url):
    """None - Steam не ответил или закрыл данные."""
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=20) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code in (400, 401, 403, 404, 500):
                return None
        except Exception:
            pass
        time.sleep(1 + attempt)
    return None


def read_account(key, steam_id):
    query = urllib.parse.urlencode({"key": key, "steamid": steam_id})
    stats_doc = fetch_json(f"{API}/ISteamUserStats/GetUserStatsForGame/v2/?appid=730&{query}")
    stats = None
    if stats_doc and "stats" in stats_doc.get("playerstats", {}):
        stats = {s["name"]: s.get("value", 0) for s in stats_doc["playerstats"]["stats"]}
    minutes = None
    played_doc = fetch_json(f"{API}/IPlayerService/GetRecentlyPlayedGames/v1/?{query}")
    if played_doc:
        for game in played_doc.get("response", {}).get("games", []):
            if game.get("appid") == 730:
                minutes = game.get("playtime_forever")
    return {"stats": stats, "minutes": minutes}


def changed(before, after, fields):
    if fields == ["last_match_*"]:
        fields = [k for k in set(before) | set(after) if k.startswith("last_match_")]
    return any(before.get(f, 0) != after.get(f, 0) for f in fields)


def compare(base, now):
    """Возвращает строки отчёта."""
    both = [i for i in now if i in base and base[i]["stats"] is not None and now[i]["stats"] is not None]
    moved, frozen, with_minutes = [], [], 0
    for steam_id in both:
        before, after = base[steam_id], now[steam_id]
        delta = None
        if before["minutes"] is not None and after["minutes"] is not None:
            with_minutes += 1
            delta = after["minutes"] - before["minutes"]
        if before["stats"] != after["stats"]:
            moved.append((delta, before["stats"], after["stats"]))
        elif delta is not None and delta >= PLAYED_MINUTES:
            frozen.append(delta)

    out = [
        f"Аккаунтов в обоих замерах с открытой статистикой: {len(both)}",
        f"Из них с видимым игровым временем: {with_minutes}",
        f"Статистика изменилась: {len(moved)}",
        f"Играли {PLAYED_MINUTES}+ минут, а статистика не изменилась вообще: {len(frozen)}",
    ]
    if frozen:
        out.append("  игровое время у них, мин: " + ", ".join(str(m) for m in sorted(frozen, reverse=True)))
    if moved:
        out.append("")
        out.append("У скольких из аккаунтов с изменившейся статистикой растёт каждая группа:")
        for label, fields in GROUPS:
            n = sum(1 for _, b, a in moved if changed(b, a, fields))
            out.append(f"  {label}: {n} из {len(moved)}")
        out.append("")
        out.append("По аккаунтам (изменение с базового замера):")
        for number, (delta, b, a) in enumerate(moved, 1):
            def diff(field):
                return a.get(field, 0) - b.get(field, 0)
            minutes = "?" if delta is None else f"+{delta}"
            out.append(
                f"  {number}: минут {minutes} | убийств +{diff('total_kills')} | раундов +{diff('total_rounds_played')}"
                f" | матчей +{diff('total_matches_played')}"
                f" | последний матч {'обновился' if changed(b, a, ['last_match_*']) else 'НЕ обновился'}"
            )
    return out


def main(ids_file, directory, reset):
    key = os.environ.get("CS2_KEY", "")
    if not key:
        sys.exit("Нет ключа Steam.")
    with open(ids_file) as f:
        ids = [line.strip() for line in f if line.strip()]
    now = {}
    for steam_id in ids:
        now[steam_id] = read_account(key, steam_id)
        time.sleep(0.3)
    open_count = sum(1 for v in now.values() if v["stats"] is not None)
    print(f"Опрошено аккаунтов: {len(now)}, статистика открыта: {open_count}")
    if open_count == 0:
        sys.exit("Steam не отдал статистику ни по одному аккаунту - замер не сохранён.")

    base_path = os.path.join(directory, "base.json")
    if reset or not os.path.exists(base_path):
        with open(base_path, "w") as f:
            json.dump({"taken_at": time.time(), "accounts": now}, f)
        print("Базовый замер сохранён. Запустите ту же команду через несколько часов (лучше на следующий день).")
        return
    with open(base_path) as f:
        base = json.load(f)
    hours = (time.time() - base["taken_at"]) / 3600
    print(f"Сравнение с базовым замером {hours:.1f} ч назад")
    print("\n".join(compare(base["accounts"], now)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], len(sys.argv) > 3 and sys.argv[3] == "reset")
