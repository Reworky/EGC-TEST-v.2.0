#!/usr/bin/env python3
"""Статический аудит пути игрока по Telegram-боту (без запуска бота и без Telegram).

Читает GamePlatformBot.java, строит граф «экран -> кнопки -> экран» и ищет:
  1. мёртвые кнопки: callback_data, для которого в коде нет обработчика;
  2. «тупики»: экраны, где нет ни «Назад», ни «Меню»;
  3. «неправильное Назад»: кнопка «⬅️ Назад» ведёт на экран, который сам не ведёт на текущий
     (игрок попадает в раздел, откуда не приходил);
  4. «сиротские» пункты menu:*, которые обрабатываются, но нигде не показаны кнопкой;
  5. глубину: экраны дальше 4 нажатий от главного меню.

Запуск:  python3 scripts/journey_audit.py [-o audit/journey-report.md]
Это эвристика по тексту кода: ложные срабатывания возможны, пустой отчёт не доказывает отсутствие багов.
"""
import argparse
import re
import sys
from collections import defaultdict, deque
from pathlib import Path

SRC = Path("src/main/java/ru/gamebot/platform/bot/GamePlatformBot.java")
SKIP_NAME = re.compile(r"[Aa]dmin|Mod[A-Z]|[Mm]oderat|[Ss]ponsor|[Bb]roadcast|Analytics|Alert|Incident|Finance|Advertiser|Adv[A-Z]|Suspect|Brawl(Anomal)|QuestEdit|QuestCreate|Template")
SKIP_CB = re.compile(r"^(admin|mod|adv|an|noop|withdraw_admin|wdquick|sponsor)")
EXIT_RE = re.compile(r'mainMenuKeyboard|Назад|Меню|backMenuKeyboard|backOnlyKeyboard|WithBackMenu|backRow|common:cancel|Отмена|sendMenuCategory|backOrCancel|singleMenuKeyboard|numberedGridWithBackMenu')


def read():
    return SRC.read_text(encoding="utf-8")


