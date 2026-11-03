package ru.gamebot.platform.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.VpnAccount;
import ru.gamebot.platform.domain.repository.VpnAccountRepository;
import ru.gamebot.platform.event.AnalyticsAlertEvent;
import ru.gamebot.platform.service.VpnPanelClient.ClientSpec;
import ru.gamebot.platform.service.VpnPanelClient.PanelException;

/** EGC VPN: один клиент в панели 3x-ui на игрока (метка egc_&lt;telegramId&gt;). Срок и лимит трафика исполняет сама панель.
 *  Всё выключено, пока VPN_ENABLED не true и не заданы адрес/логин/пароль панели. Платёжный код (activatePaid) при сбое панели
 *  не теряет деньги молча: аккаунт остаётся в PENDING, админам уходит алерт, повторный вызов безопасен. */
@Slf4j
@Service
public class VpnService {

    /** code: DISABLED (флаг выключен), TRIAL_USED (пробный уже брали), PANEL (панель не ответила). */
    public static class VpnException extends Exception {
        private final String code;

        public VpnException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() { return code; }
    }

    private static final long GB = 1024L * 1024 * 1024;
    private static final int TRIAL_LIMIT_IP = 1;
    private static final int PAID_LIMIT_IP = 2;
    private static final String PANEL_COMMENT = "EGC";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VpnPanelClient panel;
    private final VpnAccountRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final boolean enabled;
    private final String subBase;
    private final int trialDays;
    private final int trialGb;
    private final int paidDays;
    private final int paidGb;
    private final String clientApp;

    public VpnService(VpnPanelClient panel, VpnAccountRepository repository, ApplicationEventPublisher eventPublisher,
                      @Value("${app.vpn-enabled:false}") boolean enabled,
                      @Value("${app.vpn-sub-base:}") String subBase,
                      @Value("${app.vpn-trial-days:3}") int trialDays,
                      @Value("${app.vpn-trial-gb:5}") int trialGb,
                      @Value("${app.vpn-paid-days:30}") int paidDays,
                      @Value("${app.vpn-paid-gb:0}") int paidGb,
                      @Value("${app.vpn-client-app:hiddify}") String clientApp) {
        this.panel = panel;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.enabled = enabled;
        this.subBase = subBase == null ? "" : subBase.trim().replaceAll("/+$", "");
        this.trialDays = trialDays;
        this.trialGb = trialGb;
        this.paidDays = paidDays;
        this.paidGb = paidGb;
        this.clientApp = clientApp == null ? "hiddify" : clientApp.trim().toLowerCase();
        if (!enabled) {
            log.warn("VpnService disabled: VPN_ENABLED is not true");
        } else if (!panel.isConfigured() || this.subBase.isBlank()) {
            log.warn("VpnService disabled: VPN_XUI_URL/VPN_XUI_USER/VPN_XUI_PASS/VPN_SUB_BASE not set");
        }
    }

    public boolean isEnabled() {
        return enabled && panel.isConfigured() && !subBase.isBlank();
    }

    public Optional<VpnAccount> find(Long telegramId) {
        return repository.findByTelegramId(telegramId);
    }

    public int getTrialDays() { return trialDays; }

    public int getTrialGb() { return trialGb; }

    public int getPaidDays() { return paidDays; }

    /** Какие приложения показываем игроку: «v2box» (V2Box / v2rayTun / v2rayN, для VLESS Reality) или «hiddify» (по умолчанию, для Hysteria2 на сервере Влада). */
    public boolean isV2boxClientApp() { return "v2box".equals(clientApp); }

    /** Ключ работает сейчас: выдан и срок не истёк. */
    public boolean isRunning(VpnAccount account) {
        return VpnAccount.STATUS_ACTIVE.equals(account.getStatus())
                && account.getExpiresAt() != null && account.getExpiresAt().isAfter(LocalDateTime.now());
    }

    public String subscriptionUrl(VpnAccount account) {
        return subBase + "/" + account.getSubId();
    }

