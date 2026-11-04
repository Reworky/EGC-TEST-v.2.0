package ru.gamebot.platform.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Vpn2Account;
import ru.gamebot.platform.domain.repository.Vpn2AccountRepository;
import ru.gamebot.platform.event.AnalyticsAlertEvent;
import ru.gamebot.platform.service.AdminService;
import ru.gamebot.platform.service.ExcTransactionService;
import ru.gamebot.platform.service.UserService;
import ru.gamebot.platform.service.Vpn2Service;
import ru.gamebot.platform.service.Vpn2Service.Tariff;
import ru.gamebot.platform.service.VpnService.VpnException;

/** Отдельный бот EGC VPN под общим брендом: тарифы, ключ, установка, рефералка, оплата Telegram Stars. Живёт в том же приложении, но со своим токеном
 *  (VPN2_BOT_TOKEN) и своей панелью (VPN2_*): основной бот и пилот VPN не затронуты. Без токена/имени не регистрируется.
 *  Пока VPN2_OPEN не true, отвечает только админам и VPN2_TEST_IDS, остальным «скоро открытие». */
@Slf4j
@Component
@RequiredArgsConstructor
public class VpnBot extends TelegramLongPollingBot {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final int REMIND_SOON_DAYS = 2;
    private static final int REMIND_EXPIRED_WINDOW_DAYS = 3;
    private static final String APP_IOS = "https://apps.apple.com/app/happ-proxy-utility/id6504287215";
    private static final String APP_ANDROID = "https://play.google.com/store/apps/details?id=com.happproxy";
    private static final String APP_DESKTOP = "https://github.com/Happ-proxy/happ-desktop/releases/latest";

    private final Vpn2Service vpn;
    private final AdminService adminService;
    private final AppProperties appProperties;
    private final UserService userService;
    private final ExcTransactionService excTransactionService;
    private final KeyboardFactory keyboardFactory;
    private final GamePlatformBot egcBot;
    private final Vpn2AccountRepository accountRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
    /** Ссылки на инвойс не зависят от человека (товар и цена те же), поэтому кэшируем: экран тарифов не ходит в Telegram за каждой ссылкой. */
    private final Map<String, String> invoiceLinks = new ConcurrentHashMap<>();
    private volatile boolean registered = false;

    @EventListener(ApplicationReadyEvent.class)
    public void registerBot() {
        if (vpn.getBotToken().isBlank() || vpn.getBotUsername().isBlank()) {
            log.warn("EGC VPN bot not registered: VPN2_BOT_TOKEN / VPN2_BOT_USERNAME not set");
            return;
        }
        try {
            ((DefaultBotOptions) getOptions()).setAllowedUpdates(List.of("message", "callback_query", "pre_checkout_query"));
            new TelegramBotsApi(DefaultBotSession.class).registerBot(this);
            registered = true;
            log.info("EGC VPN bot registered: {}", getBotUsername());
        } catch (Exception e) {
            log.error("EGC VPN bot registration failed (main bot is not affected)", e);
        }
    }

    @Override
    public String getBotUsername() {
        return vpn.getBotUsername();
    }