def balanced_args(src, i):
    """src[i] стоит сразу после '('; возвращает (список аргументов верхнего уровня, позиция после ')')."""
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
    m = re.match(r'"((?:[^"\\]|\\.)*)"\s*(\+|$)', arg)
    if not m:
        return None, False
    return m.group(1), m.group(2) == "+"  # (prefix, dynamic_tail)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default="audit/journey-report.md")
    args = ap.parse_args()
    src = read()
    line_of = lambda pos: src.count("\n", 0, pos) + 1

    methods = [(m.start(), m.group(2)) for m in re.finditer(r"\n    (private|public|protected)[^\n;=]*?\s(\w+)\([^\n]*\{\s*\n", src)]

    def method_at(pos):
        r = "?"
        for s, n in methods:
            if s <= pos:
                r = n
            else:
                break
        return r

    def body_of(name):
        for k, (s, n) in enumerate(methods):
            if n == name:
                e = methods[k + 1][0] if k + 1 < len(methods) else len(src)
                return src[s:e]
        return ""

    # --- кнопки ---
    buttons = []  # (method, label, data_prefix, dynamic, line)
    for m in re.finditer(r"\bcallback\(", src):
        parts, _ = balanced_args(src, m.end())
        if len(parts) < 2:
            continue
        label, data = parts[0], parts[1]
        pref, dyn = literal_prefix(data)
        label_txt = re.sub(r'^"|"$', "", label)[:40]
        buttons.append((method_at(m.start()), label_txt, pref, dyn, line_of(m.start()), data))

    # --- обработчики ---
    eq, starts, cases = set(), set(), set()
    for m in re.finditer(r'"([^"\n]+)"\s*\.equals\(\s*(?:data|action|sub|target)\s*\)|(?:data|action|sub)\s*\.equals\(\s*"([^"\n]+)"\s*\)', src):
        eq.add(m.group(1) or m.group(2))
    starts_data = set()
    for m in re.finditer(r'(data|action|sub|target)\s*\.startsWith\(\s*"([^"\n]+)"', src):
        starts.add(m.group(2))
        if m.group(1) == "data":
            starts_data.add(m.group(2))
    for m in re.finditer(r'case\s+((?:"[^"\n]+"\s*,\s*)*"[^"\n]+")\s*->', src):
        for lit in re.findall(r'"([^"\n]+)"', m.group(1)):
            cases.add(lit)

    def handled(data_prefix, dynamic):
        if data_prefix is None:
            return True  # динамика целиком, не проверяем
        d = data_prefix
        # admin:*-действия разбирает вложенный switch по action, остальные callback'и - только прямые проверки data.startsWith
        pool = starts if d.startswith(("admin:", "menu:", "mod:")) else starts_data
        if d in eq or any(d.startswith(p) for p in pool):
            return True
        if d.rstrip(":") in eq or (dynamic and any(p.startswith(d) or d.startswith(p) for p in pool)):
            return True
        segs = d.split(":")
        # menu:profile -> case "profile"; menu:cat:more -> case "cat:more"
        if len(segs) >= 2:
            tail = ":".join(segs[1:]).rstrip(":")
            if (tail in cases or segs[1] in cases) and (segs[0] + ":") in starts:
                return True
        return False

    dead = [(m, l, p, ln) for (m, l, p, dyn, ln, raw) in buttons
            if p and not SKIP_NAME.search(m) and not SKIP_CB.match(p) and not handled(p, dyn)]

    # --- экраны и тупики ---
    dead_ends = []
    for k, (s, name) in enumerate(methods):
        if not re.match(r"(send|show)", name) or SKIP_NAME.search(name):
            continue
        if re.search(r"Feed|Notice|Prompt|Preview|Csv|Request|BrokenMessage|BonusMessage|JoinRequest|Content$|Blocked|RegistrationStep|^sendText$|^sendPhotoCaption$|^sendCardWithPhoto$|Withdrawal(Player|Ton|Screen)", name):
            continue  # уведомления/служебные отправки, не экраны меню
        body = body_of(name)
        if not re.search(r"\b(sendText|sendPhotoCaption|sendCardWithPhoto|execute)\(", body):
            continue
        if not EXIT_RE.search(body):
            dead_ends.append((name, line_of(s)))

    # --- граф menu: -> метод ---
    menu_target = {}
    a = src.index('case "faq" -> sendFaqMenu')
    b = src.index('default -> sendMainMenu(user, mainMenuText(user));', a)
    for m in re.finditer(r'case\s+"([^"\n]+)"\s*->\s*(?:\{[^}]*?)?(send\w+|handle\w+)\(', src[a:b]):
        menu_target["menu:" + m.group(1)] = m.group(2)
    produced_menu = {p.rstrip() for (_, _, p, dyn, _, _) in buttons if p and p.startswith("menu:")}
    orphans = sorted(a for a in menu_target
                     if a not in produced_menu and not SKIP_NAME.search(menu_target[a]) and a not in {"menu:admin", "menu:moderation"})

    # --- рёбра по кнопкам внутри методов ---
    out_edges = defaultdict(set)  # метод -> {callback}
    back_of = defaultdict(set)    # метод -> {callback «Назад»}
    for (m, l, p, dyn, ln, raw) in buttons:
        if p is None:
            continue
        out_edges[m].add(p)
        if "Назад" in l:
            back_of[m].add(p)
    # callback -> методы (menu: + явные if-блоки)
    cb_method = dict(menu_target)
    cb_method["menu:main"] = "mainMenuKeyboard"
    for m in re.finditer(r'(?:"([^"\n]+)"\s*\.equals\(\s*data\s*\)|data\.equals\(\s*"([^"\n]+)"\s*\)|data\.startsWith\(\s*"([^"\n]+)"\s*\))\s*\)\s*\{(.{0,700}?)\n        \}', src, re.S):
        key = m.group(1) or m.group(2) or m.group(3)
        t = re.search(r"\b(send\w+|handle\w+)\(", m.group(4))
        if t:
            cb_method.setdefault(key, t.group(1))

    def method_for(cb):
        if cb in cb_method:
            return cb_method[cb]
        for k, v in cb_method.items():
            if cb.startswith(k) and k.endswith(":"):
                return v
        return None

    # BFS от главного меню
    start = "mainMenuKeyboard"
    dist, parent_screens = {start: 0}, defaultdict(set)
    q = deque([start])
    while q:
        cur = q.popleft()
        for cb in out_edges.get(cur, ()):
            tgt = method_for(cb)
            if not tgt or SKIP_NAME.search(tgt):
                continue
            if "Назад" in " ".join(l for (mm, l, p, *_r) in buttons if mm == cur and p == cb):
                continue  # ребро «назад» не считаем вперёд
            parent_screens[tgt].add(cur)
            if tgt not in dist:
                dist[tgt] = dist[cur] + 1
                q.append(tgt)

    wrong_back = []
    for scr, ds in sorted(dist.items()):
        if scr == start:
            continue
        for bcb in back_of.get(scr, ()):
            tgt = method_for(bcb)
            if tgt and tgt not in parent_screens[scr] and tgt != scr:
                wrong_back.append((scr, bcb, tgt, sorted(parent_screens[scr])))
    deep = sorted((d, n) for n, d in dist.items() if d >= 5)

    # --- отчёт ---
    L = ["# Аудит пути игрока (статический)", "",
         f"Источник: `{SRC}`. Кнопок: {len(buttons)}, экранов в графе от главного меню: {len(dist)}.", "",
         "Это эвристика по тексту кода — проверять глазами перед правкой.", ""]
    L.append(f"## 1. Кнопки без обработчика ({len(dead)})")
    L += [f"- `{p}` — кнопка «{l}» в `{m}` (строка {ln})" for (m, l, p, ln) in dead] or ["- нет"]
    L.append(f"\n## 2. Экраны без «Назад»/«Меню» ({len(dead_ends)})")
    L += [f"- `{n}` (строка {ln})" for (n, ln) in dead_ends] or ["- нет"]
    L.append(f"\n## 3. «Назад» ведёт не туда, откуда пришли ({len(wrong_back)})")
    L += [f"- `{s}`: «Назад» → `{b}` (экран `{t}`), а сюда ведут только: {', '.join(ps) or '—'}" for (s, b, t, ps) in wrong_back] or ["- нет"]
    L.append(f"\n## 4. Пункты menu:*, которые обрабатываются, но не показаны ни одной кнопкой ({len(orphans)})")
    L += [f"- `{a}` → `{menu_target[a]}`" for a in orphans] or ["- нет"]
    L.append(f"\n## 5. Глубокие экраны (5+ нажатий от главного меню) ({len(deep)})")
    L += [f"- `{n}` — {d} нажатий" for d, n in deep] or ["- нет"]
    report = "\n".join(L) + "\n"
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    print(report)
    print(f"→ записано в {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
