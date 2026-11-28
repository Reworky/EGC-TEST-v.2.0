package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.NotificationLog;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.NotificationLogRepository;

/**
 * Антиспам для напоминаний о предложениях (спонсорские квесты, VPN, предложения после наград), 28.11.2026, владелец: «чтобы не было частого спама».
 * Поверх общего лимита (NotificationGateService): не чаще раза в 3 суток и не больше 2 за 7 дней на игрока (считаются и прежние цепочки о спонсорских квестах),
 * только если за 20 часов не было никакого напоминания; после трёх подряд без возвращения - пауза 30 дней; игрок может отключить кнопкой.
 */
@Service
@RequiredArgsConstructor
public class PromoGateService {

    public static final int GAP_HOURS = 72;
    public static final int MAX_PER_WEEK = 2;
    public static final int IGNORED_PAUSE_DAYS = 30;
    private static final String OFF_KEY = "promo.off.";

    private static final List<String> OWN = List.of(NudgeType.PROMO_BANK.name(), NudgeType.PROMO_GAME.name(), NudgeType.PROMO_OFFER.name(),
            NudgeType.PROMO_VIEWED.name(), NudgeType.PROMO_VPN.name());
    private static final List<String> WITH_OTHER_CHAINS = List.of(NudgeType.PROMO_BANK.name(), NudgeType.PROMO_GAME.name(), NudgeType.PROMO_OFFER.name(),
            NudgeType.PROMO_VIEWED.name(), NudgeType.PROMO_VPN.name(), NudgeType.SPONSOR_QUEST.name(), NudgeType.OTP_MISSION.name());

    private final NotificationGateService gate;
    private final NotificationLogRepository logRepository;
    private final AppSettingRepository appSettingRepository;

    /** Можно ли сейчас слать игроку напоминание о предложении (ничего не записывает). */
    public boolean canSend(AppUser user) {
        if (user == null || user.isBlocked() || appSettingRepository.findById(OFF_KEY + user.getId()).isPresent()) return false;
        LocalDateTime now = LocalDateTime.now();
        if (user.getLastNudgeAt() != null && user.getLastNudgeAt().isAfter(now.minusHours(NotificationGateService.WINDOW_HOURS))) return false;
        if (logRepository.countByUserIdAndTypeInAndSentAtAfter(user.getId(), WITH_OTHER_CHAINS, now.minusHours(GAP_HOURS)) > 0) return false;
        if (logRepository.countByUserIdAndTypeInAndSentAtAfter(user.getId(), WITH_OTHER_CHAINS, now.minusDays(7)) >= MAX_PER_WEEK) return false;
        List<NotificationLog> last = logRepository.findTop3ByUserIdAndTypeInOrderBySentAtDesc(user.getId(), OWN);
        boolean ignored = last.size() == 3
                && last.stream().allMatch(n -> n.getReturnedAt() == null && n.getSentAt().isBefore(now.minusHours(NotificationGateService.RETURN_WINDOW_HOURS)));
        return !(ignored && last.get(0).getSentAt().isAfter(now.minusDays(IGNORED_PAUSE_DAYS)));
    }

    /** Занять слот перед отправкой (или сразу после неё): запись в общий журнал напоминаний. */
    public boolean record(AppUser user, NudgeType type) {
        return gate.tryAcquire(user, type);
    }

    public void optOut(Long userId) {
        AppSetting s = new AppSetting();
        s.setKey(OFF_KEY + userId);
        s.setValue(LocalDateTime.now().toString());
        appSettingRepository.save(s);
    }
}
