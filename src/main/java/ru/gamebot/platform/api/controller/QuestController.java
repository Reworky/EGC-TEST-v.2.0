package ru.gamebot.platform.api.controller;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.gamebot.platform.api.dto.MyQuestDto;
import ru.gamebot.platform.util.DurationFormatter;
import ru.gamebot.platform.api.dto.QuestActionResponseDto;
import ru.gamebot.platform.api.dto.QuestDetailDto;
import ru.gamebot.platform.api.dto.QuestDto;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.BrawlQuestVerificationService;
import ru.gamebot.platform.service.QuestActionStatus;
import ru.gamebot.platform.service.QuestService;
import ru.gamebot.platform.service.TelegramFileService;

@Slf4j
@RestController
@RequestMapping("/api/quests")
@RequiredArgsConstructor
public class QuestController {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** Обложки игр в памяти: file_id -> байты (десяток картинок по ~100 КБ). */
    private static final java.util.concurrent.ConcurrentHashMap<String, byte[]> GAME_PHOTO_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int GAME_PHOTO_CACHE_MAX = 64;

    private final QuestService questService;
    private final AppUserRepository appUserRepository;
    private final TelegramFileService telegramFileService;
    private final GamePlatformBot gamePlatformBot;
    private final BrawlQuestVerificationService brawlQuestVerificationService;
    private final ru.gamebot.platform.service.ClashRoyaleQuestVerificationService clashRoyaleQuestVerificationService;
    private final ru.gamebot.platform.service.QuestRewardBoostService questRewardBoostService;
    private final ru.gamebot.platform.service.GameCatalogService gameCatalogService;
    private final ru.gamebot.platform.service.GameCoverService gameCoverService;
    private final ru.gamebot.platform.service.UserService userService;

    /** Награда для списка «Мои квесты» — там вперемешку активные и уже закрытые заявки, поэтому недельное
     *  снижение (правило 3.4) сюда НЕ подмешиваем: для уже одобренного квеста цифра «как если бы брали
     *  сейчас» была бы ложью о том, сколько реально начислили. Для обычных квестов — номинал; для
     *  repeatableNoCooldownEligible (пилот "квесты без стен") — сумма за следующее прохождение по кривой
     *  убывания. user == null (гость) — просто базовая цена. Показ ДО взятия — displayRewardCoins ниже. */
    private long historyRewardCoins(AppUser user, Quest quest) {
        return user != null && quest.isRepeatableNoCooldownEligible()
                ? questService.computeReward(user, quest).coins()
                : quest.getRewardCoins();
    }

    private static final QuestService.WeeklyLimitStatus NO_WEEKLY_LIMIT = new QuestService.WeeklyLimitStatus(0, 0, false);

    /** Недельный лимит (правило 3.4) для показа игроку. Считается по игре+категории, а квестов в списке
     *  десятки — cache живёт один запрос и не даёт делать десятки одинаковых COUNT. Гостю и квестам с
     *  собственной кривой убывания (repeatableNoCooldown + потолок окна) лимит не показываем и в кэш
     *  их не кладём — иначе один такой квест "отравил" бы кэш всей игры+категории. */
    private QuestService.WeeklyLimitStatus weeklyStatus(Map<String, QuestService.WeeklyLimitStatus> cache, AppUser user, Quest quest) {
        if (user == null || (quest.isRepeatableNoCooldownEligible() && quest.getTargetPeriodCeiling() != null)) {
            return NO_WEEKLY_LIMIT;
        }
        return cache.computeIfAbsent(quest.getGameName() + "|" + quest.getCategory(),
                key -> questService.weeklyLimitStatus(user, quest));
    }

    /** Награда для показа ДО взятия квеста: у обычных квестов — номинал, вдвое меньше, если недельный
     *  лимит уже исчерпан (иначе игрок видел бы 1500, а получал 750 без объяснений). */
    private long displayRewardCoins(AppUser user, Quest quest, QuestService.WeeklyLimitStatus weekly) {
        if (user == null) {
            return quest.getRewardCoins();
        }
        if (quest.isRepeatableNoCooldownEligible()) {
            return questService.computeReward(user, quest).coins();
        }
        return weekly.reached() ? QuestService.diminishedCoins(quest.getRewardCoins()) : quest.getRewardCoins();
    }

    /** Числовой прогресс для авто-верифицируемого квеста — тот же расчёт, что и в GamePlatformBot.autoVerifyProgressLabel,
     *  но без готового текста: фронтенд Mini App рисует прогресс сам (бар/проценты), не текстовой строкой. */
    private record AutoVerifyProgress(Integer progress, Integer target) {
        static final AutoVerifyProgress NONE = new AutoVerifyProgress(null, null);
    }

