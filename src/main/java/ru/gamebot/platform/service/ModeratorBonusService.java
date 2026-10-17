package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.ModeratorBonusLog;
import ru.gamebot.platform.domain.repository.ModeratorBonusLogRepository;

/** Начисление бонуса игроку модератором (2026-10-17). В отличие от админского «🎁 Бонус» (без ограничений) здесь есть лимиты на одно
 *  начисление и на сутки, обязательная причина, журнал и сообщение админам о каждом начислении. */
@Service
@RequiredArgsConstructor
public class ModeratorBonusService {

    public static final long MAX_XP_PER_GRANT = 200;
    public static final long MAX_EXC_PER_GRANT = 500;
    public static final long MAX_TICKETS_PER_GRANT = 5;
    public static final long MAX_XP_PER_DAY = 1_000;
    public static final long MAX_EXC_PER_DAY = 2_000;

    private final UserService userService;
    private final ModeratorBonusLogRepository logRepository;

    public record Result(String error, UserService.RewardGrant grant) {
        public boolean ok() { return error == null; }
    }

    @Transactional
    public Result grant(AppUser moderator, Long targetTelegramId, long xp, long exc, long tickets, String comment) {
        if (targetTelegramId == null || targetTelegramId.equals(moderator.getTelegramId())) {
            return new Result("Нельзя начислять бонус самому себе.", null);
        }
        Optional<AppUser> target = userService.findByTelegramId(targetTelegramId);
        if (target.isEmpty()) return new Result("Игрок не найден.", null);
        if (xp < 0 || exc < 0 || tickets < 0 || xp + exc + tickets == 0) return new Result("Укажите, что начислить: XP, EXC или билеты.", null);
        if (xp > MAX_XP_PER_GRANT || exc > MAX_EXC_PER_GRANT || tickets > MAX_TICKETS_PER_GRANT) {
            return new Result("За одно начисление не больше " + MAX_XP_PER_GRANT + " XP, " + MAX_EXC_PER_GRANT + " EXC и " + MAX_TICKETS_PER_GRANT + " билетов.", null);
        }
        String reason = comment == null ? "" : comment.trim();
        if (reason.length() < 3) return new Result("Укажите причину начисления (от 3 символов).", null);
        if (reason.length() > 200) reason = reason.substring(0, 200);

        LocalDateTime since = LocalDateTime.now().minusHours(24);
        long usedExc = logRepository.sumExcSince(moderator.getTelegramId(), since);
        long usedXp = logRepository.sumXpSince(moderator.getTelegramId(), since);
        if (usedExc + exc > MAX_EXC_PER_DAY || usedXp + xp > MAX_XP_PER_DAY) {
            return new Result("Суточный лимит исчерпан: за 24 часа не больше " + MAX_EXC_PER_DAY + " EXC и " + MAX_XP_PER_DAY
                    + " XP (уже выдано " + usedExc + " EXC и " + usedXp + " XP). Остальное может начислить админ.", null);
        }

        UserService.RewardGrant grant = userService.addReward(target.get(), xp, exc, tickets, ExcTransactionService.BONUS,
                "Бонус модератора " + moderator.getTelegramId() + ": " + reason);
        ModeratorBonusLog entry = new ModeratorBonusLog();
        entry.setModeratorTelegramId(moderator.getTelegramId());
        entry.setTargetTelegramId(targetTelegramId);
        entry.setXp(xp);
        entry.setExc(exc);
        entry.setTickets(tickets);
        entry.setComment(reason);
        logRepository.save(entry);
        return new Result(null, grant);
    }
}
