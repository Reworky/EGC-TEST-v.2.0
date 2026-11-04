package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.Vpn2Account;
import ru.gamebot.platform.domain.model.Vpn2Payment;
import ru.gamebot.platform.domain.model.Vpn2User;
import ru.gamebot.platform.domain.repository.Vpn2AccountRepository;
import ru.gamebot.platform.domain.repository.Vpn2PaymentRepository;
import ru.gamebot.platform.domain.repository.Vpn2UserRepository;
import ru.gamebot.platform.event.AnalyticsAlertEvent;
import ru.gamebot.platform.service.VpnPanelClient.ClientSpec;
import ru.gamebot.platform.service.VpnPanelClient.PanelException;
import ru.gamebot.platform.service.VpnService.VpnException;

/** Бэкенд отдельного бота EGC VPN: тарифы, ключи во ВТОРОЙ панели 3x-ui (настройки VPN2_*), рефералка. Пилот в основном боте (VpnService, vpn_accounts)
 *  не затрагивается: у этого сервиса свой клиент панели, свои таблицы vpn2_*. Платёжный код (activatePaid) при сбое панели не теряет деньги молча:
 *  аккаунт остаётся в PENDING, админам уходит алерт, повторный вызов безопасен. */
@Slf4j
@Service
public class Vpn2Service {

    /** Тарифы: цена в рублях и в Stars (курс как у месячного: 150 ₽ = 89 ⭐), со скидкой за EXC (−50 ₽) - отдельная цена. */
    public enum Tariff {
        M1("M1", "1 месяц", 30, 150, 89, 100, 59),
        M3("M3", "3 месяца", 90, 400, 237, 350, 208),
        M12("M12", "12 месяцев", 365, 1350, 801, 1300, 771);

        private final String key;
        private final String title;
        private final int days;
        private final int priceRub;
        private final int stars;
        private final int excPriceRub;
        private final int excStars;

        Tariff(String key, String title, int days, int priceRub, int stars, int excPriceRub, int excStars) {
            this.key = key;
            this.title = title;
            this.days = days;
            this.priceRub = priceRub;
            this.stars = stars;
            this.excPriceRub = excPriceRub;
            this.excStars = excStars;
        }

        public String getKey() { return key; }

        public String getTitle() { return title; }

        public int getDays() { return days; }

        public int getPriceRub() { return priceRub; }

        public int getStars() { return stars; }

        public int getExcPriceRub() { return excPriceRub; }

        public int getExcStars() { return excStars; }

        public static Optional<Tariff> byKey(String key) {
            return Arrays.stream(values()).filter(t -> t.key.equalsIgnoreCase(key)).findFirst();
        }
    }

    /** Скидка за EXC: столько EXC списывается за −50 ₽ на любой срок. */
    public static final long EXC_DISCOUNT = 18_000L;
    public static final int REFERRAL_BONUS_DAYS = 7;

    private static final long GB = 1024L * 1024 * 1024;
    private static final int TRIAL_LIMIT_IP = 1;
    private static final int PAID_LIMIT_IP = 2;
    private static final String PANEL_COMMENT = "EGC-BOT";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VpnPanelClient panel;
    private final Vpn2AccountRepository accounts;
    private final Vpn2UserRepository users;
    private final Vpn2PaymentRepository payments;
    private final ApplicationEventPublisher eventPublisher;
    private final boolean enabled;
    private final boolean open;
    private final Set<Long> testIds;
    private final String botToken;
    private final String botUsername;
    private final String subBase;
    private final int trialDays;
    private final int trialGb;

