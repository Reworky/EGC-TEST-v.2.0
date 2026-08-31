package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.ClashVerifyType;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.ClashQuestAutoVerifiedEvent;

/**
 * Автоматическая верификация квестов Clash of Clans через официальный API вместо ручного скриншота —
 * тот же паттерн, что и BrawlQuestVerificationService (baseline при первом опросе, дальше дельта).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClashQuestVerificationService {

    private static final long BATCH_DELAY_MS = 180; // тот же паттерн, что в BrawlQuestVerificationService

    private final ClashOfClansApiService clashApiService;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppUserRepository appUserRepository;
    private final QuestService questService;
    private final ApplicationEventPublisher eventPublisher;

    public record TagLookupResult(boolean success, String error, ClashOfClansApiService.PlayerInfo playerInfo) {}

    public TagLookupResult lookupTag(String rawTag) {
        if (!clashApiService.isEnabled()) {
            return new TagLookupResult(false, "Привязка тега временно недоступна. Попробуйте позже.", null);
        }
        try {
            Optional<ClashOfClansApiService.PlayerInfo> info = clashApiService.fetchPlayer(rawTag);
            if (info.isEmpty()) {
                return new TagLookupResult(false, "Тег не найден. Проверьте правильность (формат #ABC123).", null);
            }
            return new TagLookupResult(true, null, info.get());
        } catch (ClashOfClansApiService.ClashApiTransientException e) {
            log.warn("Clash tag lookup transient failure for tag={}", rawTag, e);
            return new TagLookupResult(false, "Сервис Clash of Clans временно недоступен. Попробуйте ещё раз чуть позже.", null);
        }
    }

    @Transactional
    public void linkTag(AppUser user, ClashOfClansApiService.PlayerInfo playerInfo) {
        user.setClashOfClansTag(playerInfo.tag());
        user.setClashTagConfirmedAt(LocalDateTime.now());
        appUserRepository.save(user);
    }

    /** Точка входа шедулера. Не @Transactional — последовательные сетевые вызовы, как в BrawlQuestVerificationService. */
    public void checkInProgressSubmissions() {
        List<QuestSubmission> pending = questSubmissionRepository.findInProgressClashAutoVerify();
        for (QuestSubmission submission : pending) {
            try {
                checkOne(submission);
            } catch (Exception e) {
                log.warn("Clash auto-verify check failed for submission {}", submission.getId(), e);
            }
            sleepBetweenCalls();
        }
    }

    private void checkOne(QuestSubmission submission) throws ClashOfClansApiService.ClashApiTransientException {
        Quest quest = submission.getQuest();
        String tag = submission.getUser().getClashOfClansTag();
        Optional<ClashOfClansApiService.PlayerInfo> infoOpt = clashApiService.fetchPlayer(tag);
        if (infoOpt.isEmpty()) return; // тег стал невалиден/переименован — пропускаем цикл, попробуем в следующий раз
        ClashOfClansApiService.PlayerInfo info = infoOpt.get();

        if (quest.getClashVerifyType() == ClashVerifyType.RESOURCES) {
            checkResources(submission, quest, info);
            return;
        }
        int current = switch (quest.getClashVerifyType()) {
            case TOWN_HALL -> info.townHallLevel();
            case TROPHIES -> info.trophies();
            case WAR_STARS -> info.warStars();
            case DONATIONS -> info.donations();
            default -> info.attackWins(); // ATTACK_WINS
        };
        checkSingleValue(submission, quest, current);
    }

    private void checkSingleValue(QuestSubmission submission, Quest quest, int current) {
        if (submission.getClashBaselineValue() == null) {
            submission.setClashBaselineValue(current);
            questSubmissionRepository.save(submission);
            return; // первый опрос только фиксирует базу, ничего не засчитывает
        }
        int delta = current - submission.getClashBaselineValue();
        submission.setClashProgressCount(Math.max(0, delta));
        questSubmissionRepository.save(submission);
        if (delta >= quest.getClashTargetCount()) {
            completeSubmission(submission);
        }
    }

    private void checkResources(QuestSubmission submission, Quest quest, ClashOfClansApiService.PlayerInfo info) {
        if (submission.getClashBaselineValue() == null || submission.getClashBaselineValue2() == null) {
            submission.setClashBaselineValue(info.goldLooted());
            submission.setClashBaselineValue2(info.elixirLooted());
            questSubmissionRepository.save(submission);
            return; // первый опрос только фиксирует базу, ничего не засчитывает
        }
        int goldDelta = info.goldLooted() - submission.getClashBaselineValue();
        int elixirDelta = info.elixirLooted() - submission.getClashBaselineValue2();
        int progress = Math.max(0, Math.max(goldDelta, elixirDelta));
        submission.setClashProgressCount(progress);
        questSubmissionRepository.save(submission);
        if (progress >= quest.getClashTargetCount()) {
            completeSubmission(submission);
        }
    }

    private void completeSubmission(QuestSubmission submission) {
        QuestSubmission approved = questService.approveSubmission(submission.getId());
        eventPublisher.publishEvent(new ClashQuestAutoVerifiedEvent(this, approved.getId()));
    }

    private void sleepBetweenCalls() {
        try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
