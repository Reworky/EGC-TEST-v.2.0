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
            // DONATIONS/DEFENSE_WINS: НЕ top-level donations/defenseWins - это счётчики текущего сезона, они обнуляются, и дельта от
            // базы, снятой посреди сезона, после сброса уходила в минус (прогресс навсегда 0). Проба 2026-09-25: donations=0 при
            // ачивке "Friend in Need"=130, defenseWins=0 при "Unbreakable"=10. Ачивки накопительные, как "Conqueror" выше.
            case DONATIONS -> info.achievement(ClashOfClansApiService.ACH_DONATIONS);
            case DEFENSE_WINS -> info.achievement(ClashOfClansApiService.ACH_DEFENSES);
            case EXP_LEVEL -> info.expLevel();
            case BUILDER_TROPHIES -> info.builderBaseTrophies();
            case ACHIEVEMENT -> info.achievement(quest.getClashAchievementName());
            case HERO_LEVELS -> info.heroLevels();
            case TROOP_LEVELS -> info.troopLevels();
            case BUILDER_HALL -> info.builderHallLevel();
            // ATTACK_WINS: НЕ top-level attackWins — тот обнуляется по сезону/новому режиму (2026-09-22,
            // тикет #213, см. javadoc PlayerInfo.multiplayerWins). Ачивка "Conqueror" не сбрасывается.
            default -> info.multiplayerWins();
        };
        // Для DONATIONS/DEFENSE_WINS база у заявок, взятых ДО перехода на ачивки, снята по старому (сезонному) счётчику и
        // несравнима с новым значением - без замены первый опрос дал бы фиктивную дельту в тысячи и мгновенно одобрил квест.
        // Признак базы «по-новому» - clashBaselineValue2 = 1 (для этих типов оно больше нигде не используется; RESOURCES
        // держит там базу эликсира, но идёт отдельной веткой выше). Старую базу молча переснимаем: прогресс до деплоя был
        // нулевым из-за самой ошибки.
        boolean needsLifetimeMarker = quest.getClashVerifyType() == ClashVerifyType.DONATIONS
                || quest.getClashVerifyType() == ClashVerifyType.DEFENSE_WINS;
        checkSingleValue(submission, quest, current, needsLifetimeMarker);
    }

    private void checkSingleValue(QuestSubmission submission, Quest quest, int current, boolean needsLifetimeMarker) {
        if (submission.getClashBaselineValue() == null || (needsLifetimeMarker && submission.getClashBaselineValue2() == null)) {
            submission.setClashBaselineValue(current);
            if (needsLifetimeMarker) submission.setClashBaselineValue2(1);
            submission.setClashProgressCount(0);
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
