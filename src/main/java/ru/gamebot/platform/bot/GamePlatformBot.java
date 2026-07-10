package ru.gamebot.platform.bot;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.enums.RewardRequestStatus;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.NewsPost;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.domain.model.SupportAttachment;
import ru.gamebot.platform.domain.model.SupportTicket;
import ru.gamebot.platform.event.LeagueRewardEvent;
import ru.gamebot.platform.event.NewsPublishedEvent;
import ru.gamebot.platform.service.AdminService;
import ru.gamebot.platform.service.GameCatalogService;
import ru.gamebot.platform.service.NewsService;
import ru.gamebot.platform.service.QuestService;
import ru.gamebot.platform.service.RewardService;
import ru.gamebot.platform.service.SessionService;
import ru.gamebot.platform.service.SinkShopService;
import ru.gamebot.platform.service.SupportService;
import ru.gamebot.platform.service.UserService;

@Slf4j
@Component
@RequiredArgsConstructor
public class GamePlatformBot extends TelegramLongPollingBot {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+|t\\.me/\\S+)");
    private static final int ADMIN_USERS_PAGE_SIZE = 8;
    private static final int BONUS_USERS_PAGE_SIZE = 8;
    private static final String ROLE_USER = "USER";
    private static final String ROLE_MODER = "MODER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final Map<String, String> PLATFORM_OPTIONS = new LinkedHashMap<>();
    private static final Map<String, String> INTEREST_OPTIONS = new LinkedHashMap<>();

    static {
        PLATFORM_OPTIONS.put("ANDROID", "Android");
        PLATFORM_OPTIONS.put("IPHONE", "iPhone");
        PLATFORM_OPTIONS.put("PC", "PC");
        PLATFORM_OPTIONS.put("PS5", "PS5");
        PLATFORM_OPTIONS.put("XBOX", "Xbox");

        INTEREST_OPTIONS.put("FPS", "FPS");
        INTEREST_OPTIONS.put("MMO", "MMO");
        INTEREST_OPTIONS.put("RPG", "RPG");
        INTEREST_OPTIONS.put("STRATEGY", "Стратегии");
        INTEREST_OPTIONS.put("SPORT", "Спорт");
        INTEREST_OPTIONS.put("CASUAL", "Казуальные");
    }

    private final AppProperties appProperties;
    private final UserService userService;
    private final QuestService questService;
    private final RewardService rewardService;
    private final NewsService newsService;
    private final AdminService adminService;
    private final SessionService sessionService;
    private final SupportService supportService;
    private final KeyboardFactory keyboardFactory;
    private final ru.gamebot.platform.service.HealthRatioService healthRatioService;
    private final ru.gamebot.platform.service.ExchangeRateService exchangeRateService;
    private final ru.gamebot.platform.service.SinkShopService sinkShopService;
    private final ru.gamebot.platform.service.CouncilService councilService;
    private final ru.gamebot.platform.service.ShopLimitService shopLimitService;
    private final GameCatalogService gameCatalogService;
    private final ru.gamebot.platform.service.TrafficSourceService trafficSourceService;
    private final ru.gamebot.platform.service.PollService pollService;
    private final ru.gamebot.platform.service.TournamentService tournamentService;
    private final ru.gamebot.platform.service.SeasonService seasonService;
    private final ru.gamebot.platform.service.SponsorService sponsorService;
    private final ru.gamebot.platform.service.ExcTransactionService excTransactionService;

    private final Queue<String[]> pendingNewsQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService albumScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> albumTimers = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void registerBot() throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(this);
        log.info("Telegram bot registered: {}", getBotUsername());
        log.info("Resolved admin IDs: {}", adminService.resolvedAdminIds());
        log.info("Resolved moderator IDs: {}", adminService.resolvedModeratorIds());
        drainNewsQueue();
        notifyAdminsStartup();
    }

    private void notifyAdminsStartup() {
        String time = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
        String text = "✅ <b>Бот запущен</b>\n\n"
                + "🕒 " + time + "\n"
                + "🤖 @" + getBotUsername() + " v1.0.0\n"
                + "📦 Статус: работает в штатном режиме";
        for (Long adminId : adminService.resolvedAdminIds()) {
            try {
                SendMessage msg = new SendMessage();
                msg.setChatId(adminId.toString());
                msg.setText(text);
                msg.setParseMode("HTML");
                execute(msg);
            } catch (TelegramApiException e) {
                log.warn("Failed to notify admin {} about startup", adminId, e);
            }
        }
    }

    @Override
    public String getBotUsername() {
        return appProperties.getBotUsername();
    }

    @Override
    public String getBotToken() {
        return appProperties.getBotToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            } else if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception exception) {
            log.error("Failed to process update", exception);
            if (update.hasCallbackQuery()) {
                answerSilently(update.getCallbackQuery().getId());
            }
            Long chatId = extractChatId(update);
            if (chatId != null) {
                sendText(chatId, "⚠️ Что-то пошло не так. Попробуйте ещё раз или вернитесь в меню командой /menu.", null);
            }
        }
    }

    private void handleMessage(Message message) {
        if (message.getFrom() == null) {
            return;
        }

        String text = message.getText();
        if (text != null && text.startsWith("/start")) {
            handleStart(message);
            return;
        }

        AppUser user = userService.getOrCreate(message.getFrom(), null);
        UserSession session = sessionService.get(user.getTelegramId());
        ensureRoleConsistency(user, session);

        if (user.isBlocked() && !adminService.isAdmin(user.getTelegramId())) {
            sendBlockedNotice(user);
            return;
        }

        if (text != null && handleClearMeCommand(user, session, text.trim())) {
            return;
        }

        if (text != null && handleRoleSwitchCommand(user, session, text.trim())) {
            return;
        }

        if (text != null && text.equals("/resetreqid") && isEffectiveAdmin(user)) {
            rewardService.resetWithdrawalRequestIds();
            sendText(user.getTelegramId(), "✅ Все заявки на вывод удалены, счётчик ID сброшен до 1.", null);
            return;
        }

        if (text != null && text.startsWith("/resetlimit") && isEffectiveAdmin(user)) {
            String[] parts = text.trim().split("\\s+");
            if (parts.length == 2) {
                try {
                    long targetId = Long.parseLong(parts[1]);
                    userService.findByTelegramId(targetId).ifPresentOrElse(target -> {
                        sinkShopService.resetWithdrawalLimit(target);
                        sendText(user.getTelegramId(), "✅ Лимит вывода сброшен для пользователя #" + targetId, null);
                    }, () -> sendText(user.getTelegramId(), "❌ Пользователь не найден: " + targetId, null));
                } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Неверный формат ID", null);
                }
            } else {
                sendText(user.getTelegramId(), "Использование: /resetlimit <telegram_id>", null);
            }
            return;
        }

        if (shouldContinueSupportMediaGroup(message, session)) {
            handleSupportMessage(user, session, message);
            return;
        }

        if (!user.isProfileCompleted() && session.getState() == SessionState.NONE) {
            session.setState(SessionState.REG_NAME);
            sendText(user.getTelegramId(),
                    "🎉 Добро пожаловать в <b>" + escape(appProperties.getClubName()) + "</b>!\n\n"
                            + "Чтобы открыть квесты, рейтинг и награды, давайте быстро оформим профиль.\n"
                            + "Напишите ваш игровой никнейм.\n\n"
                            + "<b>ВАЖНО! Ник в боте должен совпадать с ником в игре</b>",
                    null);
            return;
        }

        if (user.isProfileCompleted() && !user.isRegistrationCompleted() && !isEffectiveModerator(user)) {
            sendCommunityActivationPrompt(user, null);
            return;
        }

        if (session.getState() == SessionState.REPORT_MEDIA) {
            handleReportMessage(user, session, message);
            return;
        }

        if (session.getState() == SessionState.REPORT_MEDIA_COLLECTING) {
            handleReportCollecting(user, session, message);
            return;
        }

        if (session.getState() == SessionState.AVATAR_UPLOAD) {
            handleAvatarUpload(user, session, message);
            return;
        }

        if (session.getState() == SessionState.QUEST_CREATE_PHOTO) {
            if (message.hasPhoto()) {
                List<PhotoSize> photos = message.getPhoto();
                String fileId = photos.get(photos.size() - 1).getFileId();
                session.getData().put("photoFileId", fileId);
            }
            showQuestPreview(user, session);
            return;
        }

        if (session.getState() == SessionState.REWARD_CREATE_PHOTO) {
            if (message.hasPhoto()) {
                List<PhotoSize> photos = message.getPhoto();
                session.getData().put("photoFileId", photos.get(photos.size() - 1).getFileId());
            }
            finalizeRewardCreation(user, session);
            return;
        }

        if (session.getState() == SessionState.WITHDRAWAL_RECEIPT) {
            // Команды переключения роли и навигации сбрасывают состояние
            if (text != null && (text.startsWith("/start") || text.equals("/moder") || text.equals("/admin") || text.equals("/user") || text.equals("/menu"))) {
                session.reset();
                sendMainMenu(user, roleWelcomeText(user, null));
                return;
            }
            if (!message.hasPhoto()) {
                sendText(user.getTelegramId(), "⚠️ Пожалуйста, отправьте фото чека.", null);
                return;
            }
            List<PhotoSize> photos = message.getPhoto();
            String fileId = photos.get(photos.size() - 1).getFileId();
            Long reqId = session.getQuestId();
            boolean isModReceiptFlow = "mod".equals(session.getData().get("receiptFlow"));
            session.reset();
            RewardRequest req = rewardService.approveRequest(reqId);
            notifyUserWithdrawalApproved(req, fileId);
            sendText(user.getTelegramId(), "✅ Выплата подтверждена, чек отправлен пользователю.", null);
            if (isModReceiptFlow) sendModWithdrawals(user);
            else sendAdminWithdrawals(user);
            return;
        }

        if (session.getState() == SessionState.GAME_PHOTO_UPLOAD) {
            if (!message.hasPhoto()) {
                sendText(user.getTelegramId(), "⚠️ Пожалуйста, отправьте изображение (не файл).", cancelKeyboard());
                return;
            }
            List<PhotoSize> photos = message.getPhoto();
            String fileId = photos.get(photos.size() - 1).getFileId();
            String gameName = session.getData().get("gamePhotoName");
            gameCatalogService.setPhoto(gameName, fileId);
            session.reset();
            sendText(user.getTelegramId(), "✅ Фото для игры «" + escape(gameName) + "» сохранено.", null);
            sendAdminQuestCategories(user, gameName);
            return;
        }

        if (session.getState() == SessionState.SUPPORT_INPUT) {
            handleSupportMessage(user, session, message);
            return;
        }

        if (session.getState() == SessionState.SUPPORT_REPLY && isEffectiveModerator(user)) {
            handleSupportReplyMessage(user, session, message);
            return;
        }

        // Сотрудник без завершённой регистрации — сбросить сессию и показать рабочее меню
        if (!user.isProfileCompleted() && isEffectiveModerator(user)) {
            session.reset();
            sendMainMenu(user, roleWelcomeText(user, null));
            return;
        }

        if (session.getState() == SessionState.BROADCAST_MESSAGE && message.hasPhoto()) {
            List<PhotoSize> bcastPhotos = message.getPhoto();
            String bcastFileId = bcastPhotos.get(bcastPhotos.size() - 1).getFileId();
            String bcastCaption = message.getCaption() != null ? message.getCaption() : "";
            handleBroadcastPhoto(user, session, bcastFileId, bcastCaption);
            return;
        }

        if (text != null && session.getState() != SessionState.NONE) {
            // Навигационные команды всегда сбрасывают текущее состояние
            if (text.equals("/moder") || text.equals("/admin") || text.equals("/user") || text.equals("/menu")) {
                session.reset();
                sendMainMenu(user, roleWelcomeText(user, null));
                return;
            }
            handleStateInput(user, session, text);
            return;
        }

        if (!user.isProfileCompleted()) {
            if ("/menu".equalsIgnoreCase(text)) {
                sendCurrentRegistrationStep(user, session,
                        "🧭 Сначала завершим регистрацию. После этого откроется полное меню платформы.");
                return;
            }
            sendText(user.getTelegramId(),
                    "🧭 Сначала завершим регистрацию. Ответьте на текущий шаг, и я сразу переведу вас дальше.",
                    null);
            sendCurrentRegistrationStep(user, session, null);
            return;
        }

        if ("/menu".equalsIgnoreCase(text)) {
            sendMainMenu(user, mainMenuText(user));
            return;
        }

        sendMainMenu(user, mainMenuText(user));
    }

    private void handleStart(Message message) {
        String srcCode = parseStartTrafficSource(message.getText());
        Long referredBy = parseStartReferral(message.getText());
        AppUser user = userService.getOrCreate(message.getFrom(), referredBy);
        UserSession session = sessionService.get(user.getTelegramId());
        ensureRoleConsistency(user, session);

        if (user.isBlocked() && !adminService.isAdmin(user.getTelegramId())) {
            sendBlockedNotice(user);
            return;
        }

        if (srcCode != null) {
            trafficSourceService.recordClick(srcCode);
            if (user.getTrafficSourceCode() == null) {
                user.setTrafficSourceCode(srcCode);
                userService.save(user);
            }
        }

        String streakMessage = userService.registerActivity(user);

        // Сотрудники (модератор/admin) получают доступ к меню без прохождения регистрации
        boolean isStaff = isEffectiveModerator(user);
        if (isStaff) {
            sendMainMenu(user, roleWelcomeText(user, streakMessage));
            return;
        }

        if (!user.isProfileCompleted()) {
            session.reset();
            session.setState(SessionState.REG_NAME);
            sendText(user.getTelegramId(),
                    "🎮 Добро пожаловать в <b>" + escape(appProperties.getClubName()) + "</b>!\n\n"
                            + "Здесь вас ждут квесты, XP, рейтинг, награды и реферальная программа.\n"
                            + "Начнем с профиля. Напишите ваш игровой никнейм.\n\n"
                            + "<b>ВАЖНО! Ник в боте должен совпадать с ником в игре</b>",
                    null);
            return;
        }

        if (!user.isRegistrationCompleted()) {
            sendCommunityActivationPrompt(user, null);
            return;
        }

        String intro = roleWelcomeText(user, streakMessage);
        sendMainMenu(user, intro);
    }

    private void handleCallback(CallbackQuery callbackQuery) {
        Long telegramId = callbackQuery.getFrom().getId();
        AppUser user = userService.getOrCreate(callbackQuery.getFrom(), null);
        UserSession session = sessionService.get(telegramId);
        ensureRoleConsistency(user, session);
        String data = callbackQuery.getData();

        if (user.isBlocked() && !adminService.isAdmin(user.getTelegramId())) {
            answerSilently(callbackQuery.getId());
            sendBlockedNotice(user);
            return;
        }

        if (data == null) {
            answer(callbackQuery.getId(), "Пустое действие");
            return;
        }

        if (data.startsWith("reg:platform:")) {
            handlePlatformSelection(callbackQuery, user, session, data.substring("reg:platform:".length()));
            return;
        }
        if (data.startsWith("reg:interest:")) {
            handleInterestSelection(callbackQuery, user, session, data.substring("reg:interest:".length()));
            return;
        }
        if ("activation:check".equals(data)) {
            handleActivationCheck(callbackQuery, user);
            return;
        }
        if ("activation:profile".equals(data)) {
            sendProfile(user);
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("common:cancel".equals(data) && !user.isRegistrationCompleted()) {
            clearInlineKeyboard(callbackQuery);
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("common:cancel".equals(data)) {
            session.reset();
            sendMainMenu(user, "↩️ Текущее действие отменено. Возвращаю вас в главное меню.");
            answer(callbackQuery.getId(), "Отменено");
            return;
        }

        if ("news:approve".equals(data) && isEffectiveAdmin(user) && session.getState() == SessionState.NEWS_APPROVAL) {
            String newsTitle = session.getData().get("pending_news_title");
            String newsBody = session.getData().get("pending_news_body");
            session.reset();
            newsService.createPost(newsTitle, newsBody);
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "✅ Новость опубликована и разослана");
            drainNewsQueue();
            return;
        }
        if ("news:reject".equals(data) && isEffectiveAdmin(user) && session.getState() == SessionState.NEWS_APPROVAL) {
            session.reset();
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "❌ Публикация отменена");
            drainNewsQueue();
            return;
        }

        if (!isEffectiveModerator(user) && !user.isProfileCompleted()) {
            answer(callbackQuery.getId(), "Сначала завершим регистрацию");
            sendCurrentRegistrationStep(user, session,
                    "🧭 Перед использованием разделов нужно закончить регистрацию. Продолжим с текущего шага.");
            return;
        }

        if (!isEffectiveModerator(user) && !user.isRegistrationCompleted()) {
            answer(callbackQuery.getId(), "Сначала активируйте аккаунт");
            sendCommunityActivationPrompt(user, null);
            return;
        }

        if (data.startsWith("menu:")) {
            handleMenuAction(callbackQuery, user, data.substring("menu:".length()));
            return;
        }
        if (data.startsWith("profile:")) {
            handleProfileAction(callbackQuery, user, session, data.substring("profile:".length()));
            return;
        }
        if (data.startsWith("quests:game:")) {
            sendQuestCategories(user, decodeGameToken(data.substring("quests:game:".length())));
            answer(callbackQuery.getId(), "Квесты обновлены");
            return;
        }
        if (data.startsWith("quests:list:")) {
            handleQuestListAction(callbackQuery, user, data.substring("quests:list:".length()));
            return;
        }
        if (data.startsWith("quests:cat:")) {
            sendQuestGames(user);
            answer(callbackQuery.getId(), "Раздел обновлен");
            return;
        }
        if (data.startsWith("quest:view:")) {
            handleQuestView(callbackQuery, user, session, data.substring("quest:view:".length()));
            return;
        }
        if (data.startsWith("myquest:cancel:")) {
            long submissionId = parseLong(data.substring("myquest:cancel:".length()));
            answerSilently(callbackQuery.getId());
            try {
                questService.cancelSubmission(submissionId, user);
                sendMySubmissions(user);
                sendText(user.getTelegramId(), "✅ Квест отменён.", null);
            } catch (Exception e) {
                log.error("Failed to cancel submission {}", submissionId, e);
                sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), null);
            }
            return;
        }
        if (data.startsWith("myquest:view:")) {
            sendMyQuestCard(user, parseLong(data.substring("myquest:view:".length())));
            answer(callbackQuery.getId(), "Мой квест");
            return;
        }
        if (data.startsWith("quest:take:")) {
            handleTakeQuest(callbackQuery, user, parseLong(data.substring("quest:take:".length())));
            return;
        }
        if (data.startsWith("quest:report:")) {
            handleReportStart(callbackQuery, user, session, parseLong(data.substring("quest:report:".length())));
            return;
        }
        if (data.startsWith("report:submit:")) {
            long submissionId = parseLong(data.substring("report:submit:".length()));
            String photos = session.getData().getOrDefault("report_photos", "");
            String comment = session.getData().getOrDefault("report_comment", "Без комментария");
            if (photos.isBlank()) {
                answerSilently(callbackQuery.getId());
                sendText(user.getTelegramId(), "⚠️ Добавьте хотя бы один скриншот или файл перед отправкой.", null);
                return;
            }
            String[] allPhotos = photos.split("\\|\\|");
            String firstPhoto = allPhotos[0];
            String extra = allPhotos.length > 1
                    ? String.join("||", java.util.Arrays.copyOfRange(allPhotos, 1, allPhotos.length))
                    : "";
            QuestSubmission submission = questService.getSubmission(submissionId);
            submission.setExtraMediaFileIds(extra);
            questService.submitReport(submission, "photo", firstPhoto, null, comment);
            session.reset();
            notifyModeratorsAboutSubmission(submission.getId());
            answerSilently(callbackQuery.getId());
            sendText(user.getTelegramId(),
                    "✅ <b>Отчёт отправлен</b> (" + allPhotos.length + " фото)\n\nМатериалы ушли в очередь проверки.",
                    backMenuKeyboard("menu:myquests"));
            return;
        }
        if (data.equals("shop:soon")) {
            answerSilently(callbackQuery.getId());
            sendText(user.getTelegramId(),
                "🔒 <b>Скоро!</b>\n\nGift Cards будут добавлены в следующем обновлении. Следи за каналом!",
                backMenuKeyboard("menu:shop"));
            return;
        }
        if (data.equals("shop:withdraw")) {
            answerSilently(callbackQuery.getId());
            if (rewardService.hasWithdrawalTodayOrPending(user)) {
                sendText(user.getTelegramId(),
                    "⚠️ <b>Лимит: 1 заявка на вывод в сутки.</b>\n\n"
                        + "Следующую заявку можно создать через 24 часа после предыдущей.",
                    backMenuKeyboard("menu:main"));
                return;
            }
            sendWithdrawalMethodChoice(user);
            return;
        }
        if (data.equals("shop:withdraw:rub")) {
            answerSilently(callbackQuery.getId());
            session.setState(SessionState.WITHDRAWAL_INPUT);
            sendWithdrawalScreen(user);
            return;
        }
        if (data.equals("shop:withdraw:usdt")) {
            answerSilently(callbackQuery.getId());
            sendWithdrawalUsdtWalletQuestion(user);
            return;
        }
        if (data.equals("shop:withdraw:usdt:has_wallet")) {
            answerSilently(callbackQuery.getId());
            session.setState(SessionState.WITHDRAWAL_USDT_AMOUNT);
            sendWithdrawalUsdtAmountScreen(user);
            return;
        }
        if (data.equals("shop:withdraw:usdt:no_wallet")) {
            answerSilently(callbackQuery.getId());
            sendWithdrawalUsdtNoWalletGuide(user);
            return;
        }
        if (data.startsWith("shop:view:")) {
            sendRewardCard(user, parseLong(data.substring("shop:view:".length())));
            answer(callbackQuery.getId(), "Карточка награды");
            return;
        }
        if (data.startsWith("shop:group:")) {
            sendGroupPicker(user, data.substring("shop:group:".length()));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("shop:soon:")) {
            RewardItem item = rewardService.getRewardItem(parseLong(data.substring("shop:soon:".length())));
            sendText(user.getTelegramId(),
                    "🔜 <b>" + escape(item.getTitle()) + "</b>\n\n"
                            + "📦 Категория: <b>" + escape(item.getCategory()) + "</b>\n"
                            + "📝 " + escape(item.getDescription()) + "\n\n"
                            + "⏳ <b>Этот товар скоро появится в магазине.</b> Следи за обновлениями!",
                    backMenuKeyboard("menu:shop"));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("reward:cancel:")) {
            handleUserRewardCancel(callbackQuery, user, parseLong(data.substring("reward:cancel:".length())));
            return;
        }
        if (data.startsWith("shop:buy:")) {
            handleRewardPurchase(callbackQuery, user, parseLong(data.substring("shop:buy:".length())));
            return;
        }
        if (data.startsWith("sink:")) {
            handleSinkAction(callbackQuery, user, data.substring("sink:".length()));
            return;
        }
        if (data.startsWith("council:")) {
            handleCouncilAction(callbackQuery, user, data.substring("council:".length()));
            return;
        }
        if (data.startsWith("quest_type:") && session.getState() == SessionState.QUEST_CREATE_COUNCIL) {
            boolean councilOnly = "council".equals(data.substring("quest_type:".length()));
            finalizeQuestCreation(user, session, councilOnly);
            answer(callbackQuery.getId(), "Квест создан");
            return;
        }
        if (data.startsWith("qc:")) {
            handleQuestCreateCallback(callbackQuery, user, session, data.substring("qc:".length()));
            return;
        }
        if (data.startsWith("reward_create:") && isEffectiveAdmin(user)) {
            if ("reward_create:photo:skip".equals(data) && session.getState() == SessionState.REWARD_CREATE_PHOTO) {
                finalizeRewardCreation(user, session);
            }
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("qe:") && isEffectiveAdmin(user)) {
            handleQuestEditCallback(callbackQuery, user, session, data.substring("qe:".length()));
            return;
        }
        if (data.startsWith("rate:")) {
            sendLeaderboard(user, data.substring("rate:".length()));
            answer(callbackQuery.getId(), "Рейтинг готов");
            return;
        }
        if (data.startsWith("battlepass:buy:")) {
            long seasonId = parseLong(data.substring("battlepass:buy:".length()));
            seasonService.findById(seasonId).ifPresentOrElse(season -> {
                ru.gamebot.platform.service.SeasonService.PurchaseResult res = seasonService.purchase(user, season);
                if (res.success()) {
                    answer(callbackQuery.getId(), "🎫 Battle Pass активирован!");
                    sendBattlePass(user);
                } else {
                    answer(callbackQuery.getId(), "❌ " + res.error());
                }
            }, () -> answer(callbackQuery.getId(), "❌ Сезон не найден."));
            return;
        }
        if (data.startsWith("tournament:join:")) {
            long tid = parseLong(data.substring("tournament:join:".length()));
            tournamentService.findById(tid).ifPresentOrElse(t -> {
                ru.gamebot.platform.service.TournamentService.JoinResult res = tournamentService.join(user, t);
                if (res.success()) {
                    answer(callbackQuery.getId(), "✅ Вы зарегистрированы! Взнос списан.");
                    sendTournament(user);
                } else {
                    answer(callbackQuery.getId(), "❌ " + res.error());
                }
            }, () -> answer(callbackQuery.getId(), "❌ Турнир не найден."));
            return;
        }
        if (data.startsWith("tournament:leaderboard:")) {
            long tid = parseLong(data.substring("tournament:leaderboard:".length()));
            sendTournamentLeaderboard(user, tid);
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("poll:view:")) {
            sendPollDetail(user, parseLong(data.substring("poll:view:".length())));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("poll:vote:")) {
            String[] parts = data.substring("poll:vote:".length()).split(":");
            handlePollVote(callbackQuery, user, parseLong(parts[0]), parseInteger(parts[1]));
            return;
        }
        if (data.startsWith("support:")) {
            handleSupportAction(callbackQuery, user, session, data.substring("support:".length()));
            return;
        }
        if ("mod:suspects".equals(data) && isEffectiveModerator(user)) {
            sendFraudSuspects(user.getTelegramId());
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("mod:suspect:") && isEffectiveModerator(user)) {
            handleSuspectAction(callbackQuery, user, data.substring("mod:suspect:".length()));
            return;
        }
        if (data.startsWith("mod:clear_suspect:") && isEffectiveModerator(user)) {
            Long targetId = parseLong(data.substring("mod:clear_suspect:".length()));
            userService.clearFraudSuspect(targetId);
            sendText(user.getTelegramId(), "✅ Флаг снят. Аккаунт помечен как проверенный.", backOnlyKeyboard("mod:suspects"));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("mod:view:") && isEffectiveModerator(user)) {
            sendSubmissionCard(user.getTelegramId(), parseLong(data.substring("mod:view:".length())));
            answer(callbackQuery.getId(), "Заявка открыта");
            return;
        }
        if (data.startsWith("mod:ok:") && isEffectiveModerator(user)) {
            handleModerationApprove(callbackQuery, parseLong(data.substring("mod:ok:".length())));
            return;
        }
        if (data.startsWith("mod:no:") && isEffectiveModerator(user)) {
            handleModerationReject(callbackQuery, parseLong(data.substring("mod:no:".length())));
            return;
        }
        if (data.startsWith("mod:more:") && isEffectiveModerator(user)) {
            handleModerationClarify(callbackQuery, parseLong(data.substring("mod:more:".length())));
            return;
        }
        if (data.startsWith("mod:support:") && isEffectiveModerator(user)) {
            handleModeratorSupportAction(callbackQuery, user, session, data.substring("mod:support:".length()));
            return;
        }
        if (data.startsWith("mod:withdrawal") && isEffectiveModerator(user)) {
            handleModWithdrawalAction(callbackQuery, user, session, data);
            return;
        }
        if (data.startsWith("admin:") && isEffectiveAdmin(user)) {
            handleAdminAction(callbackQuery, user, session, data.substring("admin:".length()));
            return;
        }

        answer(callbackQuery.getId(), "Неизвестное действие");
    }

    private void handleMenuAction(CallbackQuery callbackQuery, AppUser user, String action) {
        switch (action) {
            case "main" -> sendMainMenu(user, mainMenuText(user));
            case "profile" -> sendProfile(user);
            case "quests" -> sendQuestGames(user);
            case "myquests" -> sendMySubmissions(user);
            case "balance" -> sendBalance(user);
            case "rating" -> sendRatingMenu(user);
            case "referrals" -> sendReferrals(user);
            case "shop" -> sendShop(user);
            case "sink" -> sendSinkShop(user);
            case "my-rewards" -> sendUserRewardRequests(user);
            case "my-withdrawals" -> sendUserWithdrawalRequests(user);
            case "council" -> sendCouncil(user);
            case "tournament" -> sendTournament(user);
            case "news" -> sendNews(user);
            case "polls" -> sendPollList(user);
            case "battlepass" -> sendBattlePass(user);
            case "support" -> sendSupport(user);
            case "quickstart" -> { answerSilently(callbackQuery.getId()); sendQuickStartGuide(user); }
            case "admin" -> sendAdminPanel(user);
            case "moderation" -> sendModerationHub(user);
            case "daily" -> { sendDailyBonus(callbackQuery, user); return; }
            case "cat:quests" -> sendQuestsCategory(user);
            case "cat:wallet" -> sendWalletCategory(user);
            case "cat:shop" -> sendShopCategory(user);
            case "cat:club" -> sendClubCategory(user);
            case "cat:help" -> sendHelpCategory(user);
            default -> sendMainMenu(user, mainMenuText(user));
        }
        answerSilently(callbackQuery.getId());
    }

    private void handleAvatarUpload(AppUser user, UserSession session, Message message) {
        if (!message.hasPhoto()) {
            sendText(user.getTelegramId(),
                    "⚠️ Пожалуйста, отправьте именно фото (не файл и не стикер).",
                    backOnlyKeyboard("menu:profile"));
            return;
        }
        List<PhotoSize> photos = message.getPhoto();
        String fileId = photos.get(photos.size() - 1).getFileId();
        user.setAvatarFileId(fileId);
        userService.save(user);
        session.setState(SessionState.NONE);
        sendText(user.getTelegramId(),
                "✅ Аватар успешно обновлён! Теперь он отображается в вашем профиле.",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("👤 Открыть профиль", "menu:profile")),
                        List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
                )));
    }

    private void handleProfileAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        switch (action) {
            case "balance" -> sendBalance(user, "menu:profile");
            case "avatar" -> {
                session.setState(SessionState.AVATAR_UPLOAD);
                sendText(user.getTelegramId(),
                        "📷 <b>Загрузка аватара</b>\n\n"
                                + "Отправьте фото, которое станет вашим аватаром в профиле.\n"
                                + "Рекомендуем квадратное фото для лучшего отображения.",
                        backOnlyKeyboard("menu:profile"));
            }
            case "nickname" -> {
                session.setState(SessionState.NICKNAME_CHANGE);
                sendText(user.getTelegramId(),
                        "✏️ <b>Смена никнейма</b>\n\n"
                                + "Текущий ник: <b>" + escape(user.getNickname()) + "</b>\n\n"
                                + "Введите новый игровой никнейм.\n"
                                + "<b>ВАЖНО: ник в боте должен совпадать с ником в игре.</b>",
                        backOnlyKeyboard("menu:profile"));
            }
            default -> sendProfile(user);
        }
        answerSilently(callbackQuery.getId());
    }

    private void handlePlatformSelection(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if (session.getState() != SessionState.REG_PLATFORMS) {
            answer(callbackQuery.getId(), "Сначала дойдите до шага платформ");
            return;
        }

        if ("done".equals(action)) {
            List<String> selected = resolveSelections(session, "platforms", PLATFORM_OPTIONS);
            if (selected.isEmpty()) {
                answer(callbackQuery.getId(), "Выберите хотя бы одну платформу");
                return;
            }
            session.setState(SessionState.REG_INTERESTS);
            editRegistrationSelectionMessage(
                    callbackQuery,
                    "🧠 Выберите игровые интересы.\n\n"
                            + "Сейчас выбрано: <b>ничего</b>",
                    selectionKeyboard(INTEREST_OPTIONS, List.of(), "reg:interest:", true, false, false)
            );
            answer(callbackQuery.getId(), "Платформы сохранены");
            return;
        }

        toggleSelection(session, "platforms", action);
        editPlatformQuestion(callbackQuery, session);
        answer(callbackQuery.getId(), "Выбор обновлен");
    }

    private void handleInterestSelection(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if (session.getState() != SessionState.REG_INTERESTS) {
            answer(callbackQuery.getId(), "Сначала дойдите до шага интересов");
            return;
        }

        if ("skip".equals(action)) {
            finishRegistration(user, session, List.of());
            answer(callbackQuery.getId(), "Регистрация завершена");
            return;
        }
        if ("done".equals(action)) {
            finishRegistration(user, session, resolveSelections(session, "interests", INTEREST_OPTIONS));
            answer(callbackQuery.getId(), "Регистрация завершена");
            return;
        }

        toggleSelection(session, "interests", action);
        editInterestQuestion(callbackQuery, session);
        answer(callbackQuery.getId(), "Выбор обновлен");
    }

    private void handleStateInput(AppUser user, UserSession session, String text) {
        switch (session.getState()) {
            case REG_NAME -> {
                session.getData().put("nickname", text.trim());
                session.setState(SessionState.REG_AGE);
                sendText(user.getTelegramId(),
                        "🧾 Отлично, <b>" + escape(text.trim()) + "</b>!\n\nТеперь укажите возраст числом.",
                        null);
            }
            case REG_AGE -> {
                Integer age = parseInteger(text.trim());
                if (age == null || age < 10 || age > 99) {
                    sendText(user.getTelegramId(),
                            "⚠️ Возраст должен быть числом в диапазоне 10-99. Попробуйте ещё раз.",
                            null);
                    return;
                }
                session.getData().put("age", age.toString());
                session.setState(SessionState.REG_COUNTRY);
                sendText(user.getTelegramId(),
                        "🌍 Отлично. Теперь напишите страну, из которой вы играете.",
                        null);
            }
            case REG_COUNTRY -> {
                session.getData().put("country", text.trim());
                session.setState(SessionState.REG_PLATFORMS);
                sendPlatformQuestion(user, session);
            }
            case NICKNAME_CHANGE -> {
                String newNick = text.trim();
                if (newNick.length() < 2 || newNick.length() > 32) {
                    sendText(user.getTelegramId(),
                            "⚠️ Никнейм должен быть от 2 до 32 символов. Попробуйте ещё раз:",
                            backOnlyKeyboard("menu:profile"));
                    return;
                }
                if (userService.findByNickname(newNick).isPresent()
                        && !newNick.equalsIgnoreCase(user.getNickname())) {
                    sendText(user.getTelegramId(),
                            "⚠️ Ник <b>" + escape(newNick) + "</b> уже занят. Введите другой:",
                            backOnlyKeyboard("menu:profile"));
                    return;
                }
                user.setNickname(newNick);
                userService.save(user);
                session.setState(SessionState.NONE);
                sendText(user.getTelegramId(),
                        "✅ Никнейм успешно изменён на <b>" + escape(newNick) + "</b>!",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("👤 Открыть профиль", "menu:profile")),
                                List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
                        )));
            }
            case BONUS_INPUT -> handleBonusInput(user, session, text);
            case DEBIT_INPUT -> handleDebitInput(user, session, text);
            case BROADCAST_MESSAGE -> handleBroadcast(user, session, text);
            case PAYOUT_POOL_INPUT -> handlePayoutPoolInput(user, session, text);
            case TRAFFIC_SOURCE_NAME -> {
                session.getData().put("trafficName", text.trim());
                session.setState(SessionState.TRAFFIC_SOURCE_CODE);
                sendText(user.getTelegramId(),
                        "🔑 Введите короткий код (латиница, цифры, дефис). Например: <code>instagram</code>, <code>vk-ad</code>, <code>blogger1</code>\n\n"
                        + "Ссылка будет: <code>t.me/" + appProperties.getBotUsername() + "?start=src_ВАШ_КОД</code>",
                        cancelKeyboard());
            }
            case TRAFFIC_SOURCE_CODE -> {
                String code = text.trim().toLowerCase().replaceAll("[^a-z0-9\\-_]", "");
                if (code.isEmpty()) {
                    sendText(user.getTelegramId(), "❌ Код должен содержать только латиницу, цифры, дефис.", cancelKeyboard());
                    return;
                }
                String name = session.getData().get("trafficName");
                try {
                    ru.gamebot.platform.domain.model.TrafficSource ts = trafficSourceService.create(name, code);
                    String link = "https://t.me/" + appProperties.getBotUsername() + "?start=src_" + ts.getCode();
                    session.reset();
                    sendText(user.getTelegramId(),
                            "✅ <b>Источник создан!</b>\n\n"
                            + "📌 Название: <b>" + escape(ts.getName()) + "</b>\n"
                            + "🔑 Код: <code>" + ts.getCode() + "</code>\n"
                            + "🔗 Ссылка:\n<code>" + link + "</code>",
                            backMenuKeyboard("admin:traffic"));
                } catch (IllegalArgumentException e) {
                    sendText(user.getTelegramId(), "❌ " + e.getMessage(), cancelKeyboard());
                }
            }
            case POLL_CREATE_QUESTION -> {
                session.getData().put("pollQuestion", text.trim());
                session.setState(SessionState.POLL_CREATE_OPTIONS);
                sendText(user.getTelegramId(),
                        "📋 Введите варианты ответа, каждый с новой строки (минимум 2, максимум 8):\n\n"
                        + "Пример:\n<code>PUBG Mobile\nFortnite\nWarzone\nApex Legends</code>",
                        cancelKeyboard());
            }
            case POLL_CREATE_OPTIONS -> {
                String[] opts = text.trim().split("\\n");
                if (opts.length < 2 || opts.length > 8) {
                    sendText(user.getTelegramId(), "❌ Нужно от 2 до 8 вариантов, каждый с новой строки.", cancelKeyboard());
                    return;
                }
                session.getData().put("pollOptions", text.trim());
                session.setState(SessionState.POLL_CREATE_PRICE);
                sendText(user.getTelegramId(), "💰 Укажите стоимость одного голоса в EXC (например: <code>500</code>):", cancelKeyboard());
            }
            case POLL_CREATE_PRICE -> {
                long price;
                try { price = Long.parseLong(text.trim()); } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Введите число.", cancelKeyboard()); return;
                }
                if (price <= 0) { sendText(user.getTelegramId(), "❌ Цена должна быть > 0.", cancelKeyboard()); return; }
                session.getData().put("pollPrice", String.valueOf(price));
                session.setState(SessionState.POLL_CREATE_DATE);
                sendText(user.getTelegramId(),
                        "⏰ Введите дату и время закрытия в формате <code>ДД.ММ.ГГГГ ЧЧ:ММ</code>\nЛибо отправьте <code>0</code> — голосование без ограничений по времени.",
                        cancelKeyboard());
            }
            case POLL_CREATE_DATE -> {
                java.time.LocalDateTime closesAt = null;
                if (!"0".equals(text.trim())) {
                    try {
                        closesAt = java.time.LocalDateTime.parse(text.trim(),
                                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                    } catch (Exception e) {
                        sendText(user.getTelegramId(), "❌ Неверный формат. Используйте ДД.ММ.ГГГГ ЧЧ:ММ или 0.", cancelKeyboard());
                        return;
                    }
                }
                String question = session.getData().get("pollQuestion");
                List<String> options = java.util.Arrays.asList(session.getData().get("pollOptions").split("\\n"));
                long price = Long.parseLong(session.getData().get("pollPrice"));
                ru.gamebot.platform.domain.model.Poll poll = pollService.create(question, options, price, closesAt);
                session.reset();
                sendText(user.getTelegramId(),
                        "✅ <b>Голосование создано!</b>\n\n"
                        + "❓ " + escape(poll.getQuestion()) + "\n"
                        + "💰 Цена голоса: <b>" + poll.getPriceExc() + " EXC</b>\n"
                        + (poll.getClosesAt() != null ? "⏰ Закрытие: <b>" + poll.getClosesAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "</b>\n" : "⏰ Бессрочное\n")
                        + "\nГолосование доступно пользователям в разделе «Голосования».",
                        backMenuKeyboard("admin:polls"));
            }
            case SEASON_CREATE_NAME -> {
                session.getData().put("sName", text.trim());
                session.setState(SessionState.SEASON_CREATE_PRICE);
                sendText(user.getTelegramId(),
                        "💰 Стоимость Battle Pass в EXC (например: <code>7500</code>):",
                        cancelKeyboard());
            }
            case SEASON_CREATE_PRICE -> {
                long price;
                try { price = Long.parseLong(text.trim()); } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Введите число.", cancelKeyboard()); return;
                }
                if (price <= 0) { sendText(user.getTelegramId(), "❌ Цена должна быть > 0.", cancelKeyboard()); return; }
                session.getData().put("sPrice", String.valueOf(price));
                session.setState(SessionState.SEASON_CREATE_XP_BOOST);
                sendText(user.getTelegramId(),
                        "⚡ Бонус XP для держателей пасса в % (например: <code>10</code> — это +10% XP за каждый квест):",
                        cancelKeyboard());
            }
            case SEASON_CREATE_XP_BOOST -> {
                int boost;
                try { boost = Integer.parseInt(text.trim()); } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Введите целое число.", cancelKeyboard()); return;
                }
                if (boost < 0 || boost > 100) { sendText(user.getTelegramId(), "❌ Укажите от 0 до 100.", cancelKeyboard()); return; }
                session.getData().put("sBoost", String.valueOf(boost));
                session.setState(SessionState.SEASON_CREATE_START);
                sendText(user.getTelegramId(),
                        "🚀 Дата начала сезона (формат <code>ДД.ММ.ГГГГ ЧЧ:ММ</code>):",
                        cancelKeyboard());
            }
            case SEASON_CREATE_START -> {
                java.time.LocalDateTime startDate;
                try {
                    startDate = java.time.LocalDateTime.parse(text.trim(),
                            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                } catch (Exception e) {
                    sendText(user.getTelegramId(), "❌ Неверный формат. Используйте ДД.ММ.ГГГГ ЧЧ:ММ", cancelKeyboard()); return;
                }
                session.getData().put("sStart", text.trim());
                session.setState(SessionState.SEASON_CREATE_END);
                sendText(user.getTelegramId(),
                        "⏰ Дата окончания сезона (формат <code>ДД.ММ.ГГГГ ЧЧ:ММ</code>):",
                        cancelKeyboard());
            }
            case SEASON_CREATE_END -> {
                java.time.LocalDateTime endDate;
                try {
                    endDate = java.time.LocalDateTime.parse(text.trim(),
                            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                } catch (Exception e) {
                    sendText(user.getTelegramId(), "❌ Неверный формат.", cancelKeyboard()); return;
                }
                java.time.LocalDateTime startDate = java.time.LocalDateTime.parse(
                        session.getData().get("sStart"),
                        java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                if (!endDate.isAfter(startDate)) {
                    sendText(user.getTelegramId(), "❌ Дата окончания должна быть позже даты начала.", cancelKeyboard()); return;
                }
                String sName = session.getData().get("sName");
                long price = Long.parseLong(session.getData().get("sPrice"));
                int boost = Integer.parseInt(session.getData().get("sBoost"));
                ru.gamebot.platform.domain.model.Season s = seasonService.create(sName, price, boost, startDate, endDate);
                session.reset();
                sendText(user.getTelegramId(),
                        "✅ <b>Сезон создан!</b>\n\n"
                        + "🎫 " + escape(s.getName()) + "\n"
                        + "💰 Цена: <b>" + s.getPriceExc() + " EXC</b>\n"
                        + "⚡ XP-буст: <b>+" + s.getXpBoostPercent() + "%</b>\n"
                        + "🚀 Начало: " + s.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "\n"
                        + "⏰ Конец: " + s.getEndDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                        backMenuKeyboard("admin:seasons"));
            }
            case SPONSOR_CREATE_NAME -> {
                session.getData().put("spName", text.trim());
                session.setState(SessionState.SPONSOR_CREATE_CAMPAIGN);
                sendText(user.getTelegramId(), "📋 Введите название кампании (например: «Запуск PUBG New State»):", cancelKeyboard());
            }
            case SPONSOR_CREATE_CAMPAIGN -> {
                session.getData().put("spCampaign", text.trim());
                session.setState(SessionState.SPONSOR_CREATE_PAID_RUB);
                sendText(user.getTelegramId(),
                        "💵 Сколько рублей заплатил спонсор? (Введите число, например: <code>50000</code>)\n"
                        + "70% автоматически пойдёт в Payout Pool, 30% — комиссия EGC.",
                        cancelKeyboard());
            }
            case SPONSOR_CREATE_PAID_RUB -> {
                long paidRub;
                try { paidRub = Long.parseLong(text.trim()); } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Введите число.", cancelKeyboard()); return;
                }
                if (paidRub < 0) { sendText(user.getTelegramId(), "❌ Сумма не может быть отрицательной.", cancelKeyboard()); return; }
                session.getData().put("spPaidRub", String.valueOf(paidRub));
                long suggestedExc = paidRub * 70; // 70% от суммы × 100 EXC/₽
                session.setState(SessionState.SPONSOR_CREATE_BUDGET_EXC);
                sendText(user.getTelegramId(),
                        "💎 Бюджет кампании в EXC (сумма, которую можно выдать игрокам).\n"
                        + "Рекомендуем: <code>" + suggestedExc + "</code> (70% от суммы × 100 EXC/₽)\n\n"
                        + "Введите число EXC:",
                        cancelKeyboard());
            }
            case SPONSOR_CREATE_BUDGET_EXC -> {
                long budgetExc;
                try { budgetExc = Long.parseLong(text.trim()); } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Введите число.", cancelKeyboard()); return;
                }
                if (budgetExc <= 0) { sendText(user.getTelegramId(), "❌ Бюджет должен быть > 0.", cancelKeyboard()); return; }
                session.getData().put("spBudgetExc", String.valueOf(budgetExc));
                session.setState(SessionState.SPONSOR_CREATE_DATES);
                sendText(user.getTelegramId(),
                        "📅 Введите даты кампании в формате:\n<code>ДД.ММ.ГГГГ ЧЧ:ММ - ДД.ММ.ГГГГ ЧЧ:ММ</code>\n\n"
                        + "Или <code>0</code> — без ограничений по дате:",
                        cancelKeyboard());
            }
            case SPONSOR_CREATE_DATES -> {
                java.time.LocalDateTime startDate = null, endDate = null;
                if (!"0".equals(text.trim())) {
                    String[] parts2 = text.trim().split(" - ");
                    if (parts2.length != 2) {
                        sendText(user.getTelegramId(), "❌ Неверный формат. Используйте: ДД.ММ.ГГГГ ЧЧ:ММ - ДД.ММ.ГГГГ ЧЧ:ММ", cancelKeyboard()); return;
                    }
                    try {
                        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
                        startDate = java.time.LocalDateTime.parse(parts2[0].trim(), dtf);
                        endDate = java.time.LocalDateTime.parse(parts2[1].trim(), dtf);
                    } catch (Exception e) {
                        sendText(user.getTelegramId(), "❌ Неверный формат даты.", cancelKeyboard()); return;
                    }
                }
                String spName = session.getData().get("spName");
                String spCampaign = session.getData().get("spCampaign");
                long paidRub = Long.parseLong(session.getData().get("spPaidRub"));
                long budgetExc = Long.parseLong(session.getData().get("spBudgetExc"));
                long commission = Math.round(paidRub * 0.30);
                long poolFunded = paidRub - commission;
                ru.gamebot.platform.domain.model.Sponsor sp = sponsorService.create(
                        spName, spCampaign, paidRub, budgetExc, startDate, endDate, user.getTelegramId());
                session.reset();
                sendText(user.getTelegramId(),
                        "✅ <b>Спонсор добавлен!</b>\n\n"
                        + "🤝 " + escape(sp.getName()) + " — " + escape(sp.getCampaignName()) + "\n"
                        + "💵 Оплата: <b>" + paidRub + " ₽</b>\n"
                        + "   ├ Комиссия EGC (30%): <b>" + commission + " ₽</b>\n"
                        + "   └ В Payout Pool (70%): <b>" + poolFunded + " ₽</b>\n"
                        + "💎 Бюджет кампании: <b>" + budgetExc + " EXC</b>\n\n"
                        + "Теперь привяжите квесты к этой кампании через «Привязать квест».",
                        backMenuKeyboard("admin:sponsors"));
            }
            case TOURNAMENT_CREATE_NAME -> {
                session.getData().put("tName", text.trim());
                session.setState(SessionState.TOURNAMENT_CREATE_GAME);
                sendText(user.getTelegramId(),
                        "🎮 Введите название игры (например: <code>PUBG Mobile</code>)\nИли <code>0</code> — если турнир по всем играм:",
                        cancelKeyboard());
            }
            case TOURNAMENT_CREATE_GAME -> {
                session.getData().put("tGame", "0".equals(text.trim()) ? null : text.trim());
                session.setState(SessionState.TOURNAMENT_CREATE_FEE);
                sendText(user.getTelegramId(),
                        "💰 Введите взнос за участие в EXC (например: <code>2000</code>):",
                        cancelKeyboard());
            }
            case TOURNAMENT_CREATE_FEE -> {
                long fee;
                try { fee = Long.parseLong(text.trim()); } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Введите число.", cancelKeyboard()); return;
                }
                if (fee <= 0) { sendText(user.getTelegramId(), "❌ Взнос должен быть > 0.", cancelKeyboard()); return; }
                session.getData().put("tFee", String.valueOf(fee));
                session.setState(SessionState.TOURNAMENT_CREATE_START);
                sendText(user.getTelegramId(),
                        "🚀 Дата и время начала регистрации (формат <code>ДД.ММ.ГГГГ ЧЧ:ММ</code>):",
                        cancelKeyboard());
            }
            case TOURNAMENT_CREATE_START -> {
                java.time.LocalDateTime startDate;
                try {
                    startDate = java.time.LocalDateTime.parse(text.trim(),
                            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                } catch (Exception e) {
                    sendText(user.getTelegramId(), "❌ Неверный формат. Используйте ДД.ММ.ГГГГ ЧЧ:ММ", cancelKeyboard()); return;
                }
                session.getData().put("tStart", text.trim());
                session.setState(SessionState.TOURNAMENT_CREATE_END);
                sendText(user.getTelegramId(),
                        "⏰ Дата и время окончания турнира (формат <code>ДД.ММ.ГГГГ ЧЧ:ММ</code>):",
                        cancelKeyboard());
            }
            case TOURNAMENT_CREATE_END -> {
                java.time.LocalDateTime endDate;
                try {
                    endDate = java.time.LocalDateTime.parse(text.trim(),
                            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                } catch (Exception e) {
                    sendText(user.getTelegramId(), "❌ Неверный формат.", cancelKeyboard()); return;
                }
                java.time.LocalDateTime startDate = java.time.LocalDateTime.parse(
                        session.getData().get("tStart"),
                        java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                if (!endDate.isAfter(startDate)) {
                    sendText(user.getTelegramId(), "❌ Дата окончания должна быть позже даты начала.", cancelKeyboard()); return;
                }
                String tName = session.getData().get("tName");
                String tGame = session.getData().get("tGame");
                long fee = Long.parseLong(session.getData().get("tFee"));
                ru.gamebot.platform.domain.model.Tournament t = tournamentService.create(tName, tGame, fee, startDate, endDate);
                session.reset();
                sendText(user.getTelegramId(),
                        "✅ <b>Турнир создан!</b>\n\n"
                        + "📌 " + escape(t.getName()) + "\n"
                        + "💰 Взнос: <b>" + t.getEntryFeeExc() + " EXC</b>\n"
                        + "🚀 Старт: " + t.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "\n"
                        + "⏰ Финиш: " + t.getEndDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "\n\n"
                        + "Турнир будет виден пользователям с момента начала регистрации.",
                        backMenuKeyboard("admin:tournaments"));
            }
            case QUEST_CREATE_TITLE -> {
                session.getData().put("title", text.trim());
                session.setState(SessionState.QUEST_CREATE_DESCRIPTION);
                sendText(user.getTelegramId(), "📝 Отправьте подробное описание квеста.", cancelKeyboard());
            }
            case QUEST_CREATE_DESCRIPTION -> {
                session.getData().put("description", text.trim());
                session.setState(SessionState.QUEST_CREATE_GAME);
                sendText(user.getTelegramId(), "🎮 Напишите название игры.", cancelKeyboard());
            }
            case QUEST_CREATE_GAME -> {
                session.getData().put("game", text.trim());
                session.setState(SessionState.QUEST_CREATE_CATEGORY);
                sendQuestCategoryKeyboard(user);
            }
            case QUEST_CREATE_PLATFORM -> {
                session.setState(SessionState.QUEST_CREATE_DURATION);
                sendText(user.getTelegramId(), "⏳ Укажите срок выполнения в днях (число). Например: <code>3</code>\n\nОтсчёт начнётся с момента, когда игрок возьмёт квест в работу.", cancelKeyboard());
            }
            case QUEST_CREATE_DURATION -> {
                Integer days = parseInteger(text.trim());
                if (days == null || days < 1 || days > 365) {
                    sendText(user.getTelegramId(), "⚠️ Укажите срок числом от 1 до 365 (количество дней).", cancelKeyboard());
                    return;
                }
                int minDays = questService.minDurationDaysForCategory(session.getData().get("category"));
                if (days < minDays) {
                    sendText(user.getTelegramId(),
                            "⚠️ Для категории «" + escape(session.getData().get("category")) + "» минимальный срок — <b>" + minDays + " дн.</b>\n\n"
                                    + "Это минимальное время честного выполнения квеста (кулдаун перед сдачей отчёта). Укажите срок не меньше этого значения.",
                            cancelKeyboard());
                    return;
                }
                session.getData().put("durationDays", days.toString());
                session.getData().put("duration", days + (days == 1 ? " день" : days < 5 ? " дня" : " дней"));
                session.setState(SessionState.QUEST_CREATE_REWARD_XP);
                sendText(user.getTelegramId(), "✨ Сколько XP начислять за квест?", cancelKeyboard());
            }
            case QUEST_CREATE_REWARD_XP -> {
                Long xp = parsePositiveLong(text.trim());
                if (xp == null) {
                    sendText(user.getTelegramId(), "⚠️ XP должен быть целым неотрицательным числом.", cancelKeyboard());
                    return;
                }
                session.getData().put("xp", xp.toString());
                session.setState(SessionState.QUEST_CREATE_REWARD_COINS);
                sendText(user.getTelegramId(), "🪙 Сколько монет начислять за квест?", cancelKeyboard());
            }
            case QUEST_CREATE_REWARD_COINS -> {
                Long coins = parsePositiveLong(text.trim());
                if (coins == null) {
                    sendText(user.getTelegramId(), "⚠️ Монеты должны быть целым неотрицательным числом.", cancelKeyboard());
                    return;
                }
                session.getData().put("coins", coins.toString());
                session.setState(SessionState.QUEST_CREATE_INSTRUCTION);
                sendText(user.getTelegramId(), "📎 Отправьте инструкцию для игрока.", cancelKeyboard());
            }
            case QUEST_CREATE_INSTRUCTION -> {
                session.getData().put("instruction", text.trim());
                session.setState(SessionState.QUEST_CREATE_REQUIREMENTS);
                sendText(user.getTelegramId(), "✅ Укажите требования к подтверждению.", cancelKeyboard());
            }
            case QUEST_CREATE_REQUIREMENTS -> {
                session.getData().put("requirements", text.trim());
                session.setState(SessionState.QUEST_CREATE_LIMIT);
                sendText(user.getTelegramId(), "👥 Укажите лимит участников числом.", cancelKeyboard());
            }
            case QUEST_CREATE_LIMIT -> {
                Integer limit = parseInteger(text.trim());
                if (limit == null || limit < 1) {
                    sendText(user.getTelegramId(), "⚠️ Лимит участников должен быть положительным числом.", cancelKeyboard());
                    return;
                }
                session.getData().put("limit", limit.toString());
                session.setState(SessionState.QUEST_CREATE_PHOTO);
                sendText(user.getTelegramId(),
                        "🖼️ Прикрепите фото к квесту (обложку) или пропустите этот шаг.",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("⏭️ Пропустить фото", "qc:photo:skip")),
                                List.of(keyboardFactory.callback("❌ Отмена", "admin:cancel"))
                        )));
            }
            case REWARD_CREATE_TITLE -> {
                session.getData().put("title", text.trim());
                session.setState(SessionState.REWARD_CREATE_DESCRIPTION);
                sendText(user.getTelegramId(), "📝 Отправьте описание награды.", cancelKeyboard());
            }
            case REWARD_CREATE_DESCRIPTION -> {
                session.getData().put("description", text.trim());
                session.setState(SessionState.REWARD_CREATE_CATEGORY);
                sendText(user.getTelegramId(),
                        "📦 Укажите категорию награды. Например: <code>Деньги</code>, <code>Мерч</code>, <code>Донат</code>",
                        cancelKeyboard());
            }
            case REWARD_CREATE_CATEGORY -> {
                session.getData().put("category", text.trim());
                session.setState(SessionState.REWARD_CREATE_PRICE);
                sendText(user.getTelegramId(), "🪙 Укажите цену в EXC (целое число).", cancelKeyboard());
            }
            case REWARD_CREATE_PRICE -> {
                Long price = parsePositiveLong(text.trim());
                if (price == null) {
                    sendText(user.getTelegramId(), "⚠️ Цена должна быть целым положительным числом.", cancelKeyboard());
                    return;
                }
                session.getData().put("price", price.toString());
                session.setState(SessionState.REWARD_CREATE_PHOTO);
                sendText(user.getTelegramId(),
                        "🖼️ Прикрепите фото к награде или пропустите шаг.",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("⏭️ Пропустить фото", "reward_create:photo:skip")),
                                List.of(keyboardFactory.callback("❌ Отмена", "admin:cancel"))
                        )));
            }
            case REWARD_EDIT_TITLE -> {
                RewardItem rTitle = rewardService.getRewardItem(session.getQuestId());
                rTitle.setTitle(text.trim());
                rewardService.save(rTitle);
                session.reset();
                sendText(user.getTelegramId(), "✅ Название обновлено.", backMenuKeyboard("admin:rewards"));
            }
            case REWARD_EDIT_DESCRIPTION -> {
                RewardItem rDesc = rewardService.getRewardItem(session.getQuestId());
                rDesc.setDescription(text.trim());
                rewardService.save(rDesc);
                session.reset();
                sendText(user.getTelegramId(), "✅ Описание обновлено.", backMenuKeyboard("admin:rewards"));
            }
            case REWARD_EDIT_PRICE -> {
                Long newPrice = parsePositiveLong(text.trim());
                if (newPrice == null) {
                    sendText(user.getTelegramId(), "⚠️ Цена должна быть целым положительным числом.", cancelKeyboard());
                    return;
                }
                RewardItem rPrice = rewardService.getRewardItem(session.getQuestId());
                rPrice.setPriceCoins(newPrice);
                rewardService.save(rPrice);
                session.reset();
                sendText(user.getTelegramId(), "✅ Цена обновлена: <b>" + newPrice + " EXC</b>", backMenuKeyboard("admin:rewards"));
            }
            case REWARD_REJECT_COMMENT -> {
                RewardRequest rejected = rewardService.rejectRequest(session.getQuestId(), text.trim());
                boolean isWithdrawal = "withdrawal".equals(session.getData().get("rejectType"));
                boolean isModFlow = "mod".equals(session.getData().get("rejectBack"));
                session.reset();
                if (isWithdrawal) {
                    notifyUserWithdrawalRejected(rejected);
                    sendText(user.getTelegramId(), "✅ Заявка на вывод отклонена. EXC возвращены пользователю.", null);
                    if (isModFlow) sendModWithdrawals(user);
                    else sendAdminWithdrawals(user);
                } else {
                    notifyUserRewardRejected(rejected);
                    sendText(user.getTelegramId(), "✅ Заявка отклонена. EXC возвращены на баланс пользователя.", null);
                    sendAdminRewardRequests(user);
                }
            }
            case BLOCK_USER_REASON -> {
                Long targetId = session.getQuestId();
                int blockPage = parseInteger(session.getData().getOrDefault("blockPage", "0"));
                AppUser target = userService.findByTelegramId(targetId).orElse(null);
                session.reset();
                if (target == null) {
                    sendText(user.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
                    return;
                }
                userService.blockUser(targetId, text.trim());
                sendText(targetId,
                        "🚫 <b>Ваш аккаунт заблокирован</b>\n\n"
                                + "Причина: <i>" + escape(text.trim()) + "</i>\n\n"
                                + "Если считаете это ошибкой — обратитесь в поддержку клуба.",
                        null);
                sendAdminUserCard(user, targetId, blockPage, "✅ Пользователь заблокирован.");
            }
            case QUEST_TEMPLATE_TITLE -> {
                session.getData().put("title", text.trim());
                session.setState(SessionState.QUEST_TEMPLATE_DESCRIPTION);
                sendText(user.getTelegramId(), "📝 Теперь отправьте <b>описание</b> квеста (суть задания для игрока):", cancelKeyboard());
            }
            case QUEST_TEMPLATE_DESCRIPTION -> {
                session.getData().put("description", text.trim());
                session.setState(SessionState.QUEST_CREATE_COUNCIL);
                showQuestPreview(user, session);
            }
            case QUEST_EDIT_TITLE -> updateQuestTitle(user, session, text);
            case QUEST_EDIT_DESCRIPTION -> updateQuestDescription(user, session, text);
            case QUEST_EDIT_REWARD -> updateQuestReward(user, session, text);
            case QUEST_EDIT_LIMIT -> {
                Integer limit = parseInteger(text.trim());
                if (limit == null || limit < 1) {
                    sendText(user.getTelegramId(), "⚠️ Лимит должен быть положительным числом.", cancelKeyboard());
                    return;
                }
                Quest q = questService.getQuest(session.getQuestId());
                q.setParticipantLimit(limit);
                questService.save(q);
                session.reset();
                sendText(user.getTelegramId(), "✅ Лимит участников обновлён.", mainMenuKeyboard(user));
            }
            case GIFT_INPUT -> {
                String nickname = text.trim();
                AppUser recipient = userService.findByNickname(nickname).orElse(null);
                if (recipient == null) {
                    sendText(user.getTelegramId(),
                        "⚠️ Игрок с ником «" + escape(nickname) + "» не найден. Проверьте написание и попробуйте снова.",
                        backMenuKeyboard("menu:sink"));
                    return;
                }
                if (recipient.getTelegramId().equals(user.getTelegramId())) {
                    sendText(user.getTelegramId(), "⚠️ Нельзя отправить подарок самому себе.", backMenuKeyboard("menu:sink"));
                    session.reset();
                    return;
                }
                try {
                    sinkShopService.purchaseGiftBoost(user, recipient);
                    session.reset();
                    sendText(user.getTelegramId(),
                        "🎁 <b>Подарок отправлен!</b>\n\nВы подарили XP-буст на 24ч игроку <b>" + escape(displayUserName(recipient)) + "</b>.\nСписано 4 500 EXC.",
                        backMenuKeyboard("menu:main"));
                    sendText(recipient.getTelegramId(),
                        "🎁 <b>" + escape(displayUserName(user)) + "</b> подарил(а) тебе XP-буст +20% на 24 часа!\n\nБуст уже активен — удачи в квестах!",
                        backMenuKeyboard("menu:main"));
                } catch (IllegalArgumentException e) {
                    session.reset();
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                }
            }
            case WITHDRAWAL_INPUT -> handleWithdrawalInput(user, session, text);
            case WITHDRAWAL_DETAILS -> handleWithdrawalDetails(user, session, text);
            case WITHDRAWAL_USDT_AMOUNT -> handleWithdrawalUsdtAmount(user, session, text);
            case WITHDRAWAL_USDT_ADDRESS -> handleWithdrawalUsdtAddress(user, session, text);
            case SHOP_GAME_DATA_INPUT -> handleShopGameDataInput(user, session, text);
            case ADMIN_USER_SEARCH -> {
                session.reset();
                try {
                    long searchId = Long.parseLong(text.trim());
                    AppUser found = userService.findByTelegramId(searchId).orElse(null);
                    if (found == null) {
                        sendText(user.getTelegramId(), "❌ Пользователь с TG ID <b>" + searchId + "</b> не найден.", backMenuKeyboard("admin:users:0"));
                    } else {
                        sendAdminUserCard(user, found.getTelegramId(), 0, null);
                    }
                } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "⚠️ TG ID должен быть числом. Попробуйте ещё раз.", backMenuKeyboard("admin:users:0"));
                }
            }
            default -> sendText(user.getTelegramId(), "🧭 Я не жду текст на этом шаге. Вернитесь в меню.", mainMenuKeyboard(user));
        }
    }

    private void sendPlatformQuestion(AppUser user, UserSession session) {
        List<String> selected = resolveSelections(session, "platforms", PLATFORM_OPTIONS);
        sendText(user.getTelegramId(), platformQuestionText(selected),
                selectionKeyboard(PLATFORM_OPTIONS, selected, "reg:platform:", true, false, false));
    }

    private void sendInterestQuestion(AppUser user, UserSession session) {
        List<String> selected = resolveSelections(session, "interests", INTEREST_OPTIONS);
        sendText(user.getTelegramId(), interestQuestionText(selected),
                selectionKeyboard(INTEREST_OPTIONS, selected, "reg:interest:", true, false, false));
    }

    private void finishRegistration(AppUser user, UserSession session, List<String> interests) {
        List<String> platforms = resolveSelections(session, "platforms", PLATFORM_OPTIONS);
        AppUser updated = userService.completeRegistration(
                user,
                session.getData().getOrDefault("nickname", user.getNickname()),
                Integer.parseInt(session.getData().getOrDefault("age", "18")),
                session.getData().getOrDefault("country", "Не указано"),
                platforms,
                interests
        );
        session.reset();
        sendCommunityActivationPrompt(updated, null);
    }

    private void sendMainMenu(AppUser user, String text) {
        if (ROLE_MODER.equals(resolveMenuRole(user, sessionService.get(user.getTelegramId())))) {
            sendModerationHub(user);
            return;
        }
        sendText(user.getTelegramId(), text, mainMenuKeyboard(user));
    }

    private void sendMenuCategory(AppUser user, String title, List<List<InlineKeyboardButton>> items) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(items);
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:main")));
        sendText(user.getTelegramId(), title, keyboardFactory.rowsLayout(rows));
    }

    private void sendQuestsCategory(AppUser user) {
        boolean hasTournament = tournamentService.findCurrentForUser().isPresent();
        String tournamentLabel = hasTournament ? "🏆 Турнир 🔥" : "🏆 Турнир";
        sendMenuCategory(user, "🎯 <b>Квесты и рейтинг</b>", List.of(
                List.of(keyboardFactory.callback("🗺️ Квесты", "menu:quests")),
                List.of(keyboardFactory.callback("🏆 Рейтинг", "menu:rating")),
                List.of(keyboardFactory.callback(tournamentLabel, "menu:tournament"))
        ));
    }

    private void sendWalletCategory(AppUser user) {
        String dailyLabel = userService.isDailyBonusAvailable(user)
                ? "🎁 Забрать ежедневный бонус 🔔"
                : "✅ Бонус за вход получен";
        sendMenuCategory(user, "💰 <b>Кошелёк</b>", List.of(
                List.of(keyboardFactory.callback("💰 Баланс", "menu:balance")),
                List.of(keyboardFactory.callback(dailyLabel, "menu:daily"))
        ));
    }

    private void sendShopCategory(AppUser user) {
        boolean hasPass = seasonService.hasActivePass(user);
        boolean hasSeason = seasonService.findCurrentSeason().isPresent();
        String passLabel = hasPass ? "🎫 Battle Pass ✅" : (hasSeason ? "🎫 Battle Pass 🆕" : "🎫 Battle Pass");
        sendMenuCategory(user, "🛍️ <b>Магазин</b>", List.of(
                List.of(keyboardFactory.callback("🛍️ Магазин наград", "menu:shop")),
                List.of(keyboardFactory.callback("⚡ Предметы", "menu:sink")),
                List.of(keyboardFactory.callback(passLabel, "menu:battlepass"))
        ));
    }

    private void sendClubCategory(AppUser user) {
        long activePolls = pollService.findActive().size();
        String pollLabel = activePolls > 0 ? "🗳 Голосования (" + activePolls + ")" : "🗳 Голосования";
        sendMenuCategory(user, "👥 <b>Клуб</b>", List.of(
                List.of(keyboardFactory.callback("🤝 Рефералы", "menu:referrals")),
                List.of(keyboardFactory.callback("🛡️ EGC Council", "menu:council")),
                List.of(keyboardFactory.callback(pollLabel, "menu:polls")),
                List.of(keyboardFactory.callback("📰 Новости", "menu:news"))
        ));
    }

    private void sendHelpCategory(AppUser user) {
        sendMenuCategory(user, "🆘 <b>Помощь</b>", List.of(
                List.of(keyboardFactory.callback("🆘 Поддержка", "menu:support")),
                List.of(keyboardFactory.callback("❓ Как начать — быстрый старт", "menu:quickstart")),
                List.of(keyboardFactory.url("⭐ Отзывы игроков", "https://t.me/egc_payouts"))
        ));
    }

    private void sendCommunityActivationPrompt(AppUser user, String notice) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("📢 Подписаться на канал", requiredChannelUrl())));
        rows.add(List.of(keyboardFactory.callback("✅ Я подписался", "activation:check")));

        String text = (notice == null || notice.isBlank() ? "" : notice + "\n\n")
                + "🔐 <b>Активация аккаунта</b>\n\n"
                + "Для активации игрового профиля в <b>EXPERIENCE GAMING CLUB</b> нужно подтвердить участие в сообществе.\n\n"
                + "Подпишитесь на канал <b>" + escape(requiredChannelLabel()) + "</b>, а затем вернитесь сюда и нажмите кнопку <b>«Я подписался»</b>.\n\n"
                + "После проверки аккаунт будет активирован автоматически, и откроется полный доступ ко всем игровым разделам.";
        sendText(user.getTelegramId(), text, keyboardFactory.rowsLayout(rows));
    }

    private void handleActivationCheck(CallbackQuery callbackQuery, AppUser user) {
        if (isRequiredChannelMember(user.getTelegramId())) {
            AppUser activated = userService.activateAccount(user);
            ru.gamebot.platform.service.UserService.ReferralActivationResult referral =
                    userService.grantReferralReward(activated);
            sendActivationSuccess(activated, referral);
            if (referral != null) {
                sendText(referral.referrerTelegramId(),
                        "🎉 <b>Твой реферал присоединился!</b>\n\n"
                                + "👤 <b>" + escape(referral.invitedNickname()) + "</b> только что активировал аккаунт по твоей ссылке.\n\n"
                                + "🪙 Тебе начислено: <b>+" + referral.referrerBonus() + " EXC</b>\n\n"
                                + "Ты будешь получать <b>3% от EXC</b>, которые он заработает на квестах в первые 14 дней.",
                        null);
            }
            notifyAdminsNewRegistration(activated);
            answer(callbackQuery.getId(), "Аккаунт активирован");
            return;
        }

        sendCommunityActivationPrompt(user,
                "⚠️ Подписка пока не подтверждена. Убедитесь, что вы подписались на канал, и нажмите кнопку ещё раз.");
        answer(callbackQuery.getId(), "Подписка не найдена");
    }

    private void sendActivationSuccess(AppUser user,
            ru.gamebot.platform.service.UserService.ReferralActivationResult referral) {
        String referralLine = referral != null
                ? "\n🪙 <b>Реферальный бонус: +" + referral.invitedBonus() + " EXC</b> уже на балансе!\n"
                        + "Ещё <b>3 000 EXC</b> придут после первого выполненного квеста.\n"
                : "";
        sendText(user.getTelegramId(),
                "✅ <b>Поздравляем! Ваш игровой профиль активирован.</b>\n\n"
                        + referralLine
                        + "\nТеперь вам доступны:\n"
                        + "Игровые задания\n"
                        + "XP и EXC\n"
                        + "Рейтинг игроков\n"
                        + "Магазин наград\n"
                        + "Реферальная программа и многое другое",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("🚀 Перейти в профиль", "activation:profile")),
                        List.of(keyboardFactory.url("📨 Пригласить друга",
                                "https://t.me/share/url?url=https://t.me/" + getBotUsername()
                                + "?start=ref_" + user.getTelegramId()
                                + "&text=" + java.net.URLEncoder.encode(
                                        "Зарабатывай EXC за игровые квесты в EXPERIENCE GAMING CLUB! 🎮",
                                        java.nio.charset.StandardCharsets.UTF_8)))
                )));
    }

    private void sendQuickStartGuide(AppUser user) {
        String text = "📖 <b>Быстрый старт — как это работает</b>\n\n"
                + "1️⃣ <b>Возьми квест</b>\n"
                + "Раздел 🗺️ Квесты → выбери игру → нажми «Взять квест».\n"
                + "Квесты бывают Лёгкие 🟢, Средние 🟡 и Сложные 🔴 — чем сложнее, тем больше наград.\n\n"
                + "2️⃣ <b>Выполни задание</b>\n"
                + "Сделай скриншот результата прямо в игре.\n"
                + "Вернись в бот → «Мои квесты» → отправь скриншот.\n\n"
                + "3️⃣ <b>Получи EXC</b>\n"
                + "Модератор проверит скриншот в течение 24 часов.\n"
                + "После одобрения EXC и XP зачислятся на баланс автоматически.\n\n"
                + "4️⃣ <b>Выведи деньги</b>\n"
                + "Раздел 🛍️ Магазин наград → Вывод EXC.\n"
                + "Минимум <b>5 000 EXC</b> (50 ₽). Доступен вывод в рубли или USDT.\n"
                + "Курс: <b>100 EXC = 1 ₽</b>.\n\n"
                + "5️⃣ <b>Приглашай друзей</b>\n"
                + "Раздел 🤝 Рефералы → получи ссылку.\n"
                + "Ты будешь получать <b>3% от EXC</b> друга первые 14 дней.\n\n"
                + "❓ Остались вопросы — пиши в 🆘 Поддержку.";
        sendText(user.getTelegramId(), text,
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("🗺️ Перейти к квестам", "menu:quests")),
                        List.of(keyboardFactory.callback("🏠 Главное меню", "menu:main"))
                )));
    }

    private void sendProfile(AppUser user) {
        long rank = userService.getOverallRank(user);
        String achievements = userService.getAchievements(user).isEmpty()
                ? "Пока нет, но первое достижение уже близко."
                : String.join(", ", userService.getAchievements(user));
        String titleLine = user.getProfileTitle() != null
                ? "🏅 Титул: <b>" + escape(user.getProfileTitle()) + "</b>\n"
                : "";
        String boostLine = sinkShopService.isBoostActive(user)
                ? "⚡ Буст EXC +20% активен\n"
                : "";
        String councilBadge = councilService.isCouncilMember(user)
                ? "🛡️ <b>EGC Council</b>\n"
                : "";
        String passLine = seasonService.hasActivePass(user) ? "🎫 <b>Battle Pass активен</b>\n" : "";

        String profileText = "👤 <b>Профиль</b>\n\n"
                + "🎮 <b>" + escape(user.getNickname()) + "</b>\n"
                + councilBadge
                + passLine
                + titleLine
                + "⭐ Уровень: <b>" + userService.getLevelNumber(user.getXp()) + ". "
                + escape(userService.getLevelName(user.getXp())) + "</b>\n"
                + levelProgressLine(user) + "\n\n"
                + "🏆 <b>Текущая форма</b>\n"
                + "🪙 Монеты: <b>" + user.getCoins() + " EXC</b>\n"
                + "💠 Бонус к EXC: <b>+" + userService.getExcBonusPercent(user.getXp()) + "%</b>\n"
                + boostLine
                + "🎟️ Билеты: <b>" + user.getTickets() + "</b>\n"
                + "🥇 Место в рейтинге: <b>" + rank + "</b>\n"
                + "✅ Выполнено квестов: <b>" + user.getCompletedQuests() + "</b>\n"
                + "🔥 Серия входов: <b>" + user.getStreakDays() + " дней</b>\n"
                + "🏅 Лига недели: <b>" + ru.gamebot.platform.service.UserService.getLeague(user.getWeeklyXp()).displayName + "</b>\n\n"
                + "🧩 <b>Игровой стиль</b>\n"
                + "🕹️ Платформы: <b>" + escape(displayValue(user.getPlatformsCsv(), "Подбираются")) + "</b>\n"
                + "🎯 Интересы: <b>" + escape(displayValue(user.getInterestsCsv(), "Открываются")) + "</b>\n"
                + "🤝 Приглашено друзей: <b>" + user.getInvitedFriends() + "</b>\n\n"
                + "🌟 <b>Достижения</b>\n"
                + escape(achievements);

        String avatarBtn = user.getAvatarFileId() != null ? "📷 Сменить аватар" : "📷 Загрузить аватар";
        InlineKeyboardMarkup profileKeyboard = keyboardFactory.rowsLayout(List.of(
                List.of(
                        keyboardFactory.callback("🗺️ Квесты", "menu:quests"),
                        keyboardFactory.callback("💰 Баланс", "profile:balance")
                ),
                List.of(
                        keyboardFactory.callback("🏆 Рейтинг", "menu:rating"),
                        keyboardFactory.callback("🤝 Рефералы", "menu:referrals")
                ),
                List.of(keyboardFactory.callback(avatarBtn, "profile:avatar")),
                List.of(keyboardFactory.callback("✏️ Сменить ник", "profile:nickname")),
                List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
        ));

        if (user.getAvatarFileId() != null) {
            sendPhotoCaption(user.getTelegramId(), user.getAvatarFileId(), profileText, profileKeyboard);
        } else {
            sendText(user.getTelegramId(), profileText, profileKeyboard);
        }
    }

    private void sendDailyBonus(CallbackQuery callbackQuery, AppUser user) {
        if (!userService.isDailyBonusAvailable(user)) {
            answer(callbackQuery.getId(), "Бонус уже получен сегодня");
            int streak = user.getStreakDays();
            long nextBonus = Math.min(150L + (long) streak * 50, 500L);
            sendText(user.getTelegramId(),
                    "✅ <b>Ежедневный бонус уже получен</b>\n\n"
                            + "🔥 Серия: <b>" + streak + " " + dayWord(streak) + " подряд</b>\n\n"
                            + "Возвращайся завтра — тебя ждёт <b>+" + nextBonus + " EXC</b>.",
                    backMenuKeyboard("menu:main"));
            return;
        }
        ru.gamebot.platform.service.UserService.DailyBonusResult result = userService.claimDailyBonus(user);
        if (result == null) {
            answer(callbackQuery.getId(), "Бонус уже получен");
            return;
        }
        StringBuilder msg = new StringBuilder();
        if (result.milestoneText() != null) {
            msg.append(result.milestoneText()).append("\n\n");
        } else {
            msg.append("🎁 <b>Ежедневный бонус получен!</b>\n\n");
        }
        msg.append("🔥 Серия: <b>").append(result.streakDays()).append(" ")
                .append(dayWord(result.streakDays())).append(" подряд</b>\n\n");
        msg.append("🪙 Начислено: <b>+").append(result.totalExc()).append(" EXC</b>");
        if (result.milestoneExc() > 0) {
            msg.append("\n   ├ ежедневный: +").append(result.dailyExc()).append(" EXC");
            msg.append("\n   └ бонус за серию: +").append(result.milestoneExc()).append(" EXC");
        }
        if (result.xpBonus() > 0) {
            msg.append("\n⭐ XP: <b>+").append(result.xpBonus()).append(" XP</b>");
        }
        long nextBonus = Math.min(150L + (long) result.streakDays() * 50, 500L);
        msg.append("\n\n💰 Баланс: <b>").append(user.getCoins() + result.totalExc()).append(" EXC</b>");
        msg.append("\n\nВозвращайся завтра — тебя ждёт <b>+").append(nextBonus).append(" EXC</b>.");
        answer(callbackQuery.getId(), "+" + result.totalExc() + " EXC получено!");
        sendText(user.getTelegramId(), msg.toString(), backMenuKeyboard("menu:main"));
    }

    private static String dayWord(int days) {
        if (days % 100 >= 11 && days % 100 <= 19) return "дней";
        return switch (days % 10) {
            case 1 -> "день";
            case 2, 3, 4 -> "дня";
            default -> "дней";
        };
    }

    private void sendBalance(AppUser user) {
        sendBalance(user, "menu:main");
    }

    private void sendBalance(AppUser user, String backData) {
        double ratio = healthRatioService.getCurrentRatio();
        int ratioPercent = (int) Math.round(ratio * 100);
        long effectiveQuestReward = healthRatioService.applyRatio(100);
        sendText(user.getTelegramId(),
                "💰 <b>Баланс</b>\n\n"
                        + "🪙 Монеты клуба: <b>" + user.getCoins() + " EXC</b>\n"
                        + "💱 Курс обмена: <b>100 EXC = 1 ₽</b>\n"
                        + "💠 Активный бонус к EXC: <b>+" + userService.getExcBonusPercent(user.getXp()) + "%</b>\n"
                        + "🎟️ Билеты сезона: <b>" + user.getTickets() + "</b>\n"
                        + "✨ Общий XP: <b>" + user.getXp() + "</b>\n"
                        + "📈 XP за неделю: <b>" + user.getWeeklyXp() + "</b>\n\n"
                        + "📊 <b>Состояние фонда клуба: " + ratioPercent + "%</b>\n"
                        + hrExplanationLine(ratioPercent, effectiveQuestReward) + "\n"
                        + "Чем активнее вы играете, тем быстрее открываете сильные награды и поднимаетесь в рейтинге.",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("💸 Вывести EXC", "shop:withdraw")),
                        List.of(keyboardFactory.callback("⬅️ Назад", backData))
                )));
    }

    private String hrExplanationLine(int ratioPercent, long effectiveRewardPer100) {
        if (ratioPercent >= 100) {
            return "✅ Клуб работает на полную мощность — награды начисляются в полном объёме.";
        }
        if (ratioPercent >= 70) {
            return "💡 Клуб выплачивает <b>" + ratioPercent + "%</b> от заявленной награды. Пример: за квест с наградой 1 500 EXC вы получите <b>" + (effectiveRewardPer100 * 15) + " EXC</b>.";
        }
        return "⚠️ Клуб временно выплачивает <b>" + ratioPercent + "%</b> от заявленной награды. Пример: за квест с наградой 1 500 EXC сейчас начислят <b>" + (effectiveRewardPer100 * 15) + " EXC</b>. Когда фонд пополнится — курс вернётся к 100%.";
    }

    private void sendQuestGames(AppUser user) {
        List<String> games = questService.findActiveGameNames();
        if (games.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📭 Сейчас в клубе нет активных квестов по играм. Как только новые задания появятся, они откроются здесь.",
                    backMenuKeyboard("menu:main"));
            return;
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String game : games) {
            rows.add(List.of(keyboardFactory.callback("🎮 " + trim(game, 28), "quests:game:" + encodeGameToken(game))));
        }
        rows.add(List.of(keyboardFactory.callback("📂 Мои квесты", "menu:myquests")));
        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));

        sendText(user.getTelegramId(),
                "🗺️ <b>Квесты по играм</b>\n\n"
                        + "Сначала выберите игру, а затем откройте задания именно по ней.\n"
                        + "Так навигация остаётся чистой, а нужные квесты находятся быстрее.",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendQuestCategories(AppUser user) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                keyboardFactory.callback("⚡ Легкие", "quests:cat:Легкие"),
                keyboardFactory.callback("🎯 Средние", "quests:cat:Средние")
        ));
        rows.add(List.of(
                keyboardFactory.callback("🏰 Сложные", "quests:cat:Сложные")
        ));
        rows.add(List.of(keyboardFactory.callback("📂 Мои квесты", "menu:myquests")));
        rows.add(List.of(keyboardFactory.callback("📚 Все квесты", "quests:cat:all")));
        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));
        sendText(user.getTelegramId(),
                "🗺️ <b>Квесты</b>\n\n"
                        + "Здесь собраны легкие старты, средние челленджи и сложные марафоны.\n"
                        + "Откройте подборку или сразу перейдите к своим активным заданиям.",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendQuestCategories(AppUser user, String gameName) {
        if (gameName == null || gameName.isBlank()) {
            sendQuestGames(user);
            return;
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                keyboardFactory.callback("⚡ Легкие", "quests:list:" + encodeGameToken(gameName) + ":fast"),
                keyboardFactory.callback("🎯 Средние", "quests:list:" + encodeGameToken(gameName) + ":medium")
        ));
        rows.add(List.of(keyboardFactory.callback("🏰 Сложные", "quests:list:" + encodeGameToken(gameName) + ":long")));
        rows.add(List.of(keyboardFactory.callback("📚 Все квесты", "quests:list:" + encodeGameToken(gameName) + ":all")));
        rows.add(List.of(
                keyboardFactory.callback("⬅️ Назад", "menu:quests"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));

        String caption = "🎮 <b>" + escape(gameName) + "</b>\n\nВыберите нужную категорию и откройте подборку квестов именно по этой игре.";
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(rows);

        gameCatalogService.getPhotoFileId(gameName).ifPresentOrElse(
                photoFileId -> sendPhotoCaption(user.getTelegramId(), photoFileId, caption, keyboard),
                () -> sendText(user.getTelegramId(), caption, keyboard)
        );
    }

    private void sendQuestList(AppUser user, String gameName, String category) {
        if (gameName == null || gameName.isBlank()) {
            sendQuestGames(user);
            return;
        }

        List<Quest> quests = category == null
                ? questService.findActiveByGameName(gameName)
                : questService.findActiveByGameNameAndCategory(gameName, category);
        if (quests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📭 В этой категории пока нет активных квестов. Проверьте позже или выберите другую подборку.",
                    backMenuKeyboard("quests:game:" + encodeGameToken(gameName)));
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Quest quest : quests) {
            buttons.add(keyboardFactory.callback(
                    "🎯 " + trim(quest.getTitle(), 32),
                    "quest:view:" + encodeGameToken(gameName) + ":" + categoryToken(category) + ":" + quest.getId()
            ));
        }

        String title = category == null ? "🎮 " + gameName : "🎮 " + gameName + " • " + category;
        sendText(user.getTelegramId(),
                "<b>" + escape(title) + "</b>\n\n"
                        + "Ниже собраны активные задания по выбранной игре. Откройте карточку, чтобы увидеть награды и условия прохождения.",
                verticalWithBackMenu(buttons, "⬅️ Назад", "quests:game:" + encodeGameToken(gameName)));
    }

    private void handleQuestListAction(CallbackQuery callbackQuery, AppUser user, String payload) {
        String[] parts = payload.split(":");
        if (parts.length != 2) {
            answer(callbackQuery.getId(), "Список квестов недоступен");
            return;
        }
        String gameName = decodeGameToken(parts[0]);
        String category = categoryFromToken(parts[1]);
        if (gameName == null) {
            answer(callbackQuery.getId(), "Список квестов недоступен");
            return;
        }
        sendQuestList(user, gameName, category);
        answerSilently(callbackQuery.getId());
    }

    private void handleQuestView(CallbackQuery callbackQuery, AppUser user, UserSession session, String payload) {
        String[] parts = payload.split(":");
        if (parts.length != 2 && parts.length != 3) {
            answer(callbackQuery.getId(), "Карточка квеста недоступна");
            return;
        }
        Long questId = parseLong(parts[parts.length - 1]);
        if (questId == null) {
            answer(callbackQuery.getId(), "Карточка квеста недоступна");
            return;
        }
        sendQuestCard(user, questId, backDataFromQuestViewToken(parts), "⬅️ Назад", null);
        answerSilently(callbackQuery.getId());
    }

    private void sendQuestCard(AppUser user, Long questId) {
        sendQuestCard(user, questId, "menu:quests", "⬅️ Назад", null);
    }

    private void sendQuestCard(AppUser user, Long questId, String backData, String backText, String notice) {
        sessionService.get(user.getTelegramId()).getData().put("quest_back_data", backData);
        Quest quest = questService.getQuest(questId);
        QuestSubmission latest = questService.getLatestSubmission(user, quest);
        String statusText = latest == null ? "Не начат" : humanStatus(latest.getStatus());

        String deadlineLine = "";
        if (latest != null && latest.getStatus() == SubmissionStatus.DRAFT && latest.getExpiresAt() != null) {
            if (questService.isExpired(latest)) {
                deadlineLine = "⌛ Дедлайн: <b>истёк</b>\n";
            } else {
                long hoursLeft = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), latest.getExpiresAt());
                if (hoursLeft < 24) {
                    deadlineLine = "⚠️ Дедлайн: <b>через " + hoursLeft + " ч</b>\n";
                } else {
                    long daysLeft = hoursLeft / 24;
                    deadlineLine = "📅 Дедлайн: <b>через " + daysLeft + " д</b>\n";
                }
            }
        } else if (quest.getDurationDays() > 0 && (latest == null || latest.getStatus() == SubmissionStatus.REJECTED)) {
            deadlineLine = "⏳ Срок: <b>" + quest.getDurationText() + "</b> с момента старта\n";
        }

        long cooldownLeft = questService.getCooldownHoursLeft(user, quest);
        String displayStatus = cooldownLeft > 0
                ? "⏳ Кулдаун (" + cooldownLeft + " ч)"
                : statusText;

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        boolean hasActiveSubmission = latest != null && latest.getStatus() != SubmissionStatus.CANCELLED;
        long activeSlots = questService.countActiveDrafts(user);
        long maxSlots = sinkShopService.getMaxQuestSlots(user);
        boolean slotsFull = activeSlots >= maxSlots && !hasActiveSubmission;
        boolean gameCooldown = !hasActiveSubmission && cooldownLeft == 0 && questService.isCooldownActive(user, quest);
        if (cooldownLeft > 0) {
            buttons.add(keyboardFactory.callback("⏳ Доступно через " + cooldownLeft + " ч", "noop"));
        } else if (slotsFull) {
            buttons.add(keyboardFactory.callback("📂 Квест уже активен", "noop"));
        } else if (gameCooldown) {
            buttons.add(keyboardFactory.callback("⏳ Кулдаун 24ч по этой игре", "noop"));
        } else if (!hasActiveSubmission) {
            buttons.add(keyboardFactory.callback("🚀 Взять", "quest:take:" + questId));
        }
        long submitCooldown = (latest != null && latest.getStatus() == SubmissionStatus.DRAFT)
                ? questService.getSubmitCooldownHoursLeft(latest) : 0;
        if (submitCooldown > 0) {
            buttons.add(keyboardFactory.callback("⏳ Отчёт через " + submitCooldown + " ч", "noop"));
        } else {
            buttons.add(keyboardFactory.callback("📤 Отчёт", "quest:report:" + questId));
        }
        if (isEffectiveAdmin(user)) {
            buttons.add(keyboardFactory.callback("✏️ Правка", "admin:quest:" + questId));
        }

        String sponsorBadge = quest.isSponsored() ? "💎 <b>Спонсорский квест</b>\n" : "";
        sendText(user.getTelegramId(),
                (notice == null ? "" : notice + "\n\n")
                        + sponsorBadge
                        + "🎯 <b>" + escape(quest.getTitle()) + "</b>\n\n"
                        + "🎮 Игра: <b>" + escape(quest.getGameName()) + "</b>\n"
                        + "📚 Формат: <b>" + escape(quest.getCategory()) + "</b>\n"
                        + "🕹️ Платформа: <b>" + escape(quest.getPlatform()) + "</b>\n"
                        + deadlineLine
                        + "📌 Статус: <b>" + escape(displayStatus) + "</b>\n\n"
                        + "🏆 <b>Награда</b>\n"
                        + "✨ +" + quest.getRewardXp() + " XP\n"
                        + "🪙 +" + quest.getRewardCoins() + " монет\n\n"
                        + "📝 <b>Суть задания</b>\n" + escape(quest.getDescription()) + "\n\n"
                        + "📎 <b>Что нужно сделать</b>\n" + escape(quest.getInstruction()) + "\n\n"
                        + "✅ <b>Что примет модерация</b>\n" + escape(quest.getRequirements()),
                verticalWithBackMenu(buttons, backText, backData));
    }

    private void handleTakeQuest(CallbackQuery callbackQuery, AppUser user, Long questId) {
        Quest quest = questService.getQuest(questId);
        QuestSubmission latest = questService.getLatestSubmission(user, quest);
        if (latest != null && latest.getStatus() == SubmissionStatus.DRAFT) {
            answerSilently(callbackQuery.getId());
            if (questService.isExpired(latest)) {
                sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                        "⌛ Срок выполнения квеста истёк. Вы не успели сдать отчёт вовремя.");
            } else {
                sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                        "🧭 Этот квест уже добавлен в работу. Ниже оставил карточку с кнопкой для отчёта.");
            }
            return;
        }
        if (latest != null && (latest.getStatus() == SubmissionStatus.PENDING || latest.getStatus() == SubmissionStatus.APPROVED)) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "📌 По этому квесту уже есть активный прогресс. Используйте карточку ниже, чтобы посмотреть статус или отправить отчёт.");
            return;
        }
        if (latest != null && (latest.getStatus() == SubmissionStatus.REJECTED || latest.getStatus() == SubmissionStatus.NEEDS_INFO)) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "❌ Ваш отчёт по этому квесту был отклонён. Нажмите «📤 Отчёт», чтобы исправить ошибки и переотправить.");
            return;
        }

        // Slot limit check
        long activeSlots = questService.countActiveDrafts(user);
        long maxSlots = sinkShopService.getMaxQuestSlots(user);
        if (activeSlots >= maxSlots) {
            answerSilently(callbackQuery.getId());
            sendText(user.getTelegramId(),
                    "📂 У вас уже " + activeSlots + " активных квеста. Завершите или отмените один из них, либо купите доп. слот (2 000 EXC) в разделе Предметы клуба.",
                    backMenuKeyboard("menu:myquests"));
            return;
        }

        // Same quest: max 1 per 24h
        if (questService.isSameQuestCooldownActive(user, quest)) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "⏳ Этот квест можно выполнять не чаще 1 раза в 24 часа.");
            return;
        }

        // 3.4 Antifaud: cooldown 24h between same-game quests
        if (questService.isCooldownActive(user, quest)) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "⏳ Кулдаун активен. Повторный квест в этой игре доступен через 24 часа.\n\n💡 Можно снять кулдаун за 1 500 EXC в разделе Предметы клуба.");
            return;
        }

        // Fix 2: atomic 1-hour global cooldown (transactional check+set prevents race condition)
        QuestSubmission newSubmission;
        try {
            newSubmission = questService.takeQuestAtomically(user, quest);
        } catch (IllegalArgumentException e) {
            answerSilently(callbackQuery.getId());
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("cooldown:")) {
                long minsLeft = Long.parseLong(msg.split(":")[1]);
                sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                        "⏳ Новый квест можно брать раз в час. Подождите ещё <b>" + minsLeft + " мин.</b>");
            } else {
                sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад", "⚠️ " + msg);
            }
            return;
        }
        answerSilently(callbackQuery.getId());

        long weeklyCount = questService.getWeeklyCompletionsOfType(user, quest);
        String notice = "🚀 Квест активен! Приступайте к игре, когда выполните задание, отправьте отчёт прямо из этой карточки.";
        if (weeklyCount >= 3) {
            notice += "\n\n⚠️ Вы уже выполнили 3+ таких квеста за неделю — награда EXC будет снижена на 50%.";
        }

        Quest freshQuest = questService.getQuest(questId);
        QuestSubmission submission = newSubmission;
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(keyboardFactory.callback("📤 Отчёт", "quest:report:" + questId));
        buttons.add(keyboardFactory.callback("📂 Мои квесты", "menu:myquests"));
        buttons.add(keyboardFactory.callback("🏠 Меню", "menu:main"));
        if (isEffectiveAdmin(user)) {
            buttons.add(keyboardFactory.callback("✏️ Правка", "admin:quest:" + questId));
        }

        String deadlineLine = "";
        if (submission != null && submission.getExpiresAt() != null) {
            long hoursLeft = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), submission.getExpiresAt());
            long daysLeft = hoursLeft / 24;
            deadlineLine = "📅 Дедлайн: <b>через " + daysLeft + " д</b>\n";
        }

        sendText(user.getTelegramId(),
                notice + "\n\n"
                        + "🎯 <b>" + escape(freshQuest.getTitle()) + "</b>\n\n"
                        + "🎮 Игра: <b>" + escape(freshQuest.getGameName()) + "</b>\n"
                        + "📚 Формат: <b>" + escape(freshQuest.getCategory()) + "</b>\n"
                        + "🕹️ Платформа: <b>" + escape(freshQuest.getPlatform()) + "</b>\n"
                        + deadlineLine
                        + "📌 Статус: <b>В процессе</b>\n\n"
                        + "🏆 <b>Награда</b>\n"
                        + "✨ +" + freshQuest.getRewardXp() + " XP\n"
                        + "🪙 +" + freshQuest.getRewardCoins() + " монет",
                keyboardFactory.smartLayout(buttons));
    }

    private void handleReportStart(CallbackQuery callbackQuery, AppUser user, UserSession session, Long questId) {
        Quest quest = questService.getQuest(questId);
        QuestSubmission latest = questService.getLatestSubmission(user, quest);
        if (latest == null) {
            latest = questService.createDraftSubmission(user, quest);
        } else if (latest.getStatus() == SubmissionStatus.PENDING) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "⏳ <b>Отчёт уже на проверке.</b>\n\nДождитесь решения модератора — дублировать заявку нельзя.");
            return;
        } else if (latest.getStatus() == SubmissionStatus.APPROVED) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "✅ <b>Этот квест уже одобрен и оплачен.</b>\n\nПовторная сдача отчёта по нему невозможна.");
            return;
        } else if (latest.getStatus() == SubmissionStatus.CANCELLED) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "🚫 <b>Эта попытка была отменена.</b>\n\nЧтобы попробовать снова, возьмите квест заново кнопкой «🚀 Взять».");
            return;
        } else if (latest.getStatus() == SubmissionStatus.REJECTED || latest.getStatus() == SubmissionStatus.NEEDS_INFO) {
            // Fix 4: cooldown 1h after rejection to prevent instant resubmit spam
            LocalDateTime rejectedAt = latest.getUpdatedAt();
            if (rejectedAt != null && LocalDateTime.now().isBefore(rejectedAt.plusHours(1))) {
                long minsLeft = java.time.temporal.ChronoUnit.MINUTES.between(LocalDateTime.now(), rejectedAt.plusHours(1));
                answerSilently(callbackQuery.getId());
                sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                        "⏳ После отклонения повторный отчёт можно отправить через <b>" + Math.max(1, minsLeft) + " мин.</b>");
                return;
            }
            latest = questService.resetToDraft(latest);
        }

        long submitCooldownLeft = questService.getSubmitCooldownHoursLeft(latest);
        if (submitCooldownLeft > 0) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "⏳ Отчёт по этому квесту можно отправить через <b>" + submitCooldownLeft + " ч.</b>\n\n"
                    + "Это минимальное время честного выполнения квеста.");
            return;
        }

        if (questService.isExpired(latest)) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "⌛ Срок выполнения этого квеста истёк. Отчёт больше не принимается.");
            return;
        }

        session.reset();
        session.setState(SessionState.REPORT_MEDIA_COLLECTING);
        session.setQuestId(questId);
        session.setSubmissionId(latest.getId());
        session.getData().put("report_photos", "");
        session.getData().put("report_comment", "");

        sendText(user.getTelegramId(),
                "📤 <b>Отчёт по квесту</b>\n\n"
                        + "Отправьте скриншот(ы), видео, файл или ссылку.\n"
                        + "Можно отправить сразу несколько фото альбомом или по одному — бот их соберёт.\n"
                        + "Когда закончите — нажмите <b>«Отправить отчёт»</b>.\n\n"
                        + "🎯 Квест: <b>" + escape(quest.getTitle()) + "</b>",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("❌ Отмена", "menu:myquests"))
                )));
        answer(callbackQuery.getId(), "Жду отчёт");
    }

    private void handleReportMessage(AppUser user, UserSession session, Message message) {
        String mediaType = "text";
        String fileId = null;
        String text = message.getCaption();

        if (message.hasPhoto()) {
            mediaType = "photo";
            List<PhotoSize> photos = message.getPhoto();
            fileId = photos.get(photos.size() - 1).getFileId();
        } else if (message.hasVideo()) {
            mediaType = "video";
            fileId = message.getVideo().getFileId();
        } else if (message.hasDocument()) {
            mediaType = "document";
            fileId = message.getDocument().getFileId();
        } else if (message.hasText()) {
            text = message.getText();
        }

        QuestSubmission submission = questService.getSubmission(session.getSubmissionId());
        String externalLink = extractUrl(text);
        questService.submitReport(submission, mediaType, fileId, externalLink, text == null ? "Без комментария" : text);
        session.reset();

        notifyModeratorsAboutSubmission(submission.getId());
        sendText(user.getTelegramId(),
                "✅ <b>Отчёт отправлен</b>\n\n"
                        + "Материалы уже ушли в очередь проверки.\n"
                        + "После одобрения награда начислится автоматически.",
                backMenuKeyboard("menu:myquests"));
    }

    private void handleReportCollecting(AppUser user, UserSession session, Message message) {
        String photos = session.getData().getOrDefault("report_photos", "");
        String comment = session.getData().getOrDefault("report_comment", "");

        if (message.hasPhoto()) {
            List<PhotoSize> photoList = message.getPhoto();
            String fileId = photoList.get(photoList.size() - 1).getFileId();
            photos = photos.isBlank() ? fileId : photos + "||" + fileId;
            if (message.getCaption() != null && !message.getCaption().isBlank()) {
                comment = message.getCaption();
            }
            session.getData().put("report_photos", photos);
            session.getData().put("report_comment", comment);

            String mediaGroupId = message.getMediaGroupId();
            Long submissionId = session.getSubmissionId();
            Long telegramId = user.getTelegramId();

            if (mediaGroupId != null) {
                // Album: cancel previous timer for this group and schedule a new one
                ScheduledFuture<?> existing = albumTimers.remove(mediaGroupId);
                if (existing != null) existing.cancel(false);
                ScheduledFuture<?> timer = albumScheduler.schedule(() -> {
                    albumTimers.remove(mediaGroupId);
                    String currentPhotos = session.getData().getOrDefault("report_photos", "");
                    int count = currentPhotos.isBlank() ? 0
                            : (int) currentPhotos.chars().filter(c -> c == '|').count() / 2 + 1;
                    sendText(telegramId,
                            "🖼 Добавлено фото: <b>" + count + " шт.</b> Можете прислать ещё или нажмите «Отправить отчёт».",
                            keyboardFactory.rowsLayout(List.of(
                                    List.of(keyboardFactory.callback("📤 Отправить отчёт", "report:submit:" + submissionId)),
                                    List.of(keyboardFactory.callback("❌ Отмена", "menu:myquests"))
                            )));
                }, 1500, TimeUnit.MILLISECONDS);
                albumTimers.put(mediaGroupId, timer);
            } else {
                int count = photos.isBlank() ? 0 : (int) photos.chars().filter(c -> c == '|').count() / 2 + 1;
                sendText(telegramId,
                        "🖼 Фото добавлено (" + count + " шт.). Можете прислать ещё или нажмите «Отправить отчёт».",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("📤 Отправить отчёт", "report:submit:" + submissionId)),
                                List.of(keyboardFactory.callback("❌ Отмена", "menu:myquests"))
                        )));
            }
        } else if (message.hasVideo()) {
            String fileId = message.getVideo().getFileId();
            String text = message.getCaption();
            QuestSubmission submission = questService.getSubmission(session.getSubmissionId());
            questService.submitReport(submission, "video", fileId, extractUrl(text), text == null ? "Без комментария" : text);
            session.reset();
            notifyModeratorsAboutSubmission(submission.getId());
            sendText(user.getTelegramId(),
                    "✅ <b>Отчёт отправлен</b>\n\nМатериалы ушли в очередь проверки.",
                    backMenuKeyboard("menu:myquests"));
        } else if (message.hasDocument()) {
            String fileId = message.getDocument().getFileId();
            String text = message.getCaption();
            QuestSubmission submission = questService.getSubmission(session.getSubmissionId());
            questService.submitReport(submission, "document", fileId, extractUrl(text), text == null ? "Без комментария" : text);
            session.reset();
            notifyModeratorsAboutSubmission(submission.getId());
            sendText(user.getTelegramId(),
                    "✅ <b>Отчёт отправлен</b>\n\nМатериалы ушли в очередь проверки.",
                    backMenuKeyboard("menu:myquests"));
        } else if (message.hasText()) {
            String text = message.getText();
            comment = text;
            session.getData().put("report_comment", comment);
            sendText(user.getTelegramId(),
                    "💬 Комментарий сохранён. Теперь пришлите скриншот или нажмите «Отправить отчёт».",
                    keyboardFactory.rowsLayout(List.of(
                            List.of(keyboardFactory.callback("📤 Отправить отчёт", "report:submit:" + session.getSubmissionId())),
                            List.of(keyboardFactory.callback("❌ Отмена", "menu:myquests"))
                    )));
        }
    }

    private void sendMySubmissions(AppUser user) {
        List<QuestSubmission> submissions = questService.getUserSubmissions(user);
        if (submissions.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📭 У вас пока нет взятых или отправленных квестов.\n\nОткройте раздел квестов и начните с первого задания.",
                    backMenuKeyboard("menu:quests"));
            return;
        }

        StringBuilder builder = new StringBuilder("📂 <b>Мои квесты</b>\n\n");
        submissions.stream().limit(10).forEach(submission -> builder
                .append("🎯 <b>").append(escape(submission.getQuest().getTitle())).append("</b>\n")
                .append("📌 Статус: <b>").append(escape(humanStatus(submission.getStatus()))).append("</b>\n")
                .append("🕒 Обновлено: <b>").append(escape(submission.getUpdatedAt().format(DATE_TIME_FORMATTER))).append("</b>\n\n"));

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        submissions.stream().limit(10).forEach(submission ->
                buttons.add(keyboardFactory.callback("🎯 " + trim(submission.getQuest().getTitle(), 30), "myquest:view:" + submission.getId()))
        );
        sendText(user.getTelegramId(), builder.toString(), verticalWithBackMenu(buttons, "⬅️ Назад", "menu:quests"));
    }

    private void sendMyQuestCard(AppUser user, Long submissionId) {
        QuestSubmission submission = questService.getSubmission(submissionId);
        Quest quest = submission.getQuest();
        String moderatorComment = submission.getModeratorComment() == null || submission.getModeratorComment().isBlank()
                ? ""
                : "\n\n💬 Комментарий модератора:\n" + escape(submission.getModeratorComment());

        boolean canCancel = submission.getStatus() == SubmissionStatus.DRAFT
                || submission.getStatus() == SubmissionStatus.PENDING
                || submission.getStatus() == SubmissionStatus.NEEDS_INFO
                || submission.getStatus() == SubmissionStatus.REJECTED;

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(keyboardFactory.callback("📤 Отчёт", "quest:report:" + quest.getId()));
        if (canCancel) {
            buttons.add(keyboardFactory.callback("❌ Отменить квест", "myquest:cancel:" + submission.getId()));
        }

        sendText(user.getTelegramId(),
                "📂 <b>Мой квест</b>\n\n"
                        + "🎯 <b>" + escape(quest.getTitle()) + "</b>\n"
                        + "📌 Статус: <b>" + escape(humanStatus(submission.getStatus())) + "</b>\n"
                        + "🕒 Обновлено: <b>" + escape(submission.getUpdatedAt().format(DATE_TIME_FORMATTER)) + "</b>\n"
                        + "✨ XP: <b>+" + quest.getRewardXp() + "</b>\n"
                        + "🪙 Монеты: <b>+" + quest.getRewardCoins() + "</b>\n\n"
                        + "📝 <b>Суть задания</b>\n" + escape(quest.getDescription()) + moderatorComment,
                verticalWithBackMenu(buttons, "⬅️ Назад", "menu:myquests"));
    }

    private void sendRatingMenu(AppUser user) {
        ru.gamebot.platform.service.UserService.League myLeague =
                ru.gamebot.platform.service.UserService.getLeague(user.getWeeklyXp());
        String leagueLine = myLeague.excPrize > 0
                ? "Твоя лига: <b>" + myLeague.displayName + "</b> (приз " + myLeague.excPrize + " EXC в конце недели)\n\n"
                : "Твоя лига: <b>" + myLeague.displayName + "</b>\nДля приза нужно " + ru.gamebot.platform.service.UserService.League.SILVER.minWeeklyXp + "+ XP за неделю\n\n";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                keyboardFactory.callback("🌍 Общий", "rate:overall"),
                keyboardFactory.callback("📆 Недельный", "rate:weekly")
        ));
        rows.add(List.of(keyboardFactory.callback("🏅 Таблица лиг", "rate:leagues")));
        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));
        sendText(user.getTelegramId(),
                "🏆 <b>Рейтинг</b>\n\n" + leagueLine
                        + "Еженедельно лидеры лиг получают EXC-призы.",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendLeaderboard(AppUser user, String type) {
        if ("leagues".equals(type)) {
            sendLeagueTable(user);
            return;
        }
        boolean weekly = "weekly".equals(type);
        List<AppUser> players = weekly ? userService.topWeekly() : userService.topOverall();
        StringBuilder builder = new StringBuilder();

        String[] medals = {"🥇", "🥈", "🥉"};

        if (weekly) {
            builder.append("📆 <b>Недельный рейтинг</b>\n");
            builder.append("<i>Сбрасывается каждый понедельник</i>\n\n");
        } else {
            builder.append("🌍 <b>Общий рейтинг</b>\n\n");
        }

        for (int i = 0; i < players.size(); i++) {
            AppUser player = players.get(i);
            long xp = weekly ? player.getWeeklyXp() : player.getXp();
            String place = i < 3 ? medals[i] : "<b>" + (i + 1) + ".</b>";
            builder.append(place).append(" ").append(escape(player.getNickname()));
            builder.append(" — <b>").append(xp).append(" XP</b>\n");
        }

        long rank = weekly ? userService.getWeeklyRank(user) : userService.getOverallRank(user);
        long myXp = weekly ? user.getWeeklyXp() : user.getXp();
        builder.append("\n");
        builder.append("▶ Ты: <b>").append(rank).append(" место</b> • <b>").append(myXp).append(" XP</b>");
        if (weekly) {
            ru.gamebot.platform.service.UserService.League myLeague =
                    ru.gamebot.platform.service.UserService.getLeague(user.getWeeklyXp());
            builder.append("\n🏅 Лига: <b>").append(myLeague.displayName).append("</b>");
            if (myLeague.excPrize > 0) {
                builder.append(" · приз <b>+").append(myLeague.excPrize).append(" EXC</b>");
            }
        }
        sendText(user.getTelegramId(), builder.toString(), backMenuKeyboard("menu:rating"));
    }

    private void sendLeagueTable(AppUser user) {
        ru.gamebot.platform.service.UserService.League myLeague =
                ru.gamebot.platform.service.UserService.getLeague(user.getWeeklyXp());
        ru.gamebot.platform.service.UserService.League[] leagues =
                ru.gamebot.platform.service.UserService.League.values();

        StringBuilder sb = new StringBuilder("🏅 <b>Таблица лиг EGC</b>\n");
        sb.append("<i>Лиги сбрасываются каждый понедельник в 00:00</i>\n\n");

        for (int i = 0; i < leagues.length; i++) {
            ru.gamebot.platform.service.UserService.League l = leagues[i];
            int nextMin = i + 1 < leagues.length ? leagues[i + 1].minWeeklyXp : Integer.MAX_VALUE;
            boolean isMine = l == myLeague;

            String range = i + 1 < leagues.length
                    ? l.minWeeklyXp + " – " + (nextMin - 1) + " XP"
                    : "от " + l.minWeeklyXp + " XP";
            String prize = l.excPrize > 0 ? "+" + l.excPrize + " EXC" : "без приза";

            if (isMine) sb.append("▶ ");
            sb.append("<b>").append(l.displayName).append("</b>");
            if (isMine) sb.append(" ← ты здесь");
            sb.append("\n");
            sb.append("   📊 ").append(range).append("\n");
            sb.append("   💰 Приз: <b>").append(prize).append("</b>\n");

            // прогресс до следующей лиги
            if (isMine && i + 1 < leagues.length) {
                int min = l.minWeeklyXp;
                int max = nextMin;
                long xp = user.getWeeklyXp();
                int pct = (int) Math.min(100, (xp - min) * 100 / (max - min));
                int filled = pct / 10;
                String bar = "█".repeat(filled) + "░".repeat(10 - filled);
                sb.append("   ").append(bar).append(" ").append(pct).append("%\n");
                sb.append("   До следующей лиги: <b>").append(Math.max(0, max - xp)).append(" XP</b>\n");
            } else if (isMine) {
                sb.append("   🏆 Максимальная лига!\n");
            }

            sb.append("\n");
        }

        sb.append("🎯 Твой XP за эту неделю: <b>").append(user.getWeeklyXp()).append(" XP</b>");
        if (myLeague.excPrize > 0) {
            sb.append("\n💸 Приз в конце недели: <b>+").append(myLeague.excPrize).append(" EXC</b>");
        }
        sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:rating"));
    }

    private void sendReferrals(AppUser user) {
        String referralLink = "https://t.me/" + appProperties.getBotUsername() + "?start=ref_" + user.getTelegramId();
        long earned = user.getReferralEarnedExc();
        long[] milestones = {3_000, 10_000, 30_000, 100_000};
        long nextMilestone = milestones[milestones.length - 1];
        for (long m : milestones) {
            if (earned < m) { nextMilestone = m; break; }
        }
        int progressPct = (int) Math.min(100, earned * 100 / nextMilestone);
        int filled = progressPct / 10;
        String bar = "█".repeat(filled) + "░".repeat(10 - filled);

        sendText(user.getTelegramId(),
                "🤝 <b>Реферальная программа EGC</b>\n\n"
                        + "🔗 Ваша ссылка:\n" + escape(referralLink) + "\n\n"
                        + "👥 Приглашено друзей: <b>" + user.getInvitedFriends() + "</b>\n"
                        + "💎 Заработано на рефералах: <b>" + earned + " EXC</b>\n\n"
                        + "📊 Прогресс до " + nextMilestone + " EXC:\n"
                        + "[" + bar + "] " + progressPct + "%\n\n"
                        + "━━━━━━━━━━━━━━━\n"
                        + "🎁 <b>Как работает:</b>\n\n"
                        + "Шаг 1 — друг вступает в клуб\n"
                        + "• Тебе сразу: <b>+300 EXC</b>\n"
                        + "• Другу сразу: <b>+500 EXC</b>\n\n"
                        + "Шаг 2 — друг выполняет первый квест\n"
                        + "• Другу бонусом: <b>+3 000 EXC</b>\n\n"
                        + "Шаг 3 — друг зарабатывает квестами\n"
                        + "• Ты получаешь <b>3% от каждого его EXC</b> в течение первых 14 дней автоматически\n\n"
                        + "━━━━━━━━━━━━━━━\n"
                        + "Скопируй ссылку и отправь другу — остальное система сделает сама.",
                backMenuKeyboard("menu:main"));
    }

    private void sendShop(AppUser user) {
        List<RewardItem> rewards = rewardService.findAvailableRewards();
        int ratioPercent = (int) Math.round(healthRatioService.getCurrentRatio() * 100);
        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (!rewards.isEmpty()) {
            java.util.LinkedHashMap<String, List<RewardItem>> byCategory = new java.util.LinkedHashMap<>();
            for (RewardItem reward : rewards) {
                String cat = reward.getCategory() != null ? reward.getCategory() : "Другое";
                byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(reward);
            }
            for (Map.Entry<String, List<RewardItem>> entry : byCategory.entrySet()) {
                rows.add(List.of(keyboardFactory.callback("── " + entry.getKey() + " ──", "noop")));
                // Group items by purchaseGroup; groups with >1 item shown as single entry
                java.util.LinkedHashMap<String, List<RewardItem>> byGroup = new java.util.LinkedHashMap<>();
                for (RewardItem reward : entry.getValue()) {
                    String group = reward.getPurchaseGroup() != null ? reward.getPurchaseGroup() : reward.getId().toString();
                    byGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(reward);
                }
                for (Map.Entry<String, List<RewardItem>> groupEntry : byGroup.entrySet()) {
                    List<RewardItem> groupItems = groupEntry.getValue();
                    if (groupItems.size() > 1) {
                        // Show as single entry leading to denomination picker
                        RewardItem first = groupItems.get(0);
                        String groupLabel = groupItemLabel(first.getTitle());
                        boolean anyAvailable = groupItems.stream().anyMatch(r ->
                                !shopLimitService.getItemStatus(user, r).startsWith("🔒"));
                        String icon = anyAvailable ? "🎁" : "🔒";
                        rows.add(List.of(keyboardFactory.callback(
                                icon + " " + groupLabel + " — выбор номинала",
                                "shop:group:" + groupEntry.getKey())));
                    } else {
                        RewardItem reward = groupItems.get(0);
                        long price = rewardService.effectivePrice(reward);
                        String status = shopLimitService.getItemStatus(user, reward);
                        String icon = status.startsWith("🔒") ? "🔒"
                                : status.startsWith("⏳") ? "⏳"
                                : status.startsWith("🚫") ? "🚫"
                                : "🎁";
                        rows.add(List.of(keyboardFactory.callback(
                                icon + " " + trim(reward.getTitle(), 22) + " — " + price + " EXC",
                                "shop:view:" + reward.getId())));
                    }
                }
            }
        }

        List<RewardItem> comingSoon = rewardService.findComingSoon();
        if (!comingSoon.isEmpty()) {
            rows.add(List.of(keyboardFactory.callback("── ⏳ Скоро в магазине ──", "noop")));
            for (RewardItem item : comingSoon) {
                rows.add(List.of(keyboardFactory.callback("🔜 " + trim(item.getTitle(), 28), "shop:soon:" + item.getId())));
            }
        }

        rows.add(List.of(
                keyboardFactory.callback("📋 Мои заявки", "menu:my-rewards"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));

        sendText(user.getTelegramId(),
                "🛍️ <b>Магазин наград</b>\n\n"
                        + "🪙 Ваш баланс: <b>" + user.getCoins() + " EXC</b>\n"
                        + "📊 Состояние фонда: <b>" + ratioPercent + "%</b>\n"
                        + "📤 Лимит вывода: <b>" + sinkShopService.getMonthlyLimit(user.getXp()) + " EXC/мес</b> (осталось: " + remaining + " EXC)\n"
                        + "💱 Курс вывода: <b>100 EXC = 1 ₽</b>\n"
                        + withdrawalLevelHint(user),
                keyboardFactory.rowsLayout(rows));
    }

    private void sendWithdrawalMethodChoice(AppUser user) {
        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);
        String text = "💸 <b>Вывод EXC</b>\n\n"
                + "🪙 Баланс: <b>" + user.getCoins() + " EXC</b>\n"
                + "📤 Остаток лимита: <b>" + remaining + " EXC (из " + sinkShopService.getMonthlyLimit(user.getXp()) + "/мес)</b>\n"
                + "💱 Курс: <b>100 EXC = 1 ₽</b>\n"
                + "⚠️ Минимум: <b>5 000 EXC</b>\n"
                + withdrawalLevelHint(user) + "\n\n"
                + "Выберите способ получения:";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("💸 В рублях (Сбербанк / СБП)", "shop:withdraw:rub")));
        rows.add(List.of(keyboardFactory.callback("💎 В USDT (Telegram Wallet, TON)", "shop:withdraw:usdt")));
        rows.add(List.of(keyboardFactory.callback("📋 Мои заявки на вывод", "menu:my-withdrawals")));
        rows.add(List.of(keyboardFactory.callback("❌ Отмена", "menu:balance")));
        sendText(user.getTelegramId(), text, keyboardFactory.rowsLayout(rows));
    }

    private void sendWithdrawalUsdtWalletQuestion(AppUser user) {
        sendText(user.getTelegramId(),
                "💎 <b>Вывод в USDT</b>\n\nЕсть ли у вас кошелёк в Telegram Wallet (@wallet)?",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("✅ Да, есть кошелёк", "shop:withdraw:usdt:has_wallet")),
                        List.of(keyboardFactory.callback("❌ Нет кошелька", "shop:withdraw:usdt:no_wallet")),
                        List.of(keyboardFactory.callback("⬅️ Назад", "shop:withdraw"))
                )));
    }

    private void sendWithdrawalUsdtNoWalletGuide(AppUser user) {
        sendText(user.getTelegramId(),
                "💎 <b>Как создать кошелёк</b>\n\n"
                        + "1. Нажми кнопку ниже — откроется <b>кошелёк</b> в Telegram\n"
                        + "2. Пройди короткую регистрацию (1–2 минуты)\n"
                        + "3. Вернись сюда и нажми <b>«У меня есть кошелёк»</b>",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.url("🚀 Открыть Telegram Wallet", "https://t.me/wallet/start?startapp=ref-3-PaQlujnvUGU")),
                        List.of(keyboardFactory.callback("✅ У меня есть кошелёк", "shop:withdraw:usdt:has_wallet")),
                        List.of(keyboardFactory.callback("⬅️ Назад", "shop:withdraw"))
                )));
    }

    private void sendWithdrawalUsdtAmountScreen(AppUser user) {
        sendText(user.getTelegramId(),
                "💎 <b>Вывод в USDT</b>\n\nВведите сумму в EXC, которую хотите вывести.",
                cancelKeyboard());
    }

    private void handleWithdrawalUsdtAmount(AppUser user, UserSession session, String text) {
        long amount;
        try {
            amount = Long.parseLong(text.trim().replace(" ", ""));
        } catch (NumberFormatException e) {
            sendText(user.getTelegramId(), "⚠️ Введите сумму числом, например: <b>5000</b>", cancelKeyboard());
            return;
        }
        if (amount < 5000) {
            sendText(user.getTelegramId(), "⚠️ Минимальная сумма вывода — <b>5 000 EXC</b>.", cancelKeyboard());
            return;
        }
        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);
        if (amount > remaining) {
            sendText(user.getTelegramId(), "⚠️ Превышен месячный лимит. Доступно: <b>" + remaining + " EXC</b>.", cancelKeyboard());
            return;
        }
        if (amount > user.getCoins()) {
            sendText(user.getTelegramId(), "⚠️ Недостаточно EXC. Баланс: <b>" + user.getCoins() + " EXC</b>.", cancelKeyboard());
            return;
        }
        double ratio = healthRatioService.getCurrentRatio();
        long rubles = Math.round(amount * ratio / 100.0);
        java.math.BigDecimal rublesDecimal = java.math.BigDecimal.valueOf(rubles);
        java.math.BigDecimal usdtRate = exchangeRateService.getUsdtRubRate();
        java.math.BigDecimal usdtAmount = exchangeRateService.rubToUsdt(rublesDecimal);
        String rateNote = exchangeRateService.isUsingFallback()
                ? "📈 Курс: 1 USDT ≈ " + usdtRate.setScale(2, java.math.RoundingMode.HALF_DOWN) + " ₽ (приблизительно)"
                : "📈 Курс: 1 USDT = " + usdtRate.setScale(2, java.math.RoundingMode.HALF_DOWN) + " ₽";
        session.getData().put("usdt_exc_amount", String.valueOf(amount));
        session.getData().put("usdt_rubles", String.valueOf(rubles));
        session.setState(SessionState.WITHDRAWAL_USDT_ADDRESS);
        String msg = "💎 <b>Сумма принята</b>\n\n"
                + "💸 " + amount + " EXC → <b>" + rubles + " ₽</b> → ~<b>" + usdtAmount + " USDT</b>\n"
                + rateNote + "\n\n"
                + "━━━━━━━━━━━━━━━\n"
                + "📋 <b>Как найти адрес кошелька:</b>\n\n"
                + "1. Открой @wallet в Telegram (или кнопку ниже)\n"
                + "2. Нажми <b>«Получить»</b> или <b>«Deposit»</b>\n"
                + "3. Выбери <b>USDT</b> → сеть <b>TON</b>\n"
                + "4. Нажми <b>«Скопировать адрес»</b>\n"
                + "5. Вернись сюда и вставь адрес\n\n"
                + "⚠️ <b>Адрес начинается с UQ... или EQ...</b>\n"
                + "━━━━━━━━━━━━━━━\n\n"
                + "Введите или вставьте адрес TON-кошелька:";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("💎 Открыть Telegram Wallet", "https://t.me/wallet/start?startapp=ref-3-PaQlujnvUGU")));
        rows.add(List.of(keyboardFactory.url("💬 Помощь — @GressToEx", "https://t.me/GressToEx")));
        rows.add(List.of(keyboardFactory.callback("❌ Отмена", "common:cancel")));
        sendText(user.getTelegramId(), msg, keyboardFactory.rowsLayout(rows));
    }

    private void handleWithdrawalUsdtAddress(AppUser user, UserSession session, String text) {
        String wallet = text.trim();
        if (wallet.length() < 20 || wallet.contains(" ")) {
            sendText(user.getTelegramId(),
                    "⚠️ <b>Некорректный адрес кошелька</b>\n\n"
                    + "Адрес TON должен начинаться с <b>UQ...</b> или <b>EQ...</b> и содержать 48 символов.\n\n"
                    + "Как найти: @wallet → Получить → USDT → TON → Скопировать адрес.\n\n"
                    + "Попробуйте ещё раз:",
                    cancelKeyboard());
            return;
        }
        if (rewardService.hasWithdrawalTodayOrPending(user)) {
            session.reset();
            sendText(user.getTelegramId(),
                "⚠️ <b>Лимит: 1 заявка на вывод в сутки.</b>\n\n"
                    + "Следующую заявку можно создать через 24 часа после предыдущей.",
                backMenuKeyboard("menu:main"));
            return;
        }
        long excAmount = Long.parseLong(session.getData().getOrDefault("usdt_exc_amount", "0"));
        long rubles = Long.parseLong(session.getData().getOrDefault("usdt_rubles", "0"));
        java.math.BigDecimal usdtRate2 = exchangeRateService.getUsdtRubRate();
        java.math.BigDecimal usdtAmount2 = exchangeRateService.rubToUsdt(java.math.BigDecimal.valueOf(rubles));
        try {
            RewardRequest usdtReq = rewardService.createUsdtWithdrawalRequest(user, excAmount, rubles, wallet);
            session.reset();
            sendText(user.getTelegramId(),
                    "✅ <b>Заявка на вывод в USDT принята!</b>\n\n"
                    + "🔢 Номер заявки: <b>В-" + usdtReq.getId() + "</b>\n"
                    + "💸 Сумма: <b>" + excAmount + " EXC</b>\n"
                    + "💵 Эквивалент: <b>" + rubles + " ₽</b> → ~<b>" + usdtAmount2 + " USDT</b>\n"
                    + "📈 Курс: 1 USDT = " + usdtRate2.setScale(2, java.math.RoundingMode.HALF_DOWN) + " ₽\n"
                    + "💎 Способ: <b>USDT · TON</b>\n"
                    + "📬 Кошелёк: <code>" + escape(wallet) + "</code>\n\n"
                    + "Администратор обработает заявку в течение 24 часов.",
                    backMenuKeyboard("menu:main"));
            notifyAdminsAboutWithdrawal(user, usdtReq);
        } catch (IllegalArgumentException e) {
            sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), cancelKeyboard());
        }
    }

    private void sendWithdrawalScreen(AppUser user) {
        sendText(user.getTelegramId(),
                "💸 <b>Вывод в рублях</b>\n\nВведите сумму в EXC, которую хотите вывести.",
                cancelKeyboard());
    }

    private String withdrawalLevelHint(AppUser user) {
        long xp = user.getXp();
        long nextXp;
        long nextLimit;
        String nextLevel;
        if (xp < 1_000)       { nextXp = 1_000;   nextLimit = 25_000;  nextLevel = "Игрок"; }
        else if (xp < 5_000)  { nextXp = 5_000;   nextLimit = 50_000;  nextLevel = "Ветеран"; }
        else if (xp < 15_000) { nextXp = 15_000;  nextLimit = 80_000;  nextLevel = "Элита"; }
        else if (xp < 35_000) { nextXp = 35_000;  nextLimit = 100_000; nextLevel = "Легенда"; }
        else if (xp < 75_000) { nextXp = 75_000;  nextLimit = 150_000; nextLevel = "Герой EXPERIENCE"; }
        else return "";
        long xpNeeded = nextXp - xp;
        return "\n💡 До уровня <b>" + nextLevel + "</b> ещё <b>" + xpNeeded + " XP</b> → лимит вывода вырастет до <b>" + nextLimit + " EXC/мес</b>";
    }

    private void sendUserRewardRequests(AppUser user) {
        List<RewardRequest> requests = rewardService.findUserRequests(user).stream()
                .filter(r -> !"Вывод".equals(r.getRewardItem().getCategory()))
                .toList();
        if (requests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📋 <b>Мои заявки</b>\n\nВы ещё не делали заявок на награды.",
                    backMenuKeyboard("menu:shop"));
            return;
        }
        StringBuilder sb = new StringBuilder("📋 <b>Мои заявки</b>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (RewardRequest req : requests) {
            String status = switch (req.getStatus()) {
                case PENDING -> "⏳ Ожидает";
                case IN_PROGRESS -> "🔄 В разработке";
                case APPROVED -> "✅ Выдано";
                case REJECTED -> "❌ Отклонено";
                case CANCELLED -> "🚫 Отменено";
            };
            sb.append("• М-").append(reqDisplayId(req)).append(" <b>").append(escape(req.getRewardItem().getTitle())).append("</b> — ").append(status);
            if (req.getStatus() == RewardRequestStatus.REJECTED && req.getAdminComment() != null) {
                sb.append("\n  📝 ").append(escape(req.getAdminComment()));
            }
            sb.append("\n");
            if (req.getStatus() == RewardRequestStatus.PENDING) {
                rows.add(List.of(keyboardFactory.callback(
                        "🚫 Отменить «" + trim(req.getRewardItem().getTitle(), 20) + "»",
                        "reward:cancel:" + req.getId())));
            }
        }
        sb.append("\n💡 <i>Отменить заявку можно только в статусе ⏳ Ожидает. После того как администратор возьмёт заявку в разработку (🔄 В разработке), отмена доступна только через поддержку.</i>");
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:shop")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendUserWithdrawalRequests(AppUser user) {
        List<RewardRequest> requests = rewardService.findUserRequests(user).stream()
                .filter(r -> "Вывод".equals(r.getRewardItem().getCategory()))
                .toList();
        if (requests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "💸 <b>Мои заявки на вывод</b>\n\nУ вас ещё нет заявок на вывод EXC.",
                    backMenuKeyboard("shop:withdraw"));
            return;
        }
        StringBuilder sb = new StringBuilder("💸 <b>Мои заявки на вывод</b>\n\n");
        List<List<InlineKeyboardButton>> rows = new java.util.ArrayList<>();
        for (RewardRequest req : requests) {
            String status = switch (req.getStatus()) {
                case PENDING -> "⏳ Ожидает";
                case IN_PROGRESS -> "🔄 В обработке";
                case APPROVED -> "✅ Выплачено";
                case REJECTED -> "❌ Отклонено";
                case CANCELLED -> "🚫 Отменено";
            };
            sb.append("• В-").append(reqDisplayId(req)).append(" <b>").append(escape(req.getRewardItem().getTitle())).append("</b> — ").append(status);
            if (req.getStatus() == RewardRequestStatus.REJECTED && req.getAdminComment() != null) {
                sb.append("\n  📝 ").append(escape(req.getAdminComment()));
            }
            sb.append("\n");
            if (req.getStatus() == RewardRequestStatus.PENDING) {
                rows.add(List.of(keyboardFactory.callback("❌ Отменить заявку В-" + reqDisplayId(req), "reward:cancel:" + req.getId())));
            }
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "shop:withdraw")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void handleUserRewardCancel(CallbackQuery callbackQuery, AppUser user, Long reqId) {
        try {
            RewardRequest req = rewardService.cancelRequest(reqId, user);
            answer(callbackQuery.getId(), "Заявка отменена");
            boolean isWithdrawal = "Вывод".equals(req.getRewardItem().getCategory());
            sendText(user.getTelegramId(),
                    "🚫 <b>Заявка отменена</b>\n\n"
                            + "🎁 <b>" + escape(req.getRewardItem().getTitle()) + "</b>\n\n"
                            + "💰 EXC возвращены на ваш баланс.",
                    backMenuKeyboard(isWithdrawal ? "menu:my-withdrawals" : "menu:my-rewards"));
            notifyAdminsRewardCancelled(user, req);
        } catch (IllegalArgumentException e) {
            answer(callbackQuery.getId(), e.getMessage());
        }
    }

    private void notifyAdminsRewardCancelled(AppUser user, RewardRequest req) {
        String usernameStr = user.getTelegramUsername() != null
                ? "@" + user.getTelegramUsername() : "#" + user.getTelegramId();
        String text = "🚫 <b>Заявка отменена пользователем</b>\n\n"
                + "👤 " + usernameStr + " (<b>" + escape(user.getNickname()) + "</b>)\n"
                + "🎁 <b>" + escape(req.getRewardItem().getTitle()) + "</b>\n"
                + "🪙 " + req.getRewardItem().getPriceCoins() + " EXC возвращено";
        for (Long adminId : adminService.allAdminIds()) {
            sendText(adminId, text, null);
        }
    }

    private void sendSinkShop(AppUser user) {
        boolean xpBoostActive = sinkShopService.isXpBoostActive(user);
        boolean excBoostActive = sinkShopService.isExcBoostActive(user);
        boolean insuranceActive = user.isRetryInsuranceActive();
        boolean slotActive = sinkShopService.hasExtraSlot(user);
        String titleLine = user.getProfileTitle() != null ? "🏅 Текущий титул: <b>" + escape(user.getProfileTitle()) + "</b>\n" : "";

        java.time.format.DateTimeFormatter dtFmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");
        StringBuilder info = new StringBuilder();
        info.append("⚡ <b>Предметы клуба</b>\n\n");
        info.append("🪙 Баланс: <b>").append(user.getCoins()).append(" EXC</b>\n");
        if (!titleLine.isEmpty()) info.append(titleLine);
        if (xpBoostActive) info.append("⚡ XP-буст активен до: <b>").append(user.getXpBoostActiveUntil().format(dtFmt)).append("</b>\n");
        if (excBoostActive) info.append("⚡ EXC-буст активен до: <b>").append(user.getExcBoostActiveUntil().format(dtFmt)).append("</b>\n");
        if (slotActive) info.append("📂 Доп. слот активен до: <b>").append(user.getQuestSlotExtraUntil().format(dtFmt)).append("</b>\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(keyboardFactory.callback("— Бусты —", "sink:noop")));
        if (xpBoostActive) {
            rows.add(List.of(keyboardFactory.callback("⚡ XP-буст активен ✅", "sink:xpboost_info")));
        } else {
            rows.add(List.of(keyboardFactory.callback("⚡ XP +20% • 24ч — 3 000 EXC", "sink:xpboost:24")));
            rows.add(List.of(keyboardFactory.callback("⚡ XP +20% • 72ч — 7 500 EXC", "sink:xpboost:72")));
        }

        if (excBoostActive) {
            rows.add(List.of(keyboardFactory.callback("⚡ EXC-буст активен ✅", "sink:excboost_info")));
        } else {
            rows.add(List.of(keyboardFactory.callback("⚡ EXC +20% • 24ч — 3 000 EXC", "sink:excboost:24")));
            rows.add(List.of(keyboardFactory.callback("⚡ EXC +20% • 72ч — 7 500 EXC", "sink:excboost:72")));
        }

        if (!xpBoostActive && !excBoostActive) {
            rows.add(List.of(keyboardFactory.callback("⚡⚡ Двойной буст • 24ч — 5 000 EXC", "sink:doubleboost:24")));
        }

        rows.add(List.of(keyboardFactory.callback("— Квесты —", "sink:noop")));
        rows.add(List.of(keyboardFactory.callback("🔀 Реролл квеста — 2 000 EXC", "sink:reroll")));

        if (insuranceActive) {
            rows.add(List.of(keyboardFactory.callback("🛡️ Страховка активна ✅", "sink:insurance_info")));
        } else {
            rows.add(List.of(keyboardFactory.callback("🛡️ Страховка провала — 1 500 EXC", "sink:insurance")));
        }

        if (slotActive) {
            rows.add(List.of(keyboardFactory.callback("📂 Доп. слот активен ✅", "sink:slot_info")));
        } else {
            rows.add(List.of(keyboardFactory.callback("📂 Доп. слот квеста 48ч — 2 000 EXC", "sink:extraslot")));
        }

        rows.add(List.of(keyboardFactory.callback("⏱️ Снятие кулдауна — 1 500 EXC", "sink:cooldown_info")));

        rows.add(List.of(keyboardFactory.callback("— Социальные —", "sink:noop")));
        rows.add(List.of(keyboardFactory.callback("🎁 Подарок другу (буст) — 4 500 EXC", "sink:gift")));
        rows.add(List.of(keyboardFactory.callback("⚔️ 🔒 Дуэль — Скоро", "sink:soon")));
        rows.add(List.of(keyboardFactory.callback("📢 🔒 Место в ТОП-посте — Скоро", "sink:soon")));

        rows.add(List.of(keyboardFactory.callback("— Кастомизация —", "sink:noop")));
        rows.add(List.of(keyboardFactory.callback("🎭 Титулы профиля", "sink:titles")));

        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));

        sendText(user.getTelegramId(), info.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void handleSinkAction(CallbackQuery callbackQuery, AppUser user, String action) {
        switch (action) {
            case "reroll" -> {
                try {
                    sinkShopService.purchaseReroll(user);
                    sendText(user.getTelegramId(),
                            "🔀 <b>Реролл активирован</b>\n\nСписано 50 EXC. Перейдите в раздел квестов — там уже другой набор заданий.",
                            backMenuKeyboard("menu:sink"));
                } catch (IllegalArgumentException e) {
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                }
            }
            case "boost" -> {
                try {
                    sinkShopService.purchaseBoost(user);
                    sendText(user.getTelegramId(),
                            "⚡ <b>Буст активирован!</b>\n\nВы получаете +20% к EXC за все квесты в течение 24 часов.\nСписано 3 000 EXC.",
                            backMenuKeyboard("menu:sink"));
                } catch (IllegalArgumentException e) {
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                }
            }
            case "boost_info" -> sendText(user.getTelegramId(),
                    "⚡ Буст уже активен. Действует до: <b>" + (user.getExcBoostActiveUntil() != null ? user.getExcBoostActiveUntil().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) : "—") + "</b>",
                    backMenuKeyboard("menu:sink"));
            case "xpboost_info" -> sendText(user.getTelegramId(),
                    "⚡ XP-буст активен до: <b>" + fmt(user.getXpBoostActiveUntil()) + "</b>",
                    backMenuKeyboard("menu:sink"));
            case "excboost_info" -> sendText(user.getTelegramId(),
                    "⚡ EXC-буст активен до: <b>" + fmt(user.getExcBoostActiveUntil()) + "</b>",
                    backMenuKeyboard("menu:sink"));
            case "slot_info" -> sendText(user.getTelegramId(),
                    "📂 Доп. слот активен до: <b>" + fmt(user.getQuestSlotExtraUntil()) + "</b>",
                    backMenuKeyboard("menu:sink"));
            case "noop" -> { /* category header — do nothing */ }
            case "soon" -> sendText(user.getTelegramId(),
                    "🔒 <b>Скоро!</b>\n\nЭта функция появится в следующем обновлении. Следи за каналом!",
                    backMenuKeyboard("menu:sink"));
            case "extraslot" -> {
                try {
                    sinkShopService.purchaseExtraSlot(user);
                    sendText(user.getTelegramId(),
                        "📂 <b>Доп. слот активирован!</b>\n\nТеперь вы можете вести 3 квеста одновременно в течение 48 часов.\nСписано 2 000 EXC.",
                        backMenuKeyboard("menu:sink"));
                } catch (IllegalArgumentException e) {
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                }
            }
            case "cooldown_info" -> {
                List<List<InlineKeyboardButton>> rows2 = new ArrayList<>();
                rows2.add(List.of(keyboardFactory.callback("⏱️ Купить снятие — 1 500 EXC", "sink:buycooldown")));
                rows2.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:sink")));
                sendText(user.getTelegramId(),
                    "⏱️ <b>Снятие кулдауна</b>\n\nСнимает 24-часовой кулдаун для следующего квеста в любой игре.\nСтоимость: 1 500 EXC. Лимит: 2 раза в сутки.\n\n💡 После покупки перейдите к нужному квесту — кулдаун будет снят автоматически при взятии.",
                    keyboardFactory.rowsLayout(rows2));
            }
            case "buycooldown" -> {
                try {
                    sinkShopService.purchaseCooldownRemoval(user);
                    sendText(user.getTelegramId(),
                        "⏱️ <b>Снятие кулдауна активировано!</b>\n\nВаш следующий квест, если на него действует кулдаун, будет доступен без ожидания.\nСписано 1 500 EXC.",
                        backMenuKeyboard("menu:sink"));
                } catch (IllegalArgumentException e) {
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                }
            }
            case "gift" -> {
                UserSession giftSession = sessionService.get(user.getTelegramId());
                giftSession.setState(SessionState.GIFT_INPUT);
                sendText(user.getTelegramId(),
                    "🎁 <b>Подарок другу</b>\n\nОтправьте XP-буст на 24 часа другому игроку.\nСтоимость: 4 500 EXC.\n\nВведите ник получателя (как в профиле бота):",
                    backMenuKeyboard("menu:sink"));
            }
            case "insurance" -> {
                try {
                    sinkShopService.purchaseInsurance(user);
                    sendText(user.getTelegramId(),
                            "🛡️ <b>Страховка активирована!</b>\n\nЕсли ваш следующий отчёт по квесту будет отклонён — вы сможете отправить его повторно без штрафа.\nСписано 75 EXC.",
                            backMenuKeyboard("menu:sink"));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                }
            }
            case "insurance_info" -> sendText(user.getTelegramId(),
                    "🛡️ Страховка активна. Она сработает при следующем отклонённом отчёте.",
                    backMenuKeyboard("menu:sink"));
            case "titles" -> sendSinkTitles(user);
            default -> {
                if (action.startsWith("buy_title:")) {
                    handleTitlePurchase(callbackQuery, user, action.substring("buy_title:".length()));
                } else if (action.startsWith("xpboost:")) {
                    int hours = action.equals("xpboost:72") ? 72 : 24;
                    long price = hours == 72 ? SinkShopService.PRICE_XP_BOOST_72H : SinkShopService.PRICE_XP_BOOST_24H;
                    try {
                        sinkShopService.purchaseXpBoost(user, hours);
                        sendText(user.getTelegramId(),
                            "⚡ <b>XP-буст активирован!</b>\n\n+20% к XP за все квесты в течение " + hours + " часов.\nСписано " + price + " EXC.",
                            backMenuKeyboard("menu:sink"));
                    } catch (IllegalArgumentException e) {
                        sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                    }
                } else if (action.startsWith("excboost:")) {
                    int hours = action.equals("excboost:72") ? 72 : 24;
                    long price = hours == 72 ? SinkShopService.PRICE_EXC_BOOST_72H : SinkShopService.PRICE_EXC_BOOST_24H;
                    try {
                        sinkShopService.purchaseExcBoostTimed(user, hours);
                        sendText(user.getTelegramId(),
                            "⚡ <b>EXC-буст активирован!</b>\n\n+20% к EXC за все квесты в течение " + hours + " часов.\nСписано " + price + " EXC.",
                            backMenuKeyboard("menu:sink"));
                    } catch (IllegalArgumentException e) {
                        sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                    }
                } else if (action.startsWith("doubleboost:")) {
                    try {
                        sinkShopService.purchaseDoubleBoost(user, 24);
                        sendText(user.getTelegramId(),
                            "⚡⚡ <b>Двойной буст активирован!</b>\n\n+20% к XP и +20% к EXC за все квесты в течение 24 часов.\nСписано 350 EXC.",
                            backMenuKeyboard("menu:sink"));
                    } catch (IllegalArgumentException e) {
                        sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                    }
                } else {
                    sendSinkShop(user);
                }
            }
        }
        answerSilently(callbackQuery.getId());
    }

    private String fmt(java.time.LocalDateTime dt) {
        return dt != null ? dt.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")) : "—";
    }

    private void sendSinkTitles(AppUser user) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("🌱 Новый игрок — 1 500 EXC", "sink:buy_title:Новый игрок:1500")));
        rows.add(List.of(keyboardFactory.callback("🔥 Квест-хантер — 4 500 EXC", "sink:buy_title:Квест-хантер:4500")));
        rows.add(List.of(keyboardFactory.callback("👑 Элита клуба — 7 500 EXC", "sink:buy_title:Элита клуба:7500")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:sink")));
        sendText(user.getTelegramId(),
                "🎭 <b>Титулы профиля</b>\n\nТитул отображается в вашем профиле и виден другим игрокам.\nПокупка заменяет текущий титул.",
                keyboardFactory.rowsLayout(rows));
    }

    private void handleTitlePurchase(CallbackQuery callbackQuery, AppUser user, String payload) {
        String[] parts = payload.split(":");
        if (parts.length != 2) {
            answerSilently(callbackQuery.getId());
            return;
        }
        String title = parts[0];
        Long price = parseLong(parts[1]);
        if (price == null) {
            answerSilently(callbackQuery.getId());
            return;
        }
        try {
            sinkShopService.purchaseTitle(user, title, price);
            sendText(user.getTelegramId(),
                    "🏅 <b>Титул «" + escape(title) + "» получен!</b>\n\nСписано " + price + " EXC. Титул отображается в вашем профиле.",
                    backMenuKeyboard("menu:sink"));
        } catch (IllegalArgumentException e) {
            sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
        }
    }

    private void sendGroupPicker(AppUser user, String purchaseGroup) {
        List<RewardItem> items = rewardService.findByPurchaseGroup(purchaseGroup);
        if (items.isEmpty()) { sendShop(user); return; }
        String groupLabel = groupItemLabel(items.get(0).getTitle());
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (RewardItem item : items) {
            long price = rewardService.effectivePrice(item);
            String status = shopLimitService.getItemStatus(user, item);
            String icon = status.startsWith("🔒") ? "🔒" : status.startsWith("⏳") ? "⏳" : "🎁";
            // Extract denomination part: everything after last space-dash-space
            String denom = item.getTitle().replaceAll(".*- ", "");
            rows.add(List.of(keyboardFactory.callback(icon + " " + denom + " — " + price + " EXC", "shop:view:" + item.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:shop"), keyboardFactory.callback("🏠 Меню", "menu:main")));
        sendText(user.getTelegramId(),
                "🎁 <b>" + escape(groupLabel) + "</b>\n\nВыберите номинал:",
                keyboardFactory.rowsLayout(rows));
    }

    private String groupItemLabel(String title) {
        // "CS2 - Пополнение Steam 150 ₽" → "CS2 - Пополнение Steam"
        return title.replaceAll("\\s+\\d+\\s*[₽$€¥]?\\s*$", "").trim();
    }

    private void sendRewardCard(AppUser user, Long rewardId) {
        sendRewardCard(user, rewardId, null);
    }

    private void sendRewardCard(AppUser user, Long rewardId, String notice) {
        RewardItem reward = rewardService.getRewardItem(rewardId);
        long effectivePrice = rewardService.effectivePrice(reward);
        String priceNote = effectivePrice != reward.getPriceCoins()
                ? " (базовая: " + reward.getPriceCoins() + " EXC)"
                : "";
        String limitStatus = shopLimitService.getItemStatus(user, reward);
        String text = (notice == null ? "" : notice + "\n\n")
                + "🎁 <b>" + escape(reward.getTitle()) + "</b>\n\n"
                + "📦 Категория: <b>" + escape(reward.getCategory()) + "</b>\n"
                + "📝 " + escape(reward.getDescription()) + "\n\n"
                + "🪙 Стоимость: <b>" + effectivePrice + " EXC</b>" + priceNote + "\n"
                + limitStatus;
        InlineKeyboardMarkup keyboard = verticalWithBackMenu(
                List.of(keyboardFactory.callback("🛒 Обменять", "shop:buy:" + rewardId)),
                "⬅️ Назад",
                "menu:shop"
        );
        if (reward.getPhotoFileId() != null && !reward.getPhotoFileId().isBlank()) {
            sendPhotoCaption(user.getTelegramId(), reward.getPhotoFileId(), text, keyboard);
            return;
        }
        sendText(user.getTelegramId(), text, keyboard);
    }

    private void handleRewardPurchase(CallbackQuery callbackQuery, AppUser user, Long rewardId) {
        RewardItem reward = rewardService.getRewardItem(rewardId);
        if (reward.getUserDataPrompt() != null && !reward.getUserDataPrompt().isBlank()) {
            UserSession session = sessionService.get(user.getTelegramId());
            session.reset();
            session.setState(SessionState.SHOP_GAME_DATA_INPUT);
            session.getData().put("pendingRewardId", String.valueOf(rewardId));
            sendText(user.getTelegramId(),
                    "📋 <b>Для оформления заявки нужны ваши данные</b>\n\n"
                            + escape(reward.getUserDataPrompt()),
                    cancelKeyboard());
            answerSilently(callbackQuery.getId());
            return;
        }
        completePurchase(callbackQuery, user, reward, null);
    }

    private void handleShopGameDataInput(AppUser user, UserSession session, String text) {
        Long rewardId = parseLong(session.getData().getOrDefault("pendingRewardId", "0"));
        RewardItem reward = rewardService.getRewardItem(rewardId);
        session.reset();
        completePurchase(null, user, reward, text.trim());
    }

    private void completePurchase(CallbackQuery callbackQuery, AppUser user, RewardItem reward, String userGameData) {
        long effectivePrice = rewardService.effectivePrice(reward);
        RewardRequest req;
        try {
            req = rewardService.createRewardRequest(user, reward);
        } catch (IllegalArgumentException exception) {
            if (callbackQuery != null) answerSilently(callbackQuery.getId());
            sendRewardCard(user, reward.getId(), "⚠️ " + exception.getMessage());
            return;
        }
        if (userGameData != null && !userGameData.isBlank()) {
            req.setPayoutDetails(userGameData);
            rewardService.saveRequest(req);
        }
        notifyAdminsAboutRewardRequest(user, reward, userGameData);
        sendText(user.getTelegramId(),
                "✅ <b>Заявка на награду отправлена</b>\n\n"
                        + "🎁 Награда: <b>" + escape(reward.getTitle()) + "</b>\n"
                        + "🪙 Списано: <b>" + effectivePrice + " EXC</b>\n\n"
                        + "Как только выдача будет подтверждена, вы получите отдельное уведомление.",
                backMenuKeyboard("menu:shop"));
        if (callbackQuery != null) answerSilently(callbackQuery.getId());
    }

    private void sendCouncil(AppUser user) {
        boolean isMember = councilService.isCouncilMember(user);
        long seats = councilService.availableSeats();
        int level = userService.getLevelNumber(user.getXp());

        if (isMember) {
            List<Quest> councilQuests = questService.findActiveCouncilQuests();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            if (councilQuests.isEmpty()) {
                rows.add(List.of(keyboardFactory.callback("📭 Эксклюзивных квестов пока нет", "menu:council")));
            } else {
                for (Quest q : councilQuests) {
                    rows.add(List.of(keyboardFactory.callback("🔐 " + trim(q.getTitle(), 28), "quest:card:" + q.getId())));
                }
            }
            rows.add(List.of(keyboardFactory.callback("🏆 VIP-турниры", "menu:tournament")));
            rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));
            sendText(user.getTelegramId(),
                    "🛡️ <b>EGC Council</b>\n\n"
                            + "Добро пожаловать, участник Council.\n\n"
                            + "Здесь доступны эксклюзивные квесты и VIP-турниры, закрытые для обычных игроков.",
                    keyboardFactory.rowsLayout(rows));
        } else {
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            boolean canJoin = level >= ru.gamebot.platform.service.CouncilService.REQUIRED_LEVEL && seats > 0;
            if (canJoin) {
                rows.add(List.of(keyboardFactory.callback("✅ Вступить в Council — 10 000 EXC", "council:join")));
            }
            rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));
            sendText(user.getTelegramId(),
                    "🛡️ <b>EGC Council</b>\n\n"
                            + "Закрытое сообщество лучших игроков клуба.\n\n"
                            + "📋 <b>Условия вступления:</b>\n"
                            + "• Уровень 6 «Герой EXPERIENCE» или выше\n"
                            + "• Взнос: 10 000 EXC\n\n"
                            + "🎁 <b>Привилегии:</b>\n"
                            + "• Эксклюзивные Council-квесты\n"
                            + "• Доступ к VIP-турнирам\n"
                            + "• Бейдж 🛡️ EGC Council в профиле\n\n"
                            + "🪑 Свободных мест: <b>" + seats + " из " + ru.gamebot.platform.service.CouncilService.MAX_SEATS + "</b>\n"
                            + "⭐ Ваш уровень: <b>" + level + "</b>" + (level < 6 ? " (нужен уровень 6+)" : " ✅") + "\n"
                            + "🪙 Ваш баланс: <b>" + user.getCoins() + " EXC</b>" + (user.getCoins() < 10_000 ? " (нужно 10 000)" : " ✅"),
                    keyboardFactory.rowsLayout(rows));
        }
    }

    private void handleCouncilAction(CallbackQuery callbackQuery, AppUser user, String action) {
        if ("join".equals(action)) {
            try {
                councilService.joinCouncil(user);
                sendText(user.getTelegramId(),
                        "🛡️ <b>Добро пожаловать в EGC Council!</b>\n\n"
                                + "Списано 10 000 EXC. Теперь вам доступны эксклюзивные квесты и VIP-турниры.\n"
                                + "Бейдж Council отображается в вашем профиле.",
                        backMenuKeyboard("menu:council"));
            } catch (IllegalStateException e) {
                sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:council"));
            }
        }
        answerSilently(callbackQuery.getId());
    }

    private void sendTournament(AppUser user) {
        java.util.Optional<ru.gamebot.platform.domain.model.Tournament> opt = tournamentService.findCurrentForUser();
        if (opt.isEmpty()) {
            sendText(user.getTelegramId(),
                    "🏆 <b>Еженедельный турнир</b>\n\n⏳ Активных турниров нет. Следите за новостями клуба!",
                    backMenuKeyboard("menu:main"));
            return;
        }
        ru.gamebot.platform.domain.model.Tournament t = opt.get();
        boolean entered = tournamentService.hasEntered(t, user);
        long entries = tournamentService.entryCount(t);
        long pool = t.getPrizePoolExc();

        StringBuilder sb = new StringBuilder("🏆 <b>Еженедельный турнир</b>\n\n");
        sb.append("📌 <b>").append(escape(t.getName())).append("</b>\n");
        if (t.getGameName() != null) sb.append("🎮 Игра: ").append(escape(t.getGameName())).append("\n");
        sb.append("💰 Взнос: <b>").append(t.getEntryFeeExc()).append(" EXC</b>\n");
        sb.append("🏅 Призовой фонд: <b>").append(pool).append(" EXC</b>\n");
        sb.append("👥 Участников: <b>").append(entries).append("</b>\n");
        if (t.getStartDate() != null) sb.append("🚀 Старт: ").append(t.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))).append("\n");
        if (t.getEndDate() != null) sb.append("⏰ Финиш: ").append(t.getEndDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))).append("\n");
        sb.append("\n");

        boolean isReg = t.getStatus() == ru.gamebot.platform.domain.model.Tournament.Status.REGISTRATION;
        boolean isActive = t.getStatus() == ru.gamebot.platform.domain.model.Tournament.Status.ACTIVE;

        if (isActive) {
            sb.append("🔥 <b>Турнир идёт!</b> Выполняйте квесты — побеждает тот, кто выполнит больше всего.\n\n");
            sb.append("🥇 1 место — 60% призового фонда\n");
            sb.append("🥈-🥉 2–10 места — остаток фонда поровну\n");
        } else if (isReg) {
            sb.append("📋 <b>Идёт регистрация!</b> Ваш баланс: <b>").append(user.getCoins()).append(" EXC</b>\n\n");
            sb.append("🥇 1 место — 60% призового фонда\n");
            sb.append("🥈-🥉 2–10 места — остаток фонда поровну\n");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (isReg && !entered) {
            rows.add(List.of(keyboardFactory.callback("⚔️ Участвовать (" + t.getEntryFeeExc() + " EXC)", "tournament:join:" + t.getId())));
        } else if (entered) {
            sb.append("\n✅ Вы зарегистрированы!");
        }
        if (entered || isActive) {
            rows.add(List.of(keyboardFactory.callback("📊 Список участников", "tournament:leaderboard:" + t.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:main")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendNews(AppUser user) {
        List<NewsPost> posts = newsService.latestNews();
        if (posts.isEmpty()) {
            sendText(user.getTelegramId(), "📰 Новостная лента уже готовится. Пока можно сосредоточиться на квестах и росте профиля.", backMenuKeyboard("menu:main"));
            return;
        }

        StringBuilder builder = new StringBuilder("📰 <b>Новости клуба</b>\n\n");
        for (NewsPost post : posts) {
            builder.append("📣 <b>").append(escape(post.getTitle())).append("</b>\n")
                    .append(post.getBody()).append("\n")
                    .append("🕒 ").append(escape(post.getPublishedAt().format(DATE_TIME_FORMATTER))).append("\n\n");
        }

        sendText(user.getTelegramId(), builder.toString(), backMenuKeyboard("menu:main"));
    }

    private void sendSupport(AppUser user) {
        sendText(user.getTelegramId(),
                "🆘 <b>Поддержка</b>\n\n"
                        + "Если что-то пошло не так, напишите сюда прямо в боте.\n"
                        + "Поддерживаются текст, фото, видео, документы и медиагруппы.\n\n"
                        + "Ответ модератора придёт сюда же, без переходов в сторонние чаты.",
                keyboardFactory.verticalLayout(List.of(
                        keyboardFactory.callback("✍️ Новая заявка", "support:new"),
                        keyboardFactory.callback("📬 Мои заявки", "support:list"),
                        keyboardFactory.callback("🏠 Меню", "menu:main")
                )));
    }

    private void sendModerationHub(AppUser user) {
        long pendingWithdrawalsMod = rewardService.findPendingWithdrawals().size();
        String wLabelMod = pendingWithdrawalsMod > 0
                ? "💸 Заявки на вывод (" + pendingWithdrawalsMod + ")"
                : "💸 Заявки на вывод";
        sendText(user.getTelegramId(),
                "🛡️ <b>Центр модерации</b>\n\n"
                        + "📂 Отчёты по квестам: <b>" + questService.pendingCount() + "</b>\n"
                        + "🆘 Открытые заявки поддержки: <b>" + supportService.activeTicketCount() + "</b>\n"
                        + "💸 Заявки на вывод: <b>" + pendingWithdrawalsMod + "</b>\n\n"
                        + "Здесь собрана вся оперативная работа по платформе.",
                keyboardFactory.rowsLayout(List.of(
                        List.of(
                                keyboardFactory.callback("📂 Квесты", "mod:support:quests"),
                                keyboardFactory.callback("🆘 Поддержка", "mod:support:list")
                        ),
                        List.of(keyboardFactory.callback(wLabelMod, "mod:withdrawals")),
                        List.of(keyboardFactory.callback("🏠 Главное меню", "menu:main"))
                )));
    }

    private void handleSupportAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        switch (action) {
            case "new" -> {
                clearSupportDraft(session);
                session.setState(SessionState.SUPPORT_INPUT);
                sendText(user.getTelegramId(),
                        "🆘 <b>Новая заявка</b>\n\n"
                                + "Пришлите текст, фото, видео, документ или медиагруппу.\n"
                                + "Можно добавить комментарий в подписи.\n\n"
                                + "После отправки заявка сразу попадёт в рабочую очередь модераторов.",
                        backMenuKeyboard("menu:support"));
                answerSilently(callbackQuery.getId());
            }
            case "list" -> {
                sendUserSupportTickets(user);
                answerSilently(callbackQuery.getId());
            }
            default -> answer(callbackQuery.getId(), "Неизвестное действие поддержки");
        }
    }

    private void handleModeratorSupportAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if ("quests".equals(action)) {
            sendModerationQueue(user.getTelegramId());
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("list".equals(action)) {
            sendSupportQueue(user.getTelegramId());
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("view:")) {
            sendSupportTicketCard(user.getTelegramId(), parseLong(action.substring("view:".length())));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("reply:")) {
            session.reset();
            session.setState(SessionState.SUPPORT_REPLY);
            session.setSupportTicketId(parseLong(action.substring("reply:".length())));
            sendText(user.getTelegramId(),
                    "✍️ Отправьте ответ пользователю. Можно текстом, фото, видео или документом.",
                    backMenuKeyboard("mod:support:list"));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("close:")) {
            SupportTicket ticket = supportService.closeTicket(parseLong(action.substring("close:".length())), user.getTelegramId());
            notifyUser(ticket.getUser().getTelegramId(),
                    "✅ Ваша заявка в поддержку закрыта.\n\nЕсли проблема останется, вы всегда можете создать новую заявку.");
            sendSupportQueue(user.getTelegramId());
            answerSilently(callbackQuery.getId());
            return;
        }
        answer(callbackQuery.getId(), "Неизвестное действие модерации");
    }

    private void handleSupportMessage(AppUser user, UserSession session, Message message) {
        IncomingContent content = extractIncomingContent(message);
        String mediaGroupId = message.getMediaGroupId();
        boolean continuation = mediaGroupId != null
                && session.getSupportTicketId() != null
                && mediaGroupId.equals(session.getData().get("support_media_group_id"));

        SupportTicket ticket;
        if (continuation) {
            ticket = supportService.getTicket(session.getSupportTicketId());
        } else {
            ticket = supportService.createTicket(user, content.text(), mediaGroupId);
            session.setSupportTicketId(ticket.getId());
            if (mediaGroupId != null) {
                session.getData().put("support_media_group_id", mediaGroupId);
            }
        }

        if (!"text".equals(content.mediaType()) || (content.text() != null && !content.text().isBlank())) {
            supportService.addAttachment(ticket, false, content.mediaType(), content.fileId(), content.text());
        }

        notifyModeratorsAboutSupportTicket(ticket, content, continuation);

        if (mediaGroupId == null) {
            clearSupportDraft(session);
            sendText(user.getTelegramId(),
                    "✅ <b>Заявка отправлена</b>\n\n"
                            + "Она уже в очереди поддержки.\n"
                            + "Ответ придёт прямо в этот чат.",
                    keyboardFactory.verticalLayout(List.of(
                            keyboardFactory.callback("📬 Мои заявки", "support:list"),
                            keyboardFactory.callback("🏠 Меню", "menu:main")
                    )));
        } else if (!continuation) {
            session.setState(SessionState.NONE);
            sendText(user.getTelegramId(),
                    "✅ <b>Медиагруппа принята</b>\n\nВсе вложения из этого альбома будут прикреплены к одной заявке поддержки.",
                    keyboardFactory.verticalLayout(List.of(
                            keyboardFactory.callback("📬 Мои заявки", "support:list"),
                            keyboardFactory.callback("🏠 Меню", "menu:main")
                    )));
        }
    }

    private void handleSupportReplyMessage(AppUser moderator, UserSession session, Message message) {
        SupportTicket ticket = supportService.getTicket(session.getSupportTicketId());
        IncomingContent content = extractIncomingContent(message);
        try {
            forwardSupportReply(ticket.getUser().getTelegramId(), content);
        } catch (Exception exception) {
            log.warn("Failed to deliver support reply to {}", ticket.getUser().getTelegramId(), exception);
            sendText(moderator.getTelegramId(),
                    "⚠️ Не удалось доставить ответ пользователю. Возможно, он закрыл чат с ботом.",
                    backMenuKeyboard("mod:support:list"));
            session.reset();
            return;
        }
        supportService.markAnswered(ticket.getId(), moderator.getTelegramId(), content.text());
        if (!"text".equals(content.mediaType()) || (content.text() != null && !content.text().isBlank())) {
            supportService.addAttachment(ticket, true, content.mediaType(), content.fileId(), content.text());
        }
        session.reset();
        sendText(moderator.getTelegramId(),
                "✅ Ответ пользователю отправлен.",
                keyboardFactory.verticalLayout(List.of(
                        keyboardFactory.callback("🆘 Поддержка", "mod:support:list"),
                        keyboardFactory.callback("🏠 Меню", "menu:main")
                )));
    }

    private void sendUserSupportTickets(AppUser user) {
        List<SupportTicket> tickets = supportService.getUserTickets(user);
        if (tickets.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📭 У вас пока нет заявок в поддержку.\n\nЕсли что-то случится, создайте новую заявку прямо здесь.",
                    keyboardFactory.rowsLayout(List.of(
                            List.of(keyboardFactory.callback("✍️ Новая заявка", "support:new")),
                            List.of(
                                    keyboardFactory.callback("⬅️ Назад", "menu:support"),
                                    keyboardFactory.callback("🏠 Меню", "menu:main")
                            )
                    )));
            return;
        }

        StringBuilder builder = new StringBuilder("📬 <b>Мои заявки</b>\n\n");
        tickets.stream().limit(10).forEach(ticket -> builder
                .append("🆘 Заявка #").append(ticket.getId()).append("\n")
                .append("📌 Статус: <b>").append(escape(humanSupportStatus(ticket.getStatus().name()))).append("</b>\n")
                .append("🕒 Обновлено: <b>").append(escape(ticket.getUpdatedAt().format(DATE_TIME_FORMATTER))).append("</b>\n")
                .append("💬 ").append(escape(trim(ticket.getInitialMessage(), 80))).append("\n\n"));

        sendText(user.getTelegramId(), builder.toString(),
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("✍️ Новая заявка", "support:new")),
                        List.of(
                                keyboardFactory.callback("⬅️ Назад", "menu:support"),
                                keyboardFactory.callback("🏠 Меню", "menu:main")
                        )
                )));
    }

    private void sendSupportQueue(Long chatId) {
        List<SupportTicket> tickets = supportService.getActiveTickets();
        if (tickets.isEmpty()) {
            sendText(chatId,
                    "🆘 Открытых заявок поддержки сейчас нет.",
                    backOnlyKeyboard("menu:moderation"));
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (SupportTicket ticket : tickets) {
            buttons.add(keyboardFactory.callback(
                    "🆘 #" + ticket.getId() + " " + trim(ticket.getUser().getNickname(), 20),
                    "mod:support:view:" + ticket.getId()
            ));
        }
        sendText(chatId,
                "🆘 <b>Очередь поддержки</b>\n\n"
                        + "Открытых заявок: <b>" + tickets.size() + "</b>\n"
                        + "Откройте карточку заявки, чтобы быстро ответить или закрыть диалог.",
                verticalWithBackMenu(buttons, "⬅️ Назад", "menu:moderation"));
    }

    private void sendSupportTicketCard(Long chatId, Long ticketId) {
        SupportTicket ticket = supportService.getTicket(ticketId);
        List<SupportAttachment> attachments = supportService.getAttachments(ticket);
        String text = "🆘 <b>Заявка поддержки #" + ticket.getId() + "</b>\n\n"
                + "👤 Игрок: <b>" + escape(ticket.getUser().getNickname()) + "</b>\n"
                + "🆔 ID: <b>" + ticket.getUser().getTelegramId() + "</b>\n"
                + "📌 Статус: <b>" + escape(humanSupportStatus(ticket.getStatus().name())) + "</b>\n"
                + "🕒 Создана: <b>" + escape(ticket.getCreatedAt().format(DATE_TIME_FORMATTER)) + "</b>\n"
                + "📎 Вложений: <b>" + attachments.size() + "</b>\n\n"
                + "💬 <b>Сообщение игрока</b>\n" + escape(ticket.getInitialMessage())
                + (ticket.getLastModeratorReply() == null ? "" : "\n\n✉️ Последний ответ модератора:\n" + escape(ticket.getLastModeratorReply()));

        sendText(chatId, text,
                verticalWithBackMenu(List.of(
                        keyboardFactory.callback("✍️ Ответить", "mod:support:reply:" + ticketId),
                        keyboardFactory.callback("✅ Закрыть", "mod:support:close:" + ticketId)
                ), "⬅️ Назад", "mod:support:list"));
    }

    private void handleSuspectAction(CallbackQuery callbackQuery, AppUser moderator, String telegramIdStr) {
        Long targetId = parseLong(telegramIdStr);
        if (targetId == null) { answerSilently(callbackQuery.getId()); return; }
        AppUser suspect = userService.findByTelegramId(targetId).orElse(null);
        if (suspect == null) {
            sendText(moderator.getTelegramId(), "⚠️ Пользователь не найден.", backOnlyKeyboard("mod:suspects"));
            answerSilently(callbackQuery.getId());
            return;
        }
        long reviewed = questService.countReviewedByUser(suspect);
        long approved = questService.countApprovedByUser(suspect);
        double rate = reviewed > 0 ? (double) approved / reviewed * 100 : 0;
        sendText(moderator.getTelegramId(),
                "⚠️ <b>Подозрительный аккаунт</b>\n\n"
                        + "👤 Никнейм: <b>" + escape(suspect.getNickname() != null ? suspect.getNickname() : "—") + "</b>\n"
                        + "🆔 Telegram ID: <b>" + suspect.getTelegramId() + "</b>\n"
                        + "✅ Одобрено: <b>" + approved + " / " + reviewed + "</b> (" + (int) rate + "%)\n"
                        + "📅 В клубе с: <b>" + (suspect.getCreatedAt() != null ? suspect.getCreatedAt().format(DATE_TIME_FORMATTER) : "—") + "</b>\n\n"
                        + "Если игрок честный — снимите флаг.",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("✅ Снять флаг — игрок честный", "mod:clear_suspect:" + targetId)),
                        List.of(keyboardFactory.callback("⬅️ Назад", "mod:suspects"))
                )));
        answerSilently(callbackQuery.getId());
    }

    private void sendModerationQueue(Long chatId) {
        List<QuestSubmission> submissions = questService.getPendingSubmissions();
        long suspectCount = userService.countFraudSuspects();

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (QuestSubmission submission : submissions) {
            boolean suspect = submission.getUser().isFraudSuspect();
            String prefix = suspect ? "⚠️ " : "🔎 ";
            String title = prefix + "К-" + (submission.getDisplayId() != null ? submission.getDisplayId() : submission.getId()) + " " + trim(submission.getUser().getNickname() + " / " + submission.getQuest().getTitle(), 22);
            buttons.add(keyboardFactory.callback(title, "mod:view:" + submission.getId()));
        }

        String suspectLine = suspectCount > 0
                ? "\n⚠️ Подозрительных аккаунтов: <b>" + suspectCount + "</b>"
                : "";

        if (submissions.isEmpty() && suspectCount == 0) {
            sendText(chatId,
                    "🛡️ Очередь проверки пуста. Все текущие отчёты уже разобраны.",
                    backOnlyKeyboard("menu:moderation"));
            return;
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (!buttons.isEmpty()) {
            rows.add(List.of()); // placeholder, filled below via verticalWithBackMenu
        }
        if (suspectCount > 0) {
            buttons.add(keyboardFactory.callback("⚠️ Подозрительные аккаунты (" + suspectCount + ")", "mod:suspects"));
        }
        buttons.add(keyboardFactory.callback("⬅️ Центр модерации", "menu:moderation"));
        buttons.add(keyboardFactory.callback("🏠 Главное меню", "menu:main"));

        sendText(chatId,
                "🛡️ <b>Очередь модерации</b>\n\n"
                        + "Заявок на проверке: <b>" + submissions.size() + "</b>"
                        + suspectLine + "\n\n"
                        + "⚠️ — аккаунты с признаками фрода помечены.",
                keyboardFactory.smartLayout(buttons));
    }

    private String rewardPreviewLine(QuestSubmission submission) {
        QuestService.RewardPreview reward = questService.computeReward(submission.getUser(), submission.getQuest());
        StringBuilder sb = new StringBuilder("💰 К начислению: <b>")
                .append(reward.xp()).append(" XP, ")
                .append(reward.coins()).append(" EXC</b>");
        if (reward.diminished()) {
            sb.append(" ⚠️ <i>снижено (лимит 3/нед по этому типу квеста)</i>");
        }
        if (reward.xpBoosted()) {
            sb.append(" ⚡<i>буст XP</i>");
        }
        return sb.toString();
    }

    private void sendFraudSuspects(Long chatId) {
        List<AppUser> suspects = userService.getFraudSuspects();
        if (suspects.isEmpty()) {
            sendText(chatId, "✅ Подозрительных аккаунтов нет.", backOnlyKeyboard("mod:support:quests"));
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (AppUser suspect : suspects) {
            buttons.add(keyboardFactory.callback(
                    "⚠️ " + trim(suspect.getNickname() != null ? suspect.getNickname() : "ID:" + suspect.getTelegramId(), 28),
                    "mod:suspect:" + suspect.getTelegramId()));
        }
        buttons.add(keyboardFactory.callback("⬅️ Назад", "mod:support:quests"));
        sendText(chatId,
                "⚠️ <b>Подозрительные аккаунты</b>\n\n"
                        + "Эти аккаунты автоматически помечены по признакам фрода:\n"
                        + "• Success rate больше 90%\n"
                        + "• Интервал между заявками меньше 10 сек\n\n"
                        + "Проверьте вручную и снимите флаг если игрок честный.",
                keyboardFactory.smartLayout(buttons));
    }

    private void sendSubmissionCard(Long chatId, Long submissionId) {
        QuestSubmission submission = questService.getSubmission(submissionId);
        String caption = "🧾 <b>Заявка К-" + (submission.getDisplayId() != null ? submission.getDisplayId() : submission.getId()) + " на проверку</b>\n\n"
                + "👤 Игрок: <b>" + escape(submission.getUser().getNickname()) + "</b>\n"
                + "🆔 ID: <b>" + submission.getUser().getTelegramId() + "</b>\n"
                + "🎯 Квест: <b>" + escape(submission.getQuest().getTitle()) + "</b>\n"
                + rewardPreviewLine(submission) + "\n"
                + "📅 Отправлено: <b>" + escape(submission.getCreatedAt().format(DATE_TIME_FORMATTER)) + "</b>\n"
                + "💬 Комментарий: " + escape(submission.getUserComment()) + "\n"
                + (submission.getExternalLink() == null ? "" : "🔗 Ссылка: " + escape(submission.getExternalLink()) + "\n");

        InlineKeyboardMarkup markup = verticalWithBackMenu(List.of(
                keyboardFactory.callback("✅ Одобрить", "mod:ok:" + submissionId),
                keyboardFactory.callback("❌ Отклонить", "mod:no:" + submissionId),
                keyboardFactory.callback("❓ Уточнить", "mod:more:" + submissionId)
        ), "⬅️ Назад", "mod:support:quests");

        String mediaFileId = submission.getMediaFileId();
        String mediaType = submission.getMediaType();

        if (mediaFileId != null && "photo".equals(mediaType)) {
            try {
                SendPhoto msg = new SendPhoto();
                msg.setChatId(chatId.toString());
                msg.setPhoto(new InputFile(mediaFileId));
                msg.setCaption(caption);
                msg.setParseMode("HTML");
                msg.setReplyMarkup(markup);
                execute(msg);
                // Send extra photos if any
                String extra = submission.getExtraMediaFileIds();
                if (extra != null && !extra.isBlank()) {
                    String[] extraIds = extra.split("\\|\\|");
                    for (int ei = 0; ei < extraIds.length; ei++) {
                        try {
                            SendPhoto ep = new SendPhoto();
                            ep.setChatId(chatId.toString());
                            ep.setPhoto(new InputFile(extraIds[ei]));
                            ep.setCaption("📎 Доп. фото " + (ei + 2) + "/" + (extraIds.length + 1));
                            execute(ep);
                        } catch (TelegramApiException ex) {
                            log.error("Failed to send extra photo {} for submission {}", ei, submissionId, ex);
                        }
                    }
                }
                return;
            } catch (TelegramApiException e) {
                log.error("Failed to send submission photo for {}", submissionId, e);
            }
        } else if (mediaFileId != null && "video".equals(mediaType)) {
            try {
                SendVideo msg = new SendVideo();
                msg.setChatId(chatId.toString());
                msg.setVideo(new InputFile(mediaFileId));
                msg.setCaption(caption);
                msg.setParseMode("HTML");
                msg.setReplyMarkup(markup);
                execute(msg);
                return;
            } catch (TelegramApiException e) {
                log.error("Failed to send submission video for {}", submissionId, e);
            }
        }
        sendText(chatId, caption, markup);
    }

    private void handleModerationApprove(CallbackQuery callbackQuery, Long submissionId) {
        QuestSubmission currentSubmission = questService.getSubmission(submissionId);
        long adjustedCoins = healthRatioService.applyRatio(currentSubmission.getQuest().getRewardCoins());
        UserService.RewardGrant rewardGrant = userService.previewReward(
                currentSubmission.getUser(),
                currentSubmission.getQuest().getRewardXp(),
                adjustedCoins,
                0
        );
        boolean isFirstQuest = currentSubmission.getUser().getCompletedQuests() == 0;
        QuestSubmission submission = questService.approveSubmission(submissionId);
        String firstQuestBonus = isFirstQuest && submission.getUser().getReferredByTelegramId() != null
                ? "\n🎁 Бонус за первый квест: <b>+3 000 EXC</b>" : "";
        notifyUser(submission.getUser().getTelegramId(),
                "🎉 Ваш отчёт по квесту <b>" + escape(submission.getQuest().getTitle()) + "</b> одобрен!\n\n"
                        + "🏅 Квест З-" + submission.getCompletionDisplayId() + "\n"
                        + "✨ XP: <b>+" + rewardGrant.xp() + "</b>\n"
                        + "🪙 EXC: <b>+" + rewardGrant.totalExc() + "</b>\n"
                        + formatExcBonusLine(rewardGrant)
                        + firstQuestBonus);
        sendModerationQueue(callbackQuery.getFrom().getId());
        answer(callbackQuery.getId(), "Заявка одобрена");
    }

    private void handleModerationReject(CallbackQuery callbackQuery, Long submissionId) {
        QuestSubmission submission = questService.rejectSubmission(
                submissionId,
                "Отчёт отклонён. Проверьте инструкцию к квесту и отправьте более точное подтверждение."
        );
        notifyUser(submission.getUser().getTelegramId(),
                "⚠️ Отчёт по квесту <b>" + escape(submission.getQuest().getTitle()) + "</b> отклонён.\n\n"
                        + escape(submission.getModeratorComment()));
        sendModerationQueue(callbackQuery.getFrom().getId());
        answer(callbackQuery.getId(), "Заявка отклонена");
    }

    private void handleModerationClarify(CallbackQuery callbackQuery, Long submissionId) {
        QuestSubmission submission = questService.requestClarification(submissionId);
        notifyUser(submission.getUser().getTelegramId(),
                "❓ По вашему отчёту по квесту <b>" + escape(submission.getQuest().getTitle()) + "</b> нужны уточнения.\n\n"
                        + escape(submission.getModeratorComment()));
        sendModerationQueue(callbackQuery.getFrom().getId());
        answer(callbackQuery.getId(), "Запрошены уточнения");
    }

    private void sendAdminPanel(AppUser user) {
        if (!isEffectiveAdmin(user)) {
            sendText(user.getTelegramId(), "⛔ Этот раздел доступен только администраторам.", mainMenuKeyboard(user));
            return;
        }
        sendText(user.getTelegramId(),
                "🛠️ <b>Админ-панель</b>\n\n"
                        + "Это главный пульт платформы.\n"
                        + "Отсюда вы управляете пользователями, ролями, контентом, экономикой и рассылками.",
                mainMenuKeyboard(user));
    }

    private void handleAdminAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        switch (action) {
            case "create" -> {
                session.reset();
                session.setState(SessionState.QUEST_CREATE_TITLE);
                sendText(user.getTelegramId(),
                        "➕ Создание квеста началось.\n\nОтправьте название нового квеста.",
                        cancelKeyboard());
            }
            case "edit" -> sendAdminQuestList(user);
            case "users" -> sendAdminUsersPage(user, 0);
            case "bonus" -> {
                session.reset();
                session.setState(SessionState.BONUS_INPUT);
                sendAdminBonusUsersPage(user, session, 0, null);
            }
            case "debit" -> {
                session.reset();
                session.setState(SessionState.DEBIT_INPUT);
                sendAdminDebitUsersPage(user, session, 0, null);
            }
            case "broadcast" -> {
                session.reset();
                session.setState(SessionState.BROADCAST_MESSAGE);
                sendText(user.getTelegramId(),
                        "📣 Отправьте текст рассылки или фото с подписью.\n\nЯ доставлю его всем зарегистрированным игрокам.",
                        cancelKeyboard());
            }
            case "stats" -> sendAdminStats(user);
            case "live" -> sendAdminLiveStatus(user);
            case "queststats" -> sendAdminQuestStats(user);
            case "template" -> sendQuestTemplateGamePicker(user);
            case "rewards" -> sendAdminRewardList(user);
            case "withdrawals" -> { sendAdminWithdrawals(user); answerSilently(callbackQuery.getId()); return; }
            case "traffic" -> { sendAdminTrafficList(user); answerSilently(callbackQuery.getId()); return; }
            case "polls" -> { sendAdminPollList(user); answerSilently(callbackQuery.getId()); return; }
            case "sponsors" -> { sendAdminSponsorList(user); answerSilently(callbackQuery.getId()); return; }
            case "sponsors:create" -> {
                session.reset();
                session.setState(SessionState.SPONSOR_CREATE_NAME);
                sendText(user.getTelegramId(),
                        "🤝 <b>Новый спонсор</b>\n\nВведите название компании/издателя:",
                        cancelKeyboard());
                answerSilently(callbackQuery.getId());
                return;
            }
            case "seasons" -> { sendAdminSeasonList(user); answerSilently(callbackQuery.getId()); return; }
            case "seasons:create" -> {
                session.reset();
                session.setState(SessionState.SEASON_CREATE_NAME);
                sendText(user.getTelegramId(),
                        "🎫 <b>Новый сезон Battle Pass</b>\n\nВведите название сезона (например: «Сезон 1 — Лето 2026»):",
                        cancelKeyboard());
                answerSilently(callbackQuery.getId());
                return;
            }
            case "tournaments" -> { sendAdminTournamentList(user); answerSilently(callbackQuery.getId()); return; }
            case "tournaments:create" -> {
                session.reset();
                session.setState(SessionState.TOURNAMENT_CREATE_NAME);
                sendText(user.getTelegramId(),
                        "🏆 <b>Новый турнир</b>\n\nВведите название турнира (например: «Турнир июля — PUBG»):",
                        cancelKeyboard());
                answerSilently(callbackQuery.getId());
                return;
            }
            case "polls:create" -> {
                session.reset();
                session.setState(SessionState.POLL_CREATE_QUESTION);
                sendText(user.getTelegramId(),
                        "🗳 <b>Новое голосование</b>\n\nВведите вопрос для голосования:",
                        cancelKeyboard());
                answerSilently(callbackQuery.getId());
                return;
            }
            case "traffic:create" -> {
                session.reset();
                session.setState(SessionState.TRAFFIC_SOURCE_NAME);
                sendText(user.getTelegramId(),
                        "📈 <b>Новый источник трафика</b>\n\nВведите название (например: Instagram, VK, Блогер Петя):",
                        cancelKeyboard());
            }
            case "payout" -> {
                session.reset();
                session.setState(SessionState.PAYOUT_POOL_INPUT);
                double ratio = healthRatioService.getCurrentRatio();
                long pool = healthRatioService.getPayoutPoolRub();
                long debt = healthRatioService.getTotalDebtExc();
                sendText(user.getTelegramId(),
                        "💳 <b>Пополнение Payout Pool</b>\n\n"
                                + "📊 Текущий Состояние фонда: <b>" + (int) Math.round(ratio * 100) + "%</b>\n"
                                + "💰 Payout Pool: <b>" + pool + " ₽</b>\n"
                                + "📉 Общий долг EXC: <b>" + debt + " EXC (" + (debt / 100) + " ₽)</b>\n\n"
                                + "Введите сумму пополнения в рублях:",
                        cancelKeyboard());
            }
            default -> {
                if (action.startsWith("quest:")) {
                    handleAdminQuestOpen(user, action.substring("quest:".length()));
                } else if (action.startsWith("quests:game:")) {
                    sendAdminQuestCategories(user, decodeGameToken(action.substring("quests:game:".length())));
                } else if (action.startsWith("quests:list:")) {
                    handleAdminQuestListAction(user, action.substring("quests:list:".length()));
                } else if ("users:bylevel".equals(action)) {
                    sendAdminUsersByLevel(user);
                    answerSilently(callbackQuery.getId());
                } else if (action.startsWith("users:level:")) {
                    int lvl = Integer.parseInt(action.substring("users:level:".length()));
                    sendAdminUsersOfLevel(user, lvl);
                    answerSilently(callbackQuery.getId());
                } else if ("users:search".equals(action)) {
                    session.setState(SessionState.ADMIN_USER_SEARCH);
                    answer(callbackQuery.getId(), "Введите TG ID");
                    sendText(user.getTelegramId(), "🔍 <b>Поиск пользователя</b>\n\nВведите Telegram ID (числовой):", cancelKeyboard());
                } else if ("users:post".equals(action)) {
                    sendAdminUsersPostCard(user);
                } else if (action.startsWith("users:")) {
                    sendAdminUsersPage(user, parseInteger(action.substring("users:".length())));
                } else if (action.startsWith("bonuspage:")) {
                    session.setState(SessionState.BONUS_INPUT);
                    sendAdminBonusUsersPage(user, session, parseInteger(action.substring("bonuspage:".length())), null);
                } else if (action.startsWith("debitpage:")) {
                    session.setState(SessionState.DEBIT_INPUT);
                    sendAdminDebitUsersPage(user, session, parseInteger(action.substring("debitpage:".length())), null);
                } else if (action.startsWith("user:")) {
                    handleAdminUserAction(user, session, action.substring("user:".length()));
                } else if (action.startsWith("delete:")) {
                    deleteQuest(user, parseLong(action.substring("delete:".length())));
                } else if (action.startsWith("toggle:")) {
                    toggleQuestStatus(user, parseLong(action.substring("toggle:".length())));
                } else if (action.startsWith("edit-title:")) {
                    session.reset();
                    session.setQuestId(parseLong(action.substring("edit-title:".length())));
                    session.setState(SessionState.QUEST_EDIT_TITLE);
                    sendText(user.getTelegramId(), "✏️ Отправьте новое название квеста.", cancelKeyboard());
                } else if (action.startsWith("edit-description:")) {
                    session.reset();
                    session.setQuestId(parseLong(action.substring("edit-description:".length())));
                    session.setState(SessionState.QUEST_EDIT_DESCRIPTION);
                    sendText(user.getTelegramId(), "📝 Отправьте новое описание квеста.", cancelKeyboard());
                } else if (action.startsWith("edit-reward:")) {
                    session.reset();
                    session.setQuestId(parseLong(action.substring("edit-reward:".length())));
                    session.setState(SessionState.QUEST_EDIT_REWARD);
                    sendText(user.getTelegramId(),
                            "✨ Отправьте новые награды в формате:\n<code>XP COINS</code>\n\nПример: <code>150 250</code>",
                            cancelKeyboard());
                } else if (action.startsWith("edit-category:")) {
                    session.reset();
                    session.setQuestId(parseLong(action.substring("edit-category:".length())));
                    session.setState(SessionState.QUEST_EDIT_CATEGORY);
                    sendText(user.getTelegramId(),
                            "📚 Выберите новую категорию:",
                            keyboardFactory.rowsLayout(List.of(
                                    List.of(keyboardFactory.callback("🟢 Легкие", "qe:cat:Легкие")),
                                    List.of(keyboardFactory.callback("🟡 Средние", "qe:cat:Средние")),
                                    List.of(keyboardFactory.callback("🔴 Сложные", "qe:cat:Сложные"))
                            )));
                } else if (action.startsWith("edit-platform:")) {
                    session.reset();
                    session.setQuestId(parseLong(action.substring("edit-platform:".length())));
                    session.setState(SessionState.QUEST_EDIT_PLATFORM);
                    sendQuestPlatformEditKeyboard(user, session);
                } else if (action.startsWith("edit-limit:")) {
                    session.reset();
                    session.setQuestId(parseLong(action.substring("edit-limit:".length())));
                    session.setState(SessionState.QUEST_EDIT_LIMIT);
                    sendText(user.getTelegramId(), "👥 Укажите новый лимит участников числом.", cancelKeyboard());
                } else if (action.startsWith("game:photo:set:")) {
                    String gameName = decodeGameToken(action.substring("game:photo:set:".length()));
                    session.reset();
                    session.setState(SessionState.GAME_PHOTO_UPLOAD);
                    session.getData().put("gamePhotoName", gameName);
                    sendText(user.getTelegramId(),
                            "🖼 <b>Загрузка фото для игры «" + escape(gameName) + "»</b>\n\n"
                                    + "Отправьте изображение (фото, не файл). Оно будет показываться пользователям при входе в раздел квестов этой игры.",
                            cancelKeyboard());
                } else if (action.startsWith("game:photo:remove:")) {
                    String gameName = decodeGameToken(action.substring("game:photo:remove:".length()));
                    gameCatalogService.removePhoto(gameName);
                    answer(callbackQuery.getId(), "Фото удалено");
                    sendAdminQuestCategories(user, gameName);
                    return;
                } else if (action.startsWith("traffic:view:")) {
                    sendAdminTrafficView(user, parseLong(action.substring("traffic:view:".length())));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("traffic:view:page:")) {
                    String[] parts = action.substring("traffic:view:page:".length()).split(":");
                    sendAdminTrafficUsersPage(user, parseLong(parts[0]), parseInteger(parts[1]));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("traffic:delete:")) {
                    trafficSourceService.delete(parseLong(action.substring("traffic:delete:".length())));
                    sendAdminTrafficList(user);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("sponsors:view:")) {
                    sendAdminSponsorView(user, parseLong(action.substring("sponsors:view:".length())));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("sponsors:deactivate:")) {
                    sponsorService.deactivate(parseLong(action.substring("sponsors:deactivate:".length())));
                    answer(callbackQuery.getId(), "Кампания деактивирована.");
                    sendAdminSponsorList(user);
                    return;
                } else if (action.startsWith("sponsors:link:")) {
                    // Link quest to sponsor: sponsors:link:sponsorId:questId
                    String[] parts = action.substring("sponsors:link:".length()).split(":");
                    long sponsorId = parseLong(parts[0]);
                    long questId = parseLong(parts[1]);
                    try {
                        ru.gamebot.platform.domain.model.Quest q = questService.getQuest(questId);
                        q.setSponsored(true);
                        q.setSponsorId(sponsorId);
                        questService.save(q);
                        answer(callbackQuery.getId(), "✅ Квест привязан к спонсору.");
                        sendAdminSponsorView(user, sponsorId);
                    } catch (Exception e) {
                        answer(callbackQuery.getId(), "❌ Ошибка: " + e.getMessage());
                    }
                    return;
                } else if (action.startsWith("sponsors:unlink:")) {
                    long questId = parseLong(action.substring("sponsors:unlink:".length()));
                    try {
                        ru.gamebot.platform.domain.model.Quest q = questService.getQuest(questId);
                        long sid = q.getSponsorId() != null ? q.getSponsorId() : 0;
                        q.setSponsored(false);
                        q.setSponsorId(null);
                        questService.save(q);
                        answer(callbackQuery.getId(), "Квест откреплён.");
                        if (sid > 0) sendAdminSponsorView(user, sid);
                        else sendAdminSponsorList(user);
                    } catch (Exception e) {
                        answer(callbackQuery.getId(), "❌ " + e.getMessage());
                    }
                    return;
                } else if (action.startsWith("sponsors:addquest:")) {
                    long sponsorId = parseLong(action.substring("sponsors:addquest:".length()));
                    sendSponsorQuestPicker(user, sponsorId);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("seasons:view:")) {
                    sendAdminSeasonView(user, parseLong(action.substring("seasons:view:".length())));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("seasons:deactivate:")) {
                    seasonService.deactivate(parseLong(action.substring("seasons:deactivate:".length())));
                    answer(callbackQuery.getId(), "Сезон деактивирован.");
                    sendAdminSeasonList(user);
                    return;
                } else if (action.startsWith("tournaments:view:")) {
                    sendAdminTournamentView(user, parseLong(action.substring("tournaments:view:".length())));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("polls:view:")) {
                    sendAdminPollView(user, parseLong(action.substring("polls:view:".length())));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("polls:close:")) {
                    long pollId = parseLong(action.substring("polls:close:".length()));
                    pollService.findById(pollId).ifPresent(p -> {
                        pollService.close(p);
                        publishPollResults(p);
                        answer(callbackQuery.getId(), "Голосование закрыто, результаты опубликованы.");
                        sendAdminPollList(user);
                    });
                    return;
                } else if (action.startsWith("polls:delete:")) {
                    long pollId = parseLong(action.substring("polls:delete:".length()));
                    pollService.findById(pollId).ifPresent(p -> {
                        pollService.close(p);
                        answer(callbackQuery.getId(), "Голосование удалено.");
                        sendAdminPollList(user);
                    });
                    return;
                } else if (action.startsWith("withdrawal:")) {
                    handleAdminWithdrawalAction(callbackQuery, user, session, action.substring("withdrawal:".length()));
                    return;
                } else if (action.startsWith("reward:")) {
                    handleAdminRewardAction(callbackQuery, user, session, action.substring("reward:".length()));
                    return;
                } else if (action.startsWith("qt:")) {
                    handleQuestTemplateCallback(callbackQuery, user, session, action.substring("qt:".length()));
                    return;
                }
            }
        }
        answer(callbackQuery.getId(), "Готово");
    }

    // ── Quest templates ──────────────────────────────────────────────────────

    private static final class QuestTemplate {
        private final String game, category, platform, durationText, instruction, requirements;
        private final int durationDays;
        private final long xp, coins;
        QuestTemplate(String game, String category, String platform,
                      int durationDays, String durationText,
                      long xp, long coins, String instruction, String requirements) {
            this.game = game; this.category = category; this.platform = platform;
            this.durationDays = durationDays; this.durationText = durationText;
            this.xp = xp; this.coins = coins;
            this.instruction = instruction; this.requirements = requirements;
        }
        String game() { return game; }
        String category() { return category; }
        String platform() { return platform; }
        int durationDays() { return durationDays; }
        String durationText() { return durationText; }
        long xp() { return xp; }
        long coins() { return coins; }
        String instruction() { return instruction; }
        String requirements() { return requirements; }
    }

    private static final java.util.LinkedHashMap<String, List<QuestTemplate>> QUEST_TEMPLATES = new java.util.LinkedHashMap<>();

    static {
        QUEST_TEMPLATES.put("PUBG", List.of(
            new QuestTemplate("PUBG", "Лёгкие", "PC, Mobile", 2, "2 дня", 50, 150,
                "1. Зайди в обычный матч PUBG.\n2. Попади в топ-10.\n3. Сделай скриншот таблицы результатов в конце матча.",
                "Скриншот таблицы результатов с вашим никнеймом и местом в топ-10."),
            new QuestTemplate("PUBG", "Средние", "PC, Mobile", 5, "5 дней", 100, 400,
                "1. Зайди в обычный или рейтинговый матч PUBG.\n2. Набери не менее 3 килов за матч.\n3. Сделай скриншот итоговой таблицы.",
                "Скриншот итоговой таблицы с вашим никнеймом и количеством килов (не менее 3)."),
            new QuestTemplate("PUBG", "Сложные", "PC, Mobile", 10, "10 дней", 250, 1000,
                "1. Сыграй рейтинговый матч PUBG.\n2. Победи (Chicken Dinner).\n3. Сделай скриншот финального экрана победы.",
                "Скриншот финального экрана с надписью Winner Winner Chicken Dinner и вашим никнеймом.")
        ));
        QUEST_TEMPLATES.put("Grim Soul", List.of(
            new QuestTemplate("Grim Soul", "Лёгкие", "Mobile", 3, "3 дня", 50, 150,
                "1. Зайди в Grim Soul.\n2. Выживи 3 дня подряд без смерти.\n3. Сделай скриншот экрана выживания с количеством дней.",
                "Скриншот экрана с количеством дней выживания (не менее 3)."),
            new QuestTemplate("Grim Soul", "Средние", "Mobile", 7, "7 дней", 100, 400,
                "1. Убей рыцаря-скелета (Knight Skeleton) в Grim Soul.\n2. Сделай скриншот тела врага сразу после убийства с вашим персонажем рядом.",
                "Скриншот с телом рыцаря-скелета и вашим персонажем в кадре."),
            new QuestTemplate("Grim Soul", "Сложные", "Mobile", 14, "14 дней", 250, 1000,
                "1. Построй укреплённую базу с каменными стенами в Grim Soul.\n2. Сделай скриншот базы сверху — должны быть видны каменные стены.",
                "Скриншот базы с каменными стенами. Должны быть видны минимум 4 каменных секции.")
        ));
        QUEST_TEMPLATES.put("EA FC 26", List.of(
            new QuestTemplate("EA FC 26", "Лёгкие", "PC, Console", 3, "3 дня", 50, 150,
                "1. Сыграй матч в режиме Ultimate Team или Карьера.\n2. Забей не менее 2 голов за матч.\n3. Сделай скриншот финального счёта.",
                "Скриншот финального счёта матча с вашим результатом (минимум 2 гола)."),
            new QuestTemplate("EA FC 26", "Средние", "PC, Console", 5, "5 дней", 100, 400,
                "1. Сыграй матч в Division Rivals или FUT Champions.\n2. Победи.\n3. Сделай скриншот экрана победы с итогом матча.",
                "Скриншот экрана победы в Division Rivals или FUT Champions."),
            new QuestTemplate("EA FC 26", "Сложные", "PC, Console", 10, "10 дней", 250, 1000,
                "1. Собери команду с рейтингом не ниже 85 в Ultimate Team.\n2. Сделай скриншот состава команды в меню.",
                "Скриншот состава команды в меню с общим рейтингом 85+.")
        ));
        QUEST_TEMPLATES.put("Brawl Stars", List.of(
            new QuestTemplate("Brawl Stars", "Лёгкие", "Mobile", 2, "2 дня", 50, 150,
                "1. Сыграй 3 матча в любом режиме Brawl Stars.\n2. Сделай скриншот профиля с количеством трофеев после матчей.",
                "Скриншот профиля с количеством трофеев."),
            new QuestTemplate("Brawl Stars", "Средние", "Mobile", 5, "5 дней", 100, 400,
                "1. Доберись до 500+ трофеев на любом бравлере.\n2. Сделай скриншот карточки бравлера с трофеями.",
                "Скриншот карточки бравлера с количеством трофеев 500+."),
            new QuestTemplate("Brawl Stars", "Сложные", "Mobile", 14, "14 дней", 250, 1000,
                "1. Войди в топ-200 локального рейтинга на любом бравлере.\n2. Сделай скриншот таблицы рейтинга с вашим ником.",
                "Скриншот таблицы рейтинга с вашим никнеймом в топ-200.")
        ));
        QUEST_TEMPLATES.put("Clash Royale", List.of(
            new QuestTemplate("Clash Royale", "Лёгкие", "Mobile", 2, "2 дня", 50, 150,
                "1. Выиграй 2 матча подряд в обычных боях Clash Royale.\n2. Сделай скриншот экрана победы после второго боя.",
                "Скриншот экрана победы с никнеймом видимым в профиле."),
            new QuestTemplate("Clash Royale", "Средние", "Mobile", 5, "5 дней", 100, 400,
                "1. Открой сундук Гигантский или лучше в Clash Royale.\n2. Сделай скриншот момента открытия с содержимым сундука.",
                "Скриншот открытия Гигантского сундука или выше с содержимым."),
            new QuestTemplate("Clash Royale", "Сложные", "Mobile", 10, "10 дней", 250, 1000,
                "1. Набери 5000+ кубков в рейтинговых боях Clash Royale.\n2. Сделай скриншот профиля с количеством кубков.",
                "Скриншот профиля с количеством кубков 5000+.")
        ));
        QUEST_TEMPLATES.put("Другая игра", List.of(
            new QuestTemplate("", "Лёгкие", "", 3, "3 дня", 50, 150, "", ""),
            new QuestTemplate("", "Средние", "", 7, "7 дней", 100, 400, "", ""),
            new QuestTemplate("", "Сложные", "", 14, "14 дней", 250, 1000, "", "")
        ));
    }

    private void sendQuestTemplateGamePicker(AppUser user) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String game : QUEST_TEMPLATES.keySet()) {
            rows.add(List.of(keyboardFactory.callback("🎮 " + game, "admin:qt:game:" + game)));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        sendText(user.getTelegramId(),
                "📋 <b>Создание квеста по шаблону</b>\n\nВыберите игру — бот подставит платформу, срок и награды автоматически.",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendQuestTemplateDifficultyPicker(AppUser user, String game) {
        List<QuestTemplate> templates = QUEST_TEMPLATES.get(game);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < templates.size(); i++) {
            QuestTemplate t = templates.get(i);
            rows.add(List.of(keyboardFactory.callback(
                    t.category() + " — " + t.xp() + " XP / " + t.coins() + " EXC / " + t.durationText(),
                    "admin:qt:pick:" + game + ":" + i)));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:template")));
        sendText(user.getTelegramId(),
                "📋 <b>" + escape(game) + "</b> — выберите сложность:",
                keyboardFactory.rowsLayout(rows));
    }

    private void handleQuestTemplateCallback(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if (action.startsWith("game:")) {
            String game = action.substring("game:".length());
            sendQuestTemplateDifficultyPicker(user, game);
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("pick:")) {
            String[] parts = action.substring("pick:".length()).split(":", 2);
            if (parts.length < 2) { answerSilently(callbackQuery.getId()); return; }
            String game = parts[0];
            Integer idxBoxed = parseInteger(parts[1]);
            if (idxBoxed == null) { answerSilently(callbackQuery.getId()); return; }
            int idx = idxBoxed;
            List<QuestTemplate> templates = QUEST_TEMPLATES.get(game);
            if (templates == null || idx < 0 || idx >= templates.size()) {
                answerSilently(callbackQuery.getId()); return;
            }
            QuestTemplate t = templates.get(idx);
            session.reset();
            session.setState(SessionState.QUEST_TEMPLATE_TITLE);
            session.getData().put("game", game.isEmpty() ? null : game);
            session.getData().put("category", t.category());
            session.getData().put("platform", t.platform());
            session.getData().put("durationDays", String.valueOf(t.durationDays()));
            session.getData().put("duration", t.durationText());
            session.getData().put("xp", String.valueOf(t.xp()));
            session.getData().put("coins", String.valueOf(t.coins()));
            session.getData().put("instruction", t.instruction());
            session.getData().put("requirements", t.requirements());
            session.getData().put("limit", "100");

            String gameLine = game.isEmpty() ? "\n\n⚠️ Игру укажите в названии или описании." : "\n\n🎮 Игра: <b>" + escape(game) + "</b>";
            sendText(user.getTelegramId(),
                    "📋 Шаблон загружен: <b>" + t.category() + "</b>" + gameLine + "\n"
                            + "⏱ Срок: <b>" + t.durationText() + "</b>\n"
                            + "🏆 Награда: <b>" + t.xp() + " XP / " + t.coins() + " EXC</b>\n\n"
                            + "Отправьте <b>название</b> квеста:",
                    cancelKeyboard());
            answerSilently(callbackQuery.getId());
        }
    }

    private void finalizeRewardCreation(AppUser user, UserSession session) {
        Map<String, String> d = session.getData();
        String title = d.get("title");
        long price = Long.parseLong(d.getOrDefault("price", "0"));
        rewardService.createRewardItem(
                title,
                d.get("description"),
                d.get("category"),
                price,
                d.get("photoFileId")
        );
        requestNewsApproval(
                "🎁 Новый товар в магазине",
                "В магазин наград добавлен <b>" + title + "</b> за " + price + " EXC. Загляни в раздел 🛍 Магазин!"
        );
        session.reset();
        sendText(user.getTelegramId(), "✅ Награда добавлена в магазин.", backMenuKeyboard("admin:rewards"));
    }

    private void sendAdminRewardList(AppUser user) {
        List<RewardItem> items = rewardService.findAllRewards();
        long pendingCount = rewardService.countPendingRequests();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("➕ Добавить награду", "admin:reward:create")));
        String requestsLabel = pendingCount > 0
                ? "📥 Заявки на выдачу (" + pendingCount + ")"
                : "📥 Заявки на выдачу";
        rows.add(List.of(keyboardFactory.callback(requestsLabel, "admin:reward:requests")));
        for (RewardItem item : items) {
            String status = item.isActive() ? "✅" : "⏸️";
            rows.add(List.of(keyboardFactory.callback(
                    status + " " + trim(item.getTitle(), 24) + " — " + item.getPriceCoins() + " EXC",
                    "admin:reward:edit:" + item.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("👁 Просмотр магазина", "admin:reward:preview")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        sendText(user.getTelegramId(),
                "🎁 <b>Управление магазином наград</b>\n\n"
                        + "Наград в магазине: <b>" + items.size() + "</b>\n"
                        + "Активных: <b>" + items.stream().filter(RewardItem::isActive).count() + "</b>\n"
                        + "Ожидают выдачи: <b>" + pendingCount + "</b>",
                keyboardFactory.rowsLayout(rows));
    }

    private void handleAdminRewardAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if ("preview".equals(action)) {
            sendShop(user);
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("create".equals(action)) {
            session.reset();
            session.setState(SessionState.REWARD_CREATE_TITLE);
            sendText(user.getTelegramId(), "🎁 Создание награды.\n\nОтправьте название награды.", cancelKeyboard());
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("edit:")) {
            Long id = parseLong(action.substring("edit:".length()));
            sendAdminRewardEditor(user, id);
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("toggle:")) {
            Long id = parseLong(action.substring("toggle:".length()));
            RewardItem item = rewardService.getRewardItem(id);
            item.setActive(!item.isActive());
            rewardService.save(item);
            sendAdminRewardEditor(user, id);
            answer(callbackQuery.getId(), item.isActive() ? "Награда активирована" : "Награда скрыта");
            return;
        }
        if (action.startsWith("delete:")) {
            Long id = parseLong(action.substring("delete:".length()));
            rewardService.deleteRewardItem(id);
            sendAdminRewardList(user);
            answer(callbackQuery.getId(), "Удалено");
            return;
        }
        if (action.startsWith("edit-title:")) {
            session.reset();
            session.setQuestId(parseLong(action.substring("edit-title:".length())));
            session.setState(SessionState.REWARD_EDIT_TITLE);
            sendText(user.getTelegramId(), "✏️ Отправьте новое название награды.", cancelKeyboard());
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("edit-description:")) {
            session.reset();
            session.setQuestId(parseLong(action.substring("edit-description:".length())));
            session.setState(SessionState.REWARD_EDIT_DESCRIPTION);
            sendText(user.getTelegramId(), "📝 Отправьте новое описание награды.", cancelKeyboard());
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("edit-price:")) {
            session.reset();
            session.setQuestId(parseLong(action.substring("edit-price:".length())));
            session.setState(SessionState.REWARD_EDIT_PRICE);
            sendText(user.getTelegramId(), "🪙 Укажите новую цену в EXC (целое число).", cancelKeyboard());
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("requests".equals(action)) {
            sendAdminRewardRequests(user);
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("req:")) {
            sendAdminRewardRequestCard(user, parseLong(action.substring("req:".length())));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("inprogress:")) {
            Long reqId = parseLong(action.substring("inprogress:".length()));
            RewardRequest req = rewardService.takeInProgressRequest(reqId);
            notifyUserRewardInProgress(req);
            sendAdminRewardRequestCard(user, reqId);
            answer(callbackQuery.getId(), "🔄 Взято в разработку");
            return;
        }
        if (action.startsWith("approve:")) {
            Long reqId = parseLong(action.substring("approve:".length()));
            RewardRequest req = rewardService.approveRequest(reqId);
            notifyUserRewardApproved(req);
            sendAdminRewardRequests(user);
            answer(callbackQuery.getId(), "✅ Выдано");
            return;
        }
        if (action.startsWith("reject:")) {
            session.reset();
            session.setQuestId(parseLong(action.substring("reject:".length())));
            session.setState(SessionState.REWARD_REJECT_COMMENT);
            answer(callbackQuery.getId(), "Введите причину отклонения");
            sendText(user.getTelegramId(), "❌ <b>Отклонение заявки</b>\n\nНапишите причину отклонения, она будет отправлена пользователю:", cancelKeyboard());
            return;
        }
        answerSilently(callbackQuery.getId());
    }

    private void sendAdminRewardRequests(AppUser user) {
        List<RewardRequest> pending = rewardService.findPendingRequests();
        if (pending.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📥 <b>Заявки на выдачу наград</b>\n\nНет новых заявок.",
                    backMenuKeyboard("admin:rewards"));
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (RewardRequest req : pending) {
            String statusMark = req.getStatus() == RewardRequestStatus.IN_PROGRESS ? "🔄 " : "";
            String label = statusMark + "М-" + reqDisplayId(req) + " @" + (req.getUser().getTelegramUsername() != null
                    ? req.getUser().getTelegramUsername()
                    : req.getUser().getTelegramId())
                    + " — " + trim(req.getRewardItem().getTitle(), 20);
            rows.add(List.of(keyboardFactory.callback(label, "admin:reward:req:" + req.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:rewards")));
        sendText(user.getTelegramId(),
                "📥 <b>Заявки на выдачу наград</b>\n\nОжидают обработки: <b>" + pending.size() + "</b>",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminRewardRequestCard(AppUser user, Long reqId) {
        RewardRequest req = rewardService.getRequest(reqId);
        AppUser requester = req.getUser();
        String usernameStr = requester.getTelegramUsername() != null
                ? "@" + requester.getTelegramUsername()
                : "#" + requester.getTelegramId();
        boolean inProgress = req.getStatus() == RewardRequestStatus.IN_PROGRESS;
        String statusLine = inProgress ? "\n🔄 Статус: <b>В разработке</b>" : "\n⏳ Статус: <b>Ожидает</b>";
        List<List<InlineKeyboardButton>> cardRows = new ArrayList<>();
        if (!inProgress) {
            cardRows.add(List.of(
                    keyboardFactory.callback("🔄 Взять в разработку", "admin:reward:inprogress:" + req.getId()),
                    keyboardFactory.callback("❌ Отклонить", "admin:reward:reject:" + req.getId())
            ));
        } else {
            cardRows.add(List.of(
                    keyboardFactory.callback("✅ Выдано", "admin:reward:approve:" + req.getId()),
                    keyboardFactory.callback("❌ Отклонить", "admin:reward:reject:" + req.getId())
            ));
        }
        cardRows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:reward:requests")));
        String userDataLine = req.getPayoutDetails() != null && !req.getPayoutDetails().isBlank()
                ? "\n📋 Данные для выдачи: <code>" + escape(req.getPayoutDetails()) + "</code>"
                : "";
        sendText(user.getTelegramId(),
                "📋 <b>Заявка М-" + reqDisplayId(req) + "</b>\n\n"
                        + "👤 Игрок: <b>" + escape(requester.getNickname()) + "</b> (" + usernameStr + ")\n"
                        + "🎁 Награда: <b>" + escape(req.getRewardItem().getTitle()) + "</b>\n"
                        + "🪙 Цена: <b>" + req.getRewardItem().getPriceCoins() + " EXC</b>"
                        + userDataLine + "\n"
                        + "📅 Дата: <b>" + req.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) + "</b>"
                        + statusLine,
                keyboardFactory.rowsLayout(cardRows));
    }

    private void notifyUserRewardInProgress(RewardRequest req) {
        sendText(req.getUser().getTelegramId(),
                "🔄 <b>Ваша заявка взята в разработку!</b>\n\n"
                        + "🎁 <b>" + escape(req.getRewardItem().getTitle()) + "</b>\n\n"
                        + "Администратор взял заявку в разработку. Отменить её теперь можно только через поддержку.",
                null);
    }

    private void notifyUserRewardApproved(RewardRequest req) {
        sendText(req.getUser().getTelegramId(),
                "✅ <b>Ваша награда выдана!</b>\n\n"
                        + "🎁 <b>" + escape(req.getRewardItem().getTitle()) + "</b>\n\n"
                        + "Свяжитесь с администратором для получения, если необходимо.",
                null);
    }

    private void notifyUserRewardRejected(RewardRequest req) {
        String comment = req.getAdminComment() != null ? req.getAdminComment() : "—";
        sendText(req.getUser().getTelegramId(),
                "❌ <b>Заявка на награду отклонена</b>\n\n"
                        + "🎁 <b>" + escape(req.getRewardItem().getTitle()) + "</b>\n\n"
                        + "📝 Причина: " + escape(comment) + "\n\n"
                        + "EXC возвращены на ваш баланс.",
                null);
    }

    // ── Withdrawal requests admin ─────────────────────────────────────────────

    private void handleAdminWithdrawalAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if (action.startsWith("req:")) {
            sendAdminWithdrawalCard(user, parseLong(action.substring("req:".length())));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("approve:skip:")) {
            long reqId = parseLong(action.substring("approve:skip:".length()));
            session.reset();
            RewardRequest req = rewardService.approveRequest(reqId);
            notifyUserWithdrawalApproved(req, null);
            sendAdminWithdrawals(user);
            answer(callbackQuery.getId(), "✅ Выплачено");
            return;
        }
        if (action.startsWith("approve:")) {
            long reqId = parseLong(action.substring("approve:".length()));
            session.setQuestId(reqId);
            session.setState(SessionState.WITHDRAWAL_RECEIPT);
            answer(callbackQuery.getId(), "Загрузите фото чека");
            sendText(user.getTelegramId(),
                    "🧾 <b>Загрузите скриншот чека</b>\n\nОтправьте фото подтверждения оплаты — оно будет отправлено пользователю.\n\nИли нажмите «Пропустить» если чек не нужен.",
                    keyboardFactory.rowsLayout(List.of(
                            List.of(keyboardFactory.callback("⏭️ Пропустить", "admin:withdrawal:approve:skip:" + reqId))
                    )));
            return;
        }
        if (action.startsWith("reject:")) {
            session.reset();
            session.setQuestId(parseLong(action.substring("reject:".length())));
            session.setState(SessionState.REWARD_REJECT_COMMENT);
            session.getData().put("rejectType", "withdrawal");
            answer(callbackQuery.getId(), "Введите причину отклонения");
            sendText(user.getTelegramId(), "❌ <b>Отклонение заявки на вывод</b>\n\nНапишите причину отклонения, она будет отправлена пользователю:", cancelKeyboard());
            return;
        }
        sendAdminWithdrawals(user);
        answerSilently(callbackQuery.getId());
    }

    private void sendAdminWithdrawals(AppUser user) {
        List<RewardRequest> pending = rewardService.findPendingWithdrawals();
        if (pending.isEmpty()) {
            sendText(user.getTelegramId(),
                    "💸 <b>Заявки на вывод EXC</b>\n\nНет новых заявок.",
                    backMenuKeyboard("menu:admin"));
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (RewardRequest req : pending) {
            String uname = req.getUser().getTelegramUsername() != null
                    ? "@" + req.getUser().getTelegramUsername()
                    : "#" + req.getUser().getTelegramId();
            String type = (req.getPayoutDetails() != null && req.getPayoutDetails().startsWith("USDT")) ? "💎 USDT" : "💸 ₽";
            rows.add(List.of(keyboardFactory.callback(
                    "В-" + reqDisplayId(req) + " " + uname + " — " + type + " " + req.getRewardItem().getPriceCoins() + " EXC",
                    "admin:withdrawal:req:" + req.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        sendText(user.getTelegramId(),
                "💸 <b>Заявки на вывод EXC</b>\n\nОжидают обработки: <b>" + pending.size() + "</b>",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminWithdrawalCard(AppUser user, Long reqId) {
        RewardRequest req = rewardService.getRequest(reqId);
        AppUser requester = req.getUser();
        String unameLink = requester.getTelegramUsername() != null
                ? "<a href=\"https://t.me/" + requester.getTelegramUsername() + "\">@" + requester.getTelegramUsername() + "</a>"
                : "<a href=\"tg://user?id=" + requester.getTelegramId() + "\">" + requester.getTelegramId() + "</a>";
        boolean isUsdt = req.getPayoutDetails() != null && req.getPayoutDetails().startsWith("USDT");
        String detailsLine = "";
        if (isUsdt) {
            String[] parts = req.getPayoutDetails().split(":");
            String wallet = parts.length > 1 ? parts[1] : req.getPayoutDetails();
            detailsLine = "\n💎 Способ: <b>USDT · TON</b>\n📬 Кошелёк: <code>" + escape(wallet) + "</code>";
        } else if (req.getPayoutDetails() != null) {
            detailsLine = "\n💵 Способ: <b>Рубли (СБП / Сбербанк)</b>\n💳 Реквизиты: <code>" + escape(req.getPayoutDetails()) + "</code>";
        } else {
            detailsLine = "\n💵 Способ: <b>Рубли (СБП / Сбербанк)</b>";
        }
        long rubles = Math.round(req.getRewardItem().getPriceCoins() / 100.0);
        long duplicateCount = rewardService.countPendingWithdrawalsByUser(requester);
        String duplicateWarning = duplicateCount > 1
                ? "\n\n⚠️ <b>ВНИМАНИЕ: у этого пользователя " + duplicateCount + " активные заявки на вывод!</b> Оплачивайте только эту." : "";
        sendText(user.getTelegramId(),
                "💸 <b>Заявка на вывод В-" + reqDisplayId(req) + "</b>\n\n"
                        + "👤 Игрок: <b>" + escape(requester.getNickname()) + "</b> (" + unameLink + ")\n"
                        + "🆔 Telegram ID: <b>" + requester.getTelegramId() + "</b>\n"
                        + "🪙 Сумма: <b>" + req.getRewardItem().getPriceCoins() + " EXC</b>\n"
                        + "💵 К выплате: <b>~" + rubles + " ₽</b>"
                        + detailsLine + "\n"
                        + "📅 Дата: <b>" + req.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) + "</b>"
                        + duplicateWarning,
                keyboardFactory.rowsLayout(List.of(
                        List.of(
                                keyboardFactory.callback("✅ Выплачено", "admin:withdrawal:approve:" + req.getId()),
                                keyboardFactory.callback("❌ Отклонить", "admin:withdrawal:reject:" + req.getId())
                        ),
                        List.of(keyboardFactory.callback("⬅️ Назад", "admin:withdrawals"))
                )));
    }

    private void notifyUserWithdrawalApproved(RewardRequest req, String receiptFileId) {
        boolean isUsdt = req.getPayoutDetails() != null;
        String method = isUsdt ? "USDT · TON" : "рубли (СБП / Сбербанк)";
        String caption = "✅ <b>Ваш вывод EXC выполнен!</b>\n\n"
                + "🔢 Номер заявки: <b>В-" + reqDisplayId(req) + "</b>\n"
                + "🪙 Сумма: <b>" + req.getRewardItem().getPriceCoins() + " EXC</b>\n"
                + "💵 Способ: <b>" + method + "</b>\n\n"
                + "Средства отправлены. Если не получили — напишите в поддержку.";
        if (receiptFileId != null) {
            SendPhoto photo = new SendPhoto();
            photo.setChatId(req.getUser().getTelegramId().toString());
            photo.setPhoto(new InputFile(receiptFileId));
            photo.setCaption(caption);
            photo.setParseMode("HTML");
            try { execute(photo); } catch (TelegramApiException e) { log.error("Failed to send receipt", e); }
        } else {
            sendText(req.getUser().getTelegramId(), caption, null);
        }
    }

    private void notifyUserWithdrawalRejected(RewardRequest req) {
        String comment = req.getAdminComment() != null ? req.getAdminComment() : "—";
        sendText(req.getUser().getTelegramId(),
                "❌ <b>Заявка на вывод отклонена</b>\n\n"
                        + "🪙 Сумма: <b>" + req.getRewardItem().getPriceCoins() + " EXC</b>\n"
                        + "📝 Причина: " + escape(comment) + "\n\n"
                        + "EXC возвращены на ваш баланс.",
                null);
    }

    private void sendAdminRewardEditor(AppUser user, Long rewardId) {
        RewardItem item = rewardService.getRewardItem(rewardId);
        String photoMark = item.getPhotoFileId() != null ? " 🖼️" : "";
        sendText(user.getTelegramId(),
                "✏️ <b>Редактор награды</b>" + photoMark + "\n\n"
                        + "🎁 <b>" + escape(item.getTitle()) + "</b>\n"
                        + "📦 Категория: <b>" + escape(item.getCategory() != null ? item.getCategory() : "—") + "</b>\n"
                        + "📝 " + escape(item.getDescription() != null ? item.getDescription() : "—") + "\n"
                        + "🪙 Цена: <b>" + item.getPriceCoins() + " EXC</b>\n"
                        + "📡 Статус: <b>" + (item.isActive() ? "активна" : "скрыта") + "</b>",
                keyboardFactory.rowsLayout(List.of(
                        List.of(
                                keyboardFactory.callback("✏️ Название", "admin:reward:edit-title:" + rewardId),
                                keyboardFactory.callback("📝 Описание", "admin:reward:edit-description:" + rewardId)
                        ),
                        List.of(keyboardFactory.callback("🪙 Цена", "admin:reward:edit-price:" + rewardId)),
                        List.of(
                                keyboardFactory.callback(item.isActive() ? "⏸️ Скрыть" : "▶️ Включить", "admin:reward:toggle:" + rewardId),
                                keyboardFactory.callback("🗑️ Удалить", "admin:reward:delete:" + rewardId)
                        ),
                        List.of(keyboardFactory.callback("⬅️ Назад", "admin:rewards"))
                )));
    }

    private void sendAdminQuestList(AppUser user) {
        List<String> games = questService.findAllGameNames();
        if (games.isEmpty()) {
            sendText(user.getTelegramId(),
                    "🗂️ <b>Управление квестами</b>\n\nПока нет квестов, распределённых по играм.",
                    backMenuKeyboard("menu:admin"));
            return;
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String game : games) {
            rows.add(List.of(keyboardFactory.callback("🎮 " + trim(game, 28), "admin:quests:game:" + encodeGameToken(game))));
        }
        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:admin")));
        sendText(user.getTelegramId(),
                "🗂️ <b>Управление квестами</b>\n\n"
                        + "Сначала выберите игру, чтобы открыть только связанные с ней квесты и редактировать их без общего смешанного списка.",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminQuestCategories(AppUser user, String gameName) {
        if (gameName == null || gameName.isBlank()) {
            sendAdminQuestList(user);
            return;
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                keyboardFactory.callback("⚡ Легкие", "admin:quests:list:" + encodeGameToken(gameName) + ":fast"),
                keyboardFactory.callback("🎯 Средние", "admin:quests:list:" + encodeGameToken(gameName) + ":medium")
        ));
        rows.add(List.of(keyboardFactory.callback("🏰 Сложные", "admin:quests:list:" + encodeGameToken(gameName) + ":long")));
        rows.add(List.of(keyboardFactory.callback("📚 Все квесты", "admin:quests:list:" + encodeGameToken(gameName) + ":all")));

        boolean hasPhoto = gameCatalogService.getPhotoFileId(gameName).isPresent();
        if (hasPhoto) {
            rows.add(List.of(
                    keyboardFactory.callback("🖼 Обновить фото игры", "admin:game:photo:set:" + encodeGameToken(gameName)),
                    keyboardFactory.callback("🗑 Удалить фото", "admin:game:photo:remove:" + encodeGameToken(gameName))
            ));
        } else {
            rows.add(List.of(keyboardFactory.callback("🖼 Добавить фото игры", "admin:game:photo:set:" + encodeGameToken(gameName))));
        }

        rows.add(List.of(
                keyboardFactory.callback("⬅️ Назад", "admin:edit"),
                keyboardFactory.callback("🏠 Меню", "menu:admin")
        ));

        String photoStatus = hasPhoto ? "✅ Фото установлено" : "📷 Фото не добавлено";
        sendText(user.getTelegramId(),
                "🎮 <b>" + escape(gameName) + "</b>\n"
                        + photoStatus + "\n\n"
                        + "Выберите категорию, чтобы открыть нужную группу квестов по этой игре.",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminQuestListByGame(AppUser user, String gameName, String category) {
        if (gameName == null || gameName.isBlank()) {
            sendAdminQuestList(user);
            return;
        }

        List<Quest> quests = category == null
                ? questService.findAllByGameName(gameName)
                : questService.findAllByGameNameAndCategory(gameName, category);
        if (quests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "🗂️ <b>Управление квестами</b>\n\nВ этой категории пока нет квестов.",
                    backMenuKeyboard("admin:quests:game:" + encodeGameToken(gameName)));
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Quest quest : quests) {
            buttons.add(keyboardFactory.callback(
                    "✏️ " + trim(quest.getTitle(), 30),
                    "admin:quest:" + encodeGameToken(gameName) + ":" + categoryToken(category) + ":" + quest.getId()
            ));
        }
        sendText(user.getTelegramId(),
                "🎮 <b>" + escape(gameName) + "</b>\n\n"
                        + (category == null ? "" : "📚 Категория: <b>" + escape(category) + "</b>\n\n")
                        + "Откройте карточку нужного квеста, чтобы обновить текст, награды или статус публикации.",
                verticalWithBackMenu(buttons, "⬅️ Назад", "admin:quests:game:" + encodeGameToken(gameName)));
    }

    private void sendAdminQuestEditor(AppUser user, Long questId) {
        sendAdminQuestEditor(user, questId, "admin:edit");
    }

    private void sendAdminQuestEditor(AppUser user, Long questId, String backData) {
        Quest quest = questService.getQuest(questId);
        sessionService.get(user.getTelegramId()).getData().put("admin_quest_back_data", backData);
        List<List<InlineKeyboardButton>> rows = List.of(
                List.of(
                        keyboardFactory.callback("✏️ Название", "admin:edit-title:" + questId),
                        keyboardFactory.callback("📝 Описание", "admin:edit-description:" + questId)
                ),
                List.of(
                        keyboardFactory.callback("✨ Награды", "admin:edit-reward:" + questId),
                        keyboardFactory.callback("📚 Категория", "admin:edit-category:" + questId)
                ),
                List.of(
                        keyboardFactory.callback("🕹️ Платформа", "admin:edit-platform:" + questId),
                        keyboardFactory.callback("👥 Лимит", "admin:edit-limit:" + questId)
                ),
                List.of(
                        keyboardFactory.callback(quest.isActive() ? "⏸️ Скрыть" : "▶️ Включить", "admin:toggle:" + questId),
                        keyboardFactory.callback("🗑️ Удалить", "admin:delete:" + questId)
                ),
                List.of(
                        keyboardFactory.callback("⬅️ Назад", backData),
                        keyboardFactory.callback("🏠 Меню", "menu:admin")
                )
        );
        String platformText = quest.getPlatform() != null ? quest.getPlatform() : "—";
        String photoMark = quest.getPhotoFileId() != null ? " 🖼️" : "";
        sendText(user.getTelegramId(),
                "✏️ <b>Редактор квеста</b>" + photoMark + "\n\n"
                        + "🎯 <b>" + escape(quest.getTitle()) + "</b>\n"
                        + "📚 Категория: <b>" + escape(quest.getCategory()) + "</b>\n"
                        + "🕹️ Платформа: <b>" + escape(platformText) + "</b>\n"
                        + "👥 Лимит: <b>" + quest.getParticipantLimit() + "</b>\n"
                        + "🎮 Игра: <b>" + escape(quest.getGameName()) + "</b>\n"
                        + "✨ XP: <b>+" + quest.getRewardXp() + "</b>\n"
                        + "🪙 Монеты: <b>+" + quest.getRewardCoins() + "</b>\n"
                        + "📡 Статус: <b>" + (quest.isActive() ? "активен" : "скрыт") + "</b>",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminStats(AppUser user) {
        List<AppUser> users = userService.allRegisteredUsers();
        java.time.LocalDate sevenDaysAgo = java.time.LocalDate.now().minusDays(7);
        long activeUsers = users.stream()
                .filter(u -> u.getLastActivityDate() != null)
                .filter(u -> !u.getLastActivityDate().isBefore(sevenDaysAgo))
                .count();
        long newUsersWeek = users.stream()
                .filter(u -> u.getCreatedAt() != null)
                .filter(u -> !u.getCreatedAt().toLocalDate().isBefore(sevenDaysAgo))
                .count();
        long totalCoins = users.stream().mapToLong(AppUser::getCoins).sum();
        long pendingQuests = questService.pendingCount();
        long pendingRewards = rewardService.countPendingRequests();

        sendText(user.getTelegramId(),
                "📊 <b>Статистика платформы</b>\n\n"
                        + "👥 Всего игроков: <b>" + userService.totalRegisteredUsers() + "</b>\n"
                        + "🟢 Активных за 7 дней: <b>" + activeUsers + "</b>\n"
                        + "🆕 Новых за 7 дней: <b>" + newUsersWeek + "</b>\n"
                        + "✅ Выполненных заданий: <b>" + users.stream().mapToInt(AppUser::getCompletedQuests).sum() + "</b>\n"
                        + "🎟️ Билетов в обороте: <b>" + users.stream().mapToLong(AppUser::getTickets).sum() + "</b>\n"
                        + "💰 EXC на счетах игроков: <b>" + totalCoins + " EXC</b>\n"
                        + "📥 Квестов на модерации: <b>" + pendingQuests + "</b>\n"
                        + "🎁 Заявок на награды: <b>" + pendingRewards + "</b>",
                keyboardFactory.smartLayout(List.of(
                        keyboardFactory.callback("🏠 Меню", "menu:main")
                )));
    }

    private void sendAdminLiveStatus(AppUser user) {
        long activeQuests = questService.countActiveInProgress();
        long activeToday = userService.countActiveToday();
        String updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));

        sendText(user.getTelegramId(),
                "📡 <b>Сейчас на платформе</b>\n\n"
                        + "🎯 Квестов в работе: <b>" + activeQuests + "</b>\n"
                        + "   <i>(взято в работу или отправлено на проверку, срок не истёк)</i>\n\n"
                        + "🟢 Заходило в бота сегодня: <b>" + activeToday + "</b>\n\n"
                        + "🕐 Обновлено: <b>" + updatedAt + "</b>",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("🔄 Обновить", "admin:live")),
                        List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
                )));
    }

    private void sendAdminQuestStats(AppUser user) {
        List<Object[]> rows = questService.getTopQuestsByCompletions();
        if (rows.isEmpty()) {
            sendText(user.getTelegramId(), "📈 <b>Топ квестов</b>\n\nВыполненных квестов пока нет.", backMenuKeyboard("menu:admin"));
            return;
        }
        StringBuilder sb = new StringBuilder("📈 <b>Топ квестов по выполнениям</b>\n\n");
        int i = 1;
        for (Object[] row : rows) {
            String title = (String) row[1];
            String game = (String) row[2];
            String category = (String) row[3];
            long count = ((Number) row[4]).longValue();
            String medal = i == 1 ? "🥇" : i == 2 ? "🥈" : i == 3 ? "🥉" : i + ".";
            sb.append(medal).append(" <b>").append(escape(title)).append("</b>\n")
              .append("   🎮 ").append(escape(game)).append(" · ").append(escape(category))
              .append(" · <b>").append(count).append("</b> раз\n\n");
            if (i >= 20) break;
            i++;
        }
        sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:admin"));
    }

    private void sendAdminTrafficList(AppUser user) {
        List<ru.gamebot.platform.domain.model.TrafficSource> sources = trafficSourceService.findAll();
        StringBuilder sb = new StringBuilder("📈 <b>Источники трафика</b>\n\n");
        if (sources.isEmpty()) {
            sb.append("Источников пока нет.");
        } else {
            for (ru.gamebot.platform.domain.model.TrafficSource ts : sources) {
                long regs = userService.countByTrafficSource(ts.getCode());
                sb.append("• <b>").append(escape(ts.getName())).append("</b>")
                        .append(" — переходов: <b>").append(ts.getClicks()).append("</b>")
                        .append(", зарег.: <b>").append(regs).append("</b>\n");
            }
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.TrafficSource ts : sources) {
            rows.add(List.of(keyboardFactory.callback("👁 " + ts.getName(), "admin:traffic:view:" + ts.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("➕ Создать источник", "admin:traffic:create")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminTrafficView(AppUser user, Long sourceId) {
        trafficSourceService.findById(sourceId).ifPresentOrElse(ts -> {
            sendAdminTrafficUsersPage(user, sourceId, 0);
        }, () -> sendText(user.getTelegramId(), "❌ Источник не найден.", backMenuKeyboard("admin:traffic")));
    }

    private void sendAdminTrafficUsersPage(AppUser user, Long sourceId, int page) {
        trafficSourceService.findById(sourceId).ifPresentOrElse(ts -> {
            List<AppUser> users = userService.findByTrafficSource(ts.getCode());
            String link = "https://t.me/" + appProperties.getBotUsername() + "?start=src_" + ts.getCode();
            int pageSize = 10;
            int totalPages = Math.max(1, (int) Math.ceil(users.size() / (double) pageSize));
            int p = Math.max(0, Math.min(page, totalPages - 1));
            int from = p * pageSize;
            int to = Math.min(users.size(), from + pageSize);
            StringBuilder sb = new StringBuilder();
            sb.append("📈 <b>").append(escape(ts.getName())).append("</b>\n\n");
            sb.append("🔗 <code>").append(link).append("</code>\n");
            sb.append("👆 Переходов: <b>").append(ts.getClicks()).append("</b>\n");
            sb.append("👥 Регистраций: <b>").append(users.size()).append("</b>\n\n");
            if (users.isEmpty()) {
                sb.append("Пользователей пока нет.");
            } else {
                sb.append("Стр. ").append(p + 1).append("/").append(totalPages).append(":\n");
                for (AppUser u : users.subList(from, to)) {
                    sb.append("• ");
                    if (u.getTelegramUsername() != null) sb.append("@").append(u.getTelegramUsername()).append(" ");
                    sb.append("<b>").append(escape(u.getNickname())).append("</b>");
                    sb.append(" — ").append(u.getXp()).append(" XP\n");
                }
            }
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> nav = new ArrayList<>();
            if (p > 0) nav.add(keyboardFactory.callback("⬅️", "admin:traffic:view:page:" + sourceId + ":" + (p - 1)));
            if (p < totalPages - 1) nav.add(keyboardFactory.callback("➡️", "admin:traffic:view:page:" + sourceId + ":" + (p + 1)));
            if (!nav.isEmpty()) rows.add(nav);
            rows.add(List.of(keyboardFactory.callback("🗑 Удалить источник", "admin:traffic:delete:" + sourceId)));
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:traffic")));
            sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
        }, () -> sendText(user.getTelegramId(), "❌ Источник не найден.", backMenuKeyboard("admin:traffic")));
    }

    // ─── Sponsors ─────────────────────────────────────────────────────────────

    private void sendAdminSponsorList(AppUser user) {
        List<ru.gamebot.platform.domain.model.Sponsor> sponsors = sponsorService.findAll();
        StringBuilder sb = new StringBuilder("🤝 <b>Спонсорские квесты</b>\n\n");
        if (sponsors.isEmpty()) {
            sb.append("Кампаний пока нет. Добавьте первого спонсора!");
        } else {
            long totalBudget = sponsors.stream().mapToLong(ru.gamebot.platform.domain.model.Sponsor::getBudgetExc).sum();
            long totalSpent = sponsors.stream().mapToLong(ru.gamebot.platform.domain.model.Sponsor::getSpentExc).sum();
            sb.append("Бюджет: <b>").append(totalBudget).append(" EXC</b> | Выдано: <b>").append(totalSpent).append(" EXC</b>");
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.Sponsor s : sponsors) {
            String icon = s.isActive() ? "🟢" : "⚫";
            long rem = sponsorService.remainingBudget(s);
            rows.add(List.of(keyboardFactory.callback(
                    icon + " " + s.getName() + " — " + rem + " EXC осталось",
                    "admin:sponsors:view:" + s.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("➕ Добавить спонсора", "admin:sponsors:create")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminSponsorView(AppUser user, long sponsorId) {
        sponsorService.findById(sponsorId).ifPresentOrElse(s -> {
            List<ru.gamebot.platform.domain.model.Quest> linked = sponsorService.findSponsoredQuests(sponsorId);
            long rem = sponsorService.remainingBudget(s);
            long commission = sponsorService.commissionRub(s);

            StringBuilder sb = new StringBuilder("🤝 <b>" + escape(s.getName()) + "</b>\n");
            sb.append("📋 Кампания: ").append(escape(s.getCampaignName() != null ? s.getCampaignName() : "—")).append("\n");
            sb.append("Статус: ").append(s.isActive() ? "🟢 Активна" : "⚫ Завершена").append("\n\n");
            sb.append("💵 Оплата: <b>").append(s.getPaidRub()).append(" ₽</b>\n");
            sb.append("   ├ Комиссия EGC: <b>").append(commission).append(" ₽</b>\n");
            sb.append("   └ В Payout Pool: <b>").append(s.getPaidRub() - commission).append(" ₽</b>\n\n");
            sb.append("💎 Бюджет: <b>").append(s.getBudgetExc()).append(" EXC</b>\n");
            sb.append("📤 Выдано игрокам: <b>").append(s.getSpentExc()).append(" EXC</b>\n");
            sb.append("💰 Остаток: <b>").append(rem).append(" EXC</b>\n\n");
            sb.append("🗺️ <b>Привязанные квесты (").append(linked.size()).append("):</b>\n");
            for (ru.gamebot.platform.domain.model.Quest q : linked) {
                sb.append("• ").append(escape(q.getTitle())).append(" — ").append(q.getRewardCoins()).append(" EXC\n");
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            if (s.isActive()) {
                rows.add(List.of(keyboardFactory.callback("➕ Привязать квест", "admin:sponsors:addquest:" + sponsorId)));
                rows.add(List.of(keyboardFactory.callback("⚫ Завершить кампанию", "admin:sponsors:deactivate:" + sponsorId)));
            }
            for (ru.gamebot.platform.domain.model.Quest q : linked) {
                rows.add(List.of(keyboardFactory.callback("❌ Открепить: " + q.getTitle(), "admin:sponsors:unlink:" + q.getId())));
            }
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:sponsors")));
            sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
        }, () -> sendText(user.getTelegramId(), "❌ Спонсор не найден.", backMenuKeyboard("admin:sponsors")));
    }

    private void sendSponsorQuestPicker(AppUser user, long sponsorId) {
        List<ru.gamebot.platform.domain.model.Quest> allQuests = questService.findAll().stream()
                .filter(q -> !q.isSponsored())
                .toList();
        if (allQuests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "⚠️ Нет квестов без спонсора. Сначала создайте квест или открепите его от другой кампании.",
                    backMenuKeyboard("admin:sponsors:view:" + sponsorId));
            return;
        }
        StringBuilder sb = new StringBuilder("🗺️ Выберите квест для привязки к кампании:\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.Quest q : allQuests.stream().limit(20).toList()) {
            rows.add(List.of(keyboardFactory.callback(
                    q.getTitle() + " (" + q.getRewardCoins() + " EXC)",
                    "admin:sponsors:link:" + sponsorId + ":" + q.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:sponsors:view:" + sponsorId)));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    // ─── Battle Pass ──────────────────────────────────────────────────────────

    private void sendBattlePass(AppUser user) {
        boolean hasPass = seasonService.hasActivePass(user);
        java.util.Optional<ru.gamebot.platform.domain.model.Season> seasonOpt = seasonService.findCurrentSeason();

        if (hasPass) {
            java.time.LocalDateTime until = user.getSeasonPassActiveUntil();
            StringBuilder sb = new StringBuilder("🎫 <b>Battle Pass — активен</b>\n\n");
            sb.append("✅ Ваш пропуск активен до: <b>")
              .append(until.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
              .append("</b>\n\n");
            seasonOpt.ifPresent(s -> {
                sb.append("⚡ Бонус XP за квесты: <b>+" + s.getXpBoostPercent() + "%</b>\n");
                sb.append("🌟 Доступны эксклюзивные сезонные квесты\n");
                sb.append("👑 Значок Battle Pass в профиле и рейтинге\n");
            });
            sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:main"));
            return;
        }

        if (seasonOpt.isEmpty()) {
            sendText(user.getTelegramId(),
                    "🎫 <b>Battle Pass</b>\n\n⏳ Активного сезона сейчас нет. Следите за анонсами!",
                    backMenuKeyboard("menu:main"));
            return;
        }

        ru.gamebot.platform.domain.model.Season s = seasonOpt.get();
        StringBuilder sb = new StringBuilder("🎫 <b>Battle Pass — " + escape(s.getName()) + "</b>\n\n");
        sb.append("💰 Стоимость: <b>" + s.getPriceExc() + " EXC</b>\n");
        sb.append("⚡ XP-буст: <b>+" + s.getXpBoostPercent() + "% к каждому квесту</b>\n");
        sb.append("🌟 Эксклюзивные сезонные квесты\n");
        sb.append("👑 Значок в профиле и рейтинге\n");
        if (s.getEndDate() != null) sb.append("⏰ Действует до: <b>" + s.getEndDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) + "</b>\n");
        sb.append("\n💼 Ваш баланс: <b>" + user.getCoins() + " EXC</b>");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (user.getCoins() >= s.getPriceExc()) {
            rows.add(List.of(keyboardFactory.callback("🎫 Купить Battle Pass", "battlepass:buy:" + s.getId())));
        } else {
            rows.add(List.of(keyboardFactory.callback("❌ Недостаточно EXC", "noop")));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:main")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminSeasonList(AppUser user) {
        List<ru.gamebot.platform.domain.model.Season> seasons = seasonService.findAll();
        StringBuilder sb = new StringBuilder("🎫 <b>Battle Pass — сезоны</b>\n\n");
        if (seasons.isEmpty()) sb.append("Сезонов пока нет.");
        else sb.append("Всего: " + seasons.size());
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.Season s : seasons) {
            String icon = s.isActive() ? "🟢" : "⚫";
            rows.add(List.of(keyboardFactory.callback(
                    icon + " " + s.getName() + " (" + s.getPriceExc() + " EXC)",
                    "admin:seasons:view:" + s.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("➕ Создать сезон", "admin:seasons:create")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminSeasonView(AppUser user, long seasonId) {
        seasonService.findById(seasonId).ifPresentOrElse(s -> {
            StringBuilder sb = new StringBuilder("🎫 <b>" + escape(s.getName()) + "</b>\n\n");
            sb.append("Статус: " + (s.isActive() ? "🟢 Активен" : "⚫ Деактивирован") + "\n");
            sb.append("💰 Цена: <b>" + s.getPriceExc() + " EXC</b>\n");
            sb.append("⚡ XP-буст: <b>+" + s.getXpBoostPercent() + "%</b>\n");
            if (s.getStartDate() != null) sb.append("🚀 Начало: " + s.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "\n");
            if (s.getEndDate() != null) sb.append("⏰ Конец: " + s.getEndDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "\n");

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            if (s.isActive()) {
                rows.add(List.of(keyboardFactory.callback("⚫ Деактивировать", "admin:seasons:deactivate:" + seasonId)));
            }
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:seasons")));
            sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
        }, () -> sendText(user.getTelegramId(), "❌ Сезон не найден.", backMenuKeyboard("admin:seasons")));
    }

    // ─── Tournaments ──────────────────────────────────────────────────────────

    private void sendAdminTournamentList(AppUser user) {
        List<ru.gamebot.platform.domain.model.Tournament> tournaments = tournamentService.findAll();
        StringBuilder sb = new StringBuilder("🏆 <b>Турниры</b>\n\n");
        if (tournaments.isEmpty()) sb.append("Турниров пока нет.");
        else sb.append("Всего: " + tournaments.size());
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.Tournament t : tournaments) {
            String icon = switch (t.getStatus()) {
                case REGISTRATION -> "📋";
                case ACTIVE -> "🔥";
                case FINISHED -> "🏁";
            };
            long entries = tournamentService.entryCount(t);
            rows.add(List.of(keyboardFactory.callback(
                    icon + " " + t.getName() + " (" + entries + " уч.)",
                    "admin:tournaments:view:" + t.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("➕ Создать турнир", "admin:tournaments:create")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminTournamentView(AppUser user, long tid) {
        tournamentService.findById(tid).ifPresentOrElse(t -> {
            long entries = tournamentService.entryCount(t);
            StringBuilder sb = new StringBuilder("🏆 <b>" + escape(t.getName()) + "</b>\n\n");
            sb.append("Статус: ").append(switch (t.getStatus()) {
                case REGISTRATION -> "📋 Регистрация";
                case ACTIVE -> "🔥 Активен";
                case FINISHED -> "🏁 Завершён";
            }).append("\n");
            if (t.getGameName() != null) sb.append("🎮 Игра: ").append(escape(t.getGameName())).append("\n");
            sb.append("💰 Взнос: <b>").append(t.getEntryFeeExc()).append(" EXC</b>\n");
            sb.append("🏅 Призовой фонд: <b>").append(t.getPrizePoolExc()).append(" EXC</b>\n");
            sb.append("👥 Участников: <b>").append(entries).append("</b>\n");
            if (t.getStartDate() != null) sb.append("🚀 Старт: ").append(t.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))).append("\n");
            if (t.getEndDate() != null) sb.append("⏰ Финиш: ").append(t.getEndDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))).append("\n");

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            if (t.getStatus() != ru.gamebot.platform.domain.model.Tournament.Status.FINISHED) {
                rows.add(List.of(keyboardFactory.callback("📊 Участники", "tournament:leaderboard:" + tid)));
            }
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:tournaments")));
            sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
        }, () -> sendText(user.getTelegramId(), "❌ Турнир не найден.", backMenuKeyboard("admin:tournaments")));
    }

    private void sendTournamentLeaderboard(AppUser user, long tid) {
        tournamentService.findById(tid).ifPresentOrElse(t -> {
            List<ru.gamebot.platform.domain.model.TournamentEntry> entries = tournamentService.getLeaderboard(t);
            StringBuilder sb = new StringBuilder("📊 <b>Участники — " + escape(t.getName()) + "</b>\n\n");
            if (entries.isEmpty()) {
                sb.append("Пока никто не записался.");
            } else {
                for (int i = 0; i < Math.min(20, entries.size()); i++) {
                    ru.gamebot.platform.domain.model.TournamentEntry e = entries.get(i);
                    String rank = e.getRank() > 0 ? e.getRank() + ". " : (i + 1) + ". ";
                    String nick = e.getUser().getNickname() != null ? e.getUser().getNickname() : "—";
                    String prize = e.getPrizeExc() > 0 ? " 🏆 +" + e.getPrizeExc() + " EXC" : "";
                    sb.append(rank).append(escape(nick)).append(prize).append("\n");
                }
                if (entries.size() > 20) sb.append("\n...и ещё " + (entries.size() - 20));
            }
            sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:tournament"));
        }, () -> sendText(user.getTelegramId(), "❌ Турнир не найден.", backMenuKeyboard("menu:main")));
    }

    @org.springframework.context.event.EventListener
    public void onTournamentFinished(ru.gamebot.platform.event.TournamentFinishedEvent event) {
        ru.gamebot.platform.domain.model.Tournament t = event.getTournament();
        List<ru.gamebot.platform.domain.model.TournamentEntry> entries = tournamentService.getLeaderboard(t);

        StringBuilder sb = new StringBuilder("🏆 <b>Итоги турнира — " + escape(t.getName()) + "</b>\n\n");
        long pool = t.getPrizePoolExc();
        sb.append("🏅 Призовой фонд: <b>" + pool + " EXC</b>\n");
        sb.append("👥 Участников: <b>" + entries.size() + "</b>\n\n");

        String[] medals = {"🥇", "🥈", "🥉"};
        for (int i = 0; i < Math.min(10, entries.size()); i++) {
            ru.gamebot.platform.domain.model.TournamentEntry e = entries.get(i);
            String medal = i < 3 ? medals[i] : (i + 1) + ".";
            String nick = e.getUser().getNickname() != null ? e.getUser().getNickname() : "—";
            String username = e.getUser().getTelegramUsername();
            sb.append(medal).append(" <b>").append(escape(nick)).append("</b>");
            if (username != null) sb.append(" (@").append(username).append(")");
            if (e.getPrizeExc() > 0) sb.append(" — <b>+").append(e.getPrizeExc()).append(" EXC</b>");
            sb.append("\n");
        }
        sb.append("\nПоздравляем победителей! 🎮\nСледите за новыми турнирами → @").append(getBotUsername());

        try {
            org.telegram.telegrambots.meta.api.methods.send.SendMessage msg = new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
            msg.setChatId(requiredChannelChatId());
            msg.setText(sb.toString());
            msg.setParseMode("HTML");
            execute(msg);
        } catch (Exception e) {
            log.error("Failed to publish tournament results for tournament {}", t.getId(), e);
        }

        // Notify each prize winner in private
        for (ru.gamebot.platform.domain.model.TournamentEntry e : entries) {
            if (e.getPrizeExc() > 0) {
                try {
                    sendText(e.getUser().getTelegramId(),
                            "🏆 <b>Турнир завершён!</b>\n\n"
                            + "Вы заняли <b>" + e.getRank() + " место</b> в турнире «" + escape(t.getName()) + "»\n"
                            + "💰 Приз зачислен: <b>+" + e.getPrizeExc() + " EXC</b>",
                            backMenuKeyboard("menu:main"));
                } catch (Exception ex) {
                    log.warn("Failed to notify user {} about tournament prize", e.getUser().getTelegramId());
                }
            }
        }
    }

    // ─── Polls ────────────────────────────────────────────────────────────────

    private void sendPollList(AppUser user) {
        List<ru.gamebot.platform.domain.model.Poll> polls = pollService.findActive();
        if (polls.isEmpty()) {
            sendText(user.getTelegramId(),
                    "🗳 <b>Голосования</b>\n\nАктивных голосований нет. Следите за обновлениями!",
                    backMenuKeyboard("menu:main"));
            return;
        }
        StringBuilder sb = new StringBuilder("🗳 <b>Активные голосования</b>\n\nВыберите, чтобы проголосовать:\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.Poll poll : polls) {
            long total = pollService.totalVotes(poll);
            boolean voted = pollService.hasVoted(poll, user);
            String prefix = voted ? "✅ " : "🗳 ";
            rows.add(List.of(keyboardFactory.callback(
                    prefix + poll.getQuestion() + " (" + total + " голосов)",
                    "poll:view:" + poll.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:main")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendPollDetail(AppUser user, long pollId) {
        pollService.findById(pollId).ifPresentOrElse(poll -> {
            List<String> options = pollService.getOptions(poll);
            long[] counts = pollService.getVoteCounts(poll);
            long total = pollService.totalVotes(poll);
            boolean voted = pollService.hasVoted(poll, user);

            StringBuilder sb = new StringBuilder("🗳 <b>" + escape(poll.getQuestion()) + "</b>\n\n");
            if (poll.isClosed()) sb.append("🔒 Голосование завершено\n\n");
            else if (poll.getClosesAt() != null)
                sb.append("⏰ Закрытие: <b>" + poll.getClosesAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")) + "</b>\n\n");
            sb.append("💰 Стоимость голоса: <b>" + poll.getPriceExc() + " EXC</b>\n");
            sb.append("👥 Всего голосов: <b>" + total + "</b>\n\n");

            for (int i = 0; i < options.size(); i++) {
                long cnt = i < counts.length ? counts[i] : 0;
                int pct = total > 0 ? (int) (cnt * 100 / total) : 0;
                int filled = pct / 10;
                String bar = "█".repeat(filled) + "░".repeat(10 - filled);
                sb.append((i + 1) + ". " + escape(options.get(i)) + "\n");
                sb.append("   [" + bar + "] " + pct + "% (" + cnt + ")\n\n");
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            if (!voted && !poll.isClosed()) {
                sb.append("\n<i>Ваш баланс: " + user.getCoins() + " EXC. Выберите вариант для голосования:</i>");
                for (int i = 0; i < options.size(); i++) {
                    rows.add(List.of(keyboardFactory.callback(
                            (i + 1) + ". " + options.get(i),
                            "poll:vote:" + poll.getId() + ":" + i)));
                }
            } else if (voted) {
                sb.append("\n✅ <i>Вы уже проголосовали.</i>");
            }
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:polls")));
            sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
        }, () -> sendText(user.getTelegramId(), "❌ Голосование не найдено.", backMenuKeyboard("menu:polls")));
    }

    private void handlePollVote(CallbackQuery callbackQuery, AppUser user, long pollId, int optionIndex) {
        pollService.findById(pollId).ifPresentOrElse(poll -> {
            ru.gamebot.platform.service.PollService.VoteResult result = pollService.castVote(user, poll, optionIndex);
            if (result.success()) {
                answer(callbackQuery.getId(), "✅ Голос принят!");
                sendPollDetail(user, pollId);
            } else {
                answer(callbackQuery.getId(), "❌ " + result.error());
            }
        }, () -> answer(callbackQuery.getId(), "❌ Голосование не найдено."));
    }

    private void sendAdminPollList(AppUser user) {
        List<ru.gamebot.platform.domain.model.Poll> polls = pollService.findAll();
        StringBuilder sb = new StringBuilder("🗳 <b>Голосования</b>\n\n");
        if (polls.isEmpty()) sb.append("Голосований пока нет.");
        else sb.append("Всего: " + polls.size());
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.Poll poll : polls) {
            String status = poll.isClosed() ? "🔒" : "🟢";
            long total = pollService.totalVotes(poll);
            rows.add(List.of(keyboardFactory.callback(
                    status + " " + poll.getQuestion() + " (" + total + ")",
                    "admin:polls:view:" + poll.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("➕ Создать голосование", "admin:polls:create")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminPollView(AppUser user, long pollId) {
        pollService.findById(pollId).ifPresentOrElse(poll -> {
            List<String> options = pollService.getOptions(poll);
            long[] counts = pollService.getVoteCounts(poll);
            long total = pollService.totalVotes(poll);

            StringBuilder sb = new StringBuilder("🗳 <b>" + escape(poll.getQuestion()) + "</b>\n\n");
            sb.append("Статус: " + (poll.isClosed() ? "🔒 Закрыто" : "🟢 Активно") + "\n");
            sb.append("💰 Цена голоса: <b>" + poll.getPriceExc() + " EXC</b> | Всего голосов: <b>" + total + "</b>\n");
            if (poll.getClosesAt() != null)
                sb.append("⏰ Закрытие: " + poll.getClosesAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "\n");
            sb.append("\n");
            for (int i = 0; i < options.size(); i++) {
                long cnt = i < counts.length ? counts[i] : 0;
                int pct = total > 0 ? (int) (cnt * 100 / total) : 0;
                sb.append((i + 1) + ". " + escape(options.get(i)) + " — <b>" + cnt + "</b> (" + pct + "%)\n");
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            if (!poll.isClosed()) {
                rows.add(List.of(keyboardFactory.callback("🔒 Закрыть и опубликовать", "admin:polls:close:" + pollId)));
                rows.add(List.of(keyboardFactory.callback("🗑 Удалить", "admin:polls:delete:" + pollId)));
            }
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:polls")));
            sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
        }, () -> sendText(user.getTelegramId(), "❌ Голосование не найдено.", backMenuKeyboard("admin:polls")));
    }

    private void publishPollResults(ru.gamebot.platform.domain.model.Poll poll) {
        List<String> options = pollService.getOptions(poll);
        long[] counts = pollService.getVoteCounts(poll);
        long total = pollService.totalVotes(poll);

        StringBuilder sb = new StringBuilder("🗳 <b>Результаты голосования</b>\n\n");
        sb.append("❓ <b>" + escape(poll.getQuestion()) + "</b>\n");
        sb.append("👥 Всего проголосовало: <b>" + total + "</b>\n\n");

        // Find winner
        int winnerIdx = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[winnerIdx]) winnerIdx = i;
        }

        for (int i = 0; i < options.size(); i++) {
            long cnt = i < counts.length ? counts[i] : 0;
            int pct = total > 0 ? (int) (cnt * 100 / total) : 0;
            int filled = pct / 10;
            String bar = "█".repeat(filled) + "░".repeat(10 - filled);
            String winner = (i == winnerIdx && total > 0) ? " 🏆" : "";
            sb.append((i + 1) + ". <b>" + escape(options.get(i)) + "</b>" + winner + "\n");
            sb.append("   [" + bar + "] " + pct + "% (" + cnt + " голосов)\n\n");
        }
        sb.append("Спасибо всем участникам! 🎮");

        try {
            org.telegram.telegrambots.meta.api.methods.send.SendMessage msg = new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
            msg.setChatId(requiredChannelChatId());
            msg.setText(sb.toString());
            msg.setParseMode("HTML");
            execute(msg);
        } catch (Exception e) {
            log.error("Failed to publish poll results for poll {}", poll.getId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onPollClosed(ru.gamebot.platform.event.PollClosedEvent event) {
        publishPollResults(event.getPoll());
    }

    private void sendAdminUsersPage(AppUser admin, Integer requestedPage) {
        List<AppUser> users = userService.allUsersSorted();
        if (users.isEmpty()) {
            sendText(admin.getTelegramId(),
                    "👥 <b>Пользователи</b>\n\nВ базе пока нет пользователей.",
                    backMenuKeyboard("menu:admin"));
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(users.size() / (double) ADMIN_USERS_PAGE_SIZE));
        int page = requestedPage == null ? 0 : Math.max(0, Math.min(requestedPage, totalPages - 1));
        int from = page * ADMIN_USERS_PAGE_SIZE;
        int to = Math.min(users.size(), from + ADMIN_USERS_PAGE_SIZE);
        List<AppUser> pageItems = users.subList(from, to);

        StringBuilder builder = new StringBuilder("👥 <b>Пользователи платформы</b>\n\n");
        builder.append("Всего: <b>").append(users.size()).append("</b>\n");
        builder.append("Страница <b>").append(page + 1).append(" / ").append(totalPages).append("</b>\n\n");
        for (AppUser target : pageItems) {
            builder.append("👤 <b>").append(escape(displayUserName(target))).append("</b>\n")
                    .append("🏷️ Тег: <b>").append(escape(displayTag(target))).append("</b>\n")
                    .append("🆔 TG ID: <b>").append(target.getTelegramId()).append("</b>\n")
                    .append("⭐ Уровень: <b>").append(userService.getLevelNumber(target.getXp())).append(". ").append(escape(userService.getLevelName(target.getXp()))).append("</b>\n")
                    .append("💰 EXC: <b>").append(target.getCoins()).append("</b>\n")
                    .append("📅 Зарегистрирован: <b>").append(target.getCreatedAt() != null ? target.getCreatedAt().toLocalDate().toString() : "—").append("</b>\n\n");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser target : pageItems) {
            rows.add(List.of(
                    keyboardFactory.callback("👤 " + trim(displayUserName(target), 24), "admin:user:view:" + target.getTelegramId() + ":" + page)
            ));
        }

        List<InlineKeyboardButton> pagination = new ArrayList<>();
        if (page > 0) {
            pagination.add(keyboardFactory.callback("⬅️ Назад", "admin:users:" + (page - 1)));
        }
        if (page < totalPages - 1) {
            pagination.add(keyboardFactory.callback("➡️ Далее", "admin:users:" + (page + 1)));
        }
        if (!pagination.isEmpty()) {
            rows.add(pagination);
        }
        rows.add(List.of(
                keyboardFactory.callback("🔍 Найти по TG ID", "admin:users:search"),
                keyboardFactory.callback("📊 По уровням", "admin:users:bylevel")
        ));
        rows.add(List.of(keyboardFactory.callback("📸 Для постов", "admin:users:post")));
        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));
        sendText(admin.getTelegramId(), builder.toString(), keyboardFactory.rowsLayout(rows));
    }

    private static final String[] LEVEL_NAMES = {
        "", "Новичок", "Игрок", "Ветеран", "Элита", "Легенда",
        "Герой EXPERIENCE", "Чемпион EXPERIENCE", "Амбассадор EXPERIENCE"
    };
    private static final long[] LEVEL_MIN_XP = {0, 0, 1_000, 5_000, 15_000, 35_000, 75_000, 150_000, 300_000};

    private void sendAdminUsersByLevel(AppUser admin) {
        List<AppUser> all = userService.allUsersSorted();
        int[] counts = new int[9]; // index = level 1..8
        for (AppUser u : all) {
            int lvl = Math.min(8, Math.max(1, userService.getLevelNumber(u.getXp())));
            counts[lvl]++;
        }
        StringBuilder sb = new StringBuilder("📊 <b>Пользователи по уровням</b>\n\n");
        sb.append("Всего зарегистрированных: <b>").append(all.size()).append("</b>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int lvl = 8; lvl >= 1; lvl--) {
            String limitStr = lvl == 1 ? "10 000" : lvl == 2 ? "25 000" : lvl == 3 ? "50 000"
                    : lvl == 4 ? "80 000" : lvl == 5 ? "100 000" : "150 000";
            sb.append("Ур. <b>").append(lvl).append(" — ").append(LEVEL_NAMES[lvl]).append("</b>: ")
              .append("<b>").append(counts[lvl]).append("</b> чел.  |  лимит вывода: <b>").append(limitStr).append(" EXC/мес</b>\n");
            if (counts[lvl] > 0) {
                rows.add(List.of(keyboardFactory.callback(
                        "Ур." + lvl + " " + LEVEL_NAMES[lvl] + " (" + counts[lvl] + ")",
                        "admin:users:level:" + lvl)));
            }
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ К пользователям", "admin:users:0")));
        sendText(admin.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminUsersOfLevel(AppUser admin, int level) {
        List<AppUser> all = userService.allUsersSorted();
        List<AppUser> filtered = all.stream()
                .filter(u -> Math.min(8, Math.max(1, userService.getLevelNumber(u.getXp()))) == level)
                .toList();
        String levelName = level >= 1 && level <= 8 ? LEVEL_NAMES[level] : "?";
        if (filtered.isEmpty()) {
            sendText(admin.getTelegramId(),
                    "📊 <b>Ур. " + level + " — " + levelName + "</b>\n\nПользователей нет.",
                    backMenuKeyboard("admin:users:bylevel"));
            return;
        }
        StringBuilder sb = new StringBuilder("📊 <b>Ур. " + level + " — " + levelName + "</b>\n");
        sb.append("<i>Всего: " + filtered.size() + " пользователей</i>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int shown = Math.min(filtered.size(), 30);
        for (int i = 0; i < shown; i++) {
            AppUser u = filtered.get(i);
            String tag = u.getTelegramUsername() != null ? "@" + u.getTelegramUsername() : "ID:" + u.getTelegramId();
            sb.append(i + 1).append(". <b>").append(escape(u.getNickname() != null ? u.getNickname() : tag)).append("</b>")
              .append(" — ").append(u.getXp()).append(" XP")
              .append(" — ").append(u.getCoins()).append(" EXC")
              .append("\n");
            rows.add(List.of(keyboardFactory.callback(
                    trim((u.getNickname() != null ? u.getNickname() : tag), 28),
                    "admin:user:view:" + u.getTelegramId() + ":0")));
        }
        if (filtered.size() > 30) {
            sb.append("\n<i>...и ещё ").append(filtered.size() - 30).append(" пользователей</i>");
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ По уровням", "admin:users:bylevel")));
        sendText(admin.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminUsersPostCard(AppUser admin) {
        List<AppUser> top5 = userService.top5Overall();
        long totalUsers = userService.totalRegisteredUsers();
        long newThisWeek = userService.countNewUsersSince(java.time.LocalDateTime.now().minusWeeks(1));
        long totalQuests = questService.countAllApproved();
        long totalExc = questService.sumAllIssuedCoins();
        String topGame = questService.topGameName();

        String[] medals = {"🥇", "🥈", "🥉"};
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>EXPERIENCE GAMING CLUB</b>\n\n");

        sb.append("👥 <b>Участников:</b> ").append(totalUsers).append("\n");
        sb.append("🆕 <b>Новых за неделю:</b> ").append(newThisWeek).append("\n");
        sb.append("✅ <b>Квестов выполнено:</b> ").append(totalQuests).append("\n");
        sb.append("💰 <b>EXC выдано всего:</b> ").append(totalExc).append("\n");
        sb.append("🎮 <b>Топ игра:</b> ").append(escape(topGame)).append("\n");

        sb.append("\n🏅 <b>ТОП-3 ИГРОКОВ</b>\n\n");

        int limit = Math.min(3, top5.size());
        for (int i = 0; i < limit; i++) {
            AppUser u = top5.get(i);
            int level = userService.getLevelNumber(u.getXp());
            String levelName = userService.getLevelName(u.getXp());
            sb.append(medals[i]).append(" <b>").append(escape(displayUserName(u))).append("</b>\n");
            sb.append("    ⭐ Ур. ").append(level).append(" · ").append(escape(levelName))
              .append(" · ").append(u.getXp()).append(" XP\n");
            sb.append("    💰 ").append(u.getCoins()).append(" EXC\n\n");
        }

        sb.append("📅 ").append(java.time.LocalDate.now());

        sendText(admin.getTelegramId(), sb.toString(),
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("⬅️ К пользователям", "admin:users:0")),
                        List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
                )));
    }

    private void handleAdminUserAction(AppUser admin, UserSession session, String payload) {
        String[] parts = payload.split(":");
        if (parts.length < 3) {
            sendText(admin.getTelegramId(), "⚠️ Карточка пользователя недоступна.", backMenuKeyboard("menu:admin"));
            return;
        }

        String action = parts[0];
        Long telegramId = parseLong(parts[1]);
        Integer page = parseInteger(parts[2]);
        if (telegramId == null) {
            sendText(admin.getTelegramId(), "⚠️ Карточка пользователя недоступна.", backMenuKeyboard("menu:admin"));
            return;
        }

        if ("view".equals(action)) {
            sendAdminUserCard(admin, telegramId, page == null ? 0 : page, null);
            return;
        }

        if ("role".equals(action) && parts.length >= 4) {
            String role = parts[3];
            applyUserRoleChange(admin, telegramId, role, page == null ? 0 : page);
            return;
        }

        if ("quests".equals(action)) {
            sendAdminUserQuestHistory(admin, telegramId, page == null ? 0 : page);
            return;
        }

        if ("exc".equals(action)) {
            sendAdminUserExcHistory(admin, telegramId, page == null ? 0 : page);
            return;
        }

        if ("resetquests".equals(action)) {
            AppUser target = userService.findByTelegramId(telegramId).orElse(null);
            if (target == null) {
                sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
                return;
            }
            int count = questService.resetActiveSubmissions(target);
            String notice = count > 0
                    ? "🗑 Сброшено активных квестов: <b>" + count + "</b>."
                    : "ℹ️ Активных квестов не было.";
            sendAdminUserCard(admin, telegramId, page == null ? 0 : page, notice);
            return;
        }

        if ("block".equals(action)) {
            AppUser target = userService.findByTelegramId(telegramId).orElse(null);
            if (target == null) {
                sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
                return;
            }
            if (telegramId.equals(admin.getTelegramId())) {
                sendText(admin.getTelegramId(), "⚠️ Нельзя заблокировать самого себя.",
                        backMenuKeyboard("admin:user:view:" + telegramId + ":" + (page == null ? 0 : page)));
                return;
            }
            session.reset();
            session.setQuestId(telegramId);
            session.getData().put("blockPage", String.valueOf(page == null ? 0 : page));
            session.setState(SessionState.BLOCK_USER_REASON);
            sendText(admin.getTelegramId(),
                    "🚫 <b>Блокировка пользователя</b>\n\n"
                            + "👤 " + escape(displayUserName(target)) + " (ID: " + telegramId + ")\n\n"
                            + "Напишите причину блокировки — она будет сохранена и отправлена пользователю:",
                    cancelKeyboard());
            return;
        }

        if ("unblock".equals(action)) {
            AppUser target = userService.findByTelegramId(telegramId).orElse(null);
            if (target == null) {
                sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
                return;
            }
            userService.unblockUser(telegramId);
            sendText(telegramId, "✅ Вы разблокированы администратором. Снова доступны все функции клуба.", null);
            sendAdminUserCard(admin, telegramId, page == null ? 0 : page, "✅ Пользователь разблокирован.");
            return;
        }

        sendText(admin.getTelegramId(), "⚠️ Действие с пользователем не распознано.", backMenuKeyboard("menu:admin"));
    }

    private void sendAdminUserQuestHistory(AppUser admin, Long telegramId, int page) {
        AppUser target = userService.findByTelegramId(telegramId).orElse(null);
        if (target == null) {
            sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
            return;
        }

        List<ru.gamebot.platform.domain.model.QuestSubmission> all =
                questService.findAllByUser(target);

        long approvedCount = all.stream()
                .filter(s -> s.getStatus() == ru.gamebot.platform.domain.enums.SubmissionStatus.APPROVED)
                .count();

        String header = "📋 <b>Квесты игрока</b>\n"
                + "👤 <b>" + escape(displayUserName(target)) + "</b> (ID: " + telegramId + ")\n"
                + "Всего заявок: <b>" + all.size() + "</b> · Одобрено: <b>" + approvedCount + "</b>\n\n";

        if (all.isEmpty()) {
            sendText(admin.getTelegramId(), header + "Заявок нет.",
                    backMenuKeyboard("admin:user:view:" + telegramId + ":" + page));
            return;
        }

        int pageSize = 10;
        int totalPages = (all.size() + pageSize - 1) / pageSize;
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        List<ru.gamebot.platform.domain.model.QuestSubmission> pageItems =
                all.subList(safePage * pageSize, Math.min((safePage + 1) * pageSize, all.size()));

        StringBuilder sb = new StringBuilder(header);
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");
        int startNum = safePage * pageSize + 1;
        for (int i = 0; i < pageItems.size(); i++) {
            ru.gamebot.platform.domain.model.QuestSubmission s = pageItems.get(i);
            String dateStr = s.getUpdatedAt() != null ? s.getUpdatedAt().format(fmt) : "—";
            String statusIcon = switch (s.getStatus()) {
                case APPROVED -> "✅";
                case REJECTED -> "❌";
                case NEEDS_INFO -> "❓";
                case PENDING -> "⏳";
                default -> "📌";
            };
            String completionTag = s.getCompletionDisplayId() != null ? " (З-" + s.getCompletionDisplayId() + ")" : "";
            sb.append(startNum + i).append(". ").append(statusIcon)
              .append(" <b>").append(escape(s.getQuest().getTitle())).append("</b>").append(completionTag).append("\n")
              .append("   🎮 ").append(escape(s.getQuest().getGameName()))
              .append(" · 💰 ").append(s.getQuest().getRewardCoins()).append(" EXC\n")
              .append("   📅 ").append(dateStr).append("\n\n");
        }
        if (totalPages > 1) {
            sb.append("📄 Страница ").append(safePage + 1).append(" из ").append(totalPages);
        }

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (safePage > 0) {
            navRow.add(keyboardFactory.callback("⬅️", "admin:user:quests:" + telegramId + ":" + (safePage - 1)));
        }
        if (safePage < totalPages - 1) {
            navRow.add(keyboardFactory.callback("➡️", "admin:user:quests:" + telegramId + ":" + (safePage + 1)));
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (!navRow.isEmpty()) rows.add(navRow);
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад к карточке", "admin:user:view:" + telegramId + ":" + page)));

        sendText(admin.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminUserExcHistory(AppUser admin, Long telegramId, int page) {
        AppUser target = userService.findByTelegramId(telegramId).orElse(null);
        if (target == null) {
            sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
            return;
        }

        int pageSize = 10;
        long total = excTransactionService.countAll(target);
        int totalPages = total == 0 ? 1 : (int) ((total + pageSize - 1) / pageSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        List<ru.gamebot.platform.domain.model.ExcTransaction> items =
                excTransactionService.getHistory(target, safePage, pageSize);
        // Внутри страницы показываем от старых к новым — чтобы цепочка Было/Стало читалась сверху вниз естественно
        java.util.Collections.reverse(items);

        String header = "💳 <b>История EXC</b>\n"
                + "👤 <b>" + escape(displayUserName(target)) + "</b> (ID: " + telegramId + ")\n"
                + "💰 Баланс: <b>" + target.getCoins() + " EXC</b>\n"
                + "Всего операций: <b>" + total + "</b>\n\n";

        if (items.isEmpty()) {
            sendText(admin.getTelegramId(), header + "Операций нет.",
                    backMenuKeyboard("admin:user:view:" + telegramId + ":0"));
            return;
        }

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");
        StringBuilder sb = new StringBuilder(header);
        for (ru.gamebot.platform.domain.model.ExcTransaction tx : items) {
            String sign = tx.getAmount() >= 0 ? "+" : "";
            String desc = tx.getDescription() != null ? escape(tx.getDescription()) : "";
            String meta = tx.getCreatedAt().format(fmt) + (desc.isEmpty() ? "" : ", " + desc);

            Long after = tx.getBalanceAfter();
            if (after != null) {
                long before = after - tx.getAmount();
                sb.append("💸 Было <b>").append(before).append(" EXC</b>\n")
                  .append(ru.gamebot.platform.service.ExcTransactionService.typeLabel(tx.getType()))
                  .append("  <b>").append(sign).append(tx.getAmount()).append(" EXC</b>")
                  .append(" (").append(meta).append(")\n")
                  .append("💸 Стало <b>").append(after).append(" EXC</b>\n\n");
            } else {
                // Старая запись без сохранённого баланса после операции — показываем как раньше
                sb.append(ru.gamebot.platform.service.ExcTransactionService.typeLabel(tx.getType()))
                  .append("  <b>").append(sign).append(tx.getAmount()).append(" EXC</b>")
                  .append(" (").append(meta).append(")\n\n");
            }
        }
        if (totalPages > 1) {
            sb.append("📄 Стр. ").append(safePage + 1).append(" / ").append(totalPages);
        }

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (safePage > 0) {
            navRow.add(keyboardFactory.callback("⬅️", "admin:user:exc:" + telegramId + ":" + (safePage - 1)));
        }
        if (safePage < totalPages - 1) {
            navRow.add(keyboardFactory.callback("➡️", "admin:user:exc:" + telegramId + ":" + (safePage + 1)));
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (!navRow.isEmpty()) rows.add(navRow);
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад к карточке", "admin:user:view:" + telegramId + ":0")));

        sendText(admin.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminUserCard(AppUser admin, Long telegramId, int page, String notice) {
        AppUser target = userService.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок не найден."));
        String configuredRole = adminService.configuredRole(telegramId);
        String configuredNote = ROLE_USER.equals(configuredRole)
                ? ""
                : "\n🔒 Закреплено через ENV: <b>" + escape(humanRole(configuredRole)) + "</b>";

        String text = (notice == null ? "" : notice + "\n\n")
                + "👤 <b>Карточка пользователя</b>\n\n"
                + "🎮 Имя: <b>" + escape(displayUserName(target)) + "</b>\n"
                + "🏷️ Тег: <b>" + escape(displayTag(target)) + "</b>\n"
                + "🆔 TG ID: <b>" + target.getTelegramId() + "</b>\n"
                + "⭐ Уровень: <b>" + userService.getLevelNumber(target.getXp()) + ". " + escape(userService.getLevelName(target.getXp())) + "</b>\n"
                + "💰 EXC: <b>" + target.getCoins() + "</b>\n"
                + "📅 Зарегистрирован: <b>" + (target.getCreatedAt() != null ? target.getCreatedAt().toLocalDate().toString() : "—") + "</b>\n"
                + "🛡️ Роль: <b>" + escape(humanRole(highestAvailableRole(target))) + "</b>\n"
                + configuredNote + "\n"
                + "✅ Регистрация: <b>" + (target.isRegistrationCompleted() ? "завершена" : "не завершена") + "</b>\n"
                + (target.isBlocked()
                        ? "🚫 Статус: <b>заблокирован</b>"
                                + (target.getBlockReason() != null && !target.getBlockReason().isBlank()
                                        ? "\n   Причина: <i>" + escape(target.getBlockReason()) + "</i>"
                                        : "")
                        : "🟢 Статус: <b>активен</b>");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>(List.of(
                List.of(keyboardFactory.callback("📋 Квесты игрока", "admin:user:quests:" + telegramId + ":" + page)),
                List.of(keyboardFactory.callback("💳 История EXC", "admin:user:exc:" + telegramId + ":0")),
                List.of(keyboardFactory.callback("🗑 Сбросить активные квесты", "admin:user:resetquests:" + telegramId + ":" + page)),
                List.of(keyboardFactory.callback("👤 Сделать игроком", "admin:user:role:" + telegramId + ":" + page + ":" + ROLE_USER)),
                List.of(keyboardFactory.callback("🛡️ Сделать модератором", "admin:user:role:" + telegramId + ":" + page + ":" + ROLE_MODER)),
                List.of(keyboardFactory.callback("🛠️ Сделать админом", "admin:user:role:" + telegramId + ":" + page + ":" + ROLE_ADMIN)),
                List.of(target.isBlocked()
                        ? keyboardFactory.callback("✅ Разблокировать", "admin:user:unblock:" + telegramId + ":" + page)
                        : keyboardFactory.callback("🚫 Заблокировать", "admin:user:block:" + telegramId + ":" + page)),
                List.of(
                        keyboardFactory.callback("⬅️ К списку", "admin:users:" + page),
                        keyboardFactory.callback("🏠 Меню", "menu:main")
                )
        ));
        sendText(admin.getTelegramId(), text, keyboardFactory.rowsLayout(rows));
    }

    private void applyUserRoleChange(AppUser admin, Long telegramId, String role, int page) {
        String normalizedRole = normalizeRole(role);
        AppUser target = userService.updateStaffRole(telegramId, normalizedRole);
        UserSession targetSession = sessionService.get(telegramId);
        targetSession.reset();
        targetSession.getData().put("active_role", highestAvailableRole(target));

        sendAdminUserCard(admin, telegramId, page,
                "✅ Роль пользователя обновлена: <b>" + escape(humanRole(normalizedRole)) + "</b>.");

        String configuredRole = adminService.configuredRole(telegramId);
        String extraNote = ROLE_USER.equals(configuredRole)
                ? ""
                : "\n\n🔒 Для этого аккаунта также действует закреплённая роль через ENV: <b>" + escape(humanRole(configuredRole)) + "</b>.";
        sendText(telegramId,
                "🛡️ <b>Роль обновлена</b>\n\n"
                        + "Администратор назначил вам роль: <b>" + escape(humanRole(normalizedRole)) + "</b>."
                        + extraNote,
                mainMenuKeyboard(target));
    }

    private void sendAdminBonusUsersPage(AppUser admin, UserSession session, Integer requestedPage, String notice) {
        List<AppUser> users = userService.allUsersSorted();
        if (users.isEmpty()) {
            sendText(admin.getTelegramId(),
                    "🎁 <b>Начисление бонуса</b>\n\nВ базе пока нет пользователей для выдачи бонуса.",
                    backMenuKeyboard("menu:main"));
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(users.size() / (double) BONUS_USERS_PAGE_SIZE));
        int page = requestedPage == null ? 0 : Math.max(0, Math.min(requestedPage, totalPages - 1));
        int from = page * BONUS_USERS_PAGE_SIZE;
        int to = Math.min(users.size(), from + BONUS_USERS_PAGE_SIZE);
        List<AppUser> pageItems = users.subList(from, to);
        session.getData().put("bonus_page", Integer.toString(page));

        StringBuilder builder = new StringBuilder();
        if (notice != null && !notice.isBlank()) {
            builder.append(notice).append("\n\n");
        }
        builder.append("🎁 <b>Начисление бонуса</b>\n\n")
                .append("Выберите игрока по номеру из списка ниже и отправьте данные одним сообщением.\n")
                .append("Формат: <code>НОМЕР XP COINS TICKETS комментарий</code>\n")
                .append("Пример: <code>").append(from + 1).append(" 100 50 3 За активность</code>\n\n")
                .append("Страница <b>").append(page + 1).append(" / ").append(totalPages).append("</b>\n\n");

        for (int i = 0; i < pageItems.size(); i++) {
            AppUser target = pageItems.get(i);
            int number = from + i + 1;
            builder.append(number).append(". <b>").append(escape(displayUserName(target))).append("</b>\n")
                    .append("🏷️ ").append(escape(displayTag(target))).append(" • ")
                    .append("ID: <code>").append(target.getTelegramId()).append("</code>\n\n");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> pagination = new ArrayList<>();
        if (page > 0) {
            pagination.add(keyboardFactory.callback("⬅️ Назад", "admin:bonuspage:" + (page - 1)));
        }
        if (page < totalPages - 1) {
            pagination.add(keyboardFactory.callback("➡️ Далее", "admin:bonuspage:" + (page + 1)));
        }
        if (!pagination.isEmpty()) {
            rows.add(pagination);
        }
        rows.add(List.of(
                keyboardFactory.callback("🛠️ Админка", "menu:admin"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));
        sendText(admin.getTelegramId(), builder.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminDebitUsersPage(AppUser admin, UserSession session, Integer requestedPage, String notice) {
        List<AppUser> users = userService.allUsersSorted();
        if (users.isEmpty()) {
            sendText(admin.getTelegramId(),
                    "➖ <b>Списание баланса</b>\n\nВ базе пока нет пользователей для списания.",
                    backMenuKeyboard("menu:main"));
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(users.size() / (double) BONUS_USERS_PAGE_SIZE));
        int page = requestedPage == null ? 0 : Math.max(0, Math.min(requestedPage, totalPages - 1));
        int from = page * BONUS_USERS_PAGE_SIZE;
        int to = Math.min(users.size(), from + BONUS_USERS_PAGE_SIZE);
        List<AppUser> pageItems = users.subList(from, to);
        session.getData().put("debit_page", Integer.toString(page));

        StringBuilder builder = new StringBuilder();
        if (notice != null && !notice.isBlank()) {
            builder.append(notice).append("\n\n");
        }
        builder.append("➖ <b>Списание баланса</b>\n\n")
                .append("Выберите игрока по номеру из списка ниже и отправьте данные одним сообщением.\n")
                .append("Формат: <code>НОМЕР XP EXC TICKETS комментарий</code>\n")
                .append("Пример: <code>").append(from + 1).append(" 50 100 1 Корректировка баланса</code>\n\n")
                .append("Страница <b>").append(page + 1).append(" / ").append(totalPages).append("</b>\n\n");

        for (int i = 0; i < pageItems.size(); i++) {
            AppUser target = pageItems.get(i);
            int number = from + i + 1;
            builder.append(number).append(". <b>").append(escape(displayUserName(target))).append("</b>\n")
                    .append("🏷️ ").append(escape(displayTag(target))).append(" • ")
                    .append("ID: <code>").append(target.getTelegramId()).append("</code>\n")
                    .append("🪙 EXC: <b>").append(target.getCoins()).append("</b> • ")
                    .append("🎟️ ").append(target.getTickets()).append(" • ")
                    .append("✨ ").append(target.getXp()).append(" XP\n\n");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> pagination = new ArrayList<>();
        if (page > 0) {
            pagination.add(keyboardFactory.callback("⬅️ Назад", "admin:debitpage:" + (page - 1)));
        }
        if (page < totalPages - 1) {
            pagination.add(keyboardFactory.callback("➡️ Далее", "admin:debitpage:" + (page + 1)));
        }
        if (!pagination.isEmpty()) {
            rows.add(pagination);
        }
        rows.add(List.of(
                keyboardFactory.callback("🛠️ Админка", "menu:admin"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));
        sendText(admin.getTelegramId(), builder.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void handleBonusInput(AppUser user, UserSession session, String text) {
        String[] parts = text.trim().split("\\s+", 5);
        if (parts.length < 4) {
            sendAdminBonusUsersPage(user, session, currentBonusPage(session),
                    "⚠️ Формат неверный. Используйте: <code>НОМЕР XP COINS TICKETS комментарий</code>.");
            return;
        }

        Long telegramId = resolveBonusTarget(parts[0]);
        Long xp = parsePositiveLong(parts[1]);
        Long coins = parsePositiveLong(parts[2]);
        Long tickets = parsePositiveLong(parts[3]);
        String comment = parts.length >= 5 ? parts[4] : "За активность";
        if (telegramId == null || xp == null || coins == null || tickets == null) {
            sendAdminBonusUsersPage(user, session, currentBonusPage(session),
                    "⚠️ Проверьте номер игрока, XP, монеты и билеты. Они должны быть указаны корректно.");
            return;
        }

        UserService.RewardGrant rewardGrant = userService.addManualBonus(telegramId, xp, coins, tickets);
        session.reset();
        notifyUser(telegramId,
                "🎁 Администратор начислил вам бонус.\n\n"
                        + "✨ XP: <b>+" + rewardGrant.xp() + "</b>\n"
                        + "🪙 EXC: <b>+" + rewardGrant.totalExc() + "</b>\n"
                        + formatExcBonusLine(rewardGrant)
                        + "🎟️ Билеты: <b>+" + rewardGrant.tickets() + "</b>\n"
                        + "💬 Основание: <b>" + escape(comment) + "</b>");
        sendText(user.getTelegramId(), "✅ Бонус начислен игроку " + telegramId + ".", mainMenuKeyboard(user));
    }

    private void handleDebitInput(AppUser user, UserSession session, String text) {
        String[] parts = text.trim().split("\\s+", 5);
        if (parts.length < 4) {
            sendAdminDebitUsersPage(user, session, currentDebitPage(session),
                    "⚠️ Формат неверный. Используйте: <code>НОМЕР XP EXC TICKETS комментарий</code>.");
            return;
        }

        Long telegramId = resolveBonusTarget(parts[0]);
        Long xp = parsePositiveLong(parts[1]);
        Long exc = parsePositiveLong(parts[2]);
        Long tickets = parsePositiveLong(parts[3]);
        String comment = parts.length >= 5 ? parts[4] : "Корректировка баланса";
        if (telegramId == null || xp == null || exc == null || tickets == null) {
            sendAdminDebitUsersPage(user, session, currentDebitPage(session),
                    "⚠️ Проверьте номер игрока, XP, EXC и билеты. Они должны быть указаны корректно.");
            return;
        }

        try {
            UserService.BalanceDebit debit = userService.debitManualBalance(telegramId, xp, exc, tickets);
            session.reset();
            notifyUser(telegramId,
                    "➖ Администратор выполнил списание баланса.\n\n"
                            + "✨ XP: <b>-" + debit.xp() + "</b>\n"
                            + "🪙 EXC: <b>-" + debit.exc() + "</b>\n"
                            + "🎟️ Билеты: <b>-" + debit.tickets() + "</b>\n"
                            + "💬 Основание: <b>" + escape(comment) + "</b>");
            sendText(user.getTelegramId(), "✅ Списание применено для игрока " + telegramId + ".", mainMenuKeyboard(user));
        } catch (IllegalArgumentException exception) {
            sendAdminDebitUsersPage(user, session, currentDebitPage(session),
                    "⚠️ " + escape(exception.getMessage()));
        }
    }

    private void handleBroadcast(AppUser user, UserSession session, String text) {
        int delivered = broadcastToAll("📣 <b>Новости платформы</b>\n\n" + escape(text));
        session.reset();
        sendText(user.getTelegramId(), "✅ Рассылка отправлена. Получателей: <b>" + delivered + "</b>.", mainMenuKeyboard(user));
    }

    private void handleBroadcastPhoto(AppUser user, UserSession session, String fileId, String caption) {
        String html = caption.isBlank() ? "" : "📣 <b>Новости платформы</b>\n\n" + escape(caption);
        int delivered = broadcastPhotoToAll(fileId, html);
        session.reset();
        sendText(user.getTelegramId(), "✅ Рассылка с фото отправлена. Получателей: <b>" + delivered + "</b>.", mainMenuKeyboard(user));
    }

    private int broadcastPhotoToAll(String fileId, String caption) {
        int delivered = 0;
        for (AppUser player : userService.allRegisteredUsers()) {
            try {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(player.getTelegramId().toString());
                photo.setPhoto(new InputFile(fileId));
                if (!caption.isBlank()) {
                    photo.setCaption(caption);
                    photo.setParseMode("HTML");
                }
                execute(photo);
                delivered++;
            } catch (Exception e) {
                log.warn("Failed to broadcast photo to {}", player.getTelegramId(), e);
            }
        }
        return delivered;
    }

    @org.springframework.context.event.EventListener
    public void onLeagueReward(LeagueRewardEvent event) {
        String msg = "🏆 <b>Итоги недели — " + escape(event.getLeagueName()) + "</b>\n\n"
                + "Ты набрал <b>" + event.getWeeklyXp() + " XP</b> за эту неделю.\n\n"
                + "🪙 Призовые: <b>+" + event.getExcPrize() + " EXC</b> начислены на баланс!\n\n"
                + "Новая неделя — новые квесты. Борись за более высокую лигу! 💪";
        sendText(event.getTelegramId(), msg, null);
    }

    @org.springframework.context.event.EventListener
    public void onHallOfFame(ru.gamebot.platform.event.HallOfFameEvent event) {
        String[] medals = {"🥇", "🥈", "🥉"};
        StringBuilder sb = new StringBuilder();
        sb.append("🏆 <b>Зал славы EGC — итоги недели</b>\n\n");
        for (ru.gamebot.platform.event.HallOfFameEvent.HallEntry entry : event.getTop3()) {
            int rank = entry.rank();
            String medal = rank <= 3 ? medals[rank - 1] : rank + ".";
            sb.append(medal).append(" <b>").append(escape(entry.nickname())).append("</b>");
            if (entry.username() != null) sb.append(" (@").append(entry.username()).append(")");
            sb.append("\n   ⚡ ").append(entry.weeklyXp()).append(" XP за неделю\n\n");
        }
        sb.append("Поздравляем лучших игроков! 💪\n")
          .append("Новая неделя — новые квесты. Присоединяйся → @").append(getBotUsername());
        try {
            SendMessage msg = new SendMessage();
            msg.setChatId(requiredChannelChatId());
            msg.setText(sb.toString());
            msg.setParseMode("HTML");
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to post hall of fame to channel", e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onNewsPublished(NewsPublishedEvent event) {
        String message = "📰 <b>" + escape(event.getTitle()) + "</b>\n\n" + event.getBody();
        int delivered = broadcastToAll(message);
        log.info("[News] Broadcast '{}' → {} users", event.getTitle(), delivered);
    }

    public void requestNewsApproval(String title, String body) {
        pendingNewsQueue.add(new String[]{title, body});
        drainNewsQueue();
    }

    private void drainNewsQueue() {
        for (Long adminId : adminService.allAdminIds()) {
            UserSession adminSession = sessionService.get(adminId);
            if (adminSession.getState() == SessionState.NEWS_APPROVAL) {
                return;
            }
        }
        String[] next = pendingNewsQueue.poll();
        if (next == null) return;
        String title = next[0];
        String body = next[1];
        String preview = "📰 <b>Новость на одобрение:</b>\n\n"
                + "<b>" + escape(title) + "</b>\n\n" + body
                + "\n\n<i>Будет опубликована и разослана всем пользователям.</i>";
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(
                        keyboardFactory.callback("✅ Опубликовать", "news:approve"),
                        keyboardFactory.callback("❌ Отменить", "news:reject")
                )
        ));
        for (Long adminId : adminService.allAdminIds()) {
            UserSession adminSession = sessionService.get(adminId);
            adminSession.setState(SessionState.NEWS_APPROVAL);
            adminSession.getData().put("pending_news_title", title);
            adminSession.getData().put("pending_news_body", body);
            sendText(adminId, preview, markup);
        }
    }

    private int broadcastToAll(String html) {
        int delivered = 0;
        for (AppUser player : userService.allRegisteredUsers()) {
            try {
                sendText(player.getTelegramId(), html, singleMenuKeyboard());
                delivered++;
            } catch (Exception exception) {
                log.warn("Failed to broadcast to {}", player.getTelegramId(), exception);
            }
        }
        return delivered;
    }

    private void handlePayoutPoolInput(AppUser user, UserSession session, String text) {
        if (!isEffectiveAdmin(user)) {
            sendText(user.getTelegramId(), "⛔ Доступ запрещён.", mainMenuKeyboard(user));
            return;
        }
        Long amount = parsePositiveLong(text.trim());
        if (amount == null || amount < 1) {
            sendText(user.getTelegramId(), "⚠️ Введите корректную сумму в рублях (целое число, больше 0).", cancelKeyboard());
            return;
        }
        healthRatioService.addToPayoutPool(amount, user.getTelegramId());
        double ratio = healthRatioService.getCurrentRatio();
        int ratioPercent = (int) Math.round(ratio * 100);
        session.reset();
        sendText(user.getTelegramId(),
                "✅ Payout Pool пополнен на <b>" + amount + " ₽</b>.\n\n"
                        + "📊 Новый Состояние фонда: <b>" + ratioPercent + "%</b>",
                mainMenuKeyboard(user));
    }

    private void handleWithdrawalInput(AppUser user, UserSession session, String text) {
        long amount;
        try {
            amount = Long.parseLong(text.trim().replace(" ", ""));
        } catch (NumberFormatException e) {
            sendText(user.getTelegramId(), "⚠️ Введите сумму числом, например: <b>5000</b>", cancelKeyboard());
            return;
        }
        if (amount < 5000) {
            sendText(user.getTelegramId(), "⚠️ Минимальная сумма вывода — <b>5 000 EXC</b>.", cancelKeyboard());
            return;
        }
        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);
        if (amount > remaining) {
            sendText(user.getTelegramId(), "⚠️ Превышен месячный лимит. Доступно: <b>" + remaining + " EXC</b>.", cancelKeyboard());
            return;
        }
        if (amount > user.getCoins()) {
            sendText(user.getTelegramId(), "⚠️ Недостаточно EXC. Баланс: <b>" + user.getCoins() + " EXC</b>.", cancelKeyboard());
            return;
        }
        double ratio = healthRatioService.getCurrentRatio();
        long rubles = Math.round(amount * ratio / 100.0);
        session.getData().put("withdrawAmount", String.valueOf(amount));
        session.getData().put("withdrawRubles", String.valueOf(rubles));
        session.setState(SessionState.WITHDRAWAL_DETAILS);
        sendText(user.getTelegramId(),
                "💳 <b>Введите реквизиты для перевода</b>\n\n"
                        + "💸 Сумма: <b>" + amount + " EXC → ~" + rubles + " ₽</b>\n\n"
                        + "Укажите <b>банк</b> и <b>номер телефона</b>.\n\n"
                        + "Пример:\n<code>Сбербанк, СБП +7 900 123 45 67</code>\n\n"
                        + "<i>*на текущий момент переводы осуществляются только по СБП, учитывайте это при создании заявки!</i>",
                cancelKeyboard());
    }

    private void handleWithdrawalDetails(AppUser user, UserSession session, String text) {
        String details = text.trim();
        if (details.length() < 6) {
            sendText(user.getTelegramId(), "⚠️ Реквизиты слишком короткие. Введите номер карты или телефон:", cancelKeyboard());
            return;
        }
        if (rewardService.hasWithdrawalTodayOrPending(user)) {
            session.reset();
            sendText(user.getTelegramId(),
                "⚠️ <b>Лимит: 1 заявка на вывод в сутки.</b>\n\n"
                    + "Следующую заявку можно создать через 24 часа после предыдущей.",
                backMenuKeyboard("menu:main"));
            return;
        }
        long amount = Long.parseLong(session.getData().get("withdrawAmount"));
        long rubles = Long.parseLong(session.getData().get("withdrawRubles"));
        try {
            RewardRequest withdrawalReq = rewardService.createWithdrawalRequestWithDetails(user, amount, rubles, details);
            session.reset();
            sendText(user.getTelegramId(),
                "✅ <b>Заявка на вывод принята!</b>\n\n"
                    + "🔢 Номер заявки: <b>В-" + withdrawalReq.getId() + "</b>\n"
                    + "💸 Сумма: <b>" + amount + " EXC</b>\n"
                    + "💵 К выплате: <b>~" + rubles + " ₽</b>\n\n"
                    + "Ожидайте, в течение 24 часов администратор выполнит перевод!",
                backMenuKeyboard("menu:main"));
            notifyAdminsAboutWithdrawal(user, withdrawalReq);
        } catch (IllegalArgumentException e) {
            sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), cancelKeyboard());
        }
    }

    private static final List<String> QUEST_PLATFORMS = List.of("PC", "Console", "Mobile");

    private void sendQuestCategoryKeyboard(AppUser user) {
        sendText(user.getTelegramId(),
                "📚 Выберите категорию сложности квеста:",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("🟢 Легкие", "qc:cat:Легкие")),
                        List.of(keyboardFactory.callback("🟡 Средние", "qc:cat:Средние")),
                        List.of(keyboardFactory.callback("🔴 Сложные", "qc:cat:Сложные")),
                        List.of(keyboardFactory.callback("❌ Отмена", "admin:cancel"))
                )));
    }

    private void sendQuestPlatformKeyboard(AppUser user, UserSession session) {
        List<String> selected = List.of(session.getData().getOrDefault("platforms_selected", "").split(","))
                .stream().filter(s -> !s.isBlank()).toList();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String p : QUEST_PLATFORMS) {
            boolean active = selected.contains(p);
            rows.add(List.of(keyboardFactory.callback((active ? "✅ " : "") + p, "qc:plat:" + p)));
        }
        rows.add(List.of(
                keyboardFactory.callback("✔️ Готово", "qc:plat:done"),
                keyboardFactory.callback("❌ Отмена", "admin:cancel")
        ));
        String selectedText = selected.isEmpty() ? "не выбрано" : String.join(", ", selected);
        sendText(user.getTelegramId(),
                "🕹️ Выберите платформы (можно несколько):\nВыбрано: <b>" + escape(selectedText) + "</b>",
                keyboardFactory.rowsLayout(rows));
    }

    private void handleQuestCreateCallback(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if (action.startsWith("cat:")) {
            if (session.getState() != SessionState.QUEST_CREATE_CATEGORY) {
                answerSilently(callbackQuery.getId());
                return;
            }
            String category = action.substring("cat:".length());
            session.getData().put("category", category);
            session.setState(SessionState.QUEST_CREATE_PLATFORM);
            sendQuestPlatformKeyboard(user, session);
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("plat:")) {
            if (session.getState() != SessionState.QUEST_CREATE_PLATFORM) {
                answerSilently(callbackQuery.getId());
                return;
            }
            String value = action.substring("plat:".length());
            if ("done".equals(value)) {
                String selected = session.getData().getOrDefault("platforms_selected", "");
                if (selected.isBlank()) {
                    answer(callbackQuery.getId(), "Выберите хотя бы одну платформу");
                    return;
                }
                session.getData().put("platform", selected);
                session.getData().remove("platforms_selected");
                session.setState(SessionState.QUEST_CREATE_DURATION);
                sendText(user.getTelegramId(), "⏳ Укажите срок выполнения, например: 1-3 дня.", cancelKeyboard());
            } else {
                List<String> current = new ArrayList<>(
                        Arrays.stream(session.getData().getOrDefault("platforms_selected", "").split(","))
                                .filter(s -> !s.isBlank()).toList());
                if (current.contains(value)) {
                    current.remove(value);
                } else {
                    current.add(value);
                }
                session.getData().put("platforms_selected", String.join(",", current));
                sendQuestPlatformKeyboard(user, session);
            }
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("photo:skip".equals(action)) {
            if (session.getState() != SessionState.QUEST_CREATE_PHOTO) {
                answerSilently(callbackQuery.getId());
                return;
            }
            showQuestPreview(user, session);
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("preview:publish".equals(action)) {
            if (session.getState() != SessionState.QUEST_CREATE_COUNCIL) {
                answerSilently(callbackQuery.getId());
                return;
            }
            boolean councilOnly = "true".equals(session.getData().get("councilOnly"));
            finalizeQuestCreation(user, session, councilOnly);
            answer(callbackQuery.getId(), "Квест опубликован");
            return;
        }
        if ("preview:edit".equals(action)) {
            session.setState(SessionState.QUEST_CREATE_TITLE);
            session.getData().clear();
            sendText(user.getTelegramId(),
                    "✏️ Начнём сначала. Отправьте новое название квеста.",
                    cancelKeyboard());
            answerSilently(callbackQuery.getId());
            return;
        }
        answerSilently(callbackQuery.getId());
    }

    private void showQuestPreview(AppUser user, UserSession session) {
        session.setState(SessionState.QUEST_CREATE_COUNCIL);
        Map<String, String> d = session.getData();
        String text = "👁 <b>Превью квеста</b>\n\n"
                + "🎯 <b>" + escape(d.getOrDefault("title", "—")) + "</b>\n\n"
                + "🎮 Игра: <b>" + escape(d.getOrDefault("game", "—")) + "</b>\n"
                + "📚 Формат: <b>" + escape(d.getOrDefault("category", "—")) + "</b>\n"
                + "🕹️ Платформа: <b>" + escape(d.getOrDefault("platform", "—")) + "</b>\n"
                + "⏳ Темп: <b>" + escape(d.getOrDefault("duration", "—")) + "</b>\n"
                + "👥 Лимит: <b>" + d.getOrDefault("limit", "—") + "</b>\n\n"
                + "🏆 <b>Награда</b>\n"
                + "✨ +" + d.getOrDefault("xp", "0") + " XP\n"
                + "🪙 +" + d.getOrDefault("coins", "0") + " монет\n\n"
                + "📝 <b>Описание</b>\n" + escape(d.getOrDefault("description", "—")) + "\n\n"
                + "📎 <b>Инструкция</b>\n" + escape(d.getOrDefault("instruction", "—")) + "\n\n"
                + "✅ <b>Требования</b>\n" + escape(d.getOrDefault("requirements", "—")) + "\n\n"
                + "Выберите тип и опубликуйте квест:";

        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🌐 Опубликовать (обычный)", "quest_type:public")),
                List.of(keyboardFactory.callback("🛡️ Опубликовать (Council)", "quest_type:council")),
                List.of(keyboardFactory.callback("✏️ Начать заново", "qc:preview:edit"))
        ));

        String photoFileId = d.get("photoFileId");
        if (photoFileId != null) {
            sendPhotoCaption(user.getTelegramId(), photoFileId, text, keyboard);
        } else {
            sendText(user.getTelegramId(), text, keyboard);
        }
    }

    private void handleQuestEditCallback(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if (action.startsWith("cat:")) {
            if (session.getState() != SessionState.QUEST_EDIT_CATEGORY || session.getQuestId() == null) {
                answerSilently(callbackQuery.getId());
                return;
            }
            String category = action.substring("cat:".length());
            Quest q = questService.getQuest(session.getQuestId());
            q.setCategory(category);
            questService.save(q);
            int extendedTo = questService.ensureDurationCoversCategory(q, category);
            session.reset();
            String extendNote = extendedTo > 0
                    ? "\n\n⏳ Срок квеста автоматически продлён до <b>" + extendedTo + " дн.</b> — иначе дедлайн наступал бы раньше, чем снимается кулдаун сдачи отчёта для этой категории. Активные заявки игроков тоже продлены."
                    : "";
            sendText(user.getTelegramId(), "✅ Категория обновлена: <b>" + escape(category) + "</b>" + extendNote, mainMenuKeyboard(user));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (action.startsWith("plat:")) {
            if (session.getState() != SessionState.QUEST_EDIT_PLATFORM || session.getQuestId() == null) {
                answerSilently(callbackQuery.getId());
                return;
            }
            String value = action.substring("plat:".length());
            if ("done".equals(value)) {
                String selected = session.getData().getOrDefault("platforms_selected", "");
                if (selected.isBlank()) {
                    answer(callbackQuery.getId(), "Выберите хотя бы одну платформу");
                    return;
                }
                Quest q = questService.getQuest(session.getQuestId());
                q.setPlatform(selected);
                questService.save(q);
                session.reset();
                sendText(user.getTelegramId(), "✅ Платформы обновлены: <b>" + escape(selected) + "</b>", mainMenuKeyboard(user));
            } else {
                List<String> current = new ArrayList<>(
                        Arrays.stream(session.getData().getOrDefault("platforms_selected", "").split(","))
                                .filter(s -> !s.isBlank()).toList());
                if (current.contains(value)) {
                    current.remove(value);
                } else {
                    current.add(value);
                }
                session.getData().put("platforms_selected", String.join(",", current));
                sendQuestPlatformEditKeyboard(user, session);
            }
            answerSilently(callbackQuery.getId());
            return;
        }
        answerSilently(callbackQuery.getId());
    }

    private void sendQuestPlatformEditKeyboard(AppUser user, UserSession session) {
        List<String> selected = Arrays.stream(session.getData().getOrDefault("platforms_selected", "").split(","))
                .filter(s -> !s.isBlank()).toList();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String p : QUEST_PLATFORMS) {
            boolean active = selected.contains(p);
            rows.add(List.of(keyboardFactory.callback((active ? "✅ " : "") + p, "qe:plat:" + p)));
        }
        rows.add(List.of(keyboardFactory.callback("✔️ Сохранить", "qe:plat:done")));
        String selectedText = selected.isEmpty() ? "не выбрано" : String.join(", ", selected);
        sendText(user.getTelegramId(),
                "🕹️ Выберите платформы:\nВыбрано: <b>" + escape(selectedText) + "</b>",
                keyboardFactory.rowsLayout(rows));
    }

    private void finalizeQuestCreation(AppUser user, UserSession session, boolean councilOnly) {
        Quest quest = new Quest();
        quest.setTitle(session.getData().get("title"));
        quest.setDescription(session.getData().get("description"));
        quest.setGameName(session.getData().get("game"));
        quest.setCategory(session.getData().get("category"));
        quest.setPlatform(session.getData().get("platform"));
        quest.setDurationText(session.getData().get("duration"));
        quest.setDurationDays(Integer.parseInt(session.getData().getOrDefault("durationDays", "0")));
        quest.setRewardXp(Long.parseLong(session.getData().get("xp")));
        quest.setRewardCoins(Long.parseLong(session.getData().get("coins")));
        quest.setInstruction(session.getData().get("instruction"));
        quest.setRequirements(session.getData().get("requirements"));
        quest.setParticipantLimit(Integer.parseInt(session.getData().getOrDefault("limit", "100")));
        quest.setCouncilOnly(councilOnly);
        quest.setPhotoFileId(session.getData().get("photoFileId"));

        questService.createQuest(quest);
        session.reset();
        String label = councilOnly ? "Council-квест" : "обычный квест";
        sendText(user.getTelegramId(),
                "✅ Новый " + label + " создан и сразу опубликован.",
                mainMenuKeyboard(user));
    }

    private void updateQuestTitle(AppUser user, UserSession session, String text) {
        Quest quest = questService.getQuest(session.getQuestId());
        quest.setTitle(text.trim());
        questService.save(quest);
        session.reset();
        sendText(user.getTelegramId(), "✅ Название обновлено.", mainMenuKeyboard(user));
    }

    private void updateQuestDescription(AppUser user, UserSession session, String text) {
        Quest quest = questService.getQuest(session.getQuestId());
        quest.setDescription(text.trim());
        questService.save(quest);
        session.reset();
        sendText(user.getTelegramId(), "✅ Описание обновлено.", mainMenuKeyboard(user));
    }

    private void updateQuestReward(AppUser user, UserSession session, String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 2) {
            sendText(user.getTelegramId(), "⚠️ Используйте формат: <code>XP COINS</code>", cancelKeyboard());
            return;
        }
        Long xp = parsePositiveLong(parts[0]);
        Long coins = parsePositiveLong(parts[1]);
        if (xp == null || coins == null) {
            sendText(user.getTelegramId(), "⚠️ XP и COINS должны быть числами.", cancelKeyboard());
            return;
        }
        Quest quest = questService.getQuest(session.getQuestId());
        quest.setRewardXp(xp);
        quest.setRewardCoins(coins);
        questService.save(quest);
        session.reset();
        sendText(user.getTelegramId(), "✅ Награды обновлены.", mainMenuKeyboard(user));
    }

    private void toggleQuestStatus(AppUser user, Long questId) {
        Quest quest = questService.getQuest(questId);
        quest.setActive(!quest.isActive());
        questService.save(quest);
        sendText(user.getTelegramId(),
                "🔁 Статус квеста изменён: теперь он " + (quest.isActive() ? "активен" : "скрыт") + ".",
                backMenuKeyboard(currentAdminQuestBackData(user, quest)));
    }

    private void deleteQuest(AppUser user, Long questId) {
        if (questId == null) {
            sendText(user.getTelegramId(), "⚠️ Квест для удаления не найден.", mainMenuKeyboard(user));
            return;
        }
        Quest quest = questService.getQuest(questId);
        String backData = currentAdminQuestBackData(user, quest);
        long removedSubmissions = questService.deleteQuest(questId);
        sendText(user.getTelegramId(),
                "🗑️ Квест удалён.\n\n"
                        + "🎯 Название: <b>" + escape(quest.getTitle()) + "</b>\n"
                        + "📎 Удалено связанных заявок: <b>" + removedSubmissions + "</b>",
                backMenuKeyboard(backData));
    }

    private void notifyModeratorsAboutSubmission(Long submissionId) {
        QuestSubmission submission = questService.getSubmission(submissionId);
        String caption = "🧾 <b>Заявка К-" + (submission.getDisplayId() != null ? submission.getDisplayId() : submissionId) + " на проверку</b>\n\n"
                + "👤 Игрок: <b>" + escape(submission.getUser().getNickname()) + "</b>\n"
                + "🆔 ID: <b>" + submission.getUser().getTelegramId() + "</b>\n"
                + "🎯 Квест: <b>" + escape(submission.getQuest().getTitle()) + "</b>\n"
                + rewardPreviewLine(submission) + "\n"
                + "📅 Отправлено: <b>" + submission.getCreatedAt().format(DATE_TIME_FORMATTER) + "</b>\n"
                + "💬 Комментарий: " + escape(submission.getUserComment());

        InlineKeyboardMarkup markup = keyboardFactory.smartLayout(List.of(
                keyboardFactory.callback("✅ Одобрить", "mod:ok:" + submissionId),
                keyboardFactory.callback("❌ Отклонить", "mod:no:" + submissionId),
                keyboardFactory.callback("❓ Уточнить", "mod:more:" + submissionId)
        ));

        Set<Long> recipients = new LinkedHashSet<>();
        recipients.add(7833944231L);

        for (Long recipient : recipients) {
            try {
                switch (submission.getMediaType()) {
                    case "photo" -> {
                        SendPhoto sendPhoto = new SendPhoto();
                        sendPhoto.setChatId(recipient.toString());
                        sendPhoto.setPhoto(new InputFile(submission.getMediaFileId()));
                        sendPhoto.setCaption(caption);
                        sendPhoto.setParseMode("HTML");
                        sendPhoto.setReplyMarkup(markup);
                        execute(sendPhoto);
                    }
                    case "video" -> {
                        SendVideo sendVideo = new SendVideo();
                        sendVideo.setChatId(recipient.toString());
                        sendVideo.setVideo(new InputFile(submission.getMediaFileId()));
                        sendVideo.setCaption(caption);
                        sendVideo.setParseMode("HTML");
                        sendVideo.setReplyMarkup(markup);
                        execute(sendVideo);
                    }
                    case "document" -> {
                        SendDocument sendDocument = new SendDocument();
                        sendDocument.setChatId(recipient.toString());
                        sendDocument.setDocument(new InputFile(submission.getMediaFileId()));
                        sendDocument.setCaption(caption);
                        sendDocument.setParseMode("HTML");
                        sendDocument.setReplyMarkup(markup);
                        execute(sendDocument);
                    }
                    default -> sendText(recipient, caption, markup);
                }
            } catch (TelegramApiException exception) {
                log.warn("Failed to notify moderator {}", recipient, exception);
            }
        }
    }

    private void notifyAdminsAboutWithdrawal(AppUser user, RewardRequest req) {
        String username = user.getTelegramUsername();
        String userLink = (username != null && !username.isBlank())
                ? "\n✉️ Написать: <a href=\"https://t.me/" + username + "\">@" + username + "</a>"
                : "\n✉️ Telegram ID: <code>" + user.getTelegramId() + "</code>";
        String details = req.getPayoutDetails() != null ? "\n💎 Детали: <code>" + escape(req.getPayoutDetails()) + "</code>" : "";
        String text = "💸 <b>Новая заявка на вывод EXC</b>\n\n"
                + "👤 Игрок: <b>" + escape(user.getNickname()) + "</b>\n"
                + "🆔 Telegram ID: <b>" + user.getTelegramId() + "</b>"
                + userLink + "\n"
                + "🪙 Сумма: <b>" + req.getRewardItem().getPriceCoins() + " EXC</b>\n"
                + "📦 Тип: <b>" + escape(req.getRewardItem().getTitle()) + "</b>"
                + details;
        Set<Long> adminIds = adminService.allAdminIds();
        for (Long recipientId : adminService.allModeratorIds()) {
            String callbackData = adminIds.contains(recipientId) ? "admin:withdrawals" : "mod:withdrawals";
            InlineKeyboardMarkup markup = keyboardFactory.rowsLayout(List.of(
                    List.of(keyboardFactory.callback("💸 Открыть заявки на вывод", callbackData))
            ));
            sendText(recipientId, text, markup);
        }
    }

    private void handleModWithdrawalAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String data) {
        answerSilently(callbackQuery.getId());
        if (data.equals("mod:withdrawals")) {
            sendModWithdrawals(user);
        } else if (data.startsWith("mod:withdrawal:req:")) {
            long reqId = Long.parseLong(data.substring("mod:withdrawal:req:".length()));
            sendModWithdrawalCard(user, reqId);
        } else if (data.startsWith("mod:withdrawal:approve:skip:")) {
            long reqId = Long.parseLong(data.substring("mod:withdrawal:approve:skip:".length()));
            session.reset();
            RewardRequest req = rewardService.approveRequest(reqId);
            notifyUserWithdrawalApproved(req, null);
            sendText(user.getTelegramId(), "✅ Заявка В-" + reqId + " одобрена.", null);
            sendModWithdrawals(user);
        } else if (data.startsWith("mod:withdrawal:approve:")) {
            long reqId = Long.parseLong(data.substring("mod:withdrawal:approve:".length()));
            session.reset();
            session.setQuestId(reqId);
            session.getData().put("receiptFlow", "mod");
            session.setState(SessionState.WITHDRAWAL_RECEIPT);
            sendText(user.getTelegramId(),
                    "🧾 <b>Загрузите скриншот чека</b>\n\nОтправьте фото подтверждения оплаты — оно будет отправлено пользователю.\n\nИли нажмите «Пропустить» если чек не нужен.",
                    keyboardFactory.rowsLayout(List.of(
                            List.of(keyboardFactory.callback("⏭️ Пропустить", "mod:withdrawal:approve:skip:" + reqId))
                    )));
        } else if (data.startsWith("mod:withdrawal:reject:")) {
            long reqId = Long.parseLong(data.substring("mod:withdrawal:reject:".length()));
            session.reset();
            session.setQuestId(reqId);
            session.getData().put("rejectType", "withdrawal");
            session.getData().put("rejectBack", "mod");
            session.setState(SessionState.REWARD_REJECT_COMMENT);
            sendText(user.getTelegramId(), "✏️ Введите причину отклонения заявки #" + reqId + ":", cancelKeyboard());
        }
    }

    private void sendModWithdrawals(AppUser user) {
        List<RewardRequest> pending = rewardService.findPendingWithdrawals();
        if (pending.isEmpty()) {
            sendText(user.getTelegramId(), "💸 <b>Заявки на вывод EXC</b>\n\nНет новых заявок.",
                    backOnlyKeyboard("menu:moderation"));
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (RewardRequest req : pending) {
            String uname = req.getUser().getTelegramUsername() != null
                    ? "@" + req.getUser().getTelegramUsername()
                    : "#" + req.getUser().getTelegramId();
            String type = (req.getPayoutDetails() != null && req.getPayoutDetails().startsWith("USDT")) ? "💎 USDT" : "💸 ₽";
            rows.add(List.of(keyboardFactory.callback(
                    "В-" + reqDisplayId(req) + " " + uname + " — " + type + " " + req.getRewardItem().getPriceCoins() + " EXC",
                    "mod:withdrawal:req:" + req.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:moderation")));
        sendText(user.getTelegramId(),
                "💸 <b>Заявки на вывод EXC</b>\n\nОжидают обработки: <b>" + pending.size() + "</b>",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendModWithdrawalCard(AppUser user, Long reqId) {
        RewardRequest req = rewardService.getRequest(reqId);
        AppUser requester = req.getUser();
        String unameLink = requester.getTelegramUsername() != null
                ? "<a href=\"https://t.me/" + requester.getTelegramUsername() + "\">@" + requester.getTelegramUsername() + "</a>"
                : "<a href=\"tg://user?id=" + requester.getTelegramId() + "\">" + requester.getTelegramId() + "</a>";
        boolean isUsdt = req.getPayoutDetails() != null && req.getPayoutDetails().startsWith("USDT");
        String detailsLine;
        if (isUsdt) {
            String[] parts = req.getPayoutDetails().split(":");
            String wallet = parts.length > 1 ? parts[1] : req.getPayoutDetails();
            detailsLine = "\n💎 Способ: <b>USDT · TON</b>\n📬 Кошелёк: <code>" + escape(wallet) + "</code>";
        } else if (req.getPayoutDetails() != null) {
            detailsLine = "\n💵 Способ: <b>Рубли (СБП / Сбербанк)</b>\n💳 Реквизиты: <code>" + escape(req.getPayoutDetails()) + "</code>";
        } else {
            detailsLine = "\n💵 Способ: <b>Рубли (СБП / Сбербанк)</b>";
        }
        long rubles = Math.round(req.getRewardItem().getPriceCoins() / 100.0);
        long dupCount = rewardService.countPendingWithdrawalsByUser(requester);
        String dupWarning = dupCount > 1
                ? "\n\n⚠️ <b>ВНИМАНИЕ: у этого пользователя " + dupCount + " активные заявки на вывод!</b> Оплачивайте только эту." : "";
        sendText(user.getTelegramId(),
                "💸 <b>Заявка на вывод В-" + reqDisplayId(req) + "</b>\n\n"
                        + "👤 Игрок: <b>" + escape(requester.getNickname()) + "</b> (" + unameLink + ")\n"
                        + "🆔 Telegram ID: <b>" + requester.getTelegramId() + "</b>\n"
                        + "🪙 Сумма: <b>" + req.getRewardItem().getPriceCoins() + " EXC</b>\n"
                        + "💵 К выплате: <b>~" + rubles + " ₽</b>"
                        + detailsLine + "\n"
                        + "📅 Дата: <b>" + req.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) + "</b>"
                        + dupWarning,
                keyboardFactory.rowsLayout(List.of(
                        List.of(
                                keyboardFactory.callback("✅ Выплачено", "mod:withdrawal:approve:" + req.getId()),
                                keyboardFactory.callback("❌ Отклонить", "mod:withdrawal:reject:" + req.getId())
                        ),
                        List.of(keyboardFactory.callback("⬅️ Назад", "mod:withdrawals"))
                )));
    }

    private void notifyAdminsAboutRewardRequest(AppUser user, RewardItem reward) {
        notifyAdminsAboutRewardRequest(user, reward, null);
    }

    private void notifyAdminsAboutRewardRequest(AppUser user, RewardItem reward, String userGameData) {
        InlineKeyboardMarkup markup = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("📥 Открыть заявки", "admin:reward:requests"))
        ));
        String dataLine = userGameData != null && !userGameData.isBlank()
                ? "\n📋 Данные игрока: <code>" + escape(userGameData) + "</code>"
                : "";
        for (Long adminId : adminService.allAdminIds()) {
            sendText(adminId,
                    "🛍️ <b>Новая заявка на выдачу награды</b>\n\n"
                            + "👤 Игрок: <b>" + escape(user.getNickname()) + "</b>\n"
                            + "🆔 Telegram ID: <b>" + user.getTelegramId() + "</b>\n"
                            + "🎁 Награда: <b>" + escape(reward.getTitle()) + "</b>\n"
                            + "🪙 Стоимость: <b>" + reward.getPriceCoins() + " EXC</b>"
                            + dataLine,
                    markup);
        }
    }

    private void notifyAdminsNewRegistration(AppUser user) {
        long totalUsers = userService.totalRegisteredUsers();
        InlineKeyboardMarkup markup = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("👤 Профиль пользователя", "admin:user:view:" + user.getTelegramId() + ":0"))
        ));
        String text = "🎮 <b>Новая регистрация</b>\n\n"
                + "👤 Никнейм: <b>" + escape(user.getNickname()) + "</b>\n"
                + "🆔 Telegram ID: <b>" + user.getTelegramId() + "</b>\n"
                + "🌍 Страна: <b>" + escape(user.getCountry() != null ? user.getCountry() : "—") + "</b>\n"
                + "🎯 Интересы: <b>" + escape(user.getInterestsCsv() != null ? user.getInterestsCsv() : "—") + "</b>\n"
                + "📊 Всего игроков: <b>" + totalUsers + "</b>";
        for (Long adminId : adminService.allAdminIds()) {
            sendText(adminId, text, markup);
        }
    }

    private void notifyUser(Long telegramId, String text) {
        sendText(telegramId, text, mainMenuButtonsOnly(telegramId));
    }

    private InlineKeyboardMarkup mainMenuButtonsOnly(Long telegramId) {
        AppUser user = userService.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return null;
        }
        return mainMenuKeyboard(user);
    }

    private InlineKeyboardMarkup mainMenuKeyboard(AppUser user) {
        String role = resolveMenuRole(user, sessionService.get(user.getTelegramId()));
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (ROLE_ADMIN.equals(role)) {
            rows.add(List.of(
                    keyboardFactory.callback("👥 Пользователи", "admin:users:0"),
                    keyboardFactory.callback("📊 Статистика", "admin:stats")
            ));
            rows.add(List.of(keyboardFactory.callback("📡 Сейчас на платформе", "admin:live")));
            rows.add(List.of(
                    keyboardFactory.callback("➕ Квест", "admin:create"),
                    keyboardFactory.callback("📋 По шаблону", "admin:template")
            ));
            rows.add(List.of(
                    keyboardFactory.callback("✏️ Квесты", "admin:edit"),
                    keyboardFactory.callback("📈 Топ квестов", "admin:queststats")
            ));
            rows.add(List.of(
                    keyboardFactory.callback("🎁 Бонус", "admin:bonus"),
                    keyboardFactory.callback("➖ Списание", "admin:debit")
            ));
            rows.add(List.of(
                    keyboardFactory.callback("🎁 Магазин наград", "admin:rewards"),
                    keyboardFactory.callback("📣 Рассылка", "admin:broadcast")
            ));
            rows.add(List.of(keyboardFactory.callback("💳 Пополнить Payout Pool", "admin:payout")));
            long pendingWithdrawals = rewardService.findPendingWithdrawals().size();
            String wLabel = pendingWithdrawals > 0
                    ? "💸 Заявки на вывод (" + pendingWithdrawals + ")"
                    : "💸 Заявки на вывод";
            rows.add(List.of(keyboardFactory.callback(wLabel, "admin:withdrawals")));
            rows.add(List.of(keyboardFactory.callback("📈 Трафик", "admin:traffic")));
            rows.add(List.of(keyboardFactory.callback("🗳 Голосования", "admin:polls")));
            rows.add(List.of(keyboardFactory.callback("🏆 Турниры", "admin:tournaments")));
            rows.add(List.of(keyboardFactory.callback("🎫 Battle Pass", "admin:seasons")));
            rows.add(List.of(keyboardFactory.callback("🤝 Спонсорские квесты", "admin:sponsors")));
            return keyboardFactory.rowsLayout(rows);
        }

        if (ROLE_MODER.equals(role)) {
            rows.add(List.of(
                    keyboardFactory.callback("📂 Квесты", "mod:support:quests"),
                    keyboardFactory.callback("🆘 Поддержка", "mod:support:list")
            ));
            long pendingWithdrawalsMod = rewardService.findPendingWithdrawals().size();
            String wLabelMod = pendingWithdrawalsMod > 0
                    ? "💸 Заявки на вывод (" + pendingWithdrawalsMod + ")"
                    : "💸 Заявки на вывод";
            rows.add(List.of(keyboardFactory.callback(wLabelMod, "mod:withdrawals")));
            return keyboardFactory.rowsLayout(rows);
        }

        rows.add(List.of(keyboardFactory.callback("👤 Профиль", "menu:profile")));

        boolean hasTournament = tournamentService.findCurrentForUser().isPresent();
        String questsLabel = hasTournament ? "🎯 Квесты и рейтинг 🔥" : "🎯 Квесты и рейтинг";
        rows.add(List.of(keyboardFactory.callback(questsLabel, "menu:cat:quests")));

        String walletLabel = userService.isDailyBonusAvailable(user) ? "💰 Кошелёк 🔔" : "💰 Кошелёк";
        rows.add(List.of(keyboardFactory.callback(walletLabel, "menu:cat:wallet")));

        rows.add(List.of(keyboardFactory.callback("🛍️ Магазин", "menu:cat:shop")));

        long activePolls = pollService.findActive().size();
        String clubLabel = activePolls > 0 ? "👥 Клуб (" + activePolls + ")" : "👥 Клуб";
        rows.add(List.of(keyboardFactory.callback(clubLabel, "menu:cat:club")));

        rows.add(List.of(keyboardFactory.callback("🆘 Помощь", "menu:cat:help")));
        rows.add(List.of(keyboardFactory.webApp("🌐 Открыть Mini App", "https://experience-gaming-club.pages.dev")));
        return keyboardFactory.rowsLayout(rows);
    }

    private InlineKeyboardMarkup singleMenuKeyboard() {
        return keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
        ));
    }

    private InlineKeyboardMarkup backMenuKeyboard(String backData) {
        return keyboardFactory.rowsLayout(List.of(
                List.of(
                        keyboardFactory.callback("⬅️ Назад", backData),
                        keyboardFactory.callback("🏠 Меню", "menu:main")
                )
        ));
    }

    private InlineKeyboardMarkup backOnlyKeyboard(String backData) {
        return keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("⬅️ Назад", backData))
        ));
    }

    private InlineKeyboardMarkup verticalWithBackMenu(List<InlineKeyboardButton> buttons, String backText, String backData) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (InlineKeyboardButton button : buttons) {
            if (button == null) {
                continue;
            }
            rows.add(List.of(button));
        }
        rows.add(List.of(
                keyboardFactory.callback(backText, backData),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));
        return keyboardFactory.rowsLayout(rows);
    }

    private InlineKeyboardMarkup cancelKeyboard() {
        return keyboardFactory.smartLayout(List.of(keyboardFactory.callback("❌ Отмена", "common:cancel")));
    }

    private InlineKeyboardMarkup selectionKeyboard(Map<String, String> options, List<String> selected,
                                                   String prefix, boolean withDone, boolean withSkip, boolean withCancel) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            boolean isSelected = selected.contains(entry.getValue());
            String text = (isSelected ? "✅ " : "▫️ ") + entry.getValue();
            buttons.add(keyboardFactory.callback(text, prefix + entry.getKey()));
        }
        if (withDone) {
            buttons.add(keyboardFactory.callback("✅ Готово", prefix + "done"));
        }
        if (withSkip) {
            buttons.add(keyboardFactory.callback("⏭️ Пропустить", prefix + "skip"));
        }
        if (withCancel) {
            buttons.add(keyboardFactory.callback("❌ Отмена", "common:cancel"));
        }
        return keyboardFactory.smartLayout(buttons);
    }

    private void editPlatformQuestion(CallbackQuery callbackQuery, UserSession session) {
        List<String> selected = resolveSelections(session, "platforms", PLATFORM_OPTIONS);
        editRegistrationSelectionMessage(
                callbackQuery,
                platformQuestionText(selected),
                selectionKeyboard(PLATFORM_OPTIONS, selected, "reg:platform:", true, false, false)
        );
    }

    private void editInterestQuestion(CallbackQuery callbackQuery, UserSession session) {
        List<String> selected = resolveSelections(session, "interests", INTEREST_OPTIONS);
        editRegistrationSelectionMessage(
                callbackQuery,
                interestQuestionText(selected),
                selectionKeyboard(INTEREST_OPTIONS, selected, "reg:interest:", true, false, false)
        );
    }

    private String platformQuestionText(List<String> selected) {
        return "🎯 Выберите платформы, на которых вам интересны задания.\n\n"
                + "Сейчас выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>";
    }

    private String interestQuestionText(List<String> selected) {
        return "🧠 Выберите игровые интересы.\n\n"
                + "Сейчас выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>";
    }

    private String mainMenuText(AppUser user) {
        String role = resolveMenuRole(user, sessionService.get(user.getTelegramId()));
        if (ROLE_USER.equals(role)) {
            return "Здравствуйте, " + escape(user.getNickname()) + ".\n\n"
                    + "Перед вами игровой центр <b>EXPERIENCE GAMING CLUB.</b>\n\n"
                    + "Здесь вы можете брать задания, накапливать XP, подниматься в рейтинге, приглашать друзей и обменивать монеты на награды.\n\n"
                    + "Активный режим: <b>Игрок.</b>\n\n"
                    + "Выберите нужный раздел ниже и продолжайте прогресс.";
        }
        String title = switch (role) {
            case ROLE_ADMIN -> "🛠️ <b>Административный контур активен</b>";
            case ROLE_MODER -> "🛡️ <b>Пульт модератора</b>";
            default -> "🏠 <b>Главное меню</b>";
        };
        String body = switch (role) {
            case ROLE_ADMIN -> "С возвращением, <b>" + escape(user.getNickname()) + "</b>.";
            case ROLE_MODER -> "Перед вами рабочий контур модерации <b>" + escape(appProperties.getClubName()) + "</b>.\n"
                    + "Здесь собраны очереди проверки квестов и обращения пользователей, чтобы вы могли быстро поддерживать качество сервиса и темп обработки заявок.\n\n"
                    + "Откройте нужную очередь и продолжайте работу.";
            default -> "Перед вами игровой центр <b>" + escape(appProperties.getClubName()) + "</b>.\n"
                    + "Здесь вы можете брать задания, накапливать XP, подниматься в рейтинге, приглашать друзей и обменивать монеты на награды.\n\n"
                    + "Выберите нужный раздел ниже и продолжайте прогресс.";
        };
        if (ROLE_ADMIN.equals(role)) {
            return title + "\n\n" + body;
        }
        return title + "\n\n"
                + "Здравствуйте, <b>" + escape(user.getNickname()) + "</b>.\n"
                + "Активный режим: <b>" + escape(humanRole(role)) + "</b>.\n\n"
                + body;
    }

    private String displayValue(String value, String fallback) {
        if (value == null || value.isBlank() || "Не выбраны".equalsIgnoreCase(value)) {
            return fallback;
        }
        return cleanChoiceDisplay(value);
    }

    private String cleanChoiceDisplay(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replace("📱 Android", "Android")
                .replace("🍎 iPhone", "iPhone")
                .replace("🖥️ PC", "PC")
                .replace("🎮 PS5", "PS5")
                .replace("🕹️ Xbox", "Xbox")
                .replace("🔫 FPS", "FPS")
                .replace("🌍 MMO", "MMO")
                .replace("🧙 RPG", "RPG")
                .replace("♟️ Стратегии", "Стратегии")
                .replace("⚽ Спорт", "Спорт")
                .replace("🎉 Казуальные", "Казуальные");
    }

    private String levelProgressLine(AppUser user) {
        long xp = user.getXp();
        long floor = userService.currentLevelFloor(xp);
        long ceiling = userService.nextLevelCeiling(xp);
        if (ceiling == floor) {
            return "📈 " + xp + "/" + ceiling + " XP";
        }
        long progress = xp - floor;
        long range = ceiling - floor;
        return "📈 <b>" + progress + "/" + range + " XP</b>";
    }

    private String formatExcBonusLine(UserService.RewardGrant rewardGrant) {
        if (rewardGrant.bonusExc() <= 0) {
            return "";
        }
        return "💠 Бонус уровня: <b>+" + rewardGrant.bonusExc() + " EXC (" + rewardGrant.excBonusPercent()
                + "%)</b>\n";
    }

    private String currentQuestBackData(AppUser user) {
        return sessionService.get(user.getTelegramId()).getData().getOrDefault("quest_back_data", "menu:quests");
    }

    private String backDataFromQuestViewToken(String[] parts) {
        if (parts.length == 3) {
            String gameName = decodeGameToken(parts[0]);
            String category = categoryFromToken(parts[1]);
            if (gameName != null) {
                return "quests:list:" + parts[0] + ":" + categoryToken(category);
            }
        }
        String token = parts[0];
        if (token == null || token.isBlank() || "all".equals(token) || "fast".equals(token)
                || "medium".equals(token) || "long".equals(token)) {
            return "menu:quests";
        }
        return "quests:game:" + token;
    }

    private String categoryToken(String category) {
        if (category == null) {
            return "all";
        }
        return switch (category) {
            case "Лёгкие", "Быстрые" -> "fast";
            case "Средние" -> "medium";
            case "Сложные", "Долгие" -> "long";
            default -> "all";
        };
    }

    private String categoryFromToken(String token) {
        return switch (token) {
            case "fast" -> "Лёгкие";
            case "medium" -> "Средние";
            case "long" -> "Сложные";
            default -> null;
        };
    }

    private String encodeGameToken(String gameName) {
        if (gameName == null) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(gameName.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeGameToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void handleAdminQuestListAction(AppUser user, String payload) {
        String[] parts = payload.split(":");
        if (parts.length != 2) {
            sendText(user.getTelegramId(), "⚠️ Список квестов недоступен.", backMenuKeyboard("admin:edit"));
            return;
        }
        String gameName = decodeGameToken(parts[0]);
        String category = categoryFromToken(parts[1]);
        sendAdminQuestListByGame(user, gameName, category);
    }

    private void handleAdminQuestOpen(AppUser user, String payload) {
        String[] parts = payload.split(":");
        if (parts.length == 1) {
            sendAdminQuestEditor(user, parseLong(parts[0]));
            return;
        }
        if (parts.length != 3) {
            sendText(user.getTelegramId(), "⚠️ Карточка квеста недоступна.", backMenuKeyboard("admin:edit"));
            return;
        }
        Long questId = parseLong(parts[2]);
        if (questId == null) {
            sendText(user.getTelegramId(), "⚠️ Карточка квеста недоступна.", backMenuKeyboard("admin:edit"));
            return;
        }
        sendAdminQuestEditor(user, questId, "admin:quests:list:" + parts[0] + ":" + parts[1]);
    }

    private String currentAdminQuestBackData(AppUser user, Quest quest) {
        return sessionService.get(user.getTelegramId()).getData()
                .getOrDefault("admin_quest_back_data", "admin:quests:game:" + encodeGameToken(quest.getGameName()));
    }

    private boolean handleRoleSwitchCommand(AppUser user, UserSession session, String text) {
        if (!canUseManualRoleSwitch(user)) {
            return false;
        }
        return switch (text.toLowerCase()) {
            case "/user" -> {
                session.getData().put("active_role", ROLE_USER);
                sendMainMenu(user, mainMenuText(user));
                yield true;
            }
            case "/moder" -> {
                session.getData().put("active_role", ROLE_MODER);
                sendMainMenu(user, mainMenuText(user));
                yield true;
            }
            case "/admin" -> {
                session.getData().put("active_role", ROLE_ADMIN);
                sendMainMenu(user, mainMenuText(user));
                yield true;
            }
            default -> false;
        };
    }

    private boolean handleClearMeCommand(AppUser user, UserSession session, String text) {
        if (!"/clearme".equalsIgnoreCase(text)) {
            return false;
        }
        if (!isEffectiveAdmin(user)) {
            sendText(user.getTelegramId(), "⛔ Команда доступна только администраторам.", null);
            return true;
        }

        AppUser cleared = userService.clearPersonalProgress(user.getTelegramId());
        session.reset();
        session.setState(SessionState.REG_NAME);
        sendText(cleared.getTelegramId(),
                "♻️ Личный профиль и пользовательский прогресс очищены.\n\n"
                        + "Административный доступ сохранён. Ниже запускаю регистрацию заново, как для первого входа.",
                null);
        sendText(cleared.getTelegramId(),
                "🎮 Добро пожаловать в <b>" + escape(appProperties.getClubName()) + "</b>!\n\n"
                        + "Здесь вас ждут квесты, XP, рейтинг, награды и реферальная программа.\n"
                        + "Начнем с профиля. Напишите ваш игровой никнейм.\n\n"
                        + "<b>ВАЖНО! Ник в боте должен совпадать с ником в игре</b>",
                null);
        return true;
    }

    private boolean isRequiredChannelMember(Long telegramId) {
        try {
            GetChatMember request = new GetChatMember();
            request.setChatId(requiredChannelChatId());
            request.setUserId(telegramId);
            ChatMember member = execute(request);
            String status = member.getStatus();
            return status != null && !"left".equalsIgnoreCase(status) && !"kicked".equalsIgnoreCase(status);
        } catch (TelegramApiException exception) {
            log.warn("Failed to verify channel membership for {}", telegramId, exception);
            return false;
        }
    }

    private String requiredChannelChatId() {
        if (appProperties.getRequiredChannelId() != null && !appProperties.getRequiredChannelId().isBlank()) {
            return appProperties.getRequiredChannelId().trim();
        }
        return appProperties.getRequiredChannelUsername();
    }

    private String requiredChannelLabel() {
        if (appProperties.getRequiredChannelUsername() != null && !appProperties.getRequiredChannelUsername().isBlank()) {
            return appProperties.getRequiredChannelUsername().trim();
        }
        return appProperties.getRequiredChannelTitle();
    }

    private String requiredChannelUrl() {
        if (appProperties.getRequiredChannelUrl() != null && !appProperties.getRequiredChannelUrl().isBlank()) {
            return appProperties.getRequiredChannelUrl().trim();
        }
        String username = appProperties.getRequiredChannelUsername();
        if (username != null && !username.isBlank()) {
            return "https://t.me/" + username.replace("@", "").trim();
        }
        return "https://t.me/exgamingclub";
    }

    private void sendBlockedNotice(AppUser user) {
        String reason = user.getBlockReason();
        sendText(user.getTelegramId(),
                "🚫 <b>Ваш аккаунт заблокирован</b>\n\n"
                        + (reason != null && !reason.isBlank() ? "Причина: <i>" + escape(reason) + "</i>\n\n" : "")
                        + "Если считаете это ошибкой — обратитесь в поддержку клуба.",
                null);
    }

    private boolean isEffectiveModerator(AppUser user) {
        return adminService.isModerator(user.getTelegramId());
    }

    private boolean isEffectiveAdmin(AppUser user) {
        return adminService.isAdmin(user.getTelegramId())
                && ROLE_ADMIN.equals(getActiveRole(sessionService.get(user.getTelegramId())));
    }

    private String getActiveRole(UserSession session) {
        return session.getData().get("active_role");
    }

    private void ensureRoleConsistency(AppUser user, UserSession session) {
        String actualRole = highestAvailableRole(user);
        String activeRole = getActiveRole(session);

        if (ROLE_ADMIN.equals(actualRole)) {
            if (!ROLE_USER.equals(activeRole) && !ROLE_MODER.equals(activeRole) && !ROLE_ADMIN.equals(activeRole)) {
                session.getData().put("active_role", ROLE_ADMIN);
            }
            return;
        }

        if (ROLE_MODER.equals(actualRole)) {
            session.getData().put("active_role", ROLE_MODER);
            return;
        }

        session.getData().put("active_role", ROLE_USER);
    }

    private String resolveMenuRole(AppUser user, UserSession session) {
        ensureRoleConsistency(user, session);
        return session.getData().getOrDefault("active_role", ROLE_USER);
    }

    private String highestAvailableRole(AppUser user) {
        return normalizeRole(adminService.effectiveRole(user));
    }

    private String roleWelcomeText(AppUser user, String streakMessage) {
        if (ROLE_USER.equals(resolveMenuRole(user, sessionService.get(user.getTelegramId())))) {
            return mainMenuText(user);
        }
        String title = switch (resolveMenuRole(user, sessionService.get(user.getTelegramId()))) {
            case ROLE_ADMIN -> "🛠️ <b>Административный контур активен</b>";
            case ROLE_MODER -> "🛡️ <b>Контур модерации активен</b>";
            default -> "🏠 <b>Платформа готова к игре</b>";
        };
        if (ROLE_ADMIN.equals(resolveMenuRole(user, sessionService.get(user.getTelegramId())))) {
            String name = user.getNickname() != null ? escape(user.getNickname()) : "Администратор";
            return title + "\n\nС возвращением, <b>" + name + "</b>.";
        }
        String activity = streakMessage == null
                ? "Все ключевые разделы уже готовы к работе."
                : escape(streakMessage);
        String displayName = user.getNickname() != null ? escape(user.getNickname()) : "Модератор";
        return title + "\n\n"
                + "С возвращением, <b>" + displayName + "</b>.\n"
                + activity + "\n\n"
                + switch (resolveMenuRole(user, sessionService.get(user.getTelegramId()))) {
                    case ROLE_ADMIN -> "Перед вами полный контур управления платформой: пользователи, роли, контент, экономика и коммуникация.";
                    case ROLE_MODER -> "Перед вами служебный контур проверки: очереди квестов, поддержка и ежедневная операционная работа.";
                    default -> "Перед вами игровой контур: квесты, награды, рейтинг, рефералы и рост вашего профиля.";
                };
    }

    private String humanRole(String role) {
        return switch (role) {
            case ROLE_USER -> "Игрок";
            case ROLE_MODER -> "Модератор";
            default -> "Администратор";
        };
    }

    private String normalizedStoredRole(AppUser user) {
        return normalizeRole(user.getStaffRole());
    }

    private String normalizeRole(String role) {
        if (ROLE_ADMIN.equalsIgnoreCase(role)) {
            return ROLE_ADMIN;
        }
        if (ROLE_MODER.equalsIgnoreCase(role)) {
            return ROLE_MODER;
        }
        return ROLE_USER;
    }

    private long reqDisplayId(ru.gamebot.platform.domain.model.RewardRequest req) {
        return req.getDisplayId() != null ? req.getDisplayId() : req.getId();
    }

    private String displayUserName(AppUser user) {
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        if (user.getTelegramFirstName() != null && !user.getTelegramFirstName().isBlank()) {
            return user.getTelegramFirstName();
        }
        return "Игрок";
    }

    private String displayTag(AppUser user) {
        if (user.getTelegramUsername() != null && !user.getTelegramUsername().isBlank()) {
            return "@" + user.getTelegramUsername();
        }
        return "без тега";
    }

    private boolean canUseManualRoleSwitch(AppUser user) {
        return ROLE_ADMIN.equals(adminService.configuredRole(user.getTelegramId()));
    }

    private Integer currentBonusPage(UserSession session) {
        return parseInteger(session.getData().getOrDefault("bonus_page", "0"));
    }

    private Integer currentDebitPage(UserSession session) {
        return parseInteger(session.getData().getOrDefault("debit_page", "0"));
    }

    private Long resolveBonusTarget(String token) {
        Long directValue = parseLong(token);
        if (directValue == null) {
            return null;
        }

        List<AppUser> users = userService.allUsersSorted();
        if (directValue >= 1 && directValue <= users.size()) {
            return users.get((int) (directValue - 1)).getTelegramId();
        }
        return directValue;
    }

    private boolean shouldContinueSupportMediaGroup(Message message, UserSession session) {
        return message.getMediaGroupId() != null
                && session.getSupportTicketId() != null
                && message.getMediaGroupId().equals(session.getData().get("support_media_group_id"));
    }

    private void clearSupportDraft(UserSession session) {
        session.setSupportTicketId(null);
        session.getData().remove("support_media_group_id");
        if (session.getState() == SessionState.SUPPORT_INPUT || session.getState() == SessionState.SUPPORT_REPLY) {
            session.setState(SessionState.NONE);
        }
    }

    private IncomingContent extractIncomingContent(Message message) {
        String mediaType = "text";
        String fileId = null;
        String text = message.getCaption();
        if (message.hasPhoto()) {
            mediaType = "photo";
            List<PhotoSize> photos = message.getPhoto();
            fileId = photos.get(photos.size() - 1).getFileId();
        } else if (message.hasVideo()) {
            mediaType = "video";
            fileId = message.getVideo().getFileId();
        } else if (message.hasDocument()) {
            mediaType = "document";
            fileId = message.getDocument().getFileId();
        } else if (message.hasText()) {
            text = message.getText();
        }
        return new IncomingContent(mediaType, fileId, text == null ? "" : text);
    }

    private void notifyModeratorsAboutSupportTicket(SupportTicket ticket, IncomingContent content, boolean continuation) {
        String caption = (continuation ? "📎 Дополнение к заявке поддержки\n\n" : "🆘 Новая заявка поддержки\n\n")
                + "👤 " + escape(ticket.getUser().getNickname()) + " (" + ticket.getUser().getTelegramId() + ")\n"
                + "🎫 Заявка #" + ticket.getId() + "\n"
                + "💬 " + escape(content.text().isBlank() ? ticket.getInitialMessage() : content.text());

        InlineKeyboardMarkup markup = keyboardFactory.verticalLayout(List.of(
                keyboardFactory.callback("👁️ Открыть", "mod:support:view:" + ticket.getId()),
                keyboardFactory.callback("✍️ Ответить", "mod:support:reply:" + ticket.getId()),
                keyboardFactory.callback("✅ Закрыть", "mod:support:close:" + ticket.getId()),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));

        Set<Long> recipients = new LinkedHashSet<>();
        recipients.addAll(adminService.allModeratorIds());

        for (Long recipient : recipients) {
            try {
                sendContent(recipient, content, caption, markup);
            } catch (Exception exception) {
                log.warn("Failed to notify support moderator {}", recipient, exception);
            }
        }
    }

    private void forwardSupportReply(Long telegramId, IncomingContent content) {
        String caption = "✉️ <b>Ответ поддержки</b>\n\n"
                + (content.text().isBlank() ? "Модератор отправил вам вложение." : escape(content.text()));
        sendContent(telegramId, content, caption, mainMenuButtonsOnly(telegramId));
    }

    private void sendContent(Long chatId, IncomingContent content, String fallbackText, InlineKeyboardMarkup markup) {
        try {
            switch (content.mediaType()) {
                case "photo" -> {
                    SendPhoto sendPhoto = new SendPhoto();
                    sendPhoto.setChatId(chatId.toString());
                    sendPhoto.setPhoto(new InputFile(content.fileId()));
                    sendPhoto.setCaption(fallbackText);
                    sendPhoto.setParseMode("HTML");
                    sendPhoto.setReplyMarkup(markup);
                    execute(sendPhoto);
                }
                case "video" -> {
                    SendVideo sendVideo = new SendVideo();
                    sendVideo.setChatId(chatId.toString());
                    sendVideo.setVideo(new InputFile(content.fileId()));
                    sendVideo.setCaption(fallbackText);
                    sendVideo.setParseMode("HTML");
                    sendVideo.setReplyMarkup(markup);
                    execute(sendVideo);
                }
                case "document" -> {
                    SendDocument sendDocument = new SendDocument();
                    sendDocument.setChatId(chatId.toString());
                    sendDocument.setDocument(new InputFile(content.fileId()));
                    sendDocument.setCaption(fallbackText);
                    sendDocument.setParseMode("HTML");
                    sendDocument.setReplyMarkup(markup);
                    execute(sendDocument);
                }
                default -> sendText(chatId, fallbackText, markup);
            }
        } catch (TelegramApiException exception) {
            throw new IllegalStateException("Failed to send content to " + chatId, exception);
        }
    }

    private String humanSupportStatus(String status) {
        return switch (status) {
            case "OPEN" -> "Открыта";
            case "ANSWERED" -> "Есть ответ";
            case "CLOSED" -> "Закрыта";
            default -> status;
        };
    }

    private void sendCurrentRegistrationStep(AppUser user, UserSession session, String intro) {
        if (intro != null && !intro.isBlank()) {
            sendText(user.getTelegramId(), intro, null);
        }

        SessionState state = session.getState();
        if (state == SessionState.NONE) {
            session.setState(SessionState.REG_NAME);
            state = SessionState.REG_NAME;
        }

        switch (state) {
            case REG_NAME -> sendText(user.getTelegramId(),
                    "🎮 Напишите ваш игровой никнейм, чтобы я создал профиль игрока.",
                    null);
            case REG_AGE -> sendText(user.getTelegramId(),
                    "🧾 Укажите возраст числом. Это нужно для корректной сегментации заданий.",
                    null);
            case REG_COUNTRY -> sendText(user.getTelegramId(),
                    "🌍 Напишите страну, из которой вы играете.",
                    null);
            case REG_PLATFORMS -> sendPlatformQuestion(user, session);
            case REG_INTERESTS -> sendInterestQuestion(user, session);
            default -> sendText(user.getTelegramId(),
                    "🧭 Продолжим заполнение профиля с текущего шага.",
                    null);
        }
    }

    private record IncomingContent(String mediaType, String fileId, String text) {
    }

    private void sendText(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("HTML");
        message.setReplyMarkup(keyboard);
        try {
            execute(message);
        } catch (TelegramApiException exception) {
            throw new IllegalStateException("Failed to send message to " + chatId, exception);
        }
    }

    private void sendPhotoCaption(Long chatId, String photoFileId, String caption, InlineKeyboardMarkup keyboard) {
        try {
            SendPhoto message = new SendPhoto();
            message.setChatId(chatId.toString());
            message.setPhoto(new InputFile(photoFileId));
            message.setCaption(caption);
            message.setParseMode("HTML");
            message.setReplyMarkup(keyboard);
            execute(message);
        } catch (TelegramApiException exception) {
            log.warn("Failed to send photo message to {}", chatId, exception);
            sendText(chatId, caption, keyboard);
        }
    }

    private void answer(String callbackId, String text) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackId);
        answer.setText(text);
        try {
            execute(answer);
        } catch (TelegramApiException exception) {
            log.warn("Failed to answer callback {}", callbackId, exception);
        }
    }

    private void answerSilently(String callbackId) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackId);
        try {
            execute(answer);
        } catch (TelegramApiException exception) {
            log.warn("Failed to answer callback silently {}", callbackId, exception);
        }
    }

    private void clearInlineKeyboard(CallbackQuery callbackQuery) {
        if (callbackQuery.getMessage() == null) {
            return;
        }
        EditMessageReplyMarkup editMessageReplyMarkup = new EditMessageReplyMarkup();
        editMessageReplyMarkup.setChatId(callbackQuery.getMessage().getChatId().toString());
        editMessageReplyMarkup.setMessageId(callbackQuery.getMessage().getMessageId());
        editMessageReplyMarkup.setReplyMarkup(null);
        try {
            execute(editMessageReplyMarkup);
        } catch (TelegramApiException exception) {
            log.warn("Failed to clear inline keyboard for stale callback {}", callbackQuery.getId(), exception);
        }
    }

    private void editRegistrationSelectionMessage(CallbackQuery callbackQuery, String text, InlineKeyboardMarkup keyboard) {
        if (callbackQuery.getMessage() == null) {
            return;
        }
        EditMessageText editMessageText = new EditMessageText();
        editMessageText.setChatId(callbackQuery.getMessage().getChatId().toString());
        editMessageText.setMessageId(callbackQuery.getMessage().getMessageId());
        editMessageText.setText(text);
        editMessageText.setParseMode("HTML");
        editMessageText.setReplyMarkup(keyboard);
        try {
            execute(editMessageText);
        } catch (TelegramApiException exception) {
            log.warn("Failed to edit registration selection message {}", callbackQuery.getId(), exception);
        }
    }

    private List<String> resolveSelections(UserSession session, String key, Map<String, String> source) {
        String raw = session.getData().getOrDefault(key, "");
        if (raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .filter(code -> !code.isBlank())
                .map(source::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private void toggleSelection(UserSession session, String key, String code) {
        Set<String> values = new LinkedHashSet<>();
        String raw = session.getData().getOrDefault(key, "");
        if (!raw.isBlank()) {
            values.addAll(List.of(raw.split(",")));
        }
        if (!values.add(code)) {
            values.remove(code);
        }
        session.getData().put(key, String.join(",", values));
    }

    private String extractUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String parseStartTrafficSource(String text) {
        if (text == null || !text.contains(" ")) return null;
        String payload = text.substring(text.indexOf(' ') + 1).trim();
        if (payload.startsWith("src_")) return payload.substring(4);
        return null;
    }

    private Long parseStartReferral(String text) {
        if (text == null || !text.contains(" ")) {
            return null;
        }
        String payload = text.substring(text.indexOf(' ') + 1).trim();
        if (payload.startsWith("ref_")) {
            payload = payload.substring(4);
        }
        return parseLong(payload);
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private Long parsePositiveLong(String value) {
        Long parsed = parseLong(value);
        return parsed == null || parsed < 0 ? null : parsed;
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private Long extractChatId(Update update) {
        if (update.hasMessage()) {
            return update.getMessage().getChatId();
        }
        if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        return null;
    }

    private String humanStatus(SubmissionStatus status) {
        return switch (status) {
            case DRAFT -> "В процессе";
            case PENDING -> "На проверке";
            case APPROVED -> "Одобрен";
            case REJECTED -> "Отклонён";
            case NEEDS_INFO -> "Нужны уточнения";
            case CANCELLED -> "Отменён";
        };
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
