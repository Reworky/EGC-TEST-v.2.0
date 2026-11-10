#!/usr/bin/env python3
"""Проба EGC VPN из России: сервер Москва раз в 5 минут подключается к связке Влада так же, как это делает игрок (Hysteria2 на узел входа, технический клиент egc_probe
на его панели, inbound 9) и проверяет: проходит ли трафик, какой адрес выхода, какая скорость (раз в час, ~1 МБ) и сколько дней осталось у сертификата узла.
Результат уходит в бот (POST heartbeat), раз в сутки с аргументом backup уходит копия базы локальной панели Москвы (резервной цепочки).
Настройки: /root/vpn-health.env (HEALTH_URL, HEALTH_KEY), /root/vpn-probe.env (PROBE_AUTH; необязательно EXPECT_EXIT_IP, HOST, HY_PORT, SUB_PORT, OBFS_PASSWORD).
Аргументы: --dry - только печать результата; backup - копия базы панели Москвы."""
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
for path in ('/root/vpn-health.env', '/root/vpn-probe.env'):
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                ENV[k] = v
URL = ENV['HEALTH_URL'].rstrip('/')
KEY = ENV['HEALTH_KEY']
HOST = ENV.get('HOST', '2.59.40.207')
HY_PORT = int(ENV.get('HY_PORT', '49250'))
SUB_PORT = int(ENV.get('SUB_PORT', '2096'))
EXPECT_IP = ENV.get('EXPECT_EXIT_IP', '')
DRY = '--dry' in sys.argv
SOCKS_PORT = 10815


def post(path, data, ctype):
    req = urllib.request.Request(URL + path, data=data, method='POST', headers={'Content-Type': ctype, 'X-Health-Key': KEY})
    with urllib.request.urlopen(req, timeout=25) as resp:
        return resp.status


def run(cmd, timeout=20, stdin=None):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, input=stdin)


def cert_hours_left():
    """Срок сертификата узла входа (по подписке на порту 2096) в часах: сертификат Let's Encrypt на IP живёт около 6,5 суток и обновляется заранее."""
    try:
        s = run(['openssl', 's_client', '-connect', '%s:%d' % (HOST, SUB_PORT), '-servername', HOST], timeout=15, stdin='')
        out = run(['openssl', 'x509', '-noout', '-enddate'], timeout=10, stdin=s.stdout).stdout.strip()
        end = datetime.datetime.strptime(out.split('=', 1)[1], '%b %d %H:%M:%S %Y %Z')
        return int((end - datetime.datetime.now(datetime.UTC).replace(tzinfo=None)).total_seconds() // 3600)
    except Exception:
        return None


def tunnel_probe(details):
    """Реальный трафик: клиент Xray (Hysteria2) на этом сервере -> узел входа Влада -> внешний сайт. Возвращает (задержка мс, адрес выхода, скорость Мбит/с или None)."""
    xray = glob.glob('/usr/local/x-ui/bin/xray-linux-*')[0]
    cfg = {"log": {"loglevel": "none"},
           "inbounds": [{"listen": "127.0.0.1", "port": SOCKS_PORT, "protocol": "socks", "settings": {"udp": False}}],
           "outbounds": [{"protocol": "hysteria", "settings": {"version": 2, "address": HOST, "port": HY_PORT},
                          "streamSettings": {"network": "hysteria", "security": "tls",
                                             "tlsSettings": {"serverName": HOST, "alpn": ["h3"]},
                                             "hysteriaSettings": {"version": 2, "auth": ENV['PROBE_AUTH']}}}]}
    if ENV.get('OBFS_PASSWORD'):  # узел с обфускацией salamander (новая панель Влада 185.32.84.230): без неё рукопожатие не проходит
        cfg['outbounds'][0]['streamSettings']['finalmask'] = {"udp": [{"type": "salamander", "settings": {"password": ENV['OBFS_PASSWORD']}}]}
    path = tempfile.mktemp(suffix='.json')
    open(path, 'w').write(json.dumps(cfg))
    proc = subprocess.Popen([xray, '-c', path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    latency, exit_ip, speed = None, '', None
    try:
        time.sleep(2)
        for url in ('https://ifconfig.me/ip', 'https://api.ipify.org', 'https://ipinfo.io/ip'):
            started = time.time()
            r = run(['curl', '-s', '--max-time', '12', '--socks5-hostname', '127.0.0.1:%d' % SOCKS_PORT, url], timeout=20)
            ip = r.stdout.strip()[:40]
            if ip:
                exit_ip = ip
                latency = int((time.time() - started) * 1000)
                break
        # скорость раз в час (первая проба часа): ~1 МБ, чтобы не нагружать чужой узел
        if exit_ip and datetime.datetime.now().minute < 5:
            r = run(['curl', '-s', '-o', '/dev/null', '--max-time', '20', '-w', '%{speed_download}', '--socks5-hostname', '127.0.0.1:%d' % SOCKS_PORT,
                     'https://proof.ovh.net/files/1Mb.dat'], timeout=30)
            try:
                speed = round(float(r.stdout.strip()) * 8 / 1_000_000, 1)
            except ValueError:
                speed = None
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        os.remove(path)
    if not exit_ip:
        details.append('через связку Влада (%s:%d) трафик не проходит, ответа нет' % (HOST, HY_PORT))
    elif EXPECT_IP and exit_ip != EXPECT_IP:
        details.append('адрес выхода изменился: ожидали %s, получили %s' % (EXPECT_IP, exit_ip))
    return latency, exit_ip, speed


def heartbeat():
    details = []
    hours = cert_hours_left()
    latency, exit_ip, speed = None, '', None
    try:
        latency, exit_ip, speed = tunnel_probe(details)
    except Exception as e:  # сбой самой проверки тоже сигнал
        details.append('проверка не выполнилась: %s' % type(e).__name__)
    body = {'ok': not details, 'details': details, 'certHoursLeft': hours, 'latencyMs': latency, 'exitIp': exit_ip, 'speedMbps': speed,
            'ts': datetime.datetime.now(datetime.UTC).isoformat()}
    if DRY:
        print(json.dumps(body, ensure_ascii=False))
        return
    try:
        status = post('/heartbeat', json.dumps(body).encode(), 'application/json')
        print(body['ts'], 'heartbeat', status, body['ok'], body['details'], 'ip', exit_ip, 'мс', latency, 'Мбит/с', speed)
    except Exception as e:
        print(body['ts'], 'heartbeat failed', type(e).__name__, e)


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
        print(datetime.datetime.now(datetime.UTC).isoformat(), 'backup', status, len(data))
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


if __name__ == '__main__':
    if 'backup' in sys.argv:
        backup()
    else:
        heartbeat()