    public Vpn2Service(ObjectMapper objectMapper, Vpn2AccountRepository accounts, Vpn2UserRepository users,
                       Vpn2PaymentRepository payments, ApplicationEventPublisher eventPublisher,
                       @Value("${app.vpn2-enabled:false}") boolean enabled,
                       @Value("${app.vpn2-open:false}") boolean open,
                       @Value("${app.vpn2-test-ids:}") String testIds,
                       @Value("${app.vpn2-bot-token:}") String botToken,
                       @Value("${app.vpn2-bot-username:}") String botUsername,
                       @Value("${app.vpn2-xui-url:}") String xuiUrl,
                       @Value("${app.vpn2-xui-user:}") String xuiUser,
                       @Value("${app.vpn2-xui-pass:}") String xuiPass,
                       @Value("${app.vpn2-xui-inbound-id:2}") int inboundId,
                       @Value("${app.vpn2-sub-base:}") String subBase,
                       @Value("${app.vpn2-trial-days:3}") int trialDays,
                       @Value("${app.vpn2-trial-gb:5}") int trialGb) {
        this.panel = new VpnPanelClient(objectMapper, xuiUrl, xuiUser, xuiPass, inboundId);
        this.accounts = accounts;
        this.users = users;
        this.payments = payments;
        this.eventPublisher = eventPublisher;
        this.enabled = enabled;
        this.open = open;
        this.testIds = parseIds(testIds);
        this.botToken = botToken == null ? "" : botToken.trim();
        this.botUsername = botUsername == null ? "" : botUsername.trim().replaceFirst("^@", "");
        this.subBase = subBase == null ? "" : subBase.trim().replaceAll("/+$", "");
        this.trialDays = trialDays;
        this.trialGb = trialGb;
        if (!enabled) {
            log.warn("Vpn2Service disabled: VPN2_ENABLED is not true");
        } else if (!panel.isConfigured() || this.subBase.isBlank()) {
            log.warn("Vpn2Service disabled: VPN2_XUI_URL/VPN2_XUI_USER/VPN2_XUI_PASS/VPN2_SUB_BASE not set");
        }
    }

    private static Set<Long> parseIds(String raw) {
        Set<Long> ids = new HashSet<>();
        if (raw == null) {
            return ids;
        }
        for (String part : raw.split("[,\\s]+")) {
            if (!part.isBlank()) {
                try {
                    ids.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException e) {
                    log.warn("Vpn2Service: bad id in VPN2_TEST_IDS: {}", part);
                }
            }
        }
        return ids;
    }

    public boolean isEnabled() {
        return enabled && panel.isConfigured() && !subBase.isBlank();
    }

    /** Открыт ли бот для всех; иначе только админы и VPN2_TEST_IDS. */
    public boolean isOpen() { return open; }

    public boolean isTester(Long telegramId) { return testIds.contains(telegramId); }

    public String getBotToken() { return botToken; }

    public String getBotUsername() { return botUsername; }

    public int getTrialDays() { return trialDays; }

    public int getTrialGb() { return trialGb; }

    public Optional<Vpn2Account> find(Long telegramId) {
        return accounts.findByTelegramId(telegramId);
    }

    /** Ключ работает сейчас: выдан и срок не истёк. */
    public boolean isRunning(Vpn2Account account) {
        return Vpn2Account.STATUS_ACTIVE.equals(account.getStatus())
                && account.getExpiresAt() != null && account.getExpiresAt().isAfter(LocalDateTime.now());
    }

    /** Можно ли ещё взять пробный: аккаунта нет, или он завис в PENDING на пробном (прошлая выдача оборвалась). */
    public boolean isTrialAvailable(Optional<Vpn2Account> found) {
        return found.isEmpty() || (Vpn2Account.STATUS_PENDING.equals(found.get().getStatus())
                && Vpn2Account.PLAN_TRIAL.equals(found.get().getPlanType()));
    }

    public String subscriptionUrl(Vpn2Account account) {
        return subBase + "/" + account.getSubId();
    }

