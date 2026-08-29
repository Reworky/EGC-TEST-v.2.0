package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.BrawlVerifyType;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.BrawlQuestAutoVerifiedEvent;

/**
 * Автоматическая верификация квестов Brawl Stars через официальный API вместо ручного скриншота.
 * Отдельно от BrawlStarsTournamentService (та же логика разделения, что и с TournamentService) —
 * квесты и турниры концептуально не связаны, только используют один и тот же BrawlStarsApiService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrawlQuestVerificationService {

    private static final long BATCH_DELAY_MS = 180; // тот же паттерн, что в BrawlStarsTournamentService.runBatch
    private static final DateTimeFormatter BRAWL_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'");

    private final BrawlStarsApiService brawlStarsApiService;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppUserRepository appUserRepository;
    private final QuestService questService;
    private final ApplicationEventPublisher eventPublisher;

    public record TagLookupResult(boolean success, String error, BrawlStarsApiService.PlayerInfo playerInfo) {}

    /** В отличие от BrawlStarsTournamentService.lookupTag — без проверки "тег уже занят": тег для квестов переиспользуется свободно. */
    public TagLookupResult lookupTag(String rawTag) {
        if (!brawlStarsApiService.isEnabled()) {
            return new TagLookupResult(false, "Привязка тега временно недоступна. Попробуйте позже.", null);
        }
        try {
            Optional<BrawlStarsApiService.PlayerInfo> info = brawlStarsApiService.fetchPlayer(rawTag);
            if (info.isEmpty()) {
                return new TagLookupResult(false, "Тег не найден. Проверьте правильность (формат #ABC123).", null);
            }
            return new TagLookupResult(true, null, info.get());
        } catch (BrawlStarsApiService.BrawlStarsTransientException e) {
            log.warn("Brawl tag lookup transient failure for tag={}", rawTag, e);
            return new TagLookupResult(false, "Сервис Brawl Stars временно недоступен. Попробуйте ещё раз чуть позже.", null);
        }
    }

    @Transactional
    public void linkTag(AppUser user, BrawlStarsApiService.PlayerInfo playerInfo) {
        user.setBrawlStarsTag(playerInfo.tag());
        user.setBrawlTagConfirmedAt(LocalDateTime.now());
        appUserRepository.save(user);
    }

    /** Точка входа шедулера. Не @Transactional — последовательные сетевые вызовы, как в BrawlStarsTournamentService.runBatch. */
    public void checkInProgressSubmissions() {
        List<QuestSubmission> pending = questSubmissionRepository.findInProgressBrawlAutoVerify();
        for (QuestSubmission submission : pending) {
            try {
                checkOne(submission);
            } catch (Exception e) {
                log.warn("Brawl auto-verify check failed for submission {}", submission.getId(), e);
            }
            sleepBetweenCalls();
        }
    }

    private void checkOne(QuestSubmission submission) throws BrawlStarsApiService.BrawlStarsTransientException {
        Quest quest = submission.getQuest();
        String tag = submission.getUser().getBrawlStarsTag();
        if (quest.getBrawlVerifyType() == BrawlVerifyType.TROPHIES) {
            checkTrophies(submission, quest, tag);
        } else {
            checkBattles(submission, quest, tag);
        }
    }

    private void checkTrophies(QuestSubmission submission, Quest quest, String tag) throws BrawlStarsApiService.BrawlStarsTransientException {
        Optional<BrawlStarsApiService.PlayerInfo> info = brawlStarsApiService.fetchPlayer(tag);
        if (info.isEmpty()) return; // тег стал невалиден/переименован — пропускаем цикл, попробуем в следующий раз
        int current = info.get().trophies();
        if (submission.getBrawlBaselineTrophies() == null) {
            submission.setBrawlBaselineTrophies(current);
            questSubmissionRepository.save(submission);
            return; // первый опрос только фиксирует базу, ничего не засчитывает
        }
        int delta = current - submission.getBrawlBaselineTrophies();
        submission.setBrawlProgressCount(Math.max(0, delta));
        questSubmissionRepository.save(submission);
        if (delta >= quest.getBrawlTargetCount()) {
            completeSubmission(submission);
        }
    }

    private void checkBattles(QuestSubmission submission, Quest quest, String tag) throws BrawlStarsApiService.BrawlStarsTransientException {
        String oldCursor = submission.getBrawlBattleCursor() != null
                ? submission.getBrawlBattleCursor()
                : formatBrawlTime(submission.getCreatedAt());
        List<BrawlStarsApiService.BattleLogEntry> entries = brawlStarsApiService.fetchBattleLog(tag);
        if (entries.isEmpty()) return;

        String newCursor = oldCursor;
        int matched = 0;
        for (BrawlStarsApiService.BattleLogEntry e : entries) {
            if (e.battleTime().compareTo(oldCursor) > 0 && matchesFilters(e, quest)) {
                matched++;
            }
            if (e.battleTime().compareTo(newCursor) > 0) {
                newCursor = e.battleTime();
            }
        }
        submission.setBrawlBattleCursor(newCursor);
        submission.setBrawlProgressCount(submission.getBrawlProgressCount() + matched);
        questSubmissionRepository.save(submission);
        if (submission.getBrawlProgressCount() >= quest.getBrawlTargetCount()) {
            completeSubmission(submission);
        }
    }

    private boolean matchesFilters(BrawlStarsApiService.BattleLogEntry e, Quest quest) {
        if (quest.isBrawlRequireVictory() && !e.victory()) return false;
        if (quest.isBrawlRequireRanked() && !"ranked".equalsIgnoreCase(e.type())) return false;
        if (quest.isBrawlRequireTeam() && !e.isTeamMode()) return false;
        if (quest.getBrawlModeKeys() != null && !csvContains(quest.getBrawlModeKeys(), e.mode())) return false;
        if (quest.getBrawlBrawlerNames() != null && !csvContains(quest.getBrawlBrawlerNames(), e.playerBrawlerName())) return false;
        return true;
    }

    private boolean csvContains(String csv, String value) {
        if (value == null) return false;
        for (String s : csv.split(",")) {
            if (s.trim().equalsIgnoreCase(value.trim())) return true;
        }
        return false;
    }

    private void completeSubmission(QuestSubmission submission) {
        QuestSubmission approved = questService.approveSubmission(submission.getId());
        eventPublisher.publishEvent(new BrawlQuestAutoVerifiedEvent(this, approved.getId()));
    }

    private String formatBrawlTime(LocalDateTime dt) {
        return dt.format(BRAWL_TIME_FMT);
    }

    private void sleepBetweenCalls() {
        try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
