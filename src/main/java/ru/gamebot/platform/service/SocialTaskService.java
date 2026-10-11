package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import ru.gamebot.platform.domain.repository.ExcTransactionRepository;
import ru.gamebot.platform.domain.repository.SocialTaskClaimRepository;

/** Задания «Соцсети» (подраздел в Квестах): разовая награда EXC за подписку на наши Telegram-каналы
 *  и ежедневная награда «👍 Я здесь» за нажатие кнопки под постом в канале.
 *  Подписку проверяет бот (getChatMember), здесь - список заданий и защищённая от повторов выплата.
 *  Откат без деплоя: app_settings 'social_tasks.enabled' = false (выключает всё, включая кнопку под постами). */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialTaskService {

    public static final String MAIN_CODE = "main";
    public static final long MAIN_REWARD_EXC = 100;
    public static final String PAYOUTS_CODE = "payouts";
    public static final long PAYOUTS_REWARD_EXC = 100;
    /** «👍 Я здесь» под постом в канале: раз в сутки на аккаунт. */
    public static final long PRESENCE_REWARD_EXC = 25;
    public static final long PRESENCE_DEFAULT_CAP_EXC = 5_000;
    private static final String ENABLED_KEY = "social_tasks.enabled";
    private static final String PRESENCE_CAP_KEY = "social_tasks.presence_cap_exc";
    private static final String PRESENCE_CODE_PREFIX = "here:";

    private final SocialTaskClaimRepository claimRepository;
    private final AppUserRepository appUserRepository;
    private final AppSettingRepository appSettingRepository;
    private final ExcTransactionRepository excTransactionRepository;
    private final ExcTransactionService excTx;
    private final AppProperties appProperties;

    /** chatId - то, что передаётся в getChatMember (id или @username канала, бот в нём админ); url - ссылка для игрока. */
    public record SocialTask(String code, String title, String description, String chatId, String url, long rewardExc) {}

    public enum ClaimStatus { OK, ALREADY_CLAIMED, DISABLED }

    public record ClaimResult(ClaimStatus status, long exc) {}

    public enum PresenceStatus { OK, ALREADY_TODAY, CAP_REACHED, DISABLED }

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

    /** Порядок = порядок в списке у игрока: основной канал первым. */
    public List<SocialTask> tasks() {
        List<SocialTask> list = new ArrayList<>();
        String mainChat = mainChatId();
        if (mainChat != null) {
            list.add(new SocialTask(
                    MAIN_CODE,
                    "📢 Основной канал клуба",
                    "Новости, новые квесты, розыгрыши и итоги недели — всё главное про клуб. Подпишись, чтобы ничего не пропускать.",
                    mainChat,
                    mainUrl(),
                    MAIN_REWARD_EXC));
        }
        String username = appProperties.getPayoutChannelUsername();
        if (username != null && !username.isBlank()) {
            String chatId = username.startsWith("@") ? username : "@" + username;
            list.add(new SocialTask(
                    PAYOUTS_CODE,
                    "📣 Канал выплат и отзывов",
                    "Здесь мы публикуем реальные выплаты игрокам и отзывы. Подпишись, чтобы видеть, что клуб платит.",
                    chatId,
                    "https://t.me/" + chatId.substring(1),
                    PAYOUTS_REWARD_EXC));
        }
        return list;
    }

    private String mainChatId() {
        String id = appProperties.getRequiredChannelId();
        if (id != null && !id.isBlank()) return id.trim();
        String username = appProperties.getRequiredChannelUsername();
        return username == null || username.isBlank() ? null : username.trim();
    }

    private String mainUrl() {
        String url = appProperties.getRequiredChannelUrl();
        if (url != null && !url.isBlank()) return url.trim();
        String username = appProperties.getRequiredChannelUsername();
        if (username != null && !username.isBlank()) return "https://t.me/" + username.replace("@", "").trim();
        return "https://t.me/exgamingclub";
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
        saveClaim(fresh, task.code(), task.rewardExc());
        excTx.creditExc(fresh, task.rewardExc(), ExcTransactionService.SOCIAL_TASK, "Соцсети: " + task.title());
        return new ClaimResult(ClaimStatus.OK, task.rewardExc());
    }

    // ── «👍 Я здесь»: ежедневная награда за нажатие кнопки под постом в канале ──

    private static String presenceCode(LocalDate day) {
        return PRESENCE_CODE_PREFIX + day;
    }

    public boolean presenceClaimedToday(AppUser user) {
        return claimRepository.existsByUserAndTaskCode(user, presenceCode(LocalDate.now()));
    }

    private long presenceCapExc() {
        try {
            return appSettingRepository.findById(PRESENCE_CAP_KEY)
                    .map(AppSetting::getValue)
                    .map(v -> Long.parseLong(v.trim()))
                    .orElse(PRESENCE_DEFAULT_CAP_EXC);
        } catch (Exception e) {
            return PRESENCE_DEFAULT_CAP_EXC;
        }
    }

    /** Награда за заход в канал: раз в сутки на аккаунт (отметка - запись с кодом «here:ДАТА»), суточный потолок выдачи по журналу операций.
     *  Членство в канале проверяет вызывающий код ДО вызова. Гонка двух одновременных нажатий разных игроков может превысить потолок на одну выплату - принято. */
    @Transactional
    public PresenceStatus claimPresence(AppUser user) {
        if (!enabled()) {
            return PresenceStatus.DISABLED;
        }
        LocalDate today = LocalDate.now();
        AppUser fresh = appUserRepository.findByIdForUpdate(user.getId()).orElse(user);
        String code = presenceCode(today);
        if (claimRepository.existsByUserAndTaskCode(fresh, code)) {
            return PresenceStatus.ALREADY_TODAY;
        }
        long issuedToday = excTransactionRepository.sumAmountByTypeSince(ExcTransactionService.CHANNEL_PRESENCE, today.atStartOfDay());
        if (issuedToday + PRESENCE_REWARD_EXC > presenceCapExc()) {
            return PresenceStatus.CAP_REACHED;
        }
        saveClaim(fresh, code, PRESENCE_REWARD_EXC);
        excTx.creditExc(fresh, PRESENCE_REWARD_EXC, ExcTransactionService.CHANNEL_PRESENCE, "Заход в канал («Я здесь»)");
        return PresenceStatus.OK;
    }

    private void saveClaim(AppUser user, String code, long rewardExc) {
        SocialTaskClaim claim = new SocialTaskClaim();
        claim.setUser(user);
        claim.setTaskCode(code);
        claim.setRewardExc(rewardExc);
        claim.setCreatedAt(LocalDateTime.now());
        claimRepository.saveAndFlush(claim);
    }
}
