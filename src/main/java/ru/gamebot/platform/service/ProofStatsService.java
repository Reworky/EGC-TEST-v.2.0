package ru.gamebot.platform.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.enums.RejectionReasonCode;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

/** Цифры для страницы «Как EGC проверяет результат» (egc-club.online/partners/proof) и экрана «🛡 Доказательства» в админке.
 *  Правило честности (ТЗ «страница доказательств»): только то, что воспроизводится запросом к базе; рядом с каждой долей размер выборки,
 *  при выборке меньше {@link #MIN_SAMPLE} доля не показывается. Период - последние {@link #WINDOW_DAYS} дней.
 *  Метрика «время до проверки» намеренно НЕ считается: у заявки нет даты сдачи на проверку, а createdAt - момент взятия квеста
 *  (в разность попало бы время самого игрока). Мультиаккаунты и платный/органический трафик тоже не считаются: учёта банов по периодам
 *  и признака источника в базе нет. Публичные значения округляются, персональных данных нет. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProofStatsService {

    public static final int WINDOW_DAYS = 30;
    public static final int MIN_SAMPLE = 30;
    private static final long CACHE_MS = 60L * 60 * 1000;
    private static final String SALT_KEY = "proof.salt";

    private final QuestSubmissionRepository submissionRepository;
    private final UserService userService;
    private final AppSettingRepository appSettingRepository;
    private final RetentionService retentionService;

    /** Полные цифры (для админа): размеры выборок и разбивка причин. Доли null, если выборка меньше MIN_SAMPLE. */
    public record Stats(LocalDateTime collectedAt, int windowDays,
                        long playersTotal, long activeWeek, long playersApproved,
                        long decided, long approved, long rejected,
                        long autoApproved, Integer autoApprovedPercent,
                        long manualDecided, long rejectedManual, Integer rejectedManualPercent,
                        Map<RejectionReasonCode, Long> rejectionReasons,
                        long duplicates, Integer duplicatePercent,
                        long retentionCohort1, Integer retention1Percent,
                        long retentionCohort7, Integer retention7Percent,
                        long retentionCohort30, Integer retention30Percent) {
        /** Одобрено / проверено (одобрено + отклонено) за период; null, если проверок меньше MIN_SAMPLE. Для витрины не годится: автоквесты не отклоняются,
         *  и доля почти всегда ~100%. */
        public Integer approvedPercent() {
            return decided >= MIN_SAMPLE ? (int) Math.round(approved * 100.0 / decided) : null;
        }
    }

    private volatile Stats cached;
    private volatile long cachedAt;

    /** Для публичного эндпоинта: пересчёт не чаще раза в час. */
    public Stats cachedStats() {
        long now = System.currentTimeMillis();
        Stats c = cached;
        if (c == null || now - cachedAt > CACHE_MS) {
            c = compute();
            cached = c;
            cachedAt = now;
        }
        return c;
    }

    public Stats compute() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(WINDOW_DAYS);
        List<QuestSubmission> decidedList = submissionRepository.findDecidedSince(since);

        long approved = 0, rejected = 0, autoApproved = 0, manualDecided = 0, rejectedManual = 0, duplicatesManual = 0;
        Set<Long> approvedUsers = new HashSet<>();
        Map<RejectionReasonCode, Long> reasons = new EnumMap<>(RejectionReasonCode.class);
        for (QuestSubmission s : decidedList) {
            boolean auto = isAutoVerified(s.getQuest());
            if (s.getStatus() == SubmissionStatus.APPROVED) {
                approved++;
                approvedUsers.add(s.getUser().getId());
                if (auto) autoApproved++;
            } else {
                rejected++;
                if (s.getRejectionReasonCode() != null) reasons.merge(s.getRejectionReasonCode(), 1L, Long::sum);
            }
            if (!auto) {
                manualDecided++;
                if (s.getStatus() == SubmissionStatus.REJECTED) rejectedManual++;
                if (s.isDuplicatePhotoDetected()) duplicatesManual++;
            }
        }
        // Дубли скриншотов считаем только среди проверенных вручную заявок: у автоквестов скриншотов нет, и в знаменателе они давали бы заниженную долю
        // Точный возврат на 1-й/7-й/30-й день из журнала активных дней (RetentionService; с 24.10.2026 вместо приближения «активен за 7 дней среди зарегистрированных 7-14 дн. назад»)
        RetentionService.PublicRates ret = retentionService.report().publicRates();

        return new Stats(now, WINDOW_DAYS,
                userService.totalRegisteredUsers(), userService.countActiveSince(LocalDate.now().minusDays(6)), approvedUsers.size(),
                approved + rejected, approved, rejected,
                autoApproved, percentOrNull(autoApproved, approved),
                manualDecided, rejectedManual, percentOrNull(rejectedManual, manualDecided),
                reasons,
                duplicatesManual, percentOrNull(duplicatesManual, manualDecided),
                ret.d1().n(), ret.d1().percent(),
                ret.d7().n(), ret.d7().percent(),
                ret.d30().n(), ret.d30().percent());
    }

    private static Integer percentOrNull(long part, long total) {
        return total >= MIN_SAMPLE ? (int) Math.round(part * 100.0 / total) : null;
    }

    /** Квест подтверждается без участия модератора: API игры, постбэк партнёрской сети или проверка подписки на канал. */
    static boolean isAutoVerified(Quest q) {
        return q != null && (q.getBrawlVerifyType() != null || q.getClashVerifyType() != null || q.getClashRoyaleVerifyType() != null
                || q.getDotaVerifyType() != null || q.getCs2VerifyType() != null || q.getPubgVerifyType() != null
                || q.isExternalAutoApprove() || q.getChannelCheckChatId() != null);
    }

    static String verificationMethod(Quest q) {
        if (q == null) return "screenshot";
        if (q.getBrawlVerifyType() != null || q.getClashVerifyType() != null || q.getClashRoyaleVerifyType() != null
                || q.getDotaVerifyType() != null || q.getCs2VerifyType() != null || q.getPubgVerifyType() != null) return "game_api";
        if (q.isExternalAutoApprove()) return "partner_postback";
        if (q.getChannelCheckChatId() != null) return "channel_check";
        return "screenshot";
    }

    // ── Обезличенная выгрузка для студий ────────────────────────────────────────────────

    /** Соль хранится только у нас (app_settings), создаётся при первой выгрузке: без неё по хэшу нельзя вернуться к Telegram ID. */
    private synchronized String salt() {
        return appSettingRepository.findById(SALT_KEY).map(AppSetting::getValue).filter(v -> !v.isBlank()).orElseGet(() -> {
            AppSetting s = new AppSetting();
            s.setKey(SALT_KEY);
            s.setValue(UUID.randomUUID().toString());
            appSettingRepository.save(s);
            return s.getValue();
        });
    }

    private String hashUser(String salt, Long telegramId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + ":" + telegramId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String csv(String v) {
        if (v == null) return "";
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    /** CSV по решённым заявкам за период: хэш игрока, время взятия, время решения, статус, игра, квест, способ проверки. Без тегов, ID и ников. */
    public String exportCsv(int days) {
        String salt = salt();
        StringBuilder sb = new StringBuilder("user_hash,taken_at,decided_at,status,game,quest,verification\n");
        for (QuestSubmission s : submissionRepository.findDecidedSince(LocalDateTime.now().minusDays(days))) {
            sb.append(hashUser(salt, s.getUser().getTelegramId())).append(',')
                    .append(s.getCreatedAt()).append(',').append(s.getUpdatedAt()).append(',')
                    .append(s.getStatus()).append(',')
                    .append(csv(s.getQuest().getGameName())).append(',').append(csv(s.getQuest().getTitle())).append(',')
                    .append(verificationMethod(s.getQuest())).append('\n');
        }
        return sb.toString();
    }
}