    /** Запись пользователя при /start; реферер учитывается только при первом заходе и только если он сам уже пользователь бота. */
    public synchronized Vpn2User registerUser(Long telegramId, String firstName, String username, Long referredBy) {
        Optional<Vpn2User> found = users.findByTelegramId(telegramId);
        if (found.isPresent()) {
            return found.get();
        }
        Vpn2User u = new Vpn2User();
        u.setTelegramId(telegramId);
        u.setFirstName(cut(firstName, 128));
        u.setUsername(cut(username, 64));
        u.setCreatedAt(LocalDateTime.now());
        if (referredBy != null && !referredBy.equals(telegramId) && users.findByTelegramId(referredBy).isPresent()) {
            u.setReferredBy(referredBy);
        }
        return users.save(u);
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Пробный период: один раз на Telegram-аккаунт. Если прошлая попытка оборвалась на полпути (аккаунт PENDING), довыдаёт тот же клиент.
     *  Бонусные дни рефералки, ждавшие выдачи, прибавляются к пробному. */
    public synchronized Vpn2Account startTrial(Long telegramId) throws VpnException {
        requireEnabled();
        Vpn2Account account = accounts.findByTelegramId(telegramId).orElse(null);
        if (account != null && (!Vpn2Account.STATUS_PENDING.equals(account.getStatus())
                || Vpn2Account.PLAN_PAID.equals(account.getPlanType()))) {
            throw new VpnException("TRIAL_USED", "пробный период уже использован");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean created = account == null;
        if (created) {
            account = newAccount(telegramId, Vpn2Account.PLAN_TRIAL, now);
            account.setTrialUsedAt(now);
        }
        int bonus = pendingBonusDays(telegramId);
        account.setExpiresAt(now.plusDays(trialDays + (long) bonus));
        account.setReminderStage(0);
        account.setUpdatedAt(now);
        account = accounts.save(account);
        try {
            panel.upsertClient(spec(account));
        } catch (PanelException e) {
            if (created) {
                accounts.delete(account); // пробный не сгорает, если панель не выдала ключ
            }
            alert("Не удалось выдать пробный VPN игроку " + telegramId, e);
            throw new VpnException("PANEL", "панель VPN недоступна");
        }
        account.setStatus(Vpn2Account.STATUS_ACTIVE);
        account = accounts.save(account);
        clearPendingBonus(telegramId);
        return account;
    }

    /** Оплаченный срок по тарифу: продлевает от конца текущего срока (если ещё идёт) или от «сейчас». paymentRef - id платежа, повтор с тем же id ничего не делает.
     *  При сбое панели аккаунт остаётся PENDING (оплата не теряется), админам уходит алерт. */
    public synchronized Vpn2Account activatePaid(Long telegramId, Tariff tariff, String paymentRef) throws VpnException {
        requireEnabled();
        LocalDateTime now = LocalDateTime.now();
        Vpn2Account account = accounts.findByTelegramId(telegramId).orElse(null);
        if (account != null && paymentRef != null && paymentRef.equals(account.getLastPaymentRef())
                && Vpn2Account.STATUS_ACTIVE.equals(account.getStatus())) {
            return account;
        }
        if (account == null) {
            account = newAccount(telegramId, Vpn2Account.PLAN_PAID, now);
        }
        boolean running = Vpn2Account.STATUS_ACTIVE.equals(account.getStatus())
                && account.getExpiresAt() != null && account.getExpiresAt().isAfter(now);
        LocalDateTime base = running ? account.getExpiresAt() : now;
        int bonus = pendingBonusDays(telegramId);
        account.setPlanType(Vpn2Account.PLAN_PAID);
        account.setExpiresAt(base.plusDays(tariff.getDays() + (long) bonus));
        account.setReminderStage(0);
        account.setLastPaymentRef(paymentRef);
        account.setUpdatedAt(now);
        account = accounts.save(account);
        try {
            panel.upsertClient(spec(account));
        } catch (PanelException e) {
            account.setStatus(Vpn2Account.STATUS_PENDING);
            accounts.save(account);
            alert("ОПЛАЧЕНО, но VPN не выдан игроку " + telegramId + " (тариф " + tariff.getKey() + ", платёж " + paymentRef
                    + "). Повторить: /vpn2 " + telegramId + " " + tariff.getKey().toLowerCase(), e);
            throw new VpnException("PANEL", "панель VPN недоступна");
        }
        account.setStatus(Vpn2Account.STATUS_ACTIVE);
        account = accounts.save(account);
        clearPendingBonus(telegramId);
        return account;
    }

    /** Журнал оплаты (для статистики). Сбой записи не должен ломать выдачу: вызывающий ловит исключение. */
    public void recordPayment(Long telegramId, Tariff tariff, int starsAmount, long excSpent, String chargeId) {
        Vpn2Payment p = new Vpn2Payment();
        p.setTelegramId(telegramId);
        p.setTariff(tariff.getKey());
        p.setStarsAmount(starsAmount);
        p.setExcSpent(excSpent);
        p.setTelegramPaymentChargeId(chargeId);
        p.setCreatedAt(LocalDateTime.now());
        payments.save(p);
    }

    /** Рефералка после ПЕРВОЙ оплаты приглашённого: +7 дней и ему, и пригласившему. Возвращает id пригласившего или null, если награды нет.
     *  Награда за оплату, а не за вход: пустые аккаунты не дают бесплатных дней. */
    public synchronized Long rewardReferral(Long buyerId) {
        Optional<Vpn2User> found = users.findByTelegramId(buyerId);
        if (found.isEmpty() || found.get().getReferredBy() == null || found.get().isReferralRewarded()) {
            return null;
        }
        Vpn2User buyer = found.get();
        Long referrerId = buyer.getReferredBy();
        buyer.setReferralRewarded(true);
        users.save(buyer);
        grantBonusDays(referrerId, REFERRAL_BONUS_DAYS);
        grantBonusDays(buyerId, REFERRAL_BONUS_DAYS);
        return referrerId;
    }

    /** +дни к работающему ключу; если ключа нет или он ещё не выдан - дни копятся и добавятся к первому пробному/оплаченному сроку. */
    public synchronized void grantBonusDays(Long telegramId, int days) {
        Vpn2Account account = accounts.findByTelegramId(telegramId).orElse(null);
        boolean provisioned = account != null && Vpn2Account.STATUS_ACTIVE.equals(account.getStatus());
        if (provisioned) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime previous = account.getExpiresAt();
            LocalDateTime base = previous != null && previous.isAfter(now) ? previous : now;
            account.setExpiresAt(base.plusDays(days));
            account.setReminderStage(0);
            account.setUpdatedAt(now);
            accounts.save(account);
            try {
                panel.upsertClient(spec(account));
                return;
            } catch (PanelException e) {
                account.setExpiresAt(previous);
                accounts.save(account);
                alert("Не удалось начислить +" + days + " дн. VPN игроку " + telegramId + ", сохранено как отложенный бонус", e);
            }
        }
        users.findByTelegramId(telegramId).ifPresent(u -> {
            u.setBonusDays(u.getBonusDays() + days);
            users.save(u);
        });
    }

    private int pendingBonusDays(Long telegramId) {
        return users.findByTelegramId(telegramId).map(Vpn2User::getBonusDays).orElse(0);
    }

    private void clearPendingBonus(Long telegramId) {
        users.findByTelegramId(telegramId).ifPresent(u -> {
            if (u.getBonusDays() != 0) {
                u.setBonusDays(0);
                users.save(u);
            }
        });
    }

    /** Отключение: клиент удаляется из панели, запись остаётся (признак «пробный брали» сохраняется). */
    public synchronized void disable(Long telegramId) throws VpnException {
        requireEnabled();
        Optional<Vpn2Account> found = accounts.findByTelegramId(telegramId);
        if (found.isEmpty()) {
            return;
        }
        Vpn2Account account = found.get();
        try {
            panel.deleteClient(account.getPanelEmail());
        } catch (PanelException e) {
            alert("Не удалось отключить VPN игрока " + telegramId, e);
            throw new VpnException("PANEL", "панель VPN недоступна");
        }
        account.setStatus(Vpn2Account.STATUS_DISABLED);
        account.setUpdatedAt(LocalDateTime.now());
        accounts.save(account);
    }

    /** Сводка для админа (HTML): люди, ключи, оплаты и рефералы. */
    public String statsHtml() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime d7 = now.minusDays(7);
        LocalDateTime d30 = now.minusDays(30);
        StringBuilder sb = new StringBuilder("📊 <b>EGC VPN (бот): статистика</b>\n\n");
        sb.append("Пользователей: <b>").append(users.count()).append("</b> (за 7 дн.: ").append(users.countByCreatedAtAfter(d7)).append(")\n");
        sb.append("Пробный брали: <b>").append(accounts.countByTrialUsedAtIsNotNull()).append("</b>\n");
        sb.append("Работают сейчас: пробные <b>").append(accounts.countByStatusAndPlanTypeAndExpiresAtAfter(Vpn2Account.STATUS_ACTIVE, Vpn2Account.PLAN_TRIAL, now))
          .append("</b>, платные <b>").append(accounts.countByStatusAndPlanTypeAndExpiresAtAfter(Vpn2Account.STATUS_ACTIVE, Vpn2Account.PLAN_PAID, now)).append("</b>\n");
        sb.append("Истекают за 3 дня: <b>").append(accounts.countByStatusAndExpiresAtBetween(Vpn2Account.STATUS_ACTIVE, now, now.plusDays(3))).append("</b>\n\n");
        sb.append("Оплат за 7 дн.: <b>").append(payments.countByCreatedAtAfter(d7)).append("</b> на <b>").append(payments.sumStarsSince(d7)).append(" ⭐</b>\n");
        sb.append("Оплат за 30 дн.: <b>").append(payments.countByCreatedAtAfter(d30)).append("</b> на <b>").append(payments.sumStarsSince(d30)).append(" ⭐</b>\n\n");
        sb.append("Пришли по приглашению: <b>").append(users.countByReferredByIsNotNull()).append("</b>, наград выдано: <b>").append(users.countByReferralRewardedTrue()).append("</b>");
        return sb.toString();
    }

