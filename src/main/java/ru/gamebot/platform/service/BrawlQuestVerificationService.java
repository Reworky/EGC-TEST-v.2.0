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

    /** Вызывается сразу после успешного взятия квеста NEW_BRAWLER/TROPHIES (бот и Mini App) — фиксирует
     *  базу для сравнения СРАЗУ, а не на первой отложенной проверке шедулера (окно ~2 мин, см.
     *  checkInProgressSubmissions/@Scheduled в WeeklyResetScheduler). Без этого, если игрок открывает
     *  нового бойца или поднимает трофеи внутри этого окна ДО первого опроса, они попадают в базу как
     *  "уже были" и квест никогда не засчитывается (инцидент 2026-09-19: игрок получил Гаса сразу после
     *  взятия квеста "Получи любого нового бойца", прогресс не засчитался). Не @Transactional — сетевой
     *  вызов, тот же паттерн, что и checkInProgressSubmissions; ошибка тут не страшна, первая отложенная
     *  проверка всё равно зафиксирует базу как раньше — просто окно гонки для этой попытки не закроется. */
    public void primeBaseline(Long submissionId, BrawlVerifyType verifyType, String tag) {
        if (tag == null || (verifyType != BrawlVerifyType.NEW_BRAWLER && verifyType != BrawlVerifyType.TROPHIES)) {
            return;
        }
        try {
            if (verifyType == BrawlVerifyType.NEW_BRAWLER) {
                java.util.Set<String> current = brawlStarsApiService.fetchOwnedBrawlerNames(tag);
                if (!current.isEmpty()) {
                    setBaselineBrawlers(submissionId, String.join(",", current));
                }
            } else {
                brawlStarsApiService.fetchPlayer(tag).ifPresent(info -> setBaselineTrophies(submissionId, info.trophies()));
            }
        } catch (BrawlStarsApiService.BrawlStarsTransientException e) {
            log.warn("Baseline priming failed for submission {}", submissionId, e);
        }
    }

    private void setBaselineBrawlers(Long submissionId, String csv) {
        questSubmissionRepository.findById(submissionId).ifPresent(s -> {
            if (s.getBrawlBaselineBrawlers() == null) {
                s.setBrawlBaselineBrawlers(csv);
                questSubmissionRepository.save(s);
            }
        });
    }

    private void setBaselineTrophies(Long submissionId, int trophies) {
        questSubmissionRepository.findById(submissionId).ifPresent(s -> {
            if (s.getBrawlBaselineTrophies() == null) {
                s.setBrawlBaselineTrophies(trophies);
                questSubmissionRepository.save(s);
            }
        });
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
        switch (quest.getBrawlVerifyType()) {
            case TROPHIES -> checkTrophies(submission, quest, tag);
            case NEW_BRAWLER -> checkNewBrawler(submission, tag);
            default -> checkBattles(submission, quest, tag);
        }
    }

    /** Засчитывается, когда в списке бойцов игрока появляется имя, которого не было на момент первого опроса —
     * API не показывает, ЧЕРЕЗ ЧТО именно получен боец (ивент/Trophy Road/Starr Drop/покупка), поэтому
     * проверяем сам факт появления нового бойца в коллекции, а не конкретный способ его получения. */
    private void checkNewBrawler(QuestSubmission submission, String tag) throws BrawlStarsApiService.BrawlStarsTransientException {
        java.util.Set<String> current = brawlStarsApiService.fetchOwnedBrawlerNames(tag);
        if (current.isEmpty()) return; // тег невалиден или временная ошибка API — пропускаем цикл
        if (submission.getBrawlBaselineBrawlers() == null) {
            submission.setBrawlBaselineBrawlers(String.join(",", current));
            questSubmissionRepository.save(submission);
            return; // первый опрос только фиксирует базу, ничего не засчитывает
        }
        java.util.Set<String> baseline = java.util.Set.of(submission.getBrawlBaselineBrawlers().split(","));
        boolean hasNew = current.stream().anyMatch(name -> !baseline.contains(name));
        if (hasNew) {
            completeSubmission(submission);
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
            if (e.battleTime().compareTo(oldCursor) > 0 && matchesFilters(e, quest, submission)) {
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

    private boolean matchesFilters(BrawlStarsApiService.BattleLogEntry e, Quest quest, QuestSubmission submission) {
        if (quest.isBrawlRequireVictory() && !e.victory()) return false;
        // Официальный API отдаёт "teamRanked" (командный формат) или "soloRanked" (сольный) для
        // рангового режима — литерала "ranked" не существует вообще (проверено через официальные
        // данные/источники после инцидента 2026-09-19: requireRanked не срабатывал НИ РАЗУ ни у
        // одного игрока с момента создания этого фильтра, квест "Выиграй бой 5 раз в ранговом режиме"
        // был сломан полностью). requireTeam — отдельный флаг, ограничивающий именно командный формат.
        if (quest.isBrawlRequireRanked() && !("teamRanked".equalsIgnoreCase(e.type()) || "soloRanked".equalsIgnoreCase(e.type()))) return false;
        if (quest.isBrawlRequireTeam() && !e.isTeamMode()) return false;
        if (quest.getBrawlModeKeys() != null && !csvContains(quest.getBrawlModeKeys(), e.mode())) return false;
        if (quest.getBrawlBrawlerNames() != null && !csvContains(quest.getBrawlBrawlerNames(), e.playerBrawlerName())) return false;
        // PARTNER_BATTLES: бой засчитывается, только если выбранный при взятии партнёр реально был
        // тиммейтом в этом бою — без этого условия квест ничем не отличался бы от обычного "команда".
        if (quest.getBrawlVerifyType() == BrawlVerifyType.PARTNER_BATTLES) {
            String partnerTag = submission.getBrawlPartnerTag();
            if (partnerTag == null) return false;
            boolean partnerPresent = e.teammateTags().stream().anyMatch(t -> t.equalsIgnoreCase(partnerTag));
            if (!partnerPresent) return false;
        }
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
