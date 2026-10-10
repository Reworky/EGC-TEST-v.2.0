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
 * Отдельно от TrophyTournamentService (та же логика разделения, что и с TournamentService) —
 * квесты и турниры концептуально не связаны, только используют один и тот же BrawlStarsApiService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrawlQuestVerificationService {

    private static final long BATCH_DELAY_MS = 180; // тот же паттерн, что в TrophyTournamentService.runBatch
    private static final DateTimeFormatter BRAWL_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'");

    private final BrawlStarsApiService brawlStarsApiService;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppUserRepository appUserRepository;
    private final QuestService questService;
    private final ApplicationEventPublisher eventPublisher;
    private final QuestProgressNotifier questProgressNotifier;

    public record TagLookupResult(boolean success, String error, BrawlStarsApiService.PlayerInfo playerInfo) {}

    /** В отличие от TrophyTournamentService.lookupTag — без проверки "тег уже занят": тег для квестов переиспользуется свободно. */
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
        if (tag == null || (verifyType != BrawlVerifyType.NEW_BRAWLER && !verifyType.usesProfileBaseline())) {
            return;
        }
        try {
            if (verifyType == BrawlVerifyType.NEW_BRAWLER) {
                java.util.Set<String> current = brawlStarsApiService.fetchOwnedBrawlerNames(tag);
                if (!current.isEmpty()) {
                    setBaselineBrawlers(submissionId, String.join(",", current));
                }
            } else {
                brawlStarsApiService.fetchPlayer(tag).ifPresent(info -> setBaselineTrophies(submissionId, profileMetric(info, verifyType)));
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

    /** Точка входа шедулера. Не @Transactional — последовательные сетевые вызовы, как в TrophyTournamentService.runBatch. */
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
            case NEW_BRAWLER -> checkNewBrawler(submission, tag);
            case BATTLES, PARTNER_BATTLES -> checkBattles(submission, quest, tag);
            default -> checkProfileMetric(submission, quest, tag); // TROPHIES и квесты на прокачку
        }
    }

    /** Число из профиля, по приросту которого считается квест (все монотонные, кроме трофеев). */
    private static int profileMetric(BrawlStarsApiService.PlayerInfo info, BrawlVerifyType type) {
        return switch (type) {
            case BRAWLER_POWER -> info.powerSum();
            case BRAWLER_RANK -> info.rankSum();
            case UNLOCKS -> info.unlocks();
            case EXP_LEVEL -> info.expLevel();
            default -> info.trophies(); // TROPHIES
        };
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

    private void checkProfileMetric(QuestSubmission submission, Quest quest, String tag) throws BrawlStarsApiService.BrawlStarsTransientException {
        Optional<BrawlStarsApiService.PlayerInfo> info = brawlStarsApiService.fetchPlayer(tag);
        if (info.isEmpty()) return; // тег стал невалиден/переименован — пропускаем цикл, попробуем в следующий раз
        int current = profileMetric(info.get(), quest.getBrawlVerifyType());
        if (submission.getBrawlBaselineTrophies() == null) {
            submission.setBrawlBaselineTrophies(current);
            questSubmissionRepository.save(submission);
            return; // первый опрос только фиксирует базу, ничего не засчитывает
        }
        int delta = current - submission.getBrawlBaselineTrophies();
        submission.setBrawlProgressCount(Math.max(0, delta));
        questProgressNotifier.onProgress(submission, quest, submission.getBrawlProgressCount(), quest.getBrawlTargetCount());
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
        questProgressNotifier.onProgress(submission, quest, submission.getBrawlProgressCount(), quest.getBrawlTargetCount());
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

    /** Диагностика для админа («⚔️ Бои Brawl Stars» в карточке игрока, 2026-10-08): последние бои из battlelog и по каждому активному
     *  Brawl-квесту игрока - сколько боёв после взятия и сколько из них подходят под условия. Нужна, когда «бот видит бои, а прогресс 0»
     *  (тикет #274): без неё невозможно понять, не подходят ли бои под условия (поражения, не тот боец) или дело в данных. */
    public String diagnoseBattles(AppUser user) {
        String tag = user.getBrawlStarsTag();
        if (tag == null) return "❌ Тег Brawl Stars не привязан.";
        List<BrawlStarsApiService.BattleLogEntry> entries;
        try {
            entries = brawlStarsApiService.fetchBattleLog(tag);
        } catch (BrawlStarsApiService.BrawlStarsTransientException e) {
            return "⚠️ Сервис Brawl Stars сейчас недоступен: " + e.getMessage();
        }
        StringBuilder sb = new StringBuilder("⚔️ <b>Бои Brawl Stars</b> " + tag.replace("<", "&lt;") + "\n");
        if (entries.isEmpty()) {
            return sb.append("\nБоёв не найдено: история пуста, тег не найден API или игрок не распознан в боях.").toString();
        }
        sb.append("Время в UTC. Найдено боёв: ").append(entries.size()).append("\n\n");
        int shown = 0;
        for (BrawlStarsApiService.BattleLogEntry e : entries) {
            if (shown++ >= 12) break;
            String t = e.battleTime() != null && e.battleTime().length() >= 15
                    ? e.battleTime().substring(9, 11) + ":" + e.battleTime().substring(11, 13) + ":" + e.battleTime().substring(13, 15) : "?";
            sb.append(t).append(" · ").append(e.mode()).append(" (").append(e.type() == null ? "-" : e.type()).append(") · ")
              .append(e.victory() ? "✅ победа" : "❌ не победа").append(" · ")
              .append(e.playerBrawlerName() == null ? "?" : e.playerBrawlerName()).append("\n");
        }
        List<QuestSubmission> mine = questSubmissionRepository.findInProgressBrawlAutoVerify().stream()
                .filter(sub -> sub.getUser() != null && sub.getUser().getId().equals(user.getId())).toList();
        if (mine.isEmpty()) {
            sb.append("\nАктивных автоквестов Brawl Stars у игрока нет.");
            return sb.toString();
        }
        sb.append("\n<b>Активные квесты</b>\n");
        for (QuestSubmission sub : mine) {
            Quest quest = sub.getQuest();
            String since = formatBrawlTime(sub.getCreatedAt());
            long after = entries.stream().filter(x -> x.battleTime().compareTo(since) > 0).count();
            long matching = entries.stream().filter(x -> x.battleTime().compareTo(since) > 0 && matchesFilters(x, quest, sub)).count();
            sb.append("• ").append(quest.getTitle().replace("<", "&lt;")).append("\n  взят ").append(since).append(" · прогресс ")
              .append(sub.getBrawlProgressCount()).append("/").append(quest.getBrawlTargetCount())
              .append(" · боёв после взятия: ").append(after).append(", подходят: ").append(matching).append("\n");
        }
        sb.append("\nПодходят = победа и нужный боец/режим после момента взятия (так же считает бот).");
        return sb.toString();
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
