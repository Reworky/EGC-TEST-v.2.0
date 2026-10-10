package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.DailyTasksClaimDto;
import ru.gamebot.platform.api.dto.DailyTasksDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.UserService;

/**
 * «Задания дня» для мини-аппа — те же правила и тот же сервис, что в боте (GamePlatformBot.sendDailyTasks / handleDailyTasksClaim):
 * забрать ежедневный бонус, выполнить квест, открыть сундук дня; за все три - «Сундук заданий».
 * Отметки ставит сам бэкенд (UserService/QuestService), поэтому действия в мини-аппе засчитываются без доп. кода.
 */
@Slf4j
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/daily-tasks")
@RequiredArgsConstructor
public class DailyTasksController {

    private final AppUserRepository appUserRepository;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<DailyTasksDto> tasks(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!userService.dailyTasksEnabled()) {
            return ResponseEntity.ok(DailyTasksDto.builder().enabled(false).build());
        }
        int mask = userService.dailyTasksMask(user);
        boolean claimed = userService.isDailyTasksChestClaimed(user);
        return ResponseEntity.ok(DailyTasksDto.builder()
                .enabled(true)
                .bonusDone((mask & UserService.DAILY_TASK_BONUS) != 0)
                .questDone((mask & UserService.DAILY_TASK_QUEST) != 0)
                .chestDone((mask & UserService.DAILY_TASK_CHEST) != 0)
                .doneCount(Integer.bitCount(mask))
                .claimed(claimed)
                .ready(!claimed && mask == UserService.DAILY_TASKS_ALL)
                .firstQuest(user.getCompletedQuests() == 0)
                .rewardExc(UserService.DAILY_TASKS_CHEST_EXC)
                .rewardTickets(UserService.DAILY_TASKS_CHEST_TICKETS)
                .build());
    }

    @PostMapping("/claim")
    public ResponseEntity<DailyTasksClaimDto> claim(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        UserService.DailyTasksClaimResult result;
        try {
            result = userService.claimDailyTasksChest(user);
        } catch (Exception e) {
            log.error("Daily tasks chest claim failed for user {}", telegramId, e);
            return ResponseEntity.ok(DailyTasksClaimDto.builder()
                    .success(false).status("ERROR").message("Не получилось, попробуй чуть позже.").build());
        }
        String message = switch (result.status()) {
            case OK -> "🎁 Сундук заданий открыт! +" + result.exc() + " EXC и " + result.tickets() + " билет колеса.";
            case NOT_READY -> "Сначала выполни все три задания.";
            case ALREADY_CLAIMED -> "Сегодня сундук заданий уже получен. Новые задания — завтра.";
            case CAP_REACHED -> "Сундуки заданий на сегодня закончились: дневной лимит клуба исчерпан. Завтра будет новый.";
            case DISABLED -> "Задания дня сейчас недоступны.";
        };
        return ResponseEntity.ok(DailyTasksClaimDto.builder()
                .success(result.status() == UserService.DailyTasksClaimStatus.OK)
                .status(result.status().name())
                .exc(result.exc())
                .tickets(result.tickets())
                .message(message)
                .build());
    }
}
