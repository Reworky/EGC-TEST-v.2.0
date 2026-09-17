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
import ru.gamebot.platform.service.QuestActionStatus;
import ru.gamebot.platform.service.QuestService;
import ru.gamebot.platform.service.TelegramFileService;

@Slf4j
@RestController
@RequestMapping("/api/quests")
@RequiredArgsConstructor
public class QuestController {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final QuestService questService;
    private final AppUserRepository appUserRepository;
    private final TelegramFileService telegramFileService;
    private final GamePlatformBot gamePlatformBot;
    private final ru.gamebot.platform.service.QuestRewardBoostService questRewardBoostService;
    private final ru.gamebot.platform.service.GameCatalogService gameCatalogService;

    /** Награда для показа игроку ДО взятия/сдачи квеста — та же логика, что и displayRewardCoins в боте.
     *  Для обычных квестов статичная quest.getRewardCoins() (как раньше); для repeatableNoCooldownEligible
     *  (пилот "квесты без стен") — реально посчитанная кривой убывания сумма за следующее прохождение,
     *  иначе игрок в мини-аппе видел бы фиксированную цифру, которая после пары прохождений за окно
     *  уже не совпадает с тем, что реально начислится. user == null (гость) — просто базовая цена. */
    private long displayRewardCoins(AppUser user, Quest quest) {
        return user != null && quest.isRepeatableNoCooldownEligible()
                ? questService.computeReward(user, quest).coins()
                : quest.getRewardCoins();
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
            boolean measured = quest.getBrawlVerifyType() != ru.gamebot.platform.domain.enums.BrawlVerifyType.TROPHIES
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
        return quests.stream().map(q -> QuestDto.builder()
                .id(q.getId())
                .title(q.getTitle())
                .description(q.getDescription())
                .gameName(q.getGameName())
                .category(flatByGame.computeIfAbsent(q.getGameName(), gameCatalogService::isFlat) ? null : q.getCategory())
                .platform(q.getPlatform())
                .durationDays(q.getDurationDays())
                .rewardXp(q.getRewardXp())
                .rewardCoins(displayRewardCoins(currentUser, q))
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
        java.util.Optional<String> fileId = gameCatalogService.getPhotoFileId(name);
        if (fileId.isEmpty()) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        try {
            byte[] image = telegramFileService.downloadFile(fileId.get());
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
                        .rewardCoins(displayRewardCoins(user, q))
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
                .endsAt(activeBoost.map(b -> b.getEndAt().toString()).orElse(null))
                .build();
    }

    @GetMapping("/sponsored")
    public List<QuestDto> sponsored(@AuthenticationPrincipal Long telegramId) {
        var quests = questService.findActiveSponsored();
        Map<Long, String> statusByQuestId = new java.util.HashMap<>();
        AppUser currentUser = telegramId != null ? appUserRepository.findByTelegramId(telegramId).orElse(null) : null;
        if (currentUser != null) {
            for (var q : quests) {
                java.util.Optional.ofNullable(questService.getLatestSubmission(currentUser, q))
                        .ifPresent(s -> statusByQuestId.put(q.getId(), s.getStatus().name()));
            }
        }
        Map<String, Boolean> flatByGame = new HashMap<>();
        return quests.stream().map(q -> QuestDto.builder()
                .id(q.getId()).title(q.getTitle()).description(q.getDescription())
                .gameName(q.getGameName())
                .category(flatByGame.computeIfAbsent(q.getGameName(), gameCatalogService::isFlat) ? null : q.getCategory())
                .platform(q.getPlatform())
                .durationDays(q.getDurationDays()).rewardXp(q.getRewardXp()).rewardCoins(displayRewardCoins(currentUser, q))
                .ticketReward(q.getTicketReward())
                .councilOnly(q.isCouncilOnly()).sponsored(true)
                .externalAutoApprove(q.isExternalAutoApprove())
                .brawlAutoVerify(q.getBrawlVerifyType() != null || q.getClashVerifyType() != null || q.getClashRoyaleVerifyType() != null || q.getDotaVerifyType() != null || q.getCs2VerifyType() != null || q.getPubgVerifyType() != null)
                .highlightNew(q.isEffectivelyNew())
                .submissionStatus(statusByQuestId.get(q.getId()))
                .build()).toList();
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
                .repeatableNoCooldownEligible(quest.isRepeatableNoCooldownEligible())
                .brawlAutoVerify(quest.getBrawlVerifyType() != null || quest.getClashVerifyType() != null || quest.getClashRoyaleVerifyType() != null || quest.getDotaVerifyType() != null || quest.getCs2VerifyType() != null || quest.getPubgVerifyType() != null);

        if (telegramId != null) {
            appUserRepository.findByTelegramId(telegramId).ifPresent(user -> {
                builder.rewardCoins(displayRewardCoins(user, quest));
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
        if (!gamePlatformBot.isActivelySubscribedFresh(user)) {
            return ResponseEntity.ok(QuestActionResponseDto.builder()
                    .success(false)
                    .status(QuestActionStatus.NEEDS_CHANNEL_SUBSCRIPTION.name())
                    .message("Чтобы взять этот квест и начать зарабатывать EXC, сначала подпишись на канал в боте.")
                    .build());
        }
        Quest quest;
        try {
            quest = questService.getQuest(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        QuestService.QuestActionResult result = questService.takeQuestChecked(user, quest);
        return ResponseEntity.ok(toResponse(result));
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
                        .rewardCoins(displayRewardCoins(user, s.getQuest()))
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
            case OK -> "Готово.";
            case ALREADY_DRAFT -> "Этот квест уже взят в работу.";
            case ALREADY_PENDING -> "Отчёт уже на проверке — дождитесь решения модератора.";
            case ALREADY_APPROVED -> "Этот квест уже одобрен и оплачен.";
            case HAS_REJECTED_REPORT -> "Отчёт по этому квесту был отклонён — исправьте и отправьте заново через «Отчёт», брать квест повторно не нужно.";
            case NOT_TAKEN -> "Сначала возьмите квест.";
            case SLOTS_FULL -> "Достигнут лимит активных квестов. Завершите текущий или купите доп. слот.";
            case SAME_QUEST_COOLDOWN -> "Этот квест можно выполнять не чаще 1 раза в " + DurationFormatter.format(result.minutesLeft()) + ".";
            case GAME_COOLDOWN -> "Кулдаун по этой игре: ещё " + DurationFormatter.format(result.minutesLeft()) + ".";
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
            case PARTICIPANT_LIMIT_REACHED -> "🔒 Квест закрыт — набор участников завершён. Попробуйте другой квест.";
        };
    }
}
