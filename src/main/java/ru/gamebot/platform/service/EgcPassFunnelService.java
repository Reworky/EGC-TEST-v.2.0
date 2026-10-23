package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.EgcPassEvent;
import ru.gamebot.platform.domain.repository.EgcPassEventRepository;
import ru.gamebot.platform.domain.repository.StarsPurchaseRepository;

/** Воронка продаж EGC Pass: записывает показы предложения и открытия экрана, считает покупки и продления (по журналу Stars) для вкладки аналитики «EGC Pass». */
@Slf4j
@Service
@RequiredArgsConstructor
public class EgcPassFunnelService {

    private final EgcPassEventRepository eventRepository;
    private final StarsPurchaseRepository purchaseRepository;
    private final jakarta.persistence.EntityManager entityManager;

    /** Сбой записи статистики не должен ломать основное действие. */
    public void record(Long telegramId, String type, String source) {
        try {
            EgcPassEvent e = new EgcPassEvent();
            e.setTelegramId(telegramId);
            e.setType(type);
            e.setSource(source);
            eventRepository.save(e);
        } catch (Exception ex) {
            log.warn("[EgcPassFunnel] failed to record {} for {}", type, telegramId, ex);
        }
    }

    public void offerSent(Long telegramId, String source) { record(telegramId, EgcPassEvent.OFFER_SENT, source); }

    public void screenOpened(Long telegramId) { record(telegramId, EgcPassEvent.SCREEN_OPENED, null); }

    public record Funnel(long offers, long offerUsers, long opens, long openUsers, long firstPurchases, long trialPurchases, long renewals,
                         List<Object[]> bySource) { }

    /** Покупки по журналу stars_purchases: item_type starsitem:EGC_PASS (первая подписка или продление) и starsitem:EGC_PASS_TRIAL (пробный месяц). */
    public Funnel funnel(LocalDateTime from, LocalDateTime to) {
        long offers = eventRepository.countByType(EgcPassEvent.OFFER_SENT, from, to);
        long offerUsers = eventRepository.countUsersByType(EgcPassEvent.OFFER_SENT, from, to);
        long opens = eventRepository.countByType(EgcPassEvent.SCREEN_OPENED, from, to);
        long openUsers = eventRepository.countUsersByType(EgcPassEvent.SCREEN_OPENED, from, to);
        long trial = num("SELECT COUNT(*) FROM stars_purchases WHERE item_type = 'starsitem:EGC_PASS_TRIAL' AND created_at >= ?1 AND created_at < ?2", from, to);
        long paidAll = num("SELECT COUNT(*) FROM stars_purchases WHERE item_type = 'starsitem:EGC_PASS' AND created_at >= ?1 AND created_at < ?2", from, to);
        // Продление = платная покупка того, у кого уже была более ранняя покупка Pass (платная или пробная)
        long renewals = num("SELECT COUNT(*) FROM stars_purchases p WHERE p.item_type = 'starsitem:EGC_PASS' AND p.created_at >= ?1 AND p.created_at < ?2 "
                + "AND EXISTS (SELECT 1 FROM stars_purchases q WHERE q.telegram_id = p.telegram_id AND q.item_type IN ('starsitem:EGC_PASS', 'starsitem:EGC_PASS_TRIAL') AND q.created_at < p.created_at)", from, to);
        return new Funnel(offers, offerUsers, opens, openUsers, Math.max(0, paidAll - renewals), trial, renewals, eventRepository.offersBySource(from, to));
    }

    private long num(String sql, Object... params) {
        jakarta.persistence.Query q = entityManager.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
        Object r = q.getSingleResult();
        return r == null ? 0 : ((Number) r).longValue();
    }
}
