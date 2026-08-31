package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.ClashRoyaleQuestAutoVerifiedEvent;

/**
 * Автоматическая верификация квестов Clash Royale через официальный API вместо ручного скриншота —
 * тот же паттерн, что и ClashQuestVerificationService (baseline при первом опросе, дальше дельта).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClashRoyaleQuestVerificationService {

    private static final long BATCH_DELAY_MS = 180;

    private final ClashRoyaleApiService clashRoyaleApiService;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppUserRepository appUserRepository;
    private final QuestService questService;
    private final ApplicationEventPublisher eventPublisher;

    public record TagLookupResult(boolean success, String error, ClashRoyaleApiService.PlayerInfo playerInfo) {}

    public TagLookupResult lookupTag(String rawTag) {
        if (!clashRoyaleApiService.isEnabled()) {
            return new TagLookupResult(false, "Привязка тега временно недоступна. Попробуйте позже.", null);
        }
        try {
            Optional<ClashRoyaleApiService.PlayerInfo> info = clashRoyaleApiService.fetchPlayer(rawTag);
            if (info.isEmpty()) {
                return new TagLookupResult(false, "Тег не найден. Проверьте правильность (формат #ABC123).", null);
            }
            return new TagLookupResult(true, null, info.get());
        } catch (ClashRoyaleApiService.ClashRoyaleApiTransientException e) {
            log.warn("Clash Royale tag lookup transient failure for tag={}", rawTag, e);
            return new TagLookupResult(false, "Сервис Clash Royale временно недоступен. Попробуйте ещё раз чуть позже.", null);
        }
    }

    @Transactional
    public void linkTag(AppUser user, ClashRoyaleApiService.PlayerInfo playerInfo) {
        user.setClashRoyaleTag(playerInfo.tag());
        user.setClashRoyaleTagConfirmedAt(LocalDateTime.now());
        appUserRepository.save(user);
    }

    /** Точка входа шедулера. Не @Transactional — последовательные сетевые вызовы, как у остальных Clash-сервисов. */
    public void checkInProgressSubmissions() {
        List<QuestSubmission> pending = questSubmissionRepository.findInProgressClashRoyaleAutoVerify();
        for (QuestSubmission submission : pending) {
            try {
                checkOne(submission);
            } catch (Exception e) {
                log.warn("Clash Royale auto-verify check failed for submission {}", submission.getId(), e);
            }
            sleepBetweenCalls();
        }
    }

    private void checkOne(QuestSubmission submission) throws ClashRoyaleApiService.ClashRoyaleApiTransientException {
        Quest quest = submission.getQuest();
        String tag = submission.getUser().getClashRoyaleTag();
        Optional<ClashRoyaleApiService.PlayerInfo> infoOpt = clashRoyaleApiService.fetchPlayer(tag);
        if (infoOpt.isEmpty()) return; // тег стал невалиден/переименован — пропускаем цикл, попробуем в следующий раз
        ClashRoyaleApiService.PlayerInfo info = infoOpt.get();

        int current = switch (quest.getClashRoyaleVerifyType()) {
            case TROPHIES -> info.trophies();
            case THREE_CROWN_WINS -> info.threeCrownWins();
            case WAR_DAY_WINS -> info.warDayWins();
            case DONATIONS -> info.totalDonations();
            case BATTLE_COUNT -> info.battleCount();
            default -> info.wins(); // WINS
        };

        if (submission.getClashRoyaleBaselineValue() == null) {
            submission.setClashRoyaleBaselineValue(current);
            questSubmissionRepository.save(submission);
            return; // первый опрос только фиксирует базу, ничего не засчитывает
        }
        int delta = current - submission.getClashRoyaleBaselineValue();
        submission.setClashRoyaleProgressCount(Math.max(0, delta));
        questSubmissionRepository.save(submission);
        if (delta >= quest.getClashRoyaleTargetCount()) {
            completeSubmission(submission);
        }
    }

    private void completeSubmission(QuestSubmission submission) {
        QuestSubmission approved = questService.approveSubmission(submission.getId());
        eventPublisher.publishEvent(new ClashRoyaleQuestAutoVerifiedEvent(this, approved.getId()));
    }

    private void sleepBetweenCalls() {
        try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
