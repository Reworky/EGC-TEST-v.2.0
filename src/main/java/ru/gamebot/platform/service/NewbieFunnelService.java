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
 *  Кнопка «Оформить карту» уходит мимо бота, поэтому с 23.11.2026 о шагах на витрине (открыла, раскрыла карточку, нажала на оффер) сообщает сама витрина по номеру заявки
 *  ({@code /api/postback/landing}); о заявке в банке - постбек LeadGid. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewbieFunnelService {

    /** Идентификаторы шагов-событий (не менять: по ним читается история). */
    public static final String W1 = "welcome_1", W2 = "welcome_2", W3 = "welcome_3", W_SKIP = "welcome_skip", W_DONE = "welcome_done",
            CHOICE_SHOWN = "choice_shown", CHOICE_CARDS = "choice_cards", CHOICE_GAMES = "choice_games",
            MENU = "menu_shown", SP_SECTION = "sp_section", SP_DEBIT = "sp_debit", BANK_CARD = "bank_card", SUB_PROMPT = "sub_prompt",
            BANK_TAKE = "bank_take18", APPLY_SHOWN = "apply_shown",
            GAMES_PICKER = "games_picker", GAME_PICK = "game_pick", STARTER_CARD = "starter_card",
            // 21.11.2026 (просьба владельца: видеть каждый шаг): список квестов игры и карточка игрового квеста (старая «стартовая карточка» после смены сценария не показывается),
            // куда ещё нажимали в разделе спонсорских квестов и просмотр карточки любого спонсорского квеста
            GAME_LIST = "game_list", GAME_CARD = "game_card", SP_OTHER = "sp_other", SP_PICKS = "sp_picks", SP_MYQ = "sp_myq", SP_ANY_CARD = "sp_any_card",
            // 23.11.2026: нажатие «Взять» ДО подтверждения «мне 18+» и сигналы витрины банков (открыла страницу, раскрыла карточку, нажала на оффер) - приходят с витрины по номеру заявки (?sid=)
            BANK_TAKE_TAP = "bank_take_tap", LP_VIEW = "lp_view", LP_CARD = "lp_card", LP_CLICK = "lp_click";

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

    /** С какого момента пишутся шаги, добавленные 21.11.2026 (список квестов игры, карточки, разделы спонсорских); null - ещё не писались. */
    public LocalDateTime newStepsSince() {
        try {
            Object r = entityManager.createNativeQuery("SELECT MIN(created_at) FROM funnel_events WHERE step IN ('" + GAME_LIST + "','" + GAME_CARD + "','"
                    + SP_OTHER + "','" + SP_PICKS + "','" + SP_MYQ + "','" + SP_ANY_CARD + "')").getSingleResult();
            return r == null ? null : (r instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) r);
        } catch (Exception e) {
            return null;
        }
    }

    /** С какого момента пишется шаг «Взять» до подтверждения 18+ (23.11.2026); null - ещё не писался. */
    public LocalDateTime tapStepsSince() {
        return firstEventSince("'" + BANK_TAKE_TAP + "'");
    }

    /** С какого момента приходят сигналы витрины банков (открыла страницу, раскрыла карточку, нажала на оффер); null - ещё не приходили. */
    public LocalDateTime lpStepsSince() {
        return firstEventSince("'" + LP_VIEW + "','" + LP_CARD + "','" + LP_CLICK + "'");
    }

    private LocalDateTime firstEventSince(String quotedSteps) {
        try {
            Object r = entityManager.createNativeQuery("SELECT MIN(created_at) FROM funnel_events WHERE step IN (" + quotedSteps + ")").getSingleResult();
            return r == null ? null : (r instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Сколько игроков источника трафика прошли онбординг: на экране выбора пути нажали «Спонсорские квесты» или «Игры» (решение владельца 23.11.2026).
     *  События пишутся с момента выкладки воронки: у игроков, выбравших путь раньше, события нет, поэтому у старых источников цифра может быть занижена. */
    public long countChosePathByTrafficSource(String code) {
        if (code == null || code.isBlank()) return 0;
        try {
            jakarta.persistence.Query q = entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM app_users u WHERE u.traffic_source_code = ?1 AND (" + ev(CHOICE_CARDS) + " OR " + ev(CHOICE_GAMES) + ")");
            q.setParameter(1, code);
            return ((Number) q.getSingleResult()).longValue();
        } catch (Exception e) {
            log.warn("[NewbieFunnel] chose-path count failed for {}", code, e);
            return 0;
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

    private String lgConv(String cond) {
        return "EXISTS(SELECT 1 FROM leadgid_conversions l JOIN quest_submissions s ON s.id = l.submission_id WHERE s.user_id = u.id" + cond + ")";
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
        m.put("take_tap", ev(BANK_TAKE_TAP));
        m.put("lp_view", ev(LP_VIEW));
        m.put("lp_card", ev(LP_CARD));
        m.put("lp_click", ev(LP_CLICK));
        m.put("bank_taken", bankSub(""));
        m.put("apply", ev(APPLY_SHOWN));
        // Постбек LeadGid (21.11.2026): лид у банка появляется, когда игрок реально оформил заявку по ссылке; статус - решение банка. Связь с игроком - по номеру заявки из SubID 4
        m.put("lg_lead", lgConv(""));
        m.put("lg_ok", lgConv(" AND l.status_norm = 'APPROVED'"));
        m.put("lg_rej", lgConv(" AND l.status_norm = 'REJECTED'"));
        m.put("bank_report", bankSub(" AND s.status IN ('PENDING','APPROVED','REJECTED','NEEDS_INFO')"));
        m.put("bank_ok", bankSub(" AND s.status = 'APPROVED'"));
        m.put("bank_paid", bankSub(" AND s.bonus_paid_at IS NOT NULL"));
        m.put("picker", ev(GAMES_PICKER));
        m.put("pick", ev(GAME_PICK));
        m.put("starter", ev(STARTER_CARD));
        m.put("game_list", ev(GAME_LIST));
        m.put("game_card", ev(GAME_CARD));
        m.put("sp_other", ev(SP_OTHER));
        m.put("sp_picks", ev(SP_PICKS));
        m.put("sp_myq", ev(SP_MYQ));
        m.put("sp_any_card", ev(SP_ANY_CARD));
        m.put("sp_acted", "(" + ev(SP_DEBIT) + " OR " + ev(SP_OTHER) + " OR " + ev(SP_PICKS) + " OR " + ev(SP_MYQ) + " OR " + ev(SP_ANY_CARD) + ")");
        m.put("game_taken", gameSub(""));
        m.put("game_ok", gameSub(" AND s.status = 'APPROVED'"));
        m.put("activated", "u.registration_completed = TRUE");
        m.put("code", "EXISTS(SELECT 1 FROM daily_code_claims c WHERE c.user_id = u.id)");
        // Возврат на следующий день: только для «созревших» (зарегистрированы не позже ?3); суточные границы - по времени сервера
        m.put("d1_base", "u.created_at <= ?3");
        m.put("d1", "u.created_at <= ?3 AND EXISTS(SELECT 1 FROM user_activity_days a WHERE a.user_id = u.id AND a.activity_day = DATEADD('DAY', 1, CAST(u.created_at AS DATE)))");
        return m;
    }

    /** Колонки таблиц «по источникам» (после источника и числа пришедших): ключи метрик без параметра ?3. */
    public static final List<String> SOURCE_KEYS = List.of("w3", "choice", "cards", "games", "menu",
            "sp_section", "sp_debit", "bank_card", "take_tap", "take18", "bank_taken", "apply", "lp_view", "lp_card", "lp_click", "lg_lead", "lg_ok", "lg_rej", "bank_ok", "bank_paid",
            "picker", "game_list", "game_card", "game_taken", "game_ok");

    /** bySource: строки [источник, пришли, по одному числу на каждый ключ из sourceKeys]. */
    public record Report(LocalDateTime from, LocalDateTime to, boolean clamped, boolean hasEvents, LocalDateTime newStepsSince, LocalDateTime tapSince, LocalDateTime lpSince, Map<String, Long> counts, List<String> sourceKeys, List<Object[]> bySource) { }

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
        return new Report(f, to, clamped, since != null, newStepsSince(), tapStepsSince(), lpStepsSince(), counts, SOURCE_KEYS, bySource(f, to, m));
    }

    /** По источникам: [источник, пришли, и по одному числу на каждый ключ из SOURCE_KEYS]. */
    @SuppressWarnings("unchecked")
    private List<Object[]> bySource(LocalDateTime f, LocalDateTime to, Map<String, String> m) {
        try {
            StringBuilder sql = new StringBuilder("SELECT COALESCE(NULLIF(u.traffic_source_code, ''), 'органика') AS grp, COUNT(*)");
            for (String k : SOURCE_KEYS) {
                sql.append(", COALESCE(SUM(CASE WHEN ").append(m.get(k)).append(" THEN 1 ELSE 0 END), 0)");
            }
            sql.append(" FROM app_users u WHERE u.created_at >= ?1 AND u.created_at < ?2 "
                    + "GROUP BY COALESCE(NULLIF(u.traffic_source_code, ''), 'органика') ORDER BY COUNT(*) DESC LIMIT 8");
            jakarta.persistence.Query q = entityManager.createNativeQuery(sql.toString());
            q.setParameter(1, f);
            q.setParameter(2, to);
            return q.getResultList();
        } catch (Exception e) {
            log.warn("[NewbieFunnel] by-source query failed", e);
            return List.of();
        }
    }

    private static final Map<String, String> STEP_LABELS = new LinkedHashMap<>();
    static {
        STEP_LABELS.put(W1, "Слайд 1");
        STEP_LABELS.put(W2, "Слайд 2");
        STEP_LABELS.put(W3, "Слайд 3");
        STEP_LABELS.put(W_SKIP, "Нажал «Пропустить»");
        STEP_LABELS.put(W_DONE, "Дошёл до конца слайдов");
        STEP_LABELS.put(CHOICE_SHOWN, "Экран выбора пути");
        STEP_LABELS.put(CHOICE_CARDS, "Выбрал «Спонсорские квесты»");
        STEP_LABELS.put(CHOICE_GAMES, "Выбрал «Игры»");
        STEP_LABELS.put(MENU, "Открыл главное меню");
        STEP_LABELS.put(SP_SECTION, "Раздел спонсорских квестов");
        STEP_LABELS.put(SP_DEBIT, "Открыл «Дебетовые карты»");
        STEP_LABELS.put(SP_OTHER, "Открыл другой раздел спонсорских");
        STEP_LABELS.put(SP_PICKS, "Открыл «Экси рекомендует»");
        STEP_LABELS.put(SP_MYQ, "Открыл «Мои квесты» спонсорских");
        STEP_LABELS.put(SP_ANY_CARD, "Открыл карточку спонсорского квеста");
        STEP_LABELS.put(BANK_CARD, "Открыл карточку банка");
        STEP_LABELS.put(BANK_TAKE_TAP, "Нажал «Взять» (банк, до подтверждения 18+)");
        STEP_LABELS.put(BANK_TAKE, "Подтвердил «мне 18+» и взял (банк)");
        STEP_LABELS.put(LP_VIEW, "Открыл витрину банков (по ссылке из бота)");
        STEP_LABELS.put(LP_CARD, "Раскрыл карточку банка на витрине");
        STEP_LABELS.put(LP_CLICK, "Нажал на оффер банка на витрине");
        STEP_LABELS.put(SUB_PROMPT, "Ему показан запрос подписки");
        STEP_LABELS.put(APPLY_SHOWN, "Экран «Оформить карту»");
        STEP_LABELS.put(GAMES_PICKER, "Экран выбора игры");
        STEP_LABELS.put(GAME_PICK, "Выбрал игру");
        STEP_LABELS.put(GAME_LIST, "Список квестов игры");
        STEP_LABELS.put(GAME_CARD, "Карточка игрового квеста");
        STEP_LABELS.put(STARTER_CARD, "Стартовая карточка (старый сценарий)");
    }

    /** Путь одного игрока (команда /journey): шаги воронки со временем, квесты и статусы заявок. Строки уже без HTML-экранирования - экранирует вызывающий. */
    @SuppressWarnings("unchecked")
    public List<String[]> timeline(Long telegramId, Long userId, LocalDateTime registeredAt) {
        List<Object[]> rows = new java.util.ArrayList<>(); // [время, текст]
        try {
            if (registeredAt != null) rows.add(new Object[]{registeredAt, "Пришёл в бота"});
            for (Object[] r : (List<Object[]>) entityManager.createNativeQuery(
                    "SELECT step, created_at FROM funnel_events WHERE telegram_id = ?1 ORDER BY created_at").setParameter(1, telegramId).getResultList()) {
                String step = String.valueOf(r[0]);
                rows.add(new Object[]{toTime(r[1]), STEP_LABELS.getOrDefault(step, step)});
            }
            if (userId != null) {
                for (Object[] r : (List<Object[]>) entityManager.createNativeQuery(
                        "SELECT q.title, s.status, s.created_at, s.bonus_paid_at FROM quest_submissions s JOIN quests q ON q.id = s.quest_id "
                                + "WHERE s.user_id = ?1 ORDER BY s.created_at").setParameter(1, userId).getResultList()) {
                    String title = String.valueOf(r[0]);
                    rows.add(new Object[]{toTime(r[2]), "Взял квест «" + title + "» (сейчас: " + r[1] + ")"});
                    if (r[3] != null) rows.add(new Object[]{toTime(r[3]), "Выплата отмечена по квесту «" + title + "»"});
                }
                for (Object[] r : (List<Object[]>) entityManager.createNativeQuery(
                        "SELECT l.created_at, l.status_norm, q.title FROM leadgid_conversions l JOIN quest_submissions s ON s.id = l.submission_id "
                                + "JOIN quests q ON q.id = s.quest_id WHERE s.user_id = ?1 ORDER BY l.created_at").setParameter(1, userId).getResultList()) {
                    String st = String.valueOf(r[1]);
                    String human = "APPROVED".equals(st) ? "банк одобрил" : "REJECTED".equals(st) ? "банк отклонил" : "PENDING".equals(st) ? "лид на проверке у банка" : "статус не распознан";
                    rows.add(new Object[]{toTime(r[0]), "LeadGid по «" + r[2] + "»: " + human});
                }
                for (Object r : (List<Object>) entityManager.createNativeQuery(
                        "SELECT claimed_at FROM daily_code_claims WHERE user_id = ?1 ORDER BY claimed_at LIMIT 1").setParameter(1, userId).getResultList()) {
                    rows.add(new Object[]{toTime(r), "Ввёл код дня (первый раз)"});
                }
            }
        } catch (Exception e) {
            log.warn("[NewbieFunnel] timeline failed for {}", telegramId, e);
        }
        rows.removeIf(r -> r[0] == null);
        rows.sort(java.util.Comparator.comparing((Object[] r) -> (LocalDateTime) r[0]));
        List<String[]> out = new java.util.ArrayList<>();
        for (Object[] r : rows) out.add(new String[]{java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm").format((LocalDateTime) r[0]), (String) r[1]});
        return out;
    }

    private static LocalDateTime toTime(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (o instanceof LocalDateTime l) return l;
        return null;
    }
}
