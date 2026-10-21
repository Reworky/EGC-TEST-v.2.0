package ru.gamebot.platform.service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.EventBudgetAlertEvent;

/**
 * Сезонное событие (Хэллоуин-2026 и последующие): одно «текущее» событие, параметры в app_settings (event.*), чтобы включать, выключать и менять
 * без деплоя. Время хранится и сравнивается по МСК. Событие влияет на три вещи: (1) окно доступности и потолок затрат у квестов с eventCode,
 * (2) надбавка к призу топ-отряда недели, (3) баннер в боте и мини-аппе. Нет настроек или event.enabled != true - никаких следов события.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonalEventService {

    public static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private static final String K_CODE = "event.code";
    private static final String K_TITLE = "event.title";
    private static final String K_START = "event.start";
    private static final String K_END = "event.end";
    private static final String K_ENABLED = "event.enabled";
    private static final String K_SQUAD_BONUS = "event.squad_bonus_exc";
    private static final String K_QUEST_BUDGET = "event.quest_budget_exc";
    private static final String K_THEME = "event.theme";

    private final AppSettingRepository settings;
    private final QuestSubmissionRepository submissionRepository;
    private final ApplicationEventPublisher publisher;

    /** О каких порогах потолка уже предупреждали (код_событии:80 / :100); сбрасывается при перезапуске - тогда админ получит напоминание ещё раз. */
    private final Set<String> alertedThresholds = ConcurrentHashMap.newKeySet();

    public record Event(String code, String title, LocalDateTime start, LocalDateTime end, boolean enabled,
                        long squadBonusExc, long questBudgetExc, String theme) {}

    public static LocalDateTime nowMsk() {
        return LocalDateTime.now(MSK);
    }

    private String get(String key) {
        try {
            return settings.findById(key).map(AppSetting::getValue).map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
        } catch (Exception e) {
            log.warn("Failed to read setting {}", key, e);
            return null;
        }
    }

    private void put(String key, String value) {
        AppSetting s = new AppSetting();
        s.setKey(key);
        s.setValue(value);
        settings.save(s);
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Настроенное событие (даже выключенное или вне окна); empty - события в настройках нет. */
    public Optional<Event> configured() {
        String code = get(K_CODE);
        String start = get(K_START);
        String end = get(K_END);
        if (code == null || start == null || end == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Event(code, get(K_TITLE) != null ? get(K_TITLE) : code,
                    LocalDateTime.parse(start), LocalDateTime.parse(end),
                    "true".equalsIgnoreCase(get(K_ENABLED)),
                    parseLong(get(K_SQUAD_BONUS)), parseLong(get(K_QUEST_BUDGET)),
                    get(K_THEME) != null ? get(K_THEME) : "default"));
        } catch (Exception e) {
            log.warn("Bad seasonal event settings: start={}, end={}", start, end, e);
            return Optional.empty();
        }
    }

    /** Событие, идущее прямо сейчас: включено и start ≤ now < end (МСК). */
    public Optional<Event> current() {
        LocalDateTime now = nowMsk();
        return configured().filter(e -> e.enabled() && !now.isBefore(e.start()) && now.isBefore(e.end()));
    }

    /** Задать событие целиком (команда админа /event). */
    public void configure(String code, String title, LocalDateTime start, LocalDateTime end, long squadBonusExc, long questBudgetExc, String theme, boolean enabled) {
        put(K_CODE, code);
        put(K_TITLE, title);
        put(K_START, start.toString());
        put(K_END, end.toString());
        put(K_SQUAD_BONUS, String.valueOf(squadBonusExc));
        put(K_QUEST_BUDGET, String.valueOf(questBudgetExc));
        put(K_THEME, theme);
        put(K_ENABLED, String.valueOf(enabled));
        alertedThresholds.removeIf(k -> k.startsWith(code + ":"));
    }

    public void setEnabled(boolean enabled) {
        put(K_ENABLED, String.valueOf(enabled));
    }

    /**
     * Виден ли квест сейчас: у обычного квеста окна нет; у квеста с окном - now (МСК) внутри [availableFrom, availableUntil); у квеста с eventCode дополнительно
     * нужно, чтобы событие с этим кодом было настроено и включено (рубильник без деплоя), а потолок затрат не был выбран.
     */
    public boolean isQuestAvailable(Quest quest) {
        if (quest.getAvailableFrom() == null && quest.getAvailableUntil() == null && quest.getEventCode() == null) {
            return true;
        }
        LocalDateTime now = nowMsk();
        if (quest.getAvailableFrom() != null && now.isBefore(quest.getAvailableFrom())) return false;
        if (quest.getAvailableUntil() != null && !now.isBefore(quest.getAvailableUntil())) return false;
        if (quest.getEventCode() != null) {
            Optional<Event> e = configured();
            if (e.isEmpty() || !e.get().enabled() || !e.get().code().equals(quest.getEventCode())) return false;
            if (budgetExhausted(e.get())) return false;
        }
        return true;
    }

    /** Кэш на 20 секунд: isQuestAvailable вызывается при каждом показе списка квестов, а сумма - запрос в БД. */
    private final java.util.Map<String, long[]> usedCache = new ConcurrentHashMap<>();

    public long usedBudget(String eventCode) {
        long now = System.currentTimeMillis();
        long[] cached = usedCache.get(eventCode);
        if (cached != null && now - cached[1] < 20_000) {
            return cached[0];
        }
        long used = submissionRepository.sumAwardedCoinsByEventCode(eventCode);
        usedCache.put(eventCode, new long[]{used, now});
        return used;
    }

    /** Свежее значение без кэша (после одобрения квеста и для /event status). */
    public long usedBudgetFresh(String eventCode) {
        usedCache.remove(eventCode);
        return usedBudget(eventCode);
    }

    public boolean budgetExhausted(Event e) {
        return e.questBudgetExc() > 0 && usedBudget(e.code()) >= e.questBudgetExc();
    }

    /** Вызывается после одобрения квеста: при достижении 80% и 100% потолка один раз предупреждает админов. */
    public void onQuestApproved(Quest quest) {
        if (quest.getEventCode() == null) return;
        try {
            Event e = configured().filter(x -> x.code().equals(quest.getEventCode())).orElse(null);
            if (e == null || e.questBudgetExc() <= 0) return;
            long used = usedBudgetFresh(e.code());
            int percent = (int) Math.min(100, used * 100 / e.questBudgetExc());
            for (int threshold : new int[]{100, 80}) {
                if (percent >= threshold) {
                    if (alertedThresholds.add(e.code() + ":" + threshold)) {
                        publisher.publishEvent(new EventBudgetAlertEvent(this, e.code(), e.title(), threshold, used, e.questBudgetExc()));
                    }
                    break;
                }
            }
        } catch (Exception ex) {
            log.warn("Event budget check failed for quest {}", quest.getId(), ex);
        }
    }

    /**
     * Надбавка к призу топ-отряда за неделю, которая ЗАКАНЧИВАЕТСЯ в момент расчёта (понедельник 00:00 МСК). Неделя = [понедельник 00:00 МСК перед now − 7 суток; понедельник 00:00 МСК).
     * Событие учитывается, если его окно пересекается с неделей по СТРОГИМ границам (start < конец недели и end > начало недели): иначе событие,
     * начинающееся ровно в понедельник 00:00, зацепило бы предыдущую неделю. Считается по МСК независимо от часового пояса JVM сервера.
     * Надбавка берётся только если событие включено.
     */
    public long squadBonusForFinishedWeek() {
        LocalDateTime weekEnd = nowMsk().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay();
        LocalDateTime weekStart = weekEnd.minusWeeks(1);
        return configured()
                .filter(Event::enabled)
                .filter(e -> e.start().isBefore(weekEnd) && e.end().isAfter(weekStart))
                .map(Event::squadBonusExc)
                .orElse(0L);
    }

    /** Приз недели отрядов прямо сейчас: базовый пул плюс надбавка, пока идёт включённое событие (для текстов «Приз победителю» в постах и рассылках). */
    public long squadPoolNow() {
        long bonus = current().map(Event::squadBonusExc).orElse(0L);
        return SquadService.WEEKLY_PRIZE_POOL + Math.max(0, bonus);
    }

    /** Подпись события для транзакции/уведомления; без события - null. */
    public String squadBonusLabel() {
        return configured().filter(Event::enabled).map(Event::title).orElse(null);
    }
}
