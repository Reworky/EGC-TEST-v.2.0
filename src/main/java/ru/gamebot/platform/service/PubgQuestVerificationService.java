package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.PubgVerifyType;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.PubgQuestAutoVerifiedEvent;

/**
 * Автоматическая верификация квестов PUBG PC через официальный API (developer.pubg.com) вместо
 * ручного скриншота. По образцу Dota2QuestVerificationService (список матчей + разбор каждого нового
 * по отдельности — у PUBG нет стабильного career-counter'а в открытом API), но с ОДНИМ отличием:
 * здесь НАКОПЛЕНИЕ подходящих матчей до цели (pubgTargetCount), а не завершение по первому же
 * подходящему матчу — квест вида "выиграй 3 раза" требует именно 3 победы, не одну.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PubgQuestVerificationService {

    /** Свой, более жёсткий интервал, чем у Dota/CS2 (180мс) — у PUBG лимит именно 10 запросов/МИНУТУ
     *  на /players (не 10/сек и не единый со всеми остальными эндпоинтами), поэтому шаг поиска матчей
     *  игрока (единственный лимитированный вызов в этом сервисе) разносим по ~6.5с, чтобы гарантированно
     *  уложиться даже при плотной последовательности заявок в одном цикле. Разбор самих матчей (/matches/*)
     *  не лимitирован вообще (rate-limits.rst) — там задержка не нужна. */
    private static final long PLAYER_LOOKUP_DELAY_MS = 6_500;

    private final PubgApiService pubgApiService;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppUserRepository appUserRepository;
    private final QuestService questService;
    private final ApplicationEventPublisher eventPublisher;

    public record AccountLookupResult(boolean success, String error, String accountId, int matchesFound) {}

    /** Принимает игровой ник PUBG — используется только на шаге привязки; дальше опрос идёт по
     *  устойчивому accountId, возвращённому API, а не по нику (ник можно сменить). */
    public AccountLookupResult lookupAccount(String nickname) {
        if (!pubgApiService.isEnabled()) {
            return new AccountLookupResult(false, "Привязка аккаунта временно недоступна. Попробуйте позже.", null, 0);
        }
        String trimmed = nickname.trim();
        if (trimmed.isBlank()) {
            return new AccountLookupResult(false, "Введите игровой ник PUBG.", null, 0);
        }
        try {
            Optional<PubgApiService.PlayerLookupResult> result = pubgApiService.lookupPlayer(trimmed);
            if (result.isEmpty()) {
                return new AccountLookupResult(false,
                        "Игрок с таким ником не найден на платформе Steam. Проверьте написание ника (регистр важен).",
                        null, 0);
            }
            return new AccountLookupResult(true, null, result.get().accountId(), result.get().matchIds().size());
        } catch (PubgApiService.PubgTransientException e) {
            log.warn("PUBG account lookup transient failure for nickname={}", trimmed, e);
            return new AccountLookupResult(false, "Сервис PUBG временно недоступен. Попробуйте ещё раз чуть позже.", null, 0);
        }
    }

    @Transactional
    public void linkAccount(AppUser user, String accountId) {
        user.setPubgAccountId(accountId);
        user.setPubgLinkedAt(LocalDateTime.now());
        appUserRepository.save(user);
    }

    /** Точка входа шедулера. Не @Transactional — последовательные сетевые вызовы, как у Dota/CS2. */
    public void checkInProgressSubmissions() {
        List<QuestSubmission> pending = questSubmissionRepository.findInProgressPubgAutoVerify();
        for (QuestSubmission submission : pending) {
            try {
                checkOne(submission);
            } catch (Exception e) {
                log.warn("PUBG auto-verify check failed for submission {}", submission.getId(), e);
            }
            sleepBetweenPlayerLookups();
        }
    }

    private void checkOne(QuestSubmission submission) throws PubgApiService.PubgTransientException {
        Quest quest = submission.getQuest();
        String accountId = submission.getUser().getPubgAccountId();
        // null = ещё не опрашивался — первый опрос учитывает всё, что сыграно ПОСЛЕ взятия квеста.
        LocalDateTime sinceTime = submission.getPubgLastProcessedMatchTime() != null
                ? submission.getPubgLastProcessedMatchTime() : submission.getCreatedAt();

        Optional<PubgApiService.PlayerLookupResult> lookup = pubgApiService.fetchRecentMatches(accountId);
        if (lookup.isEmpty()) return;
        List<String> matchIds = lookup.get().matchIds();
        if (matchIds.isEmpty()) return;

        // Порядок matchIds в ответе API не гарантирован — разбираем все матчи из списка и фильтруем
        // по времени, а не полагаемся на позицию в списке (в отличие от Dota, где match_id монотонен).
        int progress = submission.getPubgProgressCount();
        LocalDateTime newCursor = sinceTime;
        boolean completed = false;
        for (String matchId : matchIds) {
            Optional<PubgApiService.MatchResult> details;
            try {
                details = pubgApiService.fetchMatch(matchId, accountId);
            } catch (PubgApiService.PubgTransientException e) {
                // Известный риск: временная недоступность после патча/обслуживания — не двигаем курсор
                // мимо этого матча, следующий цикл поллера (через 10 минут) попробует снова.
                log.warn("PUBG fetchMatch failed for matchId={}, will retry next cycle", matchId, e);
                continue;
            }
            if (details.isEmpty() || details.get().createdAt() == null) continue;
            if (!details.get().createdAt().isAfter(sinceTime)) continue; // старый матч, уже учтён/до взятия квеста

            if (details.get().createdAt().isAfter(newCursor)) {
                newCursor = details.get().createdAt();
            }
            boolean qualifies = quest.getPubgVerifyType() == PubgVerifyType.WINS
                    ? details.get().winPlace() == 1
                    : true; // MATCHES_PLAYED — любой новый матч засчитывается
            if (qualifies) {
                progress++;
            }
        }

        submission.setPubgProgressCount(progress);
        submission.setPubgLastProcessedMatchTime(newCursor);
        if (progress >= quest.getPubgTargetCount()) {
            completed = true;
        }
        questSubmissionRepository.save(submission);

        if (completed) {
            completeSubmission(submission);
        }
    }

    private void completeSubmission(QuestSubmission submission) {
        QuestSubmission approved = questService.approveSubmission(submission.getId());
        eventPublisher.publishEvent(new PubgQuestAutoVerifiedEvent(this, approved.getId()));
    }

    private void sleepBetweenPlayerLookups() {
        try { Thread.sleep(PLAYER_LOOKUP_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
