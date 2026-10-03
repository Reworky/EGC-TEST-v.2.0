#!/usr/bin/env python3
"""Ops-бот для управления сервером gamebot из Telegram (отдельный бот, не игровой).

Умеет ТОЛЬКО фиксированный набор действий: /status, /logs, /errors, /deploy, /rollback, /restart.
Произвольных команд оболочки здесь нет и быть не должно. Отвечает только владельцу (OPS_OWNER_ID), остальным молчит.
Опасные действия (деплой, откат, перезапуск) выполняются только после подтверждения кнопкой.

Деплой и откат этот бот сам не делает: он кладёт заявку в data/deploy/request ровно так же, как кнопка «Обновить бота»
в админке игрового бота, а саму выкладку выполняет уже установленный агент (gamebot-deploy-agent). Поэтому работает,
даже если игровой бот упал.

Только стандартная библиотека Python 3. Токен и ID владельца — в /etc/gamebot-ops.env (chmod 600), не в репозитории.
"""
import fcntl
import json
import logging
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN = os.environ.get("OPS_BOT_TOKEN", "").strip()
OWNER = int(os.environ.get("OPS_OWNER_ID", "0") or 0)
REPO = os.environ.get("GAMEBOT_REPO", "/root/gamebot")
DEPLOY_DIR = os.environ.get("GAMEBOT_DEPLOY_DIR", REPO + "/data/deploy")
LOCK_FILE = os.environ.get("GAMEBOT_LOCK", "/var/lock/gamebot-deploy.lock")
CONTAINER = os.environ.get("GAMEBOT_CONTAINER", "gamebot")
API = "https://api.telegram.org/bot%s/" % TOKEN

MAX_LOG_LINES = 150
DEFAULT_LOG_LINES = 40
CONFIRM_TTL = 120          # секунд на подтверждение действия
WATCH_TIMEOUT = 25 * 60    # сколько ждём окончания деплоя/отката

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("ops-bot")

_pending = {}              # nonce -> (action, expires_at)
_pending_lock = threading.Lock()

TOKEN_RE = re.compile(r"\b\d{8,12}:[A-Za-z0-9_-]{30,}\b")
SECRET_RE = re.compile(r"(?i)(token|secret|password|api[_-]?key)=[^&\s\"',]+")


def mask(text):
    text = TOKEN_RE.sub("***", text)
    return SECRET_RE.sub(lambda m: m.group(1) + "=***", text)


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def tg(method, **params):
    data = json.dumps(params).encode("utf-8")
    req = urllib.request.Request(API + method, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def send(chat_id, text, markup=None):
    if len(text) > 3900:
        text = text[:3900] + "\n…(обрезано)"
    params = {"chat_id": chat_id, "text": text, "parse_mode": "HTML", "disable_web_page_preview": True}
    if markup:
        params["reply_markup"] = markup
    try:
        tg("sendMessage", **params)
    except Exception as exc:  # сеть/Telegram — не роняем бота
        log.warning("sendMessage failed: %s", exc)


def pre(text):
    return "<pre>" + esc(mask(text)) + "</pre>"


def run(cmd, timeout=60):
    try:
        out = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, text=True, errors="replace")
        return out.returncode, out.stdout
    except subprocess.TimeoutExpired:
        return 124, "таймаут %d с" % timeout
    except Exception as exc:
        return 1, "не удалось выполнить: %s" % exc


def read_status():
    status = {}
    try:
        with open(DEPLOY_DIR + "/status", encoding="utf-8") as f:
            for line in f:
                if "=" in line:
                    key, value = line.rstrip("\n").split("=", 1)
                    status[key] = value
    except OSError:
        pass
    return status


