#!/usr/bin/env python3
"""Проба API 3x-ui (панель Влада): логин, чтение inbound, создание тестового клиента.

Секреты берутся из переменных окружения, в файл не пишутся:
  XUI_URL   https://2.59.40.207:26856/<WebBasePath>
  XUI_USER  логин панели
  XUI_PASS  пароль панели
  XUI_SUB   (необязательно) база подписки, по умолчанию https://<хост>:2096/sub

Команды:
  python3 scripts/vpn_3xui_probe.py info              # inbound 9: протокол, порт, число клиентов
  python3 scripts/vpn_3xui_probe.py add egc_test 1 5  # тест-клиент: email, дней, ГБ -> ссылка подписки
  python3 scripts/vpn_3xui_probe.py ips egc_test      # IP-адреса клиента (метаданные)
  python3 scripts/vpn_3xui_probe.py ext egc_test 30   # продлить клиента на N дней (проверка update)
  python3 scripts/vpn_3xui_probe.py off egc_test      # удалить клиента по метке
"""
import json
import os
import re
import secrets
import ssl
import sys
import time
import urllib.parse
import urllib.request
import uuid
from http.cookiejar import CookieJar

INBOUND_ID = 9

base = os.environ["XUI_URL"].rstrip("/")
host = urllib.parse.urlparse(base).hostname
sub_base = os.environ.get("XUI_SUB", f"https://{host}:2096/sub").rstrip("/")

ctx = ssl.create_default_context()  # у панели сертификат Let's Encrypt на IP, проверка TLS штатная
opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(CookieJar()),
    urllib.request.HTTPSHandler(context=ctx),
)


csrf = ""


def refresh_csrf(page):
    """Новые версии 3x-ui требуют X-CSRF-Token; токен лежит в meta страницы и меняется после логина."""
    global csrf
    with opener.open(urllib.request.Request(base + page), timeout=20) as r:
        m = re.search(r'name="csrf-token" content="([^"]+)"', r.read().decode("utf-8", "replace"))
    csrf = m.group(1) if m else ""


def call(path, data=None):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(base + path, data=body, headers={"X-CSRF-Token": csrf, "X-Requested-With": "XMLHttpRequest"})
    with opener.open(req, timeout=20) as r:
        return json.loads(r.read().decode())


def call_json(path, data):
    req = urllib.request.Request(
        base + path, data=json.dumps(data).encode(),
        headers={"X-CSRF-Token": csrf, "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/json"},
    )
    try:
        with opener.open(req, timeout=20) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"success": False, "msg": f"HTTP {e.code}"}


