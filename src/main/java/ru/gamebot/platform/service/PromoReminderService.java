package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.PartnerQuestEvent;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.LeadgidConversionRepository;
import ru.gamebot.platform.domain.repository.PartnerQuestEventRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.Vpn2UserRepository;

/**
 * Напоминания о спонсорских квестах и VPN (тексты и расписание одобрены владельцем 28.11.2026). Раз в 30 минут, 10:00-21:00 по Москве; частоту держит PromoGateService.
 * Порядок важности: взял квест банка → взял игровой квест партнёра → смотрел и не взял → VPN. Второе напоминание о взятом квесте уходит на 4-7 день
 * (а не на 3-й): между напоминаниями минимум 72 часа. Бэклог: учитываются только действия после первого запуска (promo.start) и только внутри узких окон.
 * Прежние цепочки (ОТП, подписка на канал спонсора, VPN-бот) не меняются. Выключатель: app_settings promo.enabled = false.
 */
@Slf4j
@Service
public class PromoReminderService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final String ENABLED_KEY = "promo.enabled";
    private static final String START_KEY = "promo.start";
    private static final int MAX_PER_RUN = 100;

    private final PromoGateService gate;
    private final AppSettingRepository appSettingRepository;
    private final AppUserRepository appUserRepository;
    private final QuestSubmissionRepository submissionRepository;
    private final QuestRepository questRepository;
    private final PartnerQuestEventRepository eventRepository;
    private final LeadgidConversionRepository leadgidRepository;
    private final Vpn2UserRepository vpnUserRepository;
    private final Vpn2Service vpn2Service;
    private final Vpn2HealthService vpnHealth;
    private final QuestService questService;
    private final GamePlatformBot bot;

    public PromoReminderService(PromoGateService gate, AppSettingRepository appSettingRepository, AppUserRepository appUserRepository,
                                QuestSubmissionRepository submissionRepository, QuestRepository questRepository, PartnerQuestEventRepository eventRepository,
                                LeadgidConversionRepository leadgidRepository, Vpn2UserRepository vpnUserRepository, Vpn2Service vpn2Service,
                                Vpn2HealthService vpnHealth, QuestService questService, @Lazy GamePlatformBot bot) {
        this.gate = gate;
        this.appSettingRepository = appSettingRepository;
        this.appUserRepository = appUserRepository;
        this.submissionRepository = submissionRepository;
        this.questRepository = questRepository;
        this.eventRepository = eventRepository;
        this.leadgidRepository = leadgidRepository;
        this.vpnUserRepository = vpnUserRepository;
        this.vpn2Service = vpn2Service;
        this.vpnHealth = vpnHealth;
        this.questService = questService;
        this.bot = bot;
    }

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 600_000)
    public void run() {
        try {
            if ("false".equalsIgnoreCase(get(ENABLED_KEY))) return;
            LocalDateTime start = startedAt();
            int hour = ZonedDateTime.now(MSK).getHour();
            if (hour < 10 || hour >= 21) return;
            int[] budget = {MAX_PER_RUN};
            takenQuests(start, budget, true);
            takenQuests(start, budget, false);
            viewed(start, budget);
            vpn(start, budget);
        } catch (Exception e) {
            log.warn("Promo reminders run failed", e);
        }
    }

    private LocalDateTime startedAt() {
        String v = get(START_KEY);
        if (v != null) return LocalDateTime.parse(v);
        LocalDateTime now = LocalDateTime.now();
        put(START_KEY, now.toString());
        return now;
    }

    /** Взял квест банка (bank = true) или игрового партнёра: через сутки (окно 24-48 ч) и на 4-7 день. */
    private void takenQuests(LocalDateTime start, int[] budget, boolean bank) {
        LocalDateTime now = LocalDateTime.now();
        for (QuestSubmission s : submissionRepository.findAllByStatusAndCreatedAtBetween(SubmissionStatus.DRAFT, now.minusDays(7), now.minusHours(24))) {
            if (budget[0] <= 0) return;
            try {
                if (s.getCreatedAt().isBefore(start)) continue;
                long age = java.time.Duration.between(s.getCreatedAt(), now).toHours();
                int step = age < 48 ? 1 : age >= 96 ? 2 : 0;
                if (step == 0) continue;
                Quest q = s.getQuest();
                if (q == null || !q.isActive() || questService.isExpired(s)) continue;
                PartnerMission pm = PartnerMissions.forQuest(q);
                if (bank) {
                    // ОТП - своя цепочка; заявка, по которой банк уже ответил или игрок прошёл дальше первого шага, в напоминании не нуждается
                    if (pm == null || PartnerMissions.OTP.key().equals(pm.key()) || s.getMissionStep() > 1 || s.getCardActivatedOn() != null
                            || s.getPurchaseReportedOn() != null || leadgidRepository.existsBySubmissionId(s.getId())) continue;
                } else {
                    if (pm != null || !q.isExternalAutoApprove() || !"landing".equals(q.getExternalNetwork())
                            || hasGameStagePaid(s.getUser().getId(), q.getExternalOfferId())) continue;
                }
                String mark = "promo." + (bank ? "b" : "g") + step + "." + s.getId();
                if (get(mark) != null || !gate.canSend(s.getUser())) continue;
                if (!gate.record(s.getUser(), bank ? NudgeType.PROMO_BANK : NudgeType.PROMO_GAME)) continue;
                put(mark, now.toString()); // сначала отметка: при сбое отправки лучше пропустить напоминание, чем прислать дважды
                if (bank) bot.sendBankQuestReminder(s, pm, step);
                else bot.sendGameQuestReminder(s, step);
                budget[0]--;
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Promo reminder failed for submission {}", s.getId(), e);
            }
        }
    }

    private boolean hasGameStagePaid(Long userId, String offerId) {
        if ("sacralium".equals(offerId) && get("gamestage.sacralium.1." + userId) != null) return true;
        return "rodina".equals(offerId)
                && GameStageRewardService.RODINA_STAGES.keySet().stream().anyMatch(k -> get("gamestage.rodina." + k + "." + userId) != null);
    }

    /** Открыл карточку спонсорского квеста сутки назад и не взял: одно сообщение про последний квест, не чаще раза в 7 дней. */
    private void viewed(LocalDateTime start, int[] budget) {
        LocalDateTime now = LocalDateTime.now();
        Set<Long> seen = new HashSet<>();
        List<PartnerQuestEvent> events = eventRepository.findAllByTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
                PartnerQuestEvent.SPONSOR_VIEWED, now.minusHours(48), now.minusHours(24));
        for (PartnerQuestEvent e : events) {
            if (budget[0] <= 0) return;
            try {
                if (e.getQuestId() == null || e.getCreatedAt().isBefore(start) || !seen.add(e.getTelegramId())) continue;
                AppUser user = appUserRepository.findByTelegramId(e.getTelegramId()).orElse(null);
                if (user == null) continue;
                String last = get("promo.v." + user.getId());
                if (last != null && LocalDateTime.parse(last).isAfter(now.minusDays(7))) continue;
                Quest q = questRepository.findById(e.getQuestId()).orElse(null);
                if (q == null || !q.isActive() || q.isCouncilOnly() || q.getChannelCheckChatId() != null
                        || !(q.isSponsored() || q.isExternalAutoApprove())) continue;
                if (q.getParticipantLimit() != null && q.getParticipantLimit() > 0 && questService.countApprovedByQuest(q) >= q.getParticipantLimit()) continue;
                boolean tookSponsor = submissionRepository.findAllByUserOrderByCreatedAtDesc(user).stream().anyMatch(s -> s.getQuest() != null
                        && (s.getQuest().getId().equals(q.getId()) || ((s.getQuest().isSponsored() || s.getQuest().isExternalAutoApprove())
                        && (s.getStatus() == SubmissionStatus.DRAFT || s.getStatus() == SubmissionStatus.PENDING || s.getCreatedAt().isAfter(now.minusDays(7))))));
                if (tookSponsor || !gate.canSend(user) || !gate.record(user, NudgeType.PROMO_VIEWED)) continue;
                put("promo.v." + user.getId(), now.toString());
                bot.sendViewedQuestReminder(user, q);
                budget[0]--;
                Thread.sleep(60);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("Promo viewed reminder failed for {}", e.getTelegramId(), ex);
            }
        }
    }

    /** VPN: тем, кто ни разу не открывал VPN-бот и был активен за 7 дней: на 3-й и на 14-й день после регистрации, только при открытом и исправном VPN. */
    private void vpn(LocalDateTime start, int[] budget) {
        if (!vpn2Service.isEnabled() || !vpn2Service.isOpen() || !vpnHealthy()) return;
        LocalDateTime now = LocalDateTime.now();
        for (int[] window : new int[][] {{1, 3, 6}, {2, 14, 17}}) {
            LocalDateTime from = now.minusDays(window[2]);
            if (from.isBefore(start)) from = start;
            LocalDateTime to = now.minusDays(window[1]);
            if (!from.isBefore(to)) continue;
            for (AppUser user : appUserRepository.findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(from, to)) {
                if (budget[0] <= 0) return;
                try {
                    String mark = "promo.vpn" + window[0] + "." + user.getId();
                    if (get(mark) != null || !recentlyActive(user, now) || vpnUserRepository.findByTelegramId(user.getTelegramId()).isPresent()
                            || vpn2Service.find(user.getTelegramId()).isPresent()) continue;
                    if (!gate.canSend(user) || !gate.record(user, NudgeType.PROMO_VPN)) continue;
                    put(mark, now.toString());
                    bot.sendVpnReminder(user, window[0]);
                    budget[0]--;
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("Promo VPN reminder failed for {}", user.getTelegramId(), e);
                }
            }
        }
    }

    private static boolean recentlyActive(AppUser user, LocalDateTime now) {
        return (user.getLastBotActivityAt() != null && user.getLastBotActivityAt().isAfter(now.minusDays(7)))
                || (user.getLastActivityDate() != null && user.getLastActivityDate().isAfter(now.toLocalDate().minusDays(7)));
    }

    private boolean vpnHealthy() {
        Vpn2HealthService.Status s = vpnHealth.status();
        if (s.panelFailures() >= 2) return false;
        return !s.heartbeatExpected() || (s.ok() && System.currentTimeMillis() - s.lastBeatMs() <= 15 * 60_000L);
    }

    private String get(String key) {
        return appSettingRepository.findById(key).map(AppSetting::getValue).orElse(null);
    }

    private void put(String key, String value) {
        AppSetting s = appSettingRepository.findById(key).orElseGet(AppSetting::new);
        s.setKey(key);
        s.setValue(value);
        appSettingRepository.save(s);
    }
}
