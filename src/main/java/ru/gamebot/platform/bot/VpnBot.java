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
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
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
import ru.gamebot.platform.domain.model.Vpn2CryptoInvoice;
import ru.gamebot.platform.domain.model.Vpn2Feedback;
import ru.gamebot.platform.domain.model.Vpn2ManualPayment;
import ru.gamebot.platform.domain.model.Vpn2Payment;
import ru.gamebot.platform.domain.repository.Vpn2AccountRepository;
import ru.gamebot.platform.domain.repository.Vpn2CryptoInvoiceRepository;
import ru.gamebot.platform.domain.repository.Vpn2FeedbackRepository;
import ru.gamebot.platform.domain.repository.Vpn2ManualPaymentRepository;
import ru.gamebot.platform.event.AnalyticsAlertEvent;
import ru.gamebot.platform.event.Vpn2TechAlertEvent;
import ru.gamebot.platform.service.AdminService;
import ru.gamebot.platform.service.CryptoPayClient;
import ru.gamebot.platform.service.ExcTransactionService;
import ru.gamebot.platform.service.TrafficSourceService;
import ru.gamebot.platform.service.UserService;
import ru.gamebot.platform.service.Vpn2HealthService;
import ru.gamebot.platform.service.Vpn2Service;
import ru.gamebot.platform.service.Vpn2Service.Tariff;
import ru.gamebot.platform.service.VpnPanelClient;
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
    private static final String TRAFFIC_SOURCE_CODE = "egc_vpn";
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
    private final TrafficSourceService trafficSourceService;
    private final Vpn2AccountRepository accountRepository;
    private final Vpn2FeedbackRepository feedbackRepository;
    private final Vpn2ManualPaymentRepository manualPaymentRepository;
    private final Vpn2CryptoInvoiceRepository cryptoInvoiceRepository;
    private final CryptoPayClient cryptoPay;
    private final Vpn2HealthService health;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
    /** Ссылки на инвойс не зависят от человека (товар и цена те же), поэтому кэшируем: экран тарифов не ходит в Telegram за каждой ссылкой. */
    private final Map<String, String> invoiceLinks = new ConcurrentHashMap<>();
    private volatile boolean registered = false;
    /** Кто сейчас пишет текст отзыва: telegramId -> id записи отзыва (в памяти, при перезапуске теряется - человек просто нажмёт кнопку заново). */
    private final Map<Long, Long> awaitingReviewText = new ConcurrentHashMap<>();
    /** Кто сейчас платит EXC: защита от двойного нажатия «Оплатить» (двойное списание). */
    private final java.util.Set<Long> excPayInProgress = ConcurrentHashMap.newKeySet();
    private final Object cryptoLock = new Object();

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
            ensureTrafficSource();
            setupCommandMenu();
        } catch (Exception e) {
            log.error("EGC VPN bot registration failed (main bot is not affected)", e);
        }
    }

    /** Меню «/»: главное меню, вопросы по оплате и условия (Telegram требует /paysupport и /terms у ботов с платежами). */
    private void setupCommandMenu() {
        try {
            execute(org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands.builder()
                    .commands(List.of(
                            org.telegram.telegrambots.meta.api.objects.commands.BotCommand.builder().command("start").description("Главное меню").build(),
                            org.telegram.telegrambots.meta.api.objects.commands.BotCommand.builder().command("paysupport").description("Вопросы по оплате и возврат").build(),
                            org.telegram.telegrambots.meta.api.objects.commands.BotCommand.builder().command("terms").description("Условия сервиса").build()))
                    .scope(org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault.builder().build())
                    .build());
        } catch (Exception e) {
            log.warn("VPN bot command menu not set: {}", e.getMessage());
        }
    }

    /** Источник трафика «egc_vpn» для ссылок из VPN-бота в игровой (?start=src_egc_vpn; до 05.11 был код vpnbot, его статистика осталась в разделе трафика): клики и новые игроки видны в разделе трафика основного бота. */
    private void ensureTrafficSource() {
        try {
            if (trafficSourceService.findByCode(TRAFFIC_SOURCE_CODE).isEmpty()) {
                trafficSourceService.create("EGC VPN бот", TRAFFIC_SOURCE_CODE);
            }
        } catch (Exception e) {
            log.warn("VPN bot: failed to ensure traffic source {}", TRAFFIC_SOURCE_CODE, e);
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
        if (message.hasPhoto() || message.hasDocument()) {
            handleProof(uid, message);
            return;
        }
        String text = message.getText() == null ? "" : message.getText().trim();
        if (text.startsWith("/paysupport")) {
            showPaySupport(uid);
            return;
        }
        if (text.startsWith("/terms")) {
            showTerms(uid, null);
            return;
        }
        if (isAdmin(uid) && text.startsWith("/vpn2refund")) {
            handleRefundCommand(uid, text);
            return;
        }
        if (isStaff(uid) && text.startsWith("/panel")) {
            showPanel(uid, null);
            return;
        }
        if (isAdmin(uid) && text.startsWith("/stats")) {
            sendHtml(uid, vpn.statsHtml() + "\n\n" + vpn.paymentMethodsHtml() + "\nПереводы ждут подтверждения: <b>"
                    + manualPaymentRepository.countByStatus(Vpn2ManualPayment.STATUS_PENDING) + "</b>\n\n" + feedbackStatsHtml(), null);
            return;
        }
        if (isAdmin(uid) && text.startsWith("/vpn2")) {
            handleAdminCommand(uid, text);
            return;
        }
        Long awaitingFeedbackId = awaitingReviewText.remove(uid);
        if (awaitingFeedbackId != null && !text.startsWith("/") && !text.isBlank()) {
            saveReviewText(uid, awaitingFeedbackId, text);
            return;
        }
        Long referrer = null;
        String source = null;
        if (text.startsWith("/start")) {
            String[] parts = text.split("\\s+");
            if (parts.length > 1 && parts[1].startsWith("ref_")) {
                try {
                    referrer = Long.parseLong(parts[1].substring(4));
                } catch (NumberFormatException ignored) {
                    // битая ссылка: просто без реферера
                }
            } else if (parts.length > 1 && "from_egc".equals(parts[1])) {
                source = "egc"; // пришёл из игрового бота EGC
            } else if (parts.length > 1 && parts[1].startsWith("from_bc_")) {
                source = parts[1].substring(5); // рассылка про VPN: bc_a / bc_b (волна 1, варианты текста), bc_w2 (волна 2)
            }
        }
        vpn.registerUser(uid, from.getFirstName(), from.getUserName(), referrer, source);
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
        return vpn.isEnabled() && (vpn.isOpen() || vpn.isTester(uid) || isAdmin(uid) || vpn.isTech(uid));
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
        vpn.registerUser(uid, from.getFirstName(), from.getUserName(), null, null);
        if (!allowed(uid)) {
            showClosed(uid, oldMsgId);
            return;
        }
        if (!cq.getData().startsWith("fb:")) {
            awaitingReviewText.remove(uid); // ушёл в другой раздел: следующее сообщение не считаем текстом отзыва
        }
        if (cq.getData().startsWith("m:") && handlePayCallback(uid, from, oldMsgId, cq.getData())) {
            return;
        }
        if (cq.getData().startsWith("p:")) {
            handlePanelCallback(uid, oldMsgId, cq.getData());
            return;
        }
        if (cq.getData().startsWith("adm:")) {
            handleAdminCallback(uid, oldMsgId, cq.getData());
            return;
        }
        if (cq.getData().startsWith("fbadm:")) {
            handleFeedbackModeration(uid, oldMsgId, cq.getData());
            return;
        }
        if (cq.getData().startsWith("fb:")) {
            handleFeedbackCallback(uid, oldMsgId, cq.getData());
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
            case "h:diag" -> showDiagnosis(uid, oldMsgId);
            case "h:qr" -> sendQr(uid);
            case "h:ok" -> showHelpOk(uid, oldMsgId);
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
        if (isStaff(uid)) {
            rows.add(List.of(keyboardFactory.callback("🛠 Панель", "p:menu")));
        }
        rows.add(List.of(keyboardFactory.url("🆘 Поддержка", "https://t.me/" + appProperties.getSupportUsername())));
        rows.add(List.of(keyboardFactory.url("🎮 Играть и копить EXC на скидку", egcUrl())));
        show(uid, oldMsgId, "🔐 <b>EGC VPN</b>\n\n"
                + "Быстрый VPN для телефона и компьютера от клуба EGC.\n"
                + "• До 2 устройств, подключение за минуту\n"
                + "• Личный ключ: он только ваш\n"
                + "• Работает в России и за границей\n\n"
                + "<b>От 50 ₽ в неделю</b>, а первые " + daysText(vpn.getTrialDays()) + " бесплатно.", rows);
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
            sb.append("Ключа пока нет. Возьмите пробный на ").append(daysText(vpn.getTrialDays())).append(" бесплатно или оформите тариф.");
            rows.add(List.of(keyboardFactory.callback("🎁 " + daysText(vpn.getTrialDays()) + " бесплатно", "m:trial")));
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
          .append(trial && a.getLimitNotifiedAt() != null ? "⚠️ Пробный лимит трафика <b>исчерпан</b>: оформите тариф, чтобы продолжить.\n\n" : "")
          .append(connectSteps()).append("\n\n")
          .append("Ваша личная ссылка (никому не показывайте):\n<code>").append(escape(vpn.subscriptionUrl(a))).append("</code>\n\n")
          .append("🎮 <i>Играйте в EGC, копите EXC и платите за VPN дешевле: ").append(formatExc(Vpn2Service.EXC_DISCOUNT)).append(" EXC = −50 ₽.</i>");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(installRows());
        rows.add(List.of(keyboardFactory.callback("🛠 Не подключается?", "h:diag"), keyboardFactory.callback("📷 QR-код", "h:qr")));
        rows.add(List.of(keyboardFactory.callback(trial ? "💳 Оформить тариф" : "💳 Продлить", "m:tariffs"),
                keyboardFactory.url("🎮 Копить EXC", egcUrl())));
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
        rows.add(List.of(keyboardFactory.callback("🛠 Не подключается?", "h:diag"), keyboardFactory.callback("📷 QR-код", "h:qr")));
        rows.add(List.of(keyboardFactory.callback("🔑 Моё подключение", "m:my")));
        rows.add(List.of(keyboardFactory.url("🆘 Поддержка", "https://t.me/" + appProperties.getSupportUsername())));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, "📲 <b>Как подключить</b>\n\n" + connectSteps()
                + "\n\nСсылку подписки берите в разделе «Моё подключение». Если что-то не получается, напишите в поддержку, поможем.", rows);
    }

    /** «7 дней», «1 день», «3 дня»: число с правильным склонением (срок пробного настраивается). */
    private static String daysText(int n) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        String word = mod100 >= 11 && mod100 <= 14 ? "дней" : mod10 == 1 ? "день" : mod10 >= 2 && mod10 <= 4 ? "дня" : "дней";
        return n + " " + word;
    }

    private void showTariffs(Long uid, Integer oldMsgId) {
        StringBuilder sb = new StringBuilder("💳 <b>Тарифы EGC VPN</b>\n\n");
        for (Tariff t : Tariff.values()) {
            sb.append("• ").append(t.getTitle()).append(": <b>").append(t.getPriceRub()).append(" ₽</b> (").append(t.getStars()).append(" ⭐)");
            if (t != Tariff.W1) {
                // выгода считается к цене недель за тот же срок (50 ₽ в неделю), а не к месячной цене
                double full = Tariff.W1.getPriceRub() * (t.getDays() / 7.0);
                sb.append(", выгода ").append(Math.round(100.0 * (full - t.getPriceRub()) / full)).append("%");
            }
            sb.append("\n");
        }
        sb.append("\nБезлимитный трафик, до 2 устройств. Оплата звёздами Telegram ⭐.");
        long coins = excBalance(uid);
        sb.append("\n\n").append(excCard(coins));
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
        if (coins >= Vpn2Service.EXC_DISCOUNT) {
            for (Tariff t : Tariff.values()) {
                if (!t.hasExcDiscount()) {
                    continue; // недельный тариф без скидки за EXC: −50 ₽ обнулила бы цену
                }
                String link = invoiceLink(t, true);
                if (link != null) {
                    rows.add(List.of(keyboardFactory.url("🪙 −50 ₽ за EXC: " + t.getTitle() + ", " + t.getExcStars() + " ⭐", link)));
                }
            }
        } else {
            rows.add(List.of(keyboardFactory.url("🎮 Заработать EXC в EGC", egcUrl())));
        }
        if (linkFailed) {
            log.warn("VPN bot: failed to create Stars invoice link for tariffs screen, user {}", uid);
            sb.append("\n\n⚠️ Часть кнопок оплаты временно недоступна, попробуйте через минуту.");
        }
        if (hasExtraPayMethods()) {
            rows.add(List.of(keyboardFactory.callback("💳 Другие способы оплаты", "m:pay")));
        }
        rows.add(List.of(keyboardFactory.callback("ℹ️ Условия", "m:terms")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, sb.toString(), rows);
    }

    /** Карточка скидки за EXC: прогресс до 18 000 EXC у игрока EGC, «скидка доступна» или объяснение для тех, у кого профиля EGC ещё нет. */
    private static String excCard(long coins) {
        long goal = Vpn2Service.EXC_DISCOUNT;
        if (coins >= goal) {
            return "🪙 <b>Скидка за EXC доступна</b>\nУ вас " + formatExc(coins) + " EXC: платите на 50 ₽ меньше за любой срок (кнопки ниже).";
        }
        if (coins >= 0) {
            long pct = Math.min(100, coins * 100 / goal);
            return "🪙 <b>Скидка за EXC</b>\nУ вас " + formatExc(coins) + " из " + formatExc(goal) + " EXC\n"
                    + progressBar(coins, goal) + " " + pct + "%\n"
                    + "Ещё " + formatExc(goal - coins) + " EXC, и −50 ₽ на любой срок.";
        }
        return "🪙 <b>Скидка за EXC</b>\nEXC копятся за игры и задания в EGC: " + formatExc(goal) + " EXC = −50 ₽ на любой срок.";
    }

    private static String progressBar(long current, long goal) {
        int filled = (int) Math.max(0, Math.min(10, current * 10 / goal));
        return "▰".repeat(filled) + "▱".repeat(10 - filled);
    }

    private void showTerms(Long uid, Integer oldMsgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("🆘 Поддержка", "https://t.me/" + appProperties.getSupportUsername())));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "m:tariffs"), keyboardFactory.callback("🏠 Меню", "m:main")));
        show(uid, oldMsgId, "ℹ️ <b>Условия EGC VPN</b>\n\n"
                + "Мы сохраняем технические данные подключения (время, IP и объём трафика) 90 дней для безопасности и поддержки. "
                + "Адреса посещаемых сайтов мы не сохраняем.\n\n"
                + "Один ключ на аккаунт, передавать его другим нельзя. Пробный период выдаётся один раз.\n\n"
                + "Оплата: звёзды Telegram, списываются один раз за выбранный срок. Автопродления нет, о конце срока мы напомним.\n"
                + "Возврат и вопросы по оплате: /paysupport.", rows);
    }

    /** /paysupport: Telegram требует от ботов с платежами ответ, куда писать по вопросам оплаты. */
    private void showPaySupport(Long uid) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("🆘 Написать в поддержку", "https://t.me/" + appProperties.getSupportUsername())));
        sendHtml(uid, "💳 <b>Вопросы по оплате</b>\n\n"
                + "Если платёж прошёл, а ключ не выдан, или нужен возврат, напишите в поддержку и укажите ваш ID <code>" + uid + "</code>, дату и тариф. "
                + "Мы всё проверим и вернём звёзды, если вы получили не то, что оплатили.", rows);
    }

    private void showReferral(Long uid, Integer oldMsgId) {
        String link = "https://t.me/" + vpn.getBotUsername() + "?start=ref_" + uid;
        // URLEncoder кодирует пробел как «+», а в t.me/share/url плюс остаётся плюсом: заменяем на %20
        String share = "https://t.me/share/url?url=" + URLEncoder.encode(link, StandardCharsets.UTF_8).replace("+", "%20")
                + "&text=" + URLEncoder.encode("EGC VPN: быстрый VPN для телефона и компьютера. Первые " + daysText(vpn.getTrialDays()) + " бесплатно!",
                        StandardCharsets.UTF_8).replace("+", "%20");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("📤 Отправить другу", share)));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, "🎁 <b>Пригласите друга</b>\n\n"
                + "Отправьте другу свою ссылку. Когда он оформит VPN за звёзды, вы оба получите <b>+" + Vpn2Service.REFERRAL_BONUS_DAYS + " дней</b>.\n\n"
                + "Ваша ссылка:\n<code>" + escape(link) + "</code>", rows);
    }

    /** Пробный период: нужна подписка на канал EGC (проверяет основной бот, он админ канала); один раз на аккаунт (проверяет Vpn2Service). */
    private void startTrial(Long uid, Integer oldMsgId) {
        Boolean member = egcBot.checkRequiredChannelForVpnBot(uid);
        if (Boolean.FALSE.equals(member)) {
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            String channelUrl = egcBot.requiredChannelJoinUrl();
            if (channelUrl != null && !channelUrl.isBlank()) {
                rows.add(List.of(keyboardFactory.url("📢 Подписаться", channelUrl)));
            }
            rows.add(List.of(keyboardFactory.callback("✅ Я подписался", "m:trial")));
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "m:my")));
            show(uid, oldMsgId, "🎁 <b>Пробный VPN на " + daysText(vpn.getTrialDays()) + ", бесплатно</b>\n\nЧтобы получить ключ, подпишитесь на наш канал.", rows);
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
                    + "Перенос всех ключей в текущую панель после смены настроек: <code>/vpn2 sync</code>. Статистика: <code>/stats</code>. Состояние: " + (vpn.isEnabled() ? "включён" : "выключен (VPN2_ENABLED и настройки панели)")
                    + ", " + (vpn.isOpen() ? "открыт всем" : "закрыт (админы и тестеры)"), null);
            return;
        }
        if ("sync".equalsIgnoreCase(parts[1])) {
            int moved = vpn.resyncAll();
            sendHtml(adminId, "🔄 Работающих ключей перенесено в панель: <b>" + moved + "</b>. Игрокам нужно заново добавить подписку по новой ссылке (она в «Моё подключение»).", null);
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
                        sendHtml(adminId, "⚠️ Действие: trial, w1, m1, m3, m12 или off.", null);
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
                        ? "🔐 <b>Пробный период EGC VPN закончился</b>\nХотите продолжить? Неделя стоит 50 ₽, месяц 150 ₽, а за EXC дешевле."
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
            long coins = excBalance(a.getTelegramId());
            String excLine = coins >= Vpn2Service.EXC_DISCOUNT
                    ? "\n\n🪙 У вас " + formatExc(coins) + " EXC: продлите со скидкой 50 ₽."
                    : coins >= 0
                            ? "\n\n🪙 До скидки 50 ₽ не хватает " + formatExc(Vpn2Service.EXC_DISCOUNT - coins) + " EXC."
                            : "";
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(keyboardFactory.callback(button, "m:tariffs")));
            if (coins >= 0 && coins < Vpn2Service.EXC_DISCOUNT) {
                rows.add(List.of(keyboardFactory.url("🎮 Заработать EXC", egcUrl())));
            }
            sendHtml(a.getTelegramId(), html + excLine, rows);
        } catch (Exception e) {
            log.warn("VPN bot reminder failed for {}", a.getTelegramId(), e);
        }
    }

    // ---------------------------------------------------------------- другие способы оплаты

    /** Маршрутизация кнопок способов оплаты; true - кнопка обработана здесь. */
    private boolean handlePayCallback(Long uid, User from, Integer oldMsgId, String data) {
        if (data.equals("m:pay")) {
            showPayMethods(uid, oldMsgId);
        } else if (data.startsWith("m:soon:")) {
            showMethodSoon(uid, oldMsgId, data.substring("m:soon:".length()));
        } else if (data.equals("m:exc")) {
            showExcPay(uid, oldMsgId);
        } else if (data.startsWith("m:excok:")) {
            confirmExcPay(uid, oldMsgId, data.substring("m:excok:".length()));
        } else if (data.startsWith("m:excgo:")) {
            payWithExc(uid, from, oldMsgId, data.substring("m:excgo:".length()));
        } else if (data.equals("m:card")) {
            showCardPay(uid, oldMsgId);
        } else if (data.startsWith("m:cardt:")) {
            startCardPayment(uid, oldMsgId, data.substring("m:cardt:".length()));
        } else if (data.equals("m:cardcancel")) {
            cancelCardPayment(uid, oldMsgId);
        } else if (data.equals("m:crypto")) {
            showCryptoPay(uid, oldMsgId);
        } else if (data.startsWith("m:cryt:")) {
            startCryptoPayment(uid, oldMsgId, data.substring("m:cryt:".length()));
        } else if (data.startsWith("m:cryc:")) {
            checkCryptoPayment(uid, oldMsgId, data.substring("m:cryc:".length()));
        } else {
            return false;
        }
        return true;
    }

    /** Есть ли хоть один способ оплаты кроме Stars, который реально включён (EXC, перевод по реквизитам, CryptoBot). Недоделанные способы игроку не показываем вовсе. */
    private boolean hasExtraPayMethods() {
        return vpn.isExcPayEnabled() || vpn.isCardPayEnabled() || vpn.isCryptoPayEnabled();
    }

    /** «Другие способы оплаты»: Stars всегда; EXC, карта/СБП и крипта - только те, что включены (остальные скрыты до запуска). */
    private void showPayMethods(Long uid, Integer oldMsgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("⭐ Звёзды Telegram", "m:tariffs")));
        if (vpn.isExcPayEnabled()) {
            rows.add(List.of(keyboardFactory.callback("🪙 Оплатить EXC", "m:exc")));
        }
        if (vpn.isCardPayEnabled()) {
            rows.add(List.of(keyboardFactory.callback("🏦 Карта / СБП (рубли)", "m:card")));
        }
        if (vpn.isCryptoPayEnabled()) {
            rows.add(List.of(keyboardFactory.callback("₿ Крипта (TON, USDT)", "m:crypto")));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Тарифы", "m:tariffs"), keyboardFactory.callback("🏠 Меню", "m:main")));
        show(uid, oldMsgId, "💳 <b>Способы оплаты</b>\n\nВыберите, как удобнее заплатить. Ключ приходит сюда сразу после оплаты, а при переводе по реквизитам после проверки перевода.", rows);
    }

    private void showMethodSoon(Long uid, Integer oldMsgId, String method) {
        StringBuilder sb = new StringBuilder();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        switch (method) {
            case "exc" -> {
                sb.append("🪙 <b>Оплата EXC</b>\n\nСкоро откроется: можно будет оплатить тариф целиком монетами EXC, без звёзд и карты.\n\nПланируемые цены:\n");
                for (Tariff t : Tariff.values()) {
                    sb.append("• ").append(t.getTitle()).append(": ").append(formatExc(t.getExcOnlyPrice())).append(" EXC\n");
                }
                sb.append("\nКопите EXC за игры и задания в EGC.");
                rows.add(List.of(keyboardFactory.url("🎮 Копить EXC", egcUrl())));
            }
            case "card" -> sb.append("🏦 <b>Карта / СБП</b>\n\nОплата рублями переводом скоро откроется.");
            case "crypto" -> sb.append("₿ <b>Крипта</b>\n\nОплата в TON и USDT через CryptoBot скоро откроется.");
            default -> sb.append("Этот способ оплаты скоро откроется.");
        }
        if (!"exc".equals(method)) {
            sb.append("\n\nПока можно оплатить звёздами Telegram.");
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Способы оплаты", "m:pay"), keyboardFactory.callback("⭐ Звёзды", "m:tariffs")));
        show(uid, oldMsgId, sb.toString(), rows);
    }

    // ---- EXC целиком

    private void showExcPay(Long uid, Integer oldMsgId) {
        if (!vpn.isExcPayEnabled()) {
            showMethodSoon(uid, oldMsgId, "exc");
            return;
        }
        long coins = excBalance(uid);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        StringBuilder sb = new StringBuilder("🪙 <b>Оплата EXC</b>\n\n");
        if (coins < 0) {
            sb.append("Для оплаты EXC нужен профиль в игровом боте EGC: там вы копите монеты за игры и задания.");
            rows.add(List.of(keyboardFactory.url("🎮 Открыть EGC", egcUrl())));
        } else {
            sb.append("Ваш баланс: <b>").append(formatExc(coins)).append(" EXC</b>\nВыберите срок:");
            for (Tariff t : Tariff.values()) {
                boolean enough = coins >= t.getExcOnlyPrice();
                rows.add(List.of(keyboardFactory.callback((enough ? "🪙 " : "🔒 ") + t.getTitle() + ": " + formatExc(t.getExcOnlyPrice()) + " EXC",
                        enough ? "m:excok:" + t.getKey() : "m:noop")));
            }
            rows.add(List.of(keyboardFactory.url("🎮 Копить EXC", egcUrl())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Способы оплаты", "m:pay")));
        show(uid, oldMsgId, sb.toString(), rows);
    }

    private void confirmExcPay(Long uid, Integer oldMsgId, String key) {
        Optional<Tariff> tariff = Tariff.byKey(key);
        if (!vpn.isExcPayEnabled() || tariff.isEmpty()) {
            showPayMethods(uid, oldMsgId);
            return;
        }
        Tariff t = tariff.get();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("✅ Оплатить", "m:excgo:" + t.getKey()), keyboardFactory.callback("Отмена", "m:exc")));
        show(uid, oldMsgId, "🪙 <b>Подтвердите оплату</b>\n\n" + t.getTitle() + ": списать <b>" + formatExc(t.getExcOnlyPrice()) + " EXC</b>. Ключ заработает сразу.", rows);
    }

    /** Оплата тарифа целиком за EXC: сначала списываем (под блокировкой строки игрока), затем выдаём ключ; если выдача не удалась - возвращаем EXC и зовём админов. Повторный тап блокируется. */
    private void payWithExc(Long uid, User from, Integer oldMsgId, String key) {
        Optional<Tariff> tariff = Tariff.byKey(key);
        if (!vpn.isExcPayEnabled() || tariff.isEmpty()) {
            showPayMethods(uid, oldMsgId);
            return;
        }
        if (!excPayInProgress.add(uid)) {
            return;
        }
        Tariff t = tariff.get();
        long price = t.getExcOnlyPrice();
        try {
            Optional<AppUser> appUser = userService.findByTelegramId(uid);
            if (appUser.isEmpty()) {
                showExcPay(uid, oldMsgId);
                return;
            }
            AppUser u = appUser.get();
            excTransactionService.refreshCoins(u);
            if (u.getCoins() < price) {
                showExcPay(uid, oldMsgId);
                return;
            }
            excTransactionService.creditExc(u, -price, ExcTransactionService.SHOP_BUY, "EGC VPN (бот): оплата EXC, " + t.getTitle());
            Vpn2Account account;
            String ref = "exc:" + uid + ":" + System.currentTimeMillis();
            try {
                account = vpn.activatePaid(uid, t, ref);
            } catch (VpnException e) {
                excTransactionService.creditExc(u, price, ExcTransactionService.SHOP_REFUND, "EGC VPN (бот): возврат EXC, ключ не выдан");
                alertAdmins("🚨 <b>VPN-бот: оплата EXC не удалась</b>\nИгрок <code>" + uid + "</code>, " + escape(t.getTitle()) + ": ключ не выдан, EXC (" + formatExc(price) + ") возвращены.");
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                rows.add(List.of(keyboardFactory.callback("⬅️ Способы оплаты", "m:pay")));
                show(uid, oldMsgId, "⚠️ Не получилось выдать ключ. EXC возвращены на баланс, попробуйте через несколько минут.", rows);
                return;
            }
            finishAltPurchase(uid, from == null ? null : from.getFirstName(), t, account, "EXC", 0, price, ref, " (" + formatExc(price) + " EXC)");
        } catch (Exception e) {
            log.error("VPN bot: EXC payment failed, user {}", uid, e);
            alertAdmins("🚨 <b>VPN-бот: сбой оплаты EXC</b>\nИгрок <code>" + uid + "</code>, " + escape(t.getTitle()) + ". Проверьте баланс EXC и ключ вручную.\n" + escape(String.valueOf(e.getMessage())));
            sendHtml(uid, "⚠️ Что-то пошло не так при оплате. Мы уже знаем и всё проверим, напишите в поддержку, если ключ не пришёл: /paysupport.", null);
        } finally {
            excPayInProgress.remove(uid);
        }
    }

    /** Общий финал для способов оплаты кроме Stars (EXC, перевод, крипта): журнал, рефералка, уведомление админам, экран ключа. Ключ уже выдан. */
    private void finishAltPurchase(Long uid, String firstName, Tariff tariff, Vpn2Account account, String method, int rubAmount, long excSpent, String ref, String amountNote) {
        try {
            vpn.recordPayment(uid, tariff, 0, excSpent, ref, method, rubAmount);
        } catch (Exception e) {
            log.error("VPN bot: failed to record {} payment {}", method, ref, e); // журнал вторичен: ключ уже выдан
        }
        String bonusNote = "";
        Vpn2Account shown = account;
        try {
            Long referrer = vpn.rewardReferral(uid);
            if (referrer != null) {
                bonusNote = "\n🎁 За приглашение вам начислено +" + Vpn2Service.REFERRAL_BONUS_DAYS + " дней.";
                sendHtml(referrer, "🎁 <b>Ваш друг оформил VPN</b>\n\nВам начислено +" + Vpn2Service.REFERRAL_BONUS_DAYS + " дней к вашему ключу.", null);
                shown = vpn.find(uid).orElse(account);
            }
        } catch (Exception e) {
            log.error("VPN bot: referral reward failed for buyer {}", uid, e);
        }
        alertAdmins("🔐 <b>Покупка в VPN-боте (" + escape(method) + ")</b>: " + escape(firstName == null ? "" : firstName) + " (<code>" + uid + "</code>), "
                + escape(tariff.getTitle()) + escape(amountNote));
        showKey(uid, null, shown, "✅ <b>Оплачено!</b> VPN работает до " + DATE_TIME.format(shown.getExpiresAt()) + bonusNote);
    }

    // ---- перевод рублями (карта / СБП по реквизитам), подтверждает админ

    private void showCardPay(Long uid, Integer oldMsgId) {
        if (!vpn.isCardPayEnabled()) {
            showMethodSoon(uid, oldMsgId, "card");
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Tariff t : Tariff.values()) {
            rows.add(List.of(keyboardFactory.callback("🏦 " + t.getTitle() + ": " + t.getPriceRub() + " ₽", "m:cardt:" + t.getKey())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Способы оплаты", "m:pay")));
        if (vpn.isCardManagerMode()) {
            show(uid, oldMsgId, "🏦 <b>Оплата рублями (карта / СБП)</b>\n\nРеквизиты вам пришлёт менеджер лично. Выберите срок:", rows);
            return;
        }
        show(uid, oldMsgId, "🏦 <b>Перевод рублями (карта / СБП)</b>\n\nВыберите срок, мы покажем реквизиты. После перевода пришлите чек, и ключ придёт после проверки.", rows);
    }

    private String cardManagerUsername() {
        return vpn.getManagerUsername().isBlank() ? appProperties.getSupportUsername() : vpn.getManagerUsername();
    }

    /** Экран оплаты рублями через менеджера: четыре шага и кнопка «Написать менеджеру» с готовым текстом. */
    private void showCardManagerScreen(Long uid, Integer oldMsgId, Tariff t) {
        String manager = cardManagerUsername();
        String text = "Здравствуйте! Хочу оплатить VPN на " + t.getTitle().toLowerCase() + " (" + t.getPriceRub() + " ₽) переводом. Мой ID: " + uid + ". Пришлите, пожалуйста, реквизиты.";
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("💬 Написать менеджеру", "https://t.me/" + manager + "?text=" + encoded)));
        rows.add(List.of(keyboardFactory.callback("❌ Отмена", "m:cardcancel")));
        show(uid, oldMsgId, "🏦 <b>Оплата рублями: " + escape(t.getTitle()) + ", " + t.getPriceRub() + " ₽</b>\n\n"
                + "1️⃣ Нажмите «Написать менеджеру», сообщение уже готово, останется отправить.\n"
                + "2️⃣ Менеджер пришлёт реквизиты (карта или СБП).\n"
                + "3️⃣ Переведите и отправьте менеджеру чек.\n"
                + "4️⃣ Ключ придёт сюда, в этот бот, обычно в течение нескольких часов.\n\n"
                + "⚠️ Реквизиты присылает только менеджер @" + escape(manager) + ". Если пишет кто-то другой, не переводите.", rows);
    }

    /** Заявка на оплату через менеджера: одна открытая на игрока (до 24 часов), админам уходит уведомление с кнопками подтверждения. */
    private void startCardManagerPayment(Long uid, Integer oldMsgId, Tariff t) {
        Optional<Vpn2ManualPayment> open = manualPaymentRepository.findFirstByTelegramIdAndStatusOrderByCreatedAtDesc(uid, Vpn2ManualPayment.STATUS_PENDING);
        if (open.isPresent()) {
            Vpn2ManualPayment old = open.get();
            if (old.getCreatedAt() != null && old.getCreatedAt().isAfter(LocalDateTime.now().minusHours(24))) {
                showCardManagerScreen(uid, oldMsgId, Tariff.byKey(old.getTariff()).orElse(t));
                return;
            }
            old.setStatus(Vpn2ManualPayment.STATUS_REJECTED);
            old.setDecidedAt(LocalDateTime.now());
            manualPaymentRepository.save(old);
        }
        Vpn2ManualPayment m = new Vpn2ManualPayment();
        m.setTelegramId(uid);
        m.setTariff(t.getKey());
        m.setAmountRub(t.getPriceRub());
        m.setCreatedAt(LocalDateTime.now());
        m.setStatus(Vpn2ManualPayment.STATUS_PENDING);
        m = manualPaymentRepository.save(m);
        showCardManagerScreen(uid, oldMsgId, t);
        List<List<InlineKeyboardButton>> adminRows = new ArrayList<>();
        adminRows.add(List.of(keyboardFactory.callback("✅ Подтвердить оплату", "adm:cok:" + m.getId()), keyboardFactory.callback("❌ Отклонить", "adm:cno:" + m.getId())));
        String note = "🏦 <b>Оплата рублями #" + m.getId() + "</b>\nИгрок <a href=\"tg://user?id=" + uid + "\">" + uid + "</a>, " + escape(t.getTitle()) + ", <b>" + t.getPriceRub()
                + " ₽</b>. Ему предложено написать менеджеру @" + escape(cardManagerUsername()) + ". Пришлите реквизиты, а когда деньги придут, нажмите «Подтвердить»: ключ уйдёт игроку сам.";
        for (Long adminId : adminService.resolvedAdminIds()) {
            try {
                sendHtml(adminId, note, adminRows);
            } catch (Exception e) {
                log.warn("VPN bot: failed to notify admin {} about manager payment", adminId, e);
            }
        }
    }

    private void startCardPayment(Long uid, Integer oldMsgId, String key) {
        Optional<Tariff> tariff = Tariff.byKey(key);
        if (!vpn.isCardPayEnabled() || tariff.isEmpty()) {
            showPayMethods(uid, oldMsgId);
            return;
        }
        if (vpn.isCardManagerMode()) {
            startCardManagerPayment(uid, oldMsgId, tariff.get());
            return;
        }
        if (manualPaymentRepository.findFirstByTelegramIdAndStatusOrderByCreatedAtDesc(uid, Vpn2ManualPayment.STATUS_PENDING).isPresent()) {
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(keyboardFactory.url("🆘 Поддержка", "https://t.me/" + appProperties.getSupportUsername())));
            rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
            show(uid, oldMsgId, "⏳ Ваш предыдущий перевод ещё проверяется. Дождитесь ответа или напишите в поддержку.", rows);
            return;
        }
        manualPaymentRepository.findFirstByTelegramIdAndStatusOrderByCreatedAtDesc(uid, Vpn2ManualPayment.STATUS_AWAITING_PROOF).ifPresent(old -> {
            old.setStatus(Vpn2ManualPayment.STATUS_REJECTED);
            old.setDecidedAt(LocalDateTime.now());
            manualPaymentRepository.save(old);
        });
        Tariff t = tariff.get();
        Vpn2ManualPayment m = new Vpn2ManualPayment();
        m.setTelegramId(uid);
        m.setTariff(t.getKey());
        m.setAmountRub(t.getPriceRub());
        m.setCreatedAt(LocalDateTime.now());
        m = manualPaymentRepository.save(m);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("❌ Отмена", "m:cardcancel")));
        show(uid, oldMsgId, "🏦 <b>Оплата " + escape(t.getTitle()) + ": " + t.getPriceRub() + " ₽</b>\n\n"
                + "Переведите <b>" + t.getPriceRub() + " ₽</b> по реквизитам:\n<code>" + escape(vpn.getCardDetails()) + "</code>\n\n"
                + "В комментарии к переводу укажите ваш ID: <code>" + uid + "</code>.\n\n"
                + "После перевода пришлите сюда <b>скриншот или файл чека</b> одним сообщением. Мы проверим перевод и пришлём ключ (обычно в течение нескольких часов).", rows);
    }

    private void cancelCardPayment(Long uid, Integer oldMsgId) {
        if (vpn.isCardManagerMode()) {
            manualPaymentRepository.findFirstByTelegramIdAndStatusOrderByCreatedAtDesc(uid, Vpn2ManualPayment.STATUS_PENDING).ifPresent(m -> {
                m.setStatus(Vpn2ManualPayment.STATUS_REJECTED);
                m.setDecidedAt(LocalDateTime.now());
                manualPaymentRepository.save(m);
            });
        }
        manualPaymentRepository.findFirstByTelegramIdAndStatusOrderByCreatedAtDesc(uid, Vpn2ManualPayment.STATUS_AWAITING_PROOF).ifPresent(m -> {
            m.setStatus(Vpn2ManualPayment.STATUS_REJECTED);
            m.setDecidedAt(LocalDateTime.now());
            manualPaymentRepository.save(m);
        });
        showPayMethods(uid, oldMsgId);
    }

    /** Фото или файл от игрока: если ждём чек, прикрепляем к заявке и отправляем админам с кнопками подтверждения. Вне ожидания чека молчим. */
    private void handleProof(Long uid, Message message) {
        Optional<Vpn2ManualPayment> found = manualPaymentRepository.findFirstByTelegramIdAndStatusOrderByCreatedAtDesc(uid, Vpn2ManualPayment.STATUS_AWAITING_PROOF);
        if (found.isEmpty()) {
            return;
        }
        Vpn2ManualPayment m = found.get();
        boolean isPhoto = message.hasPhoto() && !message.getPhoto().isEmpty();
        String fileId = isPhoto ? message.getPhoto().get(message.getPhoto().size() - 1).getFileId()
                : message.getDocument() != null ? message.getDocument().getFileId() : null;
        if (fileId == null) {
            return;
        }
        m.setProofFileId(fileId);
        m.setProofIsPhoto(isPhoto);
        m.setStatus(Vpn2ManualPayment.STATUS_PENDING);
        manualPaymentRepository.save(m);
        List<List<InlineKeyboardButton>> userRows = new ArrayList<>();
        userRows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        sendHtml(uid, "✅ <b>Чек получен.</b> Проверим перевод и пришлём ключ сюда. Обычно это занимает несколько часов.", userRows);
        String caption = "🏦 <b>Оплата переводом</b> #" + m.getId() + "\nИгрок <a href=\"tg://user?id=" + uid + "\">" + uid + "</a>, тариф " + escape(m.getTariff())
                + ", сумма <b>" + m.getAmountRub() + " ₽</b>.\nПроверьте поступление и подтвердите.";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("✅ Подтвердить", "adm:cok:" + m.getId()), keyboardFactory.callback("❌ Отклонить", "adm:cno:" + m.getId())));
        for (Long adminId : adminService.resolvedAdminIds()) {
            try {
                if (isPhoto) {
                    SendPhoto photo = new SendPhoto();
                    photo.setChatId(adminId.toString());
                    photo.setPhoto(new InputFile(fileId));
                    photo.setCaption(caption);
                    photo.setParseMode("HTML");
                    photo.setReplyMarkup(keyboardFactory.rowsLayout(rows));
                    execute(photo);
                } else {
                    org.telegram.telegrambots.meta.api.methods.send.SendDocument doc = new org.telegram.telegrambots.meta.api.methods.send.SendDocument();
                    doc.setChatId(adminId.toString());
                    doc.setDocument(new InputFile(fileId));
                    doc.setCaption(caption);
                    doc.setParseMode("HTML");
                    doc.setReplyMarkup(keyboardFactory.rowsLayout(rows));
                    execute(doc);
                }
            } catch (TelegramApiException e) {
                log.warn("VPN bot: failed to send proof to admin {}", adminId, e);
            }
        }
        alertAdmins("🏦 <b>Новая оплата переводом</b> #" + m.getId() + " от <code>" + uid + "</code>, " + m.getAmountRub() + " ₽. Подтвердите в VPN-боте (чек отправлен туда).");
    }

    /** Подтверждение или отклонение перевода админом (кнопки под чеком). */
    private void handleCardDecision(Long adminId, Integer oldMsgId, String action, String idText) {
        Optional<Vpn2ManualPayment> found = manualPaymentRepository.findById(Long.parseLong(idText));
        if (found.isEmpty() || !Vpn2ManualPayment.STATUS_PENDING.equals(found.get().getStatus())) {
            show(adminId, oldMsgId, "Эта заявка не найдена или уже обработана.", null);
            return;
        }
        Vpn2ManualPayment m = found.get();
        Optional<Tariff> tariff = Tariff.byKey(m.getTariff());
        if ("cno".equals(action) || tariff.isEmpty()) {
            m.setStatus(Vpn2ManualPayment.STATUS_REJECTED);
            m.setDecidedAt(LocalDateTime.now());
            manualPaymentRepository.save(m);
            show(adminId, oldMsgId, "❌ Заявка #" + m.getId() + " отклонена.", null);
            sendHtml(m.getTelegramId(), "❌ <b>Перевод не подтверждён</b>\n\nНе нашли поступление. Если вы оплатили, напишите в поддержку: /paysupport.", null);
            return;
        }
        Vpn2Account account;
        String ref = "card:" + m.getId();
        try {
            account = vpn.activatePaid(m.getTelegramId(), tariff.get(), ref);
        } catch (VpnException e) {
            show(adminId, oldMsgId, "⚠️ Не удалось выдать ключ (" + escape(e.getCode()) + "). Заявка #" + m.getId() + " осталась на проверке, попробуйте позже.", null);
            return;
        }
        m.setStatus(Vpn2ManualPayment.STATUS_APPROVED);
        m.setDecidedAt(LocalDateTime.now());
        manualPaymentRepository.save(m);
        show(adminId, oldMsgId, "✅ Заявка #" + m.getId() + " подтверждена, ключ выдан игроку <code>" + m.getTelegramId() + "</code>.", null);
        finishAltPurchase(m.getTelegramId(), null, tariff.get(), account, "CARD", m.getAmountRub(), 0, ref, ", " + m.getAmountRub() + " ₽");
    }

    // ---- крипта (CryptoBot)

    private void showCryptoPay(Long uid, Integer oldMsgId) {
        if (!vpn.isCryptoPayEnabled()) {
            showMethodSoon(uid, oldMsgId, "crypto");
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Tariff t : Tariff.values()) {
            rows.add(List.of(keyboardFactory.callback("₿ " + t.getTitle() + ": " + t.getPriceRub() + " ₽", "m:cryt:" + t.getKey())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Способы оплаты", "m:pay")));
        show(uid, oldMsgId, "₿ <b>Оплата криптой</b>\n\nВыберите срок: мы выставим счёт в @CryptoBot, оплатить можно TON или USDT. Сумма пересчитывается из рублей по курсу.", rows);
    }

    private void startCryptoPayment(Long uid, Integer oldMsgId, String key) {
        Optional<Tariff> tariff = Tariff.byKey(key);
        if (!vpn.isCryptoPayEnabled() || tariff.isEmpty()) {
            showPayMethods(uid, oldMsgId);
            return;
        }
        Tariff t = tariff.get();
        try {
            CryptoPayClient.Invoice invoice = cryptoPay.createInvoice(t.getPriceRub(), "EGC VPN: " + t.getTitle(), "vpn2:" + uid + ":" + t.getKey(), 3600);
            if (invoice.invoiceId() == 0 || invoice.payUrl().isBlank()) {
                throw new IllegalStateException("пустой ответ Crypto Pay");
            }
            Vpn2CryptoInvoice row = new Vpn2CryptoInvoice();
            row.setTelegramId(uid);
            row.setTariff(t.getKey());
            row.setAmountRub(t.getPriceRub());
            row.setCryptoInvoiceId(invoice.invoiceId());
            row.setPayUrl(invoice.payUrl());
            row.setCreatedAt(LocalDateTime.now());
            row = cryptoInvoiceRepository.save(row);
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(keyboardFactory.url("₿ Оплатить в CryptoBot", invoice.payUrl())));
            rows.add(List.of(keyboardFactory.callback("🔄 Проверить оплату", "m:cryc:" + row.getId())));
            rows.add(List.of(keyboardFactory.callback("⬅️ Способы оплаты", "m:pay")));
            show(uid, oldMsgId, "₿ <b>Счёт на " + escape(t.getTitle()) + ": " + t.getPriceRub() + " ₽</b>\n\nОплатите его в @CryptoBot (TON или USDT) в течение часа. "
                    + "После оплаты ключ придёт сюда сам, обычно за 1-2 минуты.", rows);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("VPN bot: crypto invoice failed for {}", uid, e);
            alertAdmins("🚨 <b>VPN-бот: не удалось создать крипто-счёт</b> для <code>" + uid + "</code>: " + escape(String.valueOf(e.getMessage())));
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(keyboardFactory.callback("⬅️ Способы оплаты", "m:pay")));
            show(uid, oldMsgId, "⚠️ Не получилось выставить счёт. Попробуйте через несколько минут или выберите другой способ оплаты.", rows);
        }
    }

    private void checkCryptoPayment(Long uid, Integer oldMsgId, String idText) {
        try {
            Optional<Vpn2CryptoInvoice> found = cryptoInvoiceRepository.findById(Long.parseLong(idText));
            if (found.isEmpty() || !uid.equals(found.get().getTelegramId())) {
                return;
            }
            Vpn2CryptoInvoice inv = found.get();
            if (Vpn2CryptoInvoice.STATUS_PENDING.equals(inv.getStatus())) {
                processCryptoInvoices(List.of(inv));
                inv = cryptoInvoiceRepository.findById(inv.getId()).orElse(inv);
            }
            if (Vpn2CryptoInvoice.STATUS_PAID.equals(inv.getStatus())) {
                return; // ключ уже отправлен processCryptoInvoices
            }
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            if (Vpn2CryptoInvoice.STATUS_PENDING.equals(inv.getStatus())) {
                rows.add(List.of(keyboardFactory.url("₿ Оплатить в CryptoBot", inv.getPayUrl())));
                rows.add(List.of(keyboardFactory.callback("🔄 Проверить ещё раз", "m:cryc:" + inv.getId())));
                show(uid, oldMsgId, "⏳ Оплата пока не найдена. После оплаты это занимает до пары минут, нажмите «Проверить ещё раз».", rows);
            } else {
                rows.add(List.of(keyboardFactory.callback("₿ Выставить новый счёт", "m:crypto")));
                show(uid, oldMsgId, "⌛ Счёт истёк. Если вы всё же оплатили, напишите в поддержку: /paysupport. Иначе выставьте новый.", rows);
            }
        } catch (NumberFormatException e) {
            log.debug("VPN bot: bad crypto check callback {}", idText);
        }
    }

    /** Раз в минуту: неоплаченные крипто-счета последних 3 часов. Оплата найдена - ключ выдаётся и отправляется сам. */
    @Scheduled(initialDelay = 2 * 60_000L, fixedDelay = 60_000L)
    public void pollCryptoInvoices() {
        if (!registered || !cryptoPay.isConfigured()) {
            return;
        }
        try {
            List<Vpn2CryptoInvoice> pending = cryptoInvoiceRepository.findByStatusAndCreatedAtAfter(Vpn2CryptoInvoice.STATUS_PENDING, LocalDateTime.now().minusHours(3));
            if (!pending.isEmpty()) {
                processCryptoInvoices(pending);
            }
        } catch (Exception e) {
            log.warn("VPN bot crypto poll failed", e);
        }
    }

    private void processCryptoInvoices(List<Vpn2CryptoInvoice> invoices) {
        synchronized (cryptoLock) {
            try {
                Map<Long, String> statuses = cryptoPay.statuses(invoices.stream().map(Vpn2CryptoInvoice::getCryptoInvoiceId).toList());
                for (Vpn2CryptoInvoice inv : invoices) {
                    Vpn2CryptoInvoice fresh = cryptoInvoiceRepository.findById(inv.getId()).orElse(inv);
                    if (!Vpn2CryptoInvoice.STATUS_PENDING.equals(fresh.getStatus())) {
                        continue;
                    }
                    String status = statuses.get(fresh.getCryptoInvoiceId());
                    if ("paid".equals(status)) {
                        completeCryptoPayment(fresh);
                    } else if ("expired".equals(status)) {
                        fresh.setStatus(Vpn2CryptoInvoice.STATUS_EXPIRED);
                        cryptoInvoiceRepository.save(fresh);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("VPN bot: crypto status check failed", e);
            }
        }
    }

    /** Счёт оплачен: сначала отметка PAID (чтобы не выдать дважды), затем ключ. Сбой выдачи - алерт админам и честное сообщение игроку (деньги не теряются). */
    private void completeCryptoPayment(Vpn2CryptoInvoice inv) {
        inv.setStatus(Vpn2CryptoInvoice.STATUS_PAID);
        inv.setPaidAt(LocalDateTime.now());
        cryptoInvoiceRepository.save(inv);
        Long uid = inv.getTelegramId();
        Optional<Tariff> tariff = Tariff.byKey(inv.getTariff());
        String ref = "crypto:" + inv.getCryptoInvoiceId();
        try {
            if (tariff.isEmpty()) {
                throw new IllegalStateException("неизвестный тариф " + inv.getTariff());
            }
            Vpn2Account account = vpn.activatePaid(uid, tariff.get(), ref);
            finishAltPurchase(uid, null, tariff.get(), account, "CRYPTO", inv.getAmountRub(), 0, ref, ", " + inv.getAmountRub() + " ₽");
        } catch (Exception e) {
            log.error("VPN bot: crypto payment grant failed, user {}, invoice {}", uid, inv.getCryptoInvoiceId(), e);
            alertAdmins("🚨 <b>ОПЛАТА КРИПТОЙ в VPN-боте не обработана</b>\nИгрок <code>" + uid + "</code>, тариф <code>" + escape(inv.getTariff()) + "</code>, счёт <code>"
                    + inv.getCryptoInvoiceId() + "</code>. Выдать: <code>/vpn2 " + uid + " " + escape(inv.getTariff().toLowerCase()) + "</code>");
            sendHtml(uid, "💳 <b>Оплата получена</b>\n\nКлюч выдаём вручную, это займёт немного времени. Мы уже знаем и пришлём его сюда.", null);
        }
    }

    // ---------------------------------------------------------------- панель техника и админа

    /** Роль «сотрудник»: админ (видит всё) или техник Влад (только просмотр, без денег, ID замаскированы). */
    private boolean isStaff(Long uid) {
        return isAdmin(uid) || vpn.isTech(uid);
    }

    /** Метка клиента без личных данных: техник видит «vpn_…4567», админ - полную. */
    private String maskLabel(String email, boolean admin) {
        if (admin) {
            return email;
        }
        int us = email.indexOf('_');
        String digits = us >= 0 ? email.substring(us + 1) : email;
        return (us >= 0 ? email.substring(0, us + 1) : "") + "…" + (digits.length() > 4 ? digits.substring(digits.length() - 4) : digits);
    }

    private static String gbText(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 100 ? String.format(java.util.Locale.ROOT, "%,.0f", gb).replace(',', ' ') + " ГБ" : String.format(java.util.Locale.ROOT, "%.1f", gb) + " ГБ";
    }

    private void handlePanelCallback(Long uid, Integer oldMsgId, String data) {
        if (!isStaff(uid)) {
            return;
        }
        switch (data) {
            case "p:node" -> showPanelNode(uid, oldMsgId);
            case "p:load" -> showPanelLoad(uid, oldMsgId);
            case "p:keys" -> showPanelKeys(uid, oldMsgId);
            default -> showPanel(uid, oldMsgId);
        }
    }

    private void showPanel(Long uid, Integer oldMsgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("📡 Состояние связки", "p:node")));
        rows.add(List.of(keyboardFactory.callback("👥 Нагрузка", "p:load"), keyboardFactory.callback("🔑 Ключи", "p:keys")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, "🛠 <b>Панель EGC VPN</b>\n\n" + (isAdmin(uid) ? "Режим администратора: полные ID." : "Режим просмотра: ID замаскированы, оплат нет.")
                + "\nТревоги о связке приходят сюда сами.", rows);
    }

    private List<List<InlineKeyboardButton>> panelBackRows(String refresh) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("🔄 Обновить", refresh), keyboardFactory.callback("⬅️ Панель", "p:menu")));
        return rows;
    }

    /** «Состояние связки»: последняя проба из России (выход, задержка, скорость), сертификат узла и ответ панели. */
    private void showPanelNode(Long uid, Integer oldMsgId) {
        Vpn2HealthService.Status st = health.status();
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("📡 <b>Состояние связки</b>\n\n");
        if (!st.heartbeatExpected()) {
            sb.append("Проба из России сейчас не ждётся (VPN2_HEALTH_EXPECT_HEARTBEAT).\n");
        } else if (now - st.lastBeatMs() > 15 * 60_000L) {
            sb.append("⚠️ Сигнала от пробы нет уже ").append(ago(now - st.lastBeatMs())).append(".\n");
        } else {
            sb.append(st.ok() ? "✅ Проба из России: работает" : "❌ Проба из России: сбой").append(" (").append(ago(now - st.lastBeatMs())).append(" назад)\n");
            if (!st.ok() && !st.details().isBlank()) {
                sb.append(escape(st.details())).append("\n");
            }
            if (!st.exitIp().isBlank()) {
                sb.append("Адрес выхода: <code>").append(escape(st.exitIp())).append("</code>");
                if (st.latencyMs() != null) {
                    sb.append(" · задержка ").append(st.latencyMs()).append(" мс");
                }
                sb.append("\n");
            }
            if (st.speedMbps() != null) {
                sb.append("Скорость (раз в час): ").append(String.format(java.util.Locale.ROOT, "%.0f", st.speedMbps())).append(" Мбит/с, замер ")
                  .append(ago(now - st.speedAtMs())).append(" назад\n");
            } else {
                sb.append("Скорость: замера пока нет (проба меряет раз в час)\n");
            }
            if (st.certHoursLeft() != null) {
                sb.append("Сертификат узла входа: ").append(st.certHoursLeft()).append(" ч до конца").append(st.certHoursLeft() < 30 ? " ⚠️" : "").append("\n");
            }
        }
        sb.append(st.panelFailures() == 0 ? "Панель отвечает с сервера бота: ✅" : "Панель не отвечает с сервера бота: ❌ (сбоев подряд: " + st.panelFailures() + ")");
        show(uid, oldMsgId, sb.toString(), panelBackRows("p:node"));
    }

    /** «Нагрузка»: клиенты на inbound, онлайн сейчас, накопленный трафик и за сутки, топ-5 наших по расходу. Маскирование ID для техника. */
    private void showPanelLoad(Long uid, Integer oldMsgId) {
        StringBuilder sb = new StringBuilder("👥 <b>Нагрузка</b>\n\n");
        try {
            VpnPanelClient.NodeLoad load = vpn.nodeLoad();
            sb.append("Клиентов на inbound: <b>").append(load.clients()).append("</b>, из них наших: <b>").append(load.ours()).append("</b>\n");
            sb.append("Онлайн сейчас: <b>").append(load.online()).append("</b>\n");
            sb.append("Трафик за всё время: ↓ ").append(gbText(load.downBytes())).append(" · ↑ ").append(gbText(load.upBytes())).append("\n");
            Optional<long[]> delta = health.trafficDelta(load.downBytes(), load.upBytes());
            if (delta.isPresent()) {
                sb.append("За ~").append(delta.get()[2]).append(" ч: ↓ ").append(gbText(delta.get()[0])).append(" · ↑ ").append(gbText(delta.get()[1])).append("\n");
            } else {
                sb.append("За сутки: история копится (снимок раз в час, нужны сутки)\n");
            }
            if (!load.top().isEmpty()) {
                sb.append("\n<b>Топ-5 наших по расходу</b> (за всё время жизни ключа):\n");
                boolean admin = isAdmin(uid);
                for (VpnPanelClient.TopClient t : load.top()) {
                    sb.append("• <code>").append(escape(maskLabel(t.email(), admin))).append("</code> — ").append(gbText(t.used())).append("\n");
                }
            }
        } catch (Exception e) {
            log.warn("VPN bot: panel load failed", e);
            sb.append("Не удалось получить данные из панели. Попробуйте через минуту.");
        }
        show(uid, oldMsgId, sb.toString().stripTrailing(), panelBackRows("p:load"));
    }

    /** «Ключи»: сводка по нашим ключам (без денег): работают, не подключались, кончаются, лимит. */
    private void showPanelKeys(Long uid, Integer oldMsgId) {
        Vpn2Service.KeysOverview k = vpn.keysOverview();
        StringBuilder sb = new StringBuilder("🔑 <b>Ключи EGC VPN</b>\n\n");
        sb.append("Работают сейчас: пробные <b>").append(k.trialRunning()).append("</b>, платные <b>").append(k.paidRunning()).append("</b>\n");
        if (k.panelOk()) {
            sb.append("Ни разу не подключались: <b>").append(k.neverConnected()).append("</b> (по данным панели)\n");
        } else {
            sb.append("Данные панели сейчас недоступны, часть сводки пропущена.\n");
        }
        sb.append("Срок кончается за 3 дня: <b>").append(k.expiring3d()).append("</b>\n");
        sb.append("Пробный лимит трафика исчерпан: <b>").append(k.limitReached()).append("</b>");
        show(uid, oldMsgId, sb.toString(), panelBackRows("p:keys"));
    }

    /** Тревоги о связке для техника (Влад): те же сообщения, что получают админы, но прямо в VPN-боте. */
    @EventListener
    public void onTechAlert(Vpn2TechAlertEvent event) {
        if (!registered) {
            return;
        }
        for (Long techId : vpn.getTechIds()) {
            sendHtml(techId, event.getTextHtml(), null);
        }
    }

    // ---------------------------------------------------------------- возврат Stars

    /** /vpn2refund ID: последние оплаты игрока с кнопками «↩️ Вернуть». Возврат - только по кнопке и после подтверждения. */
    private void handleRefundCommand(Long adminId, String text) {
        String[] parts = text.split("\\s+");
        long target;
        try {
            target = Long.parseLong(parts.length > 1 ? parts[1] : "");
        } catch (NumberFormatException e) {
            sendHtml(adminId, "Формат: <code>/vpn2refund ID</code> (Telegram ID игрока). Покажу его последние оплаты с кнопками возврата.", null);
            return;
        }
        List<Vpn2Payment> list = vpn.recentPayments(target);
        if (list.isEmpty()) {
            sendHtml(adminId, "У игрока " + target + " нет оплат в VPN-боте.", null);
            return;
        }
        StringBuilder sb = new StringBuilder("↩️ <b>Оплаты игрока " + target + "</b>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Vpn2Payment p : list) {
            sb.append("#").append(p.getId()).append(" · ").append(p.getCreatedAt().format(DATE_TIME)).append(" · ").append(escape(p.getTariff()))
              .append(" · ").append(p.getStarsAmount()).append(" ⭐").append(p.getRefundedAt() != null ? " · ↩️ возвращено" : "").append("\n");
            if (p.getRefundedAt() == null) {
                rows.add(List.of(keyboardFactory.callback("↩️ Вернуть #" + p.getId() + " (" + p.getStarsAmount() + " ⭐)", "adm:rf:" + p.getId())));
            }
        }
        sendHtml(adminId, sb.toString(), rows.isEmpty() ? null : rows);
    }

    /** Кнопки админа: adm:rf:ID - подтверждение возврата, adm:rfy:ID - вернуть, adm:off:TELEGRAM_ID - отключить ключ, adm:no - отмена. */
    private void handleAdminCallback(Long adminId, Integer oldMsgId, String data) {
        if (!isAdmin(adminId)) {
            return;
        }
        String[] p = data.split(":");
        if (p.length < 2) {
            return;
        }
        try {
            switch (p[1]) {
                case "rf" -> {
                    Optional<Vpn2Payment> payment = vpn.findPayment(Long.parseLong(p[2]));
                    if (payment.isEmpty() || payment.get().getRefundedAt() != null) {
                        show(adminId, oldMsgId, "Эта оплата не найдена или уже возвращена.", null);
                        return;
                    }
                    Vpn2Payment pay = payment.get();
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    rows.add(List.of(keyboardFactory.callback("✅ Да, вернуть", "adm:rfy:" + pay.getId()), keyboardFactory.callback("Отмена", "adm:no")));
                    show(adminId, oldMsgId, "↩️ <b>Вернуть " + pay.getStarsAmount() + " ⭐</b> игроку <code>" + pay.getTelegramId() + "</code> за тариф "
                            + escape(pay.getTariff()) + "?\n\nКлюч автоматически не отключится: после возврата появится кнопка «Отключить ключ».", rows);
                }
                case "rfy" -> {
                    Optional<Vpn2Payment> payment = vpn.findPayment(Long.parseLong(p[2]));
                    if (payment.isEmpty() || payment.get().getRefundedAt() != null) {
                        show(adminId, oldMsgId, "Эта оплата не найдена или уже возвращена.", null);
                        return;
                    }
                    Vpn2Payment pay = payment.get();
                    if (pay.getTelegramPaymentChargeId() == null || pay.getTelegramPaymentChargeId().isBlank()
                            || !refundStars(pay.getTelegramId(), pay.getTelegramPaymentChargeId())) {
                        show(adminId, oldMsgId, "⚠️ Telegram не принял возврат (возможно, он уже сделан или платёж слишком старый). Подробности в логе.", null);
                        return;
                    }
                    vpn.markRefunded(pay);
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    rows.add(List.of(keyboardFactory.callback("🔒 Отключить ключ", "adm:off:" + pay.getTelegramId())));
                    show(adminId, oldMsgId, "✅ Возвращено " + pay.getStarsAmount() + " ⭐ игроку <code>" + pay.getTelegramId() + "</code>.", rows);
                    sendHtml(pay.getTelegramId(), "💳 <b>Мы вернули вам " + pay.getStarsAmount() + " ⭐ за EGC VPN</b>\n\nЕсли остались вопросы, напишите в поддержку: /paysupport.", null);
                }
                case "off" -> {
                    long target = Long.parseLong(p[2]);
                    try {
                        vpn.disable(target);
                        show(adminId, oldMsgId, "🔒 Ключ игрока <code>" + target + "</code> отключён.", null);
                    } catch (VpnException e) {
                        show(adminId, oldMsgId, "⚠️ Не получилось отключить ключ: " + escape(e.getMessage()) + " (" + e.getCode() + ")", null);
                    }
                }
                case "cok", "cno" -> handleCardDecision(adminId, oldMsgId, p[1], p[2]);
                case "no" -> show(adminId, oldMsgId, "Отменено.", null);
                default -> { }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            log.debug("VPN bot: bad admin callback {}", data);
        }
    }

    /** refundStarPayment токеном ЭТОГО бота (платёж прошёл через него). true - Telegram принял возврат. */
    private boolean refundStars(Long uid, String chargeId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user_id", uid);
            body.put("telegram_payment_charge_id", chargeId);
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.telegram.org/bot" + vpn.getBotToken() + "/refundStarPayment"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            java.net.http.HttpResponse<String> response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() == 200 && root.path("ok").asBoolean(false)) {
                return true;
            }
            log.error("VPN bot: refundStarPayment failed for {}: HTTP {} {}", uid, response.statusCode(), response.body());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("VPN bot: refundStarPayment failed for {}", uid, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- помощь с подключением

    /** «🛠 Не подключается?»: по данным панели (загружало ли приложение подписку, были ли подключения и трафик) говорит, на каком шаге человек застрял. */
    private void showDiagnosis(Long uid, Integer oldMsgId) {
        Optional<Vpn2Account> found = vpn.find(uid);
        if (found.isEmpty() || !vpn.isRunning(found.get())) {
            showMy(uid, oldMsgId);
            return;
        }
        Vpn2Account a = found.get();
        Optional<VpnPanelClient.ClientStat> stat = vpn.clientStat(a);
        StringBuilder sb = new StringBuilder("🛠 <b>Проверка подключения</b>\n\n");
        if (stat.isEmpty()) {
            sb.append("Не удалось проверить сервер прямо сейчас. Попробуйте через пару минут или напишите в поддержку.");
        } else {
            VpnPanelClient.ClientStat st = stat.get();
            long now = System.currentTimeMillis();
            if (!st.enable()) {
                sb.append(Vpn2Account.PLAN_TRIAL.equals(a.getPlanType())
                        ? "Пробный лимит трафика исчерпан, поэтому ключ приостановлен. Оформите тариф, и всё заработает сразу."
                        : "Ключ сейчас отключён. Напишите в поддержку, разберёмся.");
            } else if (st.lastSubFetchMs() <= 0 && st.lastOnlineMs() <= 0) {
                sb.append("Приложение пока ни разу не загружало вашу ссылку. Проверьте:\n")
                  .append("1. Установлено ли приложение Happ (кнопки ниже).\n")
                  .append("2. Скопирована ли ссылка полностью: откройте «Моё подключение» и нажмите на ссылку.\n")
                  .append("3. В Happ добавьте подписку из буфера обмена и нажмите «Обновить».\n\n")
                  .append("Не получается вставить? Нажмите «📷 QR-код» и отсканируйте его с другого устройства.");
            } else if (st.lastOnlineMs() <= 0) {
                sb.append("Ссылка в приложении есть, но подключений к серверу ещё не было. Проверьте:\n")
                  .append("1. В Happ выбран ваш профиль, нажмите «Подключить».\n")
                  .append("2. iPhone при первом подключении спросит разрешение добавить VPN-конфигурацию: нажмите «Разрешить».\n")
                  .append("3. Обновите подписку в приложении и попробуйте снова.\n\n")
                  .append("Если ошибка осталась, напишите в поддержку и приложите скриншот.");
            } else {
                sb.append("✅ Ключ работает: последнее подключение ").append(ago(now - st.lastOnlineMs())).append(" назад, использовано ")
                  .append(String.format(java.util.Locale.ROOT, "%.2f", st.used() / (1024.0 * 1024 * 1024))).append(" ГБ.\n\n")
                  .append("Если сайты не открываются: выключите и снова включите VPN, обновите подписку в приложении, попробуйте другую сеть (Wi-Fi или мобильный интернет).");
            }
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(installRows());
        rows.add(List.of(keyboardFactory.callback("📷 QR-код", "h:qr"), keyboardFactory.callback("🔑 Моё подключение", "m:my")));
        rows.add(List.of(keyboardFactory.url("🆘 Поддержка", "https://t.me/" + appProperties.getSupportUsername())));
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, sb.toString(), rows);
    }

    private static String ago(long millis) {
        long minutes = Math.max(0, millis / 60_000);
        if (minutes < 60) {
            return Math.max(1, minutes) + " мин";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + " ч" : (hours / 24) + " дн.";
    }

    private void showHelpOk(Long uid, Integer oldMsgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
        show(uid, oldMsgId, "🙌 Отлично! Приятного пользования.", rows);
    }

    /** «📷 QR-код»: картинка со ссылкой подписки, чтобы добавить её в приложение другим устройством (ПК → телефон). Ссылка личная: в подписи просим не показывать. */
    private void sendQr(Long uid) {
        Optional<Vpn2Account> found = vpn.find(uid);
        if (found.isEmpty() || !vpn.isRunning(found.get())) {
            showMy(uid, null);
            return;
        }
        try {
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(vpn.subscriptionUrl(found.get()), com.google.zxing.BarcodeFormat.QR_CODE, 480, 480);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(matrix.getWidth(), matrix.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            SendPhoto photo = new SendPhoto();
            photo.setChatId(uid.toString());
            photo.setPhoto(new InputFile(new java.io.ByteArrayInputStream(out.toByteArray()), "egc-vpn-qr.png"));
            photo.setCaption("📷 QR-код вашей ссылки. Отсканируйте его камерой другого устройства или в приложении Happ. Это личная ссылка, никому её не показывайте.");
            execute(photo);
        } catch (Exception e) {
            log.warn("VPN bot: QR generation failed for {}", uid, e);
            sendHtml(uid, "⚠️ Не получилось сделать QR-код. Скопируйте ссылку из «Моё подключение» или напишите в поддержку.", null);
        }
    }

    /** Через час после выдачи ключа смотрим панель: если приложение так и не подключилось, предлагаем помощь (один раз на аккаунт). Окно «выдан от 1 до 24 часов назад»
     *  не даёт писать про старые ключи; отметка helpNudgedAt ставится до отправки. */
    @Scheduled(initialDelay = 4 * 60_000L, fixedDelay = 10 * 60_000L)
    public void nudgeNotConnected() {
        if (!registered || !vpn.isEnabled()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<Vpn2Account> pending = new ArrayList<>();
            for (Vpn2Account a : accountRepository.findByStatusAndHelpNudgedAtIsNullAndCreatedAtBetween(
                    Vpn2Account.STATUS_ACTIVE, now.minusHours(24), now.minusHours(1))) {
                if (vpn.isRunning(a)) {
                    pending.add(a);
                }
            }
            if (pending.isEmpty()) {
                return;
            }
            Map<String, VpnPanelClient.ClientStat> stats = vpn.allClientStats();
            for (Vpn2Account a : pending) {
                a.setHelpNudgedAt(now);
                accountRepository.save(a);
                VpnPanelClient.ClientStat st = stats.get(a.getPanelEmail());
                if (st != null && st.used() == 0 && st.lastOnlineMs() <= 0) {
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    rows.add(List.of(keyboardFactory.callback("✅ Да, работает", "h:ok"), keyboardFactory.callback("🛠 Нет, помогите", "h:diag")));
                    sendHtml(a.getTelegramId(), "🔐 <b>Получилось подключиться?</b>\n\nВидим, что VPN пока не заработал. Давайте поможем.", rows);
                }
            }
        } catch (Exception e) {
            log.warn("VPN bot connection nudge failed", e);
        }
    }

    // ---------------------------------------------------------------- лимит пробного

    /** Раз в 15 минут: у пробных, которые работают сейчас, смотрим расход в панели; когда лимит исчерпан (панель отключает ключ), один раз пишем игроку
     *  с предложением оформить тариф - самый тёплый момент для продажи. Бэклог: аккаунтов мало, повторов нет (отметка limitNotifiedAt ставится до отправки). */
    @Scheduled(initialDelay = 3 * 60_000L, fixedDelay = 15 * 60_000L)
    public void notifyTrialLimitReached() {
        if (!registered || !vpn.isEnabled()) {
            return;
        }
        try {
            List<Vpn2Account> candidates = accountRepository.findByPlanTypeAndStatusAndLimitNotifiedAtIsNullAndExpiresAtAfter(
                    Vpn2Account.PLAN_TRIAL, Vpn2Account.STATUS_ACTIVE, LocalDateTime.now());
            if (candidates.isEmpty()) {
                return;
            }
            Map<String, VpnPanelClient.ClientStat> usage = vpn.allClientStats();
            for (Vpn2Account a : candidates) {
                VpnPanelClient.ClientStat u = usage.get(a.getPanelEmail());
                if (u != null && u.total() > 0 && u.used() >= u.total() * 0.99) {
                    notifyLimitReached(a);
                }
            }
        } catch (Exception e) {
            log.warn("VPN bot trial limit check failed", e);
        }
    }

    private void notifyLimitReached(Vpn2Account a) {
        try {
            a.setLimitNotifiedAt(LocalDateTime.now());
            accountRepository.save(a); // сначала отметка: при сбое отправки лучше промолчать, чем слать каждые 15 минут
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(keyboardFactory.callback("💳 Тарифы", "m:tariffs"), keyboardFactory.url("🎮 Копить EXC", egcUrl())));
            sendHtml(a.getTelegramId(), "🔐 <b>Пробный лимит исчерпан</b>\n\nВы использовали все " + vpn.getTrialGb()
                    + " ГБ. Оформите тариф, чтобы продолжить: безлимитный трафик, до 2 устройств.", rows);
        } catch (Exception e) {
            log.warn("VPN bot limit notice failed for {}", a.getTelegramId(), e);
        }
    }

    // ---------------------------------------------------------------- отзывы

    private static final Map<String, String> LOW_REASONS = new LinkedHashMap<>();
    private static final Map<String, String> CHURN_REASONS = new LinkedHashMap<>();

    static {
        LOW_REASONS.put("speed", "Скорость");
        LOW_REASONS.put("conn", "Не подключается");
        LOW_REASONS.put("app", "Неудобное приложение");
        LOW_REASONS.put("price", "Цена");
        LOW_REASONS.put("other", "Другое");
        CHURN_REASONS.put("price", "Дорого");
        CHURN_REASONS.put("slow", "Медленно");
        CHURN_REASONS.put("broken", "Не работает");
        CHURN_REASONS.put("notneeded", "Больше не нужен");
        CHURN_REASONS.put("other", "Другое");
    }

    /** Опросы раз в час: оценка за сутки до конца пробного, оценка через 7 дней у платного, «почему не продлили» через 2 дня после конца срока.
     *  У каждого аккаунта свой этап (feedbackStage), поэтому повторов нет; окна по времени ограничены, чтобы не писать про давно прошедшее. */
    @Scheduled(initialDelay = 10 * 60_000L, fixedDelay = 60 * 60_000L)
    public void askFeedback() {
        if (!registered || !vpn.isEnabled()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Vpn2Account a : accountRepository.findByPlanTypeAndStatusAndFeedbackStageLessThanAndExpiresAtBetween(
                    Vpn2Account.PLAN_TRIAL, Vpn2Account.STATUS_ACTIVE, 1, now, now.plusDays(1))) {
                askRating(a, 1, Vpn2Feedback.KIND_TRIAL, "🔐 <b>Как вам EGC VPN?</b>\nОцените, это займёт 5 секунд.");
            }
            for (Vpn2Account a : accountRepository.findByPlanTypeAndStatusAndFeedbackStageLessThanAndCreatedAtBefore(
                    Vpn2Account.PLAN_PAID, Vpn2Account.STATUS_ACTIVE, 2, now.minusDays(7))) {
                if (vpn.isRunning(a)) {
                    askRating(a, 2, Vpn2Feedback.KIND_WEEK, "🔐 <b>Вы с нами неделю. Как вам EGC VPN?</b>\nОцените, это займёт 5 секунд.");
                }
            }
            for (Vpn2Account a : accountRepository.findByStatusAndFeedbackStageLessThanAndExpiresAtBetween(
                    Vpn2Account.STATUS_ACTIVE, 3, now.minusDays(4), now.minusDays(2))) {
                askChurn(a);
            }
        } catch (Exception e) {
            log.warn("VPN bot feedback surveys failed", e);
        }
    }

    private void askRating(Vpn2Account a, int stage, String kind, String html) {
        try {
            a.setFeedbackStage(stage);
            accountRepository.save(a); // сначала отметка: при сбое отправки лучше пропустить опрос, чем слать его каждый час
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(keyboardFactory.callback("😍 Отлично", "fb:r:" + kind + ":5"), keyboardFactory.callback("🙂 Нормально", "fb:r:" + kind + ":4")));
            rows.add(List.of(keyboardFactory.callback("😐 Так себе", "fb:r:" + kind + ":3"), keyboardFactory.callback("😕 Плохо", "fb:r:" + kind + ":2")));
            sendHtml(a.getTelegramId(), html, rows);
        } catch (Exception e) {
            log.warn("VPN bot rating survey failed for {}", a.getTelegramId(), e);
        }
    }

    private void askChurn(Vpn2Account a) {
        try {
            a.setFeedbackStage(3);
            accountRepository.save(a);
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(keyboardFactory.callback("Дорого", "fb:ch:price"), keyboardFactory.callback("Медленно", "fb:ch:slow")));
            rows.add(List.of(keyboardFactory.callback("Не работает", "fb:ch:broken"), keyboardFactory.callback("Больше не нужен", "fb:ch:notneeded")));
            rows.add(List.of(keyboardFactory.callback("Другое", "fb:ch:other")));
            sendHtml(a.getTelegramId(), "🔐 <b>Почему не продлили EGC VPN?</b>\nНам важно знать.", rows);
        } catch (Exception e) {
            log.warn("VPN bot churn survey failed for {}", a.getTelegramId(), e);
        }
    }

    private void handleFeedbackCallback(Long uid, Integer oldMsgId, String data) {
        String[] p = data.split(":");
        if (p.length < 2) {
            return;
        }
        try {
            switch (p[1]) {
                case "r" -> {
                    if (p.length < 4 || !(Vpn2Feedback.KIND_TRIAL.equals(p[2]) || Vpn2Feedback.KIND_WEEK.equals(p[2]))) {
                        return;
                    }
                    int rating = Integer.parseInt(p[3]);
                    if (rating < 2 || rating > 5) {
                        return;
                    }
                    Vpn2Feedback f = new Vpn2Feedback();
                    f.setTelegramId(uid);
                    f.setKind(p[2]);
                    f.setRating(rating);
                    f.setCreatedAt(LocalDateTime.now());
                    f = feedbackRepository.save(f);
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    if (rating >= 4) {
                        rows.add(List.of(keyboardFactory.callback("✍️ Написать отзыв", "fb:w:" + f.getId()), keyboardFactory.callback("Пропустить", "fb:s")));
                        show(uid, oldMsgId, "🙏 <b>Спасибо!</b>\nНапишете пару слов? Это поможет другим.", rows);
                    } else {
                        List<InlineKeyboardButton> row = new ArrayList<>();
                        for (Map.Entry<String, String> e : LOW_REASONS.entrySet()) {
                            row.add(keyboardFactory.callback(e.getValue(), "fb:c:" + f.getId() + ":" + e.getKey()));
                            if (row.size() == 2) {
                                rows.add(row);
                                row = new ArrayList<>();
                            }
                        }
                        if (!row.isEmpty()) {
                            rows.add(row);
                        }
                        show(uid, oldMsgId, "😕 <b>Жаль.</b> Что не так?", rows);
                    }
                }
                case "c" -> {
                    Vpn2Feedback f = ownedFeedback(uid, p.length > 2 ? p[2] : null);
                    if (f == null || p.length < 4 || !LOW_REASONS.containsKey(p[3])) {
                        return;
                    }
                    f.setReason(p[3]);
                    feedbackRepository.save(f);
                    alertAdmins("😕 <b>Плохая оценка EGC VPN</b>: " + f.getRating() + "/5, причина: " + escape(LOW_REASONS.get(p[3]))
                            + ". Игрок <a href=\"tg://user?id=" + uid + "\">написать</a> (<code>" + uid + "</code>)");
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    rows.add(List.of(keyboardFactory.url("🆘 Поддержка", "https://t.me/" + appProperties.getSupportUsername())));
                    rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
                    show(uid, oldMsgId, "🙏 <b>Спасибо, учтём.</b> Если нужна помощь с подключением, напишите в поддержку, поможем.", rows);
                }
                case "ch" -> {
                    if (p.length < 3 || !CHURN_REASONS.containsKey(p[2])) {
                        return;
                    }
                    Vpn2Feedback f = new Vpn2Feedback();
                    f.setTelegramId(uid);
                    f.setKind(Vpn2Feedback.KIND_CHURN);
                    f.setReason(p[2]);
                    f.setCreatedAt(LocalDateTime.now());
                    feedbackRepository.save(f);
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    if ("price".equals(p[2])) {
                        rows.add(List.of(keyboardFactory.callback("💳 Тарифы", "m:tariffs"), keyboardFactory.url("🎮 Копить EXC", egcUrl())));
                    }
                    rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
                    show(uid, oldMsgId, "🙏 <b>Спасибо!</b> Это помогает нам стать лучше."
                            + ("price".equals(p[2]) ? "\nЗа EXC можно платить на 50 ₽ меньше." : ""), rows);
                }
                case "w" -> {
                    Vpn2Feedback f = ownedFeedback(uid, p.length > 2 ? p[2] : null);
                    if (f == null) {
                        return;
                    }
                    awaitingReviewText.put(uid, f.getId());
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    rows.add(List.of(keyboardFactory.callback("Отмена", "fb:s")));
                    show(uid, oldMsgId, "✍️ <b>Напишите отзыв</b> одним сообщением (до 500 символов).", rows);
                }
                case "s" -> {
                    awaitingReviewText.remove(uid);
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
                    show(uid, oldMsgId, "🙌 Спасибо за оценку!", rows);
                }
                case "p" -> {
                    Vpn2Feedback f = ownedFeedback(uid, p.length > 2 ? p[2] : null);
                    if (f == null || p.length < 4 || f.getReviewText() == null) {
                        return;
                    }
                    boolean yes = "1".equals(p[3]);
                    f.setPublishConsent(yes);
                    if (yes) {
                        f.setStatus(Vpn2Feedback.STATUS_PENDING);
                    }
                    feedbackRepository.save(f);
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
                    show(uid, oldMsgId, yes ? "🙌 <b>Спасибо!</b> Отзыв уйдёт на проверку и появится в канале анонимно." : "🙌 Спасибо за отзыв!", rows);
                    if (yes) {
                        sendModerationRequest(f);
                    }
                }
                default -> { }
            }
        } catch (NumberFormatException e) {
            log.debug("VPN bot: bad feedback callback {}", data);
        }
    }

    /** Отзыв принадлежит этому человеку; null - нет такого или чужой. */
    private Vpn2Feedback ownedFeedback(Long uid, String idText) {
        if (idText == null) {
            return null;
        }
        try {
            Vpn2Feedback f = feedbackRepository.findById(Long.parseLong(idText)).orElse(null);
            return f != null && uid.equals(f.getTelegramId()) ? f : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Текст отзыва после «✍️ Написать отзыв»: сохраняем, при оценке 4-5 и тексте от 20 символов спрашиваем согласие на публикацию. */
    private void saveReviewText(Long uid, Long feedbackId, String text) {
        Vpn2Feedback f = ownedFeedback(uid, String.valueOf(feedbackId));
        if (f == null) {
            showMain(uid, null);
            return;
        }
        String clean = text.trim();
        if (clean.length() > 500) {
            clean = clean.substring(0, 500);
        }
        f.setReviewText(clean);
        feedbackRepository.save(f);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (f.getRating() != null && f.getRating() >= 4 && clean.length() >= 20) {
            rows.add(List.of(keyboardFactory.callback("✅ Да, анонимно", "fb:p:" + f.getId() + ":1"), keyboardFactory.callback("Нет, спасибо", "fb:p:" + f.getId() + ":0")));
            sendHtml(uid, "🙏 <b>Спасибо за отзыв!</b>\nМожно опубликовать его в канале анонимно?", rows);
        } else {
            rows.add(List.of(keyboardFactory.callback("⬅️ Меню", "m:main")));
            sendHtml(uid, "🙏 <b>Спасибо за отзыв!</b> Он нам очень помогает.", rows);
        }
    }

    private void sendModerationRequest(Vpn2Feedback f) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("✅ Опубликовать", "fbadm:pub:" + f.getId()), keyboardFactory.callback("❌ Отклонить", "fbadm:no:" + f.getId())));
        String html = "📝 <b>Отзыв на модерацию</b>\n" + stars(f.getRating()) + "\n«" + escape(f.getReviewText()) + "»\nИгрок <code>" + f.getTelegramId() + "</code>"
                + (vpn.getReviewsChannel().isBlank() ? "\n\n⚠️ Канал отзывов не задан (VPN2_REVIEWS_CHANNEL), опубликовать пока нельзя." : "");
        for (Long adminId : adminService.resolvedAdminIds()) {
            sendHtml(adminId, html, rows);
        }
    }

    /** Кнопки модерации «✅ Опубликовать» / «❌ Отклонить» у админа. */
    private void handleFeedbackModeration(Long adminId, Integer oldMsgId, String data) {
        if (!isAdmin(adminId)) {
            return;
        }
        String[] p = data.split(":");
        if (p.length < 3) {
            return;
        }
        Vpn2Feedback f;
        try {
            f = feedbackRepository.findById(Long.parseLong(p[2])).orElse(null);
        } catch (NumberFormatException e) {
            return;
        }
        if (f == null || !Vpn2Feedback.STATUS_PENDING.equals(f.getStatus())) {
            show(adminId, oldMsgId, "Этот отзыв уже обработан.", null);
            return;
        }
        if ("no".equals(p[1])) {
            f.setStatus(Vpn2Feedback.STATUS_REJECTED);
            feedbackRepository.save(f);
            show(adminId, oldMsgId, "❌ Отзыв отклонён.", null);
            return;
        }
        if (vpn.getReviewsChannel().isBlank()) {
            show(adminId, oldMsgId, "⚠️ Канал отзывов не задан (VPN2_REVIEWS_CHANNEL в .env), опубликовать нельзя.", null);
            return;
        }
        try {
            publishCard(f);
            f.setStatus(Vpn2Feedback.STATUS_PUBLISHED);
            f.setPublishedAt(LocalDateTime.now());
            feedbackRepository.save(f);
            show(adminId, oldMsgId, "✅ Опубликовано в канале.", null);
        } catch (TelegramApiException e) {
            log.warn("VPN bot: failed to publish review {}", f.getId(), e);
            show(adminId, oldMsgId, "⚠️ Не получилось опубликовать: проверьте, что бот добавлен в канал админом с правом публикации.", null);
        }
    }

    /** Карточка отзыва в канале: звёзды, цитата, срок пользования, кнопка «Попробовать» (ведёт в VPN-бот). Без имени и ника. */
    private void publishCard(Vpn2Feedback f) throws TelegramApiException {
        String usage = "Только подключился";
        Optional<Vpn2Account> account = vpn.find(f.getTelegramId());
        if (account.isPresent() && account.get().getCreatedAt() != null) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(account.get().getCreatedAt(), LocalDateTime.now());
            if (days >= 1) {
                usage = "Пользуется " + days + " дн.";
            }
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("🔐 Попробовать " + daysText(vpn.getTrialDays()) + " бесплатно", "https://t.me/" + vpn.getBotUsername())));
        SendMessage m = new SendMessage();
        m.setChatId(vpn.getReviewsChannel());
        m.setText(stars(f.getRating()) + "\n\n«" + escape(f.getReviewText()) + "»\n\n" + usage);
        m.setParseMode("HTML");
        m.setDisableWebPagePreview(true);
        m.setReplyMarkup(keyboardFactory.rowsLayout(rows));
        execute(m);
    }

    private static String stars(Integer rating) {
        int n = rating == null ? 0 : Math.max(0, Math.min(5, rating));
        return "⭐".repeat(n);
    }

    /** Сводка по отзывам для /stats: средняя оценка, распределение, причины отказов, очередь модерации. */
    private String feedbackStatsHtml() {
        StringBuilder sb = new StringBuilder("📝 <b>Отзывы</b>\n");
        Double avg = feedbackRepository.averageRating();
        if (avg == null) {
            sb.append("Оценок пока нет.");
        } else {
            sb.append("Средняя оценка: <b>").append(String.format(java.util.Locale.ROOT, "%.1f", avg)).append("</b>\nОценки:");
            for (Object[] row : feedbackRepository.ratingDistribution()) {
                sb.append(" ").append(row[0]).append("⭐ ").append(row[1]).append(" ·");
            }
            sb.setLength(sb.length() - 2);
        }
        List<Object[]> reasons = feedbackRepository.reasonCounts();
        if (!reasons.isEmpty()) {
            sb.append("\nПричины:");
            for (Object[] row : reasons) {
                String key = String.valueOf(row[0]);
                sb.append(" ").append(escape(LOW_REASONS.getOrDefault(key, CHURN_REASONS.getOrDefault(key, key)))).append(" ").append(row[1]).append(" ·");
            }
            sb.setLength(sb.length() - 2);
        }
        sb.append("\nНа модерации: <b>").append(feedbackRepository.countByStatus(Vpn2Feedback.STATUS_PENDING))
          .append("</b>, опубликовано: <b>").append(feedbackRepository.countByStatus(Vpn2Feedback.STATUS_PUBLISHED)).append("</b>");
        return sb.toString();
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

    /** Ссылка в игровой бот с меткой источника: новые игроки оттуда попадают в трафик «vpnbot». */
    private String egcUrl() {
        return "https://t.me/" + appProperties.getBotUsername() + "?start=src_" + TRAFFIC_SOURCE_CODE;
    }

    private static String formatExc(long amount) {
        return String.format("%,d", amount).replace(',', ' ');
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