def login():
    refresh_csrf("/")
    res = call("/login", {"username": os.environ["XUI_USER"], "password": os.environ["XUI_PASS"]})
    if not res.get("success"):
        sys.exit(f"логин не прошёл: {res.get('msg')}")
    refresh_csrf("/panel/")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "info"
    login()
    if cmd == "info":
        ib = call(f"/panel/api/inbounds/get/{INBOUND_ID}")["obj"]
        settings = ib["settings"]
        clients = (json.loads(settings) if isinstance(settings, str) else settings).get("clients", [])
        print(f"protocol={ib['protocol']} port={ib['port']} enable={ib['enable']} клиентов={len(clients)}")
    elif cmd == "add":
        email = sys.argv[2]
        days = int(sys.argv[3]) if len(sys.argv) > 3 else 1
        gb = int(sys.argv[4]) if len(sys.argv) > 4 else 5
        sub_id = secrets.token_hex(8)
        client = {
            "email": email, "subId": sub_id, "uuid": str(uuid.uuid4()), "password": secrets.token_hex(8),
            "auth": secrets.token_urlsafe(16), "flow": "", "security": "", "limitIp": 1,
            "totalGB": gb * 1024 ** 3, "expiryTime": int((time.time() + days * 86400) * 1000),
            "enable": True, "tgId": 0, "comment": "EGC", "reset": 0,
        }
        # формат тела в новой версии не документирован: пробуем два варианта (при ошибке ничего не создаётся)
        for body in ({"client": client, "inboundIds": [INBOUND_ID]}, {**client, "inboundIds": [INBOUND_ID]}):
            res = call_json("/panel/api/clients/add", body)
            print(json.dumps(res, ensure_ascii=False)[:300])
            if res.get("success"):
                print("подписка:", f"{sub_base}/{sub_id}")
                break
    elif cmd == "routes":
        # новая версия панели: ищем пути API клиентов/inbound в JS-бандлах (после логина)
        def get(path):
            with opener.open(urllib.request.Request(base + path), timeout=20) as r:
                return r.read().decode("utf-8", "replace")
        seen, queue, found = set(), re.findall(r'/assets/[\w.-]+\.js', get("/panel/") + get("/panel/inbounds")), set()
        while queue and len(seen) < 60:
            a = queue.pop()
            if a in seen:
                continue
            seen.add(a)
            js = get(a.replace("/assets", "/assets", 1)) if a.startswith("/assets") else ""
            found |= set(re.findall(r'["`](/?(?:panel/)?api/[\w/${}.-]*(?:client|inbound)[\w/${}.-]*)["`]', js, re.I))
            found |= {"CTX: " + m for m in re.findall(r'.{60}api/clients/(?:add|create|update|del|bulk)[^;]{0,80}', js)}
            queue += ["/assets/" + n for n in re.findall(r'\./([\w.-]+\.js)', js) if "/assets/" + n not in seen]
        print("\n".join(sorted(found)))
    elif cmd == "probe":
        # какие адреса отвечают (404 = нет такого, 400/200/405 = есть)
        for path in ["/panel/api/clients/add", "/panel/api/clients/create", "/panel/api/clients/list", "/panel/api/clients/update/x", "/panel/api/clients/del/x", "/panel/api/inbounds/addClient", "/panel/inbound/addClient",
                     "/panel/api/client/add", "/panel/api/clients/create", "/panel/api/clients/addClient",
                     f"/panel/api/inbounds/{INBOUND_ID}/addClient", "/panel/api/clients/list", "/panel/api/clients",
                     "/panel/api/inbounds/list", f"/panel/api/inbounds/get/{INBOUND_ID}", "/panel/api/server/status"]:
            for method in ("GET", "POST"):
                req = urllib.request.Request(base + path, data=b"" if method == "POST" else None,
                                             headers={"X-CSRF-Token": csrf, "X-Requested-With": "XMLHttpRequest"})
                try:
                    with opener.open(req, timeout=20) as r:
                        code, body = r.status, r.read(200).decode("utf-8", "replace")
                except urllib.error.HTTPError as e:
                    code, body = e.code, ""
                print(method, path, code, body.replace("\n", " ")[:120])
    elif cmd == "schema":
        # только названия полей и типы, без значений (в списке чужие клиенты)
        lst = call("/panel/api/clients/list")["obj"]
        print("clients/list: всего", len(lst))
        print({k: type(v).__name__ for k, v in lst[0].items()})
        ib = call(f"/panel/api/inbounds/get/{INBOUND_ID}")["obj"]
        print("inbound:", {k: type(v).__name__ for k, v in ib.items()})
        st = ib["settings"]
        st = json.loads(st) if isinstance(st, str) else st
        print("inbound.settings:", {k: type(v).__name__ for k, v in st.items()})
        if st.get("clients"):
            print("inbound.settings.clients[0]:", {k: type(v).__name__ for k, v in st["clients"][0].items()})
        print("inbound.clientStats?", "clientStats" in ib)
    elif cmd == "ips":
        print(call(f"/panel/api/inbounds/clientIps/{sys.argv[2]}"))
    elif cmd in ("ext", "off"):
        # ext - продлить на N дней (update), off - удалить клиента по метке. Ответ панели ненадёжен (бывает «record not found» при
        # реально выполненном действии), поэтому после каждой попытки перечитываем список и сверяем факт.
        email = sys.argv[2]

        def fetch():
            return next((c for c in call("/panel/api/clients/list")["obj"] if c.get("email") == email), None)

        item = fetch()
        if not item:
            sys.exit(f"клиент {email} не найден")
        keys = [("email", urllib.parse.quote(email)), ("id", str(item["id"])), ("uuid", item.get("uuid", ""))]
        if cmd == "off":
            for name, key in keys:
                res = call_json(f"/panel/api/clients/del/{key}", {})
                gone = fetch() is None
                print("del по", name, json.dumps(res, ensure_ascii=False)[:120], "| реально удалён:", gone)
                if gone:
                    break
            return
        days = int(sys.argv[3]) if len(sys.argv) > 3 else 30
        want = int((time.time() + days * 86400) * 1000)
        merged = {k: v for k, v in item.items() if k not in ("id", "traffic", "createdAt", "updatedAt", "inboundIds")}
        merged["expiryTime"] = want
        merged["enable"] = True
        bodies = [("wrapped без id", {"client": merged, "inboundIds": [INBOUND_ID]}),
                  ("flat без id", {**merged, "inboundIds": [INBOUND_ID]}),
                  ("wrapped id=uuid", {"client": {**merged, "id": item.get("uuid", "")}, "inboundIds": [INBOUND_ID]})]
        done = False
        for kname, key in keys:
            for bname, body in bodies:
                res = call_json(f"/panel/api/clients/update/{key}", body)
                now = fetch()
                ok = bool(now) and abs(now.get("expiryTime", 0) - want) < 60000
                print("update по", kname, "/", bname, json.dumps(res, ensure_ascii=False)[:120], "| реально продлён:", ok)
                if ok:
                    done = True
                    break
            if done:
                break
    elif cmd == "routes":
        # новая версия панели: ищем пути API клиентов/inbound в JS-бандлах (после логина)
        def get(path):
            with opener.open(urllib.request.Request(base + path), timeout=20) as r:
                return r.read().decode("utf-8", "replace")
        seen, queue, found = set(), re.findall(r'/assets/[\w.-]+\.js', get("/panel/") + get("/panel/inbounds")), set()
        while queue and len(seen) < 60:
            a = queue.pop()
            if a in seen:
                continue
            seen.add(a)
            js = get(a.replace("/assets", "/assets", 1)) if a.startswith("/assets") else ""
            found |= set(re.findall(r'["`](/?(?:panel/)?api/[\w/${}.-]*(?:client|inbound)[\w/${}.-]*)["`]', js, re.I))
            found |= {"CTX: " + m for m in re.findall(r'.{60}api/clients/(?:add|create|update|del|bulk)[^;]{0,80}', js)}
            queue += ["/assets/" + n for n in re.findall(r'\./([\w.-]+\.js)', js) if "/assets/" + n not in seen]
        print("\n".join(sorted(found)))
    elif cmd == "probe":
        # какие адреса отвечают (404 = нет такого, 400/200/405 = есть)
        for path in ["/panel/api/clients/add", "/panel/api/clients/create", "/panel/api/clients/list", "/panel/api/clients/update/x", "/panel/api/clients/del/x", "/panel/api/inbounds/addClient", "/panel/inbound/addClient",
                     "/panel/api/client/add", "/panel/api/clients/create", "/panel/api/clients/addClient",
                     f"/panel/api/inbounds/{INBOUND_ID}/addClient", "/panel/api/clients/list", "/panel/api/clients",
                     "/panel/api/inbounds/list", f"/panel/api/inbounds/get/{INBOUND_ID}", "/panel/api/server/status"]:
            for method in ("GET", "POST"):
                req = urllib.request.Request(base + path, data=b"" if method == "POST" else None,
                                             headers={"X-CSRF-Token": csrf, "X-Requested-With": "XMLHttpRequest"})
                try:
                    with opener.open(req, timeout=20) as r:
                        code, body = r.status, r.read(200).decode("utf-8", "replace")
                except urllib.error.HTTPError as e:
                    code, body = e.code, ""
                print(method, path, code, body.replace("\n", " ")[:120])
    elif cmd == "schema":
        # только названия полей и типы, без значений (в списке чужие клиенты)
        lst = call("/panel/api/clients/list")["obj"]
        print("clients/list: всего", len(lst))
        print({k: type(v).__name__ for k, v in lst[0].items()})
        ib = call(f"/panel/api/inbounds/get/{INBOUND_ID}")["obj"]
        print("inbound:", {k: type(v).__name__ for k, v in ib.items()})
        st = ib["settings"]
        st = json.loads(st) if isinstance(st, str) else st
        print("inbound.settings:", {k: type(v).__name__ for k, v in st.items()})
        if st.get("clients"):
            print("inbound.settings.clients[0]:", {k: type(v).__name__ for k, v in st["clients"][0].items()})
        print("inbound.clientStats?", "clientStats" in ib)
    elif cmd == "ips":
        print(call(f"/panel/api/inbounds/clientIps/{sys.argv[2]}"))
    elif cmd in ("ext", "off"):
        # то же, что делает бот: ext - продлить на N дней (update клиента), off - удалить клиента по метке.
        # Ключ в адресе (email / числовой id / uuid) и вид тела подбираем перебором: печатаем, какой вариант сработал.
        email = sys.argv[2]
        item = next((c for c in call("/panel/api/clients/list")["obj"] if c.get("email") == email), None)
        if not item:
            sys.exit(f"клиент {email} не найден")
        keys = [("email", urllib.parse.quote(email)), ("id", str(item["id"])), ("uuid", item.get("uuid", ""))]
        if cmd == "off":
            for name, key in keys:
                res = call_json(f"/panel/api/clients/del/{key}", {})
                print("del по", name, json.dumps(res, ensure_ascii=False)[:160])
                if res.get("success"):
                    break
            return
        days = int(sys.argv[3]) if len(sys.argv) > 3 else 30
        merged = {k: v for k, v in item.items() if k not in ("id", "traffic", "createdAt", "updatedAt", "inboundIds")}
        merged["expiryTime"] = int((time.time() + days * 86400) * 1000)
        merged["enable"] = True
        bodies = [("wrapped без id", {"client": merged, "inboundIds": [INBOUND_ID]}),
                  ("flat без id", {**merged, "inboundIds": [INBOUND_ID]}),
                  ("wrapped id=uuid", {"client": {**merged, "id": item.get("uuid", "")}, "inboundIds": [INBOUND_ID]})]
        done = False
        for kname, key in keys:
            for bname, body in bodies:
                res = call_json(f"/panel/api/clients/update/{key}", body)
                print("update по", kname, "/", bname, json.dumps(res, ensure_ascii=False)[:160])
                if res.get("success"):
                    done = True
                    break
            if done:
                break
    elif cmd == "routes":
        # новая версия панели: ищем пути API клиентов/inbound в JS-бандлах (после логина)
        def get(path):
            with opener.open(urllib.request.Request(base + path), timeout=20) as r:
                return r.read().decode("utf-8", "replace")
        seen, queue, found = set(), re.findall(r'/assets/[\w.-]+\.js', get("/panel/") + get("/panel/inbounds")), set()
        while queue and len(seen) < 60:
            a = queue.pop()
            if a in seen:
                continue
            seen.add(a)
            js = get(a.replace("/assets", "/assets", 1)) if a.startswith("/assets") else ""
            found |= set(re.findall(r'["`](/?(?:panel/)?api/[\w/${}.-]*(?:client|inbound)[\w/${}.-]*)["`]', js, re.I))
            found |= {"CTX: " + m for m in re.findall(r'.{60}api/clients/(?:add|create|update|del|bulk)[^;]{0,80}', js)}
            queue += ["/assets/" + n for n in re.findall(r'\./([\w.-]+\.js)', js) if "/assets/" + n not in seen]
        print("\n".join(sorted(found)))
    elif cmd == "probe":
        # какие адреса отвечают (404 = нет такого, 400/200/405 = есть)
        for path in ["/panel/api/clients/add", "/panel/api/clients/create", "/panel/api/clients/list", "/panel/api/clients/update/x", "/panel/api/clients/del/x", "/panel/api/inbounds/addClient", "/panel/inbound/addClient",
                     "/panel/api/client/add", "/panel/api/clients/create", "/panel/api/clients/addClient",
                     f"/panel/api/inbounds/{INBOUND_ID}/addClient", "/panel/api/clients/list", "/panel/api/clients",
                     "/panel/api/inbounds/list", f"/panel/api/inbounds/get/{INBOUND_ID}", "/panel/api/server/status"]:
            for method in ("GET", "POST"):
                req = urllib.request.Request(base + path, data=b"" if method == "POST" else None,
                                             headers={"X-CSRF-Token": csrf, "X-Requested-With": "XMLHttpRequest"})
                try:
                    with opener.open(req, timeout=20) as r:
                        code, body = r.status, r.read(200).decode("utf-8", "replace")
                except urllib.error.HTTPError as e:
                    code, body = e.code, ""
                print(method, path, code, body.replace("\n", " ")[:120])
    elif cmd == "schema":
        # только названия полей и типы, без значений (в списке чужие клиенты)
        lst = call("/panel/api/clients/list")["obj"]
        print("clients/list: всего", len(lst))
        print({k: type(v).__name__ for k, v in lst[0].items()})
        ib = call(f"/panel/api/inbounds/get/{INBOUND_ID}")["obj"]
        print("inbound:", {k: type(v).__name__ for k, v in ib.items()})
        st = ib["settings"]
        st = json.loads(st) if isinstance(st, str) else st
        print("inbound.settings:", {k: type(v).__name__ for k, v in st.items()})
        if st.get("clients"):
            print("inbound.settings.clients[0]:", {k: type(v).__name__ for k, v in st["clients"][0].items()})
        print("inbound.clientStats?", "clientStats" in ib)
    elif cmd == "ips":
        print(call(f"/panel/api/inbounds/clientIps/{sys.argv[2]}"))
    elif cmd in ("ext", "off"):
        # то же, что делает бот: ext - продлить на N дней (update клиента), off - удалить клиента по метке
        email = sys.argv[2]
        item = next((c for c in call("/panel/api/clients/list")["obj"] if c.get("email") == email), None)
        if not item:
            sys.exit(f"клиент {email} не найден")
        if cmd == "off":
            print(call_json(f"/panel/api/clients/del/{item['id']}", {}))
            return
        days = int(sys.argv[3]) if len(sys.argv) > 3 else 30
        merged = {k: v for k, v in item.items() if k not in ("traffic", "createdAt", "updatedAt", "inboundIds")}
        merged["expiryTime"] = int((time.time() + days * 86400) * 1000)
        merged["enable"] = True
        for name, body in (("wrapped", {"client": merged, "inboundIds": [INBOUND_ID]}),
                           ("flat", {**merged, "inboundIds": [INBOUND_ID]})):
            res = call_json(f"/panel/api/clients/update/{item['id']}", body)
            print(name, json.dumps(res, ensure_ascii=False)[:200])
            if res.get("success"):
                break
    else:
        sys.exit(__doc__)


main()
