package ru.gamebot.platform.bot;

import jakarta.annotation.PostConstruct;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.NewsPost;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.model.SupportAttachment;
import ru.gamebot.platform.domain.model.SupportTicket;
import ru.gamebot.platform.service.AdminService;
import ru.gamebot.platform.service.NewsService;
import ru.gamebot.platform.service.QuestService;
import ru.gamebot.platform.service.RewardService;
import ru.gamebot.platform.service.SessionService;
import ru.gamebot.platform.service.SupportService;
import ru.gamebot.platform.service.UserService;

@Slf4j
@Component
@RequiredArgsConstructor
public class GamePlatformBot extends TelegramLongPollingBot {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+|t\\.me/\\S+)");
    private static final String ROLE_USER = "USER";
    private static final String ROLE_MODER = "MODER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final Map<String, String> PLATFORM_OPTIONS = new LinkedHashMap<>();
    private static final Map<String, String> INTEREST_OPTIONS = new LinkedHashMap<>();

    static {
        PLATFORM_OPTIONS.put("ANDROID", "📱 Android");
        PLATFORM_OPTIONS.put("IPHONE", "🍎 iPhone");
        PLATFORM_OPTIONS.put("PC", "🖥️ PC");
        PLATFORM_OPTIONS.put("PS5", "🎮 PS5");
        PLATFORM_OPTIONS.put("XBOX", "🕹️ Xbox");

        INTEREST_OPTIONS.put("FPS", "🔫 FPS");
        INTEREST_OPTIONS.put("MMO", "🌍 MMO");
        INTEREST_OPTIONS.put("RPG", "🧙 RPG");
        INTEREST_OPTIONS.put("STRATEGY", "♟️ Стратегии");
        INTEREST_OPTIONS.put("SPORT", "⚽ Спорт");
        INTEREST_OPTIONS.put("CASUAL", "🎉 Казуальные");
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

    @PostConstruct
    public void registerBot() throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(this);
        log.info("Telegram bot registered: {}", getBotUsername());
        log.info("Resolved admin IDs: {}", adminService.resolvedAdminIds());
        log.info("Resolved moderator IDs: {}", adminService.resolvedModeratorIds());
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

        if (text != null && handleRoleSwitchCommand(user, session, text.trim())) {
            return;
        }

        if (shouldContinueSupportMediaGroup(message, session)) {
            handleSupportMessage(user, session, message);
            return;
        }

        if (!user.isRegistrationCompleted() && session.getState() == SessionState.NONE) {
            session.setState(SessionState.REG_NAME);
            sendText(user.getTelegramId(),
                    "🎉 Добро пожаловать в <b>" + escape(appProperties.getClubName()) + "</b>!\n\n"
                            + "Чтобы открыть квесты, рейтинг и награды, давайте быстро оформим профиль.\n"
                            + "Напишите ваш игровой никнейм.",
                    null);
            return;
        }

        if (session.getState() == SessionState.REPORT_MEDIA) {
            handleReportMessage(user, session, message);
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

        if (text != null && session.getState() != SessionState.NONE) {
            handleStateInput(user, session, text);
            return;
        }

        if (!user.isRegistrationCompleted()) {
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
        Long referredBy = parseStartReferral(message.getText());
        AppUser user = userService.getOrCreate(message.getFrom(), referredBy);
        UserSession session = sessionService.get(user.getTelegramId());
        ensureRoleConsistency(user, session);

        String streakMessage = userService.registerActivity(user);
        if (!user.isRegistrationCompleted()) {
            session.reset();
            session.setState(SessionState.REG_NAME);
            sendText(user.getTelegramId(),
                    "🎮 Добро пожаловать в <b>" + escape(appProperties.getClubName()) + "</b>!\n\n"
                            + "Здесь вас ждут квесты, XP, рейтинг, награды и реферальная программа.\n"
                            + "Начнем с профиля. Напишите ваш игровой никнейм.",
                    null);
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

        if (!user.isRegistrationCompleted()) {
            answer(callbackQuery.getId(), "Сначала завершим регистрацию");
            sendCurrentRegistrationStep(user, session,
                    "🧭 Перед использованием разделов нужно закончить регистрацию. Продолжим с текущего шага.");
            return;
        }

        if (data.startsWith("menu:")) {
            handleMenuAction(callbackQuery, user, data.substring("menu:".length()));
            return;
        }
        if (data.startsWith("profile:")) {
            handleProfileAction(callbackQuery, user, data.substring("profile:".length()));
            return;
        }
        if (data.startsWith("quests:cat:")) {
            String category = data.substring("quests:cat:".length());
            sendQuestList(user, "all".equals(category) ? null : category);
            answer(callbackQuery.getId(), "Раздел обновлен");
            return;
        }
        if (data.startsWith("quest:view:")) {
            handleQuestView(callbackQuery, user, session, data.substring("quest:view:".length()));
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
        if (data.startsWith("shop:view:")) {
            sendRewardCard(user, parseLong(data.substring("shop:view:".length())));
            answer(callbackQuery.getId(), "Карточка награды");
            return;
        }
        if (data.startsWith("shop:buy:")) {
            handleRewardPurchase(callbackQuery, user, parseLong(data.substring("shop:buy:".length())));
            return;
        }
        if (data.startsWith("rate:")) {
            sendLeaderboard(user, data.substring("rate:".length()));
            answer(callbackQuery.getId(), "Рейтинг готов");
            return;
        }
        if (data.startsWith("support:")) {
            handleSupportAction(callbackQuery, user, session, data.substring("support:".length()));
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
            case "quests" -> sendQuestCategories(user);
            case "myquests" -> sendMySubmissions(user);
            case "balance" -> sendBalance(user);
            case "rating" -> sendRatingMenu(user);
            case "referrals" -> sendReferrals(user);
            case "shop" -> sendShop(user);
            case "news" -> sendNews(user);
            case "support" -> sendSupport(user);
            case "admin" -> sendAdminPanel(user);
            case "moderation" -> sendModerationHub(user);
            default -> sendMainMenu(user, mainMenuText(user));
        }
        answerSilently(callbackQuery.getId());
    }

    private void handleProfileAction(CallbackQuery callbackQuery, AppUser user, String action) {
        switch (action) {
            case "balance" -> sendBalance(user, "menu:profile");
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
                    "🧠 Выберите игровые интересы. Этот шаг можно пропустить.\n\n"
                            + "Сейчас выбрано: <b>ничего</b>",
                    selectionKeyboard(INTEREST_OPTIONS, List.of(), "reg:interest:", true, true, false)
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
            case BONUS_INPUT -> handleBonusInput(user, session, text);
            case BROADCAST_MESSAGE -> handleBroadcast(user, session, text);
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
                sendText(user.getTelegramId(), "📚 Укажите категорию: Быстрые, Средние или Долгие.", cancelKeyboard());
            }
            case QUEST_CREATE_CATEGORY -> {
                session.getData().put("category", text.trim());
                session.setState(SessionState.QUEST_CREATE_PLATFORM);
                sendText(user.getTelegramId(), "🕹️ Укажите платформу или связку платформ.", cancelKeyboard());
            }
            case QUEST_CREATE_PLATFORM -> {
                session.getData().put("platform", text.trim());
                session.setState(SessionState.QUEST_CREATE_DURATION);
                sendText(user.getTelegramId(), "⏳ Укажите срок выполнения, например: 1-3 дня.", cancelKeyboard());
            }
            case QUEST_CREATE_DURATION -> {
                session.getData().put("duration", text.trim());
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
            case QUEST_CREATE_LIMIT -> handleQuestCreateFinish(user, session, text);
            case QUEST_EDIT_TITLE -> updateQuestTitle(user, session, text);
            case QUEST_EDIT_DESCRIPTION -> updateQuestDescription(user, session, text);
            case QUEST_EDIT_REWARD -> updateQuestReward(user, session, text);
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
                selectionKeyboard(INTEREST_OPTIONS, selected, "reg:interest:", true, true, false));
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
        userService.grantReferralReward(updated);
        session.reset();

        String achievements = userService.getAchievements(updated).isEmpty()
                ? "Пока без достижений, но первое уже совсем рядом."
                : String.join(", ", userService.getAchievements(updated));

        sendMainMenu(updated,
                "✨ <b>Профиль собран</b>\n\n"
                        + "🎮 Никнейм: <b>" + escape(updated.getNickname()) + "</b>\n"
                        + "🌍 Регион: <b>" + escape(updated.getCountry()) + "</b>\n"
                        + "🕹️ Платформы: <b>" + escape(updated.getPlatformsCsv()) + "</b>\n"
                        + "🎯 Интересы: <b>" + escape(updated.getInterestsCsv()) + "</b>\n"
                        + "🌟 Стартовые достижения: " + escape(achievements) + "\n\n"
                        + "Добро пожаловать в клуб. Теперь можно идти за первым сильным прогрессом.");
    }

    private void sendMainMenu(AppUser user, String text) {
        sendText(user.getTelegramId(), text, mainMenuKeyboard(user));
    }

    private void sendProfile(AppUser user) {
        long rank = userService.getOverallRank(user);
        String achievements = userService.getAchievements(user).isEmpty()
                ? "Пока нет, но первое достижение уже близко."
                : String.join(", ", userService.getAchievements(user));

        sendText(user.getTelegramId(),
                "👤 <b>Профиль</b>\n\n"
                        + "🎮 <b>" + escape(user.getNickname()) + "</b>\n"
                        + "⭐ Ранг: <b>" + escape(userService.getLevelName(user.getXp())) + "</b>\n"
                        + levelProgressLine(user) + "\n\n"
                        + "🏆 <b>Текущая форма</b>\n"
                        + "✨ XP: <b>" + user.getXp() + "</b>\n"
                        + "🪙 Монеты: <b>" + user.getCoins() + "</b>\n"
                        + "🥇 Место в рейтинге: <b>" + rank + "</b>\n"
                        + "✅ Выполнено квестов: <b>" + user.getCompletedQuests() + "</b>\n"
                        + "🔥 Серия входов: <b>" + user.getStreakDays() + " дней</b>\n\n"
                        + "🧩 <b>Игровой стиль</b>\n"
                        + "🕹️ Платформы: <b>" + escape(displayValue(user.getPlatformsCsv(), "Подбираются")) + "</b>\n"
                        + "🎯 Интересы: <b>" + escape(displayValue(user.getInterestsCsv(), "Открываются")) + "</b>\n"
                        + "🤝 Приглашено друзей: <b>" + user.getInvitedFriends() + "</b>\n\n"
                        + "🌟 <b>Достижения</b>\n"
                        + escape(achievements),
                keyboardFactory.rowsLayout(List.of(
                        List.of(
                                keyboardFactory.callback("🗺️ Квесты", "menu:quests"),
                                keyboardFactory.callback("💰 Баланс", "profile:balance")
                        ),
                        List.of(
                                keyboardFactory.callback("🏆 Рейтинг", "menu:rating"),
                                keyboardFactory.callback("🤝 Рефералы", "menu:referrals")
                        ),
                        List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))
                )));
    }

    private void sendBalance(AppUser user) {
        sendBalance(user, "menu:main");
    }

    private void sendBalance(AppUser user, String backData) {
        sendText(user.getTelegramId(),
                "💰 <b>Баланс</b>\n\n"
                        + "🪙 Монеты клуба: <b>" + user.getCoins() + "</b>\n"
                        + "✨ Общий XP: <b>" + user.getXp() + "</b>\n"
                        + "📈 XP за неделю: <b>" + user.getWeeklyXp() + "</b>\n\n"
                        + "Чем активнее вы играете, тем быстрее открываете сильные награды и поднимаетесь в рейтинге.",
                backMenuKeyboard(backData));
    }

    private void sendQuestCategories(AppUser user) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                keyboardFactory.callback("⚡ Быстрые", "quests:cat:Быстрые"),
                keyboardFactory.callback("🎯 Средние", "quests:cat:Средние")
        ));
        rows.add(List.of(
                keyboardFactory.callback("🏰 Долгие", "quests:cat:Долгие")
        ));
        rows.add(List.of(keyboardFactory.callback("📂 Мои квесты", "menu:myquests")));
        rows.add(List.of(keyboardFactory.callback("📚 Все квесты", "quests:cat:all")));
        rows.add(List.of(keyboardFactory.callback("🏠 Меню", "menu:main")));
        sendText(user.getTelegramId(),
                "🗺️ <b>Квесты</b>\n\n"
                        + "Здесь собраны быстрые старты, средние челленджи и длинные марафоны.\n"
                        + "Откройте подборку или сразу перейдите к своим активным заданиям.",
                keyboardFactory.rowsLayout(rows));
    }

    private void sendQuestList(AppUser user, String category) {
        List<Quest> quests = category == null ? questService.findActiveQuests() : questService.findByCategory(category);
        if (quests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📭 В этом разделе пока нет активных квестов. Проверьте позже или загляните в другие категории.",
                    backMenuKeyboard("menu:quests"));
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Quest quest : quests) {
            buttons.add(keyboardFactory.callback("🎯 " + trim(quest.getTitle(), 32), "quest:view:" + categoryToken(category) + ":" + quest.getId()));
        }
        buttons.add(keyboardFactory.callback("🏠 Меню", "menu:main"));

        String title = category == null ? "📚 Все доступные квесты" : "📚 Категория: " + category;
        sendText(user.getTelegramId(),
                "<b>" + escape(title) + "</b>\n\n"
                        + "Откройте карточку задания, чтобы увидеть награды, сроки и точные условия прохождения.",
                keyboardFactory.smartLayout(buttons));
    }

    private void handleQuestView(CallbackQuery callbackQuery, AppUser user, UserSession session, String payload) {
        String[] parts = payload.split(":");
        if (parts.length != 2) {
            answer(callbackQuery.getId(), "Карточка квеста недоступна");
            return;
        }
        Long questId = parseLong(parts[1]);
        if (questId == null) {
            answer(callbackQuery.getId(), "Карточка квеста недоступна");
            return;
        }
        sendQuestCard(user, questId, backDataFromCategoryToken(parts[0]), "⬅️ Назад", null);
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

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(keyboardFactory.callback("🚀 Взять", "quest:take:" + questId));
        buttons.add(keyboardFactory.callback("📤 Отчёт", "quest:report:" + questId));
        if (isEffectiveAdmin(user)) {
            buttons.add(keyboardFactory.callback("✏️ Правка", "admin:quest:" + questId));
        }

        sendText(user.getTelegramId(),
                (notice == null ? "" : notice + "\n\n")
                        + "🎯 <b>" + escape(quest.getTitle()) + "</b>\n\n"
                        + "🎮 Игра: <b>" + escape(quest.getGameName()) + "</b>\n"
                        + "📚 Формат: <b>" + escape(quest.getCategory()) + "</b>\n"
                        + "🕹️ Платформа: <b>" + escape(quest.getPlatform()) + "</b>\n"
                        + "⏳ Темп: <b>" + escape(quest.getDurationText()) + "</b>\n"
                        + "📌 Статус: <b>" + escape(statusText) + "</b>\n\n"
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
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "🧭 Этот квест уже добавлен в работу. Ниже оставил карточку с кнопкой для отчёта.");
            return;
        }
        if (latest != null && (latest.getStatus() == SubmissionStatus.PENDING || latest.getStatus() == SubmissionStatus.APPROVED)) {
            answerSilently(callbackQuery.getId());
            sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                    "📌 По этому квесту уже есть активный прогресс. Используйте карточку ниже, чтобы посмотреть статус или отправить отчёт.");
            return;
        }

        questService.createDraftSubmission(user, quest);
        answerSilently(callbackQuery.getId());
        sendQuestCard(user, questId, currentQuestBackData(user), "⬅️ Назад",
                "🚀 Квест добавлен в работу. Когда будете готовы, отправьте отчёт прямо из этой карточки или через раздел «Мои квесты».");
    }

    private void handleReportStart(CallbackQuery callbackQuery, AppUser user, UserSession session, Long questId) {
        Quest quest = questService.getQuest(questId);
        QuestSubmission latest = questService.getLatestSubmission(user, quest);
        if (latest == null || latest.getStatus() == SubmissionStatus.REJECTED || latest.getStatus() == SubmissionStatus.NEEDS_INFO) {
            latest = questService.createDraftSubmission(user, quest);
        }

        session.reset();
        session.setState(SessionState.REPORT_MEDIA);
        session.setQuestId(questId);
        session.setSubmissionId(latest.getId());

        sendText(user.getTelegramId(),
                "📤 <b>Отчёт по квесту</b>\n\n"
                        + "Пришлите одним сообщением скриншот, видео, файл или ссылку.\n"
                        + "Можно добавить комментарий в подписи или текстом.\n\n"
                        + "🎯 Квест: <b>" + escape(quest.getTitle()) + "</b>",
                cancelKeyboard());
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

        List<InlineKeyboardButton> buttons = List.of(
                keyboardFactory.callback("📤 Отчёт", "quest:report:" + quest.getId())
        );

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
        List<InlineKeyboardButton> buttons = List.of(
                keyboardFactory.callback("🌍 Общий", "rate:overall"),
                keyboardFactory.callback("📆 Недельный", "rate:weekly"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        );
        sendText(user.getTelegramId(),
                "🏆 <b>Рейтинг</b>\n\n"
                        + "Сравните общий прогресс клуба или посмотрите гонку этой недели.",
                keyboardFactory.smartLayout(buttons));
    }

    private void sendLeaderboard(AppUser user, String type) {
        boolean weekly = "weekly".equals(type);
        List<AppUser> players = weekly ? userService.topWeekly() : userService.topOverall();
        StringBuilder builder = new StringBuilder();
        builder.append(weekly ? "📆 <b>Недельный рейтинг</b>\n\n" : "🌍 <b>Общий рейтинг</b>\n\n");

        for (int i = 0; i < players.size(); i++) {
            AppUser player = players.get(i);
            builder.append(i + 1).append(". ")
                    .append(escape(player.getNickname()))
                    .append(" — ")
                    .append(weekly ? player.getWeeklyXp() + " XP" : player.getXp() + " XP")
                    .append(", ")
                    .append(player.getCompletedQuests()).append(" квестов")
                    .append(", ")
                    .append(player.getCoins()).append(" монет\n");
        }

        long rank = weekly ? userService.getWeeklyRank(user) : userService.getOverallRank(user);
        builder.append("\n👤 Ваше место: <b>").append(rank).append("</b>");
        sendText(user.getTelegramId(), builder.toString(), backMenuKeyboard("menu:main"));
    }

    private void sendReferrals(AppUser user) {
        String referralLink = "https://t.me/" + appProperties.getBotUsername() + "?start=ref_" + user.getTelegramId();
        sendText(user.getTelegramId(),
                "🤝 <b>Рефералы</b>\n\n"
                        + "🔗 Ваша ссылка:\n" + escape(referralLink) + "\n\n"
                        + "👥 Приглашено друзей: <b>" + user.getInvitedFriends() + "</b>\n"
                        + "🎁 Награда за активного друга: <b>+30 XP и +50 монет</b>\n\n"
                        + "Приглашайте друзей в клуб и усиливайте свой прогресс без лишней рутины.",
                backMenuKeyboard("menu:main"));
    }

    private void sendShop(AppUser user) {
        List<RewardItem> rewards = rewardService.findAvailableRewards();
        if (rewards.isEmpty()) {
            sendText(user.getTelegramId(), "🛍️ Магазин пока обновляется. Новые награды появятся здесь совсем скоро.", backMenuKeyboard("menu:main"));
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (RewardItem reward : rewards) {
            buttons.add(keyboardFactory.callback("🎁 " + trim(reward.getTitle(), 26), "shop:view:" + reward.getId()));
        }
        buttons.add(keyboardFactory.callback("🏠 Меню", "menu:main"));

        sendText(user.getTelegramId(),
                "🛍️ <b>Магазин наград</b>\n\n"
                        + "Здесь монеты превращаются в реальные бонусы, цифровые призы и мерч.\n"
                        + "Текущий баланс: <b>" + user.getCoins() + " монет</b>.",
                keyboardFactory.smartLayout(buttons));
    }

    private void sendRewardCard(AppUser user, Long rewardId) {
        sendRewardCard(user, rewardId, null);
    }

    private void sendRewardCard(AppUser user, Long rewardId, String notice) {
        RewardItem reward = rewardService.getRewardItem(rewardId);
        sendText(user.getTelegramId(),
                (notice == null ? "" : notice + "\n\n")
                        + "🎁 <b>" + escape(reward.getTitle()) + "</b>\n\n"
                        + "📦 Категория: <b>" + escape(reward.getCategory()) + "</b>\n"
                        + "📝 " + escape(reward.getDescription()) + "\n\n"
                        + "🪙 Стоимость: <b>" + reward.getPriceCoins() + " монет</b>",
                verticalWithBackMenu(List.of(keyboardFactory.callback("🛒 Обменять", "shop:buy:" + rewardId)), "⬅️ Назад", "menu:shop"));
    }

    private void handleRewardPurchase(CallbackQuery callbackQuery, AppUser user, Long rewardId) {
        RewardItem reward = rewardService.getRewardItem(rewardId);
        try {
            rewardService.createRewardRequest(user, reward);
        } catch (IllegalArgumentException exception) {
            answerSilently(callbackQuery.getId());
            sendRewardCard(user, rewardId,
                    "⚠️ Для этой награды пока не хватает монет. Посмотрите стоимость ниже и возвращайтесь после новых квестов.");
            return;
        }
        notifyAdminsAboutRewardRequest(user, reward);
        sendText(user.getTelegramId(),
                "✅ <b>Заявка на награду отправлена</b>\n\n"
                        + "🎁 Награда: <b>" + escape(reward.getTitle()) + "</b>\n"
                        + "🪙 Списано: <b>" + reward.getPriceCoins() + " монет</b>\n\n"
                        + "Как только выдача будет подтверждена, вы получите отдельное уведомление.",
                backMenuKeyboard("menu:shop"));
        answerSilently(callbackQuery.getId());
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
                    .append(escape(post.getBody())).append("\n")
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
        sendText(user.getTelegramId(),
                "🛡️ <b>Центр модерации</b>\n\n"
                        + "📂 Отчёты по квестам: <b>" + questService.pendingCount() + "</b>\n"
                        + "🆘 Открытые заявки поддержки: <b>" + supportService.activeTicketCount() + "</b>\n\n"
                        + "Здесь собрана вся оперативная работа по платформе.",
                keyboardFactory.verticalLayout(List.of(
                        keyboardFactory.callback("📂 Квесты", "mod:support:quests"),
                        keyboardFactory.callback("🆘 Поддержка", "mod:support:list"),
                        keyboardFactory.callback("🏠 Меню", "menu:main")
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
                    backMenuKeyboard("menu:moderation"));
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

    private void sendModerationQueue(Long chatId) {
        List<QuestSubmission> submissions = questService.getPendingSubmissions();
        if (submissions.isEmpty()) {
            sendText(chatId,
                    "🛡️ Очередь проверки пуста. Все текущие отчёты уже разобраны.",
                    backMenuKeyboard("menu:moderation"));
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (QuestSubmission submission : submissions) {
            String title = "🔎 " + trim(submission.getUser().getNickname() + " / " + submission.getQuest().getTitle(), 28);
            buttons.add(keyboardFactory.callback(title, "mod:view:" + submission.getId()));
        }
        sendText(chatId,
                "🛡️ <b>Очередь модерации</b>\n\n"
                        + "Заявок на проверке: <b>" + submissions.size() + "</b>\n"
                        + "Откройте карточку, чтобы принять, отклонить или запросить уточнение.",
                verticalWithBackMenu(buttons, "⬅️ Назад", "menu:moderation"));
    }

    private void sendSubmissionCard(Long chatId, Long submissionId) {
        QuestSubmission submission = questService.getSubmission(submissionId);
        String text = "🧾 <b>Заявка на проверку</b>\n\n"
                + "👤 Игрок: <b>" + escape(submission.getUser().getNickname()) + "</b>\n"
                + "🆔 ID: <b>" + submission.getUser().getTelegramId() + "</b>\n"
                + "🎯 Квест: <b>" + escape(submission.getQuest().getTitle()) + "</b>\n"
                + "📅 Отправлено: <b>" + escape(submission.getCreatedAt().format(DATE_TIME_FORMATTER)) + "</b>\n"
                + "📎 Тип: <b>" + escape(submission.getMediaType()) + "</b>\n"
                + "💬 Комментарий: " + escape(submission.getUserComment()) + "\n"
                + (submission.getExternalLink() == null ? "" : "🔗 Ссылка: " + escape(submission.getExternalLink()) + "\n");

        InlineKeyboardMarkup markup = verticalWithBackMenu(List.of(
                keyboardFactory.callback("✅ Одобрить", "mod:ok:" + submissionId),
                keyboardFactory.callback("❌ Отклонить", "mod:no:" + submissionId),
                keyboardFactory.callback("❓ Уточнить", "mod:more:" + submissionId)
        ), "⬅️ Назад", "mod:support:quests");
        sendText(chatId, text, markup);
    }

    private void handleModerationApprove(CallbackQuery callbackQuery, Long submissionId) {
        QuestSubmission submission = questService.approveSubmission(submissionId);
        notifyUser(submission.getUser().getTelegramId(),
                "🎉 Ваш отчёт по квесту <b>" + escape(submission.getQuest().getTitle()) + "</b> одобрен!\n\n"
                        + "✨ Начислено: <b>+" + submission.getQuest().getRewardXp() + " XP</b>\n"
                        + "🪙 Монеты: <b>+" + submission.getQuest().getRewardCoins() + "</b>");
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
        List<InlineKeyboardButton> buttons = List.of(
                keyboardFactory.callback("➕ Квест", "admin:create"),
                keyboardFactory.callback("✏️ Квесты", "admin:edit"),
                keyboardFactory.callback("🎁 Бонус", "admin:bonus"),
                keyboardFactory.callback("📣 Рассылка", "admin:broadcast"),
                keyboardFactory.callback("📊 Статистика", "admin:stats"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        );
        sendText(user.getTelegramId(),
                "🛠️ <b>Админ-панель</b>\n\n"
                        + "Управляйте контентом, экономикой, статистикой и коммуникацией платформы из одного центра.",
                keyboardFactory.smartLayout(buttons));
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
            case "bonus" -> {
                session.reset();
                session.setState(SessionState.BONUS_INPUT);
                sendText(user.getTelegramId(),
                        "🎁 Отправьте бонус в формате:\n<code>TELEGRAM_ID XP COINS комментарий</code>\n\n"
                                + "Пример: <code>123456789 100 50 За активность</code>",
                        cancelKeyboard());
            }
            case "broadcast" -> {
                session.reset();
                session.setState(SessionState.BROADCAST_MESSAGE);
                sendText(user.getTelegramId(),
                        "📣 Отправьте текст рассылки. Я доставлю его всем зарегистрированным игрокам.",
                        cancelKeyboard());
            }
            case "stats" -> sendAdminStats(user);
            default -> {
                if (action.startsWith("quest:")) {
                    sendAdminQuestEditor(user, parseLong(action.substring("quest:".length())));
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
                }
            }
        }
        answer(callbackQuery.getId(), "Готово");
    }

    private void sendAdminQuestList(AppUser user) {
        List<Quest> quests = questService.findAll();
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Quest quest : quests) {
            buttons.add(keyboardFactory.callback("✏️ " + trim(quest.getTitle(), 30), "admin:quest:" + quest.getId()));
        }
        buttons.add(keyboardFactory.callback("🏠 Меню", "menu:admin"));
        sendText(user.getTelegramId(),
                "🗂️ <b>Управление квестами</b>\n\n"
                        + "Откройте карточку, чтобы обновить текст, награды или видимость задания.",
                keyboardFactory.smartLayout(buttons));
    }

    private void sendAdminQuestEditor(AppUser user, Long questId) {
        Quest quest = questService.getQuest(questId);
        List<InlineKeyboardButton> buttons = List.of(
                keyboardFactory.callback("✏️ Название", "admin:edit-title:" + questId),
                keyboardFactory.callback("📝 Описание", "admin:edit-description:" + questId),
                keyboardFactory.callback("✨ Награды", "admin:edit-reward:" + questId),
                keyboardFactory.callback(quest.isActive() ? "⏸️ Скрыть" : "▶️ Включить", "admin:toggle:" + questId),
                keyboardFactory.callback("🛠️ Админка", "menu:admin")
        );
        sendText(user.getTelegramId(),
                "✏️ <b>Редактор квеста</b>\n\n"
                        + "🎯 <b>" + escape(quest.getTitle()) + "</b>\n"
                        + "📚 Категория: <b>" + escape(quest.getCategory()) + "</b>\n"
                        + "🎮 Игра: <b>" + escape(quest.getGameName()) + "</b>\n"
                        + "✨ XP: <b>+" + quest.getRewardXp() + "</b>\n"
                        + "🪙 Монеты: <b>+" + quest.getRewardCoins() + "</b>\n"
                        + "📡 Статус: <b>" + (quest.isActive() ? "активен" : "скрыт") + "</b>",
                keyboardFactory.smartLayout(buttons));
    }

    private void sendAdminStats(AppUser user) {
        List<AppUser> users = userService.allRegisteredUsers();
        long activeUsers = users.stream()
                .filter(player -> player.getLastActivityDate() != null)
                .filter(player -> !player.getLastActivityDate().isBefore(java.time.LocalDate.now().minusDays(7)))
                .count();
        long totalQuestExpenses = questService.findAll().stream()
                .mapToLong(quest -> quest.getRewardCoins())
                .sum();

        sendText(user.getTelegramId(),
                "📊 <b>Статистика платформы</b>\n\n"
                        + "👥 Всего игроков: <b>" + userService.totalRegisteredUsers() + "</b>\n"
                        + "🟢 Активных за 7 дней: <b>" + activeUsers + "</b>\n"
                        + "🆕 Новых игроков: <b>" + users.stream().filter(player -> player.getCreatedAt().toLocalDate().equals(java.time.LocalDate.now())).count() + "</b>\n"
                        + "✅ Выполненных заданий: <b>" + users.stream().mapToInt(AppUser::getCompletedQuests).sum() + "</b>\n"
                        + "💸 Потенциальные расходы на выплаты: <b>" + totalQuestExpenses + " монет</b>\n"
                        + "📥 Заявок на модерации: <b>" + questService.pendingCount() + "</b>",
                keyboardFactory.smartLayout(List.of(
                        keyboardFactory.callback("🛠️ Админка", "menu:admin"),
                        keyboardFactory.callback("🏠 Меню", "menu:main")
                )));
    }

    private void handleBonusInput(AppUser user, UserSession session, String text) {
        String[] parts = text.trim().split("\\s+", 4);
        if (parts.length < 3) {
            sendText(user.getTelegramId(),
                    "⚠️ Формат неверный. Используйте: <code>TELEGRAM_ID XP COINS комментарий</code>",
                    cancelKeyboard());
            return;
        }

        Long telegramId = parseLong(parts[0]);
        Long xp = parsePositiveLong(parts[1]);
        Long coins = parsePositiveLong(parts[2]);
        if (telegramId == null || xp == null || coins == null) {
            sendText(user.getTelegramId(), "⚠️ ID, XP и COINS должны быть числами.", cancelKeyboard());
            return;
        }

        userService.addManualBonus(telegramId, xp, coins);
        session.reset();
        notifyUser(telegramId,
                "🎁 Администратор начислил вам бонус.\n\n✨ XP: <b>+" + xp + "</b>\n🪙 Монеты: <b>+" + coins + "</b>");
        sendText(user.getTelegramId(), "✅ Бонус начислен игроку " + telegramId + ".", mainMenuKeyboard(user));
    }

    private void handleBroadcast(AppUser user, UserSession session, String text) {
        int delivered = 0;
        for (AppUser player : userService.allRegisteredUsers()) {
            try {
                sendText(player.getTelegramId(),
                        "📣 <b>Новости платформы</b>\n\n" + escape(text),
                        mainMenuKeyboard(player));
                delivered++;
            } catch (Exception exception) {
                log.warn("Failed to broadcast to {}", player.getTelegramId(), exception);
            }
        }
        session.reset();
        sendText(user.getTelegramId(), "✅ Рассылка отправлена. Получателей: <b>" + delivered + "</b>.", mainMenuKeyboard(user));
    }

    private void handleQuestCreateFinish(AppUser user, UserSession session, String text) {
        Integer limit = parseInteger(text.trim());
        if (limit == null || limit < 1) {
            sendText(user.getTelegramId(), "⚠️ Лимит участников должен быть положительным числом.", cancelKeyboard());
            return;
        }

        Quest quest = new Quest();
        quest.setTitle(session.getData().get("title"));
        quest.setDescription(session.getData().get("description"));
        quest.setGameName(session.getData().get("game"));
        quest.setCategory(session.getData().get("category"));
        quest.setPlatform(session.getData().get("platform"));
        quest.setDurationText(session.getData().get("duration"));
        quest.setRewardXp(Long.parseLong(session.getData().get("xp")));
        quest.setRewardCoins(Long.parseLong(session.getData().get("coins")));
        quest.setInstruction(session.getData().get("instruction"));
        quest.setRequirements(session.getData().get("requirements"));
        quest.setParticipantLimit(limit);

        questService.createQuest(quest);
        session.reset();
        sendText(user.getTelegramId(),
                "✅ Новый квест создан и сразу опубликован в списке доступных заданий.",
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
                mainMenuKeyboard(user));
    }

    private void notifyModeratorsAboutSubmission(Long submissionId) {
        QuestSubmission submission = questService.getSubmission(submissionId);
        String caption = "🧾 Новая заявка на модерацию\n\n"
                + "👤 " + escape(submission.getUser().getNickname()) + " (" + submission.getUser().getTelegramId() + ")\n"
                + "🎯 " + escape(submission.getQuest().getTitle()) + "\n"
                + "💬 " + escape(submission.getUserComment());

        InlineKeyboardMarkup markup = keyboardFactory.smartLayout(List.of(
                keyboardFactory.callback("✅ Одобрить", "mod:ok:" + submissionId),
                keyboardFactory.callback("❌ Отклонить", "mod:no:" + submissionId),
                keyboardFactory.callback("❓ Уточнить", "mod:more:" + submissionId)
        ));

        Set<Long> recipients = new LinkedHashSet<>();
        recipients.addAll(appProperties.getModeratorIds());
        recipients.addAll(appProperties.getAdminIds());

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

    private void notifyAdminsAboutRewardRequest(AppUser user, RewardItem reward) {
        for (Long adminId : appProperties.getAdminIds()) {
            sendText(adminId,
                    "🛍️ Новая заявка на выдачу награды\n\n"
                            + "👤 Игрок: <b>" + escape(user.getNickname()) + "</b>\n"
                            + "🆔 Telegram ID: <b>" + user.getTelegramId() + "</b>\n"
                            + "🎁 Награда: <b>" + escape(reward.getTitle()) + "</b>\n"
                            + "🪙 Стоимость: <b>" + reward.getPriceCoins() + " монет</b>",
                    null);
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
                    keyboardFactory.callback("🛠️ Админка", "menu:admin"),
                    keyboardFactory.callback("🛡️ Модерация", "menu:moderation")
            ));
            rows.add(List.of(
                    keyboardFactory.callback("👤 Профиль", "menu:profile"),
                    keyboardFactory.callback("🗺️ Квесты", "menu:quests")
            ));
            rows.add(List.of(
                    keyboardFactory.callback("💰 Баланс", "menu:balance"),
                    keyboardFactory.callback("🤝 Рефералы", "menu:referrals")
            ));
            rows.add(List.of(
                    keyboardFactory.callback("🏆 Рейтинг", "menu:rating"),
                    keyboardFactory.callback("📰 Новости", "menu:news")
            ));
            rows.add(List.of(keyboardFactory.callback("🛍️ Магазин", "menu:shop")));
            rows.add(List.of(keyboardFactory.callback("🆘 Поддержка", "menu:support")));
            return keyboardFactory.rowsLayout(rows);
        }

        if (ROLE_MODER.equals(role)) {
            rows.add(List.of(
                    keyboardFactory.callback("🛡️ Модерация", "menu:moderation"),
                    keyboardFactory.callback("🆘 Поддержка", "menu:support")
            ));
            rows.add(List.of(
                    keyboardFactory.callback("👤 Профиль", "menu:profile"),
                    keyboardFactory.callback("🗺️ Квесты", "menu:quests")
            ));
            rows.add(List.of(
                    keyboardFactory.callback("💰 Баланс", "menu:balance"),
                    keyboardFactory.callback("🏆 Рейтинг", "menu:rating")
            ));
            rows.add(List.of(
                    keyboardFactory.callback("🤝 Рефералы", "menu:referrals"),
                    keyboardFactory.callback("📰 Новости", "menu:news")
            ));
            rows.add(List.of(keyboardFactory.callback("🛍️ Магазин", "menu:shop")));
            return keyboardFactory.rowsLayout(rows);
        }

        rows.add(List.of(
                keyboardFactory.callback("👤 Профиль", "menu:profile"),
                keyboardFactory.callback("🗺️ Квесты", "menu:quests")
        ));
        rows.add(List.of(
                keyboardFactory.callback("💰 Баланс", "menu:balance"),
                keyboardFactory.callback("🤝 Рефералы", "menu:referrals")
        ));
        rows.add(List.of(
                keyboardFactory.callback("🏆 Рейтинг", "menu:rating"),
                keyboardFactory.callback("📰 Новости", "menu:news")
        ));
        rows.add(List.of(keyboardFactory.callback("🛍️ Магазин", "menu:shop")));
        rows.add(List.of(keyboardFactory.callback("🆘 Поддержка", "menu:support")));
        return keyboardFactory.rowsLayout(rows);
    }

    private InlineKeyboardMarkup backMenuKeyboard(String backData) {
        return keyboardFactory.rowsLayout(List.of(
                List.of(
                        keyboardFactory.callback("⬅️ Назад", backData),
                        keyboardFactory.callback("🏠 Меню", "menu:main")
                )
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
                selectionKeyboard(INTEREST_OPTIONS, selected, "reg:interest:", true, true, false)
        );
    }

    private String platformQuestionText(List<String> selected) {
        return "🎯 Выберите платформы, на которых вам интересны задания.\n\n"
                + "Сейчас выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>";
    }

    private String interestQuestionText(List<String> selected) {
        return "🧠 Выберите игровые интересы. Этот шаг можно пропустить.\n\n"
                + "Сейчас выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>";
    }

    private String mainMenuText(AppUser user) {
        String role = resolveMenuRole(user, sessionService.get(user.getTelegramId()));
        String title = switch (role) {
            case ROLE_ADMIN -> "🛠️ <b>Пульт администратора</b>";
            case ROLE_MODER -> "🛡️ <b>Пульт модератора</b>";
            default -> "🏠 <b>Главное меню</b>";
        };
        return title + "\n\n"
                + "Привет, <b>" + escape(user.getNickname()) + "</b>.\n"
                + "Активный режим: <b>" + escape(humanRole(role)) + "</b>.\n"
                + "Выберите нужный раздел ниже.";
    }

    private String displayValue(String value, String fallback) {
        if (value == null || value.isBlank() || "Не выбраны".equalsIgnoreCase(value)) {
            return fallback;
        }
        return value;
    }

    private String levelProgressLine(AppUser user) {
        long xp = user.getXp();
        long floor = currentLevelFloor(xp);
        long ceiling = nextLevelCeiling(xp);
        if (ceiling == floor) {
            return "🏁 Максимальный ранг уже открыт";
        }
        long progress = xp - floor;
        long range = ceiling - floor;
        int filled = (int) Math.max(0, Math.min(8, Math.round((progress * 8.0) / range)));
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            bar.append(i < filled ? '■' : '□');
        }
        return "📈 " + bar + " <b>" + xp + " / " + ceiling + " XP</b>";
    }

    private long currentLevelFloor(long xp) {
        if (xp >= 6000) {
            return 6000;
        }
        if (xp >= 3000) {
            return 3000;
        }
        if (xp >= 1500) {
            return 1500;
        }
        if (xp >= 700) {
            return 700;
        }
        if (xp >= 300) {
            return 300;
        }
        if (xp >= 100) {
            return 100;
        }
        return 0;
    }

    private long nextLevelCeiling(long xp) {
        if (xp < 100) {
            return 100;
        }
        if (xp < 300) {
            return 300;
        }
        if (xp < 700) {
            return 700;
        }
        if (xp < 1500) {
            return 1500;
        }
        if (xp < 3000) {
            return 3000;
        }
        if (xp < 6000) {
            return 6000;
        }
        return 6000;
    }

    private String categoryToken(String category) {
        if (category == null) {
            return "all";
        }
        return switch (category) {
            case "Быстрые" -> "fast";
            case "Средние" -> "medium";
            case "Долгие" -> "long";
            default -> "all";
        };
    }

    private String backDataFromCategoryToken(String token) {
        return switch (token) {
            case "fast" -> "quests:cat:Быстрые";
            case "medium" -> "quests:cat:Средние";
            case "long" -> "quests:cat:Долгие";
            default -> "quests:cat:all";
        };
    }

    private String currentQuestBackData(AppUser user) {
        return sessionService.get(user.getTelegramId()).getData().getOrDefault("quest_back_data", "quests:cat:all");
    }

    private boolean handleRoleSwitchCommand(AppUser user, UserSession session, String text) {
        if (!adminService.isAdmin(user.getTelegramId())) {
            return false;
        }
        return switch (text.toLowerCase()) {
            case "/user" -> {
                session.getData().put("active_role", ROLE_USER);
                sendMainMenu(user, "👤 Включён пользовательский режим.\n\nПоказываю меню и сценарии так, как их видит обычный игрок.");
                yield true;
            }
            case "/moder" -> {
                session.getData().put("active_role", ROLE_MODER);
                sendMainMenu(user, "🛡️ Включён режим модератора.\n\nОткрываю меню проверки заявок, поддержки и игровых разделов.");
                yield true;
            }
            case "/admin" -> {
                session.getData().put("active_role", ROLE_ADMIN);
                sendMainMenu(user, "🛠️ Включён полный режим администратора.\n\nОткрываю административное меню со всеми разделами.");
                yield true;
            }
            default -> false;
        };
    }

    private boolean isEffectiveModerator(AppUser user) {
        if (adminService.isAdmin(user.getTelegramId())) {
            String role = getActiveRole(sessionService.get(user.getTelegramId()));
            return ROLE_ADMIN.equals(role) || ROLE_MODER.equals(role);
        }
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
        if (adminService.isAdmin(user.getTelegramId())) {
            return ROLE_ADMIN;
        }
        if (adminService.isModerator(user.getTelegramId())) {
            return ROLE_MODER;
        }
        return ROLE_USER;
    }

    private String roleWelcomeText(AppUser user, String streakMessage) {
        String title = switch (resolveMenuRole(user, sessionService.get(user.getTelegramId()))) {
            case ROLE_ADMIN -> "🛠️ <b>Административный контур активен</b>";
            case ROLE_MODER -> "🛡️ <b>Контур модерации активен</b>";
            default -> "🏠 <b>Платформа готова к игре</b>";
        };
        String activity = streakMessage == null
                ? "Все ключевые разделы уже готовы к работе."
                : escape(streakMessage);
        return title + "\n\n"
                + "С возвращением, <b>" + escape(user.getNickname()) + "</b>.\n"
                + activity;
    }

    private String humanRole(String role) {
        return switch (role) {
            case ROLE_USER -> "Игрок";
            case ROLE_MODER -> "Модератор";
            default -> "Администратор";
        };
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
        recipients.addAll(appProperties.getModeratorIds());
        recipients.addAll(appProperties.getAdminIds());

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
