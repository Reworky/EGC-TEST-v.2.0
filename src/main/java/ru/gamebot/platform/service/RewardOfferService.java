package ru.gamebot.platform.service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.QuestRewardedEvent;

/**
 * Предложения после наград (27.11.2026, порядок утверждён владельцем): по одному на новую награду и не чаще раза в сутки, каждое - один раз на игрока.
 * 1) спонсорские квесты - после первой игровой награды; 2) VPN; 3) UGC. Тем, кто уже брал спонсорский квест, пользуется VPN или снимал ролик, соответствующее не шлём.
 * Срабатывает только на новых одобрениях (планировщика нет, поэтому по накопленной базе залпа не будет). Отметки лежат в app_settings: offer.<вид>.<id игрока>.
 */
@Slf4j
@Service
public class RewardOfferService {

    private static final int DELAY_SECONDS = 90;
    private static final int GAP_HOURS = 24;

    private final AppSettingRepository appSettingRepository;
    private final AppUserRepository appUserRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final GamePlatformBot bot;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public RewardOfferService(AppSettingRepository appSettingRepository, AppUserRepository appUserRepository,
                              QuestSubmissionRepository questSubmissionRepository, @Lazy GamePlatformBot bot) {
        this.appSettingRepository = appSettingRepository;
        this.appUserRepository = appUserRepository;
        this.questSubmissionRepository = questSubmissionRepository;
        this.bot = bot;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQuestRewarded(QuestRewardedEvent event) {
        Long userId = event.getUserId();
        Long submissionId = event.getSubmissionId();
        executor.schedule(() -> {
            try {
                offer(userId, submissionId);
            } catch (Exception e) {
                log.warn("Reward offer failed for user {}", userId, e);
            }
        }, DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void offer(Long userId, Long submissionId) {
        AppUser user = appUserRepository.findById(userId).orElse(null);
        if (user == null || user.isBlocked()) return;
        List<QuestSubmission> subs = questSubmissionRepository.findAllByUserOrderByCreatedAtDesc(user);
        QuestSubmission trigger = subs.stream().filter(s -> s.getId().equals(submissionId)).findFirst().orElse(null);
        if (trigger == null || trigger.getStatus() != SubmissionStatus.APPROVED) return;
        String last = get("offer.last." + userId);
        if (last != null && LocalDateTime.parse(last).isAfter(LocalDateTime.now().minusHours(GAP_HOURS))) return;

        boolean firstGameReward = isGame(trigger.getQuest())
                && subs.stream().filter(s -> s.getStatus() == SubmissionStatus.APPROVED && isGame(s.getQuest())).count() == 1;
        if (get("offer.sp." + userId) == null && firstGameReward
                && subs.stream().noneMatch(s -> isSponsor(s.getQuest())) && bot.sendSponsorOffer(user)) {
            mark("sp", userId);
            return;
        }
        if (get("offer.vpn." + userId) == null && bot.sendVpnOffer(user)) {
            mark("vpn", userId);
            return;
        }
        if (get("offer.ugc." + userId) == null && subs.stream().noneMatch(s -> QuestService.isUgcQuest(s.getQuest())) && bot.sendUgcOffer(user)) {
            mark("ugc", userId);
        }
    }

    private static boolean isSponsor(Quest q) {
        return q != null && (q.isSponsored() || q.isExternalAutoApprove() || PartnerMissions.forQuest(q) != null);
    }

    private static boolean isGame(Quest q) {
        return q != null && !isSponsor(q) && q.getSponsorId() == null && !QuestService.isUgcQuest(q);
    }

    private String get(String key) {
        return appSettingRepository.findById(key).map(AppSetting::getValue).orElse(null);
    }

    private void mark(String kind, Long userId) {
        String now = LocalDateTime.now().toString();
        put("offer." + kind + "." + userId, now);
        put("offer.last." + userId, now);
    }

    private void put(String key, String value) {
        AppSetting s = appSettingRepository.findById(key).orElseGet(AppSetting::new);
        s.setKey(key);
        s.setValue(value);
        appSettingRepository.save(s);
    }
}
