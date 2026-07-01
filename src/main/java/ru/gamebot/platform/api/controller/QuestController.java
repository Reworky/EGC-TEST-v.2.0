package ru.gamebot.platform.api.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.QuestDto;
import ru.gamebot.platform.service.QuestService;

@RestController
@RequestMapping("/api/quests")
@RequiredArgsConstructor
public class QuestController {

    private final QuestService questService;

    @GetMapping
    public List<QuestDto> quests(
            @RequestParam(required = false) String game,
            @RequestParam(required = false) String category) {
        var quests = (game != null && !game.isBlank())
                ? (category != null ? questService.findActiveByGameNameAndCategory(game, category)
                                    : questService.findActiveByGameName(game))
                : (category != null ? questService.findByCategory(category)
                                    : questService.findActiveQuests());

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
                .build()).toList();
    }

    @GetMapping("/games")
    public List<String> games() {
        return questService.findActiveGameNames();
    }
}