    private AutoVerifyProgress computeAutoVerifyProgress(Quest quest, QuestSubmission submission) {
        if (submission == null) {
            return AutoVerifyProgress.NONE;
        }
        if (quest.getBrawlVerifyType() != null) {
            boolean measured = !quest.getBrawlVerifyType().usesProfileBaseline()
                    || submission.getBrawlBaselineTrophies() != null;
            return new AutoVerifyProgress(measured ? submission.getBrawlProgressCount() : null, quest.getBrawlTargetCount());
        }
        if (quest.getClashVerifyType() != null) {
            boolean measured = submission.getClashBaselineValue() != null;
            return new AutoVerifyProgress(measured ? submission.getClashProgressCount() : null, quest.getClashTargetCount());
        }
        if (quest.getClashRoyaleVerifyType() != null) {
            boolean measured = submission.getClashRoyaleBaselineValue() != null;
            return new AutoVerifyProgress(measured ? submission.getClashRoyaleProgressCount() : null, quest.getClashRoyaleTargetCount());
        }
        if (quest.getDotaVerifyType() != null) {
            return new AutoVerifyProgress(submission.getDotaBestValue(), quest.getDotaTargetCount());
        }
        if (quest.getCs2VerifyType() != null) {
            boolean measured = submission.getCs2BaselineValue() != null;
            return new AutoVerifyProgress(measured ? submission.getCs2ProgressCount() : null, quest.getCs2TargetCount());
        }
        if (quest.getPubgVerifyType() != null) {
            return new AutoVerifyProgress(submission.getPubgProgressCount(), quest.getPubgTargetCount());
        }
        return AutoVerifyProgress.NONE;
    }

    @GetMapping
    public List<QuestDto> quests(
            @RequestParam(required = false) String game,
            @RequestParam(required = false) String category,
            @AuthenticationPrincipal Long telegramId) {
        var quests = new java.util.ArrayList<>((game != null && !game.isBlank())
                ? (category != null ? questService.findActiveByGameNameAndCategory(game, category)
                                    : questService.findActiveByGameName(game))
                : (category != null ? questService.findByCategory(category)
                                    : questService.findActiveQuests()));
        // Квесты «подпишись на канал» (спонсор) сдаются только в боте (кнопка «Я подписался»), в мини-апп их не показываем
        quests.removeIf(q -> q.getChannelCheckChatId() != null);
        // Новые квесты сверху — та же логика, что и в sendQuestList в боте (см. Quest.isEffectivelyNew).
        quests.sort(java.util.Comparator.comparing(Quest::isEffectivelyNew, java.util.Comparator.reverseOrder())
                .thenComparing(Quest::getTitle, String.CASE_INSENSITIVE_ORDER));

        Map<Long, String> statusByQuestId = new HashMap<>();
        AppUser currentUser = telegramId != null ? appUserRepository.findByTelegramId(telegramId).orElse(null) : null;
        if (currentUser != null) {
            for (QuestSubmission s : questService.getUserSubmissions(currentUser)) {
                statusByQuestId.putIfAbsent(s.getQuest().getId(), s.getStatus().name());
            }
        }

        // У квестов игр, переведённых на FLAT-режим (CS2/Dota 2/PUBG и т.п.), поле category в БД
        // часто остаётся заполнено ещё со времён деления на Лёгкие/Средние/Сложные — сама награда
        // уже единая (см. аудит наградной политики), но без этой правки мини-апп всё равно рисовал бы
        // устаревшие заголовки категорий. Обнуляем category в DTO для таких игр, не трогая базу.
        Map<String, Boolean> flatByGame = new HashMap<>();
        Map<String, QuestService.WeeklyLimitStatus> weeklyCache = new HashMap<>();
        return quests.stream().map(q -> QuestDto.builder()
                .id(q.getId())
                .title(q.getTitle())
                .description(q.getDescription())
                .gameName(q.getGameName())
                .category(flatByGame.computeIfAbsent(q.getGameName(), gameCatalogService::isFlat) ? null : q.getCategory())
                .platform(q.getPlatform())
                .durationDays(q.getDurationDays())
                .rewardXp(q.getRewardXp())
                .rewardCoins(displayRewardCoins(currentUser, q, weeklyStatus(weeklyCache, currentUser, q)))
                .rewardDiminished(weeklyStatus(weeklyCache, currentUser, q).reached())
                .weeklyLimit(weeklyStatus(weeklyCache, currentUser, q).limit())
                .ticketReward(q.getTicketReward())
                .councilOnly(q.isCouncilOnly())
                .sponsored(q.isSponsored())
                .externalAutoApprove(q.isExternalAutoApprove())
                .brawlAutoVerify(q.getBrawlVerifyType() != null || q.getClashVerifyType() != null || q.getClashRoyaleVerifyType() != null || q.getDotaVerifyType() != null || q.getCs2VerifyType() != null || q.getPubgVerifyType() != null)
                .highlightNew(q.isEffectivelyNew())
                .submissionStatus(statusByQuestId.get(q.getId()))
                .build()).toList();
    }

    @GetMapping("/games")
    public List<String> games(@AuthenticationPrincipal Long telegramId) {
        List<String> games = questService.findActiveGameNames();
        if (telegramId == null) {
            return games;
        }
        return appUserRepository.findByTelegramId(telegramId)
                .map(user -> questService.sortGamesByInterest(user, games))
                .orElse(games);
    }

