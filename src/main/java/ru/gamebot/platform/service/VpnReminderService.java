package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.VpnAccount;
import ru.gamebot.platform.domain.repository.VpnAccountRepository;
import ru.gamebot.platform.event.PlayerPushEvent;

/** Напоминания о сроке EGC VPN: раз в час, за 2 дня до конца и после окончания (не позже чем через 3 дня). Это уведомления о купленной услуге,
 *  поэтому они идут мимо лимита «не больше 2 рассылок в сутки», но не чаще двух сообщений на один срок (reminderStage хранится в аккаунте).
 *  Бэклог: до запуска аккаунтов нет, а окно «истёк не позже 3 дней назад» не даёт слать про давно закончившееся. */
@Slf4j
@Service
@RequiredArgsConstructor
public class VpnReminderService {

    private static final int SOON_DAYS = 2;
    private static final int EXPIRED_WINDOW_DAYS = 3;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final VpnService vpnService;
    private final VpnAccountRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(initialDelay = 5 * 60_000L, fixedDelay = 60 * 60_000L)
    public void remind() {
        if (!vpnService.isEnabled()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<VpnAccount> soon = repository.findByStatusAndReminderStageLessThanAndExpiresAtBetween(
                    VpnAccount.STATUS_ACTIVE, 1, now, now.plusDays(SOON_DAYS));
            for (VpnAccount a : soon) {
                send(a, 1, "🔐 <b>EGC VPN скоро закончится</b>\nДействует до <b>" + a.getExpiresAt().format(FMT) + "</b>. "
                        + (VpnAccount.PLAN_TRIAL.equals(a.getPlanType()) ? "Понравилось? Оформите месяц, чтобы не потерять доступ." : "Продлите заранее, чтобы не остаться без VPN."),
                        "💳 Продлить");
            }
            List<VpnAccount> expired = repository.findByStatusAndReminderStageLessThanAndExpiresAtBetween(
                    VpnAccount.STATUS_ACTIVE, 2, now.minusDays(EXPIRED_WINDOW_DAYS), now);
            for (VpnAccount a : expired) {
                send(a, 2, VpnAccount.PLAN_TRIAL.equals(a.getPlanType())
                        ? "🔐 <b>Пробный период EGC VPN закончился</b>\nХотите продолжить? Месяц стоит 150 ₽, а за EXC дешевле."
                        : "🔐 <b>Срок EGC VPN истёк</b>\nПродлите, и ключ снова заработает.",
                        "💳 Оформить");
            }
        } catch (Exception e) {
            log.warn("VPN reminders failed", e);
        }
    }

    private void send(VpnAccount a, int stage, String html, String button) {
        try {
            a.setReminderStage(stage);
            repository.save(a); // сначала отметка: при сбое отправки лучше пропустить напоминание, чем слать его каждый час
            eventPublisher.publishEvent(new PlayerPushEvent(this, a.getTelegramId(), html, button, "menu:vpn", null));
        } catch (Exception e) {
            log.warn("VPN reminder failed for {}", a.getTelegramId(), e);
        }
    }
}
