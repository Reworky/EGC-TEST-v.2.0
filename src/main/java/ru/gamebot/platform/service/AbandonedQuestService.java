package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

/** Автозакрытие брошенных игровых квестов (решение владельца 2026-10-15): заявка в статусе «взят» (DRAFT) или «нужна информация» (NEEDS_INFO),
 *  по которой игрок не заходил в бота и ничего не менялось {@link #INACTIVE_DAYS} дней, а срок квеста ещё не истёк, закрывается как «отменён».
 *  НЕ трогаем: «на проверке» (игрок уже отправил отчёт и ждёт модератора), спонсорские/UGC/внешние квесты, автопроверяемые квесты с ненулевым
 *  прогрессом (игрок мог играть, не заходя в бота). Строки остаются (CANCELLED + autoClosedAt), игрокам ничего не отправляем.
 *  Автозадача выключена по умолчанию: включается в админке, первый запуск - вручную по кнопке после просмотра списка. */
@Service
@RequiredArgsConstructor
public class AbandonedQuestService {

    public static final int INACTIVE_DAYS = 14;
    public static final String ENABLED_KEY = "quest.autoclose.enabled";
    /** Защита от залпа: за один запуск закрываем не больше стольких заявок, остальное - на следующий. */
    private static final int MAX_PER_RUN = 500;

    private final QuestSubmissionRepository submissionRepository;
    private final AppSettingRepository appSettingRepository;

    public boolean isEnabled() {
        return appSettingRepository.findById(ENABLED_KEY)
                .map(AppSetting::getValue)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    /** Кого закрыли бы прямо сейчас (ничего не меняет). Самые старые первыми. */
    @Transactional(readOnly = true)
    public List<QuestSubmission> findCandidates() {
        LocalDateTime before = LocalDateTime.now().minusDays(INACTIVE_DAYS);
        LocalDate activeBefore = LocalDate.now().minusDays(INACTIVE_DAYS);
        return submissionRepository.findAbandonedCandidates(before, activeBefore).stream()
                .filter(s -> isGameQuest(s.getQuest()))
                .filter(s -> !(isAutoVerified(s.getQuest()) && progress(s) > 0))
                .toList();
    }

    /** Закрывает подходящие заявки, возвращает закрытые. */
    @Transactional
    public List<QuestSubmission> closeAbandoned() {
        List<QuestSubmission> targets = findCandidates();
        if (targets.size() > MAX_PER_RUN) targets = targets.subList(0, MAX_PER_RUN);
        LocalDateTime now = LocalDateTime.now();
        for (QuestSubmission s : targets) {
            s.setStatus(SubmissionStatus.CANCELLED);
            s.setUpdatedAt(now);
            s.setAutoClosedAt(now);
        }
        submissionRepository.saveAll(targets);
        return targets;
    }

    /** Игровой квест = как в разделе «Игровые квесты» (QuestService.findActiveGameNames): не спонсорский, не внешний, не UGC. */
    private static boolean isGameQuest(Quest q) {
        return q != null && !q.isSponsored() && !q.isExternalAutoApprove() && q.getSponsorId() == null
                && q.getGameName() != null && !q.getGameName().isBlank() && !"UGC".equalsIgnoreCase(q.getGameName());
    }

    private static boolean isAutoVerified(Quest q) {
        return q.getBrawlVerifyType() != null || q.getClashVerifyType() != null || q.getClashRoyaleVerifyType() != null
                || q.getDotaVerifyType() != null || q.getCs2VerifyType() != null || q.getPubgVerifyType() != null;
    }

    private static int progress(QuestSubmission s) {
        return Math.max(Math.max(s.getBrawlProgressCount(), s.getClashProgressCount()),
                Math.max(s.getClashRoyaleProgressCount(), Math.max(s.getCs2ProgressCount(), s.getPubgProgressCount())));
    }
}
