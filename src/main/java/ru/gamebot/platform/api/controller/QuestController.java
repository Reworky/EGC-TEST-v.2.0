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
import ru.gamebot.platform.api.dto.QuestActionResponseDto;
import ru.gamebot.platform.api.dto.QuestDetailDto;
import ru.gamebot.platform.api.dto.QuestDto;
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

    @GetMapping
    public List<QuestDto> quests(
            @RequestParam(required = false) String game,
            @RequestParam(required = false) String category,
            @AuthenticationPrincipal Long telegramId) {
        var quests = (game != null && !game.isBlank())
                ? (category != null ? questService.findActiveByGameNameAndCategory(game, category)
                                    : questService.findActiveByGameName(game))
                : (category != null ? questService.findByCategory(category)
                                    : questService.findActiveQuests());

        Map<Long, String> statusByQuestId = new HashMap<>();
        if (telegramId != null) {
            appUserRepository.findByTelegramId(telegramId).ifPresent(user -> {
                for (QuestSubmission s : questService.getUserSubmissions(user)) {
                    statusByQuestId.putIfAbsent(s.getQuest().getId(), s.getStatus().name());
                }
            });
        }

        return quests.stream().map(q -> QuestDto.builder()
                .id(q.getId())
                .title(q.getTitle())
                .description(q.getDescription())
                .gameName(q.getGameName())
                .category(q.getCategory())
                .platform(q.getPlatform())
                .durationDays(q.getDurationDays())
                .rewardXp(q.getRewardXp())
                .rewardCoins(q.getRewardCoins())
                .councilOnly(q.isCouncilOnly())
                .sponsored(q.isSponsored())
                .submissionStatus(statusByQuestId.get(q.getId()))
                .build()).toList();
    }

    @GetMapping("/games")
    public List<String> games() {
        return questService.findActiveGameNames();
    }

    @GetMapping("/sponsored")
    public List<QuestDto> sponsored(@AuthenticationPrincipal Long telegramId) {
        var quests = questService.findActiveSponsored();
        Map<Long, String> statusByQuestId = new java.util.HashMap<>();
        if (telegramId != null) {
            appUserRepository.findByTelegramId(telegramId).ifPresent(user -> {
                for (var q : quests) {
                    java.util.Optional.ofNullable(questService.getLatestSubmission(user, q))
                            .ifPresent(s -> statusByQuestId.put(q.getId(), s.getStatus().name()));
                }
            });
        }
        return quests.stream().map(q -> QuestDto.builder()
                .id(q.getId()).title(q.getTitle()).description(q.getDescription())
                .gameName(q.getGameName()).category(q.getCategory()).platform(q.getPlatform())
                .durationDays(q.getDurationDays()).rewardXp(q.getRewardXp()).rewardCoins(q.getRewardCoins())
                .councilOnly(q.isCouncilOnly()).sponsored(true)
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

        QuestDetailDto.QuestDetailDtoBuilder builder = QuestDetailDto.builder()
                .id(quest.getId())
                .title(quest.getTitle())
                .description(quest.getDescription())
                .instruction(quest.getInstruction())
                .requirements(quest.getRequirements())
                .gameName(quest.getGameName())
                .category(quest.getCategory())
                .platform(quest.getPlatform())
                .durationDays(quest.getDurationDays())
                .rewardXp(quest.getRewardXp())
                .rewardCoins(quest.getRewardCoins())
                .councilOnly(quest.isCouncilOnly());

        if (telegramId != null) {
            appUserRepository.findByTelegramId(telegramId).ifPresent(user -> {
                QuestSubmission latest = questService.getLatestSubmission(user, quest);
                if (latest != null && latest.getStatus() != ru.gamebot.platform.domain.enums.SubmissionStatus.CANCELLED) {
                    builder.submissionStatus(latest.getStatus().name());
                    builder.moderatorComment(latest.getModeratorComment());
                    if (latest.getExpiresAt() != null) {
                        builder.expiresAt(latest.getExpiresAt().format(ISO_FMT));
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
        if (photo != null && !photo.isEmpty()) {
            String contentType = photo.getContentType() != null ? photo.getContentType() : "";
            boolean isVideo = contentType.startsWith("video/");
            try {
                String filename = photo.getOriginalFilename() != null ? photo.getOriginalFilename() : (isVideo ? "report.mp4" : "report.jpg");
                if (isVideo) {
                    fileId = telegramFileService.uploadVideo(photo.getBytes(), filename, telegramId);
                    mediaType = "video";
                } else {
                    fileId = telegramFileService.uploadPhoto(photo.getBytes(), filename, telegramId);
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
                user, quest, mediaType, fileId, externalLink, comment);
        return ResponseEntity.ok(toResponse(result));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<MyQuestDto>> myQuests(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        List<MyQuestDto> result = questService.getUserSubmissions(user).stream()
                .map(s -> MyQuestDto.builder()
                        .submissionId(s.getId())
                        .questId(s.getQuest().getId())
                        .title(s.getQuest().getTitle())
                        .gameName(s.getQuest().getGameName())
                        .category(s.getQuest().getCategory())
                        .status(s.getStatus().name())
                        .updatedAt(s.getUpdatedAt() != null ? s.getUpdatedAt().format(ISO_FMT) : null)
                        .expiresAt(s.getExpiresAt() != null ? s.getExpiresAt().format(ISO_FMT) : null)
                        .moderatorComment(s.getModeratorComment())
                        .rewardXp(s.getQuest().getRewardXp())
                        .rewardCoins(s.getQuest().getRewardCoins())
                        .build())
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
        long hours = (result.minutesLeft() + 59) / 60;
        return switch (result.status()) {
            case OK -> "Готово.";
            case ALREADY_DRAFT -> "Этот квест уже взят в работу.";
            case ALREADY_PENDING -> "Отчёт уже на проверке — дождитесь решения модератора.";
            case ALREADY_APPROVED -> "Этот квест уже одобрен и оплачен.";
            case HAS_REJECTED_REPORT -> "Отчёт по этому квесту был отклонён — исправьте и отправьте заново через «Отчёт», брать квест повторно не нужно.";
            case NOT_TAKEN -> "Сначала возьмите квест.";
            case SLOTS_FULL -> "Достигнут лимит активных квестов. Завершите текущий или купите доп. слот.";
            case SAME_QUEST_COOLDOWN -> "Этот квест можно выполнять не чаще 1 раза в 24 часа.";
            case GAME_COOLDOWN -> "Кулдаун по этой игре: ещё " + hours + " ч.";
            case TAKE_COOLDOWN -> "Между взятием квестов должен пройти час. Осталось " + result.minutesLeft() + " мин.";
            case REJECT_COOLDOWN -> "После отклонения повторный отчёт можно отправить через " + result.minutesLeft() + " мин.";
            case HAS_PENDING_REPORT -> "У вас уже есть отчёт на проверке у модератора — дождитесь решения, прежде чем отправлять следующий.";
            case EXPIRED -> "Срок выполнения этого квеста истёк.";
        };
    }
}
