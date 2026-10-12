#!/usr/bin/env python3
"""Статический аудит кнопок «Назад» в админке (без запуска бота).

Читает GamePlatformBot.java, режет админские обработчики (handleAdminAction и вложенные handleXxxAction)
на «экраны» (ветки case / if action.equals|startsWith), собирает кнопки каждого экрана (вместе с методами send*/show*,
которые он вызывает) и проверяет: экран, у которого есть «Назад» -> admin:T, должен быть достижим одной кнопкой с экрана T.
Иначе «Назад» ведёт не туда, откуда пришли.

Запуск:  python3 scripts/admin_audit.py [-o audit/admin-report.md]
Это эвристика: ложные срабатывания возможны (динамические callback'и, экраны без своей ветки), пустой отчёт не доказывает отсутствие багов.
"""
import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

SRC = Path("src/main/java/ru/gamebot/platform/bot/GamePlatformBot.java")
# обработчик -> префикс action, который он получает через action.substring(...)
HANDLERS = {
    "handleAdminAction": "",
    "handleAdminUserAction": "user:",
    "handleAdminPacksAction": "packs:",
    "handleAdvertiserAction": "adv:",
    "handleAnalyticsAction": "an:",
    "handleFinanceAction": "fin:",
    "handleIncidentAction": "inc:",
    "handleAdminWithdrawalAction": "withdrawal:",
    "handleAdminGemPurchaseAction": "gempurchase:",
    "handleAdminRewardAction": "reward:",
    "handleQuestTemplateCallback": "qt:",
}
BACK_FUNCS = ["backMenuKeyboard", "backOnlyKeyboard", "backOrCancelKeyboard", "backRow"]
CALL_RE = re.compile(r"\b((?:send|show|open|render)\w+)\(")


def balanced_args(src, i):
    depth, j, parts, cur, instr = 1, i, [], "", False
    while j < len(src) and depth:
        c = src[j]
        if instr:
            cur += c
            if c == "\\":
                cur += src[j + 1]
                j += 1
            elif c == '"':
                instr = False
        else:
            if c == '"':
                instr = True
                cur += c
            elif c in "([{":
                depth += 1
                cur += c
            elif c in ")]}":
                depth -= 1
                if depth:
                    cur += c
            elif c == "," and depth == 1:
                parts.append(cur)
                cur = ""
            else:
                cur += c
        j += 1
    parts.append(cur)
    return [p.strip() for p in parts], j


