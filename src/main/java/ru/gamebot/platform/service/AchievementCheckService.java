package ru.gamebot.platform.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.AchievementType;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.event.AchievementEvent;

/** Раз в 10 минут (см. WeeklyResetScheduler) сравнивает XP-уровень и баланс EXC каждого пользователя
 *  с последним замеченным значением — рост считается достижением (RANK_UP / EXC_MILESTONE).
 *
 *  Coins/XP начисляются россыпью в десятке разных мест (UserService/QuestService/RewardService и т.д.),
 *  единой точки для хука "только что пересёк порог" нет — поэтому поллер по образцу авто-верификации
 *  квестов (Brawl/Clash/ClashRoyale), а не рефакторинг всех точек начисления. Ничего в существующей
 *  логике наград не меняет — чистая надстройка. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AchievementCheckService {

    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${achievement.exc-milestones}")
    private String milestonesRaw;

    private List<Long> sortedMilestones() {
        List<Long> milestones = new ArrayList<>();
        for (String part : milestonesRaw.split(",")) {
            if (!part.isBlank()) milestones.add(Long.parseLong(part.trim()));
        }
        milestones.sort(Comparator.naturalOrder());
        return milestones;
    }

    @Transactional
    public void checkMilestones() {
        List<Long> milestones = sortedMilestones();
        for (AppUser user : appUserRepository.findAll()) {
            if (!user.isRegistrationCompleted()) continue;
            checkLevel(user);
            checkExcMilestone(user, milestones);
        }
    }

    private void checkLevel(AppUser user) {
        int currentLevel = userService.getLevelNumber(user.getXp());
        Integer lastNotified = user.getLastNotifiedLevelNumber();
        if (lastNotified == null) {
            // Первый замер — фиксируем текущий уровень без уведомления (тот же паттерн, что
            // brawlBaselineTrophies: нельзя считать достижением состояние на момент включения фичи).
            user.setLastNotifiedLevelNumber(currentLevel);
            appUserRepository.save(user);
            return;
        }
        if (currentLevel > lastNotified) {
            user.setLastNotifiedLevelNumber(currentLevel);
            appUserRepository.save(user);
            eventPublisher.publishEvent(new AchievementEvent(this, user.getTelegramId(),
                    AchievementType.RANK_UP, userService.getLevelName(user.getXp())));
        }
    }

    private void checkExcMilestone(AppUser user, List<Long> milestones) {
        long coins = user.getCoins();
        Long highestReached = null;
        for (Long m : milestones) {
            if (coins >= m) highestReached = m; else break;
        }
        if (highestReached == null) return;

        Long lastNotified = user.getLastNotifiedExcMilestone();
        if (lastNotified == null) {
            // Первый замер — как и с уровнем, фиксируем без уведомления.
            user.setLastNotifiedExcMilestone(highestReached);
            appUserRepository.save(user);
            return;
        }
        if (highestReached > lastNotified) {
            user.setLastNotifiedExcMilestone(highestReached);
            appUserRepository.save(user);
            eventPublisher.publishEvent(new AchievementEvent(this, user.getTelegramId(),
                    AchievementType.EXC_MILESTONE, String.valueOf(highestReached)));
        }
    }
}
