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

    /** Тарифы: цена в рублях и в Stars (курс как у месячного: 150 ₽ = 89 ⭐; неделя 50 ₽ = 30 ⭐), со скидкой за EXC (−50 ₽) - отдельная цена (на неделе скидки нет). */
    public enum Tariff {
        W1("W1", "1 неделя", 7, 50, 30, 50, 30, 18_000),
        M1("M1", "1 месяц", 30, 150, 89, 100, 59, 54_000),
        M3("M3", "3 месяца", 90, 400, 237, 350, 208, 144_000),
        M12("M12", "12 месяцев", 365, 1350, 801, 1300, 771, 486_000);

        private final String key;
        private final String title;
        private final int days;
        private final int priceRub;
        private final int stars;
        private final int excPriceRub;
        private final int excStars;
        /** Полная оплата EXC без Stars: по курсу скидки (18 000 EXC = 50 ₽, то есть 360 EXC за рубль). Цифры ждут утверждения владельцем до включения оплаты EXC. */
        private final long excOnlyPrice;

        Tariff(String key, String title, int days, int priceRub, int stars, int excPriceRub, int excStars, long excOnlyPrice) {
            this.key = key;
            this.title = title;
            this.days = days;
            this.priceRub = priceRub;
            this.stars = stars;
            this.excPriceRub = excPriceRub;
            this.excStars = excStars;
            this.excOnlyPrice = excOnlyPrice;
        }

        public String getKey() { return key; }

        public String getTitle() { return title; }

        public int getDays() { return days; }

        public int getPriceRub() { return priceRub; }

        public int getStars() { return stars; }

        public int getExcPriceRub() { return excPriceRub; }

        public int getExcStars() { return excStars; }

        public long getExcOnlyPrice() { return excOnlyPrice; }

        /** Скидка за EXC (−50 ₽) есть только там, где цена со скидкой ниже обычной: на недельном тарифе её нет. */
        public boolean hasExcDiscount() { return excPriceRub < priceRub; }

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
    private final Set<Long> techIds;
    private final int inboundId;
    private final String botToken;
    private final String botUsername;
    private final String subBase;
    private final int trialDays;
    private final int trialGb;
    private final String reviewsChannel;
    private final boolean excPayEnabled;
    private final String cardDetails;
    private final String cryptoToken;
    private final boolean cardManager;
    private final String managerUsername;

    public Vpn2Service(ObjectMapper objectMapper, Vpn2AccountRepository accounts, Vpn2UserRepository users,
                       Vpn2PaymentRepository payments, ApplicationEventPublisher eventPublisher,
                       @Value("${app.vpn2-enabled:false}") boolean enabled,
                       @Value("${app.vpn2-open:false}") boolean open,
                       @Value("${app.vpn2-test-ids:}") String testIds,
                       @Value("${app.vpn2-tech-ids:}") String techIds,
                       @Value("${app.vpn2-bot-token:}") String botToken,
                       @Value("${app.vpn2-bot-username:}") String botUsername,
                       @Value("${app.vpn2-xui-url:}") String xuiUrl,
                       @Value("${app.vpn2-xui-user:}") String xuiUser,
                       @Value("${app.vpn2-xui-pass:}") String xuiPass,
                       @Value("${app.vpn2-xui-inbound-id:2}") int inboundId,
                       @Value("${app.vpn2-sub-base:}") String subBase,
                       @Value("${app.vpn2-trial-days:7}") int trialDays,
                       @Value("${app.vpn2-trial-gb:10}") int trialGb,
                       @Value("${app.vpn2-reviews-channel:}") String reviewsChannel,
                       @Value("${app.vpn2-exc-pay-enabled:false}") boolean excPayEnabled,
                       @Value("${app.vpn2-card-details:}") String cardDetails,
                       @Value("${app.vpn2-cryptobot-token:}") String cryptoToken,
                       @Value("${app.vpn2-card-manager:false}") boolean cardManager,
                       @Value("${app.vpn2-manager-username:}") String managerUsername) {
        this.panel = new VpnPanelClient(objectMapper, xuiUrl, xuiUser, xuiPass, inboundId);
        this.accounts = accounts;
        this.users = users;
        this.payments = payments;
        this.eventPublisher = eventPublisher;
        this.enabled = enabled;
        this.open = open;
        this.testIds = parseIds(testIds);
        this.techIds = parseIds(techIds);
        this.inboundId = inboundId;
        this.botToken = botToken == null ? "" : botToken.trim();
        this.botUsername = botUsername == null ? "" : botUsername.trim().replaceFirst("^@", "");
        this.subBase = subBase == null ? "" : subBase.trim().replaceAll("/+$", "");
        this.trialDays = trialDays;
        this.trialGb = trialGb;
        this.reviewsChannel = reviewsChannel == null ? "" : reviewsChannel.trim();
        this.excPayEnabled = excPayEnabled;
        this.cardDetails = cardDetails == null ? "" : cardDetails.trim();
        this.cryptoToken = cryptoToken == null ? "" : cryptoToken.trim();
        this.cardManager = cardManager;
        this.managerUsername = managerUsername == null ? "" : managerUsername.trim().replaceFirst("^@", "");
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

    /** Техник (Влад): видит панель «🛠 Панель» только на просмотр и получает тревоги о связке в VPN-боте; без денег и без полных ID. VPN2_TECH_IDS. */
    public boolean isTech(Long telegramId) { return techIds.contains(telegramId); }

    public Set<Long> getTechIds() { return techIds; }

    public String getBotToken() { return botToken; }

    public String getBotUsername() { return botUsername; }

    public int getTrialDays() { return trialDays; }

    public int getTrialGb() { return trialGb; }

    /** Канал отзывов (@username или id): сюда бот публикует одобренные карточки; пусто - публикация выключена. */
    public String getReviewsChannel() { return reviewsChannel; }

    /** Оплата тарифа целиком за EXC: показывается всегда, но кнопки работают только при VPN2_EXC_PAY_ENABLED=true («видно, но закрыто» до решения владельца). */
    public boolean isExcPayEnabled() { return excPayEnabled; }

    /** Реквизиты для перевода рублями (карта/СБП), текст как его надо показать игроку; пусто - способ закрыт. */
    public String getCardDetails() { return cardDetails; }

    public boolean isCardPayEnabled() { return !cardDetails.isBlank() || cardManager; }

    /** Перевод рублями через менеджера (05.11): реквизиты игроку присылает менеджер лично, VPN2_CARD_MANAGER=true; если задан и VPN2_CARD_DETAILS, работает старая схема с реквизитами в боте. */
    public boolean isCardManagerMode() { return cardManager && cardDetails.isBlank(); }

    /** Менеджер по оплатам VPN (VPN2_MANAGER_USERNAME без @); пусто - берётся общий аккаунт поддержки. */
    public String getManagerUsername() { return managerUsername; }

    /** Токен CryptoBot (Crypto Pay API); пусто - крипто-оплата закрыта. */
    public String getCryptoToken() { return cryptoToken; }

    public boolean isCryptoPayEnabled() { return !cryptoToken.isBlank(); }

    /** Состояние всех клиентов второй панели одним запросом (метка -> расход, лимит, последний онлайн, загрузка подписки). */
    public java.util.Map<String, VpnPanelClient.ClientStat> allClientStats() throws PanelException {
        return panel.clientStats();
    }

    /** Нагрузка на inbound бота в панели (клиенты, онлайн, трафик, топ-5 наших). */
    public VpnPanelClient.NodeLoad nodeLoad() throws PanelException {
        return panel.nodeLoad(inboundId, System.currentTimeMillis());
    }

    /** Сводка по нашим ключам для панели: работающие пробные и платные, ни разу не подключавшиеся (по данным панели), кончаются за 3 дня, исчерпали лимит. */
    public record KeysOverview(int trialRunning, int paidRunning, int neverConnected, int expiring3d, int limitReached, boolean panelOk) {}

    public KeysOverview keysOverview() {
        LocalDateTime now = LocalDateTime.now();
        java.util.Map<String, VpnPanelClient.ClientStat> stats = null;
        boolean panelOk = true;
        try {
            stats = panel.clientStats();
        } catch (PanelException e) {
            panelOk = false;
        }
        int trial = 0;
        int paid = 0;
        int never = 0;
        int expiring = 0;
        int limit = 0;
        for (Vpn2Account a : accounts.findAll()) {
            if (!isRunning(a)) {
                continue;
            }
            boolean isTrial = Vpn2Account.PLAN_TRIAL.equals(a.getPlanType());
            if (isTrial) {
                trial++;
                if (a.getLimitNotifiedAt() != null) {
                    limit++;
                }
            } else {
                paid++;
            }
            if (a.getExpiresAt().isBefore(now.plusDays(3))) {
                expiring++;
            }
            VpnPanelClient.ClientStat st = stats == null ? null : stats.get(a.getPanelEmail());
            if (st != null && st.used() == 0 && st.lastOnlineMs() <= 0) {
                never++;
            }
        }
        return new KeysOverview(trial, paid, never, expiring, limit, panelOk);
    }

    /** Состояние ключа игрока в панели; пусто - панель не ответила или клиента там нет. */
    public Optional<VpnPanelClient.ClientStat> clientStat(Vpn2Account account) {
        try {
            return Optional.ofNullable(panel.clientStats().get(account.getPanelEmail()));
        } catch (PanelException e) {
            log.warn("VPN2: client stat for {} failed: {}", account.getTelegramId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Склонение срока: «7 дней», «1 день», «3 дня». */
    public static String daysText(int n) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        String word = mod100 >= 11 && mod100 <= 14 ? "дней" : mod10 == 1 ? "день" : mod10 >= 2 && mod10 <= 4 ? "дня" : "дней";
        return n + " " + word;
    }

    /** Telegram ID всех, у кого в VPN-боте уже есть ключ: им рассылка «попробуйте VPN» не нужна. */
    public java.util.Set<Long> keyHolderIds() {
        return accounts.findAll().stream().map(Vpn2Account::getTelegramId).collect(java.util.stream.Collectors.toSet());
    }

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

    /** Бот включён и виден этому человеку: всем (VPN2_OPEN), тестерам (VPN2_TEST_IDS) или админам. Основной бот по этому признаку решает, показывать ли ссылку на VPN-бот. */
    public boolean isVisibleFor(Long telegramId, boolean admin) {
        return isEnabled() && (open || admin || testIds.contains(telegramId));
    }

    /** Запись пользователя при /start; реферер и источник (например «egc») учитываются только при первом заходе; реферер - только если он сам уже пользователь бота. */
    public synchronized Vpn2User registerUser(Long telegramId, String firstName, String username, Long referredBy, String sourceCode) {
        Optional<Vpn2User> found = users.findByTelegramId(telegramId);
        if (found.isPresent()) {
            return found.get();
        }
        Vpn2User u = new Vpn2User();
        u.setTelegramId(telegramId);
        u.setFirstName(cut(firstName, 128));
        u.setUsername(cut(username, 64));
        u.setSourceCode(cut(sourceCode, 32));
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
        recordPayment(telegramId, tariff, starsAmount, excSpent, chargeId, "STARS", 0);
    }

    /** То же с указанием способа (STARS, EXC, CARD, CRYPTO) и рублёвой суммы для CARD и CRYPTO. */
    public void recordPayment(Long telegramId, Tariff tariff, int starsAmount, long excSpent, String chargeId, String method, int rubAmount) {
        Vpn2Payment p = new Vpn2Payment();
        p.setMethod(method);
        p.setRubAmount(rubAmount);
        p.setTelegramId(telegramId);
        p.setTariff(tariff.getKey());
        p.setStarsAmount(starsAmount);
        p.setExcSpent(excSpent);
        p.setTelegramPaymentChargeId(chargeId);
        p.setCreatedAt(LocalDateTime.now());
        payments.save(p);
    }

    /** Последние 5 оплат человека (для возврата админом). */
    public java.util.List<Vpn2Payment> recentPayments(Long telegramId) {
        return payments.findTop5ByTelegramIdOrderByCreatedAtDesc(telegramId);
    }

    public Optional<Vpn2Payment> findPayment(Long paymentId) {
        return payments.findById(paymentId);
    }

    /** Отметка «звёзды возвращены» (после успешного refundStarPayment). */
    public void markRefunded(Vpn2Payment payment) {
        payment.setRefundedAt(LocalDateTime.now());
        payments.save(payment);
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

    /** Перенос ключей на новую панель (после смены бэкенда VPN2_XUI_*): для всех работающих ключей создаёт или обновляет клиента с теми же subId и сроком.
     *  Ссылка подписки у игроков меняется вместе с VPN2_SUB_BASE, поэтому после переноса им нужно заново добавить подписку. Возвращает число перенесённых. */
    public synchronized int resyncAll() {
        int done = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Vpn2Account a : accounts.findAll()) {
            if (!Vpn2Account.STATUS_ACTIVE.equals(a.getStatus()) || a.getExpiresAt() == null || !a.getExpiresAt().isAfter(now)) {
                continue;
            }
            try {
                panel.upsertClient(spec(a));
                done++;
            } catch (PanelException e) {
                alert("Не удалось перенести ключ игрока " + a.getTelegramId() + " на новую панель", e);
            }
        }
        return done;
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

    /** Оплаты по способам (HTML-блок для /stats): число, Stars, рубли, EXC. */
    public String paymentMethodsHtml() {
        StringBuilder sb = new StringBuilder("💳 <b>Оплаты по способам</b>\n");
        java.util.List<Object[]> rows = payments.summaryByMethod();
        if (rows.isEmpty()) {
            return sb.append("Оплат пока нет.").toString();
        }
        for (Object[] r : rows) {
            sb.append(r[0]).append(": ").append(r[1]).append(" шт.");
            if (((Number) r[2]).longValue() > 0) {
                sb.append(", ").append(r[2]).append(" ⭐");
            }
            if (((Number) r[3]).longValue() > 0) {
                sb.append(", ").append(r[3]).append(" ₽");
            }
            if (((Number) r[4]).longValue() > 0) {
                sb.append(", ").append(r[4]).append(" EXC");
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
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
        sb.append("Пришли из игрового бота EGC: <b>").append(users.countBySourceCode("egc")).append("</b>\n");
        sb.append("Пришли из рассылки: вариант A <b>").append(users.countBySourceCode("bc_a")).append("</b>, вариант B <b>").append(users.countBySourceCode("bc_b"))
          .append("</b>, волна 2 <b>").append(users.countBySourceCode("bc_w2")).append("</b>\n");
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
