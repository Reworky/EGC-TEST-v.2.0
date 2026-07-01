package ru.gamebot.platform.api.controller;

import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.QuestSubmissionDto;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.QuestService;

@RestController
@RequestMapping("/api/profile/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final AppUserRepository appUserRepository;
    private final QuestService questService;

    @GetMapping
    public ResponseEntity<?> submissions(@AuthenticationPrincipal Long telegramId) {
        return appUserRepository.findByTelegramId(telegramId)
                .filter(u -> u.isRegistrationCompleted())
                .map(user -> {
                    List<QuestSubmissionDto> list = questService.getUserSubmissions(user).stream()
                            .map(s -> QuestSubmissionDto.builder()
                                    .id(s.getId())
                                    .questTitle(s.getQuest().getTitle())
                                    .gameName(s.getQuest().getGameName())
                                    .category(s.getQuest().getCategory())
                                    .status(s.getStatus().name())
                                    .rewardXp(s.getQuest().getRewardXp())
                                    .rewardCoins(s.getQuest().getRewardCoins())
                                    .moderatorComment(s.getModeratorComment())
                                    .createdAt(s.getCreatedAt() != null ? s.getCreatedAt().format(FMT) : null)
                                    .updatedAt(s.getUpdatedAt() != null ? s.getUpdatedAt().format(FMT) : null)
                                    .build())
                            .toList();
                    return ResponseEntity.ok(list);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