    /** Обложка игры для карточек в разделе квестов — тот же паттерн проксирования Telegram file_id,
     *  что и ProfileController.getAvatar. 404, если у игры не загружено фото через админку —
     *  фронтенд на этот случай показывает градиентную заглушку. */
    @GetMapping("/games/{name}/photo")
    public org.springframework.http.ResponseEntity<byte[]> gamePhoto(@PathVariable String name) {
        // Прямые return из тела метода (не лямбда в Optional.map) — return-выражение получает
        // целевой тип из объявленного ResponseEntity<byte[]> метода, а внутри лямбды Java не может
        // так же вывести тип для ResponseEntity.notFound().build() (wildcard-захват, не компилируется).
        // Своя обложка 16:9 из ресурсов (GameCoverService) важнее фото из админки: ровно встаёт в карточки мини-аппа и сайта.
        java.util.Optional<byte[]> ownCover = gameCoverService.bytesFor(name);
        if (ownCover.isPresent()) {
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.IMAGE_JPEG)
                    .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)))
                    .body(ownCover.get());
        }
        java.util.Optional<String> fileId = gameCatalogService.getPhotoFileId(name);
        if (fileId.isEmpty()) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        // Кэш в памяти по file_id (27.10.2026): без него каждый запрос заново ходил в Telegram дважды (getFile + скачивание),
        // обложка грузилась 1-4 с. Новая загрузка фото даёт новый file_id, поэтому устаревания нет.
        byte[] cachedImage = GAME_PHOTO_CACHE.get(fileId.get());
        if (cachedImage != null) {
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.IMAGE_JPEG)
                    .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(24)))
                    .body(cachedImage);
        }
        try {
            byte[] image = telegramFileService.downloadFile(fileId.get());
            if (image != null && image.length > 0) {
                if (GAME_PHOTO_CACHE.size() >= GAME_PHOTO_CACHE_MAX) GAME_PHOTO_CACHE.clear();
                GAME_PHOTO_CACHE.put(fileId.get(), image);
            }
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.IMAGE_JPEG)
                    .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(24)))
                    .body(image);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return org.springframework.http.ResponseEntity.notFound().build();
        } catch (IOException e) {
            log.warn("Failed to fetch game photo for '{}'", name, e);
            return org.springframework.http.ResponseEntity.notFound().build();
        }
    }

    /** Персонализированный показ 1-2 квестов при заходе (аудит вовлечённости, 2026-09-14) — тот же
     *  подбор, что и кнопка "🎯 Квест для тебя" в боте (см. QuestService.recommendQuest). 204, если
     *  подобрать нечего (всё уже взято или на кулдауне) — фронтенд просто не показывает секцию. */
    @GetMapping("/recommended")
    public ResponseEntity<QuestDto> recommended(@AuthenticationPrincipal Long telegramId) {
        if (telegramId == null) {
            return ResponseEntity.noContent().build();
        }
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.noContent().build();
        }
        Map<String, QuestService.WeeklyLimitStatus> weeklyCache = new HashMap<>();
        return questService.recommendQuest(user)
                .map(q -> ResponseEntity.ok(QuestDto.builder()
                        .id(q.getId())
                        .title(q.getTitle())
                        .description(q.getDescription())
                        .gameName(q.getGameName())
                        .category(q.getCategory())
                        .platform(q.getPlatform())
                        .durationDays(q.getDurationDays())
                        .rewardXp(q.getRewardXp())
                        .rewardCoins(displayRewardCoins(user, q, weeklyStatus(weeklyCache, user, q)))
                        .rewardDiminished(weeklyStatus(weeklyCache, user, q).reached())
                        .weeklyLimit(weeklyStatus(weeklyCache, user, q).limit())
                        .ticketReward(q.getTicketReward())
                        .councilOnly(q.isCouncilOnly())
                        .sponsored(q.isSponsored())
                        .externalAutoApprove(q.isExternalAutoApprove())
                        .brawlAutoVerify(q.getBrawlVerifyType() != null || q.getClashVerifyType() != null || q.getClashRoyaleVerifyType() != null || q.getDotaVerifyType() != null || q.getCs2VerifyType() != null || q.getPubgVerifyType() != null)
                        .highlightNew(q.isEffectivelyNew())
                        .build()))
                .orElse(ResponseEntity.noContent().build());
    }

    /** Буст выходных на EXC за квесты (аудит вовлечённости, 2026-09-14) — та же информация, что уходит
     *  игрокам в канал при старте буста (см. WeeklyResetScheduler.startWeekendBoost), но для баннера
     *  в Mini App. Параллель с ReferralController.boostActive/boostMultiplier/boostEndsAt для рефералки. */
    @GetMapping("/boost")
    public ru.gamebot.platform.api.dto.QuestBoostDto boost() {
        var activeBoost = questRewardBoostService.findActiveBoost();
        return ru.gamebot.platform.api.dto.QuestBoostDto.builder()
                .active(activeBoost.isPresent())
                .multiplier(activeBoost.map(b -> 1 + b.getBoostPercent() / 100).orElse(null))
                .percent(activeBoost.map(b -> b.getBoostPercent()).orElse(null))
                .maxQuests(activeBoost.isPresent() ? ru.gamebot.platform.service.QuestRewardBoostService.MAX_BOOSTED_QUESTS_PER_USER : null)
                .endsAt(activeBoost.map(b -> b.getEndAt().toString()).orElse(null))
                .build();
    }

    @GetMapping("/sponsored")
    public List<QuestDto> sponsored(@AuthenticationPrincipal Long telegramId) {
        AppUser currentUser = telegramId != null ? appUserRepository.findByTelegramId(telegramId).orElse(null) : null;
        // Как в боте (sendSponsoredQuestList): только активные квесты; завершённая кампания пропадает у всех, в том числе у выполнивших (решение владельца 23.10.2026).
        var quests = new java.util.ArrayList<>(questService.findAllSponsored().stream()
                .filter(Quest::isActive)
                .toList());
        Map<Long, String> statusByQuestId = new java.util.HashMap<>();
        if (currentUser != null) {
            for (var q : quests) {
                java.util.Optional.ofNullable(questService.getLatestSubmission(currentUser, q))
                        .ifPresent(s -> statusByQuestId.put(q.getId(), s.getStatus().name()));
            }
        }
        Map<String, Boolean> flatByGame = new HashMap<>();
        Map<String, QuestService.WeeklyLimitStatus> weeklyCache = new HashMap<>();
        return quests.stream().map(q -> QuestDto.builder()
                .id(q.getId()).title(q.getTitle()).description(q.getDescription())
                .gameName(q.getGameName())
                .category(flatByGame.computeIfAbsent(q.getGameName(), gameCatalogService::isFlat) ? null : q.getCategory())
                .platform(q.getPlatform())
                .durationDays(q.getDurationDays()).rewardXp(q.getRewardXp())
                .rewardCoins(displayRewardCoins(currentUser, q, weeklyStatus(weeklyCache, currentUser, q)))
                .rewardDiminished(weeklyStatus(weeklyCache, currentUser, q).reached())
                .weeklyLimit(weeklyStatus(weeklyCache, currentUser, q).limit())
                .ticketReward(q.getTicketReward())
                .councilOnly(q.isCouncilOnly()).sponsored(true)
                .externalAutoApprove(q.isExternalAutoApprove())
                .brawlAutoVerify(q.getBrawlVerifyType() != null || q.getClashVerifyType() != null || q.getClashRoyaleVerifyType() != null || q.getDotaVerifyType() != null || q.getCs2VerifyType() != null || q.getPubgVerifyType() != null)
                .highlightNew(q.isEffectivelyNew())
                .submissionStatus(statusByQuestId.get(q.getId()))
                .channelCheck(q.getChannelCheckChatId() != null)
                .seatsLeft(seatsLeft(q))
                .seatsTotal(seatsTotal(q))
                .campaignClosed(q.getChannelCheckChatId() != null && questService.isChannelCampaignClosed(q))
                .build()).toList();
    }

    /** Есть выполненный квест или не истёкшая работа в процессе (DRAFT/PENDING/NEEDS_INFO) - такой квест показываем и после закрытия кампании. */
    private boolean hasDoneOrLiveSubmission(AppUser user, Quest quest) {
        if (user == null) return false;
        QuestSubmission sub = questService.getLatestSubmission(user, quest);
        if (sub == null) return false;
        var status = sub.getStatus();
        return status == ru.gamebot.platform.domain.enums.SubmissionStatus.APPROVED
                || ((status == ru.gamebot.platform.domain.enums.SubmissionStatus.DRAFT
                || status == ru.gamebot.platform.domain.enums.SubmissionStatus.PENDING
                || status == ru.gamebot.platform.domain.enums.SubmissionStatus.NEEDS_INFO) && !questService.isExpired(sub));
    }

    /** Лимит мест кампании «подпишись на канал»; null - лимита нет (или это не подписка на канал). */
    private Integer seatsTotal(Quest q) {
        if (q.getChannelCheckChatId() == null || q.getParticipantLimit() == null || q.getParticipantLimit() <= 0) {
            return null;
        }
        return ((Number) q.getParticipantLimit()).intValue();
    }

    private Integer seatsLeft(Quest q) {
        Integer total = seatsTotal(q);
        return total == null ? null : (int) Math.max(0, total - questService.countApprovedByQuest(q));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> questDetail(@PathVariable Long id, @AuthenticationPrincipal Long telegramId) {
        Quest quest;
        try {
            quest = questService.getQuest(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        String instruction = telegramId != null
                ? questService.personalizeInstruction(quest.getInstruction(), telegramId)
                : quest.getInstruction();

        QuestDetailDto.QuestDetailDtoBuilder builder = QuestDetailDto.builder()
                .id(quest.getId())
                .title(quest.getTitle())
                .description(quest.getDescription())
                .instruction(instruction)
                .requirements(quest.getRequirements())
                .gameName(quest.getGameName())
                .category(gameCatalogService.isFlat(quest.getGameName()) ? null : quest.getCategory())
                .platform(quest.getPlatform())
                .durationDays(quest.getDurationDays())
                .rewardXp(quest.getRewardXp())
                .rewardCoins(quest.getRewardCoins())
                .ticketReward(quest.getTicketReward())
                .councilOnly(quest.isCouncilOnly())
                .externalAutoApprove(quest.isExternalAutoApprove())
                .oneTimePerAccount(quest.isOneTimePerAccount())
                .repeatableNoCooldownEligible(quest.isRepeatableNoCooldownEligible())
                .brawlAutoVerify(quest.getBrawlVerifyType() != null || quest.getClashVerifyType() != null || quest.getClashRoyaleVerifyType() != null || quest.getDotaVerifyType() != null || quest.getCs2VerifyType() != null || quest.getPubgVerifyType() != null)
                .sponsored(quest.isSponsored())
                .channelCheck(quest.getChannelCheckChatId() != null)
                .seatsLeft(seatsLeft(quest))
                .seatsTotal(seatsTotal(quest))
                .campaignClosed(quest.getChannelCheckChatId() != null && questService.isChannelCampaignClosed(quest));

        if (telegramId != null) {
            appUserRepository.findByTelegramId(telegramId).ifPresent(user -> {
                QuestService.WeeklyLimitStatus weekly = weeklyStatus(new HashMap<>(), user, quest);
                builder.rewardCoins(displayRewardCoins(user, quest, weekly));
                builder.rewardDiminished(weekly.reached());
                builder.weeklyLimit(weekly.limit());
                builder.weeklyCompleted(weekly.completed());
                if (userService.isEgcPassActive(user)) {
                    QuestService.RewardPreview egcPreview = questService.computeReward(user, quest);
                    long egcBonus = egcPreview.egcPassBonusCoins();
                    builder.egcPassBonusCoins(egcBonus);
                    builder.egcPassBonusXp(egcPreview.egcPassXpBonus());
                    builder.egcPassBoostExhausted(egcBonus <= 0 && userService.egcPassBoostRemainingThisMonth(user) <= 0);
                }
                QuestSubmission latest = questService.getLatestSubmission(user, quest);
                if (latest != null && latest.getStatus() != ru.gamebot.platform.domain.enums.SubmissionStatus.CANCELLED) {
                    builder.submissionStatus(latest.getStatus().name());
                    builder.moderatorComment(latest.getModeratorComment());
                    if (latest.getExpiresAt() != null) {
                        builder.expiresAt(latest.getExpiresAt().format(ISO_FMT));
                    }
                    AutoVerifyProgress progress = computeAutoVerifyProgress(quest, latest);
                    builder.autoVerifyProgress(progress.progress());
                    builder.autoVerifyTarget(progress.target());
                    if (quest.getChannelCheckChatId() != null && quest.getChannelCheckUrl() != null
                            && latest.getStatus() == ru.gamebot.platform.domain.enums.SubmissionStatus.DRAFT
                            && !questService.isExpired(latest) && !questService.isChannelCampaignClosed(quest)) {
                        builder.channelUrl(quest.getChannelCheckUrl());
                    }
                }
            });
        }

        return ResponseEntity.ok(builder.build());
    }

    @PostMapping("/{id}/take")
    public ResponseEntity<QuestActionResponseDto> takeQuest(@PathVariable Long id, @AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        // Та же точечная проверка подписки, что и в боте (см. GamePlatformBot.isActivelySubscribedFresh) —
        // без неё мини-апп был бы обходным путём мимо этого правила, раз общая блокировка снята. Именно
        // "Fresh"-вариант, без часового кэша: разовый флаг isRegistrationCompleted() остаётся true
        // навсегда, а кэшированная проверка позволила бы весь час брать квесты после отписки.
        // Первый квест можно взять без подписки (2026-10-10, см. GamePlatformBot.isFirstQuestFree) - подписку просим со второго.
        if (!gamePlatformBot.mayTakeQuestNow(user)) {
            return ResponseEntity.ok(QuestActionResponseDto.builder()
                    .success(false)
                    .status(QuestActionStatus.NEEDS_CHANNEL_SUBSCRIPTION.name())
                    .message(user.getCompletedQuests() > 0
                            ? "Первая награда твоя! Чтобы брать следующие квесты, подпишись на канал в боте."
                            : "Чтобы взять этот квест и начать зарабатывать EXC, сначала подпишись на канал в боте.")
                    .build());
        }
        Quest quest;
        try {
            quest = questService.getQuest(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        // Спонсор платит за НОВЫХ подписчиков: кто уже в канале, квест взять не может (та же проверка, что в боте).
        // Если проверить не вышло (нет доступа к каналу) - не блокируем, решит проверка при сдаче.
        if (quest.getChannelCheckChatId() != null
                && Boolean.TRUE.equals(gamePlatformBot.checkQuestChannelMembership(quest, telegramId, false))) {
            return ResponseEntity.ok(QuestActionResponseDto.builder()
                    .success(false)
                    .status("ALREADY_SUBSCRIBED")
                    .message("ℹ️ Ты уже подписан на этот канал: награда за этот квест только для новых подписчиков. Загляни в другие квесты.")
                    .build());
        }

        // Кубки Clash Royale: API отдаёт не больше 14 000, игроку у потолка квест на прирост не выдаём (та же проверка, что в боте)
        String trophyCapProblem = clashRoyaleQuestVerificationService.trophyCapProblem(user, quest);
        if (trophyCapProblem != null) {
            return ResponseEntity.ok(QuestActionResponseDto.builder()
                    .success(false)
                    .status("CR_TROPHY_CAP")
                    .message(trophyCapProblem)
                    .build());
        }

        QuestService.QuestActionResult result = questService.takeQuestChecked(user, quest);
        if (result.status() == QuestActionStatus.OK && quest.getBrawlVerifyType() != null) {
            brawlQuestVerificationService.primeBaseline(result.submission().getId(), quest.getBrawlVerifyType(), user.getBrawlStarsTag());
        }
        QuestActionResponseDto response = toResponse(result);
        if (result.status() == QuestActionStatus.SLOTS_FULL && !userService.isEgcPassActive(user)) {
            response.setMessage("Достигнут лимит активных квестов. Завершите текущий, купите доп. слот или оформите EGC Pass: с ним 3 слота.");
        }
        if (result.status() == QuestActionStatus.OK && quest.getGameName() != null && !quest.getGameName().isBlank()
                && !quest.isSponsored() && !"UGC".equalsIgnoreCase(quest.getGameName())) {
            // Подсказка с названием игры: после «Взять» игрок должен сразу пойти в игру
            response.setMessage("🚀 Квест активен! Теперь заходите в игру «" + quest.getGameName() + "» и выполняйте задание.");
        }
        return ResponseEntity.ok(response);
    }

    /** Игроки, у которых сейчас идёт проверка подписки (двойной тап/одновременно бот и мини-апп): вторая проверка не нужна. */
    private final java.util.Set<Long> channelCheckInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** «✅ Я подписался» для спонсорской подписки на канал - тот же порядок проверок и тот же approveSubmission, что в боте
     *  (GamePlatformBot.handleChannelCheck): взят ли квест, не закрыта ли кампания, не истёк ли срок, подписан ли игрок. */
    @PostMapping("/{id}/channel-check")
    public ResponseEntity<QuestActionResponseDto> channelCheck(@PathVariable Long id, @AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Quest quest;
        try {
            quest = questService.getQuest(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        if (quest.getChannelCheckChatId() == null) {
            return ResponseEntity.badRequest().body(channelCheckReply(false, "NOT_CHANNEL_QUEST", "Этот квест не про подписку на канал."));
        }
        if (!channelCheckInFlight.add(telegramId)) {
            return ResponseEntity.ok(channelCheckReply(false, "IN_PROGRESS", "Проверяем подписку, подожди секунду."));
        }
        try {
            QuestSubmission latest = questService.getLatestSubmission(user, quest);
            if (latest == null || latest.getStatus() == ru.gamebot.platform.domain.enums.SubmissionStatus.CANCELLED) {
                return ResponseEntity.ok(channelCheckReply(false, "NOT_TAKEN", "Сначала возьми квест кнопкой «Взять»."));
            }
            if (latest.getStatus() == ru.gamebot.platform.domain.enums.SubmissionStatus.APPROVED) {
                return ResponseEntity.ok(channelCheckReply(false, "ALREADY_APPROVED", "Этот квест уже выполнен и оплачен."));
            }
            if (latest.getStatus() != ru.gamebot.platform.domain.enums.SubmissionStatus.DRAFT) {
                return ResponseEntity.ok(channelCheckReply(false, "NOT_SUBMITTABLE", "Квест сейчас нельзя сдать. Загляни в «Мои квесты»."));
            }
            if (questService.isChannelCampaignClosed(quest)) {
                return ResponseEntity.ok(channelCheckReply(false, "CAMPAIGN_CLOSED",
                        "Квест закрыт: спонсор оплатил ограниченное число подписчиков, и все места уже заняты."));
            }
            if (questService.isExpired(latest)) {
                return ResponseEntity.ok(channelCheckReply(false, "EXPIRED", "Срок квеста истёк. Возьми его заново, если он ещё доступен."));
            }
            Boolean member = gamePlatformBot.checkQuestChannelMembership(quest, telegramId, true);
            if (member == null) {
                return ResponseEntity.ok(channelCheckReply(false, "CHECK_FAILED", "Не удалось проверить подписку, попробуй чуть позже."));
            }
            if (!member) {
                return ResponseEntity.ok(channelCheckReply(false, "NOT_SUBSCRIBED",
                        "Подписки пока не видно. Открой канал, подпишись и снова нажми «Я подписался»."));
            }
            QuestSubmission done = questService.approveSubmission(latest.getId());
            String holdNote = done.getHeldCoins() != null && done.getHeldReleaseAt() != null
                    ? " Ещё +" + done.getHeldCoins() + " EXC придёт " + done.getHeldReleaseAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                            + ", если останешься в канале."
                    : "";
            return ResponseEntity.ok(channelCheckReply(true, "OK",
                    "✅ Подписка подтверждена! +" + done.getAwardedCoins() + " EXC, +" + done.getAwardedXp() + " XP." + holdNote));
        } catch (Exception e) {
            log.error("Channel check approval failed for quest {} user {}", id, telegramId, e);
            return ResponseEntity.ok(channelCheckReply(false, "ERROR", "Не получилось, попробуй чуть позже."));
        } finally {
            channelCheckInFlight.remove(telegramId);
        }
    }

    private QuestActionResponseDto channelCheckReply(boolean success, String status, String message) {
        return QuestActionResponseDto.builder().success(success).status(status).message(message).build();
    }

    @PostMapping("/{id}/report")
    public ResponseEntity<QuestActionResponseDto> submitReport(
            @PathVariable Long id,
            @AuthenticationPrincipal Long telegramId,
            @RequestParam(required = false) MultipartFile photo,
            @RequestParam(required = false) String externalLink,
            @RequestParam(required = false) String comment) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Quest quest;
        try {
            quest = questService.getQuest(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        if ((photo == null || photo.isEmpty()) && (externalLink == null || externalLink.isBlank())) {
            return ResponseEntity.badRequest().body(QuestActionResponseDto.builder()
                    .success(false)
                    .status("NO_EVIDENCE")
                    .message("Прикрепите скриншот или ссылку на подтверждение.")
                    .build());
        }

        String mediaType = null;
        String fileId = null;
        String photoUniqueIds = null;
        if (photo != null && !photo.isEmpty()) {
            String contentType = photo.getContentType() != null ? photo.getContentType() : "";
            boolean isVideo = contentType.startsWith("video/");
            try {
                String filename = photo.getOriginalFilename() != null ? photo.getOriginalFilename() : (isVideo ? "report.mp4" : "report.jpg");
                if (isVideo) {
                    fileId = telegramFileService.uploadVideo(photo.getBytes(), filename, telegramId);
                    mediaType = "video";
                } else {
                    String[] uploaded = telegramFileService.uploadPhotoFull(photo.getBytes(), filename, telegramId);
                    fileId = uploaded[0];
                    photoUniqueIds = uploaded[1];
                    mediaType = "photo";
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Failed to upload report media for user {}", telegramId, e);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(QuestActionResponseDto.builder()
                        .success(false)
                        .status("UPLOAD_FAILED")
                        .message("Не удалось загрузить файл. Попробуйте ещё раз.")
                        .build());
            }
        }

        QuestService.QuestActionResult result = questService.submitReportChecked(
                user, quest, mediaType, fileId, photoUniqueIds, externalLink, comment);
        return ResponseEntity.ok(toResponse(result));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<MyQuestDto>> myQuests(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Map<String, Boolean> flatByGame = new HashMap<>();
        List<MyQuestDto> result = questService.getUserSubmissions(user).stream()
                .map(s -> {
                    AutoVerifyProgress progress = computeAutoVerifyProgress(s.getQuest(), s);
                    return MyQuestDto.builder()
                        .submissionId(s.getId())
                        .questId(s.getQuest().getId())
                        .title(s.getQuest().getTitle())
                        .gameName(s.getQuest().getGameName())
                        .category(flatByGame.computeIfAbsent(s.getQuest().getGameName(), gameCatalogService::isFlat) ? null : s.getQuest().getCategory())
                        .externalAutoApprove(s.getQuest().isExternalAutoApprove())
                        .brawlAutoVerify(s.getQuest().getBrawlVerifyType() != null || s.getQuest().getClashVerifyType() != null || s.getQuest().getClashRoyaleVerifyType() != null || s.getQuest().getDotaVerifyType() != null || s.getQuest().getCs2VerifyType() != null || s.getQuest().getPubgVerifyType() != null)
                        .autoVerifyProgress(progress.progress())
                        .autoVerifyTarget(progress.target())
                        .status(s.getStatus().name())
                        .updatedAt(s.getUpdatedAt() != null ? s.getUpdatedAt().format(ISO_FMT) : null)
                        .expiresAt(s.getExpiresAt() != null ? s.getExpiresAt().format(ISO_FMT) : null)
                        .moderatorComment(s.getModeratorComment())
                        .rewardXp(s.getQuest().getRewardXp())
                        .rewardCoins(historyRewardCoins(user, s.getQuest()))
                        .build();
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/mine/{submissionId}/cancel")
    public ResponseEntity<QuestActionResponseDto> cancelMyQuest(
            @PathVariable Long submissionId, @AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            questService.cancelSubmission(submissionId, user);
            return ResponseEntity.ok(QuestActionResponseDto.builder()
                    .success(true)
                    .status("CANCELLED")
                    .message("Квест отменён.")
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(QuestActionResponseDto.builder()
                    .success(false)
                    .status("ERROR")
                    .message(e.getMessage())
                    .build());
        } catch (Exception e) {
            // Раньше любая другая ошибка уходила как безымянные 500 без записи в лог: игрок видел «ничего не происходит» (разбор 27.10.2026, заявка 8014)
            org.slf4j.LoggerFactory.getLogger(QuestController.class).error("Cancel of submission {} failed for telegramId {}", submissionId, telegramId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(QuestActionResponseDto.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Не удалось отменить квест. Попробуйте ещё раз или напишите в поддержку.")
                    .build());
        }
    }

    private QuestActionResponseDto toResponse(QuestService.QuestActionResult result) {
        boolean success = result.status() == QuestActionStatus.OK;
        return QuestActionResponseDto.builder()
                .success(success)
                .status(result.status().name())
                .minutesLeft(result.minutesLeft())
                .message(messageFor(result))
                .build();
    }

    private String messageFor(QuestService.QuestActionResult result) {
        return switch (result.status()) {
            case OK -> "🚀 Квест активен! Теперь заходите в игру и выполняйте задание.";
            case ALREADY_DRAFT -> "Этот квест уже взят в работу.";
            case ALREADY_PENDING -> "Отчёт уже на проверке — дождитесь решения модератора.";
            case ALREADY_APPROVED -> "Этот квест уже одобрен и оплачен.";
            case HAS_REJECTED_REPORT -> "Отчёт по этому квесту был отклонён — исправьте и отправьте заново через «Отчёт», брать квест повторно не нужно.";
            case NOT_TAKEN -> "Сначала возьмите квест.";
            case SLOTS_FULL -> "Достигнут лимит активных квестов. Завершите текущий или купите доп. слот.";
            case SAME_QUEST_COOLDOWN -> "Этот квест можно выполнять не чаще 1 раза в " + DurationFormatter.format(result.minutesLeft()) + ".";
            case GAME_COOLDOWN -> "Кулдаун по этой игре: ещё " + DurationFormatter.formatExact(result.minutesLeft()) + ".";
            // Без фиксированного "час" — порог для новичка короче (15 мин), не всегда час.
            case TAKE_COOLDOWN -> "Новый квест можно будет взять чуть позже. Осталось " + result.minutesLeft() + " мин.";
            case REJECT_COOLDOWN -> "После отклонения повторный отчёт можно отправить через " + result.minutesLeft() + " мин.";
            case HAS_PENDING_REPORT -> "У вас уже есть отчёт на проверке у модератора — дождитесь решения, прежде чем отправлять следующий.";
            case EXPIRED -> "Срок выполнения этого квеста истёк.";
            case QUEST_INACTIVE -> "Этот квест больше недоступен.";
            case NEEDS_BRAWL_TAG -> "🏷️ Для этого квеста нужно сначала привязать тег Brawl Stars — откройте бота и возьмите этот квест там, бот попросит ввести тег.";
            case NEEDS_CLASH_TAG -> "🏷️ Для этого квеста нужно сначала привязать тег Clash of Clans — откройте бота и возьмите этот квест там, бот попросит ввести тег.";
            case NEEDS_CLASH_ROYALE_TAG -> "🏷️ Для этого квеста нужно сначала привязать тег Clash Royale — откройте бота и возьмите этот квест там, бот попросит ввести тег.";
            case NEEDS_DOTA_LINK -> "🏷️ Для этого квеста нужно сначала привязать аккаунт Dota 2 — откройте бота и возьмите этот квест там, бот попросит ввести account_id.";
            case NEEDS_CS2_LINK -> "🏷️ Для этого квеста нужно сначала привязать аккаунт Steam (CS2) — откройте бота и возьмите этот квест там, бот попросит ввести SteamID64.";
            case NEEDS_PUBG_LINK -> "🏷️ Для этого квеста нужно сначала привязать аккаунт PUBG PC — откройте бота и возьмите этот квест там, бот попросит ввести игровой ник.";
            case AUTO_VERIFIED_NO_REPORT -> "ℹ️ Этот квест подтверждается автоматически — отправлять отчёт не нужно и нельзя.";
            case NEEDS_BRAWL_PARTNER -> "🤝 Для этого квеста нужно выбрать партнёра (реферала или отрядника) — откройте бота и возьмите этот квест там.";
            case NEEDS_CHANNEL_SUBSCRIPTION -> "📢 Чтобы брать квесты, нужно подписаться на канал — откройте бота, там будет кнопка проверки подписки.";
            case EARLY_ACCESS_LOCKED -> "🔒 Ранний доступ EGC Pass: этот квест нового набора ещё около " + (result.minutesLeft() / 60)
                    + " ч доступен только подписчикам. С EGC Pass можно взять его сразу.";
            case PARTICIPANT_LIMIT_REACHED -> "🔒 Квест закрыт — набор участников завершён. Попробуйте другой квест.";
        };
    }
}