def literal_prefix(arg):
    m = re.match(r'"((?:[^"\\]|\\.)*)"\s*(\+|$)', arg.strip())
    return (m.group(1), m.group(2) == "+") if m else (None, False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default="audit/admin-report.md")
    args = ap.parse_args()
    src = SRC.read_text(encoding="utf-8")
    line_of = lambda pos: src.count("\n", 0, pos) + 1

    methods = [(m.start(), m.group(2)) for m in re.finditer(r"\n    (private|public|protected)[^\n;=]*?\s(\w+)\([^{;]*\{\s*\n", src)]
    bodies = {}
    for k, (s, n) in enumerate(methods):
        e = methods[k + 1][0] if k + 1 < len(methods) else len(src)
        bodies.setdefault(n, (s, src[s:e]))

    # --- ветки (экраны) админских обработчиков ---
    marker = re.compile(
        r'\n[ \t]*(?:case\s+((?:"[^"\n]+"\s*,\s*)*"[^"\n]+")\s*->'
        r'|(?:\}\s*)?(?:else\s+)?if\s*\(\s*(?:action\.(equals|startsWith)\(\s*"([^"\n]+)"\s*\)|"([^"\n]+)"\s*\.equals\(\s*action\s*\)))')
    regions = {}  # ключ -> (method, текст, строка, startsWith?)
    for hname, prefix in HANDLERS.items():
        if hname not in bodies:
            continue
        hs, htext = bodies[hname]
        ms = list(marker.finditer(htext))
        for k, m in enumerate(ms):
            end = ms[k + 1].start() if k + 1 < len(ms) else len(htext)
            text = htext[m.start():end]
            keys = []
            sw = False
            if m.group(1):
                keys = re.findall(r'"([^"\n]+)"', m.group(1))
            elif m.group(3):
                keys = [m.group(3)]
                sw = m.group(2) == "startsWith"
            elif m.group(4):
                keys = [m.group(4)]
            for key in keys:
                full = prefix + key
                regions.setdefault(full, (hname, text, line_of(hs + m.start()), sw))

    # --- кнопки и «Назад» в тексте ---
    def parse_buttons(text):
        fwd, back = [], []
        for m in re.finditer(r"\bcallback\(", text):
            parts, _ = balanced_args(text, m.end())
            if len(parts) < 2:
                continue
            pref, dyn = literal_prefix(parts[1])
            if pref is None:
                continue
            (back if "Назад" in parts[0] else fwd).append(pref)
        for fn in BACK_FUNCS:
            for m in re.finditer(r"\b" + fn + r"\(", text):
                parts, _ = balanced_args(text, m.end())
                if parts:
                    pref, _d = literal_prefix(parts[0])
                    if pref:
                        back.append(pref)
        for m in re.finditer(r"\b(?:verticalWithBackMenu|numberedGridWithBackMenu)\(", text):
            parts, _ = balanced_args(text, m.end())
            if len(parts) >= 3:
                pref, _d = literal_prefix(parts[2])
                if pref:
                    back.append(pref)
        return fwd, back

    def expand(text, base_line, depth=2, seen=None):
        """Куски кода ветки + тела вызываемых send*/show* методов: [(текст, строка начала)]."""
        seen = seen if seen is not None else set()
        out = [(text, base_line)]
        if depth <= 0:
            return out
        for m in CALL_RE.finditer(text):
            name = m.group(1)
            if name in seen or name not in bodies or name in HANDLERS:
                continue
            seen.add(name)
            out += expand(bodies[name][1], line_of(bodies[name][0]), depth - 1, seen)
        return out

    ERR_RE = re.compile(r"не найден|недоступн|⚠️|❌|Ошибк|ошибк|устарел|Нет |нет данных|Неизвестн|Не удалось|уже обработан|Пусто|пуст")

    def parse_back_with_context(text, base_line):
        """Все «Назад»-цели куска: (цель, строка, фрагмент ближайшего текста сообщения, это ветка ошибки)."""
        res = []

        def add(pref, pos):
            if not pref:
                return
            ln = base_line + text.count("\n", 0, pos)
            ctx_start = max(0, text.rfind("sendText(", 0, pos))
            ctx = text[ctx_start:pos]
            lit = re.findall(r'"((?:[^"\\]|\\.)*)"', ctx)
            snippet = (lit[0] if lit else "")[:70]
            res.append((pref, ln, snippet, bool(ERR_RE.search(ctx[:400]))))

        for m in re.finditer(r"\bcallback\(", text):
            parts, _ = balanced_args(text, m.end())
            if len(parts) >= 2 and "Назад" in parts[0]:
                pref, _d = literal_prefix(parts[1])
                add(pref, m.start())
        for fn in BACK_FUNCS:
            for m in re.finditer(r"\b" + fn + r"\(", text):
                parts, _ = balanced_args(text, m.end())
                if parts:
                    pref, _d = literal_prefix(parts[0])
                    add(pref, m.start())
        for m in re.finditer(r"\b(?:verticalWithBackMenu|numberedGridWithBackMenu)\(", text):
            parts, _ = balanced_args(text, m.end())
            if len(parts) >= 3:
                pref, _d = literal_prefix(parts[2])
                add(pref, m.start())
        return res

    info = {}
    for key, (hname, text, ln, sw) in regions.items():
        pieces = expand(text, ln)
        fwd, back = [], []
        backs_ctx = []
        for (t, bl) in pieces:
            f, b = parse_buttons(t)
            fwd += f
            back += b
            backs_ctx += parse_back_with_context(t, bl)
        info[key] = {"method": hname, "line": ln, "fwd": fwd, "back": back, "ctx": backs_ctx,
                     "text": "\n".join(t for t, _ in pieces), "sw": sw}

    keys_sorted = sorted(info, key=len, reverse=True)

    def region_of(data):
        if not data.startswith("admin:"):
            return None
        d = data[len("admin:"):]
        for k in keys_sorted:
            if d == k or d.startswith(k + ":") or (k.endswith(":") and d.startswith(k)) or (info[k]["sw"] and d.startswith(k)):
                return k
        return None

    edges = defaultdict(set)    # экран -> экраны, на которые ведут его кнопки вперёд
    parents = defaultdict(set)
    for key, i in info.items():
        for d in i["fwd"]:
            t = region_of(d)
            if t and t != key:
                edges[key].add(t)
                parents[t].add(key)

    wrong = []
    for key, i in sorted(info.items()):
        seen_targets = set()
        for (b, bln, snippet, is_err) in i["ctx"]:
            if not b.startswith("admin:") or is_err or (b, bln) in seen_targets:
                continue
            seen_targets.add((b, bln))
            t = region_of(b)
            if not t or t == key:
                continue
            if key not in edges[t]:
                wrong.append((key, bln, b, t, sorted(parents[key]), snippet))

    # экраны админки, где нет ни «Назад», ни «Меню», ни «Отмена»
    exit_re = re.compile(r"Назад|Меню|Отмена|backMenuKeyboard|backOnlyKeyboard|backRow|backOrCancel|cancelKeyboard|mainMenuKeyboard|WithBackMenu")
    dead_ends = []
    for key, i in sorted(info.items()):
        txt = i["text"]
        if re.search(r"\b(sendText|sendPhotoCaption|sendCardWithPhoto|editMessage\w*)\(", txt) and not exit_re.search(txt):
            dead_ends.append((key, i["line"]))

    L = ["# Аудит «Назад» в админке (статический)", "",
         f"Источник: `{SRC}`. Экранов (веток) найдено: {len(info)}.", "",
         "Эвристика по тексту кода: проверять глазами перед правкой.", ""]
    L.append(f"## 1. «Назад» ведёт на экран, с которого сюда не попасть одной кнопкой ({len(wrong)})")
    for (k, ln, b, t, ps, sn) in wrong:
        L.append(f"- `admin:{k}` (строка {ln}, «{sn}»): «Назад» → `{b}` (экран `{t}`); сюда ведут кнопки с экранов: {', '.join('`'+p+'`' for p in ps) or '—'}")
    if not wrong:
        L.append("- нет")
    L.append(f"\n## 2. Экраны без «Назад»/«Меню»/«Отмена» ({len(dead_ends)})")
    L += [f"- `admin:{k}` (строка {ln})" for k, ln in dead_ends] or ["- нет"]
    report = "\n".join(L) + "\n"
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    print(report)
    print(f"→ записано в {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
