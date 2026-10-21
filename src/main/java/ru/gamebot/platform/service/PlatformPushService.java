package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.PlayerPushEvent;
import ru.gamebot.platform.event.WeekendBoostStartedEvent;

/** Короткие рассылки игрокам в бота вместо постов канала на согласование (решение владельца 2026-10-17, тексты согласованы): буст выходных, новые квесты по игре
 *  после смены пачки, гонка отрядов (среда 05:00 UTC, личная: место отряда) и гонка за Зал славы (воскресенье 05:00 UTC, личная: место игрока).
 *  Уходят БЕЗ согласования, но через лимит частоты (NotificationGateService: не больше 2 сообщений в сутки на игрока). Каждую рассылку можно выключить
 *  отдельно: /push off boost|rotation|squad|hall. Гонка отрядов и гонка за Зал славы дополнительно остаются постами в канале на согласование (решение владельца 17.10.2026). */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformPushService {

    public static final List<String> NAMES = List.of("boost", "rotation", "squad", "hall");

    private static final int ACTIVE_DAYS_BOOST = 14;
    private static final int ACTIVE_DAYS_GAME = 30;
    private static final int GAME_HISTORY_DAYS = 60;

    private final AppSettingRepository settingRepository;
    private final UserService userService;
    private final SquadService squadService;
    private final NotificationGateService notificationGate;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SeasonalEventService seasonalEventService;

    // ───────────────────────── настройки ─────────────────────────

    public boolean enabled(String name) {
        return !"0".equals(settingRepository.findById("push." + name).map(AppSetting::getValue).orElse(null));
    }

    public void setEnabled(String name, boolean v) {
        AppSetting s = settingRepository.findById("push." + name).orElseGet(AppSetting::new);
        s.setKey("push." + name);
        s.setValue(v ? "1" : "0");
        settingRepository.save(s);
    }

    // ───────────────────────── отправка ─────────────────────────

    private static boolean activeSince(AppUser u, LocalDateTime since) {
        return (u.getLastBotActivityAt() != null && u.getLastBotActivityAt().isAfter(since))
                || (u.getLastMiniAppOpenAt() != null && u.getLastMiniAppOpenAt().isAfter(since));
    }

    private boolean send(AppUser u, NudgeType type, String html, String buttonText, String buttonCallback, String gameName) {
        try {
            if (u.isBlocked() || !u.isRegistrationCompleted()) return false;
            if (!notificationGate.tryAcquire(u, type)) return false;
            eventPublisher.publishEvent(new PlayerPushEvent(this, u.getTelegramId(), html, buttonText, buttonCallback, gameName));
            Thread.sleep(40);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("[PlatformPush] failed for user {}", u.getTelegramId(), e);
            return false;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String num(long v) {
        return String.format(Locale.forLanguageTag("ru"), "%,d", v).replace(' ', ' ');
    }

    /** Буст выходных (пятница 18:00 UTC): всем, кто заходил за 14 дней. Вместо поста в канал на согласование. */
    @EventListener
    public void onWeekendBoostStarted(WeekendBoostStartedEvent event) {
        if (!enabled("boost")) return;
        int maxQuests = QuestRewardBoostService.MAX_BOOSTED_QUESTS_PER_USER;
        String html = "🔥 <b>Буст выходных: +" + event.getBoostPercent() + "% EXC за квесты!</b>\n\n"
                + "До понедельника (00:00 UTC, 03:00 по Москве) первые " + maxQuests + " засчитанных квеста дают на " + event.getBoostPercent() + "% больше.";
        CompletableFuture.runAsync(() -> {
            LocalDateTime since = LocalDateTime.now().minusDays(ACTIVE_DAYS_BOOST);
            int sent = 0;
            for (AppUser u : userService.allRegisteredUsers()) {
                if (activeSince(u, since) && send(u, NudgeType.WEEKEND_BOOST, html, "🎯 Квесты", "menu:quests", null)) sent++;
            }
            log.info("[PlatformPush] weekend boost sent to {} player(s)", sent);
        });
    }

    /** Новые квесты по игре после смены пачки: игрокам, брали квесты этой игры за 60 дней и заходившим за 30. */
    public void pushQuestRotation(String gameName, String packName, List<Quest> quests) {
        if (!enabled("rotation") || quests == null || quests.isEmpty()) return;
        List<Quest> sorted = new ArrayList<>(quests);
        sorted.sort((a, b) -> Long.compare(b.getRewardCoins(), a.getRewardCoins()));
        StringBuilder sb = new StringBuilder("🎮 <b>Новые квесты по ").append(esc(gameName)).append("</b>\n\nВ боте обновился набор: теперь их <b>")
                .append(quests.size()).append("</b>. Самые щедрые: ");
        List<String> top = new ArrayList<>();
        for (Quest q : sorted.subList(0, Math.min(2, sorted.size()))) {
            top.add(esc(q.getTitle()) + " (" + num(q.getRewardCoins()) + " EXC)");
        }
        sb.append(String.join(", ", top)).append(".\n\nТе, что уже взял, можно спокойно доделать.");
        String html = sb.toString();
        CompletableFuture.runAsync(() -> {
            Set<Long> ids = new HashSet<>(questSubmissionRepository.findUserIdsWithSubmissionInGameSince(gameName, LocalDateTime.now().minusDays(GAME_HISTORY_DAYS)));
            LocalDateTime since = LocalDateTime.now().minusDays(ACTIVE_DAYS_GAME);
            int sent = 0;
            for (Long id : ids) {
                AppUser u = userService.findById(id).orElse(null);
                if (u != null && activeSince(u, since) && send(u, NudgeType.PACK_ROTATION, html, "🎮 Квесты: " + gameName, null, gameName)) sent++;
            }
            log.info("[PlatformPush] pack rotation '{}' ({}) sent to {} player(s)", gameName, packName, sent);
        });
    }

    /** Гонка отрядов, среда 05:00 UTC: каждому участнику отряда - место его отряда и отставание от первого (личная версия поста для канала). */
    @Scheduled(cron = "0 0 5 * * WED")
    public void pushSquadRace() {
        try {
            if (!enabled("squad")) return;
            List<SquadService.SquadRankEntry> board = squadService.getFullLeaderboard();
            if (board.isEmpty()) return;
            LocalDateTime since = LocalDateTime.now().minusDays(ACTIVE_DAYS_GAME);
            int sent = 0;
            for (int i = 0; i < board.size(); i++) {
                SquadService.SquadRankEntry e = board.get(i);
                if (e.memberCount() < 2) continue;
                int rank = i + 1;
                String name = esc(e.squad().getName());
                String html = "🛡️ <b>Гонка отрядов: экватор недели</b>\n\n"
                        + (rank == 1
                        ? "Твой отряд «" + name + "» сейчас на <b>1-м</b> месте: " + num(e.weeklyXp()) + " XP."
                                + (board.size() > 1 ? " Отрыв от второго: <b>" + num(e.weeklyXp() - board.get(1).weeklyXp()) + " XP</b>." : "")
                        : "Твой отряд «" + name + "» сейчас на <b>" + rank + "-м</b> месте: " + num(e.weeklyXp()) + " XP. До первого места не хватает <b>"
                                + num(board.get(0).weeklyXp() - e.weeklyXp()) + " XP</b>.")
                        + "\nПобедитель недели делит <b>" + num(seasonalEventService.squadPoolNow()) + " EXC</b>. Неделя закончится в понедельник в 00:00 UTC.";
                for (AppUser m : squadService.getMembers(e.squad())) {
                    if (activeSince(m, since) && send(m, NudgeType.SQUAD_RACE, html, "⚔️ Мой отряд", "menu:squads", null)) sent++;
                }
            }
            log.info("[PlatformPush] squad race sent to {} player(s)", sent);
        } catch (Exception e) {
            log.error("[PlatformPush] squad race failed", e);
        }
    }

    /** Гонка за Зал славы, воскресенье 05:00 UTC: каждому, кто набрал XP на этой неделе - его место и порог тройки. */
    @Scheduled(cron = "0 0 5 * * SUN")
    public void pushHallRace() {
        try {
            if (!enabled("hall")) return;
            List<AppUser> ranked = new ArrayList<>();
            for (AppUser u : userService.allRegisteredUsers()) {
                if (!u.isBlocked() && u.getWeeklyXp() > 0) ranked.add(u);
            }
            ranked.sort((a, b) -> Long.compare(b.getWeeklyXp(), a.getWeeklyXp()));
            if (ranked.size() < 3) return;
            long thirdXp = ranked.get(2).getWeeklyXp();
            int sent = 0;
            for (int i = 0; i < ranked.size(); i++) {
                AppUser u = ranked.get(i);
                int rank = i + 1;
                String html = "🏁 <b>Гонка за Зал славы</b>\n\n"
                        + (rank <= 3
                        ? "Ты сейчас в тройке: <b>" + rank + "-е</b> место, <b>" + num(u.getWeeklyXp()) + " XP</b>. Держи отрыв до конца недели."
                        : "Ты на <b>" + rank + "-м</b> месте с <b>" + num(u.getWeeklyXp()) + " XP</b>. Чтобы попасть в тройку, нужно больше <b>" + num(thirdXp) + " XP</b>.")
                        + "\nНеделя закончится в понедельник в 00:00 UTC (03:00 по Москве).";
                if (send(u, NudgeType.HALL_RACE, html, "🎯 Квесты", "menu:quests", null)) sent++;
            }
            log.info("[PlatformPush] hall race sent to {} player(s)", sent);
        } catch (Exception e) {
            log.error("[PlatformPush] hall race failed", e);
        }
    }
}
