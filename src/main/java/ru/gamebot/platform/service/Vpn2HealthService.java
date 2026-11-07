package ru.gamebot.platform.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.event.AnalyticsAlertEvent;

/** Мониторинг цепочки EGC VPN. Сам бот цепочку не проверяет: на сервере входа (Москва) раз в 5 минут работает скрипт, который проверяет панель, сертификат
 *  и реальный проход трафика до выхода, и присылает результат сюда (POST /api/public/vpn2/heartbeat с секретом VPN2_HEALTH_SECRET). Если сигнал пропал
 *  больше чем на 15 минут (упал сервер, скрипт или сеть) или проверка не пройдена, админам уходит алерт: при смене проблемы сразу, при той же - раз в 2 часа,
 *  после починки - сообщение «снова работает». Раз в сутки тот же скрипт присылает копию базы панели: сервер входа единственный, потеря = потеря всех ключей. */
@Slf4j
@Service
public class Vpn2HealthService {

    private static final long STALE_MS = 15 * 60_000L;
    private static final long REALERT_MS = 2 * 60 * 60_000L;
    private static final long MAX_BACKUP_BYTES = 20L * 1024 * 1024;
    private static final int KEEP_BACKUPS = 14;
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final Vpn2Service vpn;
    private final ApplicationEventPublisher eventPublisher;
    private final String secret;
    private final Path backupDir;
    private final boolean expectHeartbeat;

    // после перезапуска бота даём скрипту 15 минут прислать сигнал, а не поднимаем ложную тревогу
    private volatile long lastBeatMs = System.currentTimeMillis();
    private volatile boolean lastOk = true;
    private volatile String lastDetails = "";
    private volatile Integer certDaysLeft = null;
    private volatile Integer certHoursLeft = null;
    private volatile String openProblem = null;
    private volatile long lastAlertMs = 0;
    private volatile int panelFailures = 0;

    public Vpn2HealthService(Vpn2Service vpn, ApplicationEventPublisher eventPublisher,
                             @Value("${app.vpn2-health-secret:}") String secret,
                             @Value("${app.vpn2-backup-dir:/data/vpn2-panel-backups}") String backupDir,
                             @Value("${app.vpn2-health-expect-heartbeat:true}") boolean expectHeartbeat) {
        this.vpn = vpn;
        this.eventPublisher = eventPublisher;
        this.secret = secret == null ? "" : secret.trim();
        this.expectHeartbeat = expectHeartbeat;
        this.backupDir = Path.of(backupDir == null || backupDir.isBlank() ? "/data/vpn2-panel-backups" : backupDir.trim());
        if (this.secret.isBlank()) {
            log.warn("Vpn2HealthService: VPN2_HEALTH_SECRET not set, monitoring endpoints are disabled");
        }
    }

    public boolean isConfigured() {
        return !secret.isBlank();
    }