def read_text(name):
    try:
        with open(DEPLOY_DIR + "/" + name, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def tail_lines(text, n):
    lines = text.splitlines()
    return "\n".join(l[:300] for l in lines[-n:])


# ── команды ────────────────────────────────────────────────────────────────

def cmd_help(chat):
    send(chat, "🛠 <b>Ops-бот gamebot</b>\n\n"
               "/status - состояние сервера и бота\n"
               "/logs [N] - последние N строк лога (по умолчанию %d, максимум %d)\n"
               "/errors - ошибки за последний час\n"
               "/deploy - выложить новую версию (с подтверждением)\n"
               "/rollback - откатиться на предыдущую версию (с подтверждением)\n"
               "/restart - перезапустить контейнер (с подтверждением)" % (DEFAULT_LOG_LINES, MAX_LOG_LINES))


def cmd_status(chat):
    parts = []
    code, out = run(["docker", "ps", "-a", "--filter", "name=^/%s$" % CONTAINER,
                     "--format", "{{.Status}} · образ {{.Image}}"])
    parts.append("🐳 Контейнер: " + (out.strip() or "не найден"))
    code, out = run(["docker", "inspect", "-f", "{{.State.Running}} {{.State.StartedAt}}", CONTAINER])
    if code == 0:
        parts.append("▶️ Запущен: " + out.strip())
    parts.append("📦 Сейчас работает: " + (read_text("deployed_commit") or "неизвестно"))
    pending = read_text("pending.txt")
    parts.append("⏳ Ждут выкладки: %d коммит(ов)" % (len(pending.splitlines()) if pending else 0))
    try:
        age = int(time.time()) - int(read_text("heartbeat") or 0)
        parts.append("🤖 Агент выкладки: %s (проверка %d мин назад)" % ("жив" if age < 600 else "НЕ отвечает", age // 60))
    except ValueError:
        parts.append("🤖 Агент выкладки: нет данных")
    code, out = run(["uptime"])
    parts.append("🖥 " + out.strip())
    code, out = run(["df", "-h", "/"])
    parts.append("💾 Диск:\n" + pre(tail_lines(out, 2)))
    code, out = run(["free", "-m"])
    parts.append("🧠 Память:\n" + pre(tail_lines(out, 3)))
    status = read_status()
    if status:
        parts.append("📋 Последняя выкладка: %s %s (%s)" % (status.get("action", "?"), status.get("state", "?"), esc(status.get("message", ""))))
    send(chat, "\n".join(parts[:6]) + "\n" + "\n".join(parts[6:]))


def cmd_logs(chat, arg):
    n = DEFAULT_LOG_LINES
    if arg.strip().isdigit():
        n = max(1, min(MAX_LOG_LINES, int(arg.strip())))
    code, out = run(["docker", "logs", CONTAINER, "--tail", str(n)])
    send(chat, "📜 <b>Лог %s (последние %d строк)</b>\n%s" % (CONTAINER, n, pre(tail_lines(out, n))))


def cmd_errors(chat):
    code, out = run(["docker", "logs", CONTAINER, "--since", "1h"], timeout=90)
    lines = [l for l in out.splitlines() if re.search(r"\bERROR\b|Exception", l)]
    if not lines:
        send(chat, "✅ За последний час ошибок в логе нет.")
        return
    send(chat, "🔴 <b>Ошибки за час: %d</b> (последние 25)\n%s" % (len(lines), pre("\n".join(l[:300] for l in lines[-25:]))))


def make_confirm(chat, action, title, details):
    nonce = os.urandom(6).hex()
    with _pending_lock:
        now = time.time()
        for key in [k for k, (_, exp) in _pending.items() if exp < now]:
            _pending.pop(key, None)
        _pending[nonce] = (action, now + CONFIRM_TTL)
    markup = {"inline_keyboard": [[
        {"text": "✅ Подтвердить", "callback_data": "ok:%s" % nonce},
        {"text": "❌ Отмена", "callback_data": "no:%s" % nonce}]]}
    send(chat, "⚠️ <b>%s</b>\n\n%s\n\nПодтвердите в течение %d секунд." % (title, details, CONFIRM_TTL), markup)


def cmd_deploy(chat):
    status = read_status()
    if status.get("state") == "running":
        send(chat, "⏳ Выкладка уже идёт: %s" % esc(status.get("message", "")))
        return
    pending = read_text("pending.txt")
    details = ("Будут выложены коммиты:\n" + pre(pending)) if pending else "Новых коммитов, судя по проверке, нет, но выкладку можно запустить."
    make_confirm(chat, "deploy", "Выложить новую версию?", details + "\nСтарая версия работает, пока собирается новая; если новая не запустится, вернётся прежняя.")


def cmd_rollback(chat):
    status = read_status()
    if status.get("state") == "running":
        send(chat, "⏳ Выкладка уже идёт, откат сейчас невозможен.")
        return
    make_confirm(chat, "rollback", "Откатиться на предыдущую версию?", "Текущая: " + esc(read_text("deployed_commit") or "?"))


def cmd_restart(chat):
    make_confirm(chat, "restart", "Перезапустить контейнер %s?" % CONTAINER,
                 "Бот будет недоступен 1-2 минуты. Новый код при перезапуске НЕ подхватывается - для этого /deploy.")


# ── исполнение подтверждённых действий ──────────────────────────────────────

def write_request(action):
    os.makedirs(DEPLOY_DIR, exist_ok=True)
    with open(DEPLOY_DIR + "/request", "w", encoding="utf-8") as f:
        f.write("%s %d\n" % (action, OWNER))


def watch_deploy(chat, action, before_started):
    deadline = time.time() + WATCH_TIMEOUT
    while time.time() < deadline:
        time.sleep(10)
        status = read_status()
        if status.get("started_at") != before_started and status.get("state") in ("ok", "failed", "rolled_back"):
            icon = {"ok": "✅", "failed": "❌", "rolled_back": "↩️"}[status["state"]]
            text = "%s <b>%s: %s</b>\n%s\nКоммит: %s %s" % (
                icon, "Деплой" if action == "deploy" else "Откат", status["state"], esc(status.get("message", "")),
                esc(status.get("commit", "")), esc(status.get("subject", "")))
            if status["state"] != "ok":
                text += "\n\nКонец лога:\n" + pre(tail_lines(read_text("last.log"), 15))
            send(chat, text)
            return
    send(chat, "⚠️ Не дождался окончания %s за %d минут - проверьте /status и /logs." % (action, WATCH_TIMEOUT // 60))


def do_request_action(chat, action):
    before = read_status().get("started_at")
    write_request(action)
    send(chat, "🚀 Заявка передана агенту выкладки (%s). Сообщу, когда закончится." % action)
    threading.Thread(target=watch_deploy, args=(chat, action, before), daemon=True).start()


def do_restart(chat):
    lock = open(LOCK_FILE, "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        send(chat, "⏳ Сейчас идёт выкладка - перезапуск отложен.")
        lock.close()
        return
    try:
        started = int(time.time())
        send(chat, "🔄 Перезапускаю контейнер…")
        code, out = run(["docker", "restart", CONTAINER], timeout=180)
        if code != 0:
            send(chat, "❌ Не удалось перезапустить:\n" + pre(out))
            return
        for _ in range(60):
            time.sleep(3)
            _, logs = run(["docker", "logs", CONTAINER, "--since", str(started)], timeout=30)
            if "Started GamePlatformBotApplication" in logs:
                send(chat, "✅ Контейнер перезапущен, бот поднялся.")
                return
            _, running = run(["docker", "inspect", "-f", "{{.State.Running}}", CONTAINER])
            if running.strip() != "true":
                send(chat, "❌ Контейнер остановился после перезапуска:\n" + pre(tail_lines(logs, 20)))
                return
        send(chat, "⚠️ Не дождался сообщения о запуске за 3 минуты - проверьте /logs.")
    finally:
        fcntl.flock(lock, fcntl.LOCK_UN)
        lock.close()


def handle_callback(cb):
    user = cb.get("from", {}).get("id")
    chat = cb.get("message", {}).get("chat", {}).get("id")
    if user != OWNER:
        log.warning("callback from stranger %s ignored", user)
        return
    try:
        tg("answerCallbackQuery", callback_query_id=cb["id"])
        tg("editMessageReplyMarkup", chat_id=chat, message_id=cb["message"]["message_id"], reply_markup={"inline_keyboard": []})
    except Exception as exc:
        log.warning("callback ack failed: %s", exc)
    kind, _, nonce = (cb.get("data") or "").partition(":")
    with _pending_lock:
        entry = _pending.pop(nonce, None)
    if kind == "no":
        send(chat, "Отменено.")
        return
    if kind != "ok" or entry is None or entry[1] < time.time():
        send(chat, "⌛ Подтверждение устарело. Запустите команду заново.")
        return
    action = entry[0]
    log.info("owner confirmed action: %s", action)
    if action in ("deploy", "rollback"):
        do_request_action(chat, action)
    elif action == "restart":
        threading.Thread(target=do_restart, args=(chat,), daemon=True).start()


def handle_message(msg):
    user = msg.get("from", {}).get("id")
    chat = msg.get("chat", {}).get("id")
    if user != OWNER or msg.get("chat", {}).get("type") != "private":
        log.warning("message from stranger %s ignored", user)
        return
    text = (msg.get("text") or "").strip()
    command, _, arg = text.partition(" ")
    command = command.split("@")[0].lower()
    log.info("command: %s", command)
    if command in ("/start", "/help"):
        cmd_help(chat)
    elif command == "/status":
        cmd_status(chat)
    elif command == "/logs":
        cmd_logs(chat, arg)
    elif command == "/errors":
        cmd_errors(chat)
    elif command == "/deploy":
        cmd_deploy(chat)
    elif command == "/rollback":
        cmd_rollback(chat)
    elif command == "/restart":
        cmd_restart(chat)
    else:
        send(chat, "Не знаю такой команды. /help")


def main():
    if not TOKEN or not OWNER:
        sys.exit("Нужны OPS_BOT_TOKEN и OPS_OWNER_ID (см. /etc/gamebot-ops.env)")
    try:
        tg("setMyCommands", commands=[
            {"command": "status", "description": "Состояние сервера и бота"},
            {"command": "logs", "description": "Последние строки лога"},
            {"command": "errors", "description": "Ошибки за час"},
            {"command": "deploy", "description": "Выложить новую версию"},
            {"command": "rollback", "description": "Откатить на предыдущую"},
            {"command": "restart", "description": "Перезапустить контейнер"}])
    except Exception as exc:
        log.warning("setMyCommands failed: %s", exc)
    log.info("ops-bot started for owner %s", OWNER)
    offset = 0
    while True:
        try:
            updates = tg("getUpdates", offset=offset, timeout=30, allowed_updates=["message", "callback_query"])
            for update in updates.get("result", []):
                offset = update["update_id"] + 1
                try:
                    if "callback_query" in update:
                        handle_callback(update["callback_query"])
                    elif "message" in update:
                        handle_message(update["message"])
                except Exception:
                    log.exception("update handling failed")
        except urllib.error.HTTPError as exc:
            log.warning("getUpdates HTTP %s", exc.code)
            time.sleep(5)
        except Exception as exc:
            log.warning("getUpdates failed: %s", exc)
            time.sleep(5)


if __name__ == "__main__":
    main()
