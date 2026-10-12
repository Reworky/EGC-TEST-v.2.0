package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.SponsorQuestEvent;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.SponsorQuestEventRepository;

/** Видимость спонсорских квестов «подпишись на канал»: журнал воронки (кому показали / отправили, кто открыл) и выключатели.
 *  Выключатели (app_settings, без деплоя): 'sponsor_push.enabled' - пуш при создании и напоминание через сутки (по умолчанию ВЫКЛЮЧЕН,
 *  пока владелец не одобрил текст); 'sponsor_play.enabled' - показ спонсорского квеста в «🎯 Играть» раз в сутки на игрока (по умолчанию включён). */
@Slf4j
@Service
@RequiredArgsConstructor
public class SponsorQuestPromoService {

    public static final String KIND_PUSH = "PUSH";
    public static final String KIND_REMINDER = "REMINDER";
    public static final String KIND_SHOWN_PLAY = "SHOWN_PLAY";
    public static final String KIND_VIEWED = "VIEWED";

    private static final String PUSH_ENABLED_KEY = "sponsor_push.enabled";
    private static final String PLAY_ENABLED_KEY = "sponsor_play.enabled";

    private final SponsorQuestEventRepository eventRepository;
    private final AppSettingRepository appSettingRepository;

    public record Funnel(long pushed, long reminded, long shownInPlay, long viewed) {}

    public boolean pushEnabled() {
        return setting(PUSH_ENABLED_KEY, false);
    }

    public boolean playEnabled() {
        return setting(PLAY_ENABLED_KEY, true);
    }

    private boolean setting(String key, boolean defaultValue) {
        try {
            return appSettingRepository.findById(key)
                    .map(AppSetting::getValue)
                    .map(v -> "true".equalsIgnoreCase(v.trim()))
                    .orElse(defaultValue);
        } catch (Exception e) {
            log.warn("Failed to read setting {}", key, e);
            return defaultValue;
        }
    }

    /** Запись события воронки; сбой записи никогда не должен мешать игроку (вызывается из обработчиков вне транзакции). */
    public void record(Long questId, Long userId, String kind) {
        try {
            SponsorQuestEvent e = new SponsorQuestEvent();
            e.setQuestId(questId);
            e.setUserId(userId);
            e.setKind(kind);
            e.setCreatedAt(LocalDateTime.now());
            eventRepository.save(e);
        } catch (Exception ex) {
            log.warn("Failed to record sponsor quest event {} for quest {} user {}", kind, questId, userId, ex);
        }
    }

    /** Открытие карточки: считаем игрока один раз на квест. */
    public void recordViewedOnce(Long questId, Long userId) {
        try {
            if (!eventRepository.existsByQuestIdAndUserIdAndKind(questId, userId, KIND_VIEWED)) {
                record(questId, userId, KIND_VIEWED);
            }
        } catch (Exception ex) {
            log.warn("Failed to check viewed event for quest {} user {}", questId, userId, ex);
        }
    }

    public boolean has(Long questId, Long userId, String kind) {
        return eventRepository.existsByQuestIdAndUserIdAndKind(questId, userId, kind);
    }

    /** Показывали ли квест игроку в «Играть» сегодня (по серверному дню): не чаще раза в сутки на игрока. */
    public boolean shownInPlayToday(Long questId, Long userId) {
        return eventRepository.existsByQuestIdAndUserIdAndKindAndCreatedAtAfter(
                questId, userId, KIND_SHOWN_PLAY, LocalDate.now().atStartOfDay());
    }

    /** Кому уже ушёл пуш при создании 24-72 часа назад и кого пора мягко напомнить. */
    public List<Long> reminderCandidates(Long questId) {
        LocalDateTime now = LocalDateTime.now();
        return eventRepository.findUserIdsInWindow(questId, KIND_PUSH, now.minusHours(72), now.minusHours(24));
    }

    public Funnel funnel(Long questId) {
        return new Funnel(
                eventRepository.countDistinctUsers(questId, KIND_PUSH),
                eventRepository.countDistinctUsers(questId, KIND_REMINDER),
                eventRepository.countDistinctUsers(questId, KIND_SHOWN_PLAY),
                eventRepository.countDistinctUsers(questId, KIND_VIEWED));
    }
}
