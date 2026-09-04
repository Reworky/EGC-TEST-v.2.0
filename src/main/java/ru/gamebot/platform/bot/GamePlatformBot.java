package ru.gamebot.platform.bot;

import java.time.LocalDate;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.enums.RejectionReasonCode;
import ru.gamebot.platform.domain.enums.RewardRequestStatus;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.BotReview;
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
import ru.gamebot.platform.service.QuestActionStatus;
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
    private volatile String shopBannerFileId = null;
    private volatile String hallOfFameFileId = null;

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
    private final ru.gamebot.platform.service.SquadService squadService;
    private final ru.gamebot.platform.service.WheelService wheelService;
    private final ru.gamebot.platform.service.PlatformSnapshotService platformSnapshotService;
    private final ru.gamebot.platform.service.ClaudeVisionService claudeVisionService;
    private final ru.gamebot.platform.domain.repository.QuestRepository questRepository;
    private final ru.gamebot.platform.service.BrawlStarsTournamentService brawlStarsTournamentService;
    private final ru.gamebot.platform.service.BrawlQuestVerificationService brawlQuestVerificationService;
    private final ru.gamebot.platform.service.ClashQuestVerificationService clashQuestVerificationService;
    private final ru.gamebot.platform.service.ClashRoyaleQuestVerificationService clashRoyaleQuestVerificationService;
    private final ru.gamebot.platform.service.ScheduledBroadcastService scheduledBroadcastService;
    private final ru.gamebot.platform.service.AdsgramBotAdService adsgramBotAdService;
    private final ru.gamebot.platform.domain.repository.TournamentEntryRepository tournamentEntryRepository;
    private final ru.gamebot.platform.domain.repository.BotReviewRepository botReviewRepository;

    private final Queue<String[]> pendingNewsQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService albumScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> albumTimers = new ConcurrentHashMap<>();
    // telegramId → timestamp of last successful subscription check (ms); re-check after 1 hour
    private final ConcurrentHashMap<Long, Long> subscriptionCheckCache = new ConcurrentHashMap<>();
    private static final long SUBSCRIPTION_CHECK_TTL_MS = 3_600_000L;

    /** Кандидат авто-опроса, ждущий одобрения администратора — только один одновременно
     * (следующий, через ~3 дня, просто перезапишет, если этот не обработали). */
    private record PendingPollCandidate(String question, List<String> options) {}
    private volatile PendingPollCandidate pendingPollCandidate;

    /** Тело тизера отрядов, ждущее одобрения/правки администратора — фиксируется один раз при
     * генерации, чтобы после ✏️ Изменить публиковалось именно то, что видел админ, а не заново
     * запрошенный рейтинг. */
    private volatile String pendingSquadTeaserText;

    /** Тела постов ленты активности (выводы), ждущие одобрения — ключ req.getId(), т.к. одновременно
     * может быть несколько заявок на согласовании (в отличие от тизера/опроса — там один "слот"). */
    private final ConcurrentHashMap<Long, String> pendingWithdrawalTexts = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void registerBot() throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(this);
        log.info("Telegram bot registered: {}", getBotUsername());
        log.info("Resolved admin IDs: {}", adminService.resolvedAdminIds());
        log.info("Resolved moderator IDs: {}", adminService.resolvedModeratorIds());
        setupMenuButton();
        drainNewsQueue();
        notifyAdminsStartup();
    }

    /** Постоянная кнопка "Открыть" рядом с полем ввода — запускает Mini App напрямую, без пункта меню. */
    private void setupMenuButton() {
        try {
            org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo webAppInfo =
                    new org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo();
            webAppInfo.setUrl("https://experience-gaming-club.pages.dev");
            org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp menuButton =
                    org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp.builder()
                            .text("Открыть")
                            .webAppInfo(webAppInfo)
                            .build();
            execute(org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton.builder()
                    .menuButton(menuButton)
                    .build());
            log.info("Chat menu button configured to open Mini App");
        } catch (TelegramApiException e) {
            log.warn("Failed to set chat menu button", e);
        }
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

        if (message.hasContact() && session.getState() == SessionState.AWAITING_PHONE_SHARE) {
            handlePhoneShare(user, session, message.getContact());
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

        if (text != null && text.startsWith("/resendreview") && isEffectiveAdmin(user)) {
            String[] parts = text.trim().split("\\s+");
            if (parts.length == 2) {
                try {
                    long reviewId = Long.parseLong(parts[1]);
                    botReviewRepository.findWithUserById(reviewId).ifPresentOrElse(review -> {
                        sendReviewModerationCard(review);
                        sendText(user.getTelegramId(), "✅ Карточка отзыва #" + reviewId + " отправлена заново.", null);
                    }, () -> sendText(user.getTelegramId(), "❌ Отзыв не найден: " + reviewId, null));
                } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Неверный формат ID", null);
                }
            } else {
                sendText(user.getTelegramId(), "Использование: /resendreview <id отзыва>", null);
            }
            return;
        }

        if (text != null && text.startsWith("/add_sponsor") && isEffectiveAdmin(user)) {
            handleAddSponsor(user, text);
            return;
        }

        if (text != null && text.startsWith("/sponsor_stats") && isEffectiveAdmin(user)) {
            handleSponsorStats(user, text);
            return;
        }

        if (text != null && text.equals("/sponsors_list") && isEffectiveAdmin(user)) {
            handleSponsorsList(user);
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
                            + socialProofLine()
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

        if (session.getState() == SessionState.TOURNAMENT_CREATE_PHOTO) {
            if (message.hasPhoto()) {
                List<PhotoSize> photos = message.getPhoto();
                session.getData().put("tPhotoFileId", photos.get(photos.size() - 1).getFileId());
            }
            finalizeTournamentCreation(user, session);
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
            sendPayoutConfirmedCard(user, req, isModReceiptFlow);
            return;
        }

        if (session.getState() == SessionState.WITHDRAWAL_REVIEW_TEXT) {
            if (text != null && (text.startsWith("/start") || text.equals("/moder") || text.equals("/admin") || text.equals("/user") || text.equals("/menu"))) {
                session.reset();
                sendMainMenu(user, roleWelcomeText(user, null));
                return;
            }
            InlineKeyboardMarkup finishKeyboard = keyboardFactory.rowsLayout(
                    List.of(List.of(keyboardFactory.callback("✅ Готово", "review:finish"))));
            if (message.hasPhoto()) {
                List<PhotoSize> photos = message.getPhoto();
                session.getData().put("reviewPhotoFileId", photos.get(photos.size() - 1).getFileId());
                sendText(user.getTelegramId(), "📎 Скриншот добавлен. Можешь дописать текст или нажать «Готово».", finishKeyboard);
                return;
            }
            if (text != null && !text.isBlank()) {
                session.getData().put("reviewText", text.length() > 500 ? text.substring(0, 500) : text);
                sendText(user.getTelegramId(), "✏️ Текст сохранён. Можешь приложить скриншот или нажать «Готово».", finishKeyboard);
                return;
            }
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

        if (session.getState() == SessionState.GAME_FLAT_XP) {
            Integer flatXp = parseInteger(text.trim());
            if (flatXp == null || flatXp < 0) {
                sendText(user.getTelegramId(), "⚠️ Введите целое неотрицательное число (XP).", cancelKeyboard());
                return;
            }
            session.getData().put("gameFlatXp", flatXp.toString());
            session.setState(SessionState.GAME_FLAT_EXC);
            sendText(user.getTelegramId(), "🪙 Теперь введите EXC за квест:", cancelKeyboard());
            return;
        }

        if (session.getState() == SessionState.GAME_FLAT_EXC) {
            Long flatExc = parsePositiveLong(text.trim());
            if (flatExc == null) {
                sendText(user.getTelegramId(), "⚠️ Введите целое положительное число (EXC).", cancelKeyboard());
                return;
            }
            String gameName = session.getData().get("gameModeName");
            int flatXp = Integer.parseInt(session.getData().getOrDefault("gameFlatXp", "50"));
            gameCatalogService.setDifficultyMode(gameName, "FLAT", flatExc, flatXp);
            int updated = questService.applyFlatRewardsToGame(gameName, flatXp, flatExc);
            session.reset();
            sendText(user.getTelegramId(),
                    "✅ Режим FLAT установлен для игры «" + escape(gameName) + "»\n"
                            + "Награда за квест: <b>" + flatXp + " XP / " + flatExc + " EXC</b>\n"
                            + "Обновлено квестов: <b>" + updated + "</b>", null);
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
            // Привязываем источник только новым пользователям — созданным в этом запросе.
            // Иначе старый игрок, кликнувший ссылку спустя месяц, попадёт в «Регистраций».
            boolean justCreated = user.getCreatedAt() != null
                    && !user.getCreatedAt().isBefore(java.time.LocalDateTime.now().minusSeconds(60));
            if (justCreated && user.getTrafficSourceCode() == null) {
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
                            + socialProofLine()
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

        // Handle squad invite deep link: /start squad_<inviteCode>
        String startPayload = message.getText().contains(" ")
                ? message.getText().substring(message.getText().indexOf(' ') + 1).trim()
                : "";
        if (startPayload.startsWith("squad_") && user.isRegistrationCompleted()) {
            String code = startPayload.substring("squad_".length());
            try {
                ru.gamebot.platform.domain.model.Squad joinedSquad = squadService.joinByInviteCode(user, code);
                sendText(user.getTelegramId(),
                        "⚔️ <b>Вы вступили в отряд «" + escape(joinedSquad.getName()) + "»!</b>\n\n"
                                + "Зарабатывайте XP вместе — топ-отряд получает 10 000 EXC каждую неделю!",
                        backMenuKeyboard("menu:squads"));
                return;
            } catch (Exception e) {
                sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:main"));
                return;
            }
        }

        // Deep link из мини-аппа: "нужен тег Brawl Stars для авто-квеста" → сразу в диалог привязки
        if (startPayload.equals("brawltag") && user.isRegistrationCompleted()) {
            session.reset();
            session.getData().put("brawlLinkPurpose", "profile");
            session.setState(SessionState.BRAWL_TAG_INPUT);
            sendText(user.getTelegramId(), "🏷️ Введите ваш игровой тег Brawl Stars (например: <code>#ABC123</code>):", cancelKeyboard());
            return;
        }
        // Deep link из мини-аппа: та же логика для Clash of Clans / Clash Royale (см. brawltag выше).
        if (startPayload.equals("clashtag") && user.isRegistrationCompleted()) {
            session.reset();
            session.setState(SessionState.CLASH_TAG_INPUT);
            sendText(user.getTelegramId(), "🏷️ Введите ваш игровой тег Clash of Clans (например: <code>#ABC123</code>):", cancelKeyboard());
            return;
        }
        if (startPayload.equals("crtag") && user.isRegistrationCompleted()) {
            session.reset();
            session.setState(SessionState.CR_TAG_INPUT);
            sendText(user.getTelegramId(), "🏷️ Введите ваш игровой тег Clash Royale (например: <code>#ABC123</code>):", cancelKeyboard());
            return;
        }

        // Возобновить незавершённый онбординг
        if (!user.isOnboardingCompleted()) {
            resumeOnboarding(user);
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

        if (data.startsWith("onboarding:")) {
            handleOnboardingCallback(callbackQuery, user, data.substring("onboarding:".length()));
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
        if ("quests:section:gaming".equals(data)) {
            sendGamingQuestGames(user);
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("quests:section:sponsored".equals(data)) {
            sendSponsoredQuestList(user);
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("quests:section:ugc".equals(data)) {
            sendUgcQuestList(user);
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("quests:section:ads".equals(data)) {
            sendAdsList(user);
            answerSilently(callbackQuery.getId());
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
            handleTakeQuest(callbackQuery, user, session, parseLong(data.substring("quest:take:".length())));
            return;
        }
        if (data.startsWith("quest:report:")) {
            handleReportStart(callbackQuery, user, session, parseLong(data.substring("quest:report:".length())));
            return;
        }
        if (data.startsWith("report:submit:")) {
            long submissionId = parseLong(data.substring("report:submit:".length()));
            String photos = session.getData().getOrDefault("report_photos", "");
            String uniqueIds = session.getData().getOrDefault("report_photo_unique_ids", "");
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
            try {
                questService.submitReport(submission, "photo", firstPhoto, uniqueIds.isBlank() ? null : uniqueIds, null, comment);
            } catch (IllegalStateException e) {
                answerSilently(callbackQuery.getId());
                sendText(user.getTelegramId(), reportSubmitErrorMessage(e), backMenuKeyboard("menu:myquests"));
                return;
            }
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
            if (user.getPhoneNumber() == null) {
                session.setState(SessionState.AWAITING_PHONE_SHARE);
                session.getData().put("pendingWithdrawal", "rub");
                sendPhoneShareRequest(user.getTelegramId());
            } else {
                session.setState(SessionState.WITHDRAWAL_INPUT);
                sendWithdrawalScreen(user);
            }
            return;
        }
        if (data.equals("shop:withdraw:ton")) {
            answerSilently(callbackQuery.getId());
            if (user.getPhoneNumber() == null) {
                session.setState(SessionState.AWAITING_PHONE_SHARE);
                session.getData().put("pendingWithdrawal", "ton");
                sendPhoneShareRequest(user.getTelegramId());
            } else {
                sendWithdrawalTonWalletQuestion(user);
            }
            return;
        }
        if (data.equals("shop:withdraw:ton:has_wallet")) {
            answerSilently(callbackQuery.getId());
            session.setState(SessionState.WITHDRAWAL_TON_AMOUNT);
            sendWithdrawalTonAmountScreen(user);
            return;
        }
        if (data.equals("shop:withdraw:ton:no_wallet")) {
            answerSilently(callbackQuery.getId());
            sendWithdrawalTonNoWalletGuide(user);
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
        if (data.startsWith("tournament_create:") && isEffectiveAdmin(user)) {
            if ("tournament_create:photo:skip".equals(data) && session.getState() == SessionState.TOURNAMENT_CREATE_PHOTO) {
                finalizeTournamentCreation(user, session);
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
                if (t.getScoringType() == ru.gamebot.platform.domain.model.Tournament.ScoringType.BRAWL_TROPHIES) {
                    session.reset();
                    session.getData().put("brawlTournamentId", String.valueOf(tid));
                    session.setState(SessionState.BRAWL_TAG_INPUT);
                    sendText(user.getTelegramId(),
                            "🏷️ Введите ваш игровой тег Brawl Stars (например: <code>#ABC123</code>):",
                            cancelKeyboard());
                    answerSilently(callbackQuery.getId());
                    return;
                }
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
        if (data.startsWith("brawl:confirm:")) {
            String confirmSuffix = data.substring("brawl:confirm:".length());
            if ("quest".equals(confirmSuffix) || "profile".equals(confirmSuffix)) {
                String tag = session.getData().get("brawlTag");
                String name = session.getData().get("brawlName");
                String trophiesStr = session.getData().get("brawlTrophies");
                if (tag == null || trophiesStr == null) {
                    answer(callbackQuery.getId(), "❌ Сессия истекла, начните заново.");
                    session.reset();
                    return;
                }
                var playerInfo = new ru.gamebot.platform.service.BrawlStarsApiService.PlayerInfo(tag, name, Integer.parseInt(trophiesStr));
                brawlQuestVerificationService.linkTag(user, playerInfo);
                String pendingQuestIdStr = session.getData().get("brawlPendingQuestId");
                session.reset();
                answer(callbackQuery.getId(), "✅ Тег привязан!");
                if ("quest".equals(confirmSuffix) && pendingQuestIdStr != null) {
                    handleTakeQuest(callbackQuery, user, session, Long.parseLong(pendingQuestIdStr));
                } else {
                    sendText(user.getTelegramId(), "✅ Тег Brawl Stars привязан: " + escape(tag), backMenuKeyboard("menu:profile"));
                }
                return;
            }
            long tid = parseLong(confirmSuffix);
            tournamentService.findById(tid).ifPresentOrElse(t -> {
                String tag = session.getData().get("brawlTag");
                String name = session.getData().get("brawlName");
                String trophiesStr = session.getData().get("brawlTrophies");
                if (tag == null || trophiesStr == null) {
                    answer(callbackQuery.getId(), "❌ Сессия истекла, начните регистрацию заново.");
                    session.reset();
                    return;
                }
                var playerInfo = new ru.gamebot.platform.service.BrawlStarsApiService.PlayerInfo(tag, name, Integer.parseInt(trophiesStr));
                ru.gamebot.platform.service.TournamentService.JoinResult res = brawlStarsTournamentService.confirmAndJoin(user, t, playerInfo);
                session.reset();
                if (res.success()) {
                    answer(callbackQuery.getId(), "✅ Вы зарегистрированы! Взнос списан.");
                    sendTournament(user);
                } else {
                    answer(callbackQuery.getId(), "❌ " + res.error());
                }
            }, () -> answer(callbackQuery.getId(), "❌ Турнир не найден."));
            return;
        }
        if (data.startsWith("brawl:retry:")) {
            String retrySuffix = data.substring("brawl:retry:".length());
            if (!"quest".equals(retrySuffix) && !"profile".equals(retrySuffix)) {
                session.getData().put("brawlTournamentId", retrySuffix);
            }
            session.setState(SessionState.BRAWL_TAG_INPUT);
            sendText(user.getTelegramId(), "🏷️ Введите игровой тег ещё раз:", cancelKeyboard());
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("clash:confirm".equals(data)) {
            String tag = session.getData().get("clashTag");
            String name = session.getData().get("clashName");
            String townHallStr = session.getData().get("clashTownHall");
            if (tag == null || townHallStr == null) {
                answer(callbackQuery.getId(), "❌ Сессия истекла, начните заново.");
                session.reset();
                return;
            }
            // linkTag использует только playerInfo.tag() — остальные поля (attackWins/золото/эликсир) для
            // привязки не нужны, поэтому их не сохраняем в сессии и восстанавливаем нулями.
            var playerInfo = new ru.gamebot.platform.service.ClashOfClansApiService.PlayerInfo(tag, name, Integer.parseInt(townHallStr), 0, 0, 0, 0, 0, 0, 0, 0, 0);
            clashQuestVerificationService.linkTag(user, playerInfo);
            String pendingQuestIdStr = session.getData().get("clashPendingQuestId");
            session.reset();
            answer(callbackQuery.getId(), "✅ Тег привязан!");
            if (pendingQuestIdStr != null) {
                handleTakeQuest(callbackQuery, user, session, Long.parseLong(pendingQuestIdStr));
            } else {
                sendText(user.getTelegramId(), "✅ Тег Clash of Clans привязан: " + escape(tag), backMenuKeyboard("menu:profile"));
            }
            return;
        }
        if ("clash:retry".equals(data)) {
            session.setState(SessionState.CLASH_TAG_INPUT);
            sendText(user.getTelegramId(), "🏷️ Введите игровой тег ещё раз:", cancelKeyboard());
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("cr:confirm".equals(data)) {
            String tag = session.getData().get("crTag");
            String name = session.getData().get("crName");
            String trophiesStr = session.getData().get("crTrophies");
            if (tag == null || trophiesStr == null) {
                answer(callbackQuery.getId(), "❌ Сессия истекла, начните заново.");
                session.reset();
                return;
            }
            // linkTag использует только playerInfo.tag() — остальные поля для привязки не нужны.
            var playerInfo = new ru.gamebot.platform.service.ClashRoyaleApiService.PlayerInfo(tag, name, Integer.parseInt(trophiesStr), 0, 0, 0, 0, 0);
            clashRoyaleQuestVerificationService.linkTag(user, playerInfo);
            String pendingQuestIdStr = session.getData().get("crPendingQuestId");
            session.reset();
            answer(callbackQuery.getId(), "✅ Тег привязан!");
            if (pendingQuestIdStr != null) {
                handleTakeQuest(callbackQuery, user, session, Long.parseLong(pendingQuestIdStr));
            } else {
                sendText(user.getTelegramId(), "✅ Тег Clash Royale привязан: " + escape(tag), backMenuKeyboard("menu:profile"));
            }
            return;
        }
        if ("cr:retry".equals(data)) {
            session.setState(SessionState.CR_TAG_INPUT);
            sendText(user.getTelegramId(), "🏷️ Введите игровой тег ещё раз:", cancelKeyboard());
            answerSilently(callbackQuery.getId());
            return;
        }
        if ("brawl:anomalies".equals(data) && isEffectiveModerator(user)) {
            sendBrawlAnomalies(user.getTelegramId());
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("brawl:anomaly:") && isEffectiveModerator(user)) {
            handleBrawlAnomalyAction(callbackQuery, user, parseLong(data.substring("brawl:anomaly:".length())));
            return;
        }
        if (data.startsWith("brawl:clear_anomaly:") && isEffectiveModerator(user)) {
            long entryId = parseLong(data.substring("brawl:clear_anomaly:".length()));
            brawlStarsTournamentService.clearAnomaly(entryId);
            brawlStarsTournamentService.releaseHeldPayout(entryId);
            sendText(user.getTelegramId(), "✅ Флаг снят.", backOnlyKeyboard("brawl:anomalies"));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("brawl:disqualify:") && isEffectiveModerator(user)) {
            long entryId = parseLong(data.substring("brawl:disqualify:".length()));
            brawlStarsTournamentService.disqualify(entryId);
            sendText(user.getTelegramId(), "🚫 Игрок дисквалифицирован.", backOnlyKeyboard("brawl:anomalies"));
            answerSilently(callbackQuery.getId());
            return;
        }
        if (data.startsWith("tournament:rules:")) {
            long tid = parseLong(data.substring("tournament:rules:".length()));
            sendBrawlTournamentRules(user, tid);
            answerSilently(callbackQuery.getId());
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
        if (data.startsWith("squad:")) {
            handleSquadAction(callbackQuery, user, session, data.substring("squad:".length()));
            return;
        }
        if (data.startsWith("wheel:")) {
            handleWheelAction(callbackQuery, user, data.substring("wheel:".length()));
            return;
        }
        if (data.startsWith("review:")) {
            handleReviewAction(callbackQuery, user, session, data.substring("review:".length()));
            return;
        }
        if (data.startsWith("revmod:") && isEffectiveModerator(user)) {
            handleReviewModAction(callbackQuery, data.substring("revmod:".length()));
            return;
        }
        if (data.startsWith("adminfeed:") && isEffectiveAdmin(user)) {
            handleAdminFeedAction(callbackQuery, user, session, data.substring("adminfeed:".length()));
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
            handleModerationReject(callbackQuery, user, parseLong(data.substring("mod:no:".length())));
            return;
        }
        if (data.startsWith("mod:no-custom:") && isEffectiveModerator(user)) {
            handleModerationRejectCustom(callbackQuery, user, session, parseLong(data.substring("mod:no-custom:".length())));
            return;
        }
        if (data.startsWith("mod:no-back:") && isEffectiveModerator(user)) {
            handleModerationRejectBack(callbackQuery, parseLong(data.substring("mod:no-back:".length())));
            return;
        }
        if (data.startsWith("mod:rejtpl:") && isEffectiveModerator(user)) {
            String[] rejtplParts = data.substring("mod:rejtpl:".length()).split(":", 2);
            handleModerationQuickReject(callbackQuery, user, Integer.parseInt(rejtplParts[0]), parseLong(rejtplParts[1]));
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
        if ("mod:usersearch".equals(data) && isEffectiveModerator(user)) {
            session.reset();
            session.setState(SessionState.MOD_USER_SEARCH);
            answer(callbackQuery.getId(), "Введите TG ID или ник");
            sendText(user.getTelegramId(), "🔍 <b>Поиск игрока</b>\n\nВведите Telegram ID или никнейм:", cancelKeyboard());
            return;
        }
        if (data.startsWith("mod:user:") && isEffectiveModerator(user)) {
            handleModUserAction(user, data.substring("mod:user:".length()));
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
            case "referral-rating" -> sendReferralRanking(user);
            case "referral-friends" -> sendReferralFriendsList(user);
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
            case "rules" -> sendRulesMessage(user, backMenuKeyboard("menu:cat:help"));
            case "quickstart" -> { answerSilently(callbackQuery.getId()); sendQuickStartGuide(user); }
            case "admin" -> sendAdminPanel(user);
            case "moderation" -> sendModerationHub(user);
            case "daily" -> { sendDailyBonus(callbackQuery, user); return; }
            case "watchad" -> { sendWatchAdOffer(callbackQuery, user); return; }
            case "cat:quests" -> sendQuestsCategory(user);
            case "cat:wallet" -> sendWalletCategory(user);
            case "cat:shop" -> sendShopCategory(user);
            case "cat:club" -> sendClubCategory(user);
            case "cat:help" -> sendHelpCategory(user);
            case "squads" -> sendSquadMenu(user);
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
            case "edit" -> sendProfileEdit(user);
            case "brawl_tag" -> {
                session.reset();
                session.getData().put("brawlLinkPurpose", "profile");
                session.setState(SessionState.BRAWL_TAG_INPUT);
                sendText(user.getTelegramId(), "🏷️ Введите ваш игровой тег Brawl Stars (например: <code>#ABC123</code>):", cancelKeyboard());
            }
            case "edit_age" -> {
                session.setState(SessionState.EDIT_AGE);
                String currentAge = user.getAge() != null ? String.valueOf(user.getAge()) : "не указан";
                sendText(user.getTelegramId(),
                        "🎂 <b>Укажи возраст</b>\n\n"
                                + "Текущий: <b>" + currentAge + "</b>\n\n"
                                + "Введи число от 10 до 99:",
                        backOnlyKeyboard("profile:edit"));
            }
            case "edit_country" -> {
                session.setState(SessionState.EDIT_COUNTRY);
                String currentCountry = user.getCountry() != null ? user.getCountry() : "не указана";
                sendText(user.getTelegramId(),
                        "🌍 <b>Укажи страну</b>\n\n"
                                + "Текущая: <b>" + escape(currentCountry) + "</b>\n\n"
                                + "Напиши страну, из которой играешь:",
                        backOnlyKeyboard("profile:edit"));
            }
            case "edit_platforms" -> sendProfilePlatformEdit(user, session);
            case "edit_genres" -> sendProfileGenreEdit(user, session);
            default -> {
                if (action.startsWith("platform:")) {
                    String platformAction = action.substring("platform:".length());
                    if ("done".equals(platformAction)) {
                        List<String> platforms = resolveSelections(session, "prof_platforms", PLATFORM_OPTIONS);
                        if (platforms.isEmpty()) {
                            answer(callbackQuery.getId(), "Выбери хотя бы одну платформу");
                            return;
                        }
                        user.setPlatformsCsv(String.join(", ", platforms));
                        userService.save(user);
                        session.getData().remove("prof_platforms");
                        sendProfileEdit(user);
                    } else {
                        toggleSelection(session, "prof_platforms", platformAction);
                        editProfilePlatformEdit(callbackQuery, session);
                        answer(callbackQuery.getId(), "Выбор обновлён");
                        return;
                    }
                } else if (action.startsWith("genre:")) {
                    String genreAction = action.substring("genre:".length());
                    if ("done".equals(genreAction)) {
                        List<String> genres = resolveSelections(session, "prof_genres", INTEREST_OPTIONS);
                        if (genres.isEmpty()) {
                            answer(callbackQuery.getId(), "Выбери хотя бы один жанр или нажми «Пропустить»");
                            return;
                        }
                        user.setInterestsCsv(String.join(", ", genres));
                        userService.save(user);
                        session.getData().remove("prof_genres");
                        sendProfileEdit(user);
                    } else if ("skip".equals(genreAction)) {
                        session.getData().remove("prof_genres");
                        sendProfileEdit(user);
                    } else {
                        toggleSelection(session, "prof_genres", genreAction);
                        editProfileGenreEdit(callbackQuery, session);
                        answer(callbackQuery.getId(), "Выбор обновлён");
                        return;
                    }
                } else {
                    sendProfile(user);
                }
            }
        }
        answerSilently(callbackQuery.getId());
    }

    private void handleStateInput(AppUser user, UserSession session, String text) {
        switch (session.getState()) {
            case REG_NAME -> {
                String regNick = text.trim();
                if (regNick.length() < 2 || regNick.length() > 32) {
                    sendText(user.getTelegramId(),
                            "⚠️ Никнейм должен быть от 2 до 32 символов. Попробуйте ещё раз:",
                            null);
                    return;
                }
                if (userService.findByNickname(regNick).isPresent()) {
                    sendText(user.getTelegramId(),
                            "⚠️ Никнейм <b>" + escape(regNick) + "</b> уже занят.\n\nПридумайте другой и введите его:",
                            null);
                    return;
                }
                try {
                    AppUser saved = userService.completeRegistration(user, regNick);
                    session.reset();
                    sendCommunityActivationPrompt(saved, null);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    sendText(user.getTelegramId(),
                            "⚠️ Никнейм <b>" + escape(regNick) + "</b> уже занят.\n\nПридумайте другой и введите его:",
                            null);
                }
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
            case EDIT_AGE -> {
                Integer newAge = parseInteger(text.trim());
                if (newAge == null || newAge < 10 || newAge > 99) {
                    sendText(user.getTelegramId(),
                            "⚠️ Возраст должен быть числом от 10 до 99. Попробуй ещё раз:",
                            backOnlyKeyboard("profile:edit"));
                    return;
                }
                user.setAge(newAge);
                userService.save(user);
                session.setState(SessionState.NONE);
                sendProfileEdit(user);
            }
            case EDIT_COUNTRY -> {
                String newCountry = text.trim();
                if (newCountry.isBlank()) {
                    sendText(user.getTelegramId(),
                            "⚠️ Напишите название страны:",
                            backOnlyKeyboard("profile:edit"));
                    return;
                }
                user.setCountry(newCountry);
                userService.save(user);
                session.setState(SessionState.NONE);
                sendProfileEdit(user);
            }
            case BONUS_INPUT -> handleBonusInput(user, session, text);
            case DEBIT_INPUT -> handleDebitInput(user, session, text);
            case BROADCAST_MESSAGE -> handleBroadcast(user, session, text);
            case BROADCAST_SCHEDULE_TIME -> handleBroadcastScheduleTime(user, session, text);
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
            case TRAFFIC_BATCH_COUNT -> {
                Integer count = parseInteger(text.trim());
                if (count == null || count < 1 || count > 50) {
                    sendText(user.getTelegramId(), "❌ Введите целое число от 1 до 50.", cancelKeyboard());
                    return;
                }
                session.getData().put("batchCount", String.valueOf(count));
                sendText(user.getTelegramId(),
                        "📦 Создать <b>" + count + "</b> новых ссылок?",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("✅ Генерировать", "admin:traffic:batch:confirm"),
                                        keyboardFactory.callback("❌ Отмена", "common:cancel"))
                        )));
            }
            case ADMINFEED_EDIT -> {
                String target = session.getData().get("editTarget");
                session.reset();
                if (target == null) return;
                if (target.equals("squad")) {
                    pendingSquadTeaserText = text.trim();
                    sendSquadFeedCard();
                } else if (target.startsWith("withdrawal:")) {
                    long reqId = parseLong(target.substring("withdrawal:".length()));
                    pendingWithdrawalTexts.put(reqId, text.trim());
                    sendWithdrawalFeedCard(reqId);
                } else if (target.equals("poll")) {
                    String[] lines = text.trim().split("\\n");
                    if (lines.length < 3) {
                        sendText(user.getTelegramId(),
                                "❌ Нужна строка вопроса и минимум 2 варианта ответа, каждый с новой строки.",
                                cancelKeyboard());
                        return;
                    }
                    String question = lines[0].trim();
                    List<String> options = java.util.Arrays.stream(lines, 1, Math.min(lines.length, 9))
                            .map(String::trim).toList();
                    pendingPollCandidate = new PendingPollCandidate(question, options);
                    sendPollFeedCard();
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
                session.setState(SessionState.SPONSOR_CREATE_CONTACT);
                sendText(user.getTelegramId(), "📞 Введите контакт менеджера спонсора (например: <code>@manager</code> или email):", cancelKeyboard());
            }
            case SPONSOR_CREATE_CONTACT -> {
                session.getData().put("spContact", text.trim());
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
                String spContact = session.getData().getOrDefault("spContact", "");
                String spCampaign = session.getData().get("spCampaign");
                long paidRub = Long.parseLong(session.getData().get("spPaidRub"));
                long budgetExc = Long.parseLong(session.getData().get("spBudgetExc"));
                long commission = Math.round(paidRub * 0.30);
                long poolFunded = paidRub - commission;
                ru.gamebot.platform.domain.model.Sponsor sp = sponsorService.create(
                        spName, spCampaign, paidRub, budgetExc, startDate, endDate, user.getTelegramId());
                sp.setSponsorContact(spContact);
                sponsorService.save(sp);
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
            case POSTPAY_CREATE_NAME -> {
                session.getData().put("ppName", text.trim());
                session.setState(SessionState.POSTPAY_CREATE_CONTACT);
                sendText(user.getTelegramId(), "📞 Введите контакт менеджера (например: <code>@manager</code>):", cancelKeyboard());
            }
            case POSTPAY_CREATE_CONTACT -> {
                session.getData().put("ppContact", text.trim());
                session.setState(SessionState.POSTPAY_CREATE_DATES);
                sendText(user.getTelegramId(),
                        "📅 Введите даты кампании в формате:\n<code>ДД.ММ.ГГГГ - ДД.ММ.ГГГГ</code>\n\nИли <code>0</code> — без ограничений:",
                        cancelKeyboard());
            }
            case POSTPAY_CREATE_DATES -> {
                java.time.LocalDate startD = null, endD = null;
                if (!"0".equals(text.trim())) {
                    String[] parts2 = text.trim().split(" - ");
                    if (parts2.length != 2) {
                        sendText(user.getTelegramId(), "❌ Неверный формат. Используйте: ДД.ММ.ГГГГ - ДД.ММ.ГГГГ", cancelKeyboard()); return;
                    }
                    try {
                        java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
                        startD = java.time.LocalDate.parse(parts2[0].trim(), df);
                        endD   = java.time.LocalDate.parse(parts2[1].trim(), df);
                    } catch (Exception e) {
                        sendText(user.getTelegramId(), "❌ Неверный формат даты.", cancelKeyboard()); return;
                    }
                }
                String ppName    = session.getData().get("ppName");
                String ppContact = session.getData().getOrDefault("ppContact", "");
                session.reset();
                ru.gamebot.platform.domain.model.Sponsor sp = sponsorService.createSimple(ppName, ppContact, null, startD, endD);
                java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
                String period = sp.getStartDate() != null
                        ? sp.getStartDate().format(fmt) + " — " + sp.getEndDate().minusDays(1).format(fmt)
                        : "без ограничений";
                List<List<InlineKeyboardButton>> ppRows = new ArrayList<>();
                ppRows.add(List.of(keyboardFactory.callback("➕ Создать квест", "admin:postpay:newquest:" + sp.getId())));
                ppRows.add(List.of(
                        keyboardFactory.callback("⬅️ Назад", "admin:postpay"),
                        keyboardFactory.callback("🏠 Меню", "menu:admin")
                ));
                sendText(user.getTelegramId(),
                        "✅ <b>Кампания под отчёт создана!</b>\n\n"
                        + "📋 " + escape(sp.getName()) + "\n"
                        + "📞 " + escape(ppContact) + "\n"
                        + "📅 " + period,
                        keyboardFactory.rowsLayout(ppRows));
            }

            case SPONSOR_QUEST_TITLE -> {
                session.getData().put("sq_title", text.trim());
                session.setState(SessionState.SPONSOR_QUEST_CHANNEL);
                sendText(user.getTelegramId(), "2️⃣ Название канала (например: <code>Подарки</code>):", cancelKeyboard());
            }
            case SPONSOR_QUEST_CHANNEL -> {
                session.getData().put("sq_channel", text.trim());
                session.setState(SessionState.SPONSOR_QUEST_DESCRIPTION);
                sendText(user.getTelegramId(), "3️⃣ Суть задания (что нужно сделать пользователю):", cancelKeyboard());
            }
            case SPONSOR_QUEST_DESCRIPTION -> {
                session.getData().put("sq_desc", text.trim());
                session.setState(SessionState.SPONSOR_QUEST_XP);
                sendText(user.getTelegramId(), "4️⃣ Сколько XP за выполнение квеста? (число)", cancelKeyboard());
            }
            case SPONSOR_QUEST_XP -> {
                try { Long.parseLong(text.trim()); } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Введите число.", cancelKeyboard()); return;
                }
                session.getData().put("sq_xp", text.trim());
                session.setState(SessionState.SPONSOR_QUEST_EXC);
                sendText(user.getTelegramId(), "5️⃣ Сколько EXC за выполнение квеста? (число)", cancelKeyboard());
            }
            case SPONSOR_QUEST_EXC -> {
                try { Long.parseLong(text.trim()); } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Введите число.", cancelKeyboard()); return;
                }
                session.getData().put("sq_exc", text.trim());
                session.setState(SessionState.SPONSOR_QUEST_DURATION);
                sendText(user.getTelegramId(), "6️⃣ Длительность квеста (например: <code>7 дней</code>, <code>30 дней</code>):", cancelKeyboard());
            }
            case SPONSOR_QUEST_DURATION -> {
                session.getData().put("sq_duration", text.trim());
                session.setState(SessionState.SPONSOR_QUEST_NOTE);
                sendText(user.getTelegramId(), "7️⃣ Ссылки на канал (через Enter, если несколько). Или <code>0</code> — без ссылок:", cancelKeyboard());
            }
            case SPONSOR_QUEST_NOTE -> {
                String note = "0".equals(text.trim()) ? "" : text.trim();
                finalizeSponsorQuest(user, session, note);
            }

            case BRAWL_TAG_INPUT -> {
                String purpose = session.getData().getOrDefault("brawlLinkPurpose", "tournament");
                if ("tournament".equals(purpose)) {
                    long tid = Long.parseLong(session.getData().get("brawlTournamentId"));
                    ru.gamebot.platform.domain.model.Tournament t = tournamentService.findById(tid).orElse(null);
                    if (t == null) {
                        session.reset();
                        sendText(user.getTelegramId(), "❌ Турнир не найден.", backMenuKeyboard("menu:tournament"));
                        return;
                    }
                    ru.gamebot.platform.service.BrawlStarsTournamentService.TagLookupResult res =
                            brawlStarsTournamentService.lookupTag(t, text.trim());
                    if (!res.success()) {
                        sendText(user.getTelegramId(), "❌ " + res.error() + "\n\nПопробуйте ещё раз:", cancelKeyboard());
                        return;
                    }
                    session.getData().put("brawlTag", res.playerInfo().tag());
                    session.getData().put("brawlName", res.playerInfo().name());
                    session.getData().put("brawlTrophies", String.valueOf(res.playerInfo().trophies()));
                    session.setState(SessionState.BRAWL_TAG_CONFIRM);
                    sendText(user.getTelegramId(),
                            "🎮 Найден игрок: <b>" + escape(res.playerInfo().name()) + "</b> (" + res.playerInfo().trophies() + " 🏆)\n\nЭто вы?",
                            keyboardFactory.rowsLayout(List.of(
                                    List.of(keyboardFactory.callback("✅ Да, это я", "brawl:confirm:" + tid)),
                                    List.of(keyboardFactory.callback("✏️ Ввести другой тег", "brawl:retry:" + tid)),
                                    List.of(keyboardFactory.callback("❌ Отмена", "menu:tournament"))
                            )));
                } else {
                    ru.gamebot.platform.service.BrawlQuestVerificationService.TagLookupResult res =
                            brawlQuestVerificationService.lookupTag(text.trim());
                    if (!res.success()) {
                        sendText(user.getTelegramId(), "❌ " + res.error() + "\n\nПопробуйте ещё раз:", cancelKeyboard());
                        return;
                    }
                    session.getData().put("brawlTag", res.playerInfo().tag());
                    session.getData().put("brawlName", res.playerInfo().name());
                    session.getData().put("brawlTrophies", String.valueOf(res.playerInfo().trophies()));
                    session.setState(SessionState.BRAWL_TAG_CONFIRM);
                    sendText(user.getTelegramId(),
                            "🎮 Найден игрок: <b>" + escape(res.playerInfo().name()) + "</b> (" + res.playerInfo().trophies() + " 🏆)\n\nЭто вы?",
                            keyboardFactory.rowsLayout(List.of(
                                    List.of(keyboardFactory.callback("✅ Да, это я", "brawl:confirm:" + purpose)),
                                    List.of(keyboardFactory.callback("✏️ Ввести другой тег", "brawl:retry:" + purpose)),
                                    List.of(keyboardFactory.callback("❌ Отмена", "menu:quests"))
                            )));
                }
            }
            case CLASH_TAG_INPUT -> {
                ru.gamebot.platform.service.ClashQuestVerificationService.TagLookupResult res =
                        clashQuestVerificationService.lookupTag(text.trim());
                if (!res.success()) {
                    sendText(user.getTelegramId(), "❌ " + res.error() + "\n\nПопробуйте ещё раз:", cancelKeyboard());
                    return;
                }
                session.getData().put("clashTag", res.playerInfo().tag());
                session.getData().put("clashName", res.playerInfo().name());
                session.getData().put("clashTownHall", String.valueOf(res.playerInfo().townHallLevel()));
                session.setState(SessionState.CLASH_TAG_CONFIRM);
                sendText(user.getTelegramId(),
                        "🎮 Найден игрок: <b>" + escape(res.playerInfo().name()) + "</b> (Ратуша " + res.playerInfo().townHallLevel() + ")\n\nЭто вы?",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("✅ Да, это я", "clash:confirm")),
                                List.of(keyboardFactory.callback("✏️ Ввести другой тег", "clash:retry")),
                                List.of(keyboardFactory.callback("❌ Отмена", "menu:quests"))
                        )));
            }
            case CR_TAG_INPUT -> {
                ru.gamebot.platform.service.ClashRoyaleQuestVerificationService.TagLookupResult res =
                        clashRoyaleQuestVerificationService.lookupTag(text.trim());
                if (!res.success()) {
                    sendText(user.getTelegramId(), "❌ " + res.error() + "\n\nПопробуйте ещё раз:", cancelKeyboard());
                    return;
                }
                session.getData().put("crTag", res.playerInfo().tag());
                session.getData().put("crName", res.playerInfo().name());
                session.getData().put("crTrophies", String.valueOf(res.playerInfo().trophies()));
                session.setState(SessionState.CR_TAG_CONFIRM);
                sendText(user.getTelegramId(),
                        "🎮 Найден игрок: <b>" + escape(res.playerInfo().name()) + "</b> (" + res.playerInfo().trophies() + " 🏆)\n\nЭто вы?",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("✅ Да, это я", "cr:confirm")),
                                List.of(keyboardFactory.callback("✏️ Ввести другой тег", "cr:retry")),
                                List.of(keyboardFactory.callback("❌ Отмена", "menu:quests"))
                        )));
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
                        "🔒 Регистрация откроется сразу после создания турнира.\n\n"
                        + "Введите дату и время, когда регистрация ЗАКРОЕТСЯ и турнир станет активным "
                        + "(формат <code>ДД.ММ.ГГГГ ЧЧ:ММ</code>, время сервера — <b>UTC</b>, это на 3 часа меньше московского):",
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
                        "⏰ Дата и время окончания турнира (формат <code>ДД.ММ.ГГГГ ЧЧ:ММ</code>, время сервера — UTC):",
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
                session.getData().put("tEnd", text.trim());
                session.setState(SessionState.TOURNAMENT_CREATE_MIN_PARTICIPANTS);
                sendText(user.getTelegramId(),
                        "👥 Минимальное число участников для проведения турнира.\n\n"
                        + "Если к моменту закрытия регистрации наберётся меньше — турнир автоматически отменится, "
                        + "а взнос вернётся всем зарегистрированным.\n\n"
                        + "Введите число, или <code>0</code> — без минимума (турнир пройдёт при любом числе участников):",
                        cancelKeyboard());
            }
            case TOURNAMENT_CREATE_MIN_PARTICIPANTS -> {
                Integer minParticipants;
                try {
                    int n = Integer.parseInt(text.trim());
                    if (n < 0) throw new NumberFormatException();
                    minParticipants = n == 0 ? null : n;
                } catch (NumberFormatException e) {
                    sendText(user.getTelegramId(), "❌ Введите целое число ≥ 0.", cancelKeyboard()); return;
                }
                session.getData().put("tMinParticipants", minParticipants != null ? minParticipants.toString() : "");
                session.setState(SessionState.TOURNAMENT_CREATE_PHOTO);
                sendText(user.getTelegramId(),
                        "🖼️ Прикрепите промо-картинку турнира или пропустите шаг.",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("⏭️ Пропустить фото", "tournament_create:photo:skip")),
                                List.of(keyboardFactory.callback("❌ Отмена", "admin:cancel"))
                        )));
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
                String enteredGame = text.trim();
                session.getData().put("game", enteredGame);
                if (gameCatalogService.isFlat(enteredGame)) {
                    session.getData().put("flat", "true");
                    session.getData().put("flatXp", String.valueOf(gameCatalogService.getFlatRewardXp(enteredGame)));
                    session.getData().put("flatExc", String.valueOf(gameCatalogService.getFlatRewardExc(enteredGame)));
                    session.setState(SessionState.QUEST_CREATE_PLATFORM);
                    sendQuestPlatformKeyboard(user, session);
                } else {
                    session.setState(SessionState.QUEST_CREATE_CATEGORY);
                    sendQuestCategoryKeyboard(user);
                }
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
                if ("true".equals(session.getData().get("flat"))) {
                    session.getData().put("xp", session.getData().getOrDefault("flatXp", "50"));
                    session.getData().put("coins", session.getData().getOrDefault("flatExc", "1500"));
                    session.getData().put("tickets", "0");
                    session.setState(SessionState.QUEST_CREATE_INSTRUCTION);
                    sendText(user.getTelegramId(), "📎 Отправьте инструкцию для игрока.", cancelKeyboard());
                } else {
                    session.setState(SessionState.QUEST_CREATE_REWARD_XP);
                    sendText(user.getTelegramId(), "✨ Сколько XP начислять за квест?", cancelKeyboard());
                }
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
                session.setState(SessionState.QUEST_CREATE_TICKETS);
                sendText(user.getTelegramId(),
                        "🎟 Сколько билетов для Колеса фортуны начислять за квест?\n\n"
                                + "Стандарт: Лёгкие — 1, Средние — 2, Сложные — 3\n"
                                + "Введите число (0 — без билетов):",
                        cancelKeyboard());
            }
            case QUEST_CREATE_TICKETS -> {
                Integer tickets = parseInteger(text.trim());
                if (tickets == null || tickets < 0) {
                    sendText(user.getTelegramId(), "⚠️ Укажите целое неотрицательное число (0 — без билетов).", cancelKeyboard());
                    return;
                }
                session.getData().put("tickets", tickets.toString());
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
            case MULTIACC_BLOCK_OTHER_ID -> {
                Long targetId = session.getQuestId();
                String query = text.trim();
                AppUser other = resolveUserBySearch(query);
                if (other == null) {
                    sendText(user.getTelegramId(), "❌ Пользователь «" + escape(query) + "» не найден (ни по TG ID, ни по нику). Введите ещё раз:", cancelKeyboard());
                    return;
                }
                if (other.getTelegramId().equals(targetId)) {
                    sendText(user.getTelegramId(), "⚠️ Это тот же самый аккаунт. Введите ID/ник другого аккаунта:", cancelKeyboard());
                    return;
                }
                session.getData().put("multiaccOtherId", String.valueOf(other.getTelegramId()));
                session.setState(SessionState.MULTIACC_BLOCK_REASON);
                sendText(user.getTelegramId(),
                        "🚫 <b>Блокировка мультиаккаунта</b>\n\n"
                                + "Второй аккаунт: " + escape(displayUserName(other)) + " (ID: " + other.getTelegramId() + ")\n\n"
                                + "Напишите причину блокировки — она будет сохранена и отправлена обоим пользователям:",
                        cancelKeyboard());
            }
            case MULTIACC_BLOCK_REASON -> {
                Long targetId = session.getQuestId();
                Long otherId = Long.valueOf(session.getData().get("multiaccOtherId"));
                int blockPage = parseInteger(session.getData().getOrDefault("blockPage", "0"));
                String reason = text.trim();
                session.reset();
                AppUser target = userService.findByTelegramId(targetId).orElse(null);
                AppUser other = userService.findByTelegramId(otherId).orElse(null);
                if (target == null || other == null) {
                    sendText(user.getTelegramId(), "⚠️ Один из пользователей не найден.", backMenuKeyboard("admin:users:0"));
                    return;
                }
                userService.blockAndConfiscate(targetId, reason);
                userService.blockAndConfiscate(otherId, reason);
                sendText(targetId,
                        "🚫 <b>Ваш аккаунт заблокирован</b>\n\n"
                                + "Причина: <i>" + escape(reason) + "</i>\n\n"
                                + "Если считаете это ошибкой — обратитесь в поддержку клуба.",
                        null);
                sendText(otherId,
                        "🚫 <b>Ваш аккаунт заблокирован</b>\n\n"
                                + "Причина: <i>" + escape(reason) + "</i>\n\n"
                                + "Если считаете это ошибкой — обратитесь в поддержку клуба.",
                        null);
                sendAdminUserCard(user, targetId, blockPage,
                        "✅ Оба аккаунта заблокированы, EXC конфискован: "
                                + escape(displayUserName(target)) + " и " + escape(displayUserName(other)) + ".");
            }
            case QUEST_REJECT_COMMENT -> {
                Long submissionId = session.getQuestId();
                session.reset();
                QuestSubmission submission = questService.rejectSubmission(
                        submissionId, text.trim(), RejectionReasonCode.OTHER, user.getTelegramId());
                notifyUser(submission.getUser().getTelegramId(),
                        "⚠️ Отчёт по квесту <b>" + escape(submission.getQuest().getTitle()) + "</b> отклонён.\n\n"
                                + escape(submission.getModeratorComment()));
                sendText(user.getTelegramId(), "✅ Заявка отклонена, игрок уведомлён с указанной причиной.", null);
                sendModerationQueue(user.getTelegramId());
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
            case QUEST_EDIT_CONDITION -> updateQuestCondition(user, session, text);
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
                String limitBackData = session.getData().getOrDefault("admin_quest_back_data", "admin:edit");
                session.reset();
                returnToQuestEditor(user, q, limitBackData);
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
            case TRANSFER_EXC_RECIPIENT -> handleTransferRecipientInput(user, session, text);
            case TRANSFER_EXC_AMOUNT -> handleTransferAmountInput(user, session, text);
            case WITHDRAWAL_INPUT -> handleWithdrawalInput(user, session, text);
            case WITHDRAWAL_DETAILS -> handleWithdrawalDetails(user, session, text);
            case WITHDRAWAL_TON_AMOUNT -> handleWithdrawalTonAmount(user, session, text);
            case WITHDRAWAL_TON_ADDRESS -> handleWithdrawalTonAddress(user, session, text);
            case SHOP_GAME_DATA_INPUT -> handleShopGameDataInput(user, session, text);
            case ADMIN_USER_SEARCH -> {
                session.reset();
                String query = text.trim();
                AppUser found = resolveUserBySearch(query);
                if (found == null) {
                    sendText(user.getTelegramId(), "❌ Пользователь «" + escape(query) + "» не найден (ни по TG ID, ни по нику).", backMenuKeyboard("admin:users:0"));
                } else {
                    sendAdminUserCard(user, found.getTelegramId(), 0, null);
                }
            }
            case MOD_USER_SEARCH -> {
                session.reset();
                String query = text.trim();
                AppUser found = resolveUserBySearch(query);
                if (found == null) {
                    sendText(user.getTelegramId(), "❌ Пользователь «" + escape(query) + "» не найден (ни по TG ID, ни по нику).", backMenuKeyboard("menu:main"));
                } else {
                    sendModUserCard(user, found.getTelegramId(), null);
                }
            }
            case BONUS_SEARCH -> {
                String query = text.trim();
                AppUser found = resolveUserBySearch(query);
                if (found == null) {
                    session.setState(SessionState.BONUS_SEARCH);
                    sendText(user.getTelegramId(),
                            "❌ Пользователь «" + escape(query) + "» не найден (ни по TG ID, ни по нику). Введите ещё раз:",
                            cancelKeyboard());
                } else {
                    session.setState(SessionState.BONUS_INPUT);
                    sendText(user.getTelegramId(),
                            "✅ Найден: <b>" + escape(displayUserName(found)) + "</b>\n"
                                    + "🏷️ " + escape(displayTag(found)) + " • ID: <code>" + found.getTelegramId() + "</code>\n\n"
                                    + "Отправьте данные одним сообщением.\n"
                                    + "Формат: <code>" + found.getTelegramId() + " XP COINS TICKETS комментарий</code>\n"
                                    + "Пример: <code>" + found.getTelegramId() + " 100 50 3 За активность</code>",
                            cancelKeyboard());
                }
            }
            case SQUAD_CREATE_NAME -> handleSquadCreateNameInput(user, session, text);
            case SQUAD_JOIN_CODE -> handleSquadJoinCodeInput(user, session, text);
            default -> sendText(user.getTelegramId(), "🧭 Я не жду текст на этом шаге. Вернитесь в меню.", mainMenuKeyboard(user));
        }
    }

    private volatile String mainMenuBannerFileId = null;

    private void sendMainMenu(AppUser user, String text) {
        if (ROLE_MODER.equals(resolveMenuRole(user, sessionService.get(user.getTelegramId())))) {
            sendModerationHub(user);
            return;
        }
        if (user.isRegistrationCompleted() && !isEffectiveModerator(user) && !isSubscriptionCacheValid(user.getTelegramId())) {
            if (!isRequiredChannelMember(user.getTelegramId())) {
                sendCommunityActivationPrompt(user,
                        "⚠️ Похоже, вы отписались от канала. Подпишитесь снова, чтобы продолжить.");
                return;
            }
            subscriptionCheckCache.put(user.getTelegramId(), System.currentTimeMillis());
        }
        InlineKeyboardMarkup keyboard = mainMenuKeyboard(user);
        if (text.length() > 1000) {
            sendText(user.getTelegramId(), text, keyboard);
            return;
        }
        if (mainMenuBannerFileId != null) {
            sendPhotoCaption(user.getTelegramId(), mainMenuBannerFileId, text, keyboard);
            return;
        }
        try {
            try (java.io.InputStream is = getClass().getResourceAsStream("/gamecenter.png")) {
                if (is == null) throw new java.io.IOException("gamecenter.png not found");
                byte[] img = is.readAllBytes();
                SendPhoto sendPhoto = new SendPhoto();
                sendPhoto.setChatId(user.getTelegramId().toString());
                sendPhoto.setPhoto(new InputFile(new java.io.ByteArrayInputStream(img), "gamecenter.png"));
                sendPhoto.setCaption(text);
                sendPhoto.setParseMode("HTML");
                sendPhoto.setReplyMarkup(keyboard);
                org.telegram.telegrambots.meta.api.objects.Message sent = execute(sendPhoto);
                if (sent.getPhoto() != null && !sent.getPhoto().isEmpty()) {
                    mainMenuBannerFileId = sent.getPhoto().get(sent.getPhoto().size() - 1).getFileId();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send main menu banner", e);
            sendText(user.getTelegramId(), text, keyboard);
        }
    }

    private void sendMenuCategory(AppUser user, String title, List<List<InlineKeyboardButton>> items) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(items);
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:main")));
        sendText(user.getTelegramId(), title, keyboardFactory.rowsLayout(rows));
    }

    private volatile String questBannerFileId = null;

    private void sendQuestsCategory(AppUser user) {
        boolean hasTournament = tournamentService.findCurrentForUser().isPresent();
        String tournamentLabel = hasTournament ? "⚔️ Турнир 🔥" : "⚔️ Турнир";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(List.of(
                List.of(keyboardFactory.callback("🗺️ Квесты", "menu:quests")),
                List.of(keyboardFactory.callback("🏆 Рейтинг", "menu:rating")),
                List.of(keyboardFactory.callback(tournamentLabel, "menu:tournament")),
                List.of(keyboardFactory.callback("⬅️ Назад", "menu:main"))
        ));
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(rows);
        if (questBannerFileId != null) {
            sendPhotoCaption(user.getTelegramId(), questBannerFileId, "🎯 <b>Квесты и рейтинг</b>", keyboard);
            return;
        }
        try {
            try (java.io.InputStream is = getClass().getResourceAsStream("/quest_banner.png")) {
                if (is == null) throw new java.io.IOException("quest_banner.png not found");
                byte[] img = is.readAllBytes();
                SendPhoto sendPhoto = new SendPhoto();
                sendPhoto.setChatId(user.getTelegramId().toString());
                sendPhoto.setPhoto(new InputFile(new java.io.ByteArrayInputStream(img), "quest_banner.png"));
                sendPhoto.setCaption("🎯 <b>Квесты и рейтинг</b>");
                sendPhoto.setParseMode("HTML");
                sendPhoto.setReplyMarkup(keyboard);
                org.telegram.telegrambots.meta.api.objects.Message sent = execute(sendPhoto);
                if (sent.getPhoto() != null && !sent.getPhoto().isEmpty()) {
                    questBannerFileId = sent.getPhoto().get(sent.getPhoto().size() - 1).getFileId();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send quest banner", e);
            sendMenuCategory(user, "🎯 <b>Квесты и рейтинг</b>", List.of(
                    List.of(keyboardFactory.callback("🗺️ Квесты", "menu:quests")),
                    List.of(keyboardFactory.callback("🏆 Рейтинг", "menu:rating")),
                    List.of(keyboardFactory.callback(tournamentLabel, "menu:tournament"))
            ));
        }
    }

    private volatile String walletBannerFileId = null;

    private void sendWalletCategory(AppUser user) {
        String dailyLabel = userService.isDailyBonusAvailable(user)
                ? "🎁 Забрать ежедневный бонус 🔔"
                : "✅ Бонус за вход получен";
        int adsLeft = userService.getAdRewardsRemainingToday(user);
        String watchAdLabel = adsLeft > 0 ? "🎬 Смотреть рекламу 🔔" : "🎬 Смотреть рекламу";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(List.of(
                List.of(keyboardFactory.callback("💰 Баланс", "menu:balance")),
                List.of(keyboardFactory.callback(dailyLabel, "menu:daily")),
                List.of(keyboardFactory.callback(watchAdLabel, "menu:watchad")),
                List.of(keyboardFactory.callback("⬅️ Назад", "menu:main"))
        ));
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(rows);
        if (walletBannerFileId != null) {
            sendPhotoCaption(user.getTelegramId(), walletBannerFileId, "💰 <b>Кошелёк</b>", keyboard);
            return;
        }
        try {
            try (java.io.InputStream is = getClass().getResourceAsStream("/wallet_banner.png")) {
                if (is == null) throw new java.io.IOException("wallet_banner.png not found");
                byte[] img = is.readAllBytes();
                SendPhoto sendPhoto = new SendPhoto();
                sendPhoto.setChatId(user.getTelegramId().toString());
                sendPhoto.setPhoto(new InputFile(new java.io.ByteArrayInputStream(img), "wallet_banner.png"));
                sendPhoto.setCaption("💰 <b>Кошелёк</b>");
                sendPhoto.setParseMode("HTML");
                sendPhoto.setReplyMarkup(keyboard);
                org.telegram.telegrambots.meta.api.objects.Message sent = execute(sendPhoto);
                if (sent.getPhoto() != null && !sent.getPhoto().isEmpty()) {
                    walletBannerFileId = sent.getPhoto().get(sent.getPhoto().size() - 1).getFileId();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send wallet banner", e);
            sendMenuCategory(user, "💰 <b>Кошелёк</b>", List.of(
                    List.of(keyboardFactory.callback("💰 Баланс", "menu:balance")),
                    List.of(keyboardFactory.callback(dailyLabel, "menu:daily")),
                    List.of(keyboardFactory.callback(watchAdLabel, "menu:watchad"))
            ));
        }
    }

    private void sendShopCategory(AppUser user) {
        boolean hasPass = seasonService.hasActivePass(user);
        boolean hasSeason = seasonService.findCurrentSeason().isPresent();
        String passLabel = hasPass ? "🎫 Battle Pass ✅" : (hasSeason ? "🎫 Battle Pass 🆕" : "🎫 Battle Pass");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(List.of(
                List.of(keyboardFactory.callback("🛍️ Магазин наград", "menu:shop")),
                List.of(keyboardFactory.callback("⚡ Предметы", "menu:sink")),
                List.of(keyboardFactory.callback(passLabel, "menu:battlepass")),
                List.of(keyboardFactory.callback("⬅️ Назад", "menu:main"))
        ));
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(rows);
        if (shopBannerFileId != null) {
            sendPhotoCaption(user.getTelegramId(), shopBannerFileId, "🛍️ <b>Магазин</b>", keyboard);
            return;
        }
        try {
            byte[] img = loadShopBanner();
            SendPhoto sendPhoto = new SendPhoto();
            sendPhoto.setChatId(user.getTelegramId().toString());
            sendPhoto.setPhoto(new InputFile(new java.io.ByteArrayInputStream(img), "shop-banner.jpg"));
            sendPhoto.setCaption("🛍️ <b>Магазин</b>");
            sendPhoto.setParseMode("HTML");
            sendPhoto.setReplyMarkup(keyboard);
            org.telegram.telegrambots.meta.api.objects.Message sent = execute(sendPhoto);
            if (sent.getPhoto() != null && !sent.getPhoto().isEmpty()) {
                shopBannerFileId = sent.getPhoto().get(sent.getPhoto().size() - 1).getFileId();
            }
        } catch (Exception e) {
            log.warn("Failed to send shop banner", e);
            sendMenuCategory(user, "🛍️ <b>Магазин</b>", List.of(
                    List.of(keyboardFactory.callback("🛍️ Магазин наград", "menu:shop")),
                    List.of(keyboardFactory.callback("⚡ Предметы", "menu:sink")),
                    List.of(keyboardFactory.callback(passLabel, "menu:battlepass"))
            ));
        }
    }

    private byte[] loadShopBanner() throws Exception {
        try (java.io.InputStream is = getClass().getResourceAsStream("/shop-banner.jpg")) {
            if (is == null) throw new java.io.IOException("shop-banner.jpg not found in resources");
            return is.readAllBytes();
        }
    }

    private byte[] generateShopBannerPng() throws Exception {
        int W = 800, H = 360;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background gradient
        java.awt.GradientPaint bg = new java.awt.GradientPaint(0, 0, new java.awt.Color(10, 11, 30),
                W, H, new java.awt.Color(8, 9, 22));
        g.setPaint(bg);
        g.fillRoundRect(0, 0, W, H, 30, 30);

        // Dot grid
        g.setColor(new java.awt.Color(168, 85, 247, 18));
        for (int x = 20; x < W; x += 28)
            for (int y = 20; y < H; y += 28)
                g.fillOval(x - 1, y - 1, 3, 3);

        // Purple glow left
        java.awt.RadialGradientPaint glow1 = new java.awt.RadialGradientPaint(
                200, 180, 220,
                new float[]{0f, 0.5f, 1f},
                new java.awt.Color[]{new java.awt.Color(124, 58, 237, 90), new java.awt.Color(124, 58, 237, 30), new java.awt.Color(124, 58, 237, 0)});
        g.setPaint(glow1);
        g.fillRect(0, 0, W, H);

        // Gold glow bottom right
        java.awt.RadialGradientPaint glow2 = new java.awt.RadialGradientPaint(
                650, 320, 180,
                new float[]{0f, 1f},
                new java.awt.Color[]{new java.awt.Color(245, 158, 11, 46), new java.awt.Color(245, 158, 11, 0)});
        g.setPaint(glow2);
        g.fillRect(0, 0, W, H);

        // Glass card for icon
        g.setPaint(new java.awt.Color(255, 255, 255, 13));
        g.fillRoundRect(60, 100, 200, 160, 24, 24);
        g.setColor(new java.awt.Color(168, 85, 247, 90));
        g.setStroke(new java.awt.BasicStroke(1.5f));
        g.drawRoundRect(60, 100, 200, 160, 24, 24);

        // Purple top border on card
        java.awt.GradientPaint cardTop = new java.awt.GradientPaint(60, 100, new java.awt.Color(124, 58, 237, 200), 260, 100, new java.awt.Color(168, 85, 247, 100));
        g.setPaint(cardTop);
        g.setStroke(new java.awt.BasicStroke(2f));
        g.drawLine(84, 100, 236, 100);

        // Shopping bag emoji as large text
        g.setFont(new java.awt.Font("Segoe UI Emoji", java.awt.Font.PLAIN, 72));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String emoji = "🛍️";
        int ew = fm.stringWidth(emoji);
        g.drawString(emoji, 160 - ew / 2, 200);

        // Right side — label
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 11));
        g.setColor(new java.awt.Color(168, 85, 247, 200));
        g.drawString("EXPERIENCE GAMING CLUB", 320, 118);

        // Divider
        java.awt.GradientPaint div = new java.awt.GradientPaint(320, 135, new java.awt.Color(168, 85, 247, 153), 720, 135, new java.awt.Color(168, 85, 247, 0));
        g.setPaint(div);
        g.setStroke(new java.awt.BasicStroke(1f));
        g.drawLine(320, 135, 720, 135);

        // Main title
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 72));
        java.awt.GradientPaint titleGrad = new java.awt.GradientPaint(320, 150, java.awt.Color.WHITE, 640, 230, new java.awt.Color(196, 181, 253));
        g.setPaint(titleGrad);
        g.drawString("Магазин", 320, 220);

        // Subtitle
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 17));
        g.setColor(new java.awt.Color(255, 255, 255, 115));
        g.drawString("Награды · Предметы · Battle Pass", 320, 252);

        // Border
        java.awt.GradientPaint border = new java.awt.GradientPaint(0, 0, new java.awt.Color(168, 85, 247, 100),
                W, H, new java.awt.Color(245, 158, 11, 51));
        g.setPaint(border);
        g.setStroke(new java.awt.BasicStroke(1.5f));
        g.drawRoundRect(1, 1, W - 2, H - 2, 30, 30);

        g.dispose();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private volatile String clubBannerFileId = null;

    private void sendClubCategory(AppUser user) {
        long activePolls = pollService.findActive().size();
        String pollLabel = activePolls > 0 ? "🗳 Голосования (" + activePolls + ")" : "🗳 Голосования";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>(List.of(
                List.of(keyboardFactory.callback("🤝 Рефералы", "menu:referrals")),
                List.of(keyboardFactory.callback("🛡️ EGC Council", "menu:council")),
                List.of(keyboardFactory.callback(pollLabel, "menu:polls")),
                List.of(keyboardFactory.callback("📰 Новости", "menu:news")),
                List.of(keyboardFactory.callback("⬅️ Назад", "menu:main"))
        ));
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(rows);
        if (clubBannerFileId != null) {
            sendPhotoCaption(user.getTelegramId(), clubBannerFileId, "👥 <b>Клуб</b>", keyboard);
            return;
        }
        try {
            try (java.io.InputStream is = getClass().getResourceAsStream("/club_banner.jpg")) {
                if (is == null) throw new java.io.IOException("club_banner.jpg not found");
                byte[] img = is.readAllBytes();
                SendPhoto sendPhoto = new SendPhoto();
                sendPhoto.setChatId(user.getTelegramId().toString());
                sendPhoto.setPhoto(new InputFile(new java.io.ByteArrayInputStream(img), "club_banner.jpg"));
                sendPhoto.setCaption("👥 <b>Клуб</b>");
                sendPhoto.setParseMode("HTML");
                sendPhoto.setReplyMarkup(keyboard);
                org.telegram.telegrambots.meta.api.objects.Message sent = execute(sendPhoto);
                if (sent.getPhoto() != null && !sent.getPhoto().isEmpty()) {
                    clubBannerFileId = sent.getPhoto().get(sent.getPhoto().size() - 1).getFileId();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send club banner", e);
            sendMenuCategory(user, "👥 <b>Клуб</b>", List.of(
                    List.of(keyboardFactory.callback("🤝 Рефералы", "menu:referrals")),
                    List.of(keyboardFactory.callback("🛡️ EGC Council", "menu:council")),
                    List.of(keyboardFactory.callback(pollLabel, "menu:polls")),
                    List.of(keyboardFactory.callback("📰 Новости", "menu:news"))
            ));
        }
    }

    private void sendHelpCategory(AppUser user) {
        sendMenuCategory(user, "🆘 <b>Помощь</b>", List.of(
                List.of(keyboardFactory.callback("📋 Правила клуба", "menu:rules")),
                List.of(keyboardFactory.callback("🆘 Поддержка", "menu:support")),
                List.of(keyboardFactory.url("⭐ Отзывы игроков", "https://t.me/egc_payouts"))
        ));
    }

    private void sendRulesMessage(AppUser user, InlineKeyboardMarkup keyboard) {
        sendText(user.getTelegramId(), rulesText(), keyboard);
    }

    private static String rulesText() {
        return "📋 <b>Правила платформы EGC</b>\n\n"
                + "<b>1. Верификация</b>\n"
                + "Каждый выполненный квест проходит ручную проверку модератором.\n"
                + "• Доказательство — скриншот или видео согласно инструкции квеста\n"
                + "• На скриншоте должен быть виден ник, результат и режим — без исключений\n"
                + "• Срок проверки: до 24 часов\n"
                + "• При отклонении EXC не начисляются, причина указывается в боте\n"
                + "• Повторная подача возможна, если доказательство было неполным\n\n"
                + "<b>2. Лимиты вывода (в месяц)</b>\n"
                + "🟢 Новичок — 10 000 EXC\n"
                + "🔵 Игрок — 25 000 EXC\n"
                + "🟣 Ветеран — 50 000 EXC\n"
                + "🟡 Элита — 80 000 EXC\n"
                + "🔴 Легенда — 100 000 EXC\n"
                + "⚡ Герой и выше — 150 000 EXC\n"
                + "Минимум для вывода: 5 000 EXC. Курс: 100 EXC = 1 ₽ (при HR 100%)\n\n"
                + "<b>3. Антифрод</b>\n"
                + "⏳ После одобрения квеста — 24ч до повтора (для Сложных — 14 дней)\n"
                + "⏳ 24ч кулдаун на квест в той же игре/категории (для Сложных — 14 дней)\n"
                + "⏳ 1ч между взятием любых квестов\n"
                + "📂 Максимум 1 активный квест одновременно\n"
                + "📉 3+ квестов одного типа за неделю → награда −50%\n"
                + "💸 1 заявка на вывод в 24ч, новую нельзя создать пока активна текущая\n\n"
                + "<b>4. Health Ratio</b>\n"
                + "HR — соотношение фонда клуба к балансу игроков.\n"
                + "При HR &lt; 100% курс EXC к рублю снижается пропорционально.\n"
                + "Текущий HR всегда виден в боте перед выводом.\n\n"
                + "<b>5. Реферальная программа</b>\n"
                + "• Тебе: +300 EXC сразу, +3% от EXC друга пока он активен (квест хотя бы раз в 14 дней)\n"
                + "• Другу: +500 EXC сразу, +3 000 EXC после первого квеста\n"
                + "Самореферал и технические аккаунты не засчитываются.\n\n"
                + "<b>6. Блокировка аккаунтов</b>\n"
                + "Пожизненная блокировка без предупреждения:\n"
                + "🚫 <b>Мультиаккаунт</b> — несколько TG-аккаунтов для получения наград. Блокируются все аккаунты, EXC аннулируются, выводы отклоняются. Совпадение реквизитов фиксируется системой автоматически.\n"
                + "🚫 <b>Фальсификация</b> — подделка скриншотов, чужие результаты, накрутка статистики\n\n"
                + "По всем вопросам — @GressToEx";
    }

    private void sendCommunityActivationPrompt(AppUser user, String notice) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.url("📢 Подписаться на канал", requiredChannelUrl())));
        rows.add(List.of(keyboardFactory.callback("✅ Я подписался", "activation:check")));

        String text = (notice == null || notice.isBlank() ? "" : notice + "\n\n")
                + "🔐 <b>Последний шаг!</b>\n\n"
                + "Подпишись на канал <b>" + escape(requiredChannelLabel()) + "</b> и прими правила клуба.\n\n"
                + "Подписавшись, ты автоматически соглашаешься с правилами платформы.\n\n"
                + "Это займёт 10 секунд — и тебе сразу начислится <b>+200 EXC</b>, плюс откроются квесты, награды и рейтинг!";
        sendText(user.getTelegramId(), text, keyboardFactory.rowsLayout(rows));
    }

    private void handleActivationCheck(CallbackQuery callbackQuery, AppUser user) {
        if (isRequiredChannelMember(user.getTelegramId())) {
            activatePlayer(user);
            answer(callbackQuery.getId(), "Аккаунт активирован");
            return;
        }

        sendCommunityActivationPrompt(user,
                "⚠️ Подписка пока не подтверждена. Убедитесь, что вы подписались на канал, и нажмите кнопку ещё раз.");
        answer(callbackQuery.getId(), "Подписка не найдена");
    }

    /** Общая логика активации аккаунта после подтверждённой подписки на канал — используется и
     * при ручном нажатии "Я подписался", и автоматической фоновой проверкой {@link #checkPendingChannelActivations}. */
    private void activatePlayer(AppUser user) {
        subscriptionCheckCache.put(user.getTelegramId(), System.currentTimeMillis());
        if (!user.isRulesAccepted()) {
            user.setRulesAccepted(true);
            userService.save(user);
        }
        AppUser activated = userService.activateAccount(user);
        userService.applyWelcomeBonus(activated);
        ru.gamebot.platform.service.UserService.ReferralActivationResult referral =
                userService.grantReferralReward(activated);
        startOnboarding(activated, referral);
        if (referral != null) {
            sendText(referral.referrerTelegramId(),
                    "🎉 <b>Твой реферал присоединился!</b>\n\n"
                            + "👤 <b>" + escape(referral.invitedNickname()) + "</b> только что активировал аккаунт по твоей ссылке.\n\n"
                            + "🪙 Тебе начислено: <b>+" + referral.referrerBonus() + " EXC</b>\n\n"
                            + "Ты будешь получать <b>3% от EXC</b>, которые он заработает на квестах, пока он активен (выполняет квесты хотя бы раз в 14 дней).",
                    null);
        }
        notifyAdminsNewRegistration(activated);
    }

    /** Игрок ввёл ник, подписался на канал, но не вернулся нажать "Я подписался" — бот сам замечает
     * подписку и активирует аккаунт, без необходимости возвращаться в чат и жать кнопку вручную.
     * Найдено 2026-09-02 при разборе воронки регистрации: ~14% введших ник теряются именно на этом шаге. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000)
    public void checkPendingChannelActivations() {
        for (AppUser user : userService.findPendingChannelActivation()) {
            try {
                if (isRequiredChannelMember(user.getTelegramId())) {
                    activatePlayer(user);
                }
            } catch (Exception e) {
                log.warn("Failed to auto-activate user {} after detecting channel membership", user.getTelegramId(), e);
            }
        }
    }

    // ─── Onboarding ──────────────────────────────────────────────────────────────

    private void startOnboarding(AppUser user, ru.gamebot.platform.service.UserService.ReferralActivationResult referral) {
        user.setOnboardingStep(1);
        user.setOnboardingStartedAt(java.time.LocalDateTime.now());
        user.setOnboardingCompleted(false);
        user.setOnboardingNotificationsSent(0);
        userService.save(user);

        String referralLine = referral != null
                ? "\n🪙 <b>Реферальный бонус: +" + referral.invitedBonus() + " EXC</b> уже на балансе!\n"
                        + "Ещё <b>3 000 EXC</b> придут после первого выполненного квеста.\n"
                : "";

        sendText(user.getTelegramId(),
                "🎮 <b>Добро пожаловать в EGC!</b>\n\n"
                        + "✅ Тебе начислено <b>200 EXC</b> за регистрацию — это твой стартовый капитал.\n"
                        + referralLine,
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("Отлично, что дальше? →", "onboarding:guide"))
                )));
    }

    private void sendOnboardingGuide(AppUser user) {
        sendText(user.getTelegramId(),
                "📖 <b>Как зарабатывать EXC</b>\n\n"
                        + "1️⃣ <b>Возьми квест</b>\n"
                        + "Выбери игру → нажми «Взять квест».\n\n"
                        + "2️⃣ <b>Выполни задание</b>\n"
                        + "Сделай скриншот результата прямо в игре. Вернись в бот → «Мои квесты» → отправь скриншот.\n\n"
                        + "3️⃣ <b>Получи EXC</b>\n"
                        + "Модератор проверит скриншот в течение 24 часов. После одобрения EXC и XP зачислятся автоматически.\n\n"
                        + "4️⃣ <b>Выведи деньги</b>\n"
                        + "Раздел 🛍️ Магазин → Вывод EXC. Минимум <b>5 000 EXC</b> (50 ₽). Курс: <b>100 EXC = 1 ₽</b>.",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("🗺️ Смотреть квесты", "onboarding:browse_all")),
                        List.of(keyboardFactory.callback("👤 В профиль", "onboarding:skip"))
                )));
    }

    private void resumeOnboarding(AppUser user) {
        sendOnboardingGuide(user);
    }

    private void handleOnboardingCallback(CallbackQuery callbackQuery, AppUser user, String sub) {
        if ("guide".equals(sub)) {
            answerSilently(callbackQuery.getId());
            sendOnboardingGuide(user);
        } else if ("browse_all".equals(sub)) {
            completeOnboarding(user);
            answerSilently(callbackQuery.getId());
            sendGamingQuestGames(user);
        } else if ("skip".equals(sub)) {
            completeOnboarding(user);
            answerSilently(callbackQuery.getId());
            sendProfile(user);
        }
    }

    private void completeOnboarding(AppUser user) {
        user.setOnboardingCompleted(true);
        user.setOnboardingCompletedAt(java.time.LocalDateTime.now());
        userService.save(user);
    }

    // ─── End Onboarding ───────────────────────────────────────────────────────────

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
                + "Минимум <b>5 000 EXC</b> (50 ₽). Доступен вывод в рубли или TON.\n"
                + "Курс: <b>100 EXC = 1 ₽</b>.\n\n"
                + "5️⃣ <b>Приглашай друзей</b>\n"
                + "Раздел 🤝 Рефералы → получи ссылку.\n"
                + "Ты будешь получать <b>3% от EXC</b> друга, пока он активен (квест хотя бы раз в 14 дней).\n\n"
                + "❓ Остались вопросы — пиши в 🆘 Поддержку.";
        sendText(user.getTelegramId(), text,
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("🗺️ Перейти к квестам", "menu:quests")),
                        List.of(keyboardFactory.callback("🏠 Главное меню", "menu:main"))
                )));
    }

    private void sendProfile(AppUser user) {
        String achievements = userService.getAchievements(user).isEmpty()
                ? "Пока нет"
                : String.join(", ", userService.getAchievements(user));

        int levelNum = userService.getLevelNumber(user.getXp());
        String levelName = escape(userService.getLevelName(user.getXp()));

        // Бейджи одной строкой
        String badges = "";
        if (councilService.isCouncilMember(user)) badges += "🛡️ EGC Council  ";
        if (seasonService.hasActivePass(user)) badges += "🎫 Battle Pass  ";
        String badgeLine = badges.isEmpty() ? "" : badges.trim() + "\n";

        int excBonus = userService.getExcBonusPercent(user.getXp());
        String league = ru.gamebot.platform.service.UserService.getLeague(user.getWeeklyXp()).displayName;

        String titleLine = user.getProfileTitle() != null ? "🏅 " + escape(user.getProfileTitle()) + "\n" : "";
        String boostNote = sinkShopService.isBoostActive(user) ? " +20% буст" : "";

        // Недельный ранг
        String leagueLine;
        if (user.getWeeklyXp() > 0) {
            long weeklyRank = userService.getWeeklyRankFast(user);
            long weeklyTotal = userService.countActiveThisWeek();
            leagueLine = "🏆 Лига: <b>" + league + "</b> · 📊 Место: <b>#" + weeklyRank + " из " + weeklyTotal + "</b>\n";
        } else {
            leagueLine = "🏆 Лига: <b>" + league + "</b> · Заработай XP, чтобы войти в рейтинг\n";
        }

        boolean profileIncomplete = user.getAge() == null || user.getCountry() == null;
        String incompleteHint = profileIncomplete ? "\n💡 <i>Заполни профиль полностью — укажи возраст и страну</i>\n" : "";

        String profileText = "🎮 <b>" + escape(user.getNickname()) + "</b>\n"
                + badgeLine
                + titleLine
                + "\nУровень " + levelNum + ": <b>" + levelName + "</b>\n"
                + levelProgressBar(user) + "\n\n"
                + "💰 <b>" + String.format("%,d", user.getCoins()).replace(',', ' ') + " EXC</b>"
                + " (+" + excBonus + "% к награде" + boostNote + ")\n"
                + leagueLine + "\n"
                + "📊 <b>Эта неделя</b>\n"
                + "✅ Квестов: <b>" + user.getCompletedQuests() + "</b>"
                + " · 🔥 Серия: <b>" + user.getStreakDays() + " дней</b>"
                + " · 🎟️ Билеты: <b>" + user.getTickets() + "</b>\n\n"
                + "🎮 Платформы: <b>" + escape(displayValue(user.getPlatformsCsv(), "не указаны")) + "</b>\n"
                + "🎯 Жанры: <b>" + escape(displayValue(user.getInterestsCsv(), "не указаны")) + "</b>\n"
                + "🎂 Возраст: <b>" + (user.getAge() != null ? user.getAge() : "не указан") + "</b>\n"
                + "🌍 Страна: <b>" + escape(displayValue(user.getCountry(), "не указана")) + "</b>\n\n"
                + "🏆 Достижения: " + escape(achievements) + "\n"
                + "👥 Приглашено друзей: <b>" + user.getInvitedFriends() + "</b>\n\n"
                + buildGameTagsBlock(user)
                + incompleteHint;

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
                List.of(keyboardFactory.callback("✏️ Редактировать профиль", "profile:edit")),
                List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
        ));

        if (user.getAvatarFileId() != null) {
            sendPhotoCaption(user.getTelegramId(), user.getAvatarFileId(), profileText, profileKeyboard);
        } else {
            sendText(user.getTelegramId(), profileText, profileKeyboard);
        }
    }

    private void sendProfileEdit(AppUser user) {
        String nickname = escape(user.getNickname());
        String age = user.getAge() != null ? String.valueOf(user.getAge()) : "не указан";
        String country = user.getCountry() != null ? escape(user.getCountry()) : "не указана";
        String platforms = user.getPlatformsCsv() != null ? escape(user.getPlatformsCsv()) : "не указаны";
        String genres = user.getInterestsCsv() != null ? escape(user.getInterestsCsv()) : "не указаны";

        sendText(user.getTelegramId(),
                "✏️ <b>Редактировать профиль</b>\n\n"
                        + "👤 Никнейм: <b>" + nickname + "</b>\n"
                        + "🎂 Возраст: <b>" + age + "</b>\n"
                        + "🌍 Страна: <b>" + country + "</b>\n"
                        + "🎮 Платформы: <b>" + platforms + "</b>\n"
                        + "🧩 Жанры: <b>" + genres + "</b>",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("✏️ Изменить никнейм", "profile:nickname")),
                        List.of(keyboardFactory.callback("🎂 " + (user.getAge() != null ? "Изменить возраст" : "Указать возраст"), "profile:edit_age")),
                        List.of(keyboardFactory.callback("🌍 " + (user.getCountry() != null ? "Изменить страну" : "Указать страну"), "profile:edit_country")),
                        List.of(keyboardFactory.callback("🎮 " + (user.getPlatformsCsv() != null ? "Изменить платформы" : "Указать платформы"), "profile:edit_platforms")),
                        List.of(keyboardFactory.callback("🧩 " + (user.getInterestsCsv() != null ? "Изменить жанры" : "Указать жанры"), "profile:edit_genres")),
                        List.of(keyboardFactory.callback("🏷️ " + (user.getBrawlStarsTag() != null ? "Изменить тег Brawl Stars" : "Привязать тег Brawl Stars"), "profile:brawl_tag")),
                        List.of(keyboardFactory.callback("⬅️ Назад", "menu:profile"))
                )));
    }

    private void sendProfilePlatformEdit(AppUser user, UserSession session) {
        loadDisplayCsvToSession(session, "prof_platforms", user.getPlatformsCsv(), PLATFORM_OPTIONS);
        List<String> selected = resolveSelections(session, "prof_platforms", PLATFORM_OPTIONS);
        sendText(user.getTelegramId(),
                "🎮 <b>Платформы</b>\n\nВыбери платформы, на которых играешь:\n\n"
                        + "Выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>",
                profileSelectionKeyboard(PLATFORM_OPTIONS, selected, "profile:platform:", false));
    }

    private void editProfilePlatformEdit(CallbackQuery callbackQuery, UserSession session) {
        List<String> selected = resolveSelections(session, "prof_platforms", PLATFORM_OPTIONS);
        editRegistrationSelectionMessage(callbackQuery,
                "🎮 <b>Платформы</b>\n\nВыбери платформы, на которых играешь:\n\n"
                        + "Выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>",
                profileSelectionKeyboard(PLATFORM_OPTIONS, selected, "profile:platform:", false));
    }

    private void sendProfileGenreEdit(AppUser user, UserSession session) {
        loadDisplayCsvToSession(session, "prof_genres", user.getInterestsCsv(), INTEREST_OPTIONS);
        List<String> selected = resolveSelections(session, "prof_genres", INTEREST_OPTIONS);
        sendText(user.getTelegramId(),
                "🧩 <b>Жанры</b>\n\nВыбери игровые жанры, которые тебе нравятся:\n\n"
                        + "Выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>",
                profileSelectionKeyboard(INTEREST_OPTIONS, selected, "profile:genre:", true));
    }

    private void editProfileGenreEdit(CallbackQuery callbackQuery, UserSession session) {
        List<String> selected = resolveSelections(session, "prof_genres", INTEREST_OPTIONS);
        editRegistrationSelectionMessage(callbackQuery,
                "🧩 <b>Жанры</b>\n\nВыбери игровые жанры, которые тебе нравятся:\n\n"
                        + "Выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>",
                profileSelectionKeyboard(INTEREST_OPTIONS, selected, "profile:genre:", true));
    }

    private InlineKeyboardMarkup profileSelectionKeyboard(Map<String, String> options, List<String> selected,
                                                           String prefix, boolean withSkip) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            boolean isSelected = selected.contains(entry.getValue());
            String label = (isSelected ? "✅ " : "▫️ ") + entry.getValue();
            buttons.add(keyboardFactory.callback(label, prefix + entry.getKey()));
        }
        buttons.add(keyboardFactory.callback("✅ Сохранить", prefix + "done"));
        if (withSkip) {
            buttons.add(keyboardFactory.callback("⏭️ Пропустить", prefix + "skip"));
        }
        buttons.add(keyboardFactory.callback("⬅️ Назад", "profile:edit"));
        return keyboardFactory.smartLayout(buttons);
    }

    private void loadDisplayCsvToSession(UserSession session, String key, String displayCsv,
                                          Map<String, String> options) {
        if (displayCsv == null || displayCsv.isBlank()) {
            session.getData().remove(key);
            return;
        }
        Set<String> displayValues = java.util.Arrays.stream(displayCsv.split(","))
                .map(String::trim).collect(java.util.stream.Collectors.toSet());
        String codes = options.entrySet().stream()
                .filter(e -> displayValues.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.joining(","));
        session.getData().put(key, codes);
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

    private void sendWatchAdOffer(CallbackQuery callbackQuery, AppUser user) {
        int remaining = userService.getAdRewardsRemainingToday(user);
        if (remaining <= 0) {
            answer(callbackQuery.getId(), "На сегодня показы рекламы закончились — приходи завтра!");
            return;
        }
        if (!adsgramBotAdService.isEnabled()) {
            answer(callbackQuery.getId(), "Реклама временно недоступна.");
            return;
        }
        java.util.Optional<ru.gamebot.platform.service.AdsgramBotAdService.AdContent> adOpt =
                adsgramBotAdService.fetchAd(user.getTelegramId());
        if (adOpt.isEmpty()) {
            answer(callbackQuery.getId(), "Сейчас нет доступной рекламы, попробуй чуть позже.");
            return;
        }
        ru.gamebot.platform.service.AdsgramBotAdService.AdContent ad = adOpt.get();
        userService.markAdRequested(user);
        answerSilently(callbackQuery.getId());

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(keyboardFactory.url(ad.buttonName(), ad.clickUrl()));
        if (ad.rewardUrl() != null) {
            buttons.add(keyboardFactory.url(ad.buttonRewardName(), ad.rewardUrl()));
        }
        InlineKeyboardMarkup keyboard = keyboardFactory.smartLayout(buttons);

        try {
            if (ad.imageUrl() != null) {
                SendPhoto sendPhoto = new SendPhoto();
                sendPhoto.setChatId(user.getTelegramId().toString());
                sendPhoto.setPhoto(new InputFile(ad.imageUrl()));
                sendPhoto.setCaption(ad.textHtml());
                sendPhoto.setParseMode("HTML");
                sendPhoto.setProtectContent(true);
                sendPhoto.setReplyMarkup(keyboard);
                execute(sendPhoto);
            } else {
                SendMessage msg = new SendMessage();
                msg.setChatId(user.getTelegramId().toString());
                msg.setText(ad.textHtml());
                msg.setParseMode("HTML");
                msg.setProtectContent(true);
                msg.setReplyMarkup(keyboard);
                execute(msg);
            }
        } catch (Exception e) {
            log.warn("Failed to send AdsGram bot ad to {}", user.getTelegramId(), e);
            sendText(user.getTelegramId(), "⚠️ Не получилось показать рекламу, попробуй ещё раз позже.", backMenuKeyboard("menu:cat:wallet"));
        }
    }

    @org.springframework.context.event.EventListener
    public void onAdRewardGranted(ru.gamebot.platform.event.AdRewardGrantedEvent event) {
        try {
            AppUser player = userService.findById(event.getUserId()).orElse(null);
            if (player == null) return;
            notifyUser(player.getTelegramId(),
                    "✅ <b>+" + event.getExcGranted() + " EXC</b> начислено за просмотр рекламы!");
        } catch (Exception e) {
            log.error("[AdsgramReward] Failed to notify about granted reward, userId={}", event.getUserId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onReferralFriendInactive(ru.gamebot.platform.event.ReferralFriendInactiveEvent event) {
        try {
            notifyUser(event.getReferrerTelegramId(),
                    "😴 <b>" + escape(event.getFriendNickname()) + "</b> давно не выполнял квесты — "
                            + "комиссия с рефералов по этому другу приостановлена.\n\n"
                            + "Напомни другу вернуться к квестам — как только он выполнит хотя бы один, "
                            + "комиссия возобновится автоматически.");
        } catch (Exception e) {
            log.error("[Referral] Failed to notify referrer {} about inactive friend", event.getReferrerTelegramId(), e);
        }
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
                        + "💱 Курс вывода: <b>" + rateString(ratioPercent) + "</b>\n"
                        + "💠 Активный бонус к EXC: <b>+" + userService.getExcBonusPercent(user.getXp()) + "%</b>\n"
                        + "🎟️ Билеты сезона: <b>" + user.getTickets() + "</b>\n"
                        + "✨ Общий XP: <b>" + user.getXp() + "</b>\n"
                        + "📈 XP за неделю: <b>" + user.getWeeklyXp() + "</b>\n\n"
                        + "📊 <b>Состояние фонда клуба: " + ratioPercent + "%</b>\n"
                        + hrExplanationLine(ratioPercent, effectiveQuestReward),
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("💸 Вывести EXC", "shop:withdraw")),
                        List.of(keyboardFactory.callback("⬅️ Назад", backData))
                )));
    }

    private String rateString(int ratioPercent) {
        double rubPer1000 = 10.0 * ratioPercent / 100.0;
        String rubStr = rubPer1000 == Math.floor(rubPer1000)
                ? String.valueOf((long) rubPer1000)
                : String.format("%.1f", rubPer1000).replace('.', ',');
        return "1 000 EXC = " + rubStr + " ₽ (фонд " + ratioPercent + "%)";
    }

    private String hrExplanationLine(int ratioPercent, long effectiveRewardPer100) {
        if (ratioPercent >= 100) {
            return "✅ Клуб работает на полную мощность — EXC выводятся по полному курсу.";
        }
        if (ratioPercent >= 70) {
            return "💡 Текущий курс вывода: <b>" + rateString(ratioPercent) + "</b>. EXC начисляются в полном объёме.";
        }
        return "⚠️ Текущий курс вывода: <b>" + rateString(ratioPercent) + "</b>. EXC начисляются в полном объёме. Когда фонд пополнится — курс вернётся к 1 000 EXC = 10 ₽.";
    }

    private void sendQuestGames(AppUser user) {
        String watchAdLabel = "🎬 Забери халявные EXC";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("🎮 Игровые квесты", "quests:section:gaming")));
        rows.add(List.of(keyboardFactory.callback("💼 Спонсорские квесты", "quests:section:sponsored")));
        rows.add(List.of(keyboardFactory.callback("🤳 UGC", "quests:section:ugc")));
        rows.add(List.of(keyboardFactory.callback(watchAdLabel, "quests:section:ads")));
        rows.add(List.of(keyboardFactory.callback("📂 Мои квесты", "menu:myquests")));
        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));
        sendText(user.getTelegramId(),
                "🗺️ <b>Квесты</b>\n\nВыберите раздел:",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendGamingQuestGames(AppUser user) {
        List<String> games = questService.findActiveGameNames();
        if (games.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📭 Сейчас в клубе нет активных квестов по играм. Как только новые задания появятся, они откроются здесь.",
                    backMenuKeyboard("menu:quests"));
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String game : games) {
            rows.add(List.of(keyboardFactory.callback(trim(game, 28), "quests:game:" + encodeGameToken(game))));
        }
        rows.add(List.of(
                keyboardFactory.callback("⬅️ Назад", "menu:quests"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));
        sendText(user.getTelegramId(),
                "🎮 <b>Игровые квесты</b>\n\nВыберите игру:",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendSponsoredQuestList(AppUser user) {
        List<Quest> quests = questService.findActiveSponsored();
        if (quests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "👀 Спонсорские квесты появятся скоро.",
                    backMenuKeyboard("menu:quests"));
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Quest q : quests) {
            buttons.add(keyboardFactory.callback("🎯 " + trim(q.getTitle(), 32), "quest:view:sponsored:all:" + q.getId()));
        }
        sendText(user.getTelegramId(),
                "💼 <b>Спонсорские квесты</b>\n\nВыберите задание:",
                verticalWithBackMenu(buttons, "⬅️ Назад", "menu:quests"));
    }

    private void sendUgcQuestList(AppUser user) {
        sendQuestList(user, "UGC", null, "menu:quests");
    }

    /** Подраздел «Реклама» в Квестах — список доступных источников рекламы за EXC. Сейчас один источник:
     * баннер в мини-аппе (AdsGram Reward-блок 45630, ранее жил только в Кошельке мини-аппа — перенесён
     * сюда). Бот-реклама (AdsGram bot-блок, прямо в чате) убрана — платформа-бот в AdsGram ещё не
     * прошла модерацию, кнопка была нерабочей. Вернуть через menu:watchad -> sendWatchAdOffer, когда
     * появится рабочий ADSGRAM_API_TOKEN/ADSGRAM_BOT_BLOCK_ID. */
    private void sendAdsList(AppUser user) {
        int remaining = userService.getAdRewardsRemainingToday(user);
        List<InlineKeyboardButton> buttons = new ArrayList<>(List.of(
                keyboardFactory.webApp("🎬 Реклама в приложении", "https://experience-gaming-club.pages.dev/quests?section=ads")));
        sendText(user.getTelegramId(),
                "📺 <b>Реклама</b>\n\nПосмотри рекламу — получи EXC. Осталось показов сегодня: <b>" + remaining + "</b>.",
                verticalWithBackMenu(buttons, "⬅️ Назад", "menu:quests"));
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

        // UGC-квесты без деления на сложность — сразу показываем список
        if ("UGC".equalsIgnoreCase(gameName)) {
            sendQuestList(user, gameName, null, "menu:quests");
            return;
        }

        // FLAT-режим — без деления на сложность, сразу показываем все квесты игры
        if (gameCatalogService.isFlat(gameName)) {
            sendQuestList(user, gameName, null, "quests:section:gaming");
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
                keyboardFactory.callback("⬅️ Назад", "quests:section:gaming"),
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
        sendQuestList(user, gameName, category, "quests:game:" + encodeGameToken(gameName));
    }

    private void sendQuestList(AppUser user, String gameName, String category, String backData) {
        if (gameName == null || gameName.isBlank()) {
            sendQuestGames(user);
            return;
        }

        List<Quest> quests = new ArrayList<>(category == null
                ? questService.findActiveByGameName(gameName)
                : questService.findActiveByGameNameAndCategory(gameName, category));
        if (quests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📭 В этой категории пока нет активных квестов. Проверьте позже или выберите другую подборку.",
                    backMenuKeyboard(backData));
            return;
        }

        quests.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));

        boolean useLabels = quests.stream().allMatch(q -> q.getShortLabel() != null && !q.getShortLabel().isBlank());

        String title = category == null ? gameName : gameName + " • " + category;
        StringBuilder listBuilder = new StringBuilder();
        List<InlineKeyboardButton> openButtons = new ArrayList<>();
        for (int i = 0; i < quests.size(); i++) {
            Quest quest = quests.get(i);
            String callback = "quest:view:" + encodeGameToken(gameName) + ":" + categoryToken(category) + ":" + quest.getId();
            if (useLabels) {
                // Квесты с явным маркером новизны (🔥 Новое) не дублируют его стандартным 🎯.
                String label = quest.getShortLabel();
                String prefix = label.startsWith("🔥") ? "" : "🎯 ";
                openButtons.add(keyboardFactory.callback(prefix + label, callback));
            } else {
                listBuilder.append(i + 1).append(". ").append(escape(quest.getTitle())).append("\n");
                openButtons.add(keyboardFactory.callback(String.valueOf(i + 1), callback));
            }
        }
        // С короткими подписями кнопки самодостаточны (видно суть квеста сразу) — раскладываем
        // по одной в ряд для читаемости вместо тесной цифровой сетки.
        InlineKeyboardMarkup keyboard = useLabels
                ? verticalWithBackMenu(openButtons, "⬅️ Назад", backData)
                : numberedGridWithBackMenu(openButtons, "⬅️ Назад", backData);

        // Photo caption has a 1024-char Telegram limit that a long quest list easily exceeds —
        // send the banner on its own (short caption, no keyboard) and the full list as a separate message.
        boolean wantsPhoto = category == null && !"UGC".equalsIgnoreCase(gameName);
        java.util.Optional<String> photoFileId = wantsPhoto ? gameCatalogService.getPhotoFileId(gameName) : java.util.Optional.empty();
        String listText;
        if (photoFileId.isPresent()) {
            sendPhotoCaption(user.getTelegramId(), photoFileId.get(), "<b>" + escape(title) + "</b>", null);
            listText = useLabels
                    ? "Нажмите на квест, чтобы увидеть награду и условия прохождения."
                    : "Откройте карточку по номеру, чтобы увидеть награду и условия прохождения.\n\n" + listBuilder;
        } else {
            listText = useLabels
                    ? "<b>" + escape(title) + "</b>\n\nНажмите на квест, чтобы увидеть награду и условия прохождения."
                    : "<b>" + escape(title) + "</b>\n\n"
                        + "Откройте карточку по номеру, чтобы увидеть награду и условия прохождения.\n\n"
                        + listBuilder;
        }
        sendText(user.getTelegramId(), listText, keyboard);
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

        String nextQuestData = null;
        if (parts.length == 3) {
            String gameName = decodeGameToken(parts[0]);
            String category = categoryFromToken(parts[1]);
            if (gameName != null) {
                List<Quest> quests = category == null
                        ? questService.findActiveByGameName(gameName)
                        : questService.findActiveByGameNameAndCategory(gameName, category);
                for (int i = 0; i < quests.size() - 1; i++) {
                    if (quests.get(i).getId().equals(questId)) {
                        Long nextId = quests.get(i + 1).getId();
                        nextQuestData = "quest:view:" + parts[0] + ":" + parts[1] + ":" + nextId;
                        break;
                    }
                }
            }
        }

        sendQuestCard(user, questId, backDataFromQuestViewToken(parts), "⬅️ Назад", null, nextQuestData);
        answerSilently(callbackQuery.getId());
    }

    private void sendQuestCard(AppUser user, Long questId) {
        sendQuestCard(user, questId, "menu:quests", "⬅️ Назад", null);
    }

    private void sendQuestCard(AppUser user, Long questId, String backData, String backText, String notice) {
        sendQuestCard(user, questId, backData, backText, notice, null);
    }

    private void sendQuestCard(AppUser user, Long questId, String backData, String backText, String notice, String nextQuestData) {
        sessionService.get(user.getTelegramId()).getData().put("quest_back_data", backData);
        Quest quest = questService.getQuest(questId);
        QuestSubmission latest = questService.getLatestSubmission(user, quest);
        boolean latestExpired = latest != null && questService.isExpired(latest);
        String statusText = latest == null ? "Не начат"
                : (latestExpired ? "Истёк срок" : humanStatus(latest.getStatus()));

        String deadlineLine = "";
        if (latest != null && latest.getStatus() == SubmissionStatus.DRAFT && latest.getExpiresAt() != null) {
            if (latestExpired) {
                deadlineLine = "⌛ Дедлайн: <b>истёк</b>\n";
            } else {
                deadlineLine = formatDeadlineLine(latest.getExpiresAt());
            }
        } else if (quest.getDurationDays() > 0 && (latest == null || latest.getStatus() == SubmissionStatus.REJECTED || latestExpired)) {
            deadlineLine = "⏳ Срок: <b>" + quest.getDurationText() + "</b> с момента старта\n";
        }

        long cooldownLeft = questService.getCooldownHoursLeft(user, quest);
        String displayStatus = cooldownLeft > 0
                ? "⏳ Кулдаун (" + cooldownLeft + " ч)"
                : statusText;

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        // Expired drafts are treated as "not active" — user should be able to retake
        boolean hasActiveSubmission = latest != null
                && latest.getStatus() != SubmissionStatus.CANCELLED
                && latest.getStatus() != SubmissionStatus.APPROVED
                && !latestExpired;
        long activeSlots = questService.countActiveDrafts(user);
        long maxSlots = sinkShopService.getMaxQuestSlots(user);
        boolean slotsFull = activeSlots >= maxSlots && !hasActiveSubmission;
        boolean gameCooldown = !hasActiveSubmission && cooldownLeft == 0 && questService.isCooldownActive(user, quest);
        if (cooldownLeft > 0) {
            buttons.add(keyboardFactory.callback("⏳ Доступно через " + cooldownLeft + " ч", "noop"));
        } else if (slotsFull) {
            buttons.add(keyboardFactory.callback("🔒 Сначала сдай активный квест", "noop"));
        } else if (gameCooldown) {
            buttons.add(keyboardFactory.callback("⏳ Кулдаун 24ч по этой игре", "noop"));
        } else if (!hasActiveSubmission) {
            buttons.add(keyboardFactory.callback("🚀 Взять", "quest:take:" + questId));
        }
        if (hasActiveSubmission) {
            buttons.add(quest.isExternalAutoApprove()
                    ? keyboardFactory.callback("⏳ Ждём подтверждения от партнёра", "noop")
                    : (quest.getBrawlVerifyType() != null || quest.getClashVerifyType() != null || quest.getClashRoyaleVerifyType() != null)
                        ? keyboardFactory.callback(autoVerifyProgressLabel(quest, latest), "noop")
                        : keyboardFactory.callback("📤 Отчёт", "quest:report:" + questId));
        }
        if (nextQuestData != null) {
            buttons.add(keyboardFactory.callback("➡️ Следующий квест", nextQuestData));
        }
        if (isEffectiveAdmin(user)) {
            buttons.add(keyboardFactory.callback("✏️ Правка", "admin:quest:" + questId));
        }

        String sponsorBadge = quest.isSponsored() ? "💎 <b>Спонсорский квест</b>\n" : "";
        String oneTimeBadge = quest.isOneTimePerAccount() ? "🔂 <b>Разовый квест</b> — доступен один раз за аккаунт\n" : "";
        boolean questFlat = gameCatalogService.isFlat(quest.getGameName());
        String personalizedInstruction = questService.personalizeInstruction(quest.getInstruction(), user.getTelegramId());
        sendText(user.getTelegramId(),
                (notice == null ? "" : notice + "\n\n")
                        + sponsorBadge
                        + oneTimeBadge
                        + "🎯 <b>" + escape(quest.getTitle()) + "</b>\n\n"
                        + (quest.isSponsored() ? "🎮 Название канала: <b>" : "🎮 Игра: <b>") + escape(quest.getGameName()) + "</b>\n"
                        + (quest.isSponsored() || "UGC".equalsIgnoreCase(quest.getGameName()) ? "" : (!questFlat && quest.getCategory() != null ? "📚 Формат: <b>" + escape(quest.getCategory()) + "</b>\n" : "") + "🕹️ Платформа: <b>" + escape(quest.getPlatform()) + "</b>\n")
                        + deadlineLine
                        + "📌 Статус: <b>" + escape(displayStatus) + "</b>\n\n"
                        + "🏆 <b>Награда:</b>\n"
                        + "✨ +" + quest.getRewardXp() + " XP\n"
                        + "🪙 +" + quest.getRewardCoins() + (quest.isSponsored() ? " EXC" : " монет") + "\n"
                        + (!quest.isSponsored() && !"UGC".equalsIgnoreCase(quest.getGameName()) && quest.getTicketReward() > 0 ? "🎟 +" + quest.getTicketReward() + " билет(а) для Колеса фортуны\n" : "")
                        + "\n"
                        + "📝 <b>Суть задания:</b>\n" + escape(quest.getDescription()) + "\n\n"
                        + (personalizedInstruction != null && !personalizedInstruction.isBlank()
                            ? (quest.isSponsored() ? "📎 <b>Ссылки:</b>\n" : "📎 <b>Что нужно сделать:</b>\n") + escape(personalizedInstruction)
                                + (quest.isSponsored() ? "" : (quest.isExternalAutoApprove() || quest.getBrawlVerifyType() != null || quest.getClashVerifyType() != null || quest.getClashRoyaleVerifyType() != null ? "\n\nℹ️ " : "\n\n✅ <b>Что примет модерация:</b>\n") + escape(quest.getRequirements()))
                            : (quest.isSponsored() ? "" : "📎 <b>Что нужно сделать:</b>\n" + escape(personalizedInstruction)
                                + (quest.getBrawlVerifyType() != null || quest.getClashVerifyType() != null || quest.getClashRoyaleVerifyType() != null ? "\n\nℹ️ " : "\n\n✅ <b>Что примет модерация:</b>\n") + escape(quest.getRequirements()))),
                verticalWithBackMenu(buttons, backText, backData));
    }

    private void handleTakeQuest(CallbackQuery callbackQuery, AppUser user, UserSession session, Long questId) {
        Quest quest = questService.getQuest(questId);
        if (quest.getBrawlVerifyType() != null && user.getBrawlStarsTag() == null) {
            answerSilently(callbackQuery.getId());
            session.reset();
            session.getData().put("brawlLinkPurpose", "quest");
            session.getData().put("brawlPendingQuestId", String.valueOf(questId));
            session.setState(SessionState.BRAWL_TAG_INPUT);
            sendText(user.getTelegramId(),
                    "🏷️ Для этого квеста нужен привязанный тег Brawl Stars — прогресс отслеживается автоматически.\n\n"
                            + "Введите ваш игровой тег (например: <code>#ABC123</code>):",
                    cancelKeyboard());
            return;
        }
        if (quest.getClashVerifyType() != null && user.getClashOfClansTag() == null) {
            answerSilently(callbackQuery.getId());
            session.reset();
            session.getData().put("clashPendingQuestId", String.valueOf(questId));
            session.setState(SessionState.CLASH_TAG_INPUT);
            sendText(user.getTelegramId(),
                    "🏷️ Для этого квеста нужен привязанный тег Clash of Clans — прогресс отслеживается автоматически.\n\n"
                            + "Введите ваш игровой тег (например: <code>#ABC123</code>):",
                    cancelKeyboard());
            return;
        }
        if (quest.getClashRoyaleVerifyType() != null && user.getClashRoyaleTag() == null) {
            answerSilently(callbackQuery.getId());
            session.reset();
            session.getData().put("crPendingQuestId", String.valueOf(questId));
            session.setState(SessionState.CR_TAG_INPUT);
            sendText(user.getTelegramId(),
                    "🏷️ Для этого квеста нужен привязанный тег Clash Royale — прогресс отслеживается автоматически.\n\n"
                            + "Введите ваш игровой тег (например: <code>#ABC123</code>):",
                    cancelKeyboard());
            return;
        }
        QuestService.QuestActionResult result = questService.takeQuestChecked(user, quest);
        answerSilently(callbackQuery.getId());

        if (result.status() != QuestActionStatus.OK) {
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад", takeQuestErrorMessage(user, quest, result));
            return;
        }

        long weeklyCount = questService.getWeeklyCompletionsOfType(user, quest);
        String notice = quest.isExternalAutoApprove()
                ? "🚀 Квест активен! Перейди по своей ссылке ниже — отчёт отправлять не нужно, EXC начислится автоматически."
                : quest.getBrawlVerifyType() != null
                    ? "🚀 Квест активен! ⏳ Прогресс отслеживается автоматически по вашему аккаунту Brawl Stars — отчёт отправлять не нужно."
                    : quest.getClashVerifyType() != null
                        ? "🚀 Квест активен! ⏳ Прогресс отслеживается автоматически по вашему аккаунту Clash of Clans — отчёт отправлять не нужно."
                        : quest.getClashRoyaleVerifyType() != null
                            ? "🚀 Квест активен! ⏳ Прогресс отслеживается автоматически по вашему аккаунту Clash Royale — отчёт отправлять не нужно."
                            : "🚀 Квест активен! Приступайте к игре, когда выполните задание, отправьте отчёт прямо из этой карточки.";
        if (!quest.isExternalAutoApprove() && quest.getBrawlVerifyType() == null && quest.getClashVerifyType() == null
                && quest.getClashRoyaleVerifyType() == null && weeklyCount >= 3) {
            notice += "\n\n⚠️ Вы уже выполнили 3+ таких квеста за неделю — награда EXC будет снижена на 50%.";
        }

        Quest freshQuest = questService.getQuest(questId);
        QuestSubmission submission = result.submission();
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        boolean freshQuestAutoVerified = freshQuest.getBrawlVerifyType() != null || freshQuest.getClashVerifyType() != null || freshQuest.getClashRoyaleVerifyType() != null;
        buttons.add(freshQuest.isExternalAutoApprove() || freshQuestAutoVerified
                ? keyboardFactory.callback(freshQuestAutoVerified ? autoVerifyProgressLabel(freshQuest, submission) : "⏳ Ждём подтверждения от партнёра", "noop")
                : keyboardFactory.callback("📤 Отчёт", "quest:report:" + questId));
        buttons.add(keyboardFactory.callback("📂 Мои квесты", "menu:myquests"));
        buttons.add(keyboardFactory.callback("🏠 Меню", "menu:main"));
        if (isEffectiveAdmin(user)) {
            buttons.add(keyboardFactory.callback("✏️ Правка", "admin:quest:" + questId));
        }

        String deadlineLine = "";
        if (submission != null && submission.getExpiresAt() != null) {
            deadlineLine = questService.isExpired(submission) ? "⌛ Дедлайн: <b>истёк</b>\n" : formatDeadlineLine(submission.getExpiresAt());
        }

        sendText(user.getTelegramId(),
                notice + "\n\n"
                        + "🎯 <b>" + escape(freshQuest.getTitle()) + "</b>\n\n"
                        + (freshQuest.isSponsored() ? "🎮 Название канала: <b>" : "🎮 Игра: <b>") + escape(freshQuest.getGameName()) + "</b>\n"
                        + (freshQuest.isSponsored() || "UGC".equalsIgnoreCase(freshQuest.getGameName()) ? "" : (!gameCatalogService.isFlat(freshQuest.getGameName()) && freshQuest.getCategory() != null ? "📚 Формат: <b>" + escape(freshQuest.getCategory()) + "</b>\n" : "") + "🕹️ Платформа: <b>" + escape(freshQuest.getPlatform()) + "</b>\n")
                        + deadlineLine
                        + "📌 Статус: <b>В процессе</b>\n\n"
                        + "🏆 <b>Награда</b>\n"
                        + "✨ +" + freshQuest.getRewardXp() + " XP\n"
                        + "🪙 +" + freshQuest.getRewardCoins() + " монет"
                        + (!freshQuest.isSponsored() && !"UGC".equalsIgnoreCase(freshQuest.getGameName()) && freshQuest.getTicketReward() > 0 ? "\n🎟 +" + freshQuest.getTicketReward() + " билет(а) для Колеса фортуны" : "")
                        + (freshQuest.isExternalAutoApprove()
                            ? "\n\n📎 <b>Что нужно сделать:</b>\n" + escape(questService.personalizeInstruction(freshQuest.getInstruction(), user.getTelegramId()))
                            : ""),
                keyboardFactory.smartLayout(buttons));
    }

    private String takeQuestErrorMessage(AppUser user, Quest quest, QuestService.QuestActionResult result) {
        return switch (result.status()) {
            case ALREADY_DRAFT -> {
                QuestSubmission latest = questService.getLatestSubmission(user, quest);
                yield (latest != null && questService.isExpired(latest))
                        ? "⌛ Срок выполнения квеста истёк. Вы не успели сдать отчёт вовремя."
                        : "🧭 Этот квест уже добавлен в работу. Ниже оставил карточку с кнопкой для отчёта.";
            }
            case ALREADY_APPROVED ->
                    quest.isExternalAutoApprove() || quest.isOneTimePerAccount()
                        ? "✅ Этот квест одноразовый и уже выполнен — награда начислена, повторно пройти его нельзя."
                        : "📌 По этому квесту уже есть активный прогресс. Используйте карточку ниже, чтобы посмотреть статус или отправить отчёт.";
            case ALREADY_PENDING ->
                    "📌 По этому квесту уже есть активный прогресс. Используйте карточку ниже, чтобы посмотреть статус или отправить отчёт.";
            case HAS_REJECTED_REPORT ->
                    "❌ Ваш отчёт по этому квесту был отклонён. Нажмите «📤 Отчёт», чтобы исправить ошибки и переотправить.";
            case SLOTS_FULL ->
                    "📂 У вас уже есть активные квесты. Завершите или отмените один из них, либо купите доп. слот (2 000 EXC) в разделе Предметы клуба.";
            case SAME_QUEST_COOLDOWN -> "⏳ Этот квест можно выполнять не чаще 1 раза в 24 часа.";
            case GAME_COOLDOWN ->
                    "⏳ Кулдаун активен. Повторный квест в этой игре доступен через 24 часа.\n\n💡 Можно снять кулдаун за 2 000 EXC в разделе Предметы клуба.";
            case TAKE_COOLDOWN -> "⏳ Новый квест можно брать раз в час. Подождите ещё <b>" + result.minutesLeft() + " мин.</b>";
            default -> "⚠️ Не удалось взять квест.";
        };
    }

    private String reportSubmitErrorMessage(IllegalStateException e) {
        return "pending_report_exists".equals(e.getMessage())
                ? "⏳ У вас уже 2 отчёта на проверке у модератора — дождитесь решения по одному из них, прежде чем отправлять следующий."
                : "⚠️ " + e.getMessage();
    }

    private void handleReportStart(CallbackQuery callbackQuery, AppUser user, UserSession session, Long questId) {
        Quest quest = questService.getQuest(questId);
        // Серверная проверка, не только скрытие кнопки в UI — иначе кнопка "Отчёт" из другого экрана
        // (или просто старое сообщение с ней) даёт вручную отправить отчёт по квесту, который должен
        // подтверждаться только через API (инцидент 2026-08-31, см. sendMyQuestCard).
        if (quest.isExternalAutoApprove() || quest.getBrawlVerifyType() != null || quest.getClashVerifyType() != null || quest.getClashRoyaleVerifyType() != null) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "ℹ️ Этот квест подтверждается автоматически — отправлять отчёт не нужно и нельзя.");
            return;
        }
        QuestSubmission latest = questService.getLatestSubmission(user, quest);
        if (latest == null || latest.getStatus() == SubmissionStatus.CANCELLED) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "⚠️ Сначала возьмите квест кнопкой «🚀 Взять».");
            return;
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

        if (questService.isExpired(latest)) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "⌛ Срок выполнения этого квеста истёк. Отчёт больше не принимается.");
            return;
        }

        if (questService.hasOtherPendingSubmission(user, quest)) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "⏳ У вас уже 2 отчёта на проверке у модератора — дождитесь решения по одному из них, прежде чем отправлять следующий.");
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
        String photoUniqueIds = null;
        String text = message.getCaption();

        if (message.hasPhoto()) {
            mediaType = "photo";
            List<PhotoSize> photos = message.getPhoto();
            PhotoSize largest = photos.get(photos.size() - 1);
            fileId = largest.getFileId();
            photoUniqueIds = largest.getFileUniqueId();
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
        questService.submitReport(submission, mediaType, fileId, photoUniqueIds, externalLink, text == null ? "Без комментария" : text);
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
        String uniqueIds = session.getData().getOrDefault("report_photo_unique_ids", "");
        String comment = session.getData().getOrDefault("report_comment", "");

        if (message.hasPhoto()) {
            List<PhotoSize> photoList = message.getPhoto();
            PhotoSize largest = photoList.get(photoList.size() - 1);
            String fileId = largest.getFileId();
            String uniqueId = largest.getFileUniqueId();
            photos = photos.isBlank() ? fileId : photos + "||" + fileId;
            uniqueIds = uniqueIds.isBlank() ? uniqueId : uniqueIds + "||" + uniqueId;
            if (message.getCaption() != null && !message.getCaption().isBlank()) {
                comment = message.getCaption();
            }
            session.getData().put("report_photos", photos);
            session.getData().put("report_photo_unique_ids", uniqueIds);
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
            try {
                questService.submitReport(submission, "video", fileId, extractUrl(text), text == null ? "Без комментария" : text);
            } catch (IllegalStateException e) {
                sendText(user.getTelegramId(), reportSubmitErrorMessage(e), backMenuKeyboard("menu:myquests"));
                return;
            }
            session.reset();
            notifyModeratorsAboutSubmission(submission.getId());
            sendText(user.getTelegramId(),
                    "✅ <b>Отчёт отправлен</b>\n\nМатериалы ушли в очередь проверки.",
                    backMenuKeyboard("menu:myquests"));
        } else if (message.hasDocument()) {
            String fileId = message.getDocument().getFileId();
            String text = message.getCaption();
            QuestSubmission submission = questService.getSubmission(session.getSubmissionId());
            try {
                questService.submitReport(submission, "document", fileId, extractUrl(text), text == null ? "Без комментария" : text);
            } catch (IllegalStateException e) {
                sendText(user.getTelegramId(), reportSubmitErrorMessage(e), backMenuKeyboard("menu:myquests"));
                return;
            }
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
                    "📭 У вас нет квестов в работе.\n\nОткройте раздел квестов и возьмите первое задание.",
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
        // Тот же гейт, что и в sendQuestCard/handleTakeQuest — без него игрок мог вручную отправить
        // отчёт по квесту, который должен подтверждаться только через API (лазейка, инцидент 2026-08-31,
        // поймана на живой заявке К-1333 "Сразись в бою 6 раз" — квест с BrawlVerifyType).
        boolean autoVerified = quest.getBrawlVerifyType() != null || quest.getClashVerifyType() != null || quest.getClashRoyaleVerifyType() != null;
        buttons.add(quest.isExternalAutoApprove()
                ? keyboardFactory.callback("⏳ Ждём подтверждения от партнёра", "noop")
                : autoVerified
                    ? keyboardFactory.callback(autoVerifyProgressLabel(quest, submission), "noop")
                    : keyboardFactory.callback("📤 Отчёт", "quest:report:" + quest.getId()));
        if (canCancel) {
            buttons.add(keyboardFactory.callback("❌ Отменить квест", "myquest:cancel:" + submission.getId()));
        }

        sendText(user.getTelegramId(),
                "📂 <b>Мой квест</b>\n\n"
                        + "🎯 <b>" + escape(quest.getTitle()) + "</b>\n"
                        + "📌 Статус: <b>" + escape(humanStatus(submission.getStatus())) + "</b>\n"
                        + "🕒 Обновлено: <b>" + escape(submission.getUpdatedAt().format(DATE_TIME_FORMATTER)) + "</b>\n"
                        + "✨ XP: <b>+" + quest.getRewardXp() + "</b>\n"
                        + "🪙 Монеты: <b>+" + quest.getRewardCoins() + "</b>\n"
                        + (quest.getTicketReward() > 0 ? "🎟 Билеты: <b>+" + quest.getTicketReward() + "</b>\n" : "")
                        + "\n📝 <b>Суть задания</b>\n" + escape(quest.getDescription()) + moderatorComment,
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
                        + "🎁 <b>Как работает:</b>\n\n"
                        + "Шаг 1 — друг вступает в клуб\n"
                        + "• Тебе сразу: <b>+300 EXC</b>\n"
                        + "• Другу сразу: <b>+500 EXC</b>\n\n"
                        + "Шаг 2 — друг выполняет первый квест\n"
                        + "• Другу бонусом: <b>+3 000 EXC</b>\n\n"
                        + "Шаг 3 — друг зарабатывает квестами\n"
                        + "• Ты получаешь <b>3% от каждого его EXC</b> — пока друг активен (выполняет квесты хотя бы раз в 14 дней)\n\n"
                        + "Скопируй ссылку и отправь другу — остальное система сделает сама.",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("🏆 Рейтинг недели", "menu:referral-rating")),
                        List.of(keyboardFactory.callback("👥 Мои друзья", "menu:referral-friends")),
                        List.of(
                                keyboardFactory.callback("⬅️ Назад", "menu:main"),
                                keyboardFactory.callback("🏠 Меню", "menu:main")
                        )
                )));
    }

    private void sendReferralFriendsList(AppUser user) {
        List<AppUser> friends = userService.findReferredFriends(user.getTelegramId());
        if (friends.isEmpty()) {
            sendText(user.getTelegramId(), "👥 <b>Мои друзья</b>\n\nВы пока никого не пригласили.",
                    backMenuKeyboard("menu:referrals"));
            return;
        }
        StringBuilder sb = new StringBuilder("👥 <b>Мои друзья</b>\n\n");
        for (AppUser friend : friends) {
            sb.append(friend.isReferralActive() ? "🟢 " : "⚪ ")
              .append(escape(displayUserName(friend)))
              .append(friend.isReferralActive() ? " — активен" : " — неактивен (комиссия на паузе)")
              .append("\n");
        }
        sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:referrals"));
    }

    private void sendReferralRanking(AppUser user) {
        LocalDateTime weekStart = java.time.LocalDate.now()
                .with(java.time.DayOfWeek.MONDAY).atStartOfDay();
        List<Object[]> rows = excTransactionService.findReferralEarningsRankingBetween(weekStart, LocalDateTime.now());

        StringBuilder sb = new StringBuilder("🏆 <b>Рейтинг рефереров за неделю</b>\n\n");
        if (rows.isEmpty()) {
            sb.append("Пока никто не заработал на рефералах на этой неделе.\n"
                    + "Пригласи друга первым — и окажешься в топе! 🚀");
        } else {
            int myRank = -1;
            for (int i = 0; i < Math.min(5, rows.size()); i++) {
                Long userId = (Long) rows.get(i)[0];
                long earned = ((Number) rows.get(i)[1]).longValue();
                boolean isMe = userId.equals(user.getId());
                if (isMe) myRank = i + 1;
                AppUser rowUser = isMe ? user : userService.findById(userId).orElse(null);
                String name = escape(rowUser != null ? displayUserName(rowUser) : "Игрок");
                int invited = rowUser != null ? rowUser.getInvitedFriends() : 0;
                sb.append(i + 1).append(". ").append(name)
                        .append(" — 👥 ").append(invited)
                        .append(" (+").append(earned).append(" EXC)")
                        .append(isMe ? " 👈" : "").append("\n");
            }
            if (myRank == -1) {
                for (int i = 5; i < rows.size(); i++) {
                    if (((Long) rows.get(i)[0]).equals(user.getId())) {
                        sb.append("\n").append(i + 1).append(". ").append(escape(displayUserName(user)))
                                .append(" - ваше место");
                        break;
                    }
                }
            }
            sb.append("\n\n📅 Топ-5 в конце недели (в понедельник) получит бонус из пула 2000 EXC.");
        }

        sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:referrals"));
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
                        String groupLabel = "avatar_frame".equals(groupEntry.getKey())
                                ? "Рамка аватара"
                                : groupItemLabel(first.getTitle());
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
                if ("Кастомизация".equals(entry.getKey())) {
                    rows.add(List.of(keyboardFactory.callback("🎭 Титулы профиля", "sink:titles")));
                }
            }
        } else {
            rows.add(List.of(keyboardFactory.callback("── Кастомизация ──", "noop")));
            rows.add(List.of(keyboardFactory.callback("🎭 Титулы профиля", "sink:titles")));
        }

        rows.add(List.of(keyboardFactory.callback("📋 Мои заявки", "menu:my-rewards")));
        rows.add(List.of(
                keyboardFactory.callback("⬅️ Назад", "menu:cat:shop"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));

        sendText(user.getTelegramId(),
                "🛍️ <b>Магазин наград</b>\n\n"
                        + "🪙 Ваш баланс: <b>" + user.getCoins() + " EXC</b>\n"
                        + "📊 Состояние фонда: <b>" + ratioPercent + "%</b>\n"
                        + "📤 Лимит вывода: <b>" + sinkShopService.getMonthlyLimit(user.getXp()) + " EXC/мес</b> (осталось: " + remaining + " EXC)\n"
                        + "💱 Курс вывода: <b>" + rateString(ratioPercent) + "</b>\n"
                        + withdrawalLevelHint(user),
                keyboardFactory.rowsLayout(rows));
    }

    private void sendWithdrawalMethodChoice(AppUser user) {
        boolean noCountry = user.getCountry() == null || user.getCountry().isBlank();
        boolean noAge = user.getAge() == null;
        if (noCountry || noAge) {
            String missing = (noCountry && noAge) ? "страну и возраст" : (noCountry ? "страну" : "возраст");
            sendText(user.getTelegramId(),
                    "⚠️ <b>Для вывода средств необходимо указать " + missing + ".</b>\n\n"
                    + "Перейдите в профиль → «✏️ Редактировать профиль» и заполните данные.",
                    keyboardFactory.rowsLayout(List.of(
                            List.of(keyboardFactory.callback("👤 Редактировать профиль", "profile:edit")),
                            List.of(keyboardFactory.callback("❌ Отмена", "menu:balance"))
                    )));
            return;
        }
        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);
        int ratioPercent = (int) Math.round(healthRatioService.getCurrentRatio() * 100);
        String text = "💸 <b>Вывод EXC</b>\n\n"
                + "🪙 Баланс: <b>" + user.getCoins() + " EXC</b>\n"
                + "📤 Остаток лимита: <b>" + remaining + " EXC (из " + sinkShopService.getMonthlyLimit(user.getXp()) + "/мес)</b>\n"
                + "💱 Курс: <b>" + rateString(ratioPercent) + "</b>\n"
                + "⚠️ Минимум: <b>5 000 EXC</b>\n"
                + withdrawalLevelHint(user) + "\n\n"
                + "Выберите способ получения:";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("💸 В рублях (Сбербанк / СБП)", "shop:withdraw:rub")));
        rows.add(List.of(keyboardFactory.callback("💎 В TON (Telegram Wallet)", "shop:withdraw:ton")));
        rows.add(List.of(keyboardFactory.callback("📋 Мои заявки на вывод", "menu:my-withdrawals")));
        rows.add(List.of(keyboardFactory.callback("❌ Отмена", "menu:balance")));
        sendText(user.getTelegramId(), text, keyboardFactory.rowsLayout(rows));
    }

    private void sendWithdrawalTonWalletQuestion(AppUser user) {
        sendText(user.getTelegramId(),
                "💎 <b>Вывод в TON</b>\n\nЕсть ли у вас кошелёк в Telegram Wallet (@wallet)?",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("✅ Да, есть кошелёк", "shop:withdraw:ton:has_wallet")),
                        List.of(keyboardFactory.callback("❌ Нет кошелька", "shop:withdraw:ton:no_wallet")),
                        List.of(keyboardFactory.callback("⬅️ Назад", "shop:withdraw"))
                )));
    }

    private void sendWithdrawalTonNoWalletGuide(AppUser user) {
        sendText(user.getTelegramId(),
                "💎 <b>Как создать кошелёк</b>\n\n"
                        + "1. Нажми кнопку ниже — откроется <b>кошелёк</b> в Telegram\n"
                        + "2. Пройди короткую регистрацию (1–2 минуты)\n"
                        + "3. Вернись сюда и нажми <b>«У меня есть кошелёк»</b>",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.url("🚀 Открыть Telegram Wallet", "https://t.me/wallet/start?startapp=ref-3-PaQlujnvUGU")),
                        List.of(keyboardFactory.callback("✅ У меня есть кошелёк", "shop:withdraw:ton:has_wallet")),
                        List.of(keyboardFactory.callback("⬅️ Назад", "shop:withdraw"))
                )));
    }

    private void sendWithdrawalTonAmountScreen(AppUser user) {
        sendText(user.getTelegramId(),
                "💎 <b>Вывод в TON</b>\n\nВведите сумму в EXC, которую хотите вывести.",
                cancelKeyboard());
    }

    private void handleWithdrawalTonAmount(AppUser user, UserSession session, String text) {
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
        java.math.BigDecimal tonRate = exchangeRateService.getTonRubRate();
        java.math.BigDecimal tonAmount = exchangeRateService.rubToTon(rublesDecimal);
        String rateNote = exchangeRateService.isUsingFallback()
                ? "📈 Курс: 1 TON ≈ " + tonRate.setScale(2, java.math.RoundingMode.HALF_DOWN) + " ₽ (приблизительно)"
                : "📈 Курс: 1 TON = " + tonRate.setScale(2, java.math.RoundingMode.HALF_DOWN) + " ₽";
        session.getData().put("ton_exc_amount", String.valueOf(amount));
        session.getData().put("ton_rubles", String.valueOf(rubles));
        session.setState(SessionState.WITHDRAWAL_TON_ADDRESS);
        String msg = "💎 <b>Сумма принята</b>\n\n"
                + "💸 " + amount + " EXC → <b>" + rubles + " ₽</b> → ~<b>" + tonAmount + " TON</b>\n"
                + rateNote + "\n"
                + "⚠️ Комиссия сети (~0.01 TON) вычитается из суммы перевода — получишь чуть меньше.\n\n"
                + "━━━━━━━━━━━━━━━\n"
                + "📋 <b>Как найти адрес кошелька:</b>\n\n"
                + "1. Открой @wallet в Telegram (или кнопку ниже)\n"
                + "2. Нажми <b>«Получить»</b> или <b>«Deposit»</b>\n"
                + "3. Выбери <b>Toncoin (TON)</b>\n"
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

    private void handleWithdrawalTonAddress(AppUser user, UserSession session, String text) {
        String wallet = text.trim();
        if (wallet.length() < 20 || wallet.contains(" ")) {
            sendText(user.getTelegramId(),
                    "⚠️ <b>Некорректный адрес кошелька</b>\n\n"
                    + "Адрес TON должен начинаться с <b>UQ...</b> или <b>EQ...</b> и содержать 48 символов.\n\n"
                    + "Как найти: @wallet → Получить → Toncoin (TON) → Скопировать адрес.\n\n"
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
        long excAmount = Long.parseLong(session.getData().getOrDefault("ton_exc_amount", "0"));
        long rubles = Long.parseLong(session.getData().getOrDefault("ton_rubles", "0"));
        java.math.BigDecimal tonRate2 = exchangeRateService.getTonRubRate();
        java.math.BigDecimal tonAmount2 = exchangeRateService.rubToTon(java.math.BigDecimal.valueOf(rubles));
        try {
            RewardRequest tonReq = rewardService.createTonWithdrawalRequest(user, excAmount, rubles, 0, wallet);
            session.reset();
            sendText(user.getTelegramId(),
                    "✅ <b>Заявка на вывод в TON принята!</b>\n\n"
                    + "🔢 Номер заявки: <b>В-" + tonReq.getId() + "</b>\n"
                    + "💸 Сумма: <b>" + excAmount + " EXC</b>\n"
                    + "💵 Эквивалент: <b>" + rubles + " ₽</b> → ~<b>" + tonAmount2 + " TON</b>\n"
                    + "📈 Курс: 1 TON = " + tonRate2.setScale(2, java.math.RoundingMode.HALF_DOWN) + " ₽\n"
                    + "💎 Способ: <b>GRAM (TON)</b>\n"
                    + "📬 Кошелёк: <code>" + escape(wallet) + "</code>\n\n"
                    + "⚠️ Комиссия сети (~0.01 TON) вычитается из суммы — на кошелёк придёт чуть меньше.\n\n"
                    + "Администратор обработает заявку в течение 24 часов.",
                    backMenuKeyboard("menu:main"));
            notifyAdminsAboutWithdrawal(user, tonReq);
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

        rows.add(List.of(keyboardFactory.callback("⏱️ Снятие кулдауна — 2 000 EXC", "sink:cooldown_info")));

        rows.add(List.of(keyboardFactory.callback("— Социальные —", "sink:noop")));
        rows.add(List.of(keyboardFactory.callback("🎁 Подарок другу (буст) — 4 500 EXC", "sink:gift")));
        rows.add(List.of(keyboardFactory.callback("🔄 🔒 Перевод EXC — Скоро", "sink:soon")));
        rows.add(List.of(keyboardFactory.callback("⚔️ 🔒 Дуэль — Скоро", "sink:soon")));
        rows.add(List.of(keyboardFactory.callback("📢 🔒 Место в ТОП-посте — Скоро", "sink:soon")));

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
                rows2.add(List.of(keyboardFactory.callback("⏱️ Купить снятие — 2 000 EXC", "sink:buycooldown")));
                rows2.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:sink")));
                sendText(user.getTelegramId(),
                    "⏱️ <b>Снятие кулдауна</b>\n\nСнимает 24-часовой кулдаун для следующего квеста в любой игре.\nСтоимость: 2 000 EXC. Лимит: 2 раза в сутки.\n\n💡 После покупки перейдите к нужному квесту — кулдаун будет снят автоматически при взятии.",
                    keyboardFactory.rowsLayout(rows2));
            }
            case "buycooldown" -> {
                try {
                    sinkShopService.purchaseCooldownRemoval(user);
                    sendText(user.getTelegramId(),
                        "⏱️ <b>Снятие кулдауна активировано!</b>\n\nВаш следующий квест, если на него действует кулдаун, будет доступен без ожидания.\nСписано 2 000 EXC.",
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
            case "transfer" -> {
                UserSession ts = sessionService.get(user.getTelegramId());
                ts.setState(SessionState.TRANSFER_EXC_RECIPIENT);
                sendText(user.getTelegramId(),
                    "🔄 <b>Перевод EXC</b>\n\n"
                    + "Переведи EXC другому игроку.\n"
                    + "Комиссия: 10% от суммы (мин. 200 EXC) — сгорает.\n"
                    + "Лимит: 1 перевод в сутки.\n\n"
                    + "Введи ник получателя (как в профиле бота):",
                    backMenuKeyboard("menu:sink"));
            }
            case "transfer:cancel" -> {
                sessionService.get(user.getTelegramId()).reset();
                sendSinkShop(user);
            }
            case "transfer:confirm" -> {
                UserSession ts = sessionService.get(user.getTelegramId());
                String recipientIdStr = ts.getData().get("transfer_recipient_id");
                String amountStr = ts.getData().get("transfer_amount");
                ts.reset();
                if (recipientIdStr == null || amountStr == null) {
                    sendText(user.getTelegramId(), "⚠️ Сессия устарела. Начните перевод заново.", backMenuKeyboard("menu:sink"));
                    return;
                }
                AppUser recipient = userService.findByTelegramId(Long.parseLong(recipientIdStr)).orElse(null);
                if (recipient == null) {
                    sendText(user.getTelegramId(), "⚠️ Получатель не найден.", backMenuKeyboard("menu:sink"));
                    return;
                }
                long amount = Long.parseLong(amountStr);
                try {
                    var transfer = sinkShopService.executeTransfer(user, recipient, amount);
                    sendText(user.getTelegramId(),
                        "✅ <b>Перевод выполнен!</b>\n\n"
                        + "Получатель: <b>" + escape(displayUserName(recipient)) + "</b>\n"
                        + "Сумма: <b>" + transfer.getAmount() + " EXC</b>\n"
                        + "Комиссия (сгорела): <b>" + transfer.getCommission() + " EXC</b>\n"
                        + "Списано с тебя: <b>" + transfer.getTotalDebited() + " EXC</b>",
                        backMenuKeyboard("menu:sink"));
                    try {
                        sendText(recipient.getTelegramId(),
                            "🔄 <b>Тебе перевели " + transfer.getAmount() + " EXC</b>\n\n"
                            + "Отправитель: <b>" + escape(displayUserName(user)) + "</b>",
                            backMenuKeyboard("menu:main"));
                    } catch (Exception ignored) { }
                } catch (IllegalArgumentException e) {
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:sink"));
                }
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

    private void handleTransferRecipientInput(AppUser user, UserSession session, String text) {
        String nickname = text.trim().replaceFirst("^@", "");
        AppUser recipient = userService.findByNickname(nickname).orElse(null);
        if (recipient == null) {
            sendText(user.getTelegramId(),
                "⚠️ Игрок с ником «" + escape(nickname) + "» не найден. Проверьте написание и попробуйте снова:",
                backMenuKeyboard("menu:sink"));
            return;
        }
        if (recipient.getTelegramId().equals(user.getTelegramId())) {
            sendText(user.getTelegramId(), "⚠️ Нельзя переводить EXC самому себе.", backMenuKeyboard("menu:sink"));
            session.reset();
            return;
        }
        session.getData().put("transfer_recipient_id", String.valueOf(recipient.getTelegramId()));
        session.setState(SessionState.TRANSFER_EXC_AMOUNT);
        sendText(user.getTelegramId(),
            "✅ Найден игрок: <b>" + escape(displayUserName(recipient)) + "</b>\n\n"
            + "Введи сумму перевода (от 1 000 до 10 000 EXC):",
            backMenuKeyboard("menu:sink"));
    }

    private void handleTransferAmountInput(AppUser user, UserSession session, String text) {
        long amount;
        try {
            amount = Long.parseLong(text.trim().replace(",", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            sendText(user.getTelegramId(), "⚠️ Введи число, например: 5000", backMenuKeyboard("menu:sink"));
            return;
        }
        if (amount < SinkShopService.TRANSFER_MIN || amount > SinkShopService.TRANSFER_MAX) {
            sendText(user.getTelegramId(),
                "⚠️ Сумма должна быть от " + SinkShopService.TRANSFER_MIN + " до " + SinkShopService.TRANSFER_MAX + " EXC.",
                backMenuKeyboard("menu:sink"));
            return;
        }
        long commission = sinkShopService.calcCommission(amount);
        long totalDebited = amount + commission;
        String recipientIdStr = session.getData().get("transfer_recipient_id");
        AppUser recipient = userService.findByTelegramId(Long.parseLong(recipientIdStr)).orElse(null);
        if (recipient == null) {
            session.reset();
            sendText(user.getTelegramId(), "⚠️ Получатель не найден. Начните заново.", backMenuKeyboard("menu:sink"));
            return;
        }
        session.getData().put("transfer_amount", String.valueOf(amount));
        session.setState(SessionState.NONE);
        sendText(user.getTelegramId(),
            "🔄 <b>Подтвердите перевод</b>\n\n"
            + "Получатель: <b>" + escape(displayUserName(recipient)) + "</b>\n"
            + "Сумма: <b>" + amount + " EXC</b>\n"
            + "Комиссия (сгорает): <b>" + commission + " EXC</b>\n"
            + "Спишется с тебя: <b>" + totalDebited + " EXC</b>",
            keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("✅ Подтвердить", "sink:transfer:confirm")),
                List.of(keyboardFactory.callback("❌ Отмена", "sink:transfer:cancel"))
            )));
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

        if (reward.getAvatarFrameColor() != null) {
            sendText(user.getTelegramId(),
                    "✅ <b>Рамка аватара применена!</b>\n\n"
                            + "🎁 " + escape(reward.getTitle()) + "\n"
                            + "🪙 Списано: <b>" + effectivePrice + " EXC</b>\n\n"
                            + "Открой мини-апп → Профиль, чтобы увидеть новую рамку вокруг аватара.",
                    backMenuKeyboard("menu:shop"));
            if (callbackQuery != null) answerSilently(callbackQuery.getId());
            return;
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
        if (t.getStartDate() != null) sb.append("🔒 Закрытие регистрации: ").append(t.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))).append(" (UTC)\n");
        if (t.getEndDate() != null) sb.append("⏰ Финиш: ").append(t.getEndDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))).append(" (UTC)\n");
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
        if (t.getScoringType() == ru.gamebot.platform.domain.model.Tournament.ScoringType.BRAWL_TROPHIES) {
            rows.add(List.of(keyboardFactory.callback("📜 Правила турнира", "tournament:rules:" + t.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:main")));
        InlineKeyboardMarkup tournamentKeyboard = keyboardFactory.rowsLayout(rows);
        if (t.getPhotoFileId() != null) {
            sendPhotoCaption(user.getTelegramId(), t.getPhotoFileId(), sb.toString(), tournamentKeyboard);
        } else {
            sendText(user.getTelegramId(), sb.toString(), tournamentKeyboard);
        }
    }

    private void sendBrawlTournamentRules(AppUser user, long tid) {
        tournamentService.findById(tid).ifPresentOrElse(t -> {
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");
            String start = t.getStartDate() != null ? t.getStartDate().format(fmt) + " UTC" : "—";
            String end = t.getEndDate() != null ? t.getEndDate().format(fmt) + " UTC" : "—";
            String text = "📜 <b>" + escape(t.getName()) + " — Правила</b>\n\n"
                    + "📌 <b>Суть турнира</b>\n"
                    + "Побеждает не тот, у кого больше трофеев, а тот, кто нарастил их больше всех за время турнира. "
                    + "У новичка с 500 трофеями и профи с 30 000 — равные шансы на победу.\n\n"
                    + "🎮 <b>Как участвовать</b>\n"
                    + "1. Нажмите «⚔️ Участвовать» и оплатите взнос\n"
                    + "2. Привяжите свой игровой тег Brawl Stars (#XXXXXXX)\n"
                    + "3. Дождитесь закрытия регистрации — с этого момента фиксируется ваш стартовый результат\n\n"
                    + "💰 <b>Взнос и призовой фонд</b>\n"
                    + "Взнос за участие: <b>" + t.getEntryFeeExc() + " EXC</b>. Все взносы участников формируют общий призовой фонд — "
                    + "чем больше игроков, тем крупнее призы.\n\n"
                    + "📊 <b>Как считаются призовые места</b>\n"
                    + "• В стартовый момент (" + start + ") фиксируются трофеи всех участников\n"
                    + "• В финальный момент (" + end + ") фиксируются трофеи повторно\n"
                    + "• Место в рейтинге определяется по приросту трофеев: финал минус старт\n"
                    + "• 🥇 1 место — 60% призового фонда\n"
                    + "• Остальные места — оставшиеся 40% делятся поровну между всеми участниками, кроме первого места\n\n"
                    + "⚠️ <b>Важно</b>\n"
                    + "• Игровой ник должен совпадать с ником, привязанным в EGC\n"
                    + "• Один участник — один игровой аккаунт\n"
                    + "• Подозрительная активность (резкая потеря трофеев перед стартом с последующим набором) проверяется вручную и может привести к дисквалификации\n"
                    + "• Взнос не возвращается ни в каком случае, включая дисквалификацию\n\n"
                    + (t.getMinParticipants() != null
                        ? "🚫 <b>Минимальное число участников</b>\n"
                          + "Турнир состоится только при " + t.getMinParticipants() + " и более зарегистрированных участниках. "
                          + "Если к моменту закрытия регистрации набралось меньше — турнир отменяется, взнос возвращается всем участникам в полном объёме.\n\n"
                        : "")
                    + "🔒 <b>Ключевые даты</b>\n"
                    + "• Закрытие регистрации: " + start + "\n"
                    + "• Финиш турнира: " + end;
            sendText(user.getTelegramId(), text, backOnlyKeyboard("menu:tournament"));
        }, () -> sendText(user.getTelegramId(), "❌ Турнир не найден.", backMenuKeyboard("menu:main")));
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

    // ─── Squads ───────────────────────────────────────────────────────────────

    private void sendSquadMenu(AppUser user) {
        ru.gamebot.platform.domain.model.Squad squad = squadService.findByUser(user).orElse(null);
        if (squad == null) {
            sendText(user.getTelegramId(),
                    "⚔️ <b>Отряды</b>\n\n"
                            + "Собирайте команду из 2–5 игроков.\n"
                            + "Суммарный XP участников — рейтинг вашего отряда.\n"
                            + "Каждую неделю топ-отряд делит <b>10 000 EXC</b> на всех.\n\n"
                            + "Зовите друзей и играйте вместе 🔥",
                    keyboardFactory.verticalLayout(List.of(
                            keyboardFactory.callback("➕ Создать отряд", "squad:create"),
                            keyboardFactory.callback("🔗 Вступить по коду", "squad:join_prompt"),
                            keyboardFactory.callback("🏆 Рейтинг отрядов", "squad:leaderboard"),
                            keyboardFactory.callback("🏠 Меню", "menu:main")
                    )));
        } else {
            sendSquadCard(user, squad);
        }
    }

    private void sendSquadCard(AppUser user, ru.gamebot.platform.domain.model.Squad squad) {
        List<ru.gamebot.platform.domain.model.AppUser> members = squadService.getMembers(squad);
        long weeklyXp = members.stream().mapToLong(ru.gamebot.platform.domain.model.AppUser::getWeeklyXp).sum();
        boolean isCaptain = user.getTelegramId().equals(squad.getCaptainTelegramId());

        StringBuilder sb = new StringBuilder();
        sb.append("⚔️ <b>Отряд «").append(escape(squad.getName())).append("»</b>\n\n");
        sb.append("👥 Состав (").append(members.size()).append("/5):\n");
        for (ru.gamebot.platform.domain.model.AppUser m : members) {
            String crown = m.getTelegramId().equals(squad.getCaptainTelegramId()) ? " 👑" : "";
            sb.append("• <b>").append(escape(m.getNickname())).append("</b>")
                    .append(crown)
                    .append(" — ").append(String.format("%,d", m.getWeeklyXp()).replace(',', ' ')).append(" XP\n");
        }
        sb.append("\n📊 XP отряда за неделю: <b>")
                .append(String.format("%,d", weeklyXp).replace(',', ' ')).append("</b>\n\n");
        sb.append("🎁 Топ-отряд каждую неделю получает <b>10 000 EXC</b>");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (isCaptain) {
            rows.add(List.of(keyboardFactory.callback("📤 Пригласить (ссылка)", "squad:invite")));
            if (members.size() > 1) {
                rows.add(List.of(keyboardFactory.callback("👢 Исключить участника", "squad:kick_list")));
            }
            rows.add(List.of(keyboardFactory.callback("🔴 Расформировать отряд", "squad:disband_confirm")));
        } else {
            rows.add(List.of(keyboardFactory.callback("🚪 Покинуть отряд", "squad:leave_confirm")));
        }
        rows.add(List.of(keyboardFactory.callback("🏆 Рейтинг отрядов", "squad:leaderboard")));
        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));

        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void handleWheelAction(CallbackQuery callbackQuery, AppUser user, String action) {
        answerSilently(callbackQuery.getId());
        switch (action) {
            case "menu" -> sendWheelMenu(user);
            case "spin" -> {
                try {
                    ru.gamebot.platform.service.WheelService.SpinResult result = wheelService.spin(user);
                    String msg = "🎰 <b>Колесо остановилось на...</b>\n\n"
                            + "<b>" + result.label() + "</b>!\n\n";
                    if ("EXC".equals(result.type())) {
                        msg += "💰 EXC зачислены на ваш счёт.";
                    } else if ("BOOST_24H".equals(result.type())) {
                        msg += "⚡ XP-буст активирован на 24 часа!";
                    } else if ("AVATAR_FRAME".equals(result.type())) {
                        msg += "✨ Рамка аватара применена к вашему профилю!";
                    }
                    int remaining = (int) user.getTickets();
                    msg += "\n\n🎟 Осталось билетов: <b>" + remaining + "</b>";
                    InlineKeyboardMarkup kb;
                    if (remaining > 0) {
                        kb = keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("🎰 Крутить ещё", "wheel:spin")),
                                List.of(keyboardFactory.callback("⬅️ Назад", "menu:main"))
                        ));
                    } else {
                        kb = keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("⬅️ Назад", "menu:main"))
                        ));
                    }
                    sendText(user.getTelegramId(), msg, kb);
                } catch (IllegalArgumentException e) {
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), keyboardFactory.rowsLayout(List.of(
                            List.of(keyboardFactory.callback("⬅️ Назад", "menu:main"))
                    )));
                }
            }
            default -> sendWheelMenu(user);
        }
    }

    private void sendWheelMenu(AppUser user) {
        int tickets = (int) user.getTickets();
        String text = "🎰 <b>Колесо фортуны</b>\n\n"
                + "У вас: 🎟 <b>" + tickets + " билет(ов)</b>\n\n"
                + "Призы:\n"
                + "🥉 50 EXC — 30%\n"
                + "🥉 100 EXC — 25%\n"
                + "🥈 300 EXC — 20%\n"
                + "🥈 500 EXC — 12%\n"
                + "🥇 1 000 EXC — 8%\n"
                + "🥇 2 000 EXC — 3%\n"
                + "💎 XP-буст 24ч — 1.5%\n"
                + "👑 Рамка аватара — 0.5%\n\n"
                + "<i>Билеты получаешь за выполнение квестов и серию входов в бот.</i>\n"
                + "<i>Лимит: " + ru.gamebot.platform.service.WheelService.MAX_SPINS_PER_DAY + " кручений в сутки.</i>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (tickets > 0) {
            rows.add(List.of(keyboardFactory.callback("🎰 Крутить (−1 🎟)", "wheel:spin")));
        } else {
            rows.add(List.of(keyboardFactory.callback("🎟 Нет билетов", "wheel:menu")));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:main")));
        sendText(user.getTelegramId(), text, keyboardFactory.rowsLayout(rows));
    }

    private void handleSquadAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        switch (action) {
            case "soon" -> {
                sendText(user.getTelegramId(),
                        "🔒 <b>Отряды — Скоро!</b>\n\nФункция появится в следующем обновлении. Следи за каналом!",
                        backMenuKeyboard("menu:main"));
                answerSilently(callbackQuery.getId());
            }
            case "create" -> {
                if (user.getSquadId() != null) {
                    answer(callbackQuery.getId(), "Вы уже в отряде");
                    return;
                }
                session.setState(SessionState.SQUAD_CREATE_NAME);
                sendText(user.getTelegramId(),
                        "⚔️ <b>Создание отряда</b>\n\n"
                                + "Придумайте название отряда (до 30 символов).\n"
                                + "Название должно быть уникальным.",
                        backMenuKeyboard("menu:squads"));
                answerSilently(callbackQuery.getId());
            }
            case "join_prompt" -> {
                if (user.getSquadId() != null) {
                    answer(callbackQuery.getId(), "Вы уже в отряде");
                    return;
                }
                session.setState(SessionState.SQUAD_JOIN_CODE);
                sendText(user.getTelegramId(),
                        "🔗 <b>Вступить в отряд</b>\n\nВведите код приглашения (8 символов):",
                        backMenuKeyboard("menu:squads"));
                answerSilently(callbackQuery.getId());
            }
            case "invite" -> {
                ru.gamebot.platform.domain.model.Squad squad = squadService.findByUser(user).orElse(null);
                if (squad == null) { answerSilently(callbackQuery.getId()); return; }
                String link = "https://t.me/" + appProperties.getBotUsername() + "?start=squad_" + squad.getInviteCode();
                sendText(user.getTelegramId(),
                        "📤 <b>Ссылка для вступления в отряд</b>\n\n"
                                + "Отправьте другу:\n" + escape(link) + "\n\n"
                                + "Код: <code>" + squad.getInviteCode() + "</code>\n"
                                + "Он также может ввести код через «Вступить по коду».",
                        backMenuKeyboard("menu:squads"));
                answerSilently(callbackQuery.getId());
            }
            case "leaderboard" -> {
                sendSquadLeaderboard(user);
                answerSilently(callbackQuery.getId());
            }
            case "leave_confirm" -> {
                sendText(user.getTelegramId(),
                        "🚪 Вы уверены, что хотите покинуть отряд?\n\nЕсли вы капитан — капитанство перейдёт следующему участнику.",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("✅ Покинуть", "squad:leave"), keyboardFactory.callback("❌ Отмена", "menu:squads"))
                        )));
                answerSilently(callbackQuery.getId());
            }
            case "leave" -> {
                try {
                    squadService.leave(user);
                    userService.save(user);
                    sendText(user.getTelegramId(), "✅ Вы покинули отряд.", backMenuKeyboard("menu:squads"));
                } catch (Exception e) {
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:squads"));
                }
                answerSilently(callbackQuery.getId());
            }
            case "disband_confirm" -> {
                sendText(user.getTelegramId(),
                        "🔴 Вы уверены, что хотите расформировать отряд?\n\nВсе участники будут исключены.",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("✅ Расформировать", "squad:disband"), keyboardFactory.callback("❌ Отмена", "menu:squads"))
                        )));
                answerSilently(callbackQuery.getId());
            }
            case "disband" -> {
                try {
                    squadService.disband(user);
                    userService.save(user);
                    sendText(user.getTelegramId(), "✅ Отряд расформирован.", backMenuKeyboard("menu:squads"));
                } catch (Exception e) {
                    sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:squads"));
                }
                answerSilently(callbackQuery.getId());
            }
            case "kick_list" -> {
                ru.gamebot.platform.domain.model.Squad squad = squadService.findByUser(user).orElse(null);
                if (squad == null || !user.getTelegramId().equals(squad.getCaptainTelegramId())) {
                    answerSilently(callbackQuery.getId()); return;
                }
                List<ru.gamebot.platform.domain.model.AppUser> members = squadService.getMembers(squad);
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (ru.gamebot.platform.domain.model.AppUser m : members) {
                    if (!m.getTelegramId().equals(user.getTelegramId())) {
                        rows.add(List.of(keyboardFactory.callback(
                                "👢 " + escape(m.getNickname()),
                                "squad:kick:" + m.getTelegramId()
                        )));
                    }
                }
                rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:squads")));
                sendText(user.getTelegramId(), "👢 <b>Исключить участника</b>\n\nВыберите кого исключить:", keyboardFactory.rowsLayout(rows));
                answerSilently(callbackQuery.getId());
            }
            default -> {
                if (action.startsWith("kick:")) {
                    Long targetId = parseLong(action.substring("kick:".length()));
                    if (targetId == null) { answerSilently(callbackQuery.getId()); return; }
                    try {
                        squadService.kick(user, targetId);
                        sendText(user.getTelegramId(), "✅ Участник исключён из отряда.", backMenuKeyboard("menu:squads"));
                        notifyUser(targetId, "⚠️ Вас исключили из отряда. Вы можете вступить в другой или создать свой.");
                    } catch (Exception e) {
                        sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:squads"));
                    }
                    answerSilently(callbackQuery.getId());
                } else {
                    answer(callbackQuery.getId(), "Неизвестное действие отряда");
                }
            }
        }
    }

    private void sendSquadLeaderboard(AppUser user) {
        List<ru.gamebot.platform.service.SquadService.SquadRankEntry> top = squadService.getLeaderboard();
        if (top.isEmpty()) {
            sendText(user.getTelegramId(),
                    "🏆 <b>Рейтинг отрядов</b>\n\nПока нет активных отрядов с XP на этой неделе.\n\nСоздайте отряд и заработайте XP вместе!",
                    backMenuKeyboard("menu:squads"));
            return;
        }
        StringBuilder sb = new StringBuilder("🏆 <b>Топ отрядов — эта неделя</b>\n\n");
        String[] medals = {"🥇", "🥈", "🥉"};
        int rank = 1;
        for (ru.gamebot.platform.service.SquadService.SquadRankEntry entry : top) {
            String medal = rank <= 3 ? medals[rank - 1] : rank + ".";
            sb.append(medal).append(" <b>").append(escape(entry.squad().getName())).append("</b>")
                    .append(" — ").append(String.format("%,d", entry.weeklyXp()).replace(',', ' ')).append(" XP")
                    .append(" (").append(entry.memberCount()).append(" чел.)\n");
            rank++;
        }
        sb.append("\n🎁 Каждую неделю топ-отряд получает <b>10 000 EXC</b>");
        sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:squads"));
    }

    private void handleSquadCreateNameInput(AppUser user, UserSession session, String text) {
        session.setState(SessionState.NONE);
        String name = text.trim();
        if (name.length() < 2 || name.length() > 30) {
            sendText(user.getTelegramId(), "⚠️ Название должно быть от 2 до 30 символов.", backMenuKeyboard("menu:squads"));
            return;
        }
        try {
            ru.gamebot.platform.domain.model.Squad squad = squadService.create(user, name);
            userService.save(user);
            String inviteLink = "https://t.me/" + appProperties.getBotUsername() + "?start=squad_" + squad.getInviteCode();
            sendText(user.getTelegramId(),
                    "⚔️ <b>Отряд «" + escape(squad.getName()) + "» создан!</b>\n\n"
                            + "Вы капитан. Пригласите от 1 до 4 друзей.\n\n"
                            + "📤 Ссылка для вступления:\n" + escape(inviteLink) + "\n\n"
                            + "Код: <code>" + squad.getInviteCode() + "</code>",
                    backMenuKeyboard("menu:squads"));
        } catch (Exception e) {
            sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:squads"));
        }
    }

    private void handleSquadJoinCodeInput(AppUser user, UserSession session, String text) {
        session.setState(SessionState.NONE);
        try {
            ru.gamebot.platform.domain.model.Squad squad = squadService.joinByInviteCode(user, text.trim());
            userService.save(user);
            sendText(user.getTelegramId(),
                    "⚔️ <b>Вы вступили в отряд «" + escape(squad.getName()) + "»!</b>\n\n"
                            + "Зарабатывайте XP вместе — топ-отряд получает 10 000 EXC каждую неделю!",
                    backMenuKeyboard("menu:squads"));
        } catch (Exception e) {
            sendText(user.getTelegramId(), "⚠️ " + e.getMessage(), backMenuKeyboard("menu:squads"));
        }
    }

    // ─── Support ──────────────────────────────────────────────────────────────

    private void sendSupport(AppUser user) {
        sendText(user.getTelegramId(),
                "🆘 <b>Поддержка</b>\n\n"
                        + "Напишите нам — вопрос, проблема, предложение.\n"
                        + "Все ваши сообщения попадают в один диалог, не нужно открывать новую заявку каждый раз.\n\n"
                        + "Ответ придёт прямо сюда.",
                keyboardFactory.verticalLayout(List.of(
                        keyboardFactory.callback("✍️ Написать в поддержку", "support:new"),
                        keyboardFactory.callback("📬 История диалогов", "support:list"),
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
                        List.of(keyboardFactory.callback("🔍 Поиск игрока", "mod:usersearch")),
                        List.of(keyboardFactory.callback("🏠 Главное меню", "menu:main"))
                )));
    }

    private void handleSupportAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        switch (action) {
            case "new" -> {
                clearSupportDraft(session);
                session.setState(SessionState.SUPPORT_INPUT);
                sendText(user.getTelegramId(),
                        "💬 <b>Поддержка</b>\n\n"
                                + "Напишите ваш вопрос или проблему.\n"
                                + "Можно отправлять текст, фото, видео и документы — всё попадёт в один диалог.\n\n"
                                + "Ответ придёт прямо сюда.",
                        backMenuKeyboard("menu:support"));
                answerSilently(callbackQuery.getId());
            }
            case "list" -> {
                sendUserSupportTickets(user);
                answerSilently(callbackQuery.getId());
            }
            case "close_chat" -> {
                Long ticketId = session.getSupportTicketId();
                clearSupportDraft(session);
                if (ticketId != null) {
                    try {
                        supportService.closeTicket(ticketId, user.getTelegramId());
                    } catch (Exception ignored) {}
                    // Уведомить модераторов о закрытии диалога пользователем
                    String modMsg = "✅ <b>Диалог закрыт пользователем</b>\n\n"
                            + "👤 <b>" + escape(user.getNickname()) + "</b> (" + user.getTelegramId() + ")\n"
                            + "🎫 Заявка #" + ticketId;
                    for (Long modId : adminService.strictModeratorIds()) {
                        try {
                            sendText(modId, modMsg, keyboardFactory.rowsLayout(List.of(
                                    List.of(keyboardFactory.callback("👁️ Открыть", "mod:support:view:" + ticketId))
                            )));
                        } catch (Exception e) {
                            log.warn("Failed to notify moderator {} about closed ticket {}", modId, ticketId, e);
                        }
                    }
                }
                sendText(user.getTelegramId(),
                        "✅ Диалог завершён.\n\nЕсли понадобится помощь снова — просто напишите.",
                        keyboardFactory.rowsLayout(List.of(
                                List.of(keyboardFactory.callback("✍️ Написать снова", "support:new")),
                                List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
                        )));
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
        boolean mediaGroupContinuation = mediaGroupId != null
                && session.getSupportTicketId() != null
                && mediaGroupId.equals(session.getData().get("support_media_group_id"));

        SupportTicket ticket;
        boolean isNewTicket;
        if (mediaGroupContinuation) {
            ticket = supportService.getTicket(session.getSupportTicketId());
            isNewTicket = false;
        } else {
            Long existingId = session.getSupportTicketId();
            ticket = supportService.getOrCreateActiveTicket(user, content.text(), mediaGroupId);
            isNewTicket = existingId == null || !ticket.getId().equals(existingId);
            session.setSupportTicketId(ticket.getId());
            if (mediaGroupId != null) {
                session.getData().put("support_media_group_id", mediaGroupId);
            } else {
                session.getData().remove("support_media_group_id");
            }
        }

        if (!"text".equals(content.mediaType()) || (content.text() != null && !content.text().isBlank())) {
            supportService.addAttachment(ticket, false, content.mediaType(), content.fileId(), content.text());
        }

        notifyModeratorsAboutSupportTicket(ticket, content, !isNewTicket);

        // Keep session alive so user can continue sending messages in the same ticket
        session.setState(SessionState.SUPPORT_INPUT);

        InlineKeyboardMarkup chatKeyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🔚 Завершить диалог", "support:close_chat")),
                List.of(
                        keyboardFactory.callback("📬 Мои заявки", "support:list"),
                        keyboardFactory.callback("🏠 Меню", "menu:main")
                )
        ));

        if (mediaGroupContinuation) {
            // silent — already showed a message for first file of group
        } else if (isNewTicket) {
            sendText(user.getTelegramId(),
                    "✅ <b>Диалог открыт</b>\n\n"
                            + "Ваше сообщение (#" + ticket.getId() + ") принято в поддержку.\n"
                            + "Можете продолжать писать — все сообщения попадут в один чат.\n"
                            + "Ответ придёт прямо сюда.",
                    chatKeyboard);
        } else {
            sendText(user.getTelegramId(),
                    "💬 Сообщение добавлено в диалог #" + ticket.getId() + ".",
                    chatKeyboard);
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

        // Build conversation history
        StringBuilder history = new StringBuilder();
        history.append("🆘 <b>Диалог #").append(ticket.getId()).append("</b>\n\n")
                .append("👤 Игрок: <b>").append(escape(ticket.getUser().getNickname())).append("</b>\n")
                .append("🆔 ID: <b>").append(ticket.getUser().getTelegramId()).append("</b>\n")
                .append("📌 Статус: <b>").append(escape(humanSupportStatus(ticket.getStatus().name()))).append("</b>\n")
                .append("🕒 Создан: <b>").append(escape(ticket.getCreatedAt().format(DATE_TIME_FORMATTER))).append("</b>\n\n")
                .append("─────────────────\n");

        for (SupportAttachment att : attachments) {
            String who = att.isFromModerator() ? "🛡️ Модератор" : "👤 Игрок";
            String time = att.getCreatedAt().format(DATE_TIME_FORMATTER);
            history.append(who).append(" · ").append(time).append("\n");
            if (att.getCaption() != null && !att.getCaption().isBlank()) {
                history.append(escape(trim(att.getCaption(), 200)));
            }
            if (att.getFileId() != null) {
                history.append(" [").append(att.getMediaType()).append("]");
            }
            history.append("\n");
        }
        history.append("─────────────────");

        sendText(chatId, history.toString(),
                verticalWithBackMenu(List.of(
                        keyboardFactory.callback("✍️ Ответить", "mod:support:reply:" + ticketId),
                        keyboardFactory.callback("✅ Закрыть", "mod:support:close:" + ticketId)
                ), "⬅️ Назад", "mod:support:list"));

        // Send media attachments separately
        for (SupportAttachment att : attachments) {
            if (att.getFileId() != null) {
                try {
                    String caption = (att.isFromModerator() ? "🛡️ " : "👤 ")
                            + (att.getCaption() != null ? att.getCaption() : "");
                    sendContent(chatId, new IncomingContent(att.getMediaType(), att.getFileId(), caption), caption, null);
                } catch (Exception e) {
                    log.warn("Failed to send support attachment {} to moderator {}", att.getId(), chatId, e);
                }
            }
        }
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
                        + "• Success rate больше 90% + интервал между заявками меньше 60 сек\n"
                        + "• Отчёт отправлен быстрее чем через 30 мин после взятия квеста\n\n"
                        + "Проверьте вручную и снимите флаг если игрок честный.",
                keyboardFactory.smartLayout(buttons));
    }

    private static final String[] QUICK_REJECT_LABELS = {
            "❌ Не по теме",
            "❌ Ник не совпадает",
            "❌ Недостаточно данных"
    };

    private static final RejectionReasonCode[] QUICK_REJECT_CODES = {
            RejectionReasonCode.NOT_RELEVANT,
            RejectionReasonCode.NICKNAME_MISMATCH,
            RejectionReasonCode.INSUFFICIENT_DATA
    };

    private List<InlineKeyboardButton> quickRejectButtons(Long submissionId) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (int i = 0; i < QUICK_REJECT_LABELS.length; i++) {
            buttons.add(keyboardFactory.callback(QUICK_REJECT_LABELS[i], "mod:rejtpl:" + i + ":" + submissionId));
        }
        return buttons;
    }

    /** Подставляет реальные данные заявки в шаблон отклонения (см. {@link AppProperties}). */
    private String fillRejectTemplate(String template, QuestSubmission submission) {
        Quest quest = submission.getQuest();
        String shortCondition = quest.getShortCondition();
        if (shortCondition == null || shortCondition.isBlank()) {
            shortCondition = quest.getRequirements() != null ? quest.getRequirements() : "см. описание задания";
        }
        String nickname = submission.getUser().getNickname();
        return template
                .replace("{название_задания}", quest.getTitle())
                .replace("{никнейм_в_боте}", nickname != null ? nickname : "—")
                .replace("{краткое_условие_задания}", shortCondition);
    }

    private String rejectTemplateFor(RejectionReasonCode code) {
        return switch (code) {
            case NOT_RELEVANT -> appProperties.getRejectTemplateNotRelevant();
            case NICKNAME_MISMATCH -> appProperties.getRejectTemplateNicknameMismatch();
            case INSUFFICIENT_DATA -> appProperties.getRejectTemplateInsufficientData();
            case OTHER -> throw new IllegalArgumentException("OTHER не имеет шаблона — это свободный текст");
        };
    }

    private void handleModerationQuickReject(CallbackQuery callbackQuery, AppUser moderator, int templateIndex, Long submissionId) {
        if (templateIndex < 0 || templateIndex >= QUICK_REJECT_CODES.length) {
            answer(callbackQuery.getId(), "Неизвестный шаблон");
            return;
        }
        RejectionReasonCode reasonCode = QUICK_REJECT_CODES[templateIndex];
        QuestSubmission current = questService.getSubmission(submissionId);
        String message = fillRejectTemplate(rejectTemplateFor(reasonCode), current);
        QuestSubmission submission = questService.rejectSubmission(submissionId, message, reasonCode, moderator.getTelegramId());
        notifyUser(submission.getUser().getTelegramId(),
                "⚠️ Отчёт по квесту <b>" + escape(submission.getQuest().getTitle()) + "</b> отклонён.\n\n"
                        + escape(submission.getModeratorComment()));
        sendModerationQueue(callbackQuery.getFrom().getId());
        answer(callbackQuery.getId(), "Отклонено: " + QUICK_REJECT_LABELS[templateIndex]);
    }

    private void sendSubmissionCard(Long chatId, Long submissionId) {
        QuestSubmission submission = questService.getSubmission(submissionId);
        AppUser submitter = submission.getUser();
        String submitterLink = submitter.getTelegramUsername() != null
                ? "<a href=\"https://t.me/" + submitter.getTelegramUsername() + "\">@" + submitter.getTelegramUsername() + "</a>"
                : "<a href=\"tg://user?id=" + submitter.getTelegramId() + "\">" + escape(submitter.getNickname()) + "</a>";
        String dupWarning = submission.isDuplicatePhotoDetected()
                ? "\n🚨 <b>ДУБЛЬ СКРИНШОТА!</b> Этот файл уже использовался в отчёте другого игрока.\n"
                : "";
        String caption = "🧾 <b>Заявка К-" + (submission.getDisplayId() != null ? submission.getDisplayId() : submission.getId()) + " на проверку</b>\n\n"
                + "👤 Игрок: <b>" + escape(submitter.getNickname()) + "</b> (" + submitterLink + ")\n"
                + "🆔 ID: <b>" + submitter.getTelegramId() + "</b>\n"
                + "🎯 Квест: <b>" + escape(submission.getQuest().getTitle()) + "</b>\n"
                + "🎮 Игра: <b>" + escape(submission.getQuest().getGameName()) + "</b>\n"
                + rewardPreviewLine(submission) + "\n"
                + "📅 Отправлено: <b>" + escape(submission.getUpdatedAt().format(DATE_TIME_FORMATTER)) + "</b>\n"
                + "💬 Комментарий: " + escape(submission.getUserComment()) + "\n"
                + (submission.getExternalLink() == null ? "" : "🔗 Ссылка: " + escape(submission.getExternalLink()) + "\n")
                + dupWarning;

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
        // Используем computeReward (с учётом снижения) — то же что применит approveSubmission
        QuestService.RewardPreview computed = questService.computeReward(currentSubmission.getUser(), currentSubmission.getQuest());
        UserService.RewardGrant rewardGrant = userService.previewReward(
                currentSubmission.getUser(),
                computed.xp(),
                computed.coins(),
                0
        );
        boolean isFirstQuest = currentSubmission.getUser().getCompletedQuests() == 0;
        QuestSubmission submission = questService.approveSubmission(submissionId);
        String firstQuestBonus = isFirstQuest && submission.getUser().getReferredByTelegramId() != null
                ? "\n🎁 Бонус за первый квест: <b>+3 000 EXC</b>" : "";
        try {
            notifyUser(submission.getUser().getTelegramId(),
                    "🎉 Ваш отчёт по квесту <b>" + escape(submission.getQuest().getTitle()) + "</b> одобрен!\n\n"
                            + "✨ XP: <b>+" + rewardGrant.xp() + "</b>\n"
                            + "🪙 EXC: <b>+" + rewardGrant.totalExc() + "</b>\n"
                            + formatExcBonusLine(rewardGrant)
                            + firstQuestBonus);
        } catch (Exception e) {
            log.warn("Could not notify user {} about quest approval: {}", submission.getUser().getTelegramId(), e.getMessage());
        }
        sendModerationQueue(callbackQuery.getFrom().getId());
        answer(callbackQuery.getId(), "Заявка одобрена");
    }

    private void handleModerationReject(CallbackQuery callbackQuery, AppUser user, Long submissionId) {
        List<InlineKeyboardButton> reasonButtons = new ArrayList<>(quickRejectButtons(submissionId));
        reasonButtons.add(keyboardFactory.callback("✏️ Своя причина", "mod:no-custom:" + submissionId));
        InlineKeyboardMarkup markup = verticalWithBackMenu(reasonButtons, "⬅️ Назад", "mod:no-back:" + submissionId);
        answer(callbackQuery.getId(), "Выберите причину");
        sendText(user.getTelegramId(),
                "❌ <b>Причина отклонения</b>\n\nВыберите готовую причину или укажите свою:",
                markup);
    }

    private void handleModerationRejectCustom(CallbackQuery callbackQuery, AppUser user, UserSession session, Long submissionId) {
        session.reset();
        session.setState(SessionState.QUEST_REJECT_COMMENT);
        session.setQuestId(submissionId);
        answer(callbackQuery.getId(), "Укажите причину");
        sendText(user.getTelegramId(),
                "❌ <b>Отклонение отчёта</b>\n\nНапишите причину отклонения — она будет отправлена игроку, чтобы он понимал, что исправить:",
                cancelKeyboard());
    }

    private void handleModerationRejectBack(CallbackQuery callbackQuery, Long submissionId) {
        answer(callbackQuery.getId(), "Отменено");
        sendSubmissionCard(callbackQuery.getFrom().getId(), submissionId);
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
            case "stats:platform" -> sendAdminStatsPlatform(user);
            case "stats:topquests" -> sendAdminStatsTopQuests(user);
            case "stats:history" -> sendAdminStatsHistory(user);
            case "stats:snapshot" -> {
                platformSnapshotService.takeSnapshot();
                sendAdminStatsPlatform(user);
                answer(callbackQuery.getId(), "📸 Снепшот сохранён");
                return;
            }
            case "stats:reset_weekly" -> sendAdminResetWeeklyConfirm(user);
            case "stats:reset_weekly:confirm" -> doAdminResetWeeklyXp(user);
            case "live" -> sendAdminLiveStatus(user);
            case "queststats" -> sendAdminQuestStats(user);
            case "onetimeabuse" -> sendAdminOneTimeQuestAbuse(user);
            case "clashtags" -> sendAdminClashTagsList(user);
            case "autoquest-activity" -> sendAdminAutoQuestActivity(user);
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
            case "postpay" -> { sendAdminPostpayList(user); answerSilently(callbackQuery.getId()); return; }
            case "postpay:create" -> {
                session.reset();
                session.getData().put("postpay", "1");
                session.setState(SessionState.POSTPAY_CREATE_NAME);
                sendText(user.getTelegramId(),
                        "📋 <b>Новый квест под отчёт</b>\n\nВведите название компании/спонсора:",
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
            case "traffic:batch" -> {
                session.reset();
                session.setState(SessionState.TRAFFIC_BATCH_COUNT);
                sendText(user.getTelegramId(),
                        "📦 <b>Пачка ссылок</b>\n\nСколько новых уникальных ссылок сгенерировать? Введите число от 1 до 50.",
                        cancelKeyboard());
            }
            case "traffic:batch:confirm" -> {
                String countStr = session.getData().get("batchCount");
                session.reset();
                Integer count = countStr != null ? parseInteger(countStr) : null;
                if (count == null || count < 1 || count > 50) {
                    sendText(user.getTelegramId(), "❌ Не удалось создать пачку — попробуйте ещё раз.", backMenuKeyboard("admin:traffic"));
                    answerSilently(callbackQuery.getId());
                    return;
                }
                List<ru.gamebot.platform.domain.model.TrafficSource> batch = trafficSourceService.createBatch(count);
                StringBuilder sb = new StringBuilder("✅ <b>Создано ссылок: " + batch.size() + "</b>\n\n");
                int idx = 1;
                for (ru.gamebot.platform.domain.model.TrafficSource ts : batch) {
                    sb.append(idx++).append(". <code>https://t.me/").append(appProperties.getBotUsername())
                      .append("?start=src_").append(ts.getCode()).append("</code>\n");
                }
                sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("admin:traffic"));
                answerSilently(callbackQuery.getId());
                return;
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
                } else if ("quests:section:gaming".equals(action)) {
                    sendAdminGamingQuestList(user);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if ("quests:section:sponsored".equals(action)) {
                    sendAdminSponsoredQuestList(user);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if ("quests:section:ugc".equals(action)) {
                    sendAdminUgcQuestList(user);
                    answerSilently(callbackQuery.getId());
                    return;
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
                    answer(callbackQuery.getId(), "Введите TG ID или ник");
                    sendText(user.getTelegramId(), "🔍 <b>Поиск пользователя</b>\n\nВведите Telegram ID или никнейм:", cancelKeyboard());
                } else if ("users:post".equals(action)) {
                    sendAdminUsersPostCard(user);
                } else if (action.startsWith("users:")) {
                    sendAdminUsersPage(user, parseInteger(action.substring("users:".length())));
                } else if ("bonussearch".equals(action)) {
                    session.setState(SessionState.BONUS_SEARCH);
                    answer(callbackQuery.getId(), "Введите TG ID или ник");
                    sendText(user.getTelegramId(), "🔍 <b>Поиск игрока для начисления бонуса</b>\n\nВведите Telegram ID или никнейм:", cancelKeyboard());
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
                    long qid = parseLong(action.substring("edit-title:".length()));
                    session.reset();
                    session.setQuestId(qid);
                    session.setState(SessionState.QUEST_EDIT_TITLE);
                    ru.gamebot.platform.domain.model.Quest qCur = questService.getQuest(qid);
                    sendText(user.getTelegramId(),
                            "✏️ <b>Изменить название</b>\n\nСейчас: <i>" + escape(qCur.getTitle()) + "</i>\n\nОтправьте новое название:",
                            cancelKeyboard());
                } else if (action.startsWith("edit-description:")) {
                    long qid = parseLong(action.substring("edit-description:".length()));
                    session.reset();
                    session.setQuestId(qid);
                    session.setState(SessionState.QUEST_EDIT_DESCRIPTION);
                    ru.gamebot.platform.domain.model.Quest qCur = questService.getQuest(qid);
                    sendText(user.getTelegramId(),
                            "📝 <b>Изменить описание</b>\n\nСейчас:\n<i>" + escape(qCur.getDescription()) + "</i>\n\nОтправьте новое описание:",
                            cancelKeyboard());
                } else if (action.startsWith("edit-condition:")) {
                    long qid = parseLong(action.substring("edit-condition:".length()));
                    session.reset();
                    session.setQuestId(qid);
                    session.setState(SessionState.QUEST_EDIT_CONDITION);
                    ru.gamebot.platform.domain.model.Quest qCur = questService.getQuest(qid);
                    String currentCondition = qCur.getShortCondition() != null ? qCur.getShortCondition() : "—";
                    sendText(user.getTelegramId(),
                            "📋 <b>Краткое условие</b>\n\nИспользуется в шаблоне быстрого отклонения «Недостаточно данных» "
                                    + "(до ~150 символов, например: «нужен скриншот итогового экрана боя»).\n\n"
                                    + "Сейчас: <i>" + escape(currentCondition) + "</i>\n\nОтправьте новый текст условия:",
                            cancelKeyboard());
                } else if (action.startsWith("edit-reward:")) {
                    long qid = parseLong(action.substring("edit-reward:".length()));
                    session.reset();
                    session.setQuestId(qid);
                    session.setState(SessionState.QUEST_EDIT_REWARD);
                    ru.gamebot.platform.domain.model.Quest qCur = questService.getQuest(qid);
                    sendText(user.getTelegramId(),
                            "✨ <b>Изменить награды</b>\n\nСейчас: <i>" + qCur.getRewardXp() + " XP, " + qCur.getRewardCoins() + " EXC</i>\n\nОтправьте новые награды в формате:\n<code>XP COINS</code>\n\nПример: <code>150 250</code>",
                            cancelKeyboard());
                } else if (action.startsWith("edit-category:")) {
                    long qid = parseLong(action.substring("edit-category:".length()));
                    session.reset();
                    session.setQuestId(qid);
                    session.setState(SessionState.QUEST_EDIT_CATEGORY);
                    ru.gamebot.platform.domain.model.Quest qCur = questService.getQuest(qid);
                    sendText(user.getTelegramId(),
                            "📚 <b>Изменить категорию</b>\n\nСейчас: <i>" + escape(qCur.getCategory()) + "</i>\n\nВыберите новую категорию:",
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
                    long qid = parseLong(action.substring("edit-limit:".length()));
                    session.reset();
                    session.setQuestId(qid);
                    session.setState(SessionState.QUEST_EDIT_LIMIT);
                    ru.gamebot.platform.domain.model.Quest qCur = questService.getQuest(qid);
                    String curLimit = qCur.getParticipantLimit() == null ? "не задан" : String.valueOf(qCur.getParticipantLimit());
                    sendText(user.getTelegramId(),
                            "👥 <b>Изменить лимит участников</b>\n\nСейчас: <i>" + curLimit + "</i>\n\nУкажите новый лимит числом:",
                            cancelKeyboard());
                } else if (action.startsWith("game:top:")) {
                    String gameName = decodeGameToken(action.substring("game:top:".length()));
                    sendAdminGameQuestTop(user, gameName);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("game:mode:set:tiered:")) {
                    String gameName = decodeGameToken(action.substring("game:mode:set:tiered:".length()));
                    gameCatalogService.setDifficultyMode(gameName, "TIERED", 1500, 50);
                    answer(callbackQuery.getId(), "Режим TIERED установлен");
                    sendAdminQuestCategories(user, gameName);
                    return;
                } else if (action.startsWith("game:mode:set:flat:xp:")) {
                    // format: game:mode:set:flat:xp:<gameName>
                    String gameName = decodeGameToken(action.substring("game:mode:set:flat:xp:".length()));
                    session.reset();
                    session.setState(SessionState.GAME_FLAT_XP);
                    session.getData().put("gameModeName", gameName);
                    sendText(user.getTelegramId(),
                            "✨ <b>Введите XP за квест для FLAT-режима игры «" + escape(gameName) + "»:</b>",
                            cancelKeyboard());
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("game:mode:")) {
                    String gameName = decodeGameToken(action.substring("game:mode:".length()));
                    boolean flat = gameCatalogService.isFlat(gameName);
                    List<List<InlineKeyboardButton>> modeRows = new ArrayList<>();
                    if (flat) {
                        long curExc = gameCatalogService.getFlatRewardExc(gameName);
                        int curXp = gameCatalogService.getFlatRewardXp(gameName);
                        modeRows.add(List.of(keyboardFactory.callback("✏️ Изменить награды", "admin:game:mode:set:flat:xp:" + encodeGameToken(gameName))));
                        modeRows.add(List.of(keyboardFactory.callback("🔄 Переключить в TIERED", "admin:game:mode:set:tiered:" + encodeGameToken(gameName))));
                        modeRows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:quests:game:" + encodeGameToken(gameName))));
                        sendText(user.getTelegramId(),
                                "⚙️ <b>Режим квестов: " + escape(gameName) + "</b>\n\n"
                                        + "Текущий режим: <b>FLAT</b>\n"
                                        + "Фиксированная награда: <b>" + curXp + " XP / " + curExc + " EXC</b>\n\n"
                                        + "В FLAT-режиме все квесты этой игры не имеют категории сложности и получают одинаковую награду.",
                                keyboardFactory.rowsLayout(modeRows));
                    } else {
                        modeRows.add(List.of(keyboardFactory.callback("🔄 Переключить в FLAT", "admin:game:mode:set:flat:xp:" + encodeGameToken(gameName))));
                        modeRows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:quests:game:" + encodeGameToken(gameName))));
                        sendText(user.getTelegramId(),
                                "⚙️ <b>Режим квестов: " + escape(gameName) + "</b>\n\n"
                                        + "Текущий режим: <b>TIERED</b> (Лёгкие / Средние / Сложные)\n\n"
                                        + "В TIERED-режиме каждый квест имеет свою категорию сложности с индивидуальной наградой.",
                                keyboardFactory.rowsLayout(modeRows));
                    }
                    answerSilently(callbackQuery.getId());
                    return;
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
                } else if (action.startsWith("sponsors:delete:")) {
                    sponsorService.deleteCampaign(parseLong(action.substring("sponsors:delete:".length())));
                    answer(callbackQuery.getId(), "Кампания удалена.");
                    sendAdminSponsorList(user);
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
                        q.setActive(false);
                        q.setSponsored(false);
                        q.setSponsorId(null);
                        questService.save(q);
                        answer(callbackQuery.getId(), "Квест откреплён и скрыт от пользователей.");
                        if (sid > 0) sendAdminSponsorView(user, sid);
                        else sendAdminSponsorList(user);
                    } catch (Exception e) {
                        answer(callbackQuery.getId(), "❌ " + e.getMessage());
                    }
                    return;
                } else if (action.startsWith("sponsors:newquest:")) {
                    long sponsorId = parseLong(action.substring("sponsors:newquest:".length()));
                    session.reset();
                    session.getData().put("sponsorId", String.valueOf(sponsorId));
                    session.setState(SessionState.SPONSOR_QUEST_TITLE);
                    sendText(user.getTelegramId(), "➕ <b>Создание спонсорского квеста</b>\n\n1️⃣ Введите название квеста:", cancelKeyboard());
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("sponsors:addquest:")) {
                    long sponsorId = parseLong(action.substring("sponsors:addquest:".length()));
                    sendSponsorQuestPicker(user, sponsorId);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("sq-edit:")) {
                    String[] parts = action.substring("sq-edit:".length()).split(":");
                    long sid = parseLong(parts[0]);
                    long qid = parseLong(parts[1]);
                    sendSponsorQuestEditor(user, qid, "admin:sponsors:view:" + sid);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("pp-edit:")) {
                    String[] parts = action.substring("pp-edit:".length()).split(":");
                    long sid = parseLong(parts[0]);
                    long qid = parseLong(parts[1]);
                    sendSponsorQuestEditor(user, qid, "admin:postpay:view:" + sid);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("sq-edit-note:")) {
                    long qid = parseLong(action.substring("sq-edit-note:".length()));
                    session.setQuestId(qid);
                    session.setState(SessionState.QUEST_EDIT_DESCRIPTION);
                    sendText(user.getTelegramId(), "📝 Введите новое примечание (или <code>0</code> — очистить):", cancelKeyboard());
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("postpay:view:")) {
                    sendAdminPostpayView(user, parseLong(action.substring("postpay:view:".length())));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("postpay:newquest:")) {
                    long sponsorId = parseLong(action.substring("postpay:newquest:".length()));
                    session.reset();
                    session.getData().put("sponsorId", String.valueOf(sponsorId));
                    session.getData().put("postpay", "1");
                    session.setState(SessionState.SPONSOR_QUEST_TITLE);
                    sendText(user.getTelegramId(), "➕ <b>Создание квеста под отчёт</b>\n\n1️⃣ Введите название квеста:", cancelKeyboard());
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("postpay:addquest:")) {
                    sendPostpayQuestPicker(user, parseLong(action.substring("postpay:addquest:".length())));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("postpay:link:")) {
                    String[] parts = action.substring("postpay:link:".length()).split(":");
                    long sponsorId = parseLong(parts[0]);
                    long questId = parseLong(parts[1]);
                    try {
                        ru.gamebot.platform.domain.model.Quest q = questService.getQuest(questId);
                        q.setSponsored(true);
                        q.setSponsorId(sponsorId);
                        questService.save(q);
                        answer(callbackQuery.getId(), "Квест привязан.");
                        sendAdminPostpayView(user, sponsorId);
                    } catch (Exception e) { answer(callbackQuery.getId(), "❌ " + e.getMessage()); }
                    return;
                } else if (action.startsWith("postpay:unlink:")) {
                    long questId = parseLong(action.substring("postpay:unlink:".length()));
                    try {
                        ru.gamebot.platform.domain.model.Quest q = questService.getQuest(questId);
                        long sid = q.getSponsorId() != null ? q.getSponsorId() : 0;
                        q.setActive(false);
                        q.setSponsored(false);
                        q.setSponsorId(null);
                        questService.save(q);
                        answer(callbackQuery.getId(), "Квест откреплён и скрыт от пользователей.");
                        if (sid > 0) sendAdminPostpayView(user, sid);
                        else sendAdminPostpayList(user);
                    } catch (Exception e) { answer(callbackQuery.getId(), "❌ " + e.getMessage()); }
                    return;
                } else if (action.startsWith("postpay:delete:")) {
                    sponsorService.deleteCampaign(parseLong(action.substring("postpay:delete:".length())));
                    answer(callbackQuery.getId(), "Кампания удалена.");
                    sendAdminPostpayList(user);
                    return;
                } else if (action.startsWith("postpay:close:")) {
                    sponsorService.deactivate(parseLong(action.substring("postpay:close:".length())));
                    answer(callbackQuery.getId(), "Кампания закрыта.");
                    sendAdminPostpayList(user);
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
                } else if (action.startsWith("tournaments:brawlparticipants:")) {
                    sendAdminBrawlParticipants(user, parseLong(action.substring("tournaments:brawlparticipants:".length())));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("tournaments:resnapshot:")) {
                    long entryId = parseLong(action.substring("tournaments:resnapshot:".length()));
                    boolean ok = brawlStarsTournamentService.reSnapshotEntry(entryId);
                    tournamentEntryRepository.findById(entryId).ifPresent(e -> sendAdminBrawlParticipants(user, e.getTournament().getId()));
                    answer(callbackQuery.getId(), ok ? "✅ Снапшот обновлён" : "❌ Не удалось получить данные");
                    return;
                } else if (action.startsWith("tournaments:delete_confirm:")) {
                    long tid = parseLong(action.substring("tournaments:delete_confirm:".length()));
                    tournamentService.findById(tid).ifPresentOrElse(t -> sendText(user.getTelegramId(),
                            "🗑️ Удалить турнир «" + escape(t.getName()) + "» безвозвратно?\n\n"
                                    + "Все заявки участников и история этого турнира будут удалены. Взносы игрокам НЕ возвращаются автоматически.",
                            keyboardFactory.rowsLayout(List.of(
                                    List.of(keyboardFactory.callback("✅ Да, удалить", "admin:tournaments:delete:" + tid),
                                            keyboardFactory.callback("❌ Отмена", "admin:tournaments:view:" + tid))
                            ))), () -> sendText(user.getTelegramId(), "❌ Турнир не найден.", backMenuKeyboard("admin:tournaments")));
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("tournaments:delete:")) {
                    long tid = parseLong(action.substring("tournaments:delete:".length()));
                    tournamentService.delete(tid);
                    answer(callbackQuery.getId(), "🗑️ Турнир удалён.");
                    sendAdminTournamentList(user);
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
                } else if ("broadcast:scheduled".equals(action)) {
                    sendAdminScheduledBroadcasts(user);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("broadcast:scheduled:view:")) {
                    long scheduledId = parseLong(action.substring("broadcast:scheduled:view:".length()));
                    sendAdminScheduledBroadcastView(user, scheduledId);
                    answerSilently(callbackQuery.getId());
                    return;
                } else if (action.startsWith("broadcast:scheduled:sendnow:")) {
                    long scheduledId = parseLong(action.substring("broadcast:scheduled:sendnow:".length()));
                    boolean triggered = scheduledBroadcastService.triggerNow(scheduledId);
                    answer(callbackQuery.getId(), triggered ? "📤 Рассылка отправляется…" : "⚠️ Уже отправлена или отменена.");
                    sendAdminScheduledBroadcasts(user);
                    return;
                } else if (action.startsWith("broadcast:scheduled:cancel:")) {
                    long scheduledId = parseLong(action.substring("broadcast:scheduled:cancel:".length()));
                    boolean cancelled = scheduledBroadcastService.cancel(scheduledId);
                    answer(callbackQuery.getId(), cancelled ? "🗑 Рассылка отменена." : "⚠️ Уже отправлена или отменена.");
                    sendAdminScheduledBroadcasts(user);
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
            rows.add(List.of(keyboardFactory.callback(game, "admin:qt:game:" + game)));
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

    private void finalizeTournamentCreation(AppUser user, UserSession session) {
        Map<String, String> d = session.getData();
        String tName = d.get("tName");
        String tGame = d.get("tGame");
        long fee = Long.parseLong(d.get("tFee"));
        java.time.format.DateTimeFormatter dtFmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        java.time.LocalDateTime startDate = java.time.LocalDateTime.parse(d.get("tStart"), dtFmt);
        java.time.LocalDateTime endDate = java.time.LocalDateTime.parse(d.get("tEnd"), dtFmt);
        String minStr = d.get("tMinParticipants");
        Integer minParticipants = (minStr != null && !minStr.isBlank()) ? Integer.parseInt(minStr) : null;
        ru.gamebot.platform.domain.model.Tournament t = tournamentService.create(
                tName, tGame, fee, startDate, endDate, minParticipants, d.get("tPhotoFileId"));
        session.reset();
        String minLine = minParticipants != null
                ? "👥 Минимум участников: <b>" + minParticipants + "</b> (иначе турнир отменится, взносы вернутся)\n"
                : "";
        String text = "✅ <b>Турнир создан!</b>\n\n"
                + "📌 " + escape(t.getName()) + "\n"
                + "💰 Взнос: <b>" + t.getEntryFeeExc() + " EXC</b>\n"
                + minLine
                + "🔒 Закрытие регистрации / старт: " + t.getStartDate().format(dtFmt) + " (UTC)\n"
                + "⏰ Финиш: " + t.getEndDate().format(dtFmt) + " (UTC)\n\n"
                + "Регистрация уже открыта — турнир виден пользователям прямо сейчас.";
        InlineKeyboardMarkup keyboard = backMenuKeyboard("admin:tournaments");
        if (t.getPhotoFileId() != null) {
            sendPhotoCaption(user.getTelegramId(), t.getPhotoFileId(), text, keyboard);
        } else {
            sendText(user.getTelegramId(), text, keyboard);
        }
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
        if (action.startsWith("history:")) {
            int pg = 0;
            try { pg = Integer.parseInt(action.substring("history:".length())); } catch (NumberFormatException ignored) {}
            sendAdminWithdrawalHistory(user, pg);
            answerSilently(callbackQuery.getId());
            return;
        }
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
        if (action.startsWith("multiblock:")) {
            String[] parts = action.substring("multiblock:".length()).split(":");
            long reqId = parseLong(parts[0]);
            long otherTgId = parseLong(parts[1]);
            String blockReason = "Мультиаккаунт — нарушение п. 6 Правил EGC";
            RewardRequest req = rewardService.rejectRequest(reqId, blockReason);
            AppUser requester = req.getUser();
            AppUser other = userService.findByTelegramId(otherTgId).orElse(null);
            userService.blockAndConfiscate(requester.getTelegramId(), blockReason);
            String otherNick = "(неизвестен)";
            if (other != null) {
                userService.blockAndConfiscate(other.getTelegramId(), blockReason);
                otherNick = other.getNickname();
            }
            notifyUserWithdrawalRejected(req);
            answer(callbackQuery.getId(), "🚫 Аккаунты заблокированы");
            sendText(user.getTelegramId(),
                    "🚫 <b>Готово</b>\n\n"
                    + "Заявка В-" + reqDisplayId(req) + " отклонена.\n"
                    + "Заблокированы аккаунты:\n"
                    + "• <b>" + escape(requester.getNickname()) + "</b>\n"
                    + "• <b>" + escape(otherNick) + "</b>\n\n"
                    + "Причина: " + blockReason,
                    keyboardFactory.rowsLayout(List.of(
                            List.of(keyboardFactory.callback("⬅️ К заявкам", "admin:withdrawals"))
                    )));
            return;
        }
        sendAdminWithdrawals(user);
        answerSilently(callbackQuery.getId());
    }

    /** true — заявка на вывод в криптовалюту (TON, либо старый формат "USDT·TON" до перехода), не в рублях. */
    private boolean isCryptoWithdrawal(RewardRequest req) {
        String details = req.getPayoutDetails();
        return details != null && (details.startsWith("TON") || details.startsWith("USDT"));
    }

    private String cryptoWalletFromPayoutDetails(String payoutDetails) {
        String[] parts = payoutDetails.split(":");
        return parts.length > 1 ? parts[1] : payoutDetails;
    }

    /** Сохраняет историческую точность для старых заявок, оформленных ещё в USDT. */
    private String cryptoMethodLabel(String payoutDetails) {
        return payoutDetails.startsWith("USDT") ? "USDT · TON" : "GRAM (TON)";
    }

    /** ~ количество монет GRAM (TON) по живому курсу — для карточек заявки на вывод у админа/модератора. */
    private String cryptoPayoutSuffix(long rubles) {
        java.math.BigDecimal tonRate = exchangeRateService.getTonRubRate();
        java.math.BigDecimal tonAmount = exchangeRateService.rubToTon(java.math.BigDecimal.valueOf(rubles));
        return " (~<b>" + tonAmount + " GRAM</b>, курс 1 GRAM ≈ " + tonRate.setScale(2, java.math.RoundingMode.HALF_DOWN) + " ₽)";
    }

    private void sendAdminWithdrawals(AppUser user) {
        List<RewardRequest> pending = rewardService.findPendingWithdrawals();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (RewardRequest req : pending) {
            String uname = req.getUser().getTelegramUsername() != null
                    ? "@" + req.getUser().getTelegramUsername()
                    : "#" + req.getUser().getTelegramId();
            String type = isCryptoWithdrawal(req) ? "💎 TON" : "💸 ₽";
            rows.add(List.of(keyboardFactory.callback(
                    "В-" + reqDisplayId(req) + " " + uname + " — " + type + " " + req.getRewardItem().getPriceCoins() + " EXC",
                    "admin:withdrawal:req:" + req.getId())));
        }
        rows.add(List.of(
                keyboardFactory.callback("📋 История", "admin:withdrawal:history:0"),
                keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        String header = pending.isEmpty()
                ? "💸 <b>Заявки на вывод EXC</b>\n\nНет новых заявок."
                : "💸 <b>Заявки на вывод EXC</b>\n\nОжидают обработки: <b>" + pending.size() + "</b>";
        sendText(user.getTelegramId(), header, keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminScheduledBroadcasts(AppUser user) {
        List<ru.gamebot.platform.domain.model.ScheduledBroadcast> pending = scheduledBroadcastService.findPending();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.ScheduledBroadcast b : pending) {
            String preview = b.getText() != null ? b.getText()
                    : ((b.getCaption() == null || b.getCaption().isBlank()) ? "[фото]" : "[фото] " + b.getCaption());
            if (preview.length() > 40) preview = preview.substring(0, 40) + "…";
            rows.add(List.of(keyboardFactory.callback(
                    "🕒 " + b.getScheduledAt().format(fmt) + " — " + preview,
                    "admin:broadcast:scheduled:view:" + b.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        String header = pending.isEmpty()
                ? "📅 <b>Запланированные рассылки</b>\n\nНет запланированных рассылок."
                : "📅 <b>Запланированные рассылки</b>\n\nНажми на рассылку, чтобы посмотреть её и отправить сейчас или отменить.";
        sendText(user.getTelegramId(), header, keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminScheduledBroadcastView(AppUser user, long id) {
        ru.gamebot.platform.domain.model.ScheduledBroadcast b = scheduledBroadcastService.getById(id);
        if (b == null) {
            sendText(user.getTelegramId(), "❌ Рассылка не найдена.", backMenuKeyboard("admin:broadcast:scheduled"));
            return;
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        String content = b.getText() != null
                ? escape(b.getText())
                : "[фото]" + ((b.getCaption() == null || b.getCaption().isBlank()) ? "" : "\n" + escape(b.getCaption()));
        String statusLine = switch (b.getStatus()) {
            case PENDING -> "⏳ Ожидает отправки";
            case SENT -> "✅ Уже отправлена (" + b.getDeliveredCount() + " получателей)";
            case CANCELLED -> "🗑 Отменена";
        };
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (b.getStatus() == ru.gamebot.platform.domain.enums.ScheduledBroadcastStatus.PENDING) {
            rows.add(List.of(keyboardFactory.callback("📤 Отправить сейчас", "admin:broadcast:scheduled:sendnow:" + b.getId())));
            rows.add(List.of(keyboardFactory.callback("🗑 Отменить", "admin:broadcast:scheduled:cancel:" + b.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:broadcast:scheduled")));
        sendText(user.getTelegramId(),
                "📅 <b>Запланированная рассылка</b>\n\n"
                        + "🕒 Время: <b>" + b.getScheduledAt().format(fmt) + " (UTC)</b>\n"
                        + "📌 Статус: " + statusLine + "\n\n"
                        + content,
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminWithdrawalHistory(AppUser user, int page) {
        org.springframework.data.domain.Page<RewardRequest> histPage =
                rewardService.findWithdrawalHistory(Math.max(0, page));
        List<RewardRequest> items = histPage.getContent();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");
        StringBuilder sb = new StringBuilder("📋 <b>История заявок на вывод</b>");
        if (histPage.getTotalPages() > 1) {
            sb.append(" (стр. ").append(page + 1).append("/").append(histPage.getTotalPages()).append(")");
        }
        sb.append("\n\n");
        if (items.isEmpty()) {
            sb.append("Заявок пока нет.");
        } else {
            for (RewardRequest req : items) {
                String statusEmoji = switch (req.getStatus()) {
                    case PENDING -> "⏳";
                    case IN_PROGRESS -> "🔄";
                    case APPROVED -> "✅";
                    case REJECTED -> "❌";
                    case CANCELLED -> "🚫";
                };
                String uname = req.getUser().getTelegramUsername() != null
                        ? "@" + req.getUser().getTelegramUsername()
                        : "#" + req.getUser().getTelegramId();
                String type = isCryptoWithdrawal(req) ? "💎" : "💸";
                String nick = escape(req.getUser().getNickname());
                if (nick.length() > 12) nick = nick.substring(0, 12) + "…";
                sb.append(statusEmoji).append(" <b>В-").append(reqDisplayId(req)).append("</b> ")
                  .append(type).append(" ").append(req.getRewardItem().getPriceCoins()).append(" EXC")
                  .append(" | ").append(nick).append(" (").append(escape(uname)).append(")")
                  .append(" | ").append(req.getCreatedAt().format(fmt)).append("\n");
            }
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (histPage.hasPrevious()) navRow.add(keyboardFactory.callback("⬅️", "admin:withdrawal:history:" + (page - 1)));
        if (histPage.hasNext()) navRow.add(keyboardFactory.callback("➡️", "admin:withdrawal:history:" + (page + 1)));
        if (!navRow.isEmpty()) rows.add(navRow);
        rows.add(List.of(keyboardFactory.callback("⬅️ К заявкам", "admin:withdrawals")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminWithdrawalCard(AppUser user, Long reqId) {
        RewardRequest req = rewardService.getRequest(reqId);
        AppUser requester = req.getUser();
        String unameLink = requester.getTelegramUsername() != null
                ? "<a href=\"https://t.me/" + requester.getTelegramUsername() + "\">@" + requester.getTelegramUsername() + "</a>"
                : "<a href=\"tg://user?id=" + requester.getTelegramId() + "\">" + requester.getTelegramId() + "</a>";
        String detailsLine;
        if (isCryptoWithdrawal(req)) {
            String wallet = cryptoWalletFromPayoutDetails(req.getPayoutDetails());
            detailsLine = "\n💎 Способ: <b>" + cryptoMethodLabel(req.getPayoutDetails()) + "</b>\n📬 Кошелёк: <code>" + escape(wallet) + "</code>";
        } else if (req.getPayoutDetails() != null) {
            detailsLine = "\n💵 Способ: <b>Рубли (СБП / Сбербанк)</b>\n💳 Реквизиты: <code>" + escape(req.getPayoutDetails()) + "</code>";
        } else {
            detailsLine = "\n💵 Способ: <b>Рубли (СБП / Сбербанк)</b>";
        }
        long rubles = fixedOrCurrentRub(req);
        String payoutSuffix = isCryptoWithdrawal(req) ? cryptoPayoutSuffix(rubles) : "";
        long duplicateCount = rewardService.countPendingWithdrawalsByUser(requester);
        String duplicateWarning = duplicateCount > 1
                ? "\n\n⚠️ <b>ВНИМАНИЕ: у этого пользователя " + duplicateCount + " активные заявки на вывод!</b> Оплачивайте только эту." : "";
        java.util.List<RewardRequest> destDups = rewardService.findDuplicateDestinationWithdrawals(req);
        String destDupWarning = destDups.isEmpty() ? "" : "\n\n🚨 <b>МУЛЬТИАККАУНТ!</b> Этот реквизит уже получил выплату другой аккаунт: <b>"
                + escape(destDups.get(0).getUser().getNickname()) + "</b> (В-" + reqDisplayId(destDups.get(0)) + ", "
                + destDups.get(0).getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) + ")";
        String phoneLine = requester.getPhoneNumber() != null
                ? "\n📱 Телефон: <code>" + escape(requester.getPhoneNumber()) + "</code>"
                : "\n📱 Телефон: <b>не подтверждён</b>";
        java.util.Optional<AppUser> phoneDupUser = userService.findDuplicatePhoneUser(requester.getPhoneNumber(), requester.getTelegramId());
        String phoneDupWarning = phoneDupUser.isPresent()
                ? "\n\n🚨 <b>МУЛЬТИАККАУНТ!</b> Этот номер телефона уже зарегистрирован на аккаунте: <b>" + escape(phoneDupUser.get().getNickname()) + "</b>"
                : "";
        java.util.Optional<AppUser> multiblockTarget = destDups.isEmpty()
                ? phoneDupUser
                : java.util.Optional.of(destDups.get(0).getUser());
        List<List<InlineKeyboardButton>> adminWdRows = new ArrayList<>();
        adminWdRows.add(List.of(
                keyboardFactory.callback("✅ Выплачено", "admin:withdrawal:approve:" + req.getId()),
                keyboardFactory.callback("❌ Отклонить", "admin:withdrawal:reject:" + req.getId())
        ));
        if (multiblockTarget.isPresent()) {
            adminWdRows.add(List.of(keyboardFactory.callback(
                    "🚫 Отклонить + заблокировать оба аккаунта",
                    "admin:withdrawal:multiblock:" + req.getId() + ":" + multiblockTarget.get().getTelegramId())));
        }
        adminWdRows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:withdrawals")));
        sendText(user.getTelegramId(),
                "💸 <b>Заявка на вывод В-" + reqDisplayId(req) + "</b>\n\n"
                        + "👤 Игрок: <b>" + escape(requester.getNickname()) + "</b> (" + unameLink + ")\n"
                        + "🆔 Telegram ID: <b>" + requester.getTelegramId() + "</b>\n"
                        + "🌍 Страна: <b>" + escape(requester.getCountry() != null ? requester.getCountry() : "Не указана") + "</b>\n"
                        + phoneLine + "\n"
                        + "🪙 Сумма: <b>" + req.getRewardItem().getPriceCoins() + " EXC</b>\n"
                        + "💵 К выплате: <b>~" + rubles + " ₽</b>" + payoutSuffix
                        + detailsLine + "\n"
                        + monthlyLimitLine(requester)
                        + "📅 Дата: <b>" + req.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) + "</b>"
                        + duplicateWarning + destDupWarning + phoneDupWarning,
                keyboardFactory.rowsLayout(adminWdRows));
    }

    /** Строка "остаток месячного лимита" для карточки заявки на вывод (эта заявка уже учтена в счётчике на момент подачи). */
    private String monthlyLimitLine(AppUser requester) {
        long limit = sinkShopService.getMonthlyLimit(requester.getXp());
        long remaining = sinkShopService.getRemainingWithdrawalLimit(requester);
        long used = limit - remaining;
        return "📊 Месячный лимит: <b>" + used + " / " + limit + " EXC</b> использовано (осталось " + remaining + ")\n";
    }

    private void sendPayoutConfirmedCard(AppUser admin, RewardRequest req, boolean isModFlow) {
        AppUser player = req.getUser();
        long exc = req.getRewardItem().getPriceCoins();
        String withdrawalsCallback = isModFlow ? "mod:withdrawals" : "admin:withdrawals";

        String usernameStr = player.getTelegramUsername() != null
                ? "@" + player.getTelegramUsername() : escape(player.getNickname());
        String levelName = escape(userService.getLevelName(player.getXp()));
        long questsDone = questService.countApprovedByUser(player);
        long totalEarned = questService.sumEarnedCoinsByUser(player);

        long rubForLine = fixedOrCurrentRub(req);
        String withdrawLine;
        if (isCryptoWithdrawal(req)) {
            java.math.BigDecimal tonAmount = exchangeRateService.rubToTon(java.math.BigDecimal.valueOf(rubForLine));
            withdrawLine = exc + " EXC → ~" + tonAmount + " GRAM";
        } else {
            withdrawLine = exc + " EXC → " + rubForLine + " ₽";
        }

        String text = "🎉 <b>Перевод выполнен!</b>\n\n"
                + "Игрок: <b>" + usernameStr + "</b>\n"
                + "Уровень: <b>" + levelName + "</b>\n"
                + "Номер заявки: <b>В-" + reqDisplayId(req) + "</b>\n"
                + "Дата: <b>" + LocalDateTime.now().format(DATE_TIME_FORMATTER) + "</b>\n"
                + "Квестов выполнено: <b>" + questsDone + "</b>\n"
                + "Заработано: <b>" + String.format("%,d", totalEarned).replace(',', ' ') + " EXC</b>\n"
                + "Вывел: <b>" + withdrawLine + "</b>\n\n"
                + "Так держать! 🔥";
        sendText(admin.getTelegramId(), text,
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("📋 Заявки на вывод", withdrawalsCallback)),
                        List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
                )));
    }

    private long parseRubFromPayoutDetails(String payoutDetails, long excFallback) {
        if (payoutDetails != null && payoutDetails.contains("rubles=")) {
            try {
                String after = payoutDetails.substring(payoutDetails.indexOf("rubles=") + 7);
                return Long.parseLong(after.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {}
        }
        return excFallback / 100;
    }

    private long parseRubFromWithdrawalTitle(String title, long excFallback) {
        if (title != null && title.contains("→")) {
            try {
                String after = title.substring(title.lastIndexOf('→') + 1).trim();
                return Long.parseLong(after.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {}
        }
        return excFallback / 100;
    }

    private void notifyUserWithdrawalApproved(RewardRequest req, String receiptFileId) {
        // Было: isUsdt = payoutDetails != null — ловило и рублёвые реквизиты тоже, не только крипту. Исправлено.
        String method = isCryptoWithdrawal(req) ? cryptoMethodLabel(req.getPayoutDetails()) : "рубли (СБП / Сбербанк)";
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
        postWithdrawalToActivityFeed(req);
        promptWithdrawalReview(req);
    }

    /** Публичная лента активности — крупные выводы EXC постятся в основной канал (не @egc_payouts,
     * тот зарезервирован под технические AdsGram/отзыв-уведомления). Без порога — выводов исторически
     * мало (десятки уникальных получателей за всё время), спама не будет. Найдено 2026-09-02 при
     * разборе рекомендаций по вовлечённости канала.
     * Публикация — только после одобрения администратора (см. {@link #handleAdminFeedAction}). */
    private String buildWithdrawalFeedText(RewardRequest req) {
        return "💸 Игрок <b>" + escape(req.getUser().getNickname()) + "</b> вывел <b>"
                + req.getRewardItem().getPriceCoins() + " EXC</b>!\n\n"
                + "Больше отзывов: @egc_payouts";
    }

    private void postWithdrawalToActivityFeed(RewardRequest req) {
        pendingWithdrawalTexts.put(req.getId(), buildWithdrawalFeedText(req));
        sendWithdrawalFeedCard(req.getId());
    }

    private void sendWithdrawalFeedCard(long reqId) {
        String text = pendingWithdrawalTexts.get(reqId);
        if (text == null) return;
        String preview = "🧾 <b>Лента активности — на согласование</b>\n\n" + text;
        InlineKeyboardMarkup markup = keyboardFactory.smartLayout(List.of(
                keyboardFactory.callback("✅ Опубликовать", "adminfeed:withdrawal:approve:" + reqId),
                keyboardFactory.callback("✏️ Изменить", "adminfeed:withdrawal:edit:" + reqId),
                keyboardFactory.callback("❌ Отклонить", "adminfeed:withdrawal:reject:" + reqId)));
        for (Long adminId : adminService.resolvedAdminIds()) {
            try {
                sendText(adminId, preview, markup);
            } catch (Exception e) {
                log.warn("Failed to send withdrawal feed candidate to admin {}", adminId, e);
            }
        }
    }

    // ── Отзывы после вывода EXC ─────────────────────────────────────────────

    /** По желанию игрока — просит оценить качество вывода 1-5 звёзд после его закрытия.
     * Собранный отзыв уходит на модерацию ({@link #sendReviewModerationCard}), затем публикуется
     * в канал отзывов ({@link #publishReviewToChannel}). */
    private void promptWithdrawalReview(RewardRequest req) {
        Long telegramId = req.getUser().getTelegramId();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                keyboardFactory.callback("⭐️⭐️⭐️⭐️⭐️", "review:stars:" + req.getId() + ":5"),
                keyboardFactory.callback("⭐️⭐️⭐️⭐️", "review:stars:" + req.getId() + ":4")));
        rows.add(List.of(
                keyboardFactory.callback("⭐️⭐️⭐️", "review:stars:" + req.getId() + ":3"),
                keyboardFactory.callback("⭐️⭐️", "review:stars:" + req.getId() + ":2"),
                keyboardFactory.callback("⭐️", "review:stars:" + req.getId() + ":1")));
        rows.add(List.of(keyboardFactory.callback("Не сейчас", "review:skip:" + req.getId())));
        sendText(telegramId,
                "🙏 Оцени, пожалуйста, качество вывода — это необязательно, но очень помогает клубу.",
                keyboardFactory.rowsLayout(rows));
    }

    private void handleReviewAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if (action.startsWith("stars:")) {
            String[] parts = action.substring("stars:".length()).split(":");
            long reqId = Long.parseLong(parts[0]);
            int stars = Integer.parseInt(parts[1]);
            session.reset();
            session.getData().put("reviewReqId", String.valueOf(reqId));
            session.getData().put("reviewStars", String.valueOf(stars));
            session.setState(SessionState.WITHDRAWAL_REVIEW_TEXT);
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "Спасибо!");
            sendText(user.getTelegramId(),
                    "Спасибо за оценку! Хочешь добавить пару слов и/или скриншот? Отправь текст и/или фото, а затем нажми «Готово» — можно нажать сразу, без текста.",
                    keyboardFactory.rowsLayout(List.of(List.of(keyboardFactory.callback("✅ Готово", "review:finish")))));
            return;
        }
        if (action.startsWith("skip:")) {
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "Хорошо, спасибо!");
            return;
        }
        if (action.equals("finish")) {
            if (session.getState() != SessionState.WITHDRAWAL_REVIEW_TEXT) {
                answerSilently(callbackQuery.getId());
                return;
            }
            finalizeWithdrawalReview(user, session);
            clearInlineKeyboard(callbackQuery);
            answerSilently(callbackQuery.getId());
        }
    }

    private void finalizeWithdrawalReview(AppUser user, UserSession session) {
        String reqIdStr = session.getData().get("reviewReqId");
        String starsStr = session.getData().get("reviewStars");
        if (reqIdStr == null || starsStr == null) {
            session.reset();
            return;
        }
        BotReview review = new BotReview();
        review.setUser(user);
        review.setRewardRequestId(Long.parseLong(reqIdStr));
        review.setStars(Integer.parseInt(starsStr));
        review.setText(session.getData().get("reviewText"));
        review.setPhotoFileId(session.getData().get("reviewPhotoFileId"));
        review.setStatus(ru.gamebot.platform.domain.enums.BotReviewStatus.PENDING);
        review.setCreatedAt(LocalDateTime.now());
        review = botReviewRepository.save(review);
        session.reset();
        sendText(user.getTelegramId(), "🙏 Спасибо за отзыв! Он появится в канале после проверки модератором.",
                backMenuKeyboard("menu:main"));
        sendReviewModerationCard(review);
    }

    private void sendReviewModerationCard(BotReview review) {
        String stars = "⭐️".repeat(Math.max(0, Math.min(5, review.getStars())));
        String reviewText = review.getText();
        String caption = "🧾 <b>Новый отзыв</b>\n\n"
                + "👤 " + escape(review.getUser().getNickname()) + " · " + stars + "\n"
                + (reviewText != null && !reviewText.isBlank() ? "\"" + escape(reviewText) + "\"" : "<i>без текста</i>");
        InlineKeyboardMarkup markup = keyboardFactory.smartLayout(List.of(
                keyboardFactory.callback("✅ Опубликовать", "revmod:approve:" + review.getId()),
                keyboardFactory.callback("❌ Отклонить", "revmod:reject:" + review.getId())));
        for (Long recipient : adminService.allModeratorIds()) {
            try {
                if (review.getPhotoFileId() != null) {
                    SendPhoto sendPhoto = new SendPhoto();
                    sendPhoto.setChatId(recipient.toString());
                    sendPhoto.setPhoto(new InputFile(review.getPhotoFileId()));
                    sendPhoto.setCaption(caption);
                    sendPhoto.setParseMode("HTML");
                    sendPhoto.setReplyMarkup(markup);
                    execute(sendPhoto);
                } else {
                    SendMessage msg = new SendMessage();
                    msg.setChatId(recipient.toString());
                    msg.setText(caption);
                    msg.setParseMode("HTML");
                    msg.setReplyMarkup(markup);
                    execute(msg);
                }
            } catch (TelegramApiException e) {
                log.warn("Failed to send review moderation card to {}", recipient, e);
            }
        }
    }

    private void handleReviewModAction(CallbackQuery callbackQuery, String action) {
        if (action.startsWith("approve:")) {
            long id = parseLong(action.substring("approve:".length()));
            botReviewRepository.findWithUserById(id).ifPresent(review -> {
                if (review.getStatus() != ru.gamebot.platform.domain.enums.BotReviewStatus.PENDING) return;
                review.setStatus(ru.gamebot.platform.domain.enums.BotReviewStatus.PUBLISHED);
                botReviewRepository.save(review);
                publishReviewToChannel(review);
            });
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "✅ Опубликовано");
            return;
        }
        if (action.startsWith("reject:")) {
            long id = parseLong(action.substring("reject:".length()));
            botReviewRepository.findById(id).ifPresent(review -> {
                if (review.getStatus() != ru.gamebot.platform.domain.enums.BotReviewStatus.PENDING) return;
                review.setStatus(ru.gamebot.platform.domain.enums.BotReviewStatus.REJECTED);
                botReviewRepository.save(review);
            });
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "❌ Отклонено");
        }
    }

    /** Согласование автопостов канала администратором — тизер отрядов, лента крупных выводов,
     * авто-опросы. Введено 2026-09-02 по явному запросу: ничего из этих трёх не должно публиковаться
     * без одобрения. Дополнено возможностью правки текста прямо перед одобрением/отклонением. */
    private void handleAdminFeedAction(CallbackQuery callbackQuery, AppUser user, UserSession session, String action) {
        if (action.equals("squad:approve")) {
            String text = pendingSquadTeaserText;
            if (text != null) {
                try {
                    SendMessage msg = new SendMessage();
                    msg.setChatId(requiredChannelChatId());
                    msg.setText(text);
                    msg.setParseMode("HTML");
                    execute(msg);
                } catch (Exception e) {
                    log.error("Failed to post approved squad teaser to channel", e);
                }
            }
            pendingSquadTeaserText = null;
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "✅ Опубликовано");
            return;
        }
        if (action.equals("squad:edit")) {
            session.reset();
            session.setState(SessionState.ADMINFEED_EDIT);
            session.getData().put("editTarget", "squad");
            answerSilently(callbackQuery.getId());
            sendText(user.getTelegramId(),
                    "✏️ Текущий текст:\n\n" + (pendingSquadTeaserText != null ? pendingSquadTeaserText : "—")
                            + "\n\nПришлите новый текст поста:",
                    cancelKeyboard());
            return;
        }
        if (action.equals("squad:reject")) {
            pendingSquadTeaserText = null;
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "❌ Отклонено");
            return;
        }
        if (action.startsWith("withdrawal:approve:")) {
            long reqId = parseLong(action.substring("withdrawal:approve:".length()));
            String text = pendingWithdrawalTexts.remove(reqId);
            try {
                if (text == null) {
                    text = buildWithdrawalFeedText(rewardService.getRequest(reqId));
                }
                SendMessage msg = new SendMessage();
                msg.setChatId(requiredChannelChatId());
                msg.setText(text);
                msg.setParseMode("HTML");
                execute(msg);
            } catch (Exception e) {
                log.error("Failed to post approved withdrawal to activity feed", e);
            }
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "✅ Опубликовано");
            return;
        }
        if (action.startsWith("withdrawal:edit:")) {
            long reqId = parseLong(action.substring("withdrawal:edit:".length()));
            session.reset();
            session.setState(SessionState.ADMINFEED_EDIT);
            session.getData().put("editTarget", "withdrawal:" + reqId);
            answerSilently(callbackQuery.getId());
            String current = pendingWithdrawalTexts.get(reqId);
            sendText(user.getTelegramId(),
                    "✏️ Текущий текст:\n\n" + (current != null ? current : "—")
                            + "\n\nПришлите новый текст поста:",
                    cancelKeyboard());
            return;
        }
        if (action.startsWith("withdrawal:reject:")) {
            long reqId = parseLong(action.substring("withdrawal:reject:".length()));
            pendingWithdrawalTexts.remove(reqId);
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "❌ Отклонено");
            return;
        }
        if (action.equals("poll:approve")) {
            PendingPollCandidate candidate = pendingPollCandidate;
            if (candidate == null) {
                clearInlineKeyboard(callbackQuery);
                answer(callbackQuery.getId(), "Уже обработано");
                return;
            }
            pendingPollCandidate = null;
            ru.gamebot.platform.domain.model.Poll poll = pollService.create(
                    candidate.question(), candidate.options(), 0L, LocalDateTime.now().plusDays(2));
            onAutoPollCreated(new ru.gamebot.platform.event.AutoPollCreatedEvent(this, poll));
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "✅ Опубликовано");
            return;
        }
        if (action.equals("poll:edit")) {
            PendingPollCandidate candidate = pendingPollCandidate;
            session.reset();
            session.setState(SessionState.ADMINFEED_EDIT);
            session.getData().put("editTarget", "poll");
            answerSilently(callbackQuery.getId());
            String current = candidate != null
                    ? candidate.question() + "\n" + String.join("\n", candidate.options())
                    : "—";
            sendText(user.getTelegramId(),
                    "✏️ Текущий текст:\n\n" + current
                            + "\n\nПришлите новый текст: первая строка — вопрос, каждая следующая — вариант ответа (2-8 штук).",
                    cancelKeyboard());
            return;
        }
        if (action.equals("poll:reject")) {
            pendingPollCandidate = null;
            clearInlineKeyboard(callbackQuery);
            answer(callbackQuery.getId(), "❌ Отклонено");
        }
    }

    private void publishReviewToChannel(BotReview review) {
        String channel = appProperties.getPayoutChannelUsername();
        if (channel == null || channel.isBlank()) return;
        String stars = "⭐️".repeat(Math.max(0, Math.min(5, review.getStars())));
        AppUser reviewer = review.getUser();
        String nickname = escape(reviewer.getNickname());
        String profileLink = reviewer.getTelegramUsername() != null && !reviewer.getTelegramUsername().isBlank()
                ? "https://t.me/" + reviewer.getTelegramUsername()
                : "tg://user?id=" + reviewer.getTelegramId();
        StringBuilder sb = new StringBuilder();
        sb.append("Отзыв с бота 🤖\n");
        sb.append("Игрок: <b><a href=\"").append(profileLink).append("\">").append(nickname).append("</a></b>\n");
        sb.append(stars);
        String reviewText = review.getText();
        if (reviewText != null && !reviewText.isBlank()) {
            sb.append("\n\n\"").append(escape(reviewText)).append("\"");
        }
        String text = sb.toString();
        try {
            if (review.getPhotoFileId() != null) {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(channel);
                photo.setPhoto(new InputFile(review.getPhotoFileId()));
                photo.setCaption(text);
                photo.setParseMode("HTML");
                execute(photo);
            } else {
                SendMessage msg = new SendMessage();
                msg.setChatId(channel);
                msg.setText(text);
                msg.setParseMode("HTML");
                execute(msg);
            }
        } catch (Exception e) {
            log.error("Failed to publish review {} to channel", review.getId(), e);
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
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(keyboardFactory.callback("🎮 Игровые квесты", "admin:quests:section:gaming")));
        rows.add(List.of(keyboardFactory.callback("💼 Спонсорские квесты", "admin:quests:section:sponsored")));
        rows.add(List.of(
                keyboardFactory.callback("🤝 Спонсоры", "admin:sponsors"),
                keyboardFactory.callback("📑 Постоплата", "admin:postpay")
        ));
        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:admin")));
        sendText(user.getTelegramId(),
                "🗂️ <b>Управление квестами</b>\n\nВыберите раздел:",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminGamingQuestList(AppUser user) {
        List<String> games = questService.findActiveGameNames();
        if (games.isEmpty()) {
            sendText(user.getTelegramId(),
                    "🗂️ <b>Игровые квесты</b>\n\nПока нет квестов, распределённых по играм.",
                    backMenuKeyboard("admin:edit"));
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String game : games) {
            long count = questService.countActiveByGameName(game);
            rows.add(List.of(keyboardFactory.callback(trim(game, 28) + " (" + count + ")", "admin:quests:game:" + encodeGameToken(game))));
        }
        rows.add(List.of(
                keyboardFactory.callback("⬅️ Назад", "admin:edit"),
                keyboardFactory.callback("🏠 Меню", "menu:admin")
        ));
        sendText(user.getTelegramId(),
                "🗂️ <b>Игровые квесты</b>\n\nВыберите игру:",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminSponsoredQuestList(AppUser user) {
        List<Quest> quests = questService.findAllSponsored();
        if (quests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "💼 <b>Спонсорские квесты</b>\n\nНет активных спонсорских квестов.",
                    backMenuKeyboard("admin:edit"));
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Quest q : quests) {
            buttons.add(keyboardFactory.callback("🎯 " + trim(q.getTitle(), 32), "admin:quest:" + q.getId()));
        }
        sendText(user.getTelegramId(),
                "💼 <b>Спонсорские квесты</b>\n\nВыберите квест для редактирования:",
                verticalWithBackMenu(buttons, "⬅️ Назад", "admin:edit"));
    }

    private void sendAdminUgcQuestList(AppUser user) {
        List<Quest> quests = questService.findAllUgc();
        if (quests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📋 <b>Квесты под отчёт</b>\n\nНет активных квестов под отчёт.",
                    backMenuKeyboard("admin:edit"));
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Quest q : quests) {
            buttons.add(keyboardFactory.callback("📋 " + trim(q.getTitle(), 32), "admin:quest:" + q.getId()));
        }
        sendText(user.getTelegramId(),
                "📋 <b>Квесты под отчёт</b>\n\nВыберите квест для редактирования:",
                verticalWithBackMenu(buttons, "⬅️ Назад", "admin:edit"));
    }

    private void sendAdminQuestCategories(AppUser user, String gameName) {
        if (gameName == null || gameName.isBlank()) {
            sendAdminQuestList(user);
            return;
        }

        boolean flat = gameCatalogService.isFlat(gameName);
        boolean hasPhoto = gameCatalogService.getPhotoFileId(gameName).isPresent();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (flat) {
            rows.add(List.of(keyboardFactory.callback("📚 Квесты", "admin:quests:list:" + encodeGameToken(gameName) + ":all")));
        } else {
            rows.add(List.of(
                    keyboardFactory.callback("⚡ Легкие", "admin:quests:list:" + encodeGameToken(gameName) + ":fast"),
                    keyboardFactory.callback("🎯 Средние", "admin:quests:list:" + encodeGameToken(gameName) + ":medium")
            ));
            rows.add(List.of(keyboardFactory.callback("🏰 Сложные", "admin:quests:list:" + encodeGameToken(gameName) + ":long")));
            rows.add(List.of(keyboardFactory.callback("📚 Все квесты", "admin:quests:list:" + encodeGameToken(gameName) + ":all")));
        }
        rows.add(List.of(keyboardFactory.callback("🏆 Топ квестов", "admin:game:top:" + encodeGameToken(gameName))));
        rows.add(List.of(keyboardFactory.callback(
                flat ? "⚙️ Режим: FLAT (без категорий)" : "⚙️ Режим: TIERED (категории)", "admin:game:mode:" + encodeGameToken(gameName))));

        if (hasPhoto) {
            rows.add(List.of(
                    keyboardFactory.callback("🖼 Обновить фото игры", "admin:game:photo:set:" + encodeGameToken(gameName)),
                    keyboardFactory.callback("🗑 Удалить фото", "admin:game:photo:remove:" + encodeGameToken(gameName))
            ));
        } else {
            rows.add(List.of(keyboardFactory.callback("🖼 Добавить фото игры", "admin:game:photo:set:" + encodeGameToken(gameName))));
        }

        rows.add(List.of(
                keyboardFactory.callback("⬅️ Назад", "admin:quests:section:gaming"),
                keyboardFactory.callback("🏠 Меню", "menu:admin")
        ));

        String photoStatus = hasPhoto ? "✅ Фото установлено" : "📷 Фото не добавлено";
        String modeStatus = flat
                ? "⚙️ Режим: FLAT · " + gameCatalogService.getFlatRewardXp(gameName) + " XP / " + gameCatalogService.getFlatRewardExc(gameName) + " EXC"
                : "⚙️ Режим: TIERED (Easy / Medium / Hard)";
        sendText(user.getTelegramId(),
                "🎮 <b>" + escape(gameName) + "</b>\n"
                        + photoStatus + "\n"
                        + modeStatus + "\n\n"
                        + (flat ? "Квесты без деления на категории сложности." : "Выберите категорию, чтобы открыть нужную группу квестов по этой игре."),
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminGameQuestTop(AppUser user, String gameName) {
        List<Quest> quests = questService.findAllByGameName(gameName);
        if (quests.isEmpty()) {
            sendText(user.getTelegramId(), "Квесты не найдены.", backMenuKeyboard("admin:quests:game:" + encodeGameToken(gameName)));
            return;
        }
        record QuestStat(Quest quest, long count) {}
        List<QuestStat> stats = quests.stream()
                .map(q -> new QuestStat(q, questService.countApprovedByQuest(q)))
                .sorted((a, b) -> Long.compare(b.count(), a.count()))
                .toList();

        String[] medals = {"🥇", "🥈", "🥉"};
        boolean flatTop = gameCatalogService.isFlat(gameName);
        StringBuilder sb = new StringBuilder("🏆 <b>Топ квестов — " + escape(gameName) + "</b>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < stats.size(); i++) {
            QuestStat s = stats.get(i);
            String place = i < medals.length ? medals[i] : (i + 1) + ".";
            String activeTag = s.quest().isActive() ? "" : " (неактивен)";
            String catLabel = (!flatTop && s.quest().getCategory() != null) ? s.quest().getCategory() + " · " : "";
            sb.append(place).append(" <b>").append(escape(s.quest().getTitle())).append("</b>").append(activeTag).append("\n")
              .append("   ").append(catLabel)
              .append(s.count()).append(" выполн.\n\n");
            String catToken = categoryToken(s.quest().getCategory());
            String cb = "admin:quest:" + encodeGameToken(gameName) + ":" + catToken + ":" + s.quest().getId();
            rows.add(List.of(keyboardFactory.callback("✏️ " + trim(s.quest().getTitle(), 35), cb)));
        }
        rows.add(List.of(
                keyboardFactory.callback("⬅️ Назад", "admin:quests:game:" + encodeGameToken(gameName)),
                keyboardFactory.callback("🏠 Меню", "menu:admin")
        ));
        sendText(user.getTelegramId(), sb.toString().trim(), keyboardFactory.rowsLayout(rows));
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
        boolean flatGame = gameCatalogService.isFlat(quest.getGameName());
        sessionService.get(user.getTelegramId()).getData().put("admin_quest_back_data", backData);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                keyboardFactory.callback("✏️ Название", "admin:edit-title:" + questId),
                keyboardFactory.callback("📝 Описание", "admin:edit-description:" + questId)
        ));
        if (flatGame) {
            rows.add(List.of(keyboardFactory.callback("✨ Награды", "admin:edit-reward:" + questId)));
        } else {
            rows.add(List.of(
                    keyboardFactory.callback("✨ Награды", "admin:edit-reward:" + questId),
                    keyboardFactory.callback("📚 Категория", "admin:edit-category:" + questId)
            ));
        }
        rows.add(List.of(
                keyboardFactory.callback("🕹️ Платформа", "admin:edit-platform:" + questId),
                keyboardFactory.callback("👥 Лимит", "admin:edit-limit:" + questId)
        ));
        rows.add(List.of(
                keyboardFactory.callback("📋 Условие (для отклонения)", "admin:edit-condition:" + questId)
        ));
        rows.add(List.of(
                keyboardFactory.callback(quest.isActive() ? "⏸️ Скрыть" : "▶️ Включить", "admin:toggle:" + questId),
                keyboardFactory.callback("🗑️ Удалить", "admin:delete:" + questId)
        ));
        rows.add(List.of(
                keyboardFactory.callback("⬅️ Назад", backData),
                keyboardFactory.callback("🏠 Меню", "menu:admin")
        ));
        String platformText = quest.getPlatform() != null ? quest.getPlatform() : "—";
        String photoMark = quest.getPhotoFileId() != null ? " 🖼️" : "";
        String categoryLine = (!flatGame && quest.getCategory() != null && !quest.getCategory().isBlank())
                ? "📚 Категория: <b>" + escape(quest.getCategory()) + "</b>\n" : "";
        String conditionLine = (quest.getShortCondition() != null && !quest.getShortCondition().isBlank())
                ? "📋 Условие: <b>" + escape(quest.getShortCondition()) + "</b>\n" : "";
        sendText(user.getTelegramId(),
                "✏️ <b>Редактор квеста</b>" + photoMark + "\n\n"
                        + "🎯 <b>" + escape(quest.getTitle()) + "</b>\n"
                        + categoryLine
                        + "🕹️ Платформа: <b>" + escape(platformText) + "</b>\n"
                        + "👥 Лимит: <b>" + quest.getParticipantLimit() + "</b>\n"
                        + "🎮 Игра: <b>" + escape(quest.getGameName()) + "</b>\n"
                        + "✨ XP: <b>+" + quest.getRewardXp() + "</b>\n"
                        + "🪙 Монеты: <b>+" + quest.getRewardCoins() + "</b>\n"
                        + (quest.getTicketReward() > 0 ? "🎟 Билеты: <b>+" + quest.getTicketReward() + "</b>\n" : "")
                        + conditionLine
                        + "📡 Статус: <b>" + (quest.isActive() ? "активен" : "скрыт") + "</b>",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendSponsorQuestEditor(AppUser user, long questId, String backData) {
        Quest quest = questService.getQuest(questId);
        sessionService.get(user.getTelegramId()).getData().put("admin_quest_back_data", backData);

        String sponsorContact = "—";
        if (quest.getSponsorId() != null) {
            sponsorContact = sponsorService.findById(quest.getSponsorId())
                    .map(s -> s.getSponsorContact() != null ? s.getSponsorContact() : s.getName())
                    .orElse("—");
        }

        List<List<InlineKeyboardButton>> rows = List.of(
                List.of(
                        keyboardFactory.callback("✏️ Название", "admin:edit-title:" + questId),
                        keyboardFactory.callback("📝 Описание", "admin:edit-description:" + questId)
                ),
                List.of(
                        keyboardFactory.callback("✨ Награды", "admin:edit-reward:" + questId),
                        keyboardFactory.callback("📋 Примечание", "admin:sq-edit-note:" + questId)
                ),
                List.of(
                        keyboardFactory.callback("📋 Условие (для отклонения)", "admin:edit-condition:" + questId)
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
        sendText(user.getTelegramId(),
                "✏️ <b>Редактор спонсорского квеста</b>\n\n"
                        + "🎯 <b>" + escape(quest.getTitle()) + "</b>\n"
                        + "📞 Контакт: <b>" + escape(sponsorContact) + "</b>\n"
                        + "✨ XP: <b>+" + quest.getRewardXp() + "</b>\n"
                        + "🪙 EXC: <b>+" + quest.getRewardCoins() + "</b>\n"
                        + (quest.getTicketReward() > 0 ? "🎟 Билеты: <b>+" + quest.getTicketReward() + "</b>\n" : "")
                        + "📅 Длительность: <b>" + (quest.getDurationText() != null ? escape(quest.getDurationText()) : "—") + "</b>\n"
                        + "📡 Статус: <b>" + (quest.isActive() ? "активен" : "скрыт") + "</b>",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminStats(AppUser user) {
        sendText(user.getTelegramId(),
                "📊 <b>Статистика</b>\n\nВыберите раздел:",
                keyboardFactory.rowsLayout(List.of(
                        List.of(
                                keyboardFactory.callback("📊 Платформа", "admin:stats:platform"),
                                keyboardFactory.callback("🔥 Топ недели", "admin:stats:topquests")
                        ),
                        List.of(keyboardFactory.callback("🔄 Сбросить недельный XP", "admin:stats:reset_weekly")),
                        List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
                )));
    }

    private void sendAdminResetWeeklyConfirm(AppUser user) {
        sendText(user.getTelegramId(),
                "⚠️ <b>Сброс недельного рейтинга</b>\n\n"
                        + "Это обнулит <b>weeklyXp</b> всем игрокам прямо сейчас.\n"
                        + "Уведомления и призы лиг отправлены <b>не будут</b> — только сброс счётчика.\n\n"
                        + "Используй один раз для выравнивания данных. Далее сброс происходит автоматически каждый понедельник в 00:00 UTC.",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("✅ Подтвердить сброс", "admin:stats:reset_weekly:confirm"),
                                keyboardFactory.callback("❌ Отмена", "admin:stats"))
                )));
    }

    private void doAdminResetWeeklyXp(AppUser user) {
        long count = userService.forceResetWeeklyXp();
        sendText(user.getTelegramId(),
                "✅ Недельный XP сброшен у <b>" + count + "</b> игроков.\n\nРейтинг чистый.",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("⬅️ Назад", "admin:stats"))
                )));
    }

    private void sendAdminStatsPlatform(AppUser user) {
        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.LocalDate sevenDaysAgo = now.minusDays(7);
        java.time.LocalDate thirtyDaysAgo = now.minusDays(30);
        java.time.LocalDateTime nowDt = java.time.LocalDateTime.now();

        long totalUsers = userService.totalRegisteredUsers();
        long active7 = userService.countActiveSince(sevenDaysAgo);
        long active30 = userService.countActiveSince(thirtyDaysAgo);
        long newUsersWeek = userService.countNewUsersSince(nowDt.minusDays(7));

        long cohort7 = userService.countRegisteredBetween(nowDt.minusDays(14), nowDt.minusDays(7));
        long retained7 = cohort7 > 0 ? userService.countRegisteredBetweenAndActiveSince(nowDt.minusDays(14), nowDt.minusDays(7), sevenDaysAgo) : 0;
        String retention7 = cohort7 > 0 ? (retained7 * 100 / cohort7) + "%" : "—";

        long cohort30 = userService.countRegisteredBetween(nowDt.minusDays(60), nowDt.minusDays(30));
        long retained30 = cohort30 > 0 ? userService.countRegisteredBetweenAndActiveSince(nowDt.minusDays(60), nowDt.minusDays(30), thirtyDaysAgo) : 0;
        String retention30 = cohort30 > 0 ? (retained30 * 100 / cohort30) + "%" : "—";

        long totalApproved = questService.countAllApproved();
        long approvedMonth = questService.countApprovedSince(nowDt.minusDays(30));
        long moderated = questService.countModerated();
        String completionRate = moderated > 0 ? (totalApproved * 100 / moderated) + "%" : "—";

        long earnedWeek = excTransactionService.sumEarnedSince(nowDt.minusDays(7));
        long totalPaidOut = rewardService.totalPaidOutExc();
        long uniqueRecipients = rewardService.countUniqueWithdrawalRecipients();
        long[] rubAndTon = rewardService.totalPaidOutRubAndTonRub();
        long totalPaidRub = rubAndTon[0];
        long totalPaidTonRub = rubAndTon[1];
        java.math.BigDecimal totalPaidGram = totalPaidTonRub > 0
                ? exchangeRateService.rubToTon(java.math.BigDecimal.valueOf(totalPaidTonRub))
                : java.math.BigDecimal.ZERO;
        long totalCoins = userService.sumAllCoins();
        long totalTickets = userService.sumAllTickets();
        long pendingQuests = questService.pendingCount();
        long pendingRewards = rewardService.countPendingRequests();
        long totalQuestsCreated = questService.countActive();

        // Дельты vs вчера
        java.util.Optional<ru.gamebot.platform.domain.model.PlatformSnapshot> prev = platformSnapshotService.getYesterday();
        String dUsers = prev.map(s -> statsDelta(totalUsers, s.getTotalUsers())).orElse("");
        String dActive7 = prev.map(s -> statsDelta(active7, s.getActive7Days())).orElse("");
        String dApproved = prev.map(s -> statsDelta(totalApproved, s.getTotalApprovedQuests())).orElse("");
        String dPaidOut = prev.map(s -> statsDelta(totalPaidOut, s.getTotalPaidOutExc())).orElse("");
        String dCoins = prev.map(s -> statsDelta(totalCoins, s.getTotalCoinsOnAccounts())).orElse("");

        String pct7 = totalUsers > 0 ? " (" + (active7 * 100 / totalUsers) + "%)" : "";
        String pct30 = totalUsers > 0 ? " (" + (active30 * 100 / totalUsers) + "%)" : "";

        String gramLine = totalPaidTonRub > 0
                ? "💎 В GRAM: <b>~" + totalPaidGram.setScale(2, java.math.RoundingMode.HALF_DOWN) + " GRAM</b> (≈ " + fmtExc(totalPaidTonRub) + " ₽)\n"
                : "";

        sendText(user.getTelegramId(),
                "📊 <b>Статистика платформы</b>\n\n"
                        + "👥 Всего игроков: <b>" + totalUsers + dUsers + "</b>\n"
                        + "🆕 Новых за 7 дней: <b>" + newUsersWeek + "</b>\n\n"
                        + "📈 <b>Активность</b>\n"
                        + "🟢 За 7 дней: <b>" + active7 + pct7 + dActive7 + "</b>\n"
                        + "🔵 За 30 дней: <b>" + active30 + pct30 + "</b>\n"
                        + "🔄 Вернулись через 7 дней: <b>" + retention7 + "</b> (из тех, кто пришёл 7–14 дней назад — " + cohort7 + " чел.)\n"
                        + "🔄 Вернулись через 30 дней: <b>" + retention30 + "</b> (из тех, кто пришёл 30–60 дней назад — " + cohort30 + " чел.)\n\n"
                        + "🎯 <b>Квесты</b>\n"
                        + "✅ Выполнено всего: <b>" + totalApproved + dApproved + "</b>\n"
                        + "📅 За последний месяц: <b>" + approvedMonth + "</b>\n"
                        + "🎯 Доля одобренных заявок: <b>" + completionRate + "</b>\n"
                        + "🗺️ Активных квестов: <b>" + totalQuestsCreated + "</b>\n"
                        + "📥 На модерации: <b>" + pendingQuests + "</b>\n\n"
                        + "💰 Заработано за 7 дней: <b>" + fmtExc(earnedWeek) + " EXC</b>\n\n"
                        + "💸 <b>Выплаты</b>\n"
                        + "💸 Выплачено: <b>" + fmtExc(totalPaidOut) + " EXC" + dPaidOut + "</b>\n"
                        + "💵 В рублях: <b>" + fmtExc(totalPaidRub) + " ₽</b>\n"
                        + gramLine
                        + "👤 Получателей: <b>" + uniqueRecipients + "</b>\n\n"
                        + "💰 EXC на счетах: <b>" + fmtExc(totalCoins) + " EXC" + dCoins + "</b>\n"
                        + "🎟️ Билетов в обороте: <b>" + totalTickets + "</b>\n"
                        + "🎁 Заявок на награды: <b>" + pendingRewards + "</b>",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("📜 История", "admin:stats:history"),
                                keyboardFactory.callback("🔄 Обновить", "admin:stats:platform")),
                        List.of(keyboardFactory.callback("📸 Снепшот сейчас", "admin:stats:snapshot")),
                        List.of(keyboardFactory.callback("⬅️ Назад", "admin:stats"),
                                keyboardFactory.callback("🏠 Меню", "menu:main"))
                )));
    }

    private void sendAdminStatsHistory(AppUser user) {
        java.util.List<ru.gamebot.platform.domain.model.PlatformSnapshot> history =
                platformSnapshotService.getHistory(14);

        if (history.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📜 <b>История статистики</b>\n\nСнапшоты ещё не накоплены — первый сохранится сегодня в 00:05.",
                    keyboardFactory.rowsLayout(List.of(
                            List.of(keyboardFactory.callback("⬅️ Назад", "admin:stats:platform"))
                    )));
            return;
        }

        StringBuilder sb = new StringBuilder("📜 <b>История статистики</b> (последние " + history.size() + " дней)\n\n");
        for (int i = 0; i < history.size(); i++) {
            ru.gamebot.platform.domain.model.PlatformSnapshot s = history.get(i);
            ru.gamebot.platform.domain.model.PlatformSnapshot prev = (i + 1 < history.size()) ? history.get(i + 1) : null;

            String dU = prev != null ? statsDelta(s.getTotalUsers(), prev.getTotalUsers()) : "";
            String dA = prev != null ? statsDelta(s.getTotalApprovedQuests(), prev.getTotalApprovedQuests()) : "";
            String dP = prev != null ? statsDelta(s.getTotalPaidOutExc(), prev.getTotalPaidOutExc()) : "";

            sb.append("<b>").append(s.getSnapshotDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))).append("</b>\n")
              .append("   👥 ").append(s.getTotalUsers()).append(dU)
              .append("  ✅ ").append(s.getTotalApprovedQuests()).append(dA)
              .append("  💸 ").append(fmtExc(s.getTotalPaidOutExc())).append(dP).append("\n")
              .append("   🟢 ").append(s.getActive7Days()).append(" за 7д")
              .append("  💰 ").append(fmtExc(s.getTotalCoinsOnAccounts())).append(" EXC\n")
              .append("   🔄 Возврат 7д: ").append(s.getRetention7Pct()).append("% (из ").append(s.getRetention7Cohort()).append(")")
              .append("  30д: ").append(s.getRetention30Pct()).append("% (из ").append(s.getRetention30Cohort()).append(")")
              .append("  🎯 ").append(s.getCompletionRatePct()).append("%\n\n");
        }

        sendText(user.getTelegramId(), sb.toString(),
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("⬅️ Назад", "admin:stats:platform"))
                )));
    }

    private String statsDelta(long current, long previous) {
        long diff = current - previous;
        if (diff == 0) return "";
        return diff > 0 ? " ▲+" + diff : " ▼" + diff;
    }

    private String fmtExc(long value) {
        return String.format("%,d", value).replace(',', ' ');
    }

    private void sendAdminStatsTopQuests(AppUser user) {
        List<Object[]> top = questService.getTopQuestsByCompletionsThisWeek();
        String[] medals = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
        StringBuilder sb = new StringBuilder("🔥 <b>Топ квестов недели</b>\n\n");
        if (top.isEmpty()) {
            sb.append("За последние 7 дней квесты ещё не выполнялись.");
        } else {
            int limit = Math.min(5, top.size());
            for (int i = 0; i < limit; i++) {
                Object[] row = top.get(i);
                String title = (String) row[1];
                String gameName = (String) row[2];
                long coins = ((Number) row[3]).longValue();
                long count = ((Number) row[4]).longValue();
                String gameLabel = gameName != null && !gameName.isBlank() ? " - " + gameName : "";
                sb.append(medals[i]).append(" <b>").append(escape(title)).append(escape(gameLabel)).append("</b>\n")
                  .append("   ✅ ").append(count).append(" ").append(pluralCompletions(count))
                  .append(" · 🪙 ").append(coins).append(" EXC\n\n");
            }
        }
        sendText(user.getTelegramId(), sb.toString(),
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("⬅️ Назад", "admin:stats"),
                                keyboardFactory.callback("🏠 Меню", "menu:main"))
                )));
    }

    private String pluralCompletions(long n) {
        if (n % 100 >= 11 && n % 100 <= 19) return "выполнений";
        return switch ((int)(n % 10)) {
            case 1 -> "выполнение";
            case 2, 3, 4 -> "выполнения";
            default -> "выполнений";
        };
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

    private void sendAdminClashTagsList(AppUser user) {
        List<AppUser> users = userService.findUsersWithClashTags();
        if (users.isEmpty()) {
            sendText(user.getTelegramId(),
                    "🏷️ <b>Привязанные теги Clash of Clans / Clash Royale</b>\n\nНи один игрок ещё не привязал тег.",
                    backMenuKeyboard("menu:admin"));
            return;
        }
        StringBuilder sb = new StringBuilder("🏷️ <b>Привязанные теги Clash of Clans / Clash Royale</b>\n\n");
        int shown = 0;
        for (AppUser u : users) {
            if (shown >= 40) {
                sb.append("… и ещё ").append(users.size() - shown).append(" запис(ей), не поместились в сообщение.\n\n");
                break;
            }
            sb.append("👤 <b>").append(escape(u.getNickname())).append("</b> (ID: <code>").append(u.getTelegramId()).append("</code>)\n");
            if (u.getClashOfClansTag() != null) {
                sb.append("   ✅ Clash of Clans: <code>").append(escape(u.getClashOfClansTag())).append("</code>\n");
            }
            if (u.getClashRoyaleTag() != null) {
                sb.append("   ✅ Clash Royale: <code>").append(escape(u.getClashRoyaleTag())).append("</code>\n");
            }
            sb.append("\n");
            shown++;
        }
        sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:admin"));
    }

    private void sendAdminAutoQuestActivity(AppUser user) {
        ru.gamebot.platform.service.QuestService.AutoQuestActivityReport report = questService.getAutoQuestActivityReport();
        StringBuilder sb = new StringBuilder("🔁 <b>Активность автоквестов</b> (за 30 дней)\n\n");
        for (ru.gamebot.platform.service.QuestService.AutoQuestGameStat g : report.games()) {
            double avg = g.activeUsers30d() == 0 ? 0 : (double) g.approvals30d() / g.activeUsers30d();
            sb.append("🎮 <b>").append(escape(g.gameName())).append("</b>\n")
              .append("   👥 Активных игроков: ").append(g.activeUsers30d()).append("\n")
              .append("   ✅ Одобрений: ").append(g.approvals30d())
              .append(" (в среднем ").append(String.format("%.1f", avg)).append(" на игрока)\n\n");
        }
        sb.append("💰 Накопленный долг: <b>").append(report.totalDebtExc()).append(" EXC</b>\n")
          .append("👤 Всего игроков в проекте: <b>").append(report.totalUsers()).append("</b>");
        sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:admin"));
    }

    private void sendAdminOneTimeQuestAbuse(AppUser user) {
        List<Object[]> rows = questService.getOneTimeQuestRepeatOffenders();
        if (rows.isEmpty()) {
            sendText(user.getTelegramId(),
                    "🕵️ <b>Повторы разовых квестов</b>\n\nНичего не найдено — повторных одобрений по разовым квестам нет.",
                    backMenuKeyboard("menu:admin"));
            return;
        }
        StringBuilder sb = new StringBuilder("🕵️ <b>Повторы разовых квестов</b>\n\n"
                + "Эти квесты теперь одноразовые (антифрод-фикс 2026-08-30 — проверка баланса/ранга/лиги "
                + "вместо свежего действия), но ниже — аккаунты, успевшие пройти их несколько раз ДО фикса:\n\n");
        long totalExtraExc = 0;
        int shown = 0;
        for (Object[] row : rows) {
            if (shown >= 30) {
                sb.append("… и ещё ").append(rows.size() - shown).append(" запис(ей), не поместились в сообщение.\n\n");
                break;
            }
            String nickname = (String) row[1];
            Long telegramId = (Long) row[2];
            String title = (String) row[3];
            String game = (String) row[4];
            long rewardCoins = ((Number) row[5]).longValue();
            long count = ((Number) row[6]).longValue();
            long extra = (count - 1) * rewardCoins;
            totalExtraExc += extra;
            sb.append("👤 <b>").append(escape(nickname)).append("</b> (ID: <code>").append(telegramId).append("</code>)\n")
              .append("   🎯 ").append(escape(title)).append(" · ").append(escape(game)).append("\n")
              .append("   🔁 ").append(count).append(" раз(а) · ~").append(extra).append(" EXC лишних\n\n");
            shown++;
        }
        sb.append("💰 Итого лишних начислений: ~<b>").append(totalExtraExc).append(" EXC</b>\n\n")
          .append("<i>Суммы примерные — по текущей награде квеста, без учёта возможных исторических изменений "
                  + "награды и снижения за 3+ квестов одного типа в неделю.</i>");
        sendText(user.getTelegramId(), sb.toString(), backMenuKeyboard("menu:admin"));
    }

    private void sendAdminTrafficList(AppUser user) {
        List<ru.gamebot.platform.domain.model.TrafficSource> sources = trafficSourceService.findAll();
        String text = "📈 <b>Источники трафика</b>" + (sources.isEmpty() ? "\n\nИсточников пока нет." : "");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.TrafficSource ts : sources) {
            long clicks = ts.getClicks();
            long started = userService.countByTrafficSource(ts.getCode());
            long activated = userService.countActivatedByTrafficSource(ts.getCode());
            String conv = clicks > 0 ? String.format("%.0f%%", activated * 100.0 / clicks) : "—";
            String label = ts.getCode() + " · " + clicks + " кл · " + started + " зш · " + activated + " акт · " + conv;
            rows.add(List.of(keyboardFactory.callback(label, "admin:traffic:view:" + ts.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("➕ Создать источник", "admin:traffic:create")));
        rows.add(List.of(keyboardFactory.callback("📦 Пачка ссылок", "admin:traffic:batch")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "menu:admin")));
        sendText(user.getTelegramId(), text, keyboardFactory.rowsLayout(rows));
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
            long clicks = ts.getClicks();
            long started = users.size();
            long registered = userService.countRegisteredByTrafficSource(ts.getCode());
            long activated = userService.countActivatedByTrafficSource(ts.getCode());
            String conv = clicks > 0 ? String.format("%.1f%%", activated * 100.0 / clicks) : "—";
            sb.append("👆 Кликов по ссылке: <b>").append(clicks).append("</b>\n");
            sb.append("🚀 Зашли в бот: <b>").append(started).append("</b>\n");
            sb.append("📝 Заполнили профиль: <b>").append(registered).append("</b>\n");
            sb.append("✅ Активировали аккаунт: <b>").append(activated).append("</b>\n");
            sb.append("📊 Конверсия (клик→акт.): <b>").append(conv).append("</b>\n\n");
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
        List<ru.gamebot.platform.domain.model.Sponsor> sponsors = sponsorService.findAll().stream()
                .filter(s -> s.getBudgetExc() > 0)
                .toList();
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
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:edit")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminSponsorView(AppUser user, long sponsorId) {
        sponsorService.findById(sponsorId).ifPresentOrElse(s -> {
            List<ru.gamebot.platform.domain.model.Quest> linked = sponsorService.findSponsoredQuests(sponsorId);
            long rem = sponsorService.remainingBudget(s);
            long commission = sponsorService.commissionRub(s);

            long completions = sponsorService.countCompletions(s);
            StringBuilder sb = new StringBuilder("🤝 <b>" + escape(s.getName()) + "</b>\n");
            if (s.getSponsorContact() != null && !s.getSponsorContact().isBlank()) {
                sb.append("📞 Контакт: ").append(escape(s.getSponsorContact())).append("\n");
            }
            sb.append("📋 Кампания: ").append(escape(s.getCampaignName() != null ? s.getCampaignName() : "—")).append("\n");
            sb.append("Статус: ").append(s.isActive() ? "🟢 Активна" : "⚫ Завершена").append("\n\n");
            if (s.getStartDate() != null && s.getEndDate() != null) {
                java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
                sb.append("📅 Период: ").append(s.getStartDate().format(fmt)).append(" — ").append(s.getEndDate().format(fmt)).append("\n");
            }
            sb.append("✅ Одобрено прохождений: <b>").append(completions).append("</b>\n\n");
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
            rows.add(List.of(keyboardFactory.callback("➕ Создать квест", "admin:sponsors:newquest:" + sponsorId)));
            if (s.isActive()) {
                rows.add(List.of(keyboardFactory.callback("⚫ Завершить кампанию", "admin:sponsors:deactivate:" + sponsorId)));
            }
            for (ru.gamebot.platform.domain.model.Quest q : linked) {
                rows.add(List.of(
                        keyboardFactory.callback("✏️ " + trim(q.getTitle(), 28), "admin:sq-edit:" + sponsorId + ":" + q.getId()),
                        keyboardFactory.callback("❌ Открепить", "admin:sponsors:unlink:" + q.getId())
                ));
            }
            rows.add(List.of(keyboardFactory.callback("🗑 Удалить кампанию", "admin:sponsors:delete:" + sponsorId)));
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:sponsors")));
            sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
        }, () -> sendText(user.getTelegramId(), "❌ Спонсор не найден.", backMenuKeyboard("admin:sponsors")));
    }

    private void sendAdminPostpayList(AppUser user) {
        List<ru.gamebot.platform.domain.model.Sponsor> all = sponsorService.findAll().stream()
                .filter(s -> s.getBudgetExc() == 0)
                .toList();
        StringBuilder sb = new StringBuilder("📋 <b>Квесты под отчёт</b>\n\nПартнёры платят постфактум — по итогам кампании.\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy");
        for (ru.gamebot.platform.domain.model.Sponsor s : all) {
            String icon = s.isActive() ? "🟢" : "⚫";
            String period = s.getStartDate() != null
                    ? s.getStartDate().format(fmt) + "–" + s.getEndDate().minusDays(1).format(fmt) : "—";
            long completions = sponsorService.countCompletions(s);
            rows.add(List.of(keyboardFactory.callback(
                    icon + " " + s.getName() + " · " + completions + " прохожд. · " + period,
                    "admin:postpay:view:" + s.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("➕ Новая кампания под отчёт", "admin:postpay:create")));
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:edit")));
        sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminPostpayView(AppUser user, long sponsorId) {
        sponsorService.findById(sponsorId).ifPresentOrElse(s -> {
            List<ru.gamebot.platform.domain.model.Quest> linked = sponsorService.findSponsoredQuests(sponsorId);
            long completions = sponsorService.countCompletions(s);
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
            String period = s.getStartDate() != null
                    ? s.getStartDate().format(fmt) + " — " + s.getEndDate().minusDays(1).format(fmt) : "без ограничений";

            StringBuilder sb = new StringBuilder("📋 <b>" + escape(s.getName()) + "</b>\n");
            if (s.getSponsorContact() != null && !s.getSponsorContact().isBlank()) {
                sb.append("📞 Контакт: ").append(escape(s.getSponsorContact())).append("\n");
            }
            sb.append("Статус: ").append(s.isActive() ? "🟢 Активна" : "⚫ Завершена").append("\n");
            sb.append("📅 Период: ").append(period).append("\n");
            sb.append("✅ Одобрено прохождений: <b>").append(completions).append("</b>\n\n");
            sb.append("🗺️ <b>Привязанные квесты (").append(linked.size()).append("):</b>\n");
            for (ru.gamebot.platform.domain.model.Quest q : linked) {
                sb.append("• ").append(escape(q.getTitle())).append("\n");
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(keyboardFactory.callback("➕ Создать квест", "admin:postpay:newquest:" + sponsorId)));
            if (s.isActive()) {
                rows.add(List.of(keyboardFactory.callback("⚫ Закрыть кампанию", "admin:postpay:close:" + sponsorId)));
            }
            for (ru.gamebot.platform.domain.model.Quest q : linked) {
                rows.add(List.of(
                        keyboardFactory.callback("✏️ " + trim(q.getTitle(), 28), "admin:pp-edit:" + sponsorId + ":" + q.getId()),
                        keyboardFactory.callback("❌ Открепить", "admin:postpay:unlink:" + q.getId())
                ));
            }
            rows.add(List.of(keyboardFactory.callback("🗑 Удалить кампанию", "admin:postpay:delete:" + sponsorId)));
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:postpay")));
            sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
        }, () -> sendText(user.getTelegramId(), "❌ Кампания не найдена.", backMenuKeyboard("admin:postpay")));
    }

    private void sendPostpayQuestPicker(AppUser user, long sponsorId) {
        List<ru.gamebot.platform.domain.model.Quest> allQuests = questService.findAll().stream()
                .filter(q -> !q.isSponsored())
                .toList();
        if (allQuests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "⚠️ Нет квестов без спонсора.",
                    backMenuKeyboard("admin:postpay:view:" + sponsorId));
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.Quest q : allQuests.stream().limit(20).toList()) {
            rows.add(List.of(keyboardFactory.callback(
                    q.getTitle() + " (" + q.getGameName() + ")",
                    "admin:postpay:link:" + sponsorId + ":" + q.getId())));
        }
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:postpay:view:" + sponsorId)));
        sendText(user.getTelegramId(), "🗺️ Выберите квест для привязки:", keyboardFactory.rowsLayout(rows));
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
                case CANCELLED_LOW_TURNOUT -> "🚫";
            };
            long entries = tournamentService.entryCount(t);
            String dateLabel = t.getStartDate() != null
                    ? " · " + t.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
                    : "";
            rows.add(List.of(keyboardFactory.callback(
                    icon + " " + t.getName() + " (" + entries + " уч.)" + dateLabel,
                    "admin:tournaments:view:" + t.getId())));
        }
        long anomalyCount = tournamentEntryRepository.countByAnomalyFlagTrueAndAnomalyResolvedFalse();
        if (anomalyCount > 0) {
            rows.add(List.of(keyboardFactory.callback("⚠️ Аномалии Brawl Stars (" + anomalyCount + ")", "brawl:anomalies")));
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
                case CANCELLED_LOW_TURNOUT -> "🚫 Отменён (недобор участников)";
            }).append("\n");
            if (t.getGameName() != null) sb.append("🎮 Игра: ").append(escape(t.getGameName())).append("\n");
            sb.append("💰 Взнос: <b>").append(t.getEntryFeeExc()).append(" EXC</b>\n");
            if (t.getMinParticipants() != null) sb.append("👥 Минимум участников: <b>").append(t.getMinParticipants()).append("</b>\n");
            sb.append("🏅 Призовой фонд: <b>").append(t.getPrizePoolExc()).append(" EXC</b>\n");
            sb.append("👥 Участников: <b>").append(entries).append("</b>\n");
            if (t.getStartDate() != null) sb.append("🔒 Закрытие регистрации: ").append(t.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))).append(" (UTC)\n");
            if (t.getEndDate() != null) sb.append("⏰ Финиш: ").append(t.getEndDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))).append(" (UTC)\n");
            if (t.getStatus() == ru.gamebot.platform.domain.model.Tournament.Status.CANCELLED_LOW_TURNOUT) {
                List<ru.gamebot.platform.domain.model.TournamentEntry> refundedEntries = tournamentEntryRepository.findAllWithUserByTournamentUnordered(t);
                sb.append("\n💸 <b>Возвраты:</b>\n");
                if (refundedEntries.isEmpty()) {
                    sb.append("Участников не было — возвращать некому.\n");
                } else {
                    java.time.format.DateTimeFormatter refundFmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");
                    for (ru.gamebot.platform.domain.model.TournamentEntry e : refundedEntries) {
                        String nick = e.getUser().getNickname() != null ? e.getUser().getNickname() : "ID:" + e.getUser().getTelegramId();
                        String when = e.getRefundedAt() != null ? e.getRefundedAt().format(refundFmt) : "—";
                        sb.append(e.isRefunded() ? "✅ " : "⏳ ").append(escape(nick))
                                .append(" — ").append(e.getEntryFeeExc()).append(" EXC");
                        if (e.isRefunded()) sb.append(" (").append(when).append(")");
                        sb.append("\n");
                    }
                }
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            if (t.getScoringType() == ru.gamebot.platform.domain.model.Tournament.ScoringType.BRAWL_TROPHIES) {
                rows.add(List.of(keyboardFactory.callback("👥 Участники (Brawl Stars)", "admin:tournaments:brawlparticipants:" + tid)));
            } else if (t.getStatus() != ru.gamebot.platform.domain.model.Tournament.Status.FINISHED) {
                rows.add(List.of(keyboardFactory.callback("📊 Участники", "tournament:leaderboard:" + tid)));
            }
            rows.add(List.of(keyboardFactory.callback("🗑️ Удалить турнир", "admin:tournaments:delete_confirm:" + tid)));
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:tournaments")));
            InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(rows);
            if (t.getPhotoFileId() != null) {
                sendPhotoCaption(user.getTelegramId(), t.getPhotoFileId(), sb.toString(), keyboard);
            } else {
                sendText(user.getTelegramId(), sb.toString(), keyboard);
            }
        }, () -> sendText(user.getTelegramId(), "❌ Турнир не найден.", backMenuKeyboard("admin:tournaments")));
    }

    private void sendAdminBrawlParticipants(AppUser user, long tid) {
        tournamentService.findById(tid).ifPresentOrElse(t -> {
            List<ru.gamebot.platform.domain.model.TournamentEntry> entries =
                    tournamentEntryRepository.findAllWithUserByTournamentUnordered(t);
            StringBuilder sb = new StringBuilder("👥 <b>Участники (Brawl Stars) — " + escape(t.getName()) + "</b>\n\n");
            if (entries.isEmpty()) sb.append("Пока никто не зарегистрировался.");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (ru.gamebot.platform.domain.model.TournamentEntry e : entries) {
                String statusIcon = switch (e.getSnapshotStatus()) {
                    case PENDING -> "⏳"; case OK -> "✅"; case FAILED -> "❌";
                };
                String anomalyIcon = e.isAnomalyFlag() && !e.isAnomalyResolved() ? " ⚠️" : "";
                String nick = e.getUser().getNickname() != null ? e.getUser().getNickname() : "ID:" + e.getUser().getTelegramId();
                String line = statusIcon + anomalyIcon + " " + trim(nick, 16)
                        + " " + (e.getGameTag() != null ? e.getGameTag() : "—")
                        + " старт:" + (e.getTrophiesStart() != null ? e.getTrophiesStart() : "—")
                        + " финиш:" + (e.getTrophiesEnd() != null ? e.getTrophiesEnd() : "—");
                rows.add(List.of(keyboardFactory.callback(line, "admin:tournaments:resnapshot:" + e.getId())));
            }
            rows.add(List.of(keyboardFactory.callback("⬅️ Назад", "admin:tournaments:view:" + tid)));
            sendText(user.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
        }, () -> sendText(user.getTelegramId(), "❌ Турнир не найден.", backMenuKeyboard("admin:tournaments")));
    }

    private void sendBrawlAnomalies(Long chatId) {
        List<ru.gamebot.platform.domain.model.TournamentEntry> flagged =
                tournamentEntryRepository.findAllByAnomalyFlagTrueAndAnomalyResolvedFalse();
        if (flagged.isEmpty()) {
            sendText(chatId, "✅ Подозрительных турнирных заявок нет.", backOnlyKeyboard("admin:tournaments"));
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (ru.gamebot.platform.domain.model.TournamentEntry e : flagged) {
            String nick = e.getUser().getNickname() != null ? e.getUser().getNickname() : "ID:" + e.getUser().getTelegramId();
            buttons.add(keyboardFactory.callback(
                    "⚠️ " + trim(nick, 20) + " (" + e.getTournament().getName() + ")",
                    "brawl:anomaly:" + e.getId()));
        }
        buttons.add(keyboardFactory.callback("⬅️ Назад", "admin:tournaments"));
        sendText(chatId,
                "⚠️ <b>Подозрительные заявки (Brawl Stars)</b>\n\n"
                        + "Падение трофеев больше 300 между регистрацией и стартом турнира.\n\n"
                        + "Проверьте вручную: снимите флаг (честно) или дисквалифицируйте.",
                keyboardFactory.smartLayout(buttons));
    }

    private void handleBrawlAnomalyAction(CallbackQuery callbackQuery, AppUser moderator, Long entryId) {
        ru.gamebot.platform.domain.model.TournamentEntry e = tournamentEntryRepository.findById(entryId).orElse(null);
        if (e == null) {
            sendText(moderator.getTelegramId(), "⚠️ Заявка не найдена.", backOnlyKeyboard("brawl:anomalies"));
            answerSilently(callbackQuery.getId());
            return;
        }
        String nick = e.getUser().getNickname() != null ? e.getUser().getNickname() : "ID:" + e.getUser().getTelegramId();
        int drop = e.getTrophiesAtRegistration() - e.getTrophiesStart();
        sendText(moderator.getTelegramId(),
                "⚠️ <b>Подозрительная заявка</b>\n\n"
                        + "👤 " + escape(nick) + "\n"
                        + "🏆 Турнир: " + escape(e.getTournament().getName()) + "\n"
                        + "🏷️ Тег: " + e.getGameTag() + "\n"
                        + "📉 Трофеи: было " + e.getTrophiesAtRegistration() + " → на старте " + e.getTrophiesStart()
                        + " (падение " + drop + ")\n\n"
                        + "Если игрок честный — снимите флаг.",
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("✅ Снять флаг", "brawl:clear_anomaly:" + entryId)),
                        List.of(keyboardFactory.callback("🚫 Дисквалифицировать", "brawl:disqualify:" + entryId)),
                        List.of(keyboardFactory.callback("⬅️ Назад", "brawl:anomalies"))
                )));
        answerSilently(callbackQuery.getId());
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

        boolean isBrawl = t.getScoringType() == ru.gamebot.platform.domain.model.Tournament.ScoringType.BRAWL_TROPHIES;

        // Notify each prize winner in private (Brawl tournaments also notify non-winners with their result)
        for (ru.gamebot.platform.domain.model.TournamentEntry e : entries) {
            boolean scored = !isBrawl || (e.getSnapshotStatus() == ru.gamebot.platform.domain.model.TournamentEntry.SnapshotStatus.OK
                    && !e.isDisqualified() && e.getTrophiesStart() != null && e.getTrophiesEnd() != null);
            if (e.getPrizeExc() > 0) {
                try {
                    String delta = isBrawl && scored ? "\n📈 Прирост трофеев: <b>+" + (e.getTrophiesEnd() - e.getTrophiesStart()) + "</b>" : "";
                    String prizeNote = e.isPayoutHeld()
                            ? "\n⏳ Приз временно удержан (идёт проверка), будет зачислен после."
                            : "\n💰 Приз зачислен: <b>+" + e.getPrizeExc() + " EXC</b>";
                    sendText(e.getUser().getTelegramId(),
                            "🏆 <b>Турнир завершён!</b>\n\n"
                            + "Вы заняли <b>" + e.getRank() + " место</b> в турнире «" + escape(t.getName()) + "»"
                            + delta + prizeNote,
                            backMenuKeyboard("menu:main"));
                } catch (Exception ex) {
                    log.warn("Failed to notify user {} about tournament prize", e.getUser().getTelegramId());
                }
            } else if (isBrawl && scored) {
                try {
                    int delta = e.getTrophiesEnd() - e.getTrophiesStart();
                    sendText(e.getUser().getTelegramId(),
                            "🏆 <b>Турнир завершён!</b>\n\n"
                            + "Вы заняли <b>" + e.getRank() + " место</b> в турнире «" + escape(t.getName()) + "»\n"
                            + "📈 Прирост трофеев: <b>" + (delta >= 0 ? "+" : "") + delta + "</b>\n"
                            + "Приза в этот раз нет — попробуйте в следующем турнире!",
                            backMenuKeyboard("menu:main"));
                } catch (Exception ex) {
                    log.warn("Failed to notify user {} about tournament result", e.getUser().getTelegramId());
                }
            }
        }
    }

    @org.springframework.context.event.EventListener
    public void onTournamentCancelled(ru.gamebot.platform.event.TournamentCancelledEvent event) {
        ru.gamebot.platform.domain.model.Tournament t = event.getTournament();
        for (ru.gamebot.platform.domain.model.TournamentEntry e : event.getRefundedEntries()) {
            try {
                sendText(e.getUser().getTelegramId(),
                        "🚫 <b>Турнир отменён</b>\n\n"
                        + "Турнир «" + escape(t.getName()) + "» не набрал минимального числа участников ("
                        + t.getMinParticipants() + ") к моменту закрытия регистрации.\n\n"
                        + "💰 Взнос возвращён на баланс: <b>+" + e.getEntryFeeExc() + " EXC</b>",
                        backMenuKeyboard("menu:main"));
            } catch (Exception ex) {
                log.warn("Failed to notify user {} about tournament cancellation refund", e.getUser().getTelegramId());
            }
        }
    }

    @org.springframework.context.event.EventListener
    public void onBrawlStartSnapshotTaken(ru.gamebot.platform.event.BrawlStarsSnapshotTakenEvent event) {
        ru.gamebot.platform.domain.model.TournamentEntry entry = event.getEntry();
        try {
            sendText(entry.getUser().getTelegramId(),
                    "🏁 <b>Турнир начался!</b>\n\n"
                    + "Ваши стартовые трофеи зафиксированы: <b>" + entry.getTrophiesStart() + " 🏆</b>\n"
                    + "Удачи в марафоне!",
                    backMenuKeyboard("menu:main"));
        } catch (Exception ex) {
            log.warn("Failed to notify user {} about Brawl start snapshot", entry.getUser().getTelegramId());
        }
    }

    @org.springframework.context.event.EventListener
    public void onBrawlSnapshotBatchFailed(ru.gamebot.platform.event.BrawlStarsSnapshotBatchFailedEvent event) {
        String phaseLabel = "start".equals(event.getPhase()) ? "стартовый" : "финишный";
        String text = "🚨 <b>Проблема с турниром Brawl Stars</b>\n\n"
                + "Турнир «" + escape(event.getTournament().getName()) + "»: " + phaseLabel
                + " снимок трофеев не удалось получить ни для одного участника.\n"
                + "Проверьте статус API-токена / IP и повторите снимок вручную из карточки турнира.";
        for (Long adminId : adminService.resolvedAdminIds()) {
            try {
                SendMessage msg = new SendMessage();
                msg.setChatId(adminId.toString());
                msg.setText(text);
                msg.setParseMode("HTML");
                execute(msg);
            } catch (TelegramApiException e) {
                log.warn("Failed to alert admin {} about Brawl snapshot batch failure", adminId, e);
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

    @org.springframework.context.event.EventListener
    public void onAutoPollCreated(ru.gamebot.platform.event.AutoPollCreatedEvent event) {
        ru.gamebot.platform.domain.model.Poll poll = event.getPoll();
        List<String> options = pollService.getOptions(poll);
        StringBuilder sb = new StringBuilder("🗳 <b>Новый опрос!</b>\n\n");
        sb.append("❓ <b>").append(escape(poll.getQuestion())).append("</b>\n\n");
        for (int i = 0; i < options.size(); i++) {
            sb.append(i + 1).append(". ").append(escape(options.get(i))).append("\n");
        }
        sb.append("\nГолосование бесплатное — выбери вариант в боте 👇");
        try {
            SendMessage msg = new SendMessage();
            msg.setChatId(requiredChannelChatId());
            msg.setText(sb.toString());
            msg.setParseMode("HTML");
            msg.setReplyMarkup(keyboardFactory.rowsLayout(List.of(
                    List.of(keyboardFactory.url("🗳 Проголосовать", "https://t.me/" + appProperties.getBotUsername())))));
            execute(msg);
        } catch (Exception e) {
            log.error("Failed to publish auto-poll announcement for poll {}", poll.getId(), e);
        }
    }

    /** Кандидат авто-опроса на согласование — сам Poll создаётся только после одобрения
     * (см. {@link #handleAdminFeedAction}), чтобы неодобренный вопрос не был "живым" в разделе бота. */
    @org.springframework.context.event.EventListener
    public void onScheduledPollCandidate(ru.gamebot.platform.event.ScheduledPollCandidateEvent event) {
        pendingPollCandidate = new PendingPollCandidate(event.getQuestion(), event.getOptions());
        sendPollFeedCard();
    }

    private void sendPollFeedCard() {
        PendingPollCandidate candidate = pendingPollCandidate;
        if (candidate == null) return;
        StringBuilder sb = new StringBuilder("🧾 <b>Авто-опрос — на согласование</b>\n\n");
        sb.append("❓ <b>").append(escape(candidate.question())).append("</b>\n\n");
        List<String> options = candidate.options();
        for (int i = 0; i < options.size(); i++) {
            sb.append(i + 1).append(". ").append(escape(options.get(i))).append("\n");
        }
        InlineKeyboardMarkup markup = keyboardFactory.smartLayout(List.of(
                keyboardFactory.callback("✅ Опубликовать", "adminfeed:poll:approve"),
                keyboardFactory.callback("✏️ Изменить", "adminfeed:poll:edit"),
                keyboardFactory.callback("❌ Отклонить", "adminfeed:poll:reject")));
        for (Long adminId : adminService.resolvedAdminIds()) {
            try {
                sendText(adminId, sb.toString(), markup);
            } catch (Exception e) {
                log.warn("Failed to send scheduled poll candidate to admin {}", adminId, e);
            }
        }
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
                keyboardFactory.callback("🔍 Найти по TG ID / нику", "admin:users:search"),
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

        if ("cancelsub".equals(action) && parts.length >= 4) {
            AppUser target = userService.findByTelegramId(telegramId).orElse(null);
            if (target == null) {
                sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
                return;
            }
            Long submissionId = parseLong(parts[3]);
            try {
                questService.cancelSubmission(submissionId, target);
                sendText(target.getTelegramId(),
                        "ℹ️ Администратор отменил вашу заявку на квест. При вопросах обратитесь в поддержку.", null);
            } catch (IllegalArgumentException e) {
                // Уже одобрен/отменён — просто покажем список заново, без падения
            }
            sendAdminUserQuestHistory(admin, telegramId, page == null ? 0 : page);
            return;
        }

        if ("exc".equals(action)) {
            sendAdminUserExcHistory(admin, telegramId, page == null ? 0 : page);
            return;
        }

        if ("withdrawals".equals(action)) {
            sendUserWithdrawalHistory(admin, telegramId, "admin");
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

        if ("multiacc".equals(action)) {
            AppUser target = userService.findByTelegramId(telegramId).orElse(null);
            if (target == null) {
                sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
                return;
            }
            session.reset();
            session.setQuestId(telegramId);
            session.getData().put("blockPage", String.valueOf(page == null ? 0 : page));
            session.setState(SessionState.MULTIACC_BLOCK_OTHER_ID);
            sendText(admin.getTelegramId(),
                    "🚫 <b>Блокировка мультиаккаунта</b>\n\n"
                            + "👤 " + escape(displayUserName(target)) + " (ID: " + telegramId + ")\n\n"
                            + "Введите TG ID или ник <b>второго</b> аккаунта того же человека:",
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

        if ("freenick".equals(action)) {
            AppUser target = userService.findByTelegramId(telegramId).orElse(null);
            if (target == null) {
                sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
                return;
            }
            String oldNickname = userService.releaseNickname(telegramId);
            if (oldNickname == null || oldNickname.isBlank()) {
                sendAdminUserCard(admin, telegramId, page == null ? 0 : page, "ℹ️ У этого аккаунта и так не было ника.");
                return;
            }
            sendText(telegramId,
                    "ℹ️ Администратор освободил ваш никнейм «" + escape(oldNickname) + "». "
                            + "Задайте новый через профиль, если понадобится.",
                    null);
            sendAdminUserCard(admin, telegramId, page == null ? 0 : page,
                    "✅ Никнейм «" + escape(oldNickname) + "» освобождён и доступен для новой регистрации.");
            return;
        }

        if ("bonus".equals(action)) {
            AppUser target = userService.findByTelegramId(telegramId).orElse(null);
            if (target == null) {
                sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
                return;
            }
            session.reset();
            session.setState(SessionState.BONUS_INPUT);
            session.getData().put("bonus_direct_target", String.valueOf(telegramId));
            session.getData().put("bonus_direct_page", String.valueOf(page == null ? 0 : page));
            sendText(admin.getTelegramId(),
                    "🎁 <b>Начисление бонуса</b>\n\n"
                            + "👤 Игрок: <b>" + escape(displayUserName(target)) + "</b> (ID: " + telegramId + ")\n\n"
                            + "Отправьте данные одним сообщением.\n"
                            + "Формат: <code>XP COINS TICKETS комментарий</code>\n"
                            + "Пример: <code>100 50 3 За активность</code>",
                    cancelKeyboard());
            return;
        }

        if ("debit".equals(action)) {
            AppUser target = userService.findByTelegramId(telegramId).orElse(null);
            if (target == null) {
                sendText(admin.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("admin:users:0"));
                return;
            }
            session.reset();
            session.setState(SessionState.DEBIT_INPUT);
            session.getData().put("debit_direct_target", String.valueOf(telegramId));
            session.getData().put("debit_direct_page", String.valueOf(page == null ? 0 : page));
            sendText(admin.getTelegramId(),
                    "➖ <b>Списание баланса</b>\n\n"
                            + "👤 Игрок: <b>" + escape(displayUserName(target)) + "</b> (ID: " + telegramId + ")\n\n"
                            + "Отправьте данные одним сообщением.\n"
                            + "Формат: <code>XP EXC TICKETS комментарий</code>\n"
                            + "Пример: <code>0 500 0 Штраф за нарушение</code>",
                    cancelKeyboard());
            return;
        }

        sendText(admin.getTelegramId(), "⚠️ Действие с пользователем не распознано.", backMenuKeyboard("menu:admin"));
    }

    private void sendAdminUserQuestHistory(AppUser admin, Long telegramId, int page) {
        sendUserQuestHistory(admin, telegramId, page, "admin");
    }

    /** prefix — "admin" или "mod", определяет куда ведут кнопки навигации внутри экрана. */
    private void sendUserQuestHistory(AppUser staff, Long telegramId, int page, String prefix) {
        String usersListRoute = "admin".equals(prefix) ? "admin:users:0" : "menu:main";
        AppUser target = userService.findByTelegramId(telegramId).orElse(null);
        if (target == null) {
            sendText(staff.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard(usersListRoute));
            return;
        }

        List<ru.gamebot.platform.domain.model.QuestSubmission> all =
                questService.findAllByUser(target).stream()
                        .filter(s -> s.getStatus() != ru.gamebot.platform.domain.enums.SubmissionStatus.CANCELLED)
                        .toList();

        long approvedCount = all.stream()
                .filter(s -> s.getStatus() == ru.gamebot.platform.domain.enums.SubmissionStatus.APPROVED)
                .count();

        String header = "📋 <b>Квесты игрока</b>\n"
                + "👤 <b>" + escape(displayUserName(target)) + "</b> (ID: " + telegramId + ")\n"
                + "Всего заявок: <b>" + all.size() + "</b> · Одобрено: <b>" + approvedCount + "</b>\n\n";

        if (all.isEmpty()) {
            sendText(staff.getTelegramId(), header + "Заявок нет.",
                    backMenuKeyboard(prefix + ":user:view:" + telegramId + ":" + page));
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
        List<InlineKeyboardButton> cancelButtons = new ArrayList<>();
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
            int num = startNum + i;
            sb.append(num).append(". ").append(statusIcon)
              .append(" <b>").append(escape(s.getQuest().getTitle())).append("</b>").append(completionTag).append("\n")
              .append("   🎮 ").append(escape(s.getQuest().getGameName()))
              .append(" · 💰 ").append(s.getQuest().getRewardCoins()).append(" EXC\n")
              .append("   📅 ").append(dateStr).append("\n\n");
            boolean cancelable = s.getStatus() != ru.gamebot.platform.domain.enums.SubmissionStatus.APPROVED
                    && s.getStatus() != ru.gamebot.platform.domain.enums.SubmissionStatus.CANCELLED;
            if ("admin".equals(prefix) && cancelable) {
                cancelButtons.add(keyboardFactory.callback("❌ Отменить №" + num,
                        prefix + ":user:cancelsub:" + telegramId + ":" + page + ":" + s.getId()));
            }
        }
        if (totalPages > 1) {
            sb.append("📄 Страница ").append(safePage + 1).append(" из ").append(totalPages);
        }

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (safePage > 0) {
            navRow.add(keyboardFactory.callback("⬅️", prefix + ":user:quests:" + telegramId + ":" + (safePage - 1)));
        }
        if (safePage < totalPages - 1) {
            navRow.add(keyboardFactory.callback("➡️", prefix + ":user:quests:" + telegramId + ":" + (safePage + 1)));
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (InlineKeyboardButton b : cancelButtons) {
            rows.add(List.of(b));
        }
        if (!navRow.isEmpty()) rows.add(navRow);
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад к карточке", prefix + ":user:view:" + telegramId + ":" + page)));

        sendText(staff.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendAdminUserExcHistory(AppUser admin, Long telegramId, int page) {
        sendUserExcHistory(admin, telegramId, page, "admin");
    }

    /** prefix — "admin" или "mod", определяет куда ведут кнопки навигации внутри экрана. */
    private void sendUserExcHistory(AppUser staff, Long telegramId, int page, String prefix) {
        String usersListRoute = "admin".equals(prefix) ? "admin:users:0" : "menu:main";
        AppUser target = userService.findByTelegramId(telegramId).orElse(null);
        if (target == null) {
            sendText(staff.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard(usersListRoute));
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
            sendText(staff.getTelegramId(), header + "Операций нет.",
                    backMenuKeyboard(prefix + ":user:view:" + telegramId + ":0"));
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
            navRow.add(keyboardFactory.callback("⬅️", prefix + ":user:exc:" + telegramId + ":" + (safePage - 1)));
        }
        if (safePage < totalPages - 1) {
            navRow.add(keyboardFactory.callback("➡️", prefix + ":user:exc:" + telegramId + ":" + (safePage + 1)));
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (!navRow.isEmpty()) rows.add(navRow);
        rows.add(List.of(keyboardFactory.callback("⬅️ Назад к карточке", prefix + ":user:view:" + telegramId + ":0")));

        sendText(staff.getTelegramId(), sb.toString(), keyboardFactory.rowsLayout(rows));
    }

    private void sendUserWithdrawalHistory(AppUser staff, Long telegramId, String prefix) {
        AppUser target = userService.findByTelegramId(telegramId).orElse(null);
        if (target == null) {
            sendText(staff.getTelegramId(), "⚠️ Пользователь не найден.",
                    backMenuKeyboard("admin".equals(prefix) ? "admin:users:0" : "menu:main"));
            return;
        }
        List<ru.gamebot.platform.domain.model.RewardRequest> all = rewardService.findUserRequests(target).stream()
                .filter(r -> "Вывод".equals(r.getRewardItem().getCategory()))
                .sorted(java.util.Comparator.comparing(ru.gamebot.platform.domain.model.RewardRequest::getCreatedAt))
                .toList();

        long monthlyLimit = sinkShopService.getMonthlyLimit(target.getXp());

        StringBuilder sb = new StringBuilder();
        sb.append("💸 <b>История выводов</b>\n")
          .append("👤 <b>").append(escape(displayUserName(target))).append("</b> (ID: ").append(telegramId).append(")\n")
          .append("📊 Месячный лимит: <b>").append(monthlyLimit).append(" EXC</b>\n\n");

        if (all.isEmpty()) {
            sb.append("Заявок на вывод нет.");
        } else {
            java.time.format.DateTimeFormatter monthFmt =
                    java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", new java.util.Locale("ru"));
            java.util.Map<java.time.YearMonth, java.util.List<ru.gamebot.platform.domain.model.RewardRequest>> byMonth =
                    new java.util.LinkedHashMap<>();
            for (ru.gamebot.platform.domain.model.RewardRequest r : all) {
                java.time.YearMonth ym = java.time.YearMonth.from(r.getCreatedAt());
                byMonth.computeIfAbsent(ym, k -> new java.util.ArrayList<>()).add(r);
            }
            for (java.util.Map.Entry<java.time.YearMonth, java.util.List<ru.gamebot.platform.domain.model.RewardRequest>> entry : byMonth.entrySet()) {
                String monthName = entry.getKey().atDay(1).format(monthFmt);
                monthName = Character.toUpperCase(monthName.charAt(0)) + monthName.substring(1);
                sb.append("── <b>").append(escape(monthName)).append("</b> ──\n");
                long monthTotal = 0;
                for (ru.gamebot.platform.domain.model.RewardRequest r : entry.getValue()) {
                    ru.gamebot.platform.domain.enums.RewardRequestStatus st = r.getStatus();
                    long exc = r.getRewardItem().getPriceCoins();
                    String statusIcon = switch (st) {
                        case PENDING     -> "⏳";
                        case IN_PROGRESS -> "🔄";
                        case APPROVED    -> "✅";
                        case REJECTED    -> "❌";
                        case CANCELLED   -> "🚫";
                    };
                    String statusName = switch (st) {
                        case PENDING     -> "Ожидает";
                        case IN_PROGRESS -> "В обработке";
                        case APPROVED    -> "Одобрена";
                        case REJECTED    -> "Отклонена";
                        case CANCELLED   -> "Отменена";
                    };
                    boolean countable = st == ru.gamebot.platform.domain.enums.RewardRequestStatus.APPROVED
                            || st == ru.gamebot.platform.domain.enums.RewardRequestStatus.PENDING
                            || st == ru.gamebot.platform.domain.enums.RewardRequestStatus.IN_PROGRESS;
                    if (countable) monthTotal += exc;

                    String rubStr = "";
                    String pd = r.getPayoutDetails();
                    if (pd != null && pd.contains("rubles=")) {
                        try {
                            String val = pd.substring(pd.indexOf("rubles=") + 7).split("[^0-9]")[0];
                            rubStr = " → " + val + " ₽";
                        } catch (Exception ignored) {}
                    }
                    String displayId = r.getDisplayId() != null ? "В-" + r.getDisplayId() : "В-" + r.getId();
                    sb.append(statusIcon).append(" <b>").append(displayId).append("</b>")
                      .append(" | ").append(exc).append(" EXC")
                      .append(rubStr)
                      .append(" — ").append(statusName);
                    if (r.getAdminComment() != null && !r.getAdminComment().isBlank()) {
                        sb.append(" (<i>").append(escape(r.getAdminComment())).append("</i>)");
                    }
                    sb.append("\n");
                }
                sb.append("📦 Итого за месяц: <b>").append(monthTotal).append(" / ").append(monthlyLimit).append(" EXC</b>\n\n");
            }
        }

        sendText(staff.getTelegramId(), sb.toString(),
                keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("⬅️ Назад к карточке", prefix + ":user:view:" + telegramId + ":0"))
                )));
    }

    /** Обработчик "mod:user:*" — урезанная карточка игрока для модератора (без смены роли/блокировки). */
    private void handleModUserAction(AppUser mod, String payload) {
        String[] parts = payload.split(":");
        if (parts.length < 3) {
            sendText(mod.getTelegramId(), "⚠️ Карточка пользователя недоступна.", backMenuKeyboard("menu:main"));
            return;
        }

        String action = parts[0];
        Long telegramId = parseLong(parts[1]);
        Integer page = parseInteger(parts[2]);
        if (telegramId == null) {
            sendText(mod.getTelegramId(), "⚠️ Карточка пользователя недоступна.", backMenuKeyboard("menu:main"));
            return;
        }

        if ("view".equals(action)) {
            sendModUserCard(mod, telegramId, null);
            return;
        }
        if ("quests".equals(action)) {
            sendUserQuestHistory(mod, telegramId, page == null ? 0 : page, "mod");
            return;
        }
        if ("exc".equals(action)) {
            sendUserExcHistory(mod, telegramId, page == null ? 0 : page, "mod");
            return;
        }
        if ("withdrawals".equals(action)) {
            sendUserWithdrawalHistory(mod, telegramId, "mod");
            return;
        }
        sendText(mod.getTelegramId(), "⚠️ Действие с пользователем не распознано.", backMenuKeyboard("menu:main"));
    }

    /** Только просмотр — без кнопок смены роли и блокировки, это остаётся исключительно у админов. */
    private void sendModUserCard(AppUser mod, Long telegramId, String notice) {
        AppUser target = userService.findByTelegramId(telegramId).orElse(null);
        if (target == null) {
            sendText(mod.getTelegramId(), "⚠️ Пользователь не найден.", backMenuKeyboard("menu:main"));
            return;
        }

        String text = (notice == null ? "" : notice + "\n\n")
                + "👤 <b>Карточка пользователя</b>\n\n"
                + "🎮 Имя: <b>" + escape(displayUserName(target)) + "</b>\n"
                + "🏷️ Тег: <b>" + escape(displayTag(target)) + "</b>\n"
                + "🆔 TG ID: <b>" + target.getTelegramId() + "</b>\n"
                + "⭐ Уровень: <b>" + userService.getLevelNumber(target.getXp()) + ". " + escape(userService.getLevelName(target.getXp())) + "</b>\n"
                + "✨ XP: <b>" + target.getXp() + "</b>\n"
                + "💰 EXC: <b>" + target.getCoins() + "</b>\n"
                + "🎟 Билеты: <b>" + target.getTickets() + "</b>\n"
                + "📅 Зарегистрирован: <b>" + (target.getCreatedAt() != null ? target.getCreatedAt().toLocalDate().toString() : "—") + "</b>\n"
                + (target.isBlocked()
                        ? "🚫 Статус: <b>заблокирован</b>"
                                + (target.getBlockReason() != null && !target.getBlockReason().isBlank()
                                        ? "\n   Причина: <i>" + escape(target.getBlockReason()) + "</i>"
                                        : "")
                        : "🟢 Статус: <b>активен</b>")
                + "\n\n" + buildGameTagsBlock(target);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>(List.of(
                List.of(keyboardFactory.callback("📋 Квесты игрока", "mod:user:quests:" + telegramId + ":0")),
                List.of(keyboardFactory.callback("💳 История EXC", "mod:user:exc:" + telegramId + ":0")),
                List.of(keyboardFactory.callback("💸 История выводов", "mod:user:withdrawals:" + telegramId + ":0")),
                List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
        ));
        sendText(mod.getTelegramId(), text, keyboardFactory.rowsLayout(rows));
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
                + "✨ XP: <b>" + target.getXp() + "</b>\n"
                + "💰 EXC: <b>" + target.getCoins() + "</b>\n"
                + "🎟 Билеты: <b>" + target.getTickets() + "</b>\n"
                + "📅 Зарегистрирован: <b>" + (target.getCreatedAt() != null ? target.getCreatedAt().toLocalDate().toString() : "—") + "</b>\n"
                + "🛡️ Роль: <b>" + escape(humanRole(highestAvailableRole(target))) + "</b>\n"
                + configuredNote + "\n"
                + "✅ Регистрация: <b>" + (target.isRegistrationCompleted() ? "завершена" : "не завершена") + "</b>\n"
                + (target.isBlocked()
                        ? "🚫 Статус: <b>заблокирован</b>"
                                + (target.getBlockReason() != null && !target.getBlockReason().isBlank()
                                        ? "\n   Причина: <i>" + escape(target.getBlockReason()) + "</i>"
                                        : "")
                        : "🟢 Статус: <b>активен</b>")
                + "\n\n" + buildGameTagsBlock(target);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>(List.of(
                List.of(keyboardFactory.callback("📋 Квесты игрока", "admin:user:quests:" + telegramId + ":" + page)),
                List.of(keyboardFactory.callback("💳 История EXC", "admin:user:exc:" + telegramId + ":0")),
                List.of(keyboardFactory.callback("💸 История выводов", "admin:user:withdrawals:" + telegramId + ":0")),
                List.of(keyboardFactory.callback("🗑 Сбросить активные квесты", "admin:user:resetquests:" + telegramId + ":" + page)),
                List.of(keyboardFactory.callback("👤 Сделать игроком", "admin:user:role:" + telegramId + ":" + page + ":" + ROLE_USER)),
                List.of(keyboardFactory.callback("🛡️ Сделать модератором", "admin:user:role:" + telegramId + ":" + page + ":" + ROLE_MODER)),
                List.of(keyboardFactory.callback("🛠️ Сделать админом", "admin:user:role:" + telegramId + ":" + page + ":" + ROLE_ADMIN)),
                List.of(target.isBlocked()
                        ? keyboardFactory.callback("✅ Разблокировать", "admin:user:unblock:" + telegramId + ":" + page)
                        : keyboardFactory.callback("🚫 Заблокировать", "admin:user:block:" + telegramId + ":" + page)),
                List.of(keyboardFactory.callback("🚫 Заблокировать как мультиаккаунт", "admin:user:multiacc:" + telegramId + ":" + page)),
                List.of(keyboardFactory.callback("🏷️ Освободить ник", "admin:user:freenick:" + telegramId + ":" + page)),
                List.of(
                        keyboardFactory.callback("🎁 Бонус", "admin:user:bonus:" + telegramId + ":" + page),
                        keyboardFactory.callback("➖ Списание", "admin:user:debit:" + telegramId + ":" + page)
                ),
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
        rows.add(List.of(keyboardFactory.callback("🔍 Поиск по TG ID / нику", "admin:bonussearch")));
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
        String directTargetStr = session.getData().get("bonus_direct_target");
        if (directTargetStr != null) {
            Long telegramId = parseLong(directTargetStr);
            Integer parsedBonusPage = parseInteger(session.getData().getOrDefault("bonus_direct_page", "0"));
            int returnPage = parsedBonusPage == null ? 0 : parsedBonusPage;
            String[] parts = text.trim().split("\\s+", 4);
            if (parts.length < 3) {
                sendText(user.getTelegramId(),
                        "⚠️ Формат неверный. Используйте: <code>XP COINS TICKETS комментарий</code>.",
                        cancelKeyboard());
                return;
            }
            Long xp = parsePositiveLong(parts[0]);
            Long coins = parsePositiveLong(parts[1]);
            Long tickets = parsePositiveLong(parts[2]);
            String comment = parts.length >= 4 ? parts[3] : "За активность";
            if (telegramId == null || xp == null || coins == null || tickets == null) {
                sendText(user.getTelegramId(),
                        "⚠️ Проверьте XP, монеты и билеты. Они должны быть числами.",
                        cancelKeyboard());
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
            sendAdminUserCard(user, telegramId, returnPage, "✅ Бонус начислен игроку " + telegramId + ".");
            return;
        }

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
        String directTargetStr = session.getData().get("debit_direct_target");
        if (directTargetStr != null) {
            Long telegramId = parseLong(directTargetStr);
            Integer parsedDebitPage = parseInteger(session.getData().getOrDefault("debit_direct_page", "0"));
            int returnPage = parsedDebitPage == null ? 0 : parsedDebitPage;
            String[] parts = text.trim().split("\\s+", 4);
            if (parts.length < 3) {
                sendText(user.getTelegramId(),
                        "⚠️ Формат неверный. Используйте: <code>XP EXC TICKETS комментарий</code>.",
                        cancelKeyboard());
                return;
            }
            Long xp = parsePositiveLong(parts[0]);
            Long exc = parsePositiveLong(parts[1]);
            Long tickets = parsePositiveLong(parts[2]);
            String comment = parts.length >= 4 ? parts[3] : "Корректировка баланса";
            if (telegramId == null || xp == null || exc == null || tickets == null) {
                sendText(user.getTelegramId(),
                        "⚠️ Проверьте XP, EXC и билеты. Они должны быть числами.",
                        cancelKeyboard());
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
                sendAdminUserCard(user, telegramId, returnPage, "✅ Списание применено для игрока " + telegramId + ".");
            } catch (IllegalArgumentException exception) {
                sendText(user.getTelegramId(), "⚠️ " + escape(exception.getMessage()), cancelKeyboard());
            }
            return;
        }

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
        session.getData().put("bcastText", text);
        session.setState(SessionState.BROADCAST_SCHEDULE_TIME);
        sendText(user.getTelegramId(),
                "🕒 Когда отправить рассылку?\n\nВведите дату и время в формате <code>ДД.ММ.ГГГГ ЧЧ:ММ</code> "
                        + "(время сервера — <b>UTC</b>), либо отправьте <b>0</b>, чтобы разослать прямо сейчас.",
                cancelKeyboard());
    }

    private void handleBroadcastPhoto(AppUser user, UserSession session, String fileId, String caption) {
        session.getData().put("bcastPhotoFileId", fileId);
        session.getData().put("bcastCaption", caption);
        session.setState(SessionState.BROADCAST_SCHEDULE_TIME);
        sendText(user.getTelegramId(),
                "🕒 Когда отправить рассылку?\n\nВведите дату и время в формате <code>ДД.ММ.ГГГГ ЧЧ:ММ</code> "
                        + "(время сервера — <b>UTC</b>), либо отправьте <b>0</b>, чтобы разослать прямо сейчас.",
                cancelKeyboard());
    }

    private void handleBroadcastScheduleTime(AppUser user, UserSession session, String text) {
        String trimmed = text.trim();
        String bcastText = session.getData().get("bcastText");
        String bcastPhotoFileId = session.getData().get("bcastPhotoFileId");
        String bcastCaption = session.getData().get("bcastCaption");

        if ("0".equals(trimmed)) {
            int delivered = bcastPhotoFileId != null
                    ? broadcastPhotoToAll(bcastPhotoFileId, (bcastCaption == null || bcastCaption.isBlank()) ? "" : escape(bcastCaption))
                    : broadcastToAll(escape(bcastText));
            session.reset();
            sendText(user.getTelegramId(), "✅ Рассылка отправлена. Получателей: <b>" + delivered + "</b>.", mainMenuKeyboard(user));
            return;
        }

        java.time.LocalDateTime scheduledAt;
        try {
            scheduledAt = java.time.LocalDateTime.parse(trimmed, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception e) {
            sendText(user.getTelegramId(), "❌ Неверный формат. Используйте ДД.ММ.ГГГГ ЧЧ:ММ или 0 для немедленной отправки.", cancelKeyboard());
            return;
        }
        if (!scheduledAt.isAfter(java.time.LocalDateTime.now())) {
            sendText(user.getTelegramId(), "❌ Дата и время должны быть в будущем.", cancelKeyboard());
            return;
        }

        scheduledBroadcastService.schedule(bcastText, bcastPhotoFileId, bcastCaption, scheduledAt, user.getTelegramId());
        session.reset();
        sendText(user.getTelegramId(), "✅ Рассылка запланирована на <b>" + trimmed + " (UTC)</b>.", mainMenuKeyboard(user));
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
    public void onQuestReportSubmitted(ru.gamebot.platform.event.QuestReportSubmittedEvent event) {
        try {
            notifyModeratorsAboutSubmission(event.getSubmissionId());
        } catch (Exception e) {
            log.error("[QuestReport] Failed to notify moderators for submission {}", event.getSubmissionId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onBrawlQuestAutoVerified(ru.gamebot.platform.event.BrawlQuestAutoVerifiedEvent event) {
        try {
            QuestSubmission approved = questService.getSubmission(event.getSubmissionId());
            notifyUser(approved.getUser().getTelegramId(),
                    "✅ <b>Квест выполнен автоматически!</b>\n\n"
                    + "Прогресс по квесту <b>" + escape(approved.getQuest().getTitle()) + "</b> в Brawl Stars засчитан.\n\n"
                    + "🪙 EXC: <b>+" + approved.getQuest().getRewardCoins() + "</b>\n"
                    + "✨ XP: <b>+" + approved.getQuest().getRewardXp() + "</b>");
            notifyModeratorsAboutAutoApproval(approved, "проверка через Brawl Stars API");
        } catch (Exception e) {
            log.error("[BrawlAutoVerify] Failed to notify user about approved submission {}", event.getSubmissionId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onClashQuestAutoVerified(ru.gamebot.platform.event.ClashQuestAutoVerifiedEvent event) {
        try {
            QuestSubmission approved = questService.getSubmission(event.getSubmissionId());
            notifyUser(approved.getUser().getTelegramId(),
                    "✅ <b>Квест выполнен автоматически!</b>\n\n"
                    + "Прогресс по квесту <b>" + escape(approved.getQuest().getTitle()) + "</b> в Clash of Clans засчитан.\n\n"
                    + "🪙 EXC: <b>+" + approved.getQuest().getRewardCoins() + "</b>\n"
                    + "✨ XP: <b>+" + approved.getQuest().getRewardXp() + "</b>");
            notifyModeratorsAboutAutoApproval(approved, "проверка через Clash of Clans API");
        } catch (Exception e) {
            log.error("[ClashAutoVerify] Failed to notify user about approved submission {}", event.getSubmissionId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onClashRoyaleQuestAutoVerified(ru.gamebot.platform.event.ClashRoyaleQuestAutoVerifiedEvent event) {
        try {
            QuestSubmission approved = questService.getSubmission(event.getSubmissionId());
            notifyUser(approved.getUser().getTelegramId(),
                    "✅ <b>Квест выполнен автоматически!</b>\n\n"
                    + "Прогресс по квесту <b>" + escape(approved.getQuest().getTitle()) + "</b> в Clash Royale засчитан.\n\n"
                    + "🪙 EXC: <b>+" + approved.getQuest().getRewardCoins() + "</b>\n"
                    + "✨ XP: <b>+" + approved.getQuest().getRewardXp() + "</b>");
            notifyModeratorsAboutAutoApproval(approved, "проверка через Clash Royale API");
        } catch (Exception e) {
            log.error("[ClashRoyaleAutoVerify] Failed to notify user about approved submission {}", event.getSubmissionId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onExternalQuestApproved(ru.gamebot.platform.event.ExternalQuestApprovedEvent event) {
        try {
            QuestSubmission approved = questService.getSubmission(event.getSubmissionId());
            notifyUser(approved.getUser().getTelegramId(),
                    "✅ <b>Партнёр подтвердил выполнение!</b>\n\n"
                    + "Квест <b>" + escape(approved.getQuest().getTitle()) + "</b> засчитан.\n\n"
                    + "🪙 EXC: <b>+" + approved.getQuest().getRewardCoins() + "</b>");
            notifyModeratorsAboutAutoApproval(approved, "подтверждено партнёрской сетью");
        } catch (Exception e) {
            log.error("[ActionPay] Failed to notify user about approved submission {}", event.getSubmissionId(), e);
        }
    }

    /** Чисто информационное уведомление модераторам об автоматически закрытом квесте — без кнопок, действие не требуется. */
    private void notifyModeratorsAboutAutoApproval(QuestSubmission submission, String methodLabel) {
        AppUser player = submission.getUser();
        String playerLink = player.getTelegramUsername() != null
                ? "<a href=\"https://t.me/" + player.getTelegramUsername() + "\">@" + player.getTelegramUsername() + "</a>"
                : "<a href=\"tg://user?id=" + player.getTelegramId() + "\">" + escape(player.getNickname()) + "</a>";
        Quest quest = submission.getQuest();
        long completionNumber = submission.getCompletionDisplayId() != null ? submission.getCompletionDisplayId() : submission.getId();
        String text = "🤖 <b>Квест закрыт автоматически (З-" + completionNumber + ")</b>\n\n"
                + "👤 Игрок: <b>" + escape(player.getNickname()) + "</b> (" + playerLink + ")\n"
                + "🆔 ID: <b>" + player.getTelegramId() + "</b>\n"
                + "🎯 Квест: <b>" + escape(quest.getTitle()) + "</b>\n"
                + "🎮 Игра: <b>" + escape(quest.getGameName()) + "</b>\n"
                + "⚙️ Способ: " + methodLabel + "\n"
                + "🏆 Награда: +" + quest.getRewardXp() + " XP, +" + quest.getRewardCoins() + " EXC\n"
                + "📅 Засчитано: <b>" + submission.getUpdatedAt().format(DATE_TIME_FORMATTER) + "</b>";
        for (Long recipient : adminService.strictModeratorIds()) {
            try {
                // Превью веб-страницы отключено намеренно: ссылка на профиль игрока (playerLink)
                // может показать чужеродный/подставной preview с чужого t.me-профиля (замечено
                // 2026-09-02 — со ссылки на аккаунт игрока подтягивалась карточка с посторонней
                // ссылкой telegra.ph, похожей на фишинг). Обычный sendText() такой флаг не даёт.
                SendMessage msg = new SendMessage();
                msg.setChatId(recipient.toString());
                msg.setText(text);
                msg.setParseMode("HTML");
                msg.setDisableWebPagePreview(true);
                msg.setReplyMarkup(keyboardFactory.rowsLayout(List.of(
                        List.of(keyboardFactory.callback("🏠 Меню", "menu:main")))));
                execute(msg);
            } catch (Exception e) {
                log.warn("Failed to notify moderator {} about auto-approved submission {}", recipient, submission.getId(), e);
            }
        }
    }

    @org.springframework.context.event.EventListener
    public void onSquadPrize(ru.gamebot.platform.event.SquadPrizeEvent event) {
        String msg = "⚔️ <b>Ваш отряд победил в еженедельном рейтинге!</b>\n\n"
                + "🏆 Отряд <b>«" + escape(event.getSquad().getName()) + "»</b> занял первое место.\n"
                + "📊 Суммарный XP за неделю: <b>" + String.format("%,d", event.getTotalWeeklyXp()).replace(',', ' ') + "</b>\n\n"
                + "🎁 Вам начислено: <b>+" + String.format("%,d", event.getPrizePerMember()).replace(',', ' ') + " EXC</b>";
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("⚔️ Мой отряд", "menu:squads"))
        ));
        for (ru.gamebot.platform.domain.model.AppUser member : event.getMembers()) {
            try {
                sendText(member.getTelegramId(), msg, keyboard);
            } catch (Exception e) {
                log.warn("Failed to notify squad member {} about prize", member.getTelegramId(), e);
            }
        }
    }

    @org.springframework.context.event.EventListener
    public void onCooldownExpired(ru.gamebot.platform.event.CooldownExpiredEvent event) {
        String msg = "🎮 <b>Кулдаун снят!</b>\n\n"
                + "Квест <b>«" + escape(event.getQuestTitle()) + "»</b> в игре <b>"
                + escape(event.getGameName()) + "</b> снова доступен.\n\n"
                + "Заходи и продолжай прогресс 👇";
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🗺️ Перейти к квестам", "menu:quests"))
        ));
        try {
            sendText(event.getTelegramId(), msg, keyboard);
        } catch (Exception e) {
            log.warn("Failed to send cooldown notification to {}", event.getTelegramId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onQuestExpired(ru.gamebot.platform.event.QuestExpiredEvent event) {
        String msg = "⏰ <b>Время вышло</b>\n\n"
                + "Квест «" + escape(event.getQuestTitle()) + "» просрочен — EXC не начислены.\n\n"
                + "Можешь взять его снова и выполнить в срок 👇";
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🗺️ К квестам", "menu:quests"))
        ));
        try {
            sendText(event.getTelegramId(), msg, keyboard);
        } catch (Exception e) {
            log.warn("Failed to send quest expired notification to {}", event.getTelegramId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onQuestDeadlineWarning(ru.gamebot.platform.event.QuestDeadlineWarningEvent event) {
        long h = event.getMinutesLeft() / 60;
        long m = event.getMinutesLeft() % 60;
        String timeStr = h > 0 ? h + " ч " + m + " мин" : m + " мин";
        String msg = "⚠️ <b>Квест истекает через " + timeStr + "!</b>\n\n"
                + "«" + escape(event.getQuestTitle()) + "»\n\n"
                + "Успей отправить доказательство 👇";
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("📤 Мои квесты", "menu:myquests"))
        ));
        try {
            sendText(event.getTelegramId(), msg, keyboard);
        } catch (Exception e) {
            log.warn("Failed to send deadline warning to {}", event.getTelegramId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onWeeklyDigestActive(ru.gamebot.platform.event.WeeklyDigestActiveEvent event) {
        String rankLine = event.getWeeklyRank() > 0 ? "#" + event.getWeeklyRank() : "—";
        String msg = "📊 <b>Итоги твоей недели в EGC</b>\n\n"
                + "✅ Квестов выполнено: <b>" + event.getCompletedQuests() + "</b>\n"
                + "💰 EXC заработано: <b>" + String.format("%,d", event.getEarnedExc()).replace(',', ' ') + "</b>\n"
                + "🏆 Место в лиге: <b>" + escape(event.getLeagueName()) + " · " + rankLine + "</b>\n"
                + "⭐ XP за неделю: <b>" + event.getWeeklyXp() + "</b>\n"
                + "📈 До следующего уровня: <b>" + event.getXpToNextLevel() + " XP</b>\n\n"
                + "Новые квесты уже ждут! 👇";
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🗺️ Перейти к квестам", "menu:quests"))
        ));
        try {
            sendText(event.getTelegramId(), msg, keyboard);
        } catch (Exception e) {
            log.warn("Failed to send weekly digest to {}", event.getTelegramId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onWeeklyDigestInactive(ru.gamebot.platform.event.WeeklyDigestInactiveEvent event) {
        String msg = "👋 <b>Давно не виделись!</b>\n\n"
                + "На прошлой неделе ты пропустил:\n"
                + "— <b>" + event.getNewQuestsCount() + "</b> новых квестов\n"
                + "— Колесо фортуны крутили <b>" + event.getTotalSpinsCount() + "</b> раз\n\n"
                + "Возвращайся — квесты ждут! 👇";
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🗺️ К квестам", "menu:quests"))
        ));
        try {
            sendText(event.getTelegramId(), msg, keyboard);
        } catch (Exception e) {
            log.warn("Failed to send inactive digest to {}", event.getTelegramId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onDormancyReengagement(ru.gamebot.platform.event.DormancyReengagementEvent event) {
        String msg = switch (event.getTier()) {
            case 1 -> "👋 <b>Давно не заходил!</b>\n\n"
                    + "Прошло уже " + event.getDaysSinceActive() + " дней. Мы соскучились — держи <b>+"
                    + event.getExcGranted() + " EXC</b>, чтобы было проще вернуться в игру! 🎁";
            case 2 -> "🔥 <b>Месяц без тебя — это долго!</b>\n\n"
                    + "За это время появилось много новых квестов и турниров. Специально для тебя: <b>+"
                    + event.getExcGranted() + " EXC</b> на баланс. Заходи и посмотри, что нового! 🚀";
            default -> "🎉 <b>С возвращением!</b>\n\n"
                    + "Тебя не было " + event.getDaysSinceActive() + " дней — этого более чем достаточно, чтобы соскучиться. "
                    + "Лови <b>+" + event.getExcGranted() + " EXC</b> и заходи глянуть, что изменилось на платформе.";
        };
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🗺️ К квестам", "menu:quests"))
        ));
        try {
            sendText(event.getTelegramId(), msg, keyboard);
        } catch (Exception e) {
            log.warn("Failed to send dormancy re-engagement message to {}", event.getTelegramId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onReferralLeaderboardReward(ru.gamebot.platform.event.ReferralLeaderboardRewardEvent event) {
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🤝 Рефералы", "menu:referrals"))
        ));
        for (ru.gamebot.platform.service.UserService.ReferralRankEntry entry : event.getWinners()) {
            String msg = "🤝 <b>Топ рефереров недели!</b>\n\n"
                    + "Ты занял <b>#" + entry.rank() + "</b> место по реферальным доходам за неделю "
                    + "(+" + entry.weeklyReferralExc() + " EXC от рефералов).\n\n"
                    + "🎁 Бонус за место в топе: <b>+" + entry.prizeExc() + " EXC</b>!\n\n"
                    + "Приглашай друзей и зарабатывай ещё больше 👇";
            try {
                sendText(entry.user().getTelegramId(), msg, keyboard);
            } catch (Exception e) {
                log.warn("Failed to notify referral leaderboard winner {}", entry.user().getTelegramId(), e);
            }
        }
    }

    @org.springframework.context.event.EventListener
    public void onOnboardingReminder(ru.gamebot.platform.event.OnboardingReminderEvent event) {
        String msg = switch (event.getNotificationNumber()) {
            case 1 -> "🎮 <b>Ты почти начал!</b>\n\n"
                    + "Первый квест занимает буквально 5 минут. Выбери игру и заработай первые EXC! 💰";
            case 2 -> "⏰ <b>Не забудь о первом квесте!</b>\n\n"
                    + "Каждый день квесты приносят EXC — которые можно вывести реальными деньгами.\n"
                    + "Начни прямо сейчас — первый шаг самый важный! 🚀";
            default -> "🔔 <b>Последнее напоминание</b>\n\n"
                    + "Выбери игру и возьми первый квест — сообщество EGC уже зарабатывает!\n"
                    + "Не упусти свои первые EXC 🏆";
        };
        InlineKeyboardMarkup keyboard = keyboardFactory.rowsLayout(List.of(
                List.of(keyboardFactory.callback("🎮 Выбрать квест", "onboarding:browse_all"))
        ));
        try {
            sendText(event.getTelegramId(), msg, keyboard);
        } catch (Exception e) {
            log.warn("Failed to send onboarding reminder to {}", event.getTelegramId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onLeagueReward(LeagueRewardEvent event) {
        String msg = "🏆 <b>Итоги недели — " + escape(event.getLeagueName()) + "</b>\n\n"
                + "Ты набрал <b>" + event.getWeeklyXp() + " XP</b> за эту неделю.\n\n"
                + "🪙 Призовые: <b>+" + event.getExcPrize() + " EXC</b> начислены на баланс!\n\n"
                + "Новая неделя — новые квесты. Борись за более высокую лигу! 💪";
        try {
            sendText(event.getTelegramId(), msg, null);
        } catch (Exception e) {
            log.warn("Failed to send league reward notification to {}", event.getTelegramId(), e);
        }
    }

    @org.springframework.context.event.EventListener
    public void onHallOfFame(ru.gamebot.platform.event.HallOfFameEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏆✨ <b>ЗАЛ СЛАВЫ EGC</b> ✨🏆\n\n")
          .append("<i>Топ игроков недели по опыту</i>\n\n");
        for (ru.gamebot.platform.event.HallOfFameEvent.HallEntry entry : event.getTop3()) {
            int rank = entry.rank();
            String nameLine = "<b>" + escape(entry.nickname()) + "</b>"
                    + (entry.username() != null ? " (@" + entry.username() + ")" : "");
            switch (rank) {
                case 1 -> sb.append("👑 ").append(nameLine).append(" — Чемпион недели!\n")
                        .append("   ⚡️ <b>").append(entry.weeklyXp()).append(" XP</b> · 🏅 ").append(entry.totalXp()).append(" XP всего\n\n");
                case 2 -> sb.append("🥈 ").append(nameLine).append("\n")
                        .append("   ⚡️ <b>").append(entry.weeklyXp()).append(" XP</b> за неделю\n\n");
                case 3 -> sb.append("🥉 ").append(nameLine).append("\n")
                        .append("   ⚡️ <b>").append(entry.weeklyXp()).append(" XP</b> за неделю\n\n");
                default -> sb.append(rank).append(". ").append(nameLine).append(" — ").append(entry.weeklyXp()).append(" XP\n\n");
            }
        }
        sb.append("\n")
          .append("👏 Поздравляем лучших игроков недели!\n")
          .append("🎯 Новая неделя уже началась — новые квесты, новые шансы попасть в топ.\n\n")
          .append("Присоединяйся → @").append(getBotUsername());

        String caption = sb.toString();
        String chatId = requiredChannelChatId();

        // Пробуем отправить с баннером
        try {
            SendPhoto sendPhoto = new SendPhoto();
            sendPhoto.setChatId(chatId);
            sendPhoto.setCaption(caption);
            sendPhoto.setParseMode("HTML");

            if (hallOfFameFileId != null) {
                sendPhoto.setPhoto(new InputFile(hallOfFameFileId));
            } else {
                try (java.io.InputStream is = getClass().getResourceAsStream("/hall_of_fame.png")) {
                    if (is == null) throw new java.io.IOException("hall_of_fame.png not found");
                    sendPhoto.setPhoto(new InputFile(new java.io.ByteArrayInputStream(is.readAllBytes()), "hall_of_fame.png"));
                }
            }

            org.telegram.telegrambots.meta.api.objects.Message sent = execute(sendPhoto);
            if (hallOfFameFileId == null && sent.getPhoto() != null && !sent.getPhoto().isEmpty()) {
                hallOfFameFileId = sent.getPhoto().get(sent.getPhoto().size() - 1).getFileId();
            }
        } catch (Exception e) {
            log.warn("Failed to send hall of fame with banner, falling back to text", e);
            try {
                SendMessage msg = new SendMessage();
                msg.setChatId(chatId);
                msg.setText(caption);
                msg.setParseMode("HTML");
                execute(msg);
            } catch (TelegramApiException ex) {
                log.error("Failed to post hall of fame to channel", ex);
            }
        }
    }

    private String buildSquadTeaserText(List<ru.gamebot.platform.service.SquadService.SquadRankEntry> topSquads) {
        StringBuilder sb = new StringBuilder("🛡️ <b>Гонка отрядов — экватор недели</b>\n\n");
        int rank = 1;
        for (ru.gamebot.platform.service.SquadService.SquadRankEntry entry : topSquads) {
            String medal = switch (rank) {
                case 1 -> "🥇";
                case 2 -> "🥈";
                case 3 -> "🥉";
                default -> rank + ".";
            };
            sb.append(medal).append(" <b>").append(escape(entry.squad().getName())).append("</b>\n")
              .append("   ⚡️ ").append(entry.weeklyXp()).append(" XP · 👥 ").append(entry.memberCount()).append(" чел.\n\n");
            rank++;
        }
        sb.append("🏆 Приз победителю — 10 000 EXC на всех участников! Финиш в воскресенье.");
        return sb.toString();
    }

    /** Публикация — только после одобрения администратора (см. {@link #handleAdminFeedAction}). */
    @org.springframework.context.event.EventListener
    public void onSquadMidweekTeaser(ru.gamebot.platform.event.SquadMidweekTeaserEvent event) {
        pendingSquadTeaserText = buildSquadTeaserText(event.getTopSquads());
        sendSquadFeedCard();
    }

    private void sendSquadFeedCard() {
        String text = pendingSquadTeaserText;
        if (text == null) return;
        String preview = "🧾 <b>Тизер отрядов — на согласование</b>\n\n" + text;
        InlineKeyboardMarkup markup = keyboardFactory.smartLayout(List.of(
                keyboardFactory.callback("✅ Опубликовать", "adminfeed:squad:approve"),
                keyboardFactory.callback("✏️ Изменить", "adminfeed:squad:edit"),
                keyboardFactory.callback("❌ Отклонить", "adminfeed:squad:reject")));
        for (Long adminId : adminService.resolvedAdminIds()) {
            try {
                sendText(adminId, preview, markup);
            } catch (Exception e) {
                log.warn("Failed to send squad teaser candidate to admin {}", adminId, e);
            }
        }
    }

    @org.springframework.context.event.EventListener
    public void onNewsPublished(NewsPublishedEvent event) {
        String message = "📰 <b>" + escape(event.getTitle()) + "</b>\n\n" + event.getBody();
        int delivered = broadcastToAll(message);
        log.info("[News] Broadcast '{}' → {} users", event.getTitle(), delivered);
    }

    @org.springframework.context.event.EventListener
    public void onScheduledBroadcastDue(ru.gamebot.platform.event.ScheduledBroadcastDueEvent event) {
        try {
            ru.gamebot.platform.domain.model.ScheduledBroadcast b = scheduledBroadcastService.getById(event.getBroadcastId());
            if (b == null || b.getStatus() != ru.gamebot.platform.domain.enums.ScheduledBroadcastStatus.PENDING) return;
            int delivered = b.getPhotoFileId() != null
                    ? broadcastPhotoToAll(b.getPhotoFileId(), (b.getCaption() == null || b.getCaption().isBlank()) ? "" : escape(b.getCaption()))
                    : broadcastToAll(escape(b.getText()));
            scheduledBroadcastService.markSent(b.getId(), delivered);
            log.info("[ScheduledBroadcast] Sent #{} -> {} users", b.getId(), delivered);
        } catch (Exception e) {
            log.error("[ScheduledBroadcast] Failed to send due broadcast {}", event.getBroadcastId(), e);
        }
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

    private void handlePhoneShare(AppUser user, UserSession session, org.telegram.telegrambots.meta.api.objects.Contact contact) {
        String phone = contact.getPhoneNumber();
        String pendingWithdrawal = session.getData().get("pendingWithdrawal");
        session.reset();
        user.setPhoneNumber(phone);
        userService.save(user);
        removeReplyKeyboard(user.getTelegramId());
        userService.findDuplicatePhoneUser(phone, user.getTelegramId()).ifPresent(dup -> {
            user.setFraudSuspect(true);
            userService.save(user);
            log.warn("Phone duplicate detected: {} and {} share phone {}", user.getTelegramId(), dup.getTelegramId(), phone);
        });
        if ("rub".equals(pendingWithdrawal)) {
            session.setState(SessionState.WITHDRAWAL_INPUT);
            sendWithdrawalScreen(user);
        } else if ("ton".equals(pendingWithdrawal)) {
            sendWithdrawalTonWalletQuestion(user);
        } else {
            sendMainMenu(user, null);
        }
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
        int ratioPercent = (int) Math.round(ratio * 100);
        long rubles = Math.round(amount * ratio / 100.0);
        session.getData().put("withdrawAmount", String.valueOf(amount));
        session.getData().put("withdrawRubles", String.valueOf(rubles));
        session.setState(SessionState.WITHDRAWAL_DETAILS);
        sendText(user.getTelegramId(),
                "💳 <b>Введите реквизиты для перевода</b>\n\n"
                        + "💸 Сумма: <b>" + amount + " EXC → ~" + rubles + " ₽</b>\n"
                        + "💱 Курс: <b>" + rateString(ratioPercent) + "</b>\n\n"
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
            RewardRequest withdrawalReq = rewardService.createWithdrawalRequestWithDetails(user, amount, rubles, 0, details);
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
            String savedSponsorId = session.getData().get("sponsorId");
            String savedPostpay = session.getData().get("postpay");
            session.getData().clear();
            if (savedSponsorId != null) session.getData().put("sponsorId", savedSponsorId);
            if (savedPostpay != null) session.getData().put("postpay", savedPostpay);
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
                + ("true".equals(d.get("flat")) ? "" : "📚 Формат: <b>" + escape(d.getOrDefault("category", "—")) + "</b>\n")
                + "🕹️ Платформа: <b>" + escape(d.getOrDefault("platform", "—")) + "</b>\n"
                + "⏳ Темп: <b>" + escape(d.getOrDefault("duration", "—")) + "</b>\n"
                + "👥 Лимит: <b>" + d.getOrDefault("limit", "—") + "</b>\n\n"
                + "🏆 <b>Награда</b>\n"
                + "✨ +" + d.getOrDefault("xp", "0") + " XP\n"
                + "🪙 +" + d.getOrDefault("coins", "0") + " монет\n"
                + "🎟 +" + d.getOrDefault("tickets", "0") + " билетов\n\n"
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
            String catBackData = session.getData().getOrDefault("admin_quest_back_data", "admin:edit");
            Long catQuestId = session.getQuestId();
            session.reset();
            sendText(user.getTelegramId(), "✅ Категория обновлена: <b>" + escape(category) + "</b>" + extendNote, null);
            Quest catQuest = questService.getQuest(catQuestId);
            returnToQuestEditor(user, catQuest, catBackData);
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
                String platBackData = session.getData().getOrDefault("admin_quest_back_data", "admin:edit");
                session.reset();
                sendText(user.getTelegramId(), "✅ Платформы обновлены: <b>" + escape(selected) + "</b>", null);
                returnToQuestEditor(user, q, platBackData);
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
        quest.setTicketReward(Integer.parseInt(session.getData().getOrDefault("tickets", "0")));
        quest.setInstruction(session.getData().get("instruction"));
        quest.setRequirements(session.getData().get("requirements"));
        quest.setParticipantLimit(Integer.parseInt(session.getData().getOrDefault("limit", "100")));
        quest.setCouncilOnly(councilOnly);
        quest.setPhotoFileId(session.getData().get("photoFileId"));

        // Link to sponsor if quest was created from sponsor/postpay flow
        String sponsorIdStr = session.getData().get("sponsorId");
        if (sponsorIdStr != null) {
            try {
                long sid = Long.parseLong(sponsorIdStr);
                quest.setSponsored(true);
                quest.setSponsorId(sid);
            } catch (NumberFormatException ignored) {}
        }

        questService.createQuest(quest);

        String backAction = sponsorIdStr != null
                ? (session.getData().containsKey("postpay") ? "admin:postpay:view:" + sponsorIdStr : "admin:sponsors:view:" + sponsorIdStr)
                : null;

        session.reset();
        String label = councilOnly ? "Council-квест" : "обычный квест";
        if (backAction != null) {
            sendText(user.getTelegramId(),
                    "✅ Спонсорский квест создан и привязан к кампании.",
                    backMenuKeyboard(backAction));
        } else {
            sendText(user.getTelegramId(),
                    "✅ Новый " + label + " создан и сразу опубликован.",
                    mainMenuKeyboard(user));
        }
        if (quest.isSponsored()) {
            sendText(user.getTelegramId(), buildQuestAnnouncement(quest), null);
        }
    }

    private String buildQuestAnnouncement(Quest quest) {
        java.time.LocalDate start = java.time.LocalDate.now();
        String startStr = start.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        String endStr = quest.getDurationDays() > 0
                ? start.plusDays(quest.getDurationDays()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                : null;
        String period = endStr != null ? startStr + " - " + endStr : startStr;
        boolean isSponsored = quest.isSponsored();
        String header = isSponsored ? "🎯 НОВЫЙ СПОНСОРСКИЙ КВЕСТ" : "🎯 НОВЫЙ КВЕСТ";
        return header + "\n\n"
                + "📋 Квест: \"" + quest.getTitle() + "\"\n"
                + "💰 Награда: " + String.format("%,d", quest.getRewardCoins()) + " EXC\n\n"
                + "📅 Период: " + period + "\n\n"
                + "👉 Выполнить → @" + getBotUsername();
    }

    private void updateQuestTitle(AppUser user, UserSession session, String text) {
        Quest quest = questService.getQuest(session.getQuestId());
        quest.setTitle(text.trim());
        questService.save(quest);
        String backData = session.getData().getOrDefault("admin_quest_back_data", "admin:edit");
        session.reset();
        returnToQuestEditor(user, quest, backData);
    }

    private void updateQuestDescription(AppUser user, UserSession session, String text) {
        Quest quest = questService.getQuest(session.getQuestId());
        quest.setDescription(text.trim());
        questService.save(quest);
        String backData = session.getData().getOrDefault("admin_quest_back_data", "admin:edit");
        session.reset();
        returnToQuestEditor(user, quest, backData);
    }

    private void updateQuestCondition(AppUser user, UserSession session, String text) {
        String trimmed = text.trim();
        if (trimmed.length() > 150) {
            sendText(user.getTelegramId(),
                    "⚠️ Слишком длинно (" + trimmed.length() + " символов, максимум 150). Сократите и отправьте снова:",
                    cancelKeyboard());
            return;
        }
        Quest quest = questService.getQuest(session.getQuestId());
        quest.setShortCondition(trimmed);
        questService.save(quest);
        String backData = session.getData().getOrDefault("admin_quest_back_data", "admin:edit");
        session.reset();
        returnToQuestEditor(user, quest, backData);
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
        String backData = session.getData().getOrDefault("admin_quest_back_data", "admin:edit");
        session.reset();
        returnToQuestEditor(user, quest, backData);
    }

    private void returnToQuestEditor(AppUser user, Quest quest, String backData) {
        if (quest.isSponsored()) {
            sendSponsorQuestEditor(user, quest.getId(), backData);
        } else {
            sendAdminQuestEditor(user, quest.getId(), backData);
        }
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
        questService.deleteQuest(questId);
        sendText(user.getTelegramId(),
                "🗑️ Квест скрыт от пользователей.\n\n"
                        + "🎯 Название: <b>" + escape(quest.getTitle()) + "</b>\n\n"
                        + "После следующего деплоя квест не вернётся.",
                backMenuKeyboard(backData));
    }

    private void notifyModeratorsAboutSubmission(Long submissionId) {
        QuestSubmission submission = questService.getSubmission(submissionId);

        // AI verification for photo submissions
        String aiNote = "";
        if (claudeVisionService.isEnabled() && "photo".equals(submission.getMediaType())) {
            try {
                ru.gamebot.platform.service.AiVerificationResult aiResult =
                        claudeVisionService.verify(submission);
                if (aiResult != null) {
                    questService.saveAiResult(submissionId, aiResult);
                    if (aiResult.isApprove()) {
                        aiAutoApprove(submission, aiResult);
                        return;
                    } else if (aiResult.isReject()) {
                        aiAutoReject(submission, aiResult);
                        return;
                    }
                    // MANUAL — show AI note to moderators
                    int pct = (int) Math.round(aiResult.confidence() * 100);
                    aiNote = "\n\n🤖 <b>AI:</b> MANUAL (" + pct + "%) — " + escape(aiResult.reason());
                }
            } catch (Exception e) {
                log.warn("AI check failed for submission {}, falling back to manual review", submissionId, e);
            }
        }

        AppUser notifUser = submission.getUser();
        String notifUserLink = notifUser.getTelegramUsername() != null
                ? "<a href=\"https://t.me/" + notifUser.getTelegramUsername() + "\">@" + notifUser.getTelegramUsername() + "</a>"
                : "<a href=\"tg://user?id=" + notifUser.getTelegramId() + "\">" + escape(notifUser.getNickname()) + "</a>";
        String caption = "🧾 <b>Заявка К-" + (submission.getDisplayId() != null ? submission.getDisplayId() : submissionId) + " на проверку</b>\n\n"
                + "👤 Игрок: <b>" + escape(notifUser.getNickname()) + "</b> (" + notifUserLink + ")\n"
                + "🆔 ID: <b>" + notifUser.getTelegramId() + "</b>\n"
                + "🎯 Квест: <b>" + escape(submission.getQuest().getTitle()) + "</b>\n"
                + "🎮 Игра: <b>" + escape(submission.getQuest().getGameName()) + "</b>\n"
                + rewardPreviewLine(submission) + "\n"
                + "📅 Отправлено: <b>" + submission.getUpdatedAt().format(DATE_TIME_FORMATTER) + "</b>\n"
                + "💬 Комментарий: " + escape(submission.getUserComment())
                + aiNote;

        InlineKeyboardMarkup markup = keyboardFactory.smartLayout(List.of(
                keyboardFactory.callback("✅ Одобрить", "mod:ok:" + submissionId),
                keyboardFactory.callback("❌ Отклонить", "mod:no:" + submissionId),
                keyboardFactory.callback("❓ Уточнить", "mod:more:" + submissionId),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));

        // Только модераторы, без админов — по явному запросу пользователя.
        Set<Long> recipients = adminService.strictModeratorIds();

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

    private void aiAutoApprove(QuestSubmission submission, ru.gamebot.platform.service.AiVerificationResult aiResult) {
        try {
            // Используем computeReward (с учётом снижения) — то же что применит approveSubmission
            QuestService.RewardPreview computed = questService.computeReward(submission.getUser(), submission.getQuest());
            UserService.RewardGrant rewardGrant = userService.previewReward(
                    submission.getUser(),
                    computed.xp(),
                    computed.coins(),
                    0
            );
            boolean isFirstQuest = submission.getUser().getCompletedQuests() == 0;
            QuestSubmission approved = questService.approveSubmission(submission.getId());
            String firstQuestBonus = isFirstQuest && approved.getUser().getReferredByTelegramId() != null
                    ? "\n🎁 Бонус за первый квест: <b>+3 000 EXC</b>" : "";
            int pct = (int) Math.round(aiResult.confidence() * 100);
            log.info("AI auto-approved submission {} (confidence={})", submission.getId(), aiResult.confidence());
            try {
                notifyUser(approved.getUser().getTelegramId(),
                        "🤖 <b>Автопроверка пройдена!</b>\n\n"
                        + "Ваш отчёт по квесту <b>" + escape(approved.getQuest().getTitle()) + "</b> одобрен AI-модератором (" + pct + "%).\n\n"
                        + "✨ XP: <b>+" + rewardGrant.xp() + "</b>\n"
                        + "🪙 EXC: <b>+" + rewardGrant.totalExc() + "</b>\n"
                        + formatExcBonusLine(rewardGrant)
                        + firstQuestBonus);
            } catch (Exception e) {
                log.warn("Could not notify user {} about AI approval: {}", approved.getUser().getTelegramId(), e.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to auto-approve submission {}: {}", submission.getId(), e.getMessage(), e);
        }
    }

    private void aiAutoReject(QuestSubmission submission, ru.gamebot.platform.service.AiVerificationResult aiResult) {
        try {
            String rejectComment = "AI-проверка: " + aiResult.reason();
            questService.rejectSubmission(submission.getId(), rejectComment);
            int pct = (int) Math.round(aiResult.confidence() * 100);
            notifyUser(submission.getUser().getTelegramId(),
                    "🤖 <b>Автопроверка</b>\n\n"
                    + "Ваш отчёт по квесту <b>" + escape(submission.getQuest().getTitle()) + "</b> не прошёл автоматическую проверку (" + pct + "%).\n\n"
                    + "❌ Причина: " + escape(aiResult.reason()) + "\n\n"
                    + "Вы можете подать повторный отчёт через 1 час с более чёткими доказательствами.");
            log.info("AI auto-rejected submission {} (confidence={}): {}", submission.getId(), aiResult.confidence(), aiResult.reason());
        } catch (Exception e) {
            log.error("Failed to auto-reject submission {}: {}", submission.getId(), e.getMessage(), e);
        }
    }

    public void notifyAdminsAboutWithdrawal(AppUser user, RewardRequest req) {
        String username = user.getTelegramUsername();
        String userLink = (username != null && !username.isBlank())
                ? "\n✉️ Написать: <a href=\"https://t.me/" + username + "\">@" + username + "</a>"
                : "\n✉️ Telegram ID: <code>" + user.getTelegramId() + "</code>";

        String details = "";
        String payoutDetails = req.getPayoutDetails();
        if (payoutDetails != null && payoutDetails.startsWith("TON:")) {
            // Формат: TON:<wallet>:rubles=<N>
            String wallet = cryptoWalletFromPayoutDetails(payoutDetails);
            long rubles = fixedOrCurrentRub(req);
            java.math.BigDecimal tonRate = exchangeRateService.getTonRubRate();
            java.math.BigDecimal tonAmount = exchangeRateService.rubToTon(java.math.BigDecimal.valueOf(rubles));
            details = "\n💰 К отправке: ~<b>" + tonAmount + " GRAM (TON)</b>"
                    + "\n📈 Курс: 1 TON = " + tonRate.setScale(2, java.math.RoundingMode.HALF_DOWN) + " ₽"
                    + "\n💵 Рублёвый эквивалент: <b>" + rubles + " ₽</b>"
                    + "\n💎 Кошелёк: <code>" + escape(wallet != null ? wallet : "") + "</code>";
        } else if (payoutDetails != null) {
            details = "\n💎 Реквизиты: <code>" + escape(payoutDetails) + "</code>";
        }

        String text = "💸 <b>Новая заявка на вывод EXC</b>\n\n"
                + "👤 Игрок: <b>" + escape(user.getNickname()) + "</b>\n"
                + "🆔 Telegram ID: <b>" + user.getTelegramId() + "</b>"
                + userLink + "\n"
                + "🌍 Страна: <b>" + escape(user.getCountry() != null ? user.getCountry() : "Не указана") + "</b>\n"
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
        } else if (data.startsWith("mod:withdrawal:multiblock:")) {
            String payload = data.substring("mod:withdrawal:multiblock:".length());
            String[] parts = payload.split(":");
            long reqId = Long.parseLong(parts[0]);
            long otherTgId = Long.parseLong(parts[1]);
            String blockReason = "Мультиаккаунт — нарушение п. 6 Правил EGC";
            RewardRequest req = rewardService.rejectRequest(reqId, blockReason);
            AppUser requester = req.getUser();
            AppUser other = userService.findByTelegramId(otherTgId).orElse(null);
            userService.blockAndConfiscate(requester.getTelegramId(), blockReason);
            String otherNick = "(неизвестен)";
            if (other != null) {
                userService.blockAndConfiscate(other.getTelegramId(), blockReason);
                otherNick = other.getNickname();
            }
            notifyUserWithdrawalRejected(req);
            sendText(user.getTelegramId(),
                    "🚫 <b>Готово</b>\n\n"
                    + "Заявка В-" + reqDisplayId(req) + " отклонена.\n"
                    + "Заблокированы аккаунты:\n"
                    + "• <b>" + escape(requester.getNickname()) + "</b>\n"
                    + "• <b>" + escape(otherNick) + "</b>\n\n"
                    + "Причина: " + blockReason,
                    keyboardFactory.rowsLayout(List.of(
                            List.of(keyboardFactory.callback("⬅️ К заявкам", "mod:withdrawals"))
                    )));
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
            String type = isCryptoWithdrawal(req) ? "💎 TON" : "💸 ₽";
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
        String detailsLine;
        if (isCryptoWithdrawal(req)) {
            String wallet = cryptoWalletFromPayoutDetails(req.getPayoutDetails());
            detailsLine = "\n💎 Способ: <b>" + cryptoMethodLabel(req.getPayoutDetails()) + "</b>\n📬 Кошелёк: <code>" + escape(wallet) + "</code>";
        } else if (req.getPayoutDetails() != null) {
            detailsLine = "\n💵 Способ: <b>Рубли (СБП / Сбербанк)</b>\n💳 Реквизиты: <code>" + escape(req.getPayoutDetails()) + "</code>";
        } else {
            detailsLine = "\n💵 Способ: <b>Рубли (СБП / Сбербанк)</b>";
        }
        long rubles = fixedOrCurrentRub(req);
        String payoutSuffix = isCryptoWithdrawal(req) ? cryptoPayoutSuffix(rubles) : "";
        long dupCount = rewardService.countPendingWithdrawalsByUser(requester);
        String dupWarning = dupCount > 1
                ? "\n\n⚠️ <b>ВНИМАНИЕ: у этого пользователя " + dupCount + " активные заявки на вывод!</b> Оплачивайте только эту." : "";
        java.util.List<RewardRequest> destDupsMod = rewardService.findDuplicateDestinationWithdrawals(req);
        String destDupWarningMod = destDupsMod.isEmpty() ? "" : "\n\n🚨 <b>МУЛЬТИАККАУНТ!</b> Этот реквизит уже получил выплату другой аккаунт: <b>"
                + escape(destDupsMod.get(0).getUser().getNickname()) + "</b> (В-" + reqDisplayId(destDupsMod.get(0)) + ", "
                + destDupsMod.get(0).getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) + ")";
        String phoneLineMod = requester.getPhoneNumber() != null
                ? "\n📱 Телефон: <code>" + escape(requester.getPhoneNumber()) + "</code>"
                : "\n📱 Телефон: <b>не подтверждён</b>";
        java.util.Optional<AppUser> phoneDupMod = userService.findDuplicatePhoneUser(requester.getPhoneNumber(), requester.getTelegramId());
        String phoneDupWarningMod = phoneDupMod.isPresent()
                ? "\n\n🚨 <b>МУЛЬТИАККАУНТ!</b> Этот номер телефона уже зарегистрирован на аккаунте: <b>" + escape(phoneDupMod.get().getNickname()) + "</b>"
                : "";
        java.util.Optional<AppUser> multiblockTargetMod = destDupsMod.isEmpty()
                ? phoneDupMod
                : java.util.Optional.of(destDupsMod.get(0).getUser());
        List<List<InlineKeyboardButton>> modWdRows = new ArrayList<>();
        modWdRows.add(List.of(
                keyboardFactory.callback("✅ Выплачено", "mod:withdrawal:approve:" + req.getId()),
                keyboardFactory.callback("❌ Отклонить", "mod:withdrawal:reject:" + req.getId())
        ));
        if (multiblockTargetMod.isPresent()) {
            modWdRows.add(List.of(keyboardFactory.callback(
                    "🚫 Отклонить + заблокировать оба аккаунта",
                    "mod:withdrawal:multiblock:" + req.getId() + ":" + multiblockTargetMod.get().getTelegramId())));
        }
        modWdRows.add(List.of(keyboardFactory.callback("⬅️ Назад", "mod:withdrawals")));
        sendText(user.getTelegramId(),
                "💸 <b>Заявка на вывод В-" + reqDisplayId(req) + "</b>\n\n"
                        + "👤 Игрок: <b>" + escape(requester.getNickname()) + "</b> (" + unameLink + ")\n"
                        + "🆔 Telegram ID: <b>" + requester.getTelegramId() + "</b>\n"
                        + "🌍 Страна: <b>" + escape(requester.getCountry() != null ? requester.getCountry() : "Не указана") + "</b>\n"
                        + phoneLineMod + "\n"
                        + "🪙 Сумма: <b>" + req.getRewardItem().getPriceCoins() + " EXC</b>\n"
                        + "💵 К выплате: <b>~" + rubles + " ₽</b>" + payoutSuffix
                        + detailsLine + "\n"
                        + monthlyLimitLine(requester)
                        + "📅 Дата: <b>" + req.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) + "</b>"
                        + dupWarning + destDupWarningMod + phoneDupWarningMod,
                keyboardFactory.rowsLayout(modWdRows));
    }

    public void notifyAdminsAboutRewardRequest(AppUser user, RewardItem reward) {
        notifyAdminsAboutRewardRequest(user, reward, null);
    }

    public void notifyAdminsAboutRewardRequest(AppUser user, RewardItem reward, String userGameData) {
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
            rows.add(List.of(keyboardFactory.callback("🕵️ Повторы разовых квестов", "admin:onetimeabuse")));
            rows.add(List.of(keyboardFactory.callback("🏷️ Теги CoC/Clash Royale", "admin:clashtags")));
            rows.add(List.of(keyboardFactory.callback("🔁 Активность автоквестов", "admin:autoquest-activity")));
            rows.add(List.of(
                    keyboardFactory.callback("🎁 Магазин наград", "admin:rewards"),
                    keyboardFactory.callback("📣 Рассылка", "admin:broadcast")
            ));
            rows.add(List.of(keyboardFactory.callback("📅 Запланированные рассылки", "admin:broadcast:scheduled")));
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
            rows.add(List.of(keyboardFactory.callback("🔍 Поиск игрока", "mod:usersearch")));
            return keyboardFactory.rowsLayout(rows);
        }

        boolean hasTournament = tournamentService.findCurrentForUser().isPresent();
        String questsLabel = hasTournament ? "🎯 Квесты и рейтинг 🔥" : "🎯 Квесты и рейтинг";
        rows.add(List.of(keyboardFactory.callback(questsLabel, "menu:cat:quests")));

        rows.add(List.of(keyboardFactory.callback("🤝 Рефералы", "menu:referrals")));

        String walletLabel = userService.isDailyBonusAvailable(user) ? "💰 Кошелёк 🔔" : "💰 Кошелёк";
        String wheelLabel = user.getTickets() > 0
                ? "🎰 Колесо фортуны 🎟 " + user.getTickets()
                : "🎰 Колесо фортуны";
        long activePolls = pollService.findActive().size();
        String clubLabel = activePolls > 0 ? "👥 Клуб (" + activePolls + ")" : "👥 Клуб";

        rows.add(List.of(
                keyboardFactory.callback("👤 Профиль", "menu:profile"),
                keyboardFactory.callback("⚔️ Отряды", "menu:squads")
        ));
        rows.add(List.of(
                keyboardFactory.callback(walletLabel, "menu:cat:wallet"),
                keyboardFactory.callback("🛍️ Магазин", "menu:cat:shop")
        ));
        rows.add(List.of(
                keyboardFactory.callback(wheelLabel, "wheel:menu"),
                keyboardFactory.callback(clubLabel, "menu:cat:club")
        ));

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

    private static final int NUMBERED_GRID_COLUMNS = 5;

    private InlineKeyboardMarkup numberedGridWithBackMenu(List<InlineKeyboardButton> buttons, String backText, String backData) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += NUMBERED_GRID_COLUMNS) {
            rows.add(new ArrayList<>(buttons.subList(i, Math.min(i + NUMBERED_GRID_COLUMNS, buttons.size()))));
        }
        rows.add(List.of(
                keyboardFactory.callback(backText, backData),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));
        return keyboardFactory.rowsLayout(rows);
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


    private String mainMenuText(AppUser user) {
        String role = resolveMenuRole(user, sessionService.get(user.getTelegramId()));
        if (ROLE_USER.equals(role)) {
            String balanceLine = "💰 <b>" + String.format("%,d", user.getCoins()).replace(',', ' ') + " EXC</b>"
                    + "   ⭐ Ур. " + userService.getLevelNumber(user.getXp()) + " — " + escape(userService.getLevelName(user.getXp()));
            return "Никнейм: " + escape(user.getNickname()) + "\n\n"
                    + balanceLine + "\n\n"
                    + "Здесь вы можете брать задания, накапливать XP, подниматься в рейтинге, приглашать друзей и обменивать монеты на награды.\n\n"
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
        return levelProgressBar(user);
    }

    private String levelProgressBar(AppUser user) {
        long xp = user.getXp();
        long floor = userService.currentLevelFloor(xp);
        long ceiling = userService.nextLevelCeiling(xp);
        int filled = 10;
        if (ceiling > floor) {
            filled = (int) Math.round(10.0 * (xp - floor) / (ceiling - floor));
            filled = Math.max(0, Math.min(10, filled));
        }
        String bar = "▰".repeat(filled) + "▱".repeat(10 - filled);
        return bar + " <b>" + String.format("%,d", xp).replace(',', ' ') + " / "
                + String.format("%,d", ceiling).replace(',', ' ') + " XP</b>";
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
                        + socialProofLine()
                        + "Здесь вас ждут квесты, XP, рейтинг, награды и реферальная программа.\n"
                        + "Начнем с профиля. Напишите ваш игровой никнейм.\n\n"
                        + "<b>ВАЖНО! Ник в боте должен совпадать с ником в игре</b>",
                null);
        return true;
    }

    private boolean isSubscriptionCacheValid(Long telegramId) {
        Long checked = subscriptionCheckCache.get(telegramId);
        return checked != null && (System.currentTimeMillis() - checked) < SUBSCRIPTION_CHECK_TTL_MS;
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

    /** Рублёвый эквивалент заявки на вывод — берём зафиксированный на момент подачи, чтобы не "плавал"
     * при изменении курса между созданием заявки и её обработкой модератором/админом. Пересчёт по
     * текущему курсу — только запасной вариант для старых заявок без сохранённого значения. */
    private long fixedOrCurrentRub(ru.gamebot.platform.domain.model.RewardRequest req) {
        return req.getFixedRubValue() != null
                ? req.getFixedRubValue()
                : Math.round(req.getRewardItem().getPriceCoins() * healthRatioService.getCurrentRatio() / 100.0);
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

    /** Статус привязки игровых тегов для авто-верификации квестов (Brawl Stars/Clash of Clans/Clash Royale). */
    private String buildGameTagsBlock(AppUser user) {
        return "🏷️ <b>Игровые теги</b>\n"
                + (user.getBrawlStarsTag() != null
                    ? "✅ Brawl Stars: <code>" + escape(user.getBrawlStarsTag()) + "</code>\n"
                    : "❌ Brawl Stars: не привязан\n")
                + (user.getClashOfClansTag() != null
                    ? "✅ Clash of Clans: <code>" + escape(user.getClashOfClansTag()) + "</code>\n"
                    : "❌ Clash of Clans: не привязан\n")
                + (user.getClashRoyaleTag() != null
                    ? "✅ Clash Royale: <code>" + escape(user.getClashRoyaleTag()) + "</code>\n"
                    : "❌ Clash Royale: не привязан\n");
    }

    /** Текст для кнопки авто-верифицируемого квеста — с текущим прогрессом, если он уже известен
     *  (после первого опроса API), или общей фразой, пока идёт первый замер (baseline ещё не зафиксирован). */
    private String autoVerifyProgressLabel(Quest quest, QuestSubmission submission) {
        if (quest.getBrawlVerifyType() != null) {
            if (submission.getBrawlBaselineTrophies() == null && quest.getBrawlVerifyType() == ru.gamebot.platform.domain.enums.BrawlVerifyType.TROPHIES) {
                return "⏳ Идёт первый замер…";
            }
            return "⏳ Прогресс: " + submission.getBrawlProgressCount() + "/" + quest.getBrawlTargetCount();
        }
        if (quest.getClashVerifyType() != null) {
            if (submission.getClashBaselineValue() == null) {
                return "⏳ Идёт первый замер…";
            }
            return "⏳ Прогресс: " + submission.getClashProgressCount() + "/" + quest.getClashTargetCount();
        }
        if (quest.getClashRoyaleVerifyType() != null) {
            if (submission.getClashRoyaleBaselineValue() == null) {
                return "⏳ Идёт первый замер…";
            }
            return "⏳ Прогресс: " + submission.getClashRoyaleProgressCount() + "/" + quest.getClashRoyaleTargetCount();
        }
        return "⏳ Прогресс отслеживается автоматически";
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

    /** Публичная обёртка для заявок поддержки, созданных через Mini App (там нет объекта Message от Telegram). */
    public void notifyModeratorsAboutSupportTicket(SupportTicket ticket, String text, String photoFileId) {
        String mediaType = photoFileId != null ? "photo" : "text";
        notifyModeratorsAboutSupportTicket(ticket, new IncomingContent(mediaType, photoFileId, text == null ? "" : text), false);
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
        recipients.addAll(adminService.strictModeratorIds());

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
        InlineKeyboardMarkup keyboard = keyboardFactory.verticalLayout(List.of(
                keyboardFactory.callback("🔚 Завершить диалог", "support:close_chat"),
                keyboardFactory.callback("📬 Мои заявки", "support:list"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        ));
        sendContent(telegramId, content, caption, keyboard);
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
                default -> {
                    String text = (fallbackText != null && !fallbackText.isBlank()) ? fallbackText
                            : (content.text() != null && !content.text().isBlank()) ? content.text()
                            : "📎 Вложение";
                    sendText(chatId, text, markup);
                }
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
            default -> sendText(user.getTelegramId(),
                    "🧭 Продолжим оформление профиля с текущего шага.",
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

    private void sendPhoneShareRequest(Long chatId) {
        KeyboardButton btn = new KeyboardButton("📱 Поделиться номером телефона");
        btn.setRequestContact(true);
        KeyboardRow row = new KeyboardRow();
        row.add(btn);
        ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                .keyboardRow(row)
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("📱 <b>Подтверждение личности</b>\n\nДля вывода средств нужно один раз поделиться номером телефона.\n\nЭто займёт один клик — нажмите кнопку ниже. Данные используются только для защиты от мультиаккаунтов.");
        message.setParseMode("HTML");
        message.setReplyMarkup(keyboard);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            throw new IllegalStateException("Failed to send phone share request to " + chatId, e);
        }
    }

    private void removeReplyKeyboard(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("✅ Номер телефона сохранён.");
        message.setReplyMarkup(ReplyKeyboardRemove.builder().removeKeyboard(true).build());
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.warn("Failed to remove reply keyboard for {}", chatId, e);
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
            case APPROVED -> "Выполнен";
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

    /** Ищет игрока по вводу: если строка — число, ищет по TG ID, иначе по никнейму (без учёта регистра). */
    private AppUser resolveUserBySearch(String input) {
        String trimmed = input.trim();
        try {
            long id = Long.parseLong(trimmed);
            return userService.findByTelegramId(id).orElse(null);
        } catch (NumberFormatException e) {
            return userService.findByNickname(trimmed).orElse(null);
        }
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

    private String formatDeadlineLine(LocalDateTime expiresAt) {
        long totalSeconds = java.time.temporal.ChronoUnit.SECONDS.between(LocalDateTime.now(), expiresAt);
        if (totalSeconds <= 0) {
            return "⌛ Дедлайн: <b>истёк</b>\n";
        }
        if (totalSeconds < 86_400) {
            long h = totalSeconds / 3600;
            long m = (totalSeconds % 3600) / 60;
            long s = totalSeconds % 60;
            String emoji = totalSeconds < 7_200 ? "⚠️" : "⏰";
            return emoji + " Осталось: <b>" + String.format("%02d:%02d:%02d", h, m, s) + "</b>\n";
        }
        long days = totalSeconds / 86_400;
        long h = (totalSeconds % 86_400) / 3600;
        return "⏰ Осталось: <b>" + days + "д " + String.format("%02d:%02d", h, 0) + "</b>\n";
    }

    /** Живая строка соц-доказательства для приветствия новичков — реальное число игроков, округлённое вниз до 50. */
    private String socialProofLine() {
        long registered = userService.totalRegisteredUsers();
        long roundedBase = (registered / 50) * 50;
        return roundedBase >= 50 ? "👥 Уже <b>" + roundedBase + "+</b> игроков в клубе\n\n" : "";
    }

    // ── Sponsor quest creation ───────────────────────────────────────────────

    private void finalizeSponsorQuest(AppUser user, UserSession session, String note) {
        Map<String, String> d = session.getData();
        String sponsorIdStr = d.get("sponsorId");
        boolean isPostpay = d.containsKey("postpay");

        Quest quest = new Quest();
        quest.setTitle(d.get("sq_title"));
        quest.setDescription(d.get("sq_desc"));
        quest.setRewardXp(Long.parseLong(d.getOrDefault("sq_xp", "0")));
        quest.setRewardCoins(Long.parseLong(d.getOrDefault("sq_exc", "0")));
        quest.setDurationText(d.get("sq_duration"));
        quest.setInstruction(note.isBlank() ? null : note);
        quest.setRequirements(null);
        quest.setCategory("Средние");
        quest.setPlatform("PC");
        quest.setParticipantLimit(10000);
        quest.setSponsored(true);

        // Parse duration days from text (e.g. "7 дней" → 7)
        String durText = d.getOrDefault("sq_duration", "");
        try {
            quest.setDurationDays(Integer.parseInt(durText.replaceAll("[^0-9]", "")));
        } catch (NumberFormatException ignored) {
            quest.setDurationDays(30);
        }

        // Link to sponsor
        if (sponsorIdStr != null) {
            try {
                long sid = Long.parseLong(sponsorIdStr);
                quest.setSponsorId(sid);
                // Use channel name if provided, otherwise fall back to sponsor name
                String channelName = d.get("sq_channel");
                if (channelName != null && !channelName.isBlank()) {
                    quest.setGameName(channelName);
                } else {
                    sponsorService.findById(sid).ifPresent(s -> quest.setGameName(s.getName()));
                }
            } catch (NumberFormatException ignored) {}
        }

        questService.createQuest(quest);

        String backAction = isPostpay
                ? "admin:postpay:view:" + sponsorIdStr
                : "admin:sponsors:view:" + sponsorIdStr;
        session.reset();

        sendText(user.getTelegramId(),
                "✅ <b>Квест создан!</b>\n\n"
                + "🎯 " + escape(quest.getTitle()) + "\n"
                + "✨ XP: <b>" + quest.getRewardXp() + "</b>  🪙 EXC: <b>" + quest.getRewardCoins() + "</b>\n"
                + "📅 " + escape(quest.getDurationText()),
                backMenuKeyboard(backAction));
        sendText(user.getTelegramId(), buildQuestAnnouncement(quest), null);
    }

    // ── Sponsor text commands ────────────────────────────────────────────────

    private void handleAddSponsor(AppUser user, String text) {
        // Format:
        // /add_sponsor
        // quest_id: 123
        // sponsor_name: Supercell
        // sponsor_contact: @supercell_manager
        // start_date: 2026-07-16
        // end_date: 2026-08-31
        try {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            for (String line : text.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim().toLowerCase().replace(' ', '_');
                    String val = line.substring(colon + 1).trim();
                    params.put(key, val);
                }
            }
            String name = params.get("sponsor_name");
            String contact = params.getOrDefault("sponsor_contact", "");
            String questIdStr = params.get("quest_id");
            String startStr = params.get("start_date");
            String endStr = params.get("end_date");

            if (name == null || startStr == null || endStr == null) {
                sendText(user.getTelegramId(),
                        "❌ Не хватает полей. Нужно:\n"
                                + "<code>/add_sponsor\nquest_id: 123\nsponsor_name: Supercell\nsponsor_contact: @manager\nstart_date: 2026-07-16\nend_date: 2026-08-31</code>",
                        null);
                return;
            }

            Long questId = questIdStr != null ? Long.parseLong(questIdStr.trim()) : null;
            LocalDate start = LocalDate.parse(startStr.trim());
            LocalDate end = LocalDate.parse(endStr.trim());

            ru.gamebot.platform.domain.model.Sponsor sp = sponsorService.createSimple(name, contact, questId, start, end);

            String questTitle = "не указан";
            if (questId != null) {
                try { questTitle = questService.getQuest(questId).getTitle(); } catch (Exception ignored) { questTitle = "квест #" + questId; }
            }

            sendText(user.getTelegramId(),
                    "✅ Спонсор создан (ID: " + sp.getId() + ")\n\n"
                            + "🏢 <b>" + escape(sp.getName()) + "</b>\n"
                            + "📞 " + escape(contact) + "\n"
                            + "🎯 Квест: " + escape(questTitle) + "\n"
                            + "📅 " + start.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                            + " — " + end.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                    null);
        } catch (Exception e) {
            sendText(user.getTelegramId(), "❌ Ошибка: " + e.getMessage(), null);
        }
    }

    private void handleSponsorStats(AppUser user, String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) {
            sendText(user.getTelegramId(), "Использование: /sponsor_stats <quest_id>", null);
            return;
        }
        try {
            long questId = Long.parseLong(parts[1]);
            ru.gamebot.platform.domain.model.Quest quest;
            try { quest = questService.getQuest(questId); } catch (Exception e) { quest = null; }
            if (quest == null) {
                sendText(user.getTelegramId(), "❌ Квест #" + questId + " не найден.", null);
                return;
            }
            if (!quest.isSponsored() || quest.getSponsorId() == null) {
                sendText(user.getTelegramId(), "ℹ️ Квест #" + questId + " не является спонсорским.", null);
                return;
            }
            ru.gamebot.platform.domain.model.Sponsor sp = sponsorService.findById(quest.getSponsorId()).orElse(null);
            if (sp == null) {
                sendText(user.getTelegramId(), "❌ Спонсор не найден.", null);
                return;
            }
            long completions = sponsorService.countCompletions(sp);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy");
            String period = sp.getStartDate() != null && sp.getEndDate() != null
                    ? sp.getStartDate().format(fmt) + " — " + sp.getEndDate().minusDays(1).format(fmt)
                    : "не задан";

            sendText(user.getTelegramId(),
                    "📊 <b>Статистика спонсорского квеста</b>\n\n"
                            + "🏢 Спонсор: <b>" + escape(sp.getName()) + "</b>\n"
                            + (sp.getSponsorContact() != null ? "📞 Контакт: " + escape(sp.getSponsorContact()) + "\n" : "")
                            + "🎯 Квест: <b>" + escape(quest.getTitle()) + "</b>\n"
                            + "📅 Период: " + period + "\n"
                            + "✅ Одобрено прохождений: <b>" + completions + "</b>",
                    null);
        } catch (NumberFormatException e) {
            sendText(user.getTelegramId(), "❌ quest_id должен быть числом.", null);
        }
    }

    private void handleSponsorsList(AppUser user) {
        java.util.List<ru.gamebot.platform.domain.model.Sponsor> sponsors = sponsorService.findAll();
        if (sponsors.isEmpty()) {
            sendText(user.getTelegramId(), "📋 Спонсорских кампаний нет.", null);
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yy");
        StringBuilder sb = new StringBuilder("📋 <b>Спонсорские квесты</b>\n\n");
        for (ru.gamebot.platform.domain.model.Sponsor sp : sponsors) {
            java.util.List<ru.gamebot.platform.domain.model.Quest> quests = sponsorService.findSponsoredQuests(sp.getId());
            long completions = sponsorService.countCompletions(sp);
            String period = sp.getStartDate() != null && sp.getEndDate() != null
                    ? sp.getStartDate().format(fmt) + "–" + sp.getEndDate().minusDays(1).format(fmt)
                    : "—";
            String status = sp.isActive() ? "🟢" : "🔴";
            sb.append(status).append(" <b>").append(escape(sp.getName())).append("</b>");
            if (sp.getSponsorContact() != null) sb.append(" (").append(escape(sp.getSponsorContact())).append(")");
            sb.append("\n");
            for (ru.gamebot.platform.domain.model.Quest q : quests) {
                sb.append("   🎯 ").append(escape(q.getTitle())).append("\n");
            }
            sb.append("   📅 ").append(period).append(" · ✅ ").append(completions).append(" прохождений\n\n");
        }
        sendText(user.getTelegramId(), sb.toString(), null);
    }
}
