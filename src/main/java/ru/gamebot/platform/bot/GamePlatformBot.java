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
import ru.gamebot.platform.service.AdminService;
import ru.gamebot.platform.service.NewsService;
import ru.gamebot.platform.service.QuestService;
import ru.gamebot.platform.service.RewardService;
import ru.gamebot.platform.service.SessionService;
import ru.gamebot.platform.service.UserService;

@Slf4j
@Component
@RequiredArgsConstructor
public class GamePlatformBot extends TelegramLongPollingBot {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+|t\\.me/\\S+)");
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
    private final KeyboardFactory keyboardFactory;

    @PostConstruct
    public void registerBot() throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(this);
        log.info("Telegram bot registered: {}", getBotUsername());
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

        if (!user.isRegistrationCompleted() && session.getState() == SessionState.NONE) {
            session.setState(SessionState.REG_NAME);
            sendText(user.getTelegramId(),
                    "🎉 Добро пожаловать в <b>" + escape(appProperties.getClubName()) + "</b>!\n\n"
                            + "Чтобы открыть квесты, рейтинг и награды, давайте быстро оформим профиль.\n"
                            + "Напишите ваш игровой никнейм.",
                    cancelKeyboard());
            return;
        }

        if (session.getState() == SessionState.REPORT_MEDIA) {
            handleReportMessage(user, session, message);
            return;
        }

        if (text != null && session.getState() != SessionState.NONE) {
            handleStateInput(user, session, text);
            return;
        }

        if ("/menu".equalsIgnoreCase(text)) {
            sendMainMenu(user, "🏠 Главное меню готово. Выбирайте раздел ниже.");
            return;
        }

        if (!user.isRegistrationCompleted()) {
            sendText(user.getTelegramId(),
                    "🧭 Сначала завершим регистрацию. Напишите ответ на текущий шаг или нажмите отмену.",
                    cancelKeyboard());
            return;
        }

        sendMainMenu(user, "✨ Я на связи. Все разделы бота открываются через inline-кнопки ниже.");
    }

    private void handleStart(Message message) {
        Long referredBy = parseStartReferral(message.getText());
        AppUser user = userService.getOrCreate(message.getFrom(), referredBy);
        UserSession session = sessionService.get(user.getTelegramId());

        String streakMessage = userService.registerActivity(user);
        if (!user.isRegistrationCompleted()) {
            session.reset();
            session.setState(SessionState.REG_NAME);
            sendText(user.getTelegramId(),
                    "🎮 Добро пожаловать в <b>" + escape(appProperties.getClubName()) + "</b>!\n\n"
                            + "Здесь вас ждут квесты, XP, рейтинг, награды и реферальная программа.\n"
                            + "Начнем с профиля. Напишите ваш игровой никнейм.",
                    cancelKeyboard());
            return;
        }

        String intro = "🚀 С возвращением, <b>" + escape(user.getNickname()) + "</b>!\n\n"
                + (streakMessage == null ? "Все игровые разделы уже готовы к работе." : escape(streakMessage));
        sendMainMenu(user, intro);
    }

    private void handleCallback(CallbackQuery callbackQuery) {
        Long telegramId = callbackQuery.getFrom().getId();
        AppUser user = userService.getOrCreate(callbackQuery.getFrom(), null);
        UserSession session = sessionService.get(telegramId);
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
        if ("common:cancel".equals(data)) {
            session.reset();
            sendMainMenu(user, "↩️ Текущее действие отменено. Возвращаю вас в главное меню.");
            answer(callbackQuery.getId(), "Отменено");
            return;
        }

        if (!user.isRegistrationCompleted()) {
            answer(callbackQuery.getId(), "Сначала завершим регистрацию");
            sendText(user.getTelegramId(),
                    "🧭 Перед использованием разделов нужно закончить регистрацию. Продолжим с текущего шага.",
                    cancelKeyboard());
            return;
        }

        if (data.startsWith("menu:")) {
            handleMenuAction(callbackQuery, user, data.substring("menu:".length()));
            return;
        }
        if (data.startsWith("quests:cat:")) {
            String category = data.substring("quests:cat:".length());
            sendQuestList(user, "all".equals(category) ? null : category);
            answer(callbackQuery.getId(), "Раздел обновлен");
            return;
        }
        if (data.startsWith("quest:view:")) {
            sendQuestCard(user, parseLong(data.substring("quest:view:".length())));
            answer(callbackQuery.getId(), "Карточка квеста");
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
        if (data.startsWith("mod:view:") && adminService.isModerator(telegramId)) {
            sendSubmissionCard(user.getTelegramId(), parseLong(data.substring("mod:view:".length())));
            answer(callbackQuery.getId(), "Заявка открыта");
            return;
        }
        if (data.startsWith("mod:ok:") && adminService.isModerator(telegramId)) {
            handleModerationApprove(callbackQuery, parseLong(data.substring("mod:ok:".length())));
            return;
        }
        if (data.startsWith("mod:no:") && adminService.isModerator(telegramId)) {
            handleModerationReject(callbackQuery, parseLong(data.substring("mod:no:".length())));
            return;
        }
        if (data.startsWith("mod:more:") && adminService.isModerator(telegramId)) {
            handleModerationClarify(callbackQuery, parseLong(data.substring("mod:more:".length())));
            return;
        }
        if (data.startsWith("admin:") && adminService.isAdmin(telegramId)) {
            handleAdminAction(callbackQuery, user, session, data.substring("admin:".length()));
            return;
        }

        answer(callbackQuery.getId(), "Неизвестное действие");
    }

    private void handleMenuAction(CallbackQuery callbackQuery, AppUser user, String action) {
        switch (action) {
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
            case "moderation" -> sendModerationQueue(user.getTelegramId());
            default -> sendMainMenu(user, "🏠 Главное меню обновлено.");
        }
        answer(callbackQuery.getId(), "Готово");
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
            sendInterestQuestion(user, session);
            answer(callbackQuery.getId(), "Платформы сохранены");
            return;
        }

        toggleSelection(session, "platforms", action);
        sendPlatformQuestion(user, session);
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
        sendInterestQuestion(user, session);
        answer(callbackQuery.getId(), "Выбор обновлен");
    }

    private void handleStateInput(AppUser user, UserSession session, String text) {
        switch (session.getState()) {
            case REG_NAME -> {
                session.getData().put("nickname", text.trim());
                session.setState(SessionState.REG_AGE);
                sendText(user.getTelegramId(),
                        "🧾 Отлично, <b>" + escape(text.trim()) + "</b>!\n\nТеперь укажите возраст числом.",
                        cancelKeyboard());
            }
            case REG_AGE -> {
                Integer age = parseInteger(text.trim());
                if (age == null || age < 10 || age > 99) {
                    sendText(user.getTelegramId(),
                            "⚠️ Возраст должен быть числом в диапазоне 10-99. Попробуйте ещё раз.",
                            cancelKeyboard());
                    return;
                }
                session.getData().put("age", age.toString());
                session.setState(SessionState.REG_COUNTRY);
                sendText(user.getTelegramId(),
                        "🌍 Отлично. Теперь напишите страну, из которой вы играете.",
                        cancelKeyboard());
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
        sendText(user.getTelegramId(),
                "🎯 Выберите платформы, на которых вам интересны задания.\n\n"
                        + "Сейчас выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>",
                selectionKeyboard(PLATFORM_OPTIONS, selected, "reg:platform:", true, false));
    }

    private void sendInterestQuestion(AppUser user, UserSession session) {
        List<String> selected = resolveSelections(session, "interests", INTEREST_OPTIONS);
        sendText(user.getTelegramId(),
                "🧠 Выберите игровые интересы. Этот шаг можно пропустить.\n\n"
                        + "Сейчас выбрано: <b>" + escape(selected.isEmpty() ? "ничего" : String.join(", ", selected)) + "</b>",
                selectionKeyboard(INTEREST_OPTIONS, selected, "reg:interest:", true, true));
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
                "✅ Профиль готов!\n\n"
                        + "👤 Никнейм: <b>" + escape(updated.getNickname()) + "</b>\n"
                        + "🌍 Страна: <b>" + escape(updated.getCountry()) + "</b>\n"
                        + "🕹️ Платформы: <b>" + escape(updated.getPlatformsCsv()) + "</b>\n"
                        + "🎯 Интересы: <b>" + escape(updated.getInterestsCsv()) + "</b>\n"
                        + "🏅 Достижения: " + escape(achievements));
    }

    private void sendMainMenu(AppUser user, String text) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(keyboardFactory.callback("👤 Профиль", "menu:profile"));
        buttons.add(keyboardFactory.callback("🗺️ Квесты", "menu:quests"));
        buttons.add(keyboardFactory.callback("📂 Мои квесты", "menu:myquests"));
        buttons.add(keyboardFactory.callback("💰 Баланс", "menu:balance"));
        buttons.add(keyboardFactory.callback("🏆 Рейтинг", "menu:rating"));
        buttons.add(keyboardFactory.callback("🤝 Рефералы", "menu:referrals"));
        buttons.add(keyboardFactory.callback("🛍️ Магазин", "menu:shop"));
        buttons.add(keyboardFactory.callback("📰 Новости", "menu:news"));
        buttons.add(keyboardFactory.callback("🆘 Поддержка", "menu:support"));
        if (adminService.isModerator(user.getTelegramId())) {
            buttons.add(keyboardFactory.callback("🛡️ Модерация", "menu:moderation"));
        }
        if (adminService.isAdmin(user.getTelegramId())) {
            buttons.add(keyboardFactory.callback("🛠️ Админка", "menu:admin"));
        }

        sendText(user.getTelegramId(), text, keyboardFactory.smartLayout(buttons));
    }

    private void sendProfile(AppUser user) {
        long rank = userService.getOverallRank(user);
        String achievements = userService.getAchievements(user).isEmpty()
                ? "Пока нет, но первое достижение уже близко."
                : String.join(", ", userService.getAchievements(user));

        sendText(user.getTelegramId(),
                "👤 <b>Личный кабинет</b>\n\n"
                        + "🆔 ID игрока: <b>" + user.getTelegramId() + "</b>\n"
                        + "🎮 Никнейм: <b>" + escape(user.getNickname()) + "</b>\n"
                        + "📅 Регистрация: <b>" + escape(user.getCreatedAt().format(DATE_TIME_FORMATTER)) + "</b>\n"
                        + "⭐ Уровень: <b>" + escape(userService.getLevelName(user.getXp())) + "</b>\n"
                        + "✨ XP: <b>" + user.getXp() + "</b>\n"
                        + "🪙 Монеты: <b>" + user.getCoins() + "</b>\n"
                        + "✅ Выполнено квестов: <b>" + user.getCompletedQuests() + "</b>\n"
                        + "🏆 Место в рейтинге: <b>" + rank + "</b>\n"
                        + "🤝 Рефералы: <b>" + user.getInvitedFriends() + "</b>\n"
                        + "🔥 Серия входов: <b>" + user.getStreakDays() + " дней</b>\n"
                        + "🛡️ Статус: <b>Активен</b>\n"
                        + "🏅 Достижения: " + escape(achievements),
                mainMenuKeyboard(user));
    }

    private void sendBalance(AppUser user) {
        sendText(user.getTelegramId(),
                "💰 <b>Баланс игрока</b>\n\n"
                        + "🪙 Монеты клуба: <b>" + user.getCoins() + "</b>\n"
                        + "✨ XP: <b>" + user.getXp() + "</b>\n"
                        + "📈 Недельный XP: <b>" + user.getWeeklyXp() + "</b>\n\n"
                        + "🔧 Архитектура уже подготовлена под будущие выплаты в RUB, USDT и внутриигровых валютах.",
                mainMenuKeyboard(user));
    }

    private void sendQuestCategories(AppUser user) {
        List<InlineKeyboardButton> buttons = List.of(
                keyboardFactory.callback("⚡ Быстрые", "quests:cat:Быстрые"),
                keyboardFactory.callback("🎯 Средние", "quests:cat:Средние"),
                keyboardFactory.callback("🏰 Долгие", "quests:cat:Долгие"),
                keyboardFactory.callback("📚 Все квесты", "quests:cat:all"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        );
        sendText(user.getTelegramId(),
                "🗺️ <b>Раздел квестов</b>\n\n"
                        + "Выберите категорию. Внутри каждой карточки есть описание, награды и кнопки действий.",
                keyboardFactory.smartLayout(buttons));
    }

    private void sendQuestList(AppUser user, String category) {
        List<Quest> quests = category == null ? questService.findActiveQuests() : questService.findByCategory(category);
        if (quests.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📭 В этом разделе пока нет активных квестов. Проверьте позже или загляните в другие категории.",
                    mainMenuKeyboard(user));
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Quest quest : quests) {
            buttons.add(keyboardFactory.callback("🎯 " + trim(quest.getTitle(), 32), "quest:view:" + quest.getId()));
        }
        buttons.add(keyboardFactory.callback("🏠 Меню", "menu:main"));

        String title = category == null ? "📚 Все доступные квесты" : "📚 Категория: " + category;
        sendText(user.getTelegramId(),
                "<b>" + escape(title) + "</b>\n\nВыберите карточку, чтобы посмотреть награды, сроки и инструкцию.",
                keyboardFactory.smartLayout(buttons));
    }

    private void sendQuestCard(AppUser user, Long questId) {
        Quest quest = questService.getQuest(questId);
        QuestSubmission latest = questService.getLatestSubmission(user, quest);
        String statusText = latest == null ? "Не начат" : humanStatus(latest.getStatus());

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(keyboardFactory.callback("🚀 Взять", "quest:take:" + questId));
        buttons.add(keyboardFactory.callback("📤 Отчёт", "quest:report:" + questId));
        buttons.add(keyboardFactory.callback("🗂️ Квесты", "menu:quests"));
        if (adminService.isAdmin(user.getTelegramId())) {
            buttons.add(keyboardFactory.callback("✏️ Правка", "admin:quest:" + questId));
        }

        sendText(user.getTelegramId(),
                "🎯 <b>" + escape(quest.getTitle()) + "</b>\n\n"
                        + "🎮 Игра: <b>" + escape(quest.getGameName()) + "</b>\n"
                        + "📚 Категория: <b>" + escape(quest.getCategory()) + "</b>\n"
                        + "🕹️ Платформа: <b>" + escape(quest.getPlatform()) + "</b>\n"
                        + "⏳ Срок: <b>" + escape(quest.getDurationText()) + "</b>\n"
                        + "✨ Награда: <b>+" + quest.getRewardXp() + " XP</b>\n"
                        + "🪙 Монеты: <b>+" + quest.getRewardCoins() + "</b>\n"
                        + "📌 Статус: <b>" + escape(statusText) + "</b>\n\n"
                        + "📝 Описание:\n" + escape(quest.getDescription()) + "\n\n"
                        + "📎 Инструкция:\n" + escape(quest.getInstruction()) + "\n\n"
                        + "✅ Требования:\n" + escape(quest.getRequirements()),
                keyboardFactory.smartLayout(buttons));
    }

    private void handleTakeQuest(CallbackQuery callbackQuery, AppUser user, Long questId) {
        Quest quest = questService.getQuest(questId);
        QuestSubmission latest = questService.getLatestSubmission(user, quest);
        if (latest != null && latest.getStatus() == SubmissionStatus.DRAFT) {
            answer(callbackQuery.getId(), "Квест уже у вас в работе");
            sendQuestCard(user, questId);
            return;
        }
        if (latest != null && (latest.getStatus() == SubmissionStatus.PENDING || latest.getStatus() == SubmissionStatus.APPROVED)) {
            answer(callbackQuery.getId(), "Квест уже в работе или принят");
            sendQuestCard(user, questId);
            return;
        }

        questService.createDraftSubmission(user, quest);
        sendText(user.getTelegramId(),
                "🚀 Квест <b>" + escape(quest.getTitle()) + "</b> добавлен в работу.\n\n"
                        + "Когда будете готовы, откройте карточку квеста и отправьте отчёт.",
                mainMenuKeyboard(user));
        answer(callbackQuery.getId(), "Квест взят");
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
                "✅ Отчёт принят в очередь на проверку.\n\n"
                        + "Модератор увидит ваш файл, комментарий и карточку задания. После проверки XP и монеты начислятся автоматически.",
                mainMenuKeyboard(user));
    }

    private void sendMySubmissions(AppUser user) {
        List<QuestSubmission> submissions = questService.getUserSubmissions(user);
        if (submissions.isEmpty()) {
            sendText(user.getTelegramId(),
                    "📭 У вас пока нет взятых или отправленных квестов.\n\nОткройте раздел квестов и начните с первого задания.",
                    mainMenuKeyboard(user));
            return;
        }

        StringBuilder builder = new StringBuilder("📂 <b>Мои квесты и отчёты</b>\n\n");
        submissions.stream().limit(10).forEach(submission -> builder
                .append("🎯 <b>").append(escape(submission.getQuest().getTitle())).append("</b>\n")
                .append("📌 Статус: <b>").append(escape(humanStatus(submission.getStatus()))).append("</b>\n")
                .append("🕒 Обновлено: <b>").append(escape(submission.getUpdatedAt().format(DATE_TIME_FORMATTER))).append("</b>\n\n"));

        sendText(user.getTelegramId(), builder.toString(), mainMenuKeyboard(user));
    }

    private void sendRatingMenu(AppUser user) {
        List<InlineKeyboardButton> buttons = List.of(
                keyboardFactory.callback("🌍 Общий", "rate:overall"),
                keyboardFactory.callback("📆 Недельный", "rate:weekly"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        );
        sendText(user.getTelegramId(),
                "🏆 <b>Рейтинги игроков</b>\n\nВыберите формат лидерборда.",
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
        sendText(user.getTelegramId(), builder.toString(), mainMenuKeyboard(user));
    }

    private void sendReferrals(AppUser user) {
        String referralLink = "https://t.me/" + appProperties.getBotUsername() + "?start=ref_" + user.getTelegramId();
        sendText(user.getTelegramId(),
                "🤝 <b>Реферальная система</b>\n\n"
                        + "🔗 Ваша ссылка:\n" + escape(referralLink) + "\n\n"
                        + "👥 Приглашено друзей: <b>" + user.getInvitedFriends() + "</b>\n"
                        + "🎁 Награда за активного друга: <b>+30 XP и +50 монет</b>\n\n"
                        + "Поделитесь ссылкой с друзьями, чтобы быстрее расти в рейтинге и копить монеты.",
                mainMenuKeyboard(user));
    }

    private void sendShop(AppUser user) {
        List<RewardItem> rewards = rewardService.findAvailableRewards();
        if (rewards.isEmpty()) {
            sendText(user.getTelegramId(), "🛍️ Магазин пока пуст. Скоро добавим новые награды.", mainMenuKeyboard(user));
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (RewardItem reward : rewards) {
            buttons.add(keyboardFactory.callback("🎁 " + trim(reward.getTitle(), 26), "shop:view:" + reward.getId()));
        }
        buttons.add(keyboardFactory.callback("🏠 Меню", "menu:main"));

        sendText(user.getTelegramId(),
                "🛍️ <b>Магазин наград</b>\n\n"
                        + "Обменивайте монеты на цифровые товары, подписки и мерч.\n"
                        + "Текущий баланс: <b>" + user.getCoins() + " монет</b>.",
                keyboardFactory.smartLayout(buttons));
    }

    private void sendRewardCard(AppUser user, Long rewardId) {
        RewardItem reward = rewardService.getRewardItem(rewardId);
        List<InlineKeyboardButton> buttons = List.of(
                keyboardFactory.callback("🛒 Обменять", "shop:buy:" + rewardId),
                keyboardFactory.callback("🛍️ Магазин", "menu:shop"),
                keyboardFactory.callback("🏠 Меню", "menu:main")
        );

        sendText(user.getTelegramId(),
                "🎁 <b>" + escape(reward.getTitle()) + "</b>\n\n"
                        + "📦 Категория: <b>" + escape(reward.getCategory()) + "</b>\n"
                        + "📝 Описание: " + escape(reward.getDescription()) + "\n"
                        + "🪙 Стоимость: <b>" + reward.getPriceCoins() + " монет</b>",
                keyboardFactory.smartLayout(buttons));
    }

    private void handleRewardPurchase(CallbackQuery callbackQuery, AppUser user, Long rewardId) {
        RewardItem reward = rewardService.getRewardItem(rewardId);
        rewardService.createRewardRequest(user, reward);
        notifyAdminsAboutRewardRequest(user, reward);
        sendText(user.getTelegramId(),
                "✅ Заявка на выдачу награды отправлена администратору.\n\n"
                        + "🎁 Награда: <b>" + escape(reward.getTitle()) + "</b>\n"
                        + "🪙 Списано: <b>" + reward.getPriceCoins() + " монет</b>",
                mainMenuKeyboard(user));
        answer(callbackQuery.getId(), "Заявка отправлена");
    }

    private void sendNews(AppUser user) {
        List<NewsPost> posts = newsService.latestNews();
        if (posts.isEmpty()) {
            sendText(user.getTelegramId(), "📰 Новости скоро появятся. Пока можно сфокусироваться на квестах.", mainMenuKeyboard(user));
            return;
        }

        StringBuilder builder = new StringBuilder("📰 <b>Новости клуба</b>\n\n");
        for (NewsPost post : posts) {
            builder.append("📣 <b>").append(escape(post.getTitle())).append("</b>\n")
                    .append(escape(post.getBody())).append("\n")
                    .append("🕒 ").append(escape(post.getPublishedAt().format(DATE_TIME_FORMATTER))).append("\n\n");
        }

        sendText(user.getTelegramId(), builder.toString(), mainMenuKeyboard(user));
    }

    private void sendSupport(AppUser user) {
        sendText(user.getTelegramId(),
                "🆘 <b>Поддержка</b>\n\n"
                        + "Если возникли вопросы по квестам, наградам, модерации или профилю, напишите менеджеру:\n"
                        + "👉 @" + escape(appProperties.getSupportUsername()) + "\n\n"
                        + "Чтобы мы помогли быстрее, приложите ID игрока и краткое описание ситуации.",
                mainMenuKeyboard(user));
    }

    private void sendModerationQueue(Long chatId) {
        List<QuestSubmission> submissions = questService.getPendingSubmissions();
        if (submissions.isEmpty()) {
            sendText(chatId,
                    "🛡️ Очередь проверки пуста. Все текущие отчёты уже разобраны.",
                    keyboardFactory.smartLayout(List.of(keyboardFactory.callback("🏠 Меню", "menu:main"))));
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (QuestSubmission submission : submissions) {
            String title = "🔎 " + trim(submission.getUser().getNickname() + " / " + submission.getQuest().getTitle(), 28);
            buttons.add(keyboardFactory.callback(title, "mod:view:" + submission.getId()));
        }
        buttons.add(keyboardFactory.callback("🏠 Меню", "menu:main"));

        sendText(chatId,
                "🛡️ <b>Очередь модерации</b>\n\n"
                        + "Заявок на проверке: <b>" + submissions.size() + "</b>\n"
                        + "Откройте карточку, чтобы принять, отклонить или запросить уточнение.",
                keyboardFactory.smartLayout(buttons));
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

        InlineKeyboardMarkup markup = keyboardFactory.smartLayout(List.of(
                keyboardFactory.callback("✅ Одобрить", "mod:ok:" + submissionId),
                keyboardFactory.callback("❌ Отклонить", "mod:no:" + submissionId),
                keyboardFactory.callback("❓ Уточнить", "mod:more:" + submissionId),
                keyboardFactory.callback("🛡️ Очередь", "menu:moderation")
        ));
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
        if (!adminService.isAdmin(user.getTelegramId())) {
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
                        + "Здесь можно создавать и редактировать квесты, проверять статистику, выдавать бонусы и запускать массовые рассылки.",
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
                "🗂️ <b>Управление квестами</b>\n\nВыберите карточку, чтобы изменить текст, награды или статус.",
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
                        + "🎯 " + escape(quest.getTitle()) + "\n"
                        + "📚 " + escape(quest.getCategory()) + "\n"
                        + "🎮 " + escape(quest.getGameName()) + "\n"
                        + "✨ +" + quest.getRewardXp() + " XP\n"
                        + "🪙 +" + quest.getRewardCoins() + " монет\n"
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
        return keyboardFactory.smartLayout(List.of(
                keyboardFactory.callback("🏠 Меню", "menu:main"),
                keyboardFactory.callback("👤 Профиль", "menu:profile"),
                keyboardFactory.callback(adminService.isModerator(user.getTelegramId()) ? "🛡️ Очередь" : "🗺️ Квесты",
                        adminService.isModerator(user.getTelegramId()) ? "menu:moderation" : "menu:quests")
        ));
    }

    private InlineKeyboardMarkup cancelKeyboard() {
        return keyboardFactory.smartLayout(List.of(keyboardFactory.callback("❌ Отмена", "common:cancel")));
    }

    private InlineKeyboardMarkup selectionKeyboard(Map<String, String> options, List<String> selected,
                                                   String prefix, boolean withDone, boolean withSkip) {
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
        buttons.add(keyboardFactory.callback("❌ Отмена", "common:cancel"));
        return keyboardFactory.smartLayout(buttons);
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