    /** Пробный период: один раз на Telegram-аккаунт. Если прошлая попытка оборвалась на полпути (аккаунт PENDING), довыдаёт тот же клиент. */
    public synchronized VpnAccount startTrial(Long telegramId) throws VpnException {
        requireEnabled();
        VpnAccount account = repository.findByTelegramId(telegramId).orElse(null);
        if (account != null && (!VpnAccount.STATUS_PENDING.equals(account.getStatus())
                || VpnAccount.PLAN_PAID.equals(account.getPlanType()))) {
            throw new VpnException("TRIAL_USED", "пробный период уже использован");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean created = account == null;
        if (created) {
            account = newAccount(telegramId, VpnAccount.PLAN_TRIAL, now);
            account.setTrialUsedAt(now);
        }
        account.setExpiresAt(now.plusDays(trialDays));
        account.setReminderStage(0);
        account.setUpdatedAt(now);
        account = repository.save(account);
        try {
            panel.upsertClient(spec(account, trialGb, TRIAL_LIMIT_IP, true));
        } catch (PanelException e) {
            if (created) {
                repository.delete(account); // пробный не сгорает, если панель не выдала ключ
            }
            alert("Не удалось выдать пробный VPN игроку " + telegramId, e);
            throw new VpnException("PANEL", "панель VPN недоступна");
        }
        account.setStatus(VpnAccount.STATUS_ACTIVE);
        return repository.save(account);
    }

    /** Оплаченный месяц: продлевает от конца текущего срока (если ещё идёт) или от «сейчас». paymentRef - id платежа, повтор с тем же id ничего не делает.
     *  При сбое панели аккаунт остаётся PENDING (оплата не теряется), админам уходит алерт. */
    public synchronized VpnAccount activatePaid(Long telegramId, String paymentRef) throws VpnException {
        requireEnabled();
        LocalDateTime now = LocalDateTime.now();
        VpnAccount account = repository.findByTelegramId(telegramId).orElse(null);
        if (account != null && paymentRef != null && paymentRef.equals(account.getLastPaymentRef())
                && VpnAccount.STATUS_ACTIVE.equals(account.getStatus())) {
            return account;
        }
        if (account == null) {
            account = newAccount(telegramId, VpnAccount.PLAN_PAID, now);
        }
        boolean running = VpnAccount.STATUS_ACTIVE.equals(account.getStatus())
                && account.getExpiresAt() != null && account.getExpiresAt().isAfter(now);
        LocalDateTime base = running ? account.getExpiresAt() : now;
        account.setPlanType(VpnAccount.PLAN_PAID);
        account.setExpiresAt(base.plusDays(paidDays));
        account.setReminderStage(0);
        account.setLastPaymentRef(paymentRef);
        account.setUpdatedAt(now);
        account = repository.save(account);
        try {
            panel.upsertClient(spec(account, paidGb, PAID_LIMIT_IP, true));
        } catch (PanelException e) {
            account.setStatus(VpnAccount.STATUS_PENDING);
            repository.save(account);
            alert("ОПЛАЧЕНО, но VPN не выдан игроку " + telegramId + " (платёж " + paymentRef + "). Повторить: /vpn " + telegramId + " paid", e);
            throw new VpnException("PANEL", "панель VPN недоступна");
        }
        account.setStatus(VpnAccount.STATUS_ACTIVE);
        return repository.save(account);
    }

    /** Отключение: клиент удаляется из панели, запись остаётся (признак «пробный брали» сохраняется). */
    public synchronized void disable(Long telegramId) throws VpnException {
        requireEnabled();
        Optional<VpnAccount> found = repository.findByTelegramId(telegramId);
        if (found.isEmpty()) {
            return;
        }
        VpnAccount account = found.get();
        try {
            panel.deleteClient(account.getPanelEmail());
        } catch (PanelException e) {
            alert("Не удалось отключить VPN игрока " + telegramId, e);
            throw new VpnException("PANEL", "панель VPN недоступна");
        }
        account.setStatus(VpnAccount.STATUS_DISABLED);
        account.setUpdatedAt(LocalDateTime.now());
        repository.save(account);
    }

    private void requireEnabled() throws VpnException {
        if (!isEnabled()) {
            throw new VpnException("DISABLED", "VPN выключен");
        }
    }

    private VpnAccount newAccount(Long telegramId, String plan, LocalDateTime now) {
        VpnAccount a = new VpnAccount();
        a.setTelegramId(telegramId);
        a.setPanelEmail("egc_" + telegramId);
        a.setSubId(randomToken(8));
        a.setClientUuid(UUID.randomUUID().toString());
        a.setClientAuth(randomToken(16));
        a.setPlanType(plan);
        a.setStatus(VpnAccount.STATUS_PENDING);
        a.setCreatedAt(now);
        return a;
    }

    private ClientSpec spec(VpnAccount a, int gb, int limitIp, boolean enable) {
        long expiryMs = a.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new ClientSpec(a.getPanelEmail(), a.getSubId(), a.getClientUuid(), a.getClientAuth(), randomToken(8),
                gb * GB, expiryMs, limitIp, enable, PANEL_COMMENT);
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Алерт админам личным сообщением; в текст не попадают пароли и тела ответов панели. */
    private void alert(String what, PanelException e) {
        log.warn("VPN: {}: {}", what, e.getMessage());
        try {
            eventPublisher.publishEvent(new AnalyticsAlertEvent(this,
                    "🔐 <b>VPN</b>: " + escape(what) + "\n<code>" + escape(e.getMessage()) + "</code>"));
        } catch (Exception ex) {
            log.warn("VPN: failed to publish admin alert", ex);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
