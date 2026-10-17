package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.event.GoldenHourPushEvent;

/** «Золотой час» (2026-10-17): каждый день в окне (по умолчанию 16:00-17:00 UTC = 19:00-20:00 по Москве) EXC-награда за засчитанный квест
 *  выше на PERCENT% для первых MAX_QUESTS квестов игрока в окне. Строится на глобальном бусте наград (QuestRewardBoostService): раз в сутки
 *  заводится событие на окно. За 30 минут до окна активным за 7 дней игрокам уходит напоминание (через лимит частоты NotificationGateService);
 *  текст напоминания согласован владельцем 2026-10-17, поэтому оно включено по умолчанию (выключить: /goldenhour push off). Все параметры - в app_settings (gh.*). */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoldenHourService {

    static final int DEFAULT_START_HOUR_UTC = 16;
    static final int DEFAULT_PERCENT = 50;
    static final int DEFAULT_MAX_QUESTS = 2;
    static final int PUSH_LEAD_MINUTES = 30;
    private static final int PUSH_ACTIVE_DAYS = 7;
    private static final int MSK_OFFSET_HOURS = 3;

    private final AppSettingRepository settingRepository;
    private final QuestRewardBoostService boostService;
    private final UserService userService;
    private final NotificationGateService notificationGate;
    private final ApplicationEventPublisher eventPublisher;

    // ───────────────────────── настройки ─────────────────────────

    private String get(String key) {
        return settingRepository.findById(key).map(AppSetting::getValue).orElse(null);
    }

    private void put(String key, String value) {
        AppSetting s = settingRepository.findById(key).orElseGet(AppSetting::new);
        s.setKey(key);
        s.setValue(value);
        settingRepository.save(s);
    }

    private int getInt(String key, int def, int min, int max) {
        try {
            String v = get(key);
            return v == null ? def : Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean enabled() { return !"0".equals(get("gh.enabled")); }
    public void setEnabled(boolean v) { put("gh.enabled", v ? "1" : "0"); }
    public boolean pushEnabled() { return !"0".equals(get("gh.push")); }
    public void setPushEnabled(boolean v) { put("gh.push", v ? "1" : "0"); }
    public int startHourUtc() { return getInt("gh.hour", DEFAULT_START_HOUR_UTC, 0, 23); }
    public void setStartHourUtc(int h) { put("gh.hour", String.valueOf(Math.max(0, Math.min(23, h)))); }
    public int percent() { return getInt("gh.percent", DEFAULT_PERCENT, 5, 300); }
    public void setPercent(int p) { put("gh.percent", String.valueOf(Math.max(5, Math.min(300, p)))); }
    public int maxQuests() { return getInt("gh.max", DEFAULT_MAX_QUESTS, 1, 10); }
    public void setMaxQuests(int m) { put("gh.max", String.valueOf(Math.max(1, Math.min(10, m)))); }

    public int startHourMsk() { return (startHourUtc() + MSK_OFFSET_HOURS) % 24; }
    public int endHourMsk() { return (startHourUtc() + 1 + MSK_OFFSET_HOURS) % 24; }

    // ───────────────────────── окно ─────────────────────────

    /** Строка статуса для меню и «Квестов» (без переноса строки): идёт сейчас, сегодня позже или завтра; пусто, если выключен. */
    public String statusLine() {
        if (!enabled()) return "";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = LocalDate.now().atTime(startHourUtc(), 0);
        String window = String.format("%02d:00-%02d:00", startHourMsk(), endHourMsk());
        String bonus = "+" + percent() + "% EXC за квест";
        if (!now.isBefore(start) && now.isBefore(start.plusHours(1))) {
            return "⚡ Золотой час идёт до " + String.format("%02d:00", endHourMsk()) + " по Москве: " + bonus
                    + " (первые " + maxQuests() + " квеста)";
        }
        return "⚡ Золотой час " + (now.isBefore(start) ? "сегодня" : "завтра") + " " + window + " по Москве: " + bonus;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void tick() {
        try {
            if (!enabled()) return;
            LocalDateTime now = LocalDateTime.now();
            String today = LocalDate.now().toString();
            LocalDateTime start = LocalDate.now().atTime(startHourUtc(), 0);
            LocalDateTime end = start.plusHours(1);
            if (!now.isBefore(start) && now.isBefore(end) && !today.equals(get("gh.last"))) {
                put("gh.last", today);
                boostService.create(start, end, percent(), maxQuests(), "GOLDEN_HOUR");
                log.info("[GoldenHour] window created {} - {} (+{}%, first {} quests)", start, end, percent(), maxQuests());
            }
            if (pushEnabled() && !today.equals(get("gh.push.last"))
                    && !now.isBefore(start.minusMinutes(PUSH_LEAD_MINUTES)) && now.isBefore(start.plusMinutes(10))) {
                put("gh.push.last", today);
                int sent = sendPush();
                log.info("[GoldenHour] push sent to {} player(s)", sent);
            }
        } catch (Exception e) {
            log.error("[GoldenHour] tick failed", e);
        }
    }

    /** Напоминание игрокам, заходившим за последние 7 дней (бот или мини-апп) и прошедшим лимит частоты. */
    private int sendPush() throws InterruptedException {
        LocalDateTime since = LocalDateTime.now().minusDays(PUSH_ACTIVE_DAYS);
        int sent = 0;
        for (AppUser u : userService.allRegisteredUsers()) {
            if (u.isBlocked()) continue;
            boolean active = (u.getLastBotActivityAt() != null && u.getLastBotActivityAt().isAfter(since))
                    || (u.getLastMiniAppOpenAt() != null && u.getLastMiniAppOpenAt().isAfter(since));
            if (!active) continue;
            try {
                if (!notificationGate.tryAcquire(u, NudgeType.GOLDEN_HOUR)) continue;
                eventPublisher.publishEvent(new GoldenHourPushEvent(this, u.getTelegramId(), startHourMsk(), endHourMsk(), percent(), maxQuests()));
                sent++;
            } catch (Exception e) {
                log.warn("[GoldenHour] push failed for user {}", u.getTelegramId(), e);
            }
            Thread.sleep(40);
        }
        return sent;
    }

    /** Пробное напоминание одному игроку (админ) - в обход лимита частоты и выключателя. */
    public void sendTestPush(Long telegramId) {
        eventPublisher.publishEvent(new GoldenHourPushEvent(this, telegramId, startHourMsk(), endHourMsk(), percent(), maxQuests()));
    }
}
