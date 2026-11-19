package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.PartnerQuestEvent;
import ru.gamebot.platform.domain.repository.PartnerQuestEventRepository;

/** Воронка партнёрского квеста банка (квест, который открывает партнёрские привилегии, сейчас ОТП): открыли карточку, взяли, нажали «Начать», первая часть проверена, банк выплатил.
 *  Показы и нажатия пишутся событиями; взятия, проверка и выплата считаются по заявкам, взятым в периоде (когорта периода). Сбой записи статистики не ломает основное действие. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerQuestFunnelService {

    private final PartnerQuestEventRepository eventRepository;
    private final jakarta.persistence.EntityManager entityManager;

    public void record(Long telegramId, String type) {
        try {
            PartnerQuestEvent e = new PartnerQuestEvent();
            e.setTelegramId(telegramId);
            e.setType(type);
            eventRepository.save(e);
        } catch (Exception ex) {
            log.warn("[PartnerQuestFunnel] failed to record {} for {}", type, telegramId, ex);
        }
    }

    public void cardViewed(Long telegramId) { record(telegramId, PartnerQuestEvent.CARD_VIEWED); }

    public void startClicked(Long telegramId) { record(telegramId, PartnerQuestEvent.START_CLICKED); }

    /** Открытие карточки спонсорского квеста (19.11.2026): для вкладки аналитики «Спонсорские квесты». */
    public void sponsorViewed(Long telegramId, Long questId) {
        try {
            PartnerQuestEvent e = new PartnerQuestEvent();
            e.setTelegramId(telegramId);
            e.setType(PartnerQuestEvent.SPONSOR_VIEWED);
            e.setQuestId(questId);
            eventRepository.save(e);
        } catch (Exception ex) {
            log.warn("[PartnerQuestFunnel] failed to record sponsor view {} for {}", questId, telegramId, ex);
        }
    }

    /** questId -> уникальных игроков, открывших карточку за период. */
    public java.util.Map<Long, Long> viewersByQuest(LocalDateTime from, LocalDateTime to) {
        java.util.Map<Long, Long> out = new java.util.HashMap<>();
        for (Object[] r : eventRepository.countViewersByQuest(from, to)) out.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        return out;
    }

    /** Уникальных игроков, открывших карточку любого спонсорского квеста за период. */
    public long sponsorViewUsers(LocalDateTime from, LocalDateTime to) {
        return eventRepository.countUsersByType(PartnerQuestEvent.SPONSOR_VIEWED, from, to);
    }

    /** Уникальных игроков, взявших любой спонсорский квест за период (заявки, созданные в периоде). */
    public long sponsorTakenUsers(LocalDateTime from, LocalDateTime to) {
        return num("SELECT COUNT(DISTINCT s.user_id) FROM quest_submissions s JOIN quests q ON q.id = s.quest_id "
                + "WHERE (q.sponsored = TRUE OR q.external_auto_approve = TRUE) AND s.created_at >= ?1 AND s.created_at < ?2", from, to);
    }

    /** questId -> {взяли, одобрено, банк выплатил}: игроки по заявкам, созданным в периоде. */
    @SuppressWarnings("unchecked")
    public java.util.Map<Long, long[]> submissionStatsByQuest(LocalDateTime from, LocalDateTime to) {
        jakarta.persistence.Query q = entityManager.createNativeQuery(
                "SELECT s.quest_id, COUNT(DISTINCT s.user_id), COUNT(DISTINCT CASE WHEN s.status = 'APPROVED' THEN s.user_id END), "
                        + "COUNT(DISTINCT CASE WHEN s.status = 'APPROVED' AND s.bonus_paid_at IS NOT NULL THEN s.user_id END) "
                        + "FROM quest_submissions s JOIN quests q ON q.id = s.quest_id "
                        + "WHERE (q.sponsored = TRUE OR q.external_auto_approve = TRUE) AND s.created_at >= ?1 AND s.created_at < ?2 GROUP BY s.quest_id");
        q.setParameter(1, from);
        q.setParameter(2, to);
        java.util.Map<Long, long[]> out = new java.util.HashMap<>();
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            out.put(((Number) r[0]).longValue(), new long[]{((Number) r[1]).longValue(), ((Number) r[2]).longValue(), ((Number) r[3]).longValue()});
        }
        return out;
    }

    /** bySource, byCountry: [название, взяли, проверено, выплата банка] (игроков). */
    public record Funnel(long viewUsers, long taken, long clickUsers, long verified, long bonusPaid, long cancelled, long rejected, long inWork,
                         List<Object[]> bySource, List<Object[]> byCountry) { }

    public Funnel funnel(String gameName, LocalDateTime from, LocalDateTime to) {
        long views = eventRepository.countUsersByType(PartnerQuestEvent.CARD_VIEWED, from, to);
        long clicks = eventRepository.countUsersByType(PartnerQuestEvent.START_CLICKED, from, to);
        String base = "FROM quest_submissions s JOIN quests q ON q.id = s.quest_id WHERE q.game_name = ?1 AND s.created_at >= ?2 AND s.created_at < ?3";
        long taken = num("SELECT COUNT(DISTINCT s.user_id) " + base, gameName, from, to);
        long verified = num("SELECT COUNT(DISTINCT s.user_id) " + base + " AND s.status = 'APPROVED'", gameName, from, to);
        long bonus = num("SELECT COUNT(DISTINCT s.user_id) " + base + " AND s.status = 'APPROVED' AND s.bonus_paid_at IS NOT NULL", gameName, from, to);
        long cancelled = num("SELECT COUNT(DISTINCT s.user_id) " + base + " AND s.status = 'CANCELLED'", gameName, from, to);
        long rejected = num("SELECT COUNT(DISTINCT s.user_id) " + base + " AND s.status = 'REJECTED'", gameName, from, to);
        long inWork = num("SELECT COUNT(DISTINCT s.user_id) " + base + " AND s.status IN ('DRAFT', 'PENDING', 'NEEDS_INFO')", gameName, from, to);
        return new Funnel(views, taken, clicks, verified, bonus, cancelled, rejected, inWork,
                breakdown("COALESCE(NULLIF(u.traffic_source_code, ''), 'органика')", gameName, from, to),
                breakdown("COALESCE(NULLIF(u.country, ''), 'не указана')", gameName, from, to));
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> breakdown(String groupExpr, String gameName, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT t.grp, COUNT(DISTINCT t.user_id), COUNT(DISTINCT CASE WHEN t.status = 'APPROVED' THEN t.user_id END), "
                + "COUNT(DISTINCT CASE WHEN t.status = 'APPROVED' AND t.bonus_paid_at IS NOT NULL THEN t.user_id END) FROM ("
                + "SELECT " + groupExpr + " AS grp, s.user_id AS user_id, s.status AS status, s.bonus_paid_at AS bonus_paid_at "
                + "FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id "
                + "WHERE q.game_name = ?1 AND s.created_at >= ?2 AND s.created_at < ?3) t GROUP BY t.grp ORDER BY COUNT(DISTINCT t.user_id) DESC LIMIT 10";
        jakarta.persistence.Query q = entityManager.createNativeQuery(sql);
        q.setParameter(1, gameName);
        q.setParameter(2, from);
        q.setParameter(3, to);
        return q.getResultList();
    }

    private long num(String sql, Object... params) {
        jakarta.persistence.Query q = entityManager.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
        Object r = q.getSingleResult();
        return r == null ? 0 : ((Number) r).longValue();
    }
}
