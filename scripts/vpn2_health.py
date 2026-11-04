#!/usr/bin/env python3
"""Проверка цепочки EGC VPN на сервере входа (Москва). Раз в 5 минут (cron): панель, порты, сертификат и реальный проход трафика до выхода (Алматы)
через тот же мост, что использует вход. Результат уходит в бот (POST heartbeat); раз в сутки с аргументом backup уходит копия базы панели.
Настройки: /root/vpn-health.env (HEALTH_URL, HEALTH_KEY). Аргумент --dry: только печать результата, без отправки."""
import datetime
import glob
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.request

ENV = {}
for line in open('/root/vpn-health.env'):
    line = line.strip()
    if line and not line.startswith('#') and '=' in line:
        k, v = line.split('=', 1)
        ENV[k] = v
URL = ENV['HEALTH_URL'].rstrip('/')
KEY = ENV['HEALTH_KEY']
DRY = '--dry' in sys.argv


def post(path, data, ctype):
    req = urllib.request.Request(URL + path, data=data, method='POST', headers={'Content-Type': ctype, 'X-Health-Key': KEY})
    with urllib.request.urlopen(req, timeout=25) as resp:
        return resp.status


def run(cmd, timeout=15):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def cert_days_left():
    out = run(['openssl', 'x509', '-enddate', '-noout', '-in', '/root/cert/ip/fullchain.pem']).stdout.strip()
    # notAfter=Nov 10 12:00:00 2026 GMT
    try:
        end = datetime.datetime.strptime(out.split('=', 1)[1], '%b %d %H:%M:%S %Y %Z')
        return (end - datetime.datetime.utcnow()).days
    except Exception:
        return None


def chain_check(details):
    """Реальный трафик: клиент Xray на этом сервере -> мост на выход (Алматы) -> внешний сайт. Ожидаем адрес выхода."""
    bridge = json.load(open('/root/bridge.json'))
    expected = bridge['address']
    xray = glob.glob('/usr/local/x-ui/bin/xray-linux-*')[0]
    cfg = {"log": {"loglevel": "none"},
           "inbounds": [{"listen": "127.0.0.1", "port": 10815, "protocol": "socks", "settings": {"udp": False}}],
           "outbounds": [{"protocol": "vless",
                          "settings": {"vnext": [{"address": expected, "port": bridge['port'], "users": [{"id": bridge['uuid'], "encryption": "none", "flow": ""}]}]},
                          "streamSettings": {"network": "tcp", "security": "reality",
                                             "realitySettings": {"serverName": bridge['sni'], "fingerprint": "chrome", "publicKey": bridge['pbk'],
                                                                 "shortId": bridge['sid'], "spiderX": "/"}}}]}
    path = tempfile.mktemp(suffix='.json')
    open(path, 'w').write(json.dumps(cfg))
    proc = subprocess.Popen([xray, '-c', path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    latency = None
    ok = False
    seen = ''
    try:
        time.sleep(2)
        for url in ('https://ifconfig.me/ip', 'https://api.ipify.org', 'https://ipinfo.io/ip'):
            started = time.time()
            r = run(['curl', '-s', '--max-time', '12', '--socks5-hostname', '127.0.0.1:10815', url], timeout=20)
            seen = r.stdout.strip()[:40]
            if seen == expected:
                ok = True
                latency = int((time.time() - started) * 1000)
                break
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        os.remove(path)
    if not ok:
        details.append('через мост на выход (%s) трафик не проходит, ответ: %s' % (expected, seen or 'нет ответа'))
    return latency


def heartbeat():
    details = []
    active = run(['systemctl', 'is-active', 'x-ui']).stdout.strip()
    if active != 'active':
        details.append('x-ui не запущен (%s)' % active)
    listening = run(['ss', '-ltnu']).stdout
    for port in ('443', '2096'):
        if (':' + port + ' ') not in listening:
            details.append('порт %s не слушается' % port)
    days = cert_days_left()
    latency = None
    try:
        latency = chain_check(details)
    except Exception as e:  # сбой самой проверки тоже сигнал
        details.append('проверка цепочки не выполнилась: %s' % type(e).__name__)
    body = {'ok': not details, 'details': details, 'certDaysLeft': days, 'latencyMs': latency,
            'ts': datetime.datetime.utcnow().isoformat() + 'Z'}
    if DRY:
        print(json.dumps(body, ensure_ascii=False))
        return
    try:
        status = post('/heartbeat', json.dumps(body).encode(), 'application/json')
        print(datetime.datetime.utcnow().isoformat(), 'heartbeat', status, body['ok'], body['details'])
    except Exception as e:
        print(datetime.datetime.utcnow().isoformat(), 'heartbeat failed', type(e).__name__, e)


def backup():
    src = '/etc/x-ui/x-ui.db'
    tmp = tempfile.mktemp(suffix='.db')
    try:
        conn = sqlite3.connect(src)
        dst = sqlite3.connect(tmp)
        conn.backup(dst)
        dst.close()
        conn.close()
        data = open(tmp, 'rb').read()
        if DRY:
            print('backup bytes', len(data))
            return
        status = post('/panel-backup', data, 'application/octet-stream')
        print(datetime.datetime.utcnow().isoformat(), 'backup', status, len(data))
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


if __name__ == '__main__':
    if 'backup' in sys.argv:
        backup()
    else:
        heartbeat()