    /** Сравнение секрета за постоянное время; пустой секрет в настройках - всегда отказ. */
    public boolean keyOk(String key) {
        return isConfigured() && key != null
                && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Сигнал от скрипта проверки: ok, details (список строк или строка), certDaysLeft (число суток до конца сертификата). */
    public void onHeartbeat(Map<String, Object> body) {
        lastBeatMs = System.currentTimeMillis();
        lastOk = !Boolean.FALSE.equals(body.get("ok"));
        Object details = body.get("details");
        lastDetails = details == null ? "" : (details instanceof List<?> list ? String.join("; ", list.stream().map(String::valueOf).toList()) : String.valueOf(details));
        Object days = body.get("certDaysLeft");
        certDaysLeft = days instanceof Number n ? n.intValue() : null;
        Object hours = body.get("certHoursLeft");
        certHoursLeft = hours instanceof Number h ? h.intValue() : null;
    }

    /** Копия базы панели от скрипта: проверяем, что это SQLite и размер разумный, кладём в каталог бэкапов и держим последние 14. Возвращает имя файла. */
    public String saveBackup(byte[] data) throws IOException {
        if (data == null || data.length < SQLITE_MAGIC.length || data.length > MAX_BACKUP_BYTES) {
            throw new IOException("bad backup size");
        }
        for (int i = 0; i < SQLITE_MAGIC.length; i++) {
            if (data[i] != SQLITE_MAGIC[i]) {
                throw new IOException("not a SQLite file");
            }
        }
        Files.createDirectories(backupDir);
        String name = "xui-" + LocalDateTime.now().format(BACKUP_STAMP) + ".db";
        Files.write(backupDir.resolve(name), data);
        pruneBackups();
        return name;
    }

    /** Удаляем только файлы xui-*.db в этом каталоге, оставляя последние KEEP_BACKUPS. */
    private void pruneBackups() {
        try (Stream<Path> files = Files.list(backupDir)) {
            List<Path> mine = new ArrayList<>(files
                    .filter(p -> p.getFileName().toString().startsWith("xui-") && p.getFileName().toString().endsWith(".db"))
                    .sorted()
                    .toList());
            for (int i = 0; i < mine.size() - KEEP_BACKUPS; i++) {
                Files.deleteIfExists(mine.get(i));
            }
        } catch (IOException e) {
            log.warn("VPN2 backup prune failed", e);
        }
    }

    @Scheduled(initialDelay = 5 * 60_000L, fixedDelay = 5 * 60_000L)
    public void monitor() {
        if (!vpn.isEnabled()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            List<String> problems = new ArrayList<>();
            // 1) панель, которой пользуется бот, отвечает с сервера бота (две проверки подряд = 10 минут)
            try {
                vpn.allClientStats();
                panelFailures = 0;
            } catch (Exception e) {
                panelFailures++;
                if (panelFailures >= 2) {
                    problems.add("панель VPN не отвечает с сервера бота (проверок подряд: " + panelFailures + "): выдача и продление ключей не работают");
                }
            }
            // 2) сигналы скрипта проверки цепочки (сервер входа Москва); отключаются VPN2_HEALTH_EXPECT_HEARTBEAT=false, когда бот работает на чужих узлах
            if (expectHeartbeat && isConfigured()) {
                long ageMin = (now - lastBeatMs) / 60_000L;
                if (now - lastBeatMs > STALE_MS) {
                    problems.add("нет сигнала от сервера проверки (Москва) уже " + ageMin + " мин: упал сервер, скрипт проверки или сеть");
                } else if (!lastOk) {
                    problems.add("проверка цепочки не пройдена: " + (lastDetails.isBlank() ? "без подробностей" : lastDetails));
                }
                Integer hours = certHoursLeft;
                Integer days = certDaysLeft;
                if (hours != null) {
                    // сертификат Let's Encrypt на IP живёт ~6,5 суток и обновляется за 2-3 суток до конца: меньше 30 часов значит обновление не сработало
                    if (hours < 30) {
                        problems.add("сертификат узла входа заканчивается через " + hours + " ч: автообновление не сработало, нужно написать Владу");
                    }
                } else if (days != null && days <= 2) {
                    problems.add("сертификат на IP заканчивается через " + days + " дн. (проверьте автообновление acme.sh на сервере входа)");
                }
            }
            if (problems.isEmpty()) {
                if (openProblem != null) {
                    openProblem = null;
                    publish("✅ <b>EGC VPN</b>: цепочка снова работает.");
                }
                return;
            }
            String signature = String.join("|", problems.stream().map(p -> p.replaceAll("\\d+", "#")).toList());
            if (!signature.equals(openProblem) || now - lastAlertMs >= REALERT_MS) {
                openProblem = signature;
                lastAlertMs = now;
                publish("🚨 <b>EGC VPN: проблема с сервисом</b>\n• " + String.join("\n• ", problems.stream().map(Vpn2HealthService::escape).toList()));
            }
        } catch (Exception e) {
            log.warn("VPN2 health monitor failed", e);
        }
    }

    private void publish(String html) {
        try {
            eventPublisher.publishEvent(new AnalyticsAlertEvent(this, html));
        } catch (Exception e) {
            log.warn("VPN2 health: failed to publish alert", e);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
