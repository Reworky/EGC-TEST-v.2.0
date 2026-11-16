package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.FunnelEvent;
import ru.gamebot.platform.domain.repository.FunnelEventRepository;

/** Воронка новичка (17.11.2026, просьба владельца: видеть, где отваливается игрок от /start до заказа карты).
 *
 *  Шаги двух видов. События (таблица funnel_events, одна строка на игрока и шаг, пишутся ботом через {@link #log}): показан слайд, экран выбора пути, выбор «карты/игры»,
 *  раздел спонсорских квестов, карточка банка, запрос подписки, «Взять», экран «Оформить карту», выбор игры, стартовая карточка, главное меню.
 *  Считаемые по базе (без событий, поэтому работают и для старых игроков): подписался на канал, взял квест банка, отправил отчёт, одобрено, банк выплатил,
 *  взял и выполнил игровой квест, ввёл код дня, вернулся на следующий день.
 *  Когорта = игроки, зарегистрированные в выбранном периоде; шаг считается, если игрок дошёл до него когда-либо (поэтому свежие когорты «дозреют»).
 *  События пишутся только с момента выкладки: период обрезается по первому событию, чтобы шаги были сопоставимы.
 *  Не видно: нажатие «Оформить карту» (кнопка-ссылка уходит мимо бота) - дальнейшие шаги банка видны только по возврату игрока с отчётом. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewbieFunnelService {

    /** Идентификаторы шагов-событий (не менять: по ним читается история). */
    public static final String W1 = "welcome_1", W2 = "welcome_2", W3 = "welcome_3", W_SKIP = "welcome_skip", W_DONE = "welcome_done",
            CHOICE_SHOWN = "choice_shown", CHOICE_CARDS = "choice_cards", CHOICE_GAMES = "choice_games",
            MENU = "menu_shown", SP_SECTION = "sp_section", SP_DEBIT = "sp_debit", BANK_CARD = "bank_card", SUB_PROMPT = "sub_prompt",
            BANK_TAKE = "bank_take18", APPLY_SHOWN = "apply_shown",
            GAMES_PICKER = "games_picker", GAME_PICK = "game_pick", STARTER_CARD = "starter_card";

    private static final int CACHE_LIMIT = 300_000;

    private final FunnelEventRepository eventRepository;
    private final jakarta.persistence.EntityManager entityManager;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /** Записать, что игрок первый раз дошёл до шага. Не должно мешать боту: любые ошибки глотаются. */
    public void log(Long telegramId, String step) {
        if (telegramId == null || step == null) return;
        try {
            String key = telegramId + ":" + step;
            if (seen.contains(key)) return;
            if (seen.size() > CACHE_LIMIT) seen.clear();
            seen.add(key);
            if (eventRepository.existsByTelegramIdAndStep(telegramId, step)) return;
            FunnelEvent e = new FunnelEvent();
            e.setTelegramId(telegramId);
            e.setStep(step);
            eventRepository.save(e);
        } catch (Exception ex) {
            log.debug("[NewbieFunnel] failed to record {} for {}: {}", step, telegramId, ex.getMessage());
        }
    }

    /** Когда появилось первое событие (с него воронка сопоставима); null - событий ещё нет. */
    public LocalDateTime eventsSince() {
        try {
            Object r = entityManager.createNativeQuery("SELECT MIN(created_at) FROM funnel_events").getSingleResult();
            return r == null ? null : (r instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) r);
        } catch (Exception e) {
            return null;
        }
    }

    private String ev(String step) {
        return "EXISTS(SELECT 1 FROM funnel_events e WHERE e.telegram_id = u.telegram_id AND e.step = '" + step + "')";
    }

    private static String bankNames() {
        StringBuilder sb = new StringBuilder();
        for (PartnerMission pm : PartnerMissions.ALL) {
            if (sb.length() > 0) sb.append(",");
            sb.append("'").append(pm.gameName().replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    private String bankSub(String cond) {
        return "EXISTS(SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id WHERE s.user_id = u.id AND q.game_name IN (" + bankNames() + ")" + cond + ")";
    }

    private String gameSub(String cond) {
        return "EXISTS(SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id WHERE s.user_id = u.id AND q.game_name NOT IN (" + bankNames()
                + ") AND q.sponsored = FALSE" + cond + ")";
    }

    /** Ключ метрики -> логическое выражение по игроку u. Порядок - порядок вывода. */
    private Map<String, String> metrics() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("started", "TRUE");
        m.put("w1", ev(W1));
        m.put("w2", ev(W2));
        m.put("w3", ev(W3));
        m.put("wskip", ev(W_SKIP));
        m.put("choice", ev(CHOICE_SHOWN));
        m.put("cards", ev(CHOICE_CARDS));
        m.put("games", ev(CHOICE_GAMES));
        m.put("menu", ev(MENU));
        m.put("sp_section", ev(SP_SECTION));
        m.put("sp_debit", ev(SP_DEBIT));
        m.put("bank_card", ev(BANK_CARD));
        m.put("sub_prompt", ev(SUB_PROMPT));
        m.put("sub_after", ev(SUB_PROMPT) + " AND u.registration_completed = TRUE");
        m.put("take18", ev(BANK_TAKE));
        m.put("bank_taken", bankSub(""));
        m.put("apply", ev(APPLY_SHOWN));
        m.put("bank_report", bankSub(" AND s.status IN ('PENDING','APPROVED','REJECTED','NEEDS_INFO')"));
        m.put("bank_ok", bankSub(" AND s.status = 'APPROVED'"));
        m.put("bank_paid", bankSub(" AND s.status = 'APPROVED' AND s.bonus_paid_at IS NOT NULL"));
        m.put("picker", ev(GAMES_PICKER));
        m.put("pick", ev(GAME_PICK));
        m.put("starter", ev(STARTER_CARD));
        m.put("game_taken", gameSub(""));
        m.put("game_ok", gameSub(" AND s.status = 'APPROVED'"));
        m.put("activated", "u.registration_completed = TRUE");
        m.put("code", "EXISTS(SELECT 1 FROM daily_code_claims c WHERE c.user_id = u.id)");
        // Возврат на следующий день: только для «созревших» (зарегистрированы не позже ?3); суточные границы - по времени сервера
        m.put("d1_base", "u.created_at <= ?3");
        m.put("d1", "u.created_at <= ?3 AND EXISTS(SELECT 1 FROM user_activity_days a WHERE a.user_id = u.id AND a.activity_day = DATEADD('DAY', 1, CAST(u.created_at AS DATE)))");
        return m;
    }

    public record Report(LocalDateTime from, LocalDateTime to, boolean clamped, boolean hasEvents, Map<String, Long> counts, List<Object[]> bySource) { }

    /** from/to - окно регистрации когорты. Если событий ещё нет, считаются только шаги по базе (hasEvents = false). */
    public Report report(LocalDateTime from, LocalDateTime to) {
        LocalDateTime since = eventsSince();
        boolean clamped = false;
        LocalDateTime f = from;
        if (since != null && from.isBefore(since)) {
            f = since;
            clamped = true;
        }
        LocalDateTime mature = LocalDateTime.now().minusDays(2);
        Map<String, String> m = metrics();
        StringBuilder sel = new StringBuilder("SELECT ");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) sel.append(", ");
            first = false;
            sel.append("COALESCE(SUM(CASE WHEN ").append(e.getValue()).append(" THEN 1 ELSE 0 END), 0)");
        }
        sel.append(" FROM app_users u WHERE u.created_at >= ?1 AND u.created_at < ?2");
        jakarta.persistence.Query q = entityManager.createNativeQuery(sel.toString());
        q.setParameter(1, f);
        q.setParameter(2, to);
        q.setParameter(3, mature);
        Object[] row = (Object[]) q.getSingleResult();
        Map<String, Long> counts = new LinkedHashMap<>();
        int i = 0;
        for (String k : m.keySet()) {
            Object v = row[i++];
            counts.put(k, v == null ? 0L : ((Number) v).longValue());
        }
        return new Report(f, to, clamped, since != null, counts, bySource(f, to, mature, m));
    }

    /** По источникам: [источник, пришли, выбрали карты, взяли квест банка, банк выплатил, выбрали игры, взяли игровой квест]. */
    @SuppressWarnings("unchecked")
    private List<Object[]> bySource(LocalDateTime f, LocalDateTime to, LocalDateTime mature, Map<String, String> m) {
        try {
            String sql = "SELECT COALESCE(NULLIF(u.traffic_source_code, ''), 'органика') AS grp, COUNT(*), "
                    + "COALESCE(SUM(CASE WHEN " + m.get("cards") + " THEN 1 ELSE 0 END), 0), "
                    + "COALESCE(SUM(CASE WHEN " + m.get("bank_taken") + " THEN 1 ELSE 0 END), 0), "
                    + "COALESCE(SUM(CASE WHEN " + m.get("bank_paid") + " THEN 1 ELSE 0 END), 0), "
                    + "COALESCE(SUM(CASE WHEN " + m.get("games") + " THEN 1 ELSE 0 END), 0), "
                    + "COALESCE(SUM(CASE WHEN " + m.get("game_taken") + " THEN 1 ELSE 0 END), 0) "
                    + "FROM app_users u WHERE u.created_at >= ?1 AND u.created_at < ?2 "
                    + "GROUP BY COALESCE(NULLIF(u.traffic_source_code, ''), 'органика') ORDER BY COUNT(*) DESC LIMIT 8";
            jakarta.persistence.Query q = entityManager.createNativeQuery(sql);
            q.setParameter(1, f);
            q.setParameter(2, to);
            return q.getResultList();
        } catch (Exception e) {
            log.warn("[NewbieFunnel] by-source query failed", e);
            return List.of();
        }
    }
}