    private void requireEnabled() throws VpnException {
        if (!isEnabled()) {
            throw new VpnException("DISABLED", "VPN выключен");
        }
    }

    private Vpn2Account newAccount(Long telegramId, String plan, LocalDateTime now) {
        Vpn2Account a = new Vpn2Account();
        a.setTelegramId(telegramId);
        a.setPanelEmail("vpn_" + telegramId);
        a.setSubId(randomToken(8));
        a.setClientUuid(UUID.randomUUID().toString());
        a.setClientAuth(randomToken(16));
        a.setPlanType(plan);
        a.setStatus(Vpn2Account.STATUS_PENDING);
        a.setCreatedAt(now);
        return a;
    }

    /** Лимиты по плану аккаунта: пробный - trialGb и 1 устройство, платный - без лимита трафика и 2 устройства. */
    private ClientSpec spec(Vpn2Account a) {
        boolean trial = Vpn2Account.PLAN_TRIAL.equals(a.getPlanType());
        long expiryMs = a.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new ClientSpec(a.getPanelEmail(), a.getSubId(), a.getClientUuid(), a.getClientAuth(), randomToken(8),
                trial ? trialGb * GB : 0L, expiryMs, trial ? TRIAL_LIMIT_IP : PAID_LIMIT_IP, true, PANEL_COMMENT);
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Алерт админам (через основной бот: админы могли ни разу не открывать бот VPN); в текст не попадают пароли и тела ответов панели. */
    private void alert(String what, PanelException e) {
        log.warn("VPN2: {}: {}", what, e.getMessage());
        try {
            eventPublisher.publishEvent(new AnalyticsAlertEvent(this,
                    "🔐 <b>VPN-бот</b>: " + escape(what) + "\n<code>" + escape(e.getMessage()) + "</code>"));
        } catch (Exception ex) {
            log.warn("VPN2: failed to publish admin alert", ex);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