    @Override
    public String getBotToken() {
        return vpn.getBotToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasPreCheckoutQuery()) {
                handlePreCheckout(update.getPreCheckoutQuery());
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            } else if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            log.error("VPN bot: update handling failed", e);
        }
    }

    // ---------------------------------------------------------------- сообщения

    private void handleMessage(Message message) {
        if (message.getFrom() == null) {
            return;
        }
        if (message.getSuccessfulPayment() != null) {
            handleSuccessfulPayment(message);
            return;
        }
        if (message.getChat() == null || !"private".equals(message.getChat().getType())) {
            return;
        }
        User from = message.getFrom();
        Long uid = from.getId();
        String text = message.getText() == null ? "" : message.getText().trim();
        if (isAdmin(uid) && text.startsWith("/stats")) {
            sendHtml(uid, vpn.statsHtml(), null);
            return;
        }
        if (isAdmin(uid) && text.startsWith("/vpn2")) {
            handleAdminCommand(uid, text);
            return;
        }
        Long referrer = null;
        if (text.startsWith("/start")) {
            String[] parts = text.split("\\s+");
            if (parts.length > 1 && parts[1].startsWith("ref_")) {
                try {
                    referrer = Long.parseLong(parts[1].substring(4));
                } catch (NumberFormatException ignored) {
                    // битая ссылка: просто без реферера
                }
            }
        }
        vpn.registerUser(uid, from.getFirstName(), from.getUserName(), referrer);
        if (!allowed(uid)) {
            showClosed(uid, null);
            return;
        }
        showMain(uid, null);
    }

    private boolean isAdmin(Long uid) {
        return adminService.resolvedAdminIds().contains(uid);
    }

    /** Бот включён и открыт для этого человека: всем (VPN2_OPEN), тестерам (VPN2_TEST_IDS) или админам. */
    private boolean allowed(Long uid) {
        return vpn.isEnabled() && (vpn.isOpen() || vpn.isTester(uid) || isAdmin(uid));
    }

    // ---------------------------------------------------------------- кнопки

    private void handleCallback(CallbackQuery cq) {
        answerSilently(cq.getId());
        if (cq.getFrom() == null || cq.getData() == null) {
            return;
        }
        User from = cq.getFrom();
        Long uid = from.getId();
        Integer oldMsgId = cq.getMessage() == null ? null : cq.getMessage().getMessageId();
        vpn.registerUser(uid, from.getFirstName(), from.getUserName(), null);
        if (!allowed(uid)) {
            showClosed(uid, oldMsgId);
            return;
        }
        switch (cq.getData()) {
            case "m:main" -> showMain(uid, oldMsgId);
            case "m:my" -> showMy(uid, oldMsgId);
            case "m:tariffs" -> showTariffs(uid, oldMsgId);
            case "m:howto" -> showHowto(uid, oldMsgId);
            case "m:ref" -> showReferral(uid, oldMsgId);
            case "m:terms" -> showTerms(uid, oldMsgId);
            case "m:trial" -> startTrial(uid, oldMsgId);
            default -> { }
        }
    }

    private void showClosed(Long uid, Integer oldMsgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("🎮 Играть и получать EXC", egcUrl())));
        show(uid, oldMsgId, "🔐 <b>EGC VPN</b>\n\nСкоро открытие. Мы напишем здесь, когда сервис запустится.", rows);
    }

    private void showMain(Long uid, Integer oldMsgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("🔑 Моё подключение", "m:my"), keyboardFactory.callback("💳 Тарифы", "m:tariffs")));
        rows.add(List.of(keyboardFactory.callback("📲 Как подключить", "m:howto"), keyboardFactory.callback("🎁 Пригласить друга", "m:ref")));
        rows.add(List.of(keyboardFactory.url("🆘 Поддержка", "https://t.me/" + appProperties.getSupportUsername())));
        rows.add(List.of(keyboardFactory.url("🎮 Играть и получать EXC", egcUrl())));
        show(uid, oldMsgId, "🔐 <b>EGC VPN</b>\n\n"
                + "Быстрый VPN для телефона и компьютера от клуба EGC.\n"
                + "• До 2 устройств, подключение за минуту\n"
                + "• Личный ключ: он только ваш\n"
                + "• Работает в России и за границей\n\n"
                + "<b>От 150 ₽ в месяц</b>, а первые " + vpn.getTrialDays() + " дня бесплатно.", rows);
    }

    private void showMy(Long uid, Integer oldMsgId) {
        Optional<Vpn2Account> found = vpn.find(uid);
        if (found.isPresent()) {
            Vpn2Account a = found.get();
            if (vpn.isRunning(a)) {
                showKey(uid, oldMsgId, a, null);
                return;
            }
            if (Vpn2Account.STATUS_PENDING.equals(a.getStatus()) && Vpn2Account.PLAN_PAID.equals(a.getPlanType())) {
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                rows.add(List.of(keyboardFactory.url("🆘 Поддержка", "https://t.me/" + appProperties.getSupportUsername())));
                rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
                show(uid, oldMsgId, "💳 <b>Оплата получена</b>\n\nКлюч VPN выдаём вручную, это займёт немного времени. Мы уже знаем и пришлём его сюда.", rows);
                return;
            }
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        StringBuilder sb = new StringBuilder("🔑 <b>Моё подключение</b>\n\n");
        if (vpn.isTrialAvailable(found)) {
            sb.append("Ключа пока нет. Возьмите пробный на ").append(vpn.getTrialDays()).append(" дня бесплатно или оформите тариф.");
            rows.add(List.of(keyboardFactory.callback("🎁 " + vpn.getTrialDays() + " дня бесплатно", "m:trial")));
        } else {
            sb.append("Срок вашего ключа истёк. Оформите тариф, и ключ снова заработает.");
        }
        rows.add(List.of(keyboardFactory.callback("💳 Тарифы", "m:tariffs")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, sb.toString(), rows);
    }

    /** Ключ: срок, лимиты, шаги подключения и личная ссылка подписки. headline - своя первая строка (после оплаты). */
    private void showKey(Long uid, Integer oldMsgId, Vpn2Account a, String headline) {
        boolean trial = Vpn2Account.PLAN_TRIAL.equals(a.getPlanType());
        StringBuilder sb = new StringBuilder();
        sb.append(headline != null ? headline : (trial ? "✅ <b>Пробный VPN включён</b>" : "✅ <b>EGC VPN активен</b>")).append("\n\n")
          .append("До: <b>").append(DATE_TIME.format(a.getExpiresAt())).append("</b> · ")
          .append(trial ? "лимит " + vpn.getTrialGb() + " ГБ · 1 устройство" : "безлимит · до 2 устройств").append("\n\n")
          .append(connectSteps()).append("\n\n")
          .append("Ваша личная ссылка (никому не показывайте):\n<code>").append(escape(vpn.subscriptionUrl(a))).append("</code>");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(installRows());
        rows.add(List.of(keyboardFactory.callback(trial ? "💳 Оформить тариф" : "💳 Продлить", "m:tariffs")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, sb.toString(), rows);
    }

    private static String connectSteps() {
        return "<b>Как подключить (2 минуты)</b>\n"
                + "1. Установите приложение Happ (кнопки ниже)\n"
                + "2. Нажмите на ссылку ниже, она скопируется\n"
                + "3. В Happ добавьте подписку по этой ссылке из буфера обмена, обновите её и нажмите «Подключить»";
    }

    private List<List<InlineKeyboardButton>> installRows() {
        return List.of(
                List.of(keyboardFactory.url("🍏 iPhone: Happ", APP_IOS), keyboardFactory.url("📱 Android: Happ", APP_ANDROID)),
                List.of(keyboardFactory.url("💻 Windows / Mac: Happ", APP_DESKTOP)));
    }

    private void showHowto(Long uid, Integer oldMsgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(installRows());
        rows.add(List.of(keyboardFactory.callback("🔑 Моё подключение", "m:my")));
        rows.add(List.of(keyboardFactory.url("🆘 Поддержка", "https://t.me/" + appProperties.getSupportUsername())));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, "📲 <b>Как подключить</b>\n\n" + connectSteps()
                + "\n\nСсылку подписки берите в разделе «Моё подключение». Если что-то не получается, напишите в поддержку, поможем.", rows);
    }

    private void showTariffs(Long uid, Integer oldMsgId) {
        StringBuilder sb = new StringBuilder("💳 <b>Тарифы EGC VPN</b>\n\n");
        for (Tariff t : Tariff.values()) {
            sb.append("• ").append(t.getTitle()).append(": <b>").append(t.getPriceRub()).append(" ₽</b> (").append(t.getStars()).append(" ⭐)");
            if (t != Tariff.M1) {
                int full = Tariff.M1.getPriceRub() * (t.getDays() / 30);
                sb.append(", выгода ").append(Math.round(100.0 * (full - t.getPriceRub()) / full)).append("%");
            }
            sb.append("\n");
        }
        sb.append("\nБезлимитный трафик, до 2 устройств. Оплата звёздами Telegram ⭐.\n")
          .append("За ").append(formatExc(Vpn2Service.EXC_DISCOUNT)).append(" EXC скидка 50 ₽ на любой срок.");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        boolean linkFailed = false;
        for (Tariff t : Tariff.values()) {
            String link = invoiceLink(t, false);
            if (link == null) {
                linkFailed = true;
                continue;
            }
            rows.add(List.of(keyboardFactory.url("💳 " + t.getTitle() + ": " + t.getStars() + " ⭐", link)));
        }
        long coins = excBalance(uid);
        if (coins >= Vpn2Service.EXC_DISCOUNT) {
            for (Tariff t : Tariff.values()) {
                String link = invoiceLink(t, true);
                if (link != null) {
                    rows.add(List.of(keyboardFactory.url("🪙 −50 ₽ за EXC: " + t.getTitle() + ", " + t.getExcStars() + " ⭐", link)));
                }
            }
        } else if (coins >= 0) {
            rows.add(List.of(keyboardFactory.callback("🪙 Скидка 50 ₽: нужно " + formatExc(Vpn2Service.EXC_DISCOUNT) + " EXC, у вас " + formatExc(coins), "m:noop")));
        } else {
            rows.add(List.of(keyboardFactory.url("🪙 Скидка за EXC: заработайте их в EGC", egcUrl())));
        }
        if (linkFailed) {
            log.warn("VPN bot: failed to create Stars invoice link for tariffs screen, user {}", uid);
            sb.append("\n\n⚠️ Часть кнопок оплаты временно недоступна, попробуйте через минуту.");
        }
        rows.add(List.of(keyboardFactory.callback("ℹ️ Условия", "m:terms")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, sb.toString(), rows);
    }

    private void showTerms(Long uid, Integer oldMsgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "m:tariffs"), keyboardFactory.callback("🏠 Меню", "m:main")));
        show(uid, oldMsgId, "ℹ️ <b>Условия EGC VPN</b>\n\n"
                + "Мы сохраняем технические данные подключения (время, IP и объём трафика) 90 дней для безопасности и поддержки. "
                + "Адреса посещаемых сайтов мы не сохраняем.\n\n"
                + "Один ключ на аккаунт, передавать его другим нельзя. Пробный период выдаётся один раз.", rows);
    }

    private void showReferral(Long uid, Integer oldMsgId) {
        String link = "https://t.me/" + vpn.getBotUsername() + "?start=ref_" + uid;
        String share = "https://t.me/share/url?url=" + URLEncoder.encode(link, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode("EGC VPN: быстрый VPN, первые дни бесплатно", StandardCharsets.UTF_8);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("📤 Отправить другу", share)));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, "🎁 <b>Пригласите друга</b>\n\n"
                + "Отправьте другу свою ссылку. Когда он оформит VPN за звёзды, вы оба получите <b>+" + Vpn2Service.REFERRAL_BONUS_DAYS + " дней</b>.\n\n"
                + "Ваша ссылка:\n<code>" + escape(link) + "</code>", rows);
    }

    /** Пробный период: нужна подписка на канал EGC (проверяет основной бот, он админ канала); один раз на аккаунт (проверяет Vpn2Service). */
    private void startTrial(Long uid, Integer oldMsgId) {
        Boolean member = egcBot.isRequiredChannelMember(uid);
        if (Boolean.FALSE.equals(member)) {
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            String channelUrl = egcBot.requiredChannelJoinUrl();
            if (channelUrl != null && !channelUrl.isBlank()) {
                rows.add(List.of(keyboardFactory.url("📢 Подписаться", channelUrl)));
            }
            rows.add(List.of(keyboardFactory.callback("✅ Я подписался", "m:trial")));
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "m:my")));
            show(uid, oldMsgId, "🎁 <b>Пробный VPN на " + vpn.getTrialDays() + " дня, бесплатно</b>\n\nЧтобы получить ключ, подпишитесь на наш канал.", rows);
            return;
        }
        try {
            showKey(uid, oldMsgId, vpn.startTrial(uid), null);
        } catch (VpnException e) {
            if ("TRIAL_USED".equals(e.getCode())) {
                showMy(uid, oldMsgId);
            } else {
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
                show(uid, oldMsgId, "⚠️ Не получилось выдать ключ. Попробуйте через несколько минут, мы уже знаем о проблеме.", rows);
            }
        }
    }

    // ---------------------------------------------------------------- оплата Stars

    /** Ответ на pre_checkout в течение 10 секунд (требование Telegram): проверяем товар, цену, доступ и хватает ли EXC для скидки. */
    private void handlePreCheckout(PreCheckoutQuery query) {
        AnswerPreCheckoutQuery answer = new AnswerPreCheckoutQuery();
        answer.setPreCheckoutQueryId(query.getId());
        String reject = preCheckoutReject(query);
        if (reject != null) {
            answer.setOk(false);
            answer.setErrorMessage(reject);
        } else {
            answer.setOk(true);
        }
        try {
            execute(answer);
        } catch (TelegramApiException e) {
            log.error("VPN bot: failed to answer pre_checkout {}", query.getId(), e);
        }
    }

    private String preCheckoutReject(PreCheckoutQuery query) {
        Long uid = query.getFrom().getId();
        String payload = query.getInvoicePayload();
        Optional<Tariff> tariff = parseTariff(payload);
        if (tariff.isEmpty()) {
            return "Неизвестный товар";
        }
        boolean exc = isExcPayload(payload);
        int expected = exc ? tariff.get().getExcStars() : tariff.get().getStars();
        if (query.getTotalAmount() == null || query.getTotalAmount() != expected) {
            return "Цена изменилась, откройте тарифы заново";
        }
        if (!allowed(uid)) {
            return "VPN пока недоступен";
        }
        if (exc && excBalance(uid) < Vpn2Service.EXC_DISCOUNT) {
            return "Не хватает EXC для скидки";
        }
        return null;
    }

    /** Деньги Telegram уже списал: любой сбой выдачи - честное сообщение игроку и алерт админам, а не тишина. EXC списываются только после успешной выдачи. */
    private void handleSuccessfulPayment(Message message) {
        SuccessfulPayment pay = message.getSuccessfulPayment();
        Long uid = message.getFrom().getId();
        String payload = pay.getInvoicePayload();
        String chargeId = pay.getTelegramPaymentChargeId();
        try {
            Optional<Tariff> parsed = parseTariff(payload);
            if (parsed.isEmpty()) {
                throw new IllegalStateException("неизвестный товар " + payload);
            }
            Tariff tariff = parsed.get();
            boolean exc = isExcPayload(payload);
            Vpn2Account account;
            try {
                account = vpn.activatePaid(uid, tariff, chargeId);
            } catch (VpnException e) {
                throw new IllegalStateException("VPN не выдан после оплаты (" + e.getCode() + ")");
            }
            long excSpent = 0;
            String excNote = "";
            if (exc) {
                Optional<AppUser> appUser = userService.findByTelegramId(uid);
                if (appUser.isPresent()) {
                    AppUser u = appUser.get();
                    try {
                        excTransactionService.refreshCoins(u);
                        if (u.getCoins() >= Vpn2Service.EXC_DISCOUNT) {
                            excTransactionService.creditExc(u, -Vpn2Service.EXC_DISCOUNT, ExcTransactionService.SHOP_BUY, "EGC VPN (бот): скидка за EXC");
                            excSpent = Vpn2Service.EXC_DISCOUNT;
                            excNote = ", списано " + formatExc(excSpent) + " EXC";
                        } else {
                            excNote = ", EXC НЕ списаны (не хватило на момент оплаты)";
                        }
                    } catch (Exception e) {
                        log.error("VPN bot: failed to debit EXC after Stars payment, user {}", uid, e);
                        excNote = ", EXC НЕ списаны (ошибка)";
                    }
                } else {
                    excNote = ", EXC НЕ списаны (нет профиля EGC)";
                }
            }
            try {
                vpn.recordPayment(uid, tariff, pay.getTotalAmount() == null ? 0 : pay.getTotalAmount(), excSpent, chargeId);
            } catch (Exception e) {
                log.error("VPN bot: failed to record payment {}", chargeId, e); // журнал вторичен: ключ уже выдан
            }
            String bonusNote = "";
            try {
                Long referrer = vpn.rewardReferral(uid);
                if (referrer != null) {
                    bonusNote = "\n🎁 За приглашение вам начислено +" + Vpn2Service.REFERRAL_BONUS_DAYS + " дней.";
                    sendHtml(referrer, "🎁 <b>Ваш друг оформил VPN</b>\n\nВам начислено +" + Vpn2Service.REFERRAL_BONUS_DAYS + " дней к вашему ключу.", null);
                    account = vpn.find(uid).orElse(account);
                }
            } catch (Exception e) {
                log.error("VPN bot: referral reward failed for buyer {}", uid, e);
            }
            alertAdmins("🔐 <b>Покупка в VPN-боте</b>: " + escape(message.getFrom().getFirstName()) + " (<code>" + uid + "</code>), "
                    + escape(tariff.getTitle()) + ", " + (pay.getTotalAmount() == null ? 0 : pay.getTotalAmount()) + " ⭐" + escape(excNote));
            showKey(uid, null, account, "✅ <b>Оплачено!</b> VPN работает до " + DATE_TIME.format(account.getExpiresAt()) + bonusNote);
        } catch (Exception e) {
            log.error("VPN bot: payment handling failed, user {}, charge {}", uid, chargeId, e);
            alertAdmins("🚨 <b>ОПЛАТА В VPN-БОТЕ не обработана</b>\nИгрок <code>" + uid + "</code>, товар <code>" + escape(payload) + "</code>, "
                    + (pay.getTotalAmount() == null ? 0 : pay.getTotalAmount()) + " ⭐, платёж <code>" + escape(chargeId) + "</code>\n"
                    + escape(String.valueOf(e.getMessage())));
            sendHtml(uid, "💳 <b>Оплата получена</b>\n\nКлюч выдаём вручную, это займёт немного времени. Мы уже знаем и пришлём его сюда.", null);
        }
    }

    private static Optional<Tariff> parseTariff(String payload) {
        if (payload == null || !payload.startsWith("vpn2:")) {
            return Optional.empty();
        }
        String[] parts = payload.split(":");
        return parts.length >= 2 ? Tariff.byKey(parts[1]) : Optional.empty();
    }

    private static boolean isExcPayload(String payload) {
        return payload != null && payload.endsWith(":exc");
    }

    /** Ссылка на инвойс Stars (createInvoiceLink токеном ЭТОГО бота: оплата и pre_checkout придут сюда). null - не удалось. */
    private String invoiceLink(Tariff t, boolean exc) {
        String payload = "vpn2:" + t.getKey() + (exc ? ":exc" : "");
        String cached = invoiceLinks.get(payload);
        if (cached != null) {
            return cached;
        }
        try {
            int stars = exc ? t.getExcStars() : t.getStars();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "EGC VPN: " + t.getTitle());
            body.put("description", "Личный ключ EGC VPN на " + t.getTitle() + ", безлимитный трафик, до 2 устройств"
                    + (exc ? ". Со скидкой за EXC" : ""));
            body.put("payload", payload);
            body.put("provider_token", "");
            body.put("currency", "XTR");
            body.put("prices", List.of(Map.of("label", "VPN " + t.getTitle(), "amount", stars)));
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.telegram.org/bot" + vpn.getBotToken() + "/createInvoiceLink"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            java.net.http.HttpResponse<String> response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() == 200 && root.path("ok").asBoolean(false)) {
                String link = root.path("result").asText();
                invoiceLinks.put(payload, link);
                return link;
            }
            log.error("VPN bot: createInvoiceLink failed ({}): HTTP {} {}", payload, response.statusCode(), response.body());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("VPN bot: createInvoiceLink failed ({})", payload, e);
            return null;
        }
    }

    /** Баланс EXC в основной базе EGC; -1 - человека нет в основном боте (EXC у него нет вовсе). */
    private long excBalance(Long uid) {
        try {
            Optional<AppUser> u = userService.findByTelegramId(uid);
            return u.isPresent() ? u.get().getCoins() : -1;
        } catch (Exception e) {
            log.warn("VPN bot: EXC balance lookup failed for {}", uid, e);
            return -1;
        }
    }

    // ---------------------------------------------------------------- админ

    /** /vpn2 ID [trial|m1|m3|m12|off]: ручная выдача (в т.ч. после сбоя панели при оплате) и статус. Без действия - статус аккаунта. */
    private void handleAdminCommand(Long adminId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            sendHtml(adminId, "Формат: <code>/vpn2 ID</code> (статус), <code>/vpn2 ID trial</code>, <code>/vpn2 ID m1</code>, <code>m3</code>, <code>m12</code> (выдать срок), <code>/vpn2 ID off</code>.\n"
                    + "Статистика: <code>/stats</code>. Состояние: " + (vpn.isEnabled() ? "включён" : "выключен (VPN2_ENABLED и настройки панели)")
                    + ", " + (vpn.isOpen() ? "открыт всем" : "закрыт (админы и тестеры)"), null);
            return;
        }
        long targetId;
        try {
            targetId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            sendHtml(adminId, "⚠️ ID должен быть числом. Пример: <code>/vpn2 123456789 trial</code>", null);
            return;
        }
        String action = parts.length > 2 ? parts[2].toLowerCase() : "";
        try {
            switch (action) {
                case "trial" -> vpn.startTrial(targetId);
                case "off" -> vpn.disable(targetId);
                case "" -> { }
                default -> {
                    Optional<Tariff> tariff = Tariff.byKey(action);
                    if (tariff.isEmpty()) {
                        sendHtml(adminId, "⚠️ Действие: trial, m1, m3, m12 или off.", null);
                        return;
                    }
                    vpn.activatePaid(targetId, tariff.get(), "admin:" + adminId + ":" + System.currentTimeMillis());
                }
            }
        } catch (VpnException e) {
            sendHtml(adminId, "⚠️ VPN: " + escape(e.getMessage()) + " (" + e.getCode() + ")", null);
            return;
        } catch (Exception e) {
            log.warn("VPN bot admin command failed", e);
            sendHtml(adminId, "⚠️ VPN: непредвиденная ошибка, подробности в логе.", null);
            return;
        }
        Optional<Vpn2Account> account = vpn.find(targetId);
        if (account.isEmpty()) {
            sendHtml(adminId, "У игрока " + targetId + " нет VPN-аккаунта.", null);
            return;
        }
        Vpn2Account a = account.get();
        sendHtml(adminId, "🔐 <b>VPN игрока " + targetId + "</b>\nТариф: " + a.getPlanType() + ", статус: " + a.getStatus()
                + "\nДо: " + (a.getExpiresAt() == null ? "-" : DATE_TIME.format(a.getExpiresAt()))
                + "\nПодписка: " + escape(vpn.subscriptionUrl(a)), null);
        if (!action.isEmpty() && !"off".equals(action) && a.getExpiresAt() != null && vpn.isRunning(a)) {
            sendHtml(targetId, "🔐 <b>EGC VPN</b>\n\nВаш ключ обновлён, он работает до " + DATE_TIME.format(a.getExpiresAt()) + ".", null);
        }
    }

    // ---------------------------------------------------------------- напоминания

    /** Напоминания о сроке: раз в час, за 2 дня до конца и после окончания (не позже чем через 3 дня); не чаще двух на один срок (reminderStage в аккаунте).
     *  Бэклог: до запуска аккаунтов нет, а окно «истёк не позже 3 дней назад» не даёт слать про давно закончившееся. */
    @Scheduled(initialDelay = 5 * 60_000L, fixedDelay = 60 * 60_000L)
    public void remind() {
        if (!registered || !vpn.isEnabled()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Vpn2Account a : accountRepository.findByStatusAndReminderStageLessThanAndExpiresAtBetween(
                    Vpn2Account.STATUS_ACTIVE, 1, now, now.plusDays(REMIND_SOON_DAYS))) {
                remindOne(a, 1, "🔐 <b>EGC VPN скоро закончится</b>\nДействует до <b>" + a.getExpiresAt().format(DATE_SHORT) + "</b>. "
                        + (Vpn2Account.PLAN_TRIAL.equals(a.getPlanType()) ? "Понравилось? Оформите тариф, чтобы не потерять доступ." : "Продлите заранее, чтобы не остаться без VPN."),
                        "💳 Продлить");
            }
            for (Vpn2Account a : accountRepository.findByStatusAndReminderStageLessThanAndExpiresAtBetween(
                    Vpn2Account.STATUS_ACTIVE, 2, now.minusDays(REMIND_EXPIRED_WINDOW_DAYS), now)) {
                remindOne(a, 2, Vpn2Account.PLAN_TRIAL.equals(a.getPlanType())
                        ? "🔐 <b>Пробный период EGC VPN закончился</b>\nХотите продолжить? Месяц стоит 150 ₽, а за EXC дешевле."
                        : "🔐 <b>Срок EGC VPN истёк</b>\nПродлите, и ключ снова заработает.",
                        "💳 Оформить");
            }
        } catch (Exception e) {
            log.warn("VPN bot reminders failed", e);
        }
    }

    private void remindOne(Vpn2Account a, int stage, String html, String button) {
        try {
            a.setReminderStage(stage);
            accountRepository.save(a); // сначала отметка: при сбое отправки лучше пропустить напоминание, чем слать его каждый час
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(keyboardFactory.callback(button, "m:tariffs")));
            sendHtml(a.getTelegramId(), html, rows);
        } catch (Exception e) {
            log.warn("VPN bot reminder failed for {}", a.getTelegramId(), e);
        }
    }

    // ---------------------------------------------------------------- отправка

    /** Новый экран, затем удаление старого сообщения (лента не растёт). Сбой удаления (старше 48 ч и т.п.) не мешает. */
    private void show(Long chatId, Integer oldMsgId, String html, List<List<InlineKeyboardButton>> rows) {
        Integer sent = sendHtml(chatId, html, rows);
        if (sent != null && oldMsgId != null) {
            try {
                execute(new DeleteMessage(chatId.toString(), oldMsgId));
            } catch (TelegramApiException e) {
                log.debug("VPN bot: failed to delete old message {} in {}", oldMsgId, chatId);
            }
        }
    }

    /** Отправка HTML-сообщения; id отправленного или null (человек заблокировал бота и т.п.). */
    private Integer sendHtml(Long chatId, String html, List<List<InlineKeyboardButton>> rows) {
        SendMessage m = new SendMessage();
        m.setChatId(chatId.toString());
        m.setText(html);
        m.setParseMode("HTML");
        m.setDisableWebPagePreview(true);
        if (rows != null) {
            m.setReplyMarkup(keyboardFactory.rowsLayout(rows));
        }
        try {
            return execute(m).getMessageId();
        } catch (TelegramApiException e) {
            log.warn("VPN bot: failed to send message to {}: {}", chatId, e.getMessage());
            return null;
        }
    }

    private void answerSilently(String callbackId) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackId);
        try {
            execute(answer);
        } catch (TelegramApiException e) {
            log.debug("VPN bot: failed to answer callback {}", callbackId);
        }
    }

    /** Алерт админам через основной бот (админы могли ни разу не открывать бот VPN). */
    private void alertAdmins(String html) {
        try {
            eventPublisher.publishEvent(new AnalyticsAlertEvent(this, html));
        } catch (Exception e) {
            log.warn("VPN bot: failed to publish admin alert", e);
        }
    }

    private String egcUrl() {
        return "https://t.me/" + appProperties.getBotUsername();
    }

    private static String formatExc(long amount) {
        return String.format("%,d", amount).replace(',', ' ');
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
