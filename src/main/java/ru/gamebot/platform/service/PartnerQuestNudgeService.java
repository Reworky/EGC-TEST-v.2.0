package ru.gamebot.platform.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.PartnerQuestEventRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.PartnerQuestDigestEvent;
import ru.gamebot.platform.event.PartnerQuestNudgeEvent;

/** Напоминания по партнёрскому квесту банка, который ведёт менеджер (10.11.2026, владелец).
 *  Игроку (до трёх, только 10:00-22:00 по Москве, общий лимит уведомлений): 1) взял, но не нажал «Начать» - через 3 часа; 2) нажал «Начать», первая часть не проверена - через сутки после нажатия;
 *  3) последнее - через 3 суток после взятия. Окна узкие нарочно (бэклог): заявка, у которой окно уже прошло, напоминание не получит.
 *  Менеджерам: сводка в 11:00 по Москве (мгновенное уведомление при нажатии «Начать» - в боте). Флаги в app_settings: pq.nudge.player.enabled, pq.nudge.mgr.enabled (по умолчанию включены). */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerQuestNudgeService {

    public static final String PLAYER_KEY = "pq.nudge.player.enabled";
    public static final String MGR_KEY = "pq.nudge.mgr.enabled";
    private static final String DIGEST_LAST_KEY = "pq.digest.last";
    static final int BIT_NO_CLICK = 16;
    static final int BIT_ASK = 32;
    static final int BIT_LAST = 64;
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final int SEND_FROM_HOUR_MSK = 10;
    private static final int SEND_UNTIL_HOUR_MSK = 22;
    private static final int DIGEST_HOUR_MSK = 11;

    private final QuestSubmissionRepository submissionRepository;
    private final PartnerQuestEventRepository eventRepository;
    private final AppSettingRepository settingRepository;
    private final NotificationGateService notificationGate;
    private final ApplicationEventPublisher eventPublisher;

    public record Row(Long submissionId, String nickname, String username, String status, long days) { }

    public record Digest(int noClick, int clickedStale, int waitingBank, int bankDue, List<Row> rows) {
        public int total() { return noClick + clickedStale + waitingBank; }
    }

    public boolean playerEnabled() { return flag(PLAYER_KEY); }

    public boolean managerEnabled() { return flag(MGR_KEY); }

    public void setFlag(String key, boolean on) {
        AppSetting s = settingRepository.findById(key).orElseGet(AppSetting::new);
        s.setKey(key);
        s.setValue(on ? "true" : "false");
        settingRepository.save(s);
    }

    private boolean flag(String key) {
        return settingRepository.findById(key).map(AppSetting::getValue).map(v -> !"false".equalsIgnoreCase(v.trim())).orElse(true);
    }

    /** Заявка нового флоу (менеджер ведёт лично): игрок не проходил этапы в боте. */
    private static boolean isManagerFlow(QuestSubmission s) {
        return s.getMissionStep() <= 1 && s.getCardActivatedOn() == null && s.getPurchaseReportedOn() == null;
    }

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 240_000)
    public void sendNudges() {
        int hour = ZonedDateTime.now(MSK).getHour();
        if (hour < SEND_FROM_HOUR_MSK || hour >= SEND_UNTIL_HOUR_MSK || !playerEnabled()) return;
        LocalDateTime now = LocalDateTime.now();
        for (QuestSubmission s : submissionRepository.findPartnerMissionOpen(PartnerMissions.OTP.gameName(), now.minusDays(8))) {
            try {
                if (s.getStatus() != SubmissionStatus.DRAFT || !isManagerFlow(s) || s.getUser() == null || s.getUser().isBlocked() || s.getCreatedAt() == null) continue;
                int sent = s.getMissionRemindersSent();
                Duration age = Duration.between(s.getCreatedAt(), now);
                LocalDateTime click = eventRepository.findFirstClickAt(s.getUser().getTelegramId(), s.getCreatedAt());
                if ((sent & BIT_LAST) == 0 && age.toDays() >= 3 && age.toDays() <= 6) {
                    deliver(s, BIT_LAST, PartnerQuestNudgeEvent.KIND_LAST);
                } else if (click != null && (sent & BIT_ASK) == 0 && Duration.between(click, now).toHours() >= 24 && Duration.between(click, now).toDays() <= 3) {
                    deliver(s, BIT_ASK, PartnerQuestNudgeEvent.KIND_ASK);
                } else if (click == null && (sent & BIT_NO_CLICK) == 0 && age.toHours() >= 3 && age.toDays() <= 2) {
                    deliver(s, BIT_NO_CLICK, PartnerQuestNudgeEvent.KIND_NO_CLICK);
                }
            } catch (Exception e) {
                log.warn("Failed to process partner quest nudge for submission {}", s.getId(), e);
            }
        }
    }

    private void deliver(QuestSubmission s, int bit, int kind) {
        if (!notificationGate.tryAcquire(s.getUser(), NudgeType.OTP_MISSION)) return; // до отметки заявки: повтор на следующем тике
        s.setMissionRemindersSent(s.getMissionRemindersSent() | bit);
        submissionRepository.save(s);
        eventPublisher.publishEvent(new PartnerQuestNudgeEvent(this, s.getUser().getTelegramId(), s.getId(), kind));
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 300_000)
    public void sendDigest() {
        ZonedDateTime msk = ZonedDateTime.now(MSK);
        if (msk.getHour() != DIGEST_HOUR_MSK || !managerEnabled()) return;
        String today = msk.toLocalDate().toString();
        if (today.equals(settingRepository.findById(DIGEST_LAST_KEY).map(AppSetting::getValue).orElse(null))) return;
        try {
            Digest digest = buildDigest();
            AppSetting mark = settingRepository.findById(DIGEST_LAST_KEY).orElseGet(AppSetting::new);
            mark.setKey(DIGEST_LAST_KEY);
            mark.setValue(today);
            settingRepository.save(mark);
            if (digest.total() > 0) eventPublisher.publishEvent(new PartnerQuestDigestEvent(this, digest));
        } catch (Exception e) {
            log.warn("Failed to build partner quest digest", e);
        }
    }

    /** Считает сводку по заявкам последних 30 дней (используется и командой /partnernudge digest). */
    public Digest buildDigest() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        int noClick = 0, clickedStale = 0, waitingBank = 0, bankDue = 0;
        List<Row> rows = new ArrayList<>();
        PartnerMission pm = PartnerMissions.OTP;
        for (QuestSubmission s : submissionRepository.findPartnerMissionOpen(pm.gameName(), now.minusDays(30))) {
            if (!isManagerFlow(s) || s.getUser() == null || s.getUser().isBlocked() || s.getCreatedAt() == null) continue;
            long days = Math.max(0, Duration.between(s.getCreatedAt(), now).toDays());
            if (s.getStatus() == SubmissionStatus.DRAFT) {
                LocalDateTime click = eventRepository.findFirstClickAt(s.getUser().getTelegramId(), s.getCreatedAt());
                if (click == null) {
                    noClick++;
                    rows.add(new Row(s.getId(), s.getUser().getNickname(), s.getUser().getTelegramUsername(), "не открыл чат", days));
                } else if (Duration.between(click, now).toHours() >= 24) {
                    clickedStale++;
                    rows.add(new Row(s.getId(), s.getUser().getNickname(), s.getUser().getTelegramUsername(), "открыл чат, нет проверки", days));
                }
            } else if (s.getStatus() == SubmissionStatus.APPROVED && s.getBonusPaidAt() == null) {
                waitingBank++;
                LocalDate base = s.getUpdatedAt() != null ? s.getUpdatedAt().toLocalDate() : s.getCreatedAt().toLocalDate();
                if (!pm.bankDate(base).isAfter(today)) {
                    bankDue++;
                    rows.add(new Row(s.getId(), s.getUser().getNickname(), s.getUser().getTelegramUsername(), "банк мог выплатить", days));
                }
            }
        }
        rows.sort(Comparator.comparingLong(Row::days).reversed());
        return new Digest(noClick, clickedStale, waitingBank, bankDue, rows.size() > 10 ? new ArrayList<>(rows.subList(0, 10)) : rows);
    }
}
