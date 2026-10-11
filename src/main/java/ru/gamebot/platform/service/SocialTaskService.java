package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.SocialTaskClaim;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.SocialTaskClaimRepository;

/** Задания «Соцсети» (подраздел в Квестах): разовая награда EXC за подписку на наш Telegram-канал.
 *  Подписку проверяет бот (getChatMember), здесь - список заданий и защищённая от повторов выплата.
 *  Откат без деплоя: app_settings 'social_tasks.enabled' = false. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialTaskService {

    public static final String PAYOUTS_CODE = "payouts";
    public static final long PAYOUTS_REWARD_EXC = 100;
    private static final String ENABLED_KEY = "social_tasks.enabled";

    private final SocialTaskClaimRepository claimRepository;
    private final AppUserRepository appUserRepository;
    private final AppSettingRepository appSettingRepository;
    private final ExcTransactionService excTx;
    private final AppProperties appProperties;

    /** chatId - то, что передаётся в getChatMember (@username канала, бот в нём админ); url - ссылка для игрока. */
    public record SocialTask(String code, String title, String description, String chatId, String url, long rewardExc) {}

    public enum ClaimStatus { OK, ALREADY_CLAIMED, DISABLED }

    public record ClaimResult(ClaimStatus status, long exc) {}

    public boolean enabled() {
        try {
            return appSettingRepository.findById(ENABLED_KEY)
                    .map(AppSetting::getValue)
                    .map(v -> !"false".equalsIgnoreCase(v.trim()))
                    .orElse(true);
        } catch (Exception e) {
            log.warn("Failed to read setting {}", ENABLED_KEY, e);
            return true;
        }
    }

    public List<SocialTask> tasks() {
        String username = appProperties.getPayoutChannelUsername();
        if (username == null || username.isBlank()) {
            return List.of();
        }
        String chatId = username.startsWith("@") ? username : "@" + username;
        return List.of(new SocialTask(
                PAYOUTS_CODE,
                "📣 Канал выплат и отзывов",
                "Здесь мы публикуем реальные выплаты игрокам и отзывы. Подпишись, чтобы видеть, что клуб платит.",
                chatId,
                "https://t.me/" + chatId.substring(1),
                PAYOUTS_REWARD_EXC));
    }

    public Optional<SocialTask> find(String code) {
        return tasks().stream().filter(t -> t.code().equals(code)).findFirst();
    }

    public Set<String> claimedCodes(AppUser user) {
        return Set.copyOf(claimRepository.findTaskCodesByUser(user));
    }

    public boolean isClaimed(AppUser user, SocialTask task) {
        return claimRepository.existsByUserAndTaskCode(user, task.code());
    }

    /** Зачисляет награду, если её ещё не было. Подписку проверяет вызывающий код ДО вызова (запрос в Telegram - вне транзакции).
     *  Строка игрока блокируется на время транзакции, поэтому две одновременные «Я подписался» не заплатят дважды;
     *  уникальный ключ (user_id, task_code) - вторая линия защиты. */
    @Transactional
    public ClaimResult claim(AppUser user, SocialTask task) {
        if (!enabled()) {
            return new ClaimResult(ClaimStatus.DISABLED, 0);
        }
        AppUser fresh = appUserRepository.findByIdForUpdate(user.getId()).orElse(user);
        if (claimRepository.existsByUserAndTaskCode(fresh, task.code())) {
            return new ClaimResult(ClaimStatus.ALREADY_CLAIMED, 0);
        }
        SocialTaskClaim claim = new SocialTaskClaim();
        claim.setUser(fresh);
        claim.setTaskCode(task.code());
        claim.setRewardExc(task.rewardExc());
        claim.setCreatedAt(LocalDateTime.now());
        claimRepository.saveAndFlush(claim);
        excTx.creditExc(fresh, task.rewardExc(), ExcTransactionService.SOCIAL_TASK, "Соцсети: " + task.title());
        return new ClaimResult(ClaimStatus.OK, task.rewardExc());
    }
}
