package ru.gamebot.platform.service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestPack;
import ru.gamebot.platform.domain.model.QuestPackSchedule;
import ru.gamebot.platform.domain.repository.QuestPackScheduleRepository;

/**
 * Автосмена пачек квестов по расписанию (у каждой игры — свой день недели и цикл, чтобы смены шли вразнобой, а не
 * все в один день). Пачки идут по кругу в порядке создания; пустые пачки пропускаются. Время хранится по Москве.
 *
 * Бэклог: nextSwitchAt после смены считается вперёд от СТАРОГО nextSwitchAt до первого момента в будущем — после
 * простоя бота происходит ровно одна смена, а не серия «догоняющих».
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestPackScheduleService {

    public static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final long REMINDER_HOURS = 24;

    /** Рекомендованные дни смены по играм (разнос по неделе); цикл по умолчанию 14 дней (решение владельца 2026-10-03). */
    private static final Map<String, Integer> DEFAULT_DAYS = Map.of(
            "brawl stars", 1, "clash royale", 2, "clash of clans", 3, "cs2", 4,
            "dota 2", 5, "pubg pc", 6, "pubg mobile", 7);

    private final QuestPackScheduleRepository scheduleRepository;
    private final QuestPackService packService;

    public record RotationEvent(String gameName, QuestPack pack, QuestPackService.SwitchResult result, List<Quest> quests) {}

    public record ReminderEvent(String gameName, String nextPackName, LocalDateTime at) {}

    public static LocalDateTime nowMsk() {
        return LocalDateTime.now(MSK);
    }

    @Transactional
    public QuestPackSchedule get(String gameName) {
        return scheduleRepository.findByGameNameIgnoreCase(gameName).orElseGet(() -> {
            QuestPackSchedule s = new QuestPackSchedule();
            s.setGameName(gameName);
            s.setDayOfWeek(DEFAULT_DAYS.getOrDefault(gameName.toLowerCase(), 1));
            s.setCycleDays(14);
            return scheduleRepository.save(s);
        });
    }

    public List<QuestPackSchedule> allEnabled() {
        return scheduleRepository.findAllByEnabledTrueOrderByNextSwitchAtAsc();
    }

    /** Пачки, участвующие в ротации (непустые), в порядке создания. */
    public List<QuestPack> rotationOrder(String gameName) {
        return packService.packsOf(gameName).stream().filter(p -> packService.questCount(p) > 0).toList();
    }

    /** @return null если включено, иначе причина отказа. */
    @Transactional
    public String setEnabled(String gameName, boolean enabled) {
        QuestPackSchedule s = get(gameName);
        if (enabled) {
            if (rotationOrder(gameName).size() < 2) {
                return "Для ротации нужно минимум две пачки с квестами.";
            }
            s.setEnabled(true);
            s.setEnabledSince(LocalDateTime.now());
            s.setNextSwitchAt(nextOccurrence(s, nowMsk()));
            s.setReminderSent(false);
        } else {
            s.setEnabled(false);
        }
        scheduleRepository.save(s);
        return null;
    }

    @Transactional
    public void setDay(String gameName, int dayOfWeek) {
        QuestPackSchedule s = get(gameName);
        s.setDayOfWeek(Math.max(1, Math.min(7, dayOfWeek)));
        reschedule(s);
    }

    @Transactional
    public void setCycle(String gameName, int days) {
        QuestPackSchedule s = get(gameName);
        s.setCycleDays(Math.max(1, Math.min(60, days)));
        reschedule(s);
    }

    private void reschedule(QuestPackSchedule s) {
        if (s.isEnabled()) {
            s.setNextSwitchAt(nextOccurrence(s, nowMsk()));
            s.setReminderSent(false);
        }
        scheduleRepository.save(s);
    }

    private static LocalDateTime nextOccurrence(QuestPackSchedule s, LocalDateTime from) {
        LocalDateTime t = from.withHour(s.getHour()).withMinute(0).withSecond(0).withNano(0)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.of(s.getDayOfWeek())));
        return t.isAfter(from) ? t : t.plusWeeks(1);
    }

    /** Следующая по кругу пачка после включённой, null — если ротировать нечего. */
    public QuestPack nextPack(String gameName) {
        List<QuestPack> order = rotationOrder(gameName);
        if (order.size() < 2) {
            return null;
        }
        int activeIdx = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).isActive()) {
                activeIdx = i;
                break;
            }
        }
        return order.get((activeIdx + 1) % order.size());
    }

    /** Делает все наступившие смены. Вызывается планировщиком раз в час. */
    public List<RotationEvent> runDueRotations() {
        LocalDateTime now = nowMsk();
        List<RotationEvent> events = new ArrayList<>();
        for (QuestPackSchedule s : allEnabled()) {
            if (s.getNextSwitchAt() == null || s.getNextSwitchAt().isAfter(now)) {
                continue;
            }
            try {
                QuestPack next = nextPack(s.getGameName());
                if (next != null) {
                    QuestPackService.SwitchResult result = packService.switchTo(next.getId(), ru.gamebot.platform.domain.model.QuestPackSwitchLog.AUTO);
                    events.add(new RotationEvent(s.getGameName(), next, result, packService.questsOf(next)));
                }
                advance(s, now);
            } catch (Exception e) {
                log.error("[QuestPackSchedule] rotation failed for '{}'", s.getGameName(), e);
            }
        }
        return events;
    }

    private void advance(QuestPackSchedule s, LocalDateTime now) {
        LocalDateTime next = s.getNextSwitchAt().plusDays(s.getCycleDays());
        while (!next.isAfter(now)) {
            next = next.plusDays(s.getCycleDays());
        }
        s.setLastSwitchAt(now);
        s.setNextSwitchAt(next);
        s.setReminderSent(false);
        scheduleRepository.save(s);
    }

    /** Смены, до которых осталось меньше суток и о которых ещё не напоминали. */
    public List<ReminderEvent> dueReminders() {
        LocalDateTime now = nowMsk();
        List<ReminderEvent> events = new ArrayList<>();
        for (QuestPackSchedule s : allEnabled()) {
            if (s.isReminderSent() || s.getNextSwitchAt() == null
                    || s.getNextSwitchAt().minusHours(REMINDER_HOURS).isAfter(now)) {
                continue;
            }
            QuestPack next = nextPack(s.getGameName());
            s.setReminderSent(true);
            scheduleRepository.save(s);
            if (next != null) {
                events.add(new ReminderEvent(s.getGameName(), next.getName(), s.getNextSwitchAt()));
            }
        }
        return events;
    }
}
