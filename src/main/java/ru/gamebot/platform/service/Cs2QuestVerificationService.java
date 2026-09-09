package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.Cs2VerifyType;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.Cs2QuestAutoVerifiedEvent;

/**
 * Автоматическая верификация квестов CS2 через официальный Steam Web API вместо ручного скриншота.
 * Два механизма в одном сервисе (см. LAST_MATCH_TYPES): кумулятивные типы — дельта career-счётчика
 * с момента взятия квеста (как ClashRoyaleQuestVerificationService); LAST_MATCH_* типы — условие
 * последнего сыгранного матча (own last_match_* поля Steam API), ближе к Dota2-паттерну "один
 * подходящий матч", но без полной истории — виден только самый свежий матч на момент опроса.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Cs2QuestVerificationService {

    private static final long BATCH_DELAY_MS = 180;

    private final Cs2ApiService cs2ApiService;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppUserRepository appUserRepository;
    private final QuestService questService;
    private final ApplicationEventPublisher eventPublisher;

    public record AccountLookupResult(boolean success, String error, Long steamId64, long matchesPlayed) {}

    /** Принимает SteamID64 напрямую либо ссылку на профиль Steam (steamcommunity.com/profiles/<id>). */
    public AccountLookupResult lookupAccount(String rawInput) {
        if (!cs2ApiService.isEnabled()) {
            return new AccountLookupResult(false, "Привязка аккаунта временно недоступна. Попробуйте позже.", null, 0);
        }
        Long steamId64 = parseSteamId64(rawInput);
        if (steamId64 == null) {
            return new AccountLookupResult(false,
                    "Не удалось распознать SteamID64. Введите число из ссылки на профиль Steam "
                            + "(steamcommunity.com/profiles/<число>) или ссылку целиком.", null, 0);
        }
        try {
            Optional<Cs2ApiService.PlayerStats> stats = cs2ApiService.fetchStats(steamId64);
            if (stats.isEmpty()) {
                return new AccountLookupResult(false,
                        "Не удалось получить статистику CS2. Убедитесь, что в настройках приватности Steam "
                                + "включено «Игровая статистика: Все» (Public) и что сыграна хотя бы одна игра.",
                        null, 0);
            }
            return new AccountLookupResult(true, null, steamId64, stats.get().matchesPlayed());
        } catch (Cs2ApiService.Cs2ApiTransientException e) {
            log.warn("CS2 account lookup transient failure for steamId64={}", steamId64, e);
            return new AccountLookupResult(false, "Сервис Steam временно недоступен. Попробуйте ещё раз чуть позже.", null, 0);
        }
    }

    private Long parseSteamId64(String rawInput) {
        String trimmed = rawInput.trim();
        if (trimmed.contains("/")) {
            String[] parts = trimmed.split("/");
            trimmed = parts[parts.length - 1].isBlank() ? parts[parts.length - 2] : parts[parts.length - 1];
        }
        try {
            long value = Long.parseLong(trimmed);
            return Cs2ApiService.looksLikeSteamId64(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Transactional
    public void linkAccount(AppUser user, long steamId64) {
        user.setCs2SteamId64(steamId64);
        user.setCs2LinkedAt(LocalDateTime.now());
        appUserRepository.save(user);
    }

    /** Точка входа шедулера. Не @Transactional — последовательные сетевые вызовы, как у остальных Steam/Clash-сервисов. */
    public void checkInProgressSubmissions() {
        List<QuestSubmission> pending = questSubmissionRepository.findInProgressCs2AutoVerify();
        for (QuestSubmission submission : pending) {
            try {
                checkOne(submission);
            } catch (Exception e) {
                log.warn("CS2 auto-verify check failed for submission {}", submission.getId(), e);
            }
            sleepBetweenCalls();
        }
    }

    private static final java.util.Set<Cs2VerifyType> LAST_MATCH_TYPES = java.util.EnumSet.of(
            Cs2VerifyType.LAST_MATCH_KILLS, Cs2VerifyType.LAST_MATCH_DEATHS_MAX,
            Cs2VerifyType.LAST_MATCH_MVPS, Cs2VerifyType.LAST_MATCH_SCORE, Cs2VerifyType.LAST_MATCH_KD_RATIO);

    private void checkOne(QuestSubmission submission) throws Cs2ApiService.Cs2ApiTransientException {
        Quest quest = submission.getQuest();
        long steamId64 = submission.getUser().getCs2SteamId64();
        Optional<Cs2ApiService.PlayerStats> statsOpt = cs2ApiService.fetchStats(steamId64);
        if (statsOpt.isEmpty()) return; // статистика временно недоступна/стала приватной — пропускаем цикл
        Cs2ApiService.PlayerStats stats = statsOpt.get();

        if (LAST_MATCH_TYPES.contains(quest.getCs2VerifyType())) {
            checkLastMatch(submission, quest, stats);
        } else {
            checkCumulative(submission, quest, stats);
        }
    }

    private void checkCumulative(QuestSubmission submission, Quest quest, Cs2ApiService.PlayerStats stats) {
        long current = switch (quest.getCs2VerifyType()) {
            case WINS -> stats.matchesWon();
            case MVPS -> stats.mvps();
            case HEADSHOTS -> stats.headshots();
            case BOMBS_PLANTED -> stats.bombsPlanted();
            case BOMBS_DEFUSED -> stats.bombsDefused();
            case MATCHES_PLAYED -> stats.matchesPlayed();
            default -> stats.kills(); // KILLS
        };

        if (submission.getCs2BaselineValue() == null) {
            submission.setCs2BaselineValue(current);
            questSubmissionRepository.save(submission);
            return; // первый опрос только фиксирует базу, ничего не засчитывает
        }
        long delta = current - submission.getCs2BaselineValue();
        submission.setCs2ProgressCount((int) Math.max(0, Math.min(Integer.MAX_VALUE, delta)));
        questSubmissionRepository.save(submission);
        if (delta >= quest.getCs2TargetCount()) {
            completeSubmission(submission);
        }
    }

    /** cs2BaselineValue переиспользован под другой смысл для LAST_MATCH_* типов: не "стартовое значение
     *  счётчика", а "total_matches_played на момент последней проверки" — курсор, чтобы понять, появился
     *  ли НОВЫЙ матч с прошлого опроса (last_match_* иначе показывал бы один и тот же матч раз за разом).
     *  Если между опросами (раз в 10 мин) сыграно НЕСКОЛЬКО матчей — виден только последний, промежуточные
     *  подходящие матчи можно пропустить; это принятое ограничение открытого API (нет истории матчей). */
    private void checkLastMatch(QuestSubmission submission, Quest quest, Cs2ApiService.PlayerStats stats) {
        if (submission.getCs2BaselineValue() == null) {
            submission.setCs2BaselineValue(stats.matchesPlayed());
            questSubmissionRepository.save(submission);
            return;
        }
        if (stats.matchesPlayed() <= submission.getCs2BaselineValue()) {
            return; // новых матчей с прошлой проверки не было
        }
        boolean qualifies = switch (quest.getCs2VerifyType()) {
            case LAST_MATCH_KILLS -> stats.lastMatchKills() >= quest.getCs2TargetCount();
            case LAST_MATCH_DEATHS_MAX -> stats.lastMatchDeaths() <= quest.getCs2TargetCount();
            case LAST_MATCH_MVPS -> stats.lastMatchMvps() >= quest.getCs2TargetCount();
            case LAST_MATCH_SCORE -> stats.lastMatchScore() >= quest.getCs2TargetCount();
            // Цель хранится как отношение×100 (200 = K/D 2.0): kills/deaths >= target/100 ⇔ kills*100 >= target*deaths.
            case LAST_MATCH_KD_RATIO -> stats.lastMatchDeaths() == 0
                    ? stats.lastMatchKills() > 0
                    : stats.lastMatchKills() * 100 >= (long) quest.getCs2TargetCount() * stats.lastMatchDeaths();
            default -> false;
        };
        submission.setCs2BaselineValue(stats.matchesPlayed());
        questSubmissionRepository.save(submission);
        if (qualifies) {
            completeSubmission(submission);
        }
    }

    private void completeSubmission(QuestSubmission submission) {
        QuestSubmission approved = questService.approveSubmission(submission.getId());
        eventPublisher.publishEvent(new Cs2QuestAutoVerifiedEvent(this, approved.getId()));
    }

    private void sleepBetweenCalls() {
        try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
