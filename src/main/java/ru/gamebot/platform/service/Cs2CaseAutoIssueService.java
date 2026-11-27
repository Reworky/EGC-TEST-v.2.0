package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.enums.RewardRequestStatus;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.domain.repository.RewardRequestRepository;

/**
 * Автовыдача кейсов CS2 (24.11.2026, решение владельца: сразу автоматически, админам - уведомления о закрытых выплатах).
 * После оформления заявки на кейс (событие Created после коммита) покупаем предмет на market.csgo.com методом buy-for:
 * маркетплейс сам отправляет его обменом на Trade URL игрока. Дальше раз в 30 секунд опрашиваем статус по custom_id
 * («egc-<номер заявки>», так что состояние не хранится у нас и переживает перезапуск): передан - заявка закрывается (APPROVED),
 * не принят или отказ маркета - заявка отклоняется, EXC возвращаются. Любой сбой: сообщение игроку и алерт админам.
 * Включено, если задан MARKET_CSGO_API_KEY и каталог кейсов открыт (CS2_CASES_ENABLED); иначе заявки идут вручную, как раньше.
 */
@Slf4j
@Service
public class Cs2CaseAutoIssueService {

    /** Событие: заявка на кейс создана (публикует RewardService внутри транзакции, слушатель срабатывает после коммита). */
    public record Created(Long requestId) {}

    private record Snap(Long id, RewardRequestStatus status, String title, String details, Long telegramId, Long displayId,
                        String nick, long paidExc, LocalDateTime createdAt, String group) {}

    /** Что покупаем на маркетплейсе: market_hash_name, закупка из каталога и множитель потолка цены (кейсы 1,35, скины 1,20: скин дороже и скачет сильнее). */
    private record Def(String hashName, double costRub, double markup) {}

    /** Метка в комментарии отклонённой заявки: обмен не принят игроком вовремя (ShopLimitService закрывает такому игроку кейсы на 30 дней). */
    public static final String NOT_ACCEPTED_MARKER = "[кейс не принят]";
    /** Сколько непринятых обменов подряд отключают автовыдачу (защита аккаунта: штрафы за чужие пропуски ложатся на нас). */
    private static final int PAUSE_AFTER_MISSED = 3;
    private final AtomicInteger missedInRow = new AtomicInteger();
    private volatile boolean paused = false;

    private static final String API = "https://market.csgo.com/api/v2/";
    private static final Pattern PARTNER = Pattern.compile("[?&]partner=(\\d{1,12})");
    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]{4,32})");
    /** Потолок цены закупки: закупка из каталога x 1,35 + 2 ₽. Покупается самый дешёвый лот, потолок только отсекает скачок цены. */
    private static final double MAX_MARKUP = 1.35;
    private static final double MAX_MARKUP_SKIN = 1.20;
    private static final int MAX_POLL_PER_CYCLE = 20;
    /** Награда игроку за одобренный отзыв о скине (EXC), решение владельца 25.11.2026. */
    public static final long REVIEW_REWARD_EXC = 100;
    /** Запас после срока приёма обмена (receive_until), чтобы маркетплейс успел пометить непринятый обмен отменённым. */
    private static final long RECEIVE_BUFFER_SEC = 120;

    private final Cs2CaseCatalog catalog;
    private final Cs2SkinCatalog skinCatalog;
    private final Cs2CapsuleCatalog capsuleCatalog;
    private final RewardService rewardService;
    private final RewardRequestRepository requestRepository;
    private final TransactionTemplate tx;
    private final GamePlatformBot bot;
    private final ObjectMapper mapper;
    private final String apiKey;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    /** Заявки, по которым алерт уже отправлен (чтобы опрос не спамил каждые 30 секунд); сбрасывается при перезапуске. */
    private final Set<Long> alerted = ConcurrentHashMap.newKeySet();

    public Cs2CaseAutoIssueService(Cs2CaseCatalog catalog, Cs2SkinCatalog skinCatalog, Cs2CapsuleCatalog capsuleCatalog, RewardService rewardService, RewardRequestRepository requestRepository,
                                   PlatformTransactionManager txManager, @Lazy GamePlatformBot bot, ObjectMapper mapper,
                                   @Value("${app.market-csgo-api-key:}") String apiKey) {
        this.catalog = catalog;
        this.skinCatalog = skinCatalog;
        this.capsuleCatalog = capsuleCatalog;
        this.rewardService = rewardService;
        this.requestRepository = requestRepository;
        this.tx = new TransactionTemplate(txManager);
        this.bot = bot;
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        log.info("[Cs2CaseAutoIssue] apiKey {}", this.apiKey.isEmpty() ? "NOT set: manual issuance" : "set");
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /** Автовыдача настроена: есть ключ маркетплейса и обмен кейсов включён (опрос статусов идёт и на паузе, чтобы довести начатые заявки). */
    public boolean isActive() {
        return !apiKey.isEmpty() && catalog.isEnabled();
    }

    /** Новые заявки берём в автовыдачу: настроена и не на паузе (после серии непринятых обменов заявки на кейсы идут вручную до перезапуска бота). */
    public boolean acceptsNew() {
        return isActive() && !paused;
    }

    /** {partner, token} из Trade URL Steam (steamcommunity.com/tradeoffer/new/?partner=...&token=...); null - ссылка не подходит. */
    public static String[] parseTradeUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!u.contains("steamcommunity.com/tradeoffer/new")) return null;
        Matcher p = PARTNER.matcher(u);
        Matcher t = TOKEN.matcher(u);
        if (!p.find() || !t.find()) return null;
        return new String[]{p.group(1), t.group(1)};
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(Created event) {
        if (!acceptsNew()) return;
        executor.submit(() -> {
            try {
                start(event.requestId());
            } catch (Exception e) {
                log.error("[Cs2CaseAutoIssue] start failed for request {}", event.requestId(), e);
                alertAdmins("⚠️ Автовыдача кейса: сбой при запуске заявки #" + event.requestId() + ": " + escape(e.getMessage())
                        + "\nЗаявка осталась в работе - проверьте вручную.");
            }
        });
    }

    private void start(Long id) {
        Snap s = snapshot(id);
        if (s == null || s.status() != RewardRequestStatus.PENDING || !isCs2Group(s.group())) return;
        Def c = findDef(s.title());
        if (c == null || c.hashName().isBlank()) {
            fail(s, "Эту позицию временно нельзя выдать автоматически.", "позиции нет в каталогах кейсов и скинов или не задан hashName: " + s.title());
            return;
        }
        String[] link = parseTradeUrl(s.details());
        if (link == null) {
            fail(s, "Ссылка обмена Steam не подошла: проверьте её и оформите заявку заново.", null);
            return;
        }
        try {
            rewardService.takeInProgressRequest(id); // занимаем заявку, чтобы вторая попытка (рестарт, дубль события) её не купила
        } catch (IllegalArgumentException alreadyHandled) {
            return;
        }
        String customId = "egc-" + id;
        JsonNode existing = call("get-buy-info-by-custom-id", Map.of("custom_id", customId));
        if (existing != null && existing.path("success").asBoolean(false)) {
            return; // покупка уже есть (повтор после сбоя) - дальше статус заберёт опрос
        }
        long priceKop = Math.round((c.costRub() * c.markup() + 2) * 100);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("hash_name", c.hashName());
        params.put("price", String.valueOf(priceKop));
        params.put("partner", link[0]);
        params.put("token", link[1]);
        params.put("custom_id", customId);
        JsonNode r = call("buy-for", params);
        if (r == null) {
            fail(s, "Не получилось связаться с поставщиком.", "нет ответа от market.csgo.com при покупке");
            return;
        }
        if (r.path("success").asBoolean(false)) {
            log.info("[Cs2CaseAutoIssue] bought '{}' for request {} (market item {})", c.hashName(), id, r.path("id").asText());
            bot.caseAutoNotify(s.telegramId(), "🎁 <b>" + noun(s) + " уже в пути</b>\n\n📦 " + escape(s.title()) + " · заявка <b>М-" + s.displayId() + "</b>\n\n"
                    + "<b>Что сделать сейчас:</b>\n1️⃣ Откройте Steam → «Предложения обмена»\n2️⃣ Нажмите «Принять» в течение <b>5 минут</b>\n\n"
                    + "⚠️ Не успеете: обмен отменится, а EXC вернутся.\n\n"
                    + "<blockquote expandable>⏱ После принятия обмена подтверждение в боте придёт не сразу, обычно в пределах 10 минут после покупки: мы проверяем сделку.</blockquote>", null);
            return;
        }
        int code = r.path("code").asInt(0);
        String error = r.path("error").asText("");
        String playerMsg = switch (code) {
            case 3, 12 -> "Ссылка обмена Steam не подошла. Возьмите ссылку заново (Steam → Инвентарь → Предложения обмена → «Кто может отправлять мне предложения обмена?») и оформите заявку ещё раз.";
            case 5 -> "Инвентарь Steam скрыт: откройте профиль и инвентарь для всех и оформите заявку заново.";
            case 6 -> "Аккаунт Steam ограничен, выдача на него невозможна.";
            case 7 -> "На аккаунте Steam не привязан мобильный аутентификатор, без него обмены не работают. Привяжите его и оформите заявку заново.";
            case 21 -> "Инвентарь Steam переполнен: освободите место и оформите заявку заново.";
            default -> "Не получилось выдать кейс автоматически.";
        };
        boolean playerFixable = code == 3 || code == 5 || code == 6 || code == 7 || code == 12 || code == 21;
        fail(s, playerMsg, playerFixable ? null : "buy-for отказал (code " + code + "): " + error + " [" + c.hashName() + ", цена-потолок " + priceKop / 100.0 + " ₽]");
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void poll() {
        if (!isActive()) return;
        List<Long> ids = tx.execute(st -> requestRepository.findAllByStatusOrderByCreatedAtAsc(RewardRequestStatus.IN_PROGRESS).stream()
                .filter(r -> isCs2Group(r.getRewardItem().getPurchaseGroup()))
                .map(RewardRequest::getId).limit(MAX_POLL_PER_CYCLE).toList());
        if (ids == null) return;
        for (Long id : ids) {
            try {
                check(id);
                Thread.sleep(300); // не частим с запросами к маркетплейсу
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[Cs2CaseAutoIssue] check failed for request {}", id, e);
            }
        }
    }

    private void check(Long id) {
        Snap s = snapshot(id);
        if (s == null || s.status() != RewardRequestStatus.IN_PROGRESS) return;
        JsonNode info = call("get-buy-info-by-custom-id", Map.of("custom_id", "egc-" + id));
        if (info == null) return; // нет связи - повторим в следующий цикл
        boolean old15 = s.createdAt() != null && s.createdAt().isBefore(LocalDateTime.now().minusMinutes(15));
        if (!info.path("success").asBoolean(false)) {
            if (old15 && alerted.add(id)) {
                alertAdmins("⚠️ Заявка на кейс М-" + s.displayId() + " в работе, но покупки на маркетплейсе нет (взята вручную или сбой до покупки). Проверьте вручную.");
            }
            return;
        }
        JsonNode data = info.path("data");
        int stage = data.path("stage").asInt(0);
        long receiveUntil = data.path("receive_until").asLong(0);
        long nowSec = System.currentTimeMillis() / 1000;
        String cancellation = data.path("cancellation_reason").isNull() ? "" : data.path("cancellation_reason").asText("");
        String causer = data.path("causer").isNull() ? "" : data.path("causer").asText("");
        if (stage == 5 || !cancellation.isEmpty() || stage == 2 || (receiveUntil > 0 && nowSec > receiveUntil + RECEIVE_BUFFER_SEC)) {
            log.info("[Cs2CaseAutoIssue] decision for request {}: {}", id, data); // сырой ответ маркетплейса для разбора признаков успеха/отмены (ключа в нём нет)
        }
        if (stage == 5 || !cancellation.isEmpty()) {
            // обмен отменён или истёк: если виноват продавец - игрок не при чём (без блока кейсов), иначе игрок не принял вовремя
            boolean sellerFault = "seller".equalsIgnoreCase(causer);
            fail(s, sellerFault ? "Продавец отменил обмен, кейс не передан." : "Обмен в Steam не был принят в течение 5 минут.",
                    "маркетплейс: обмен по заявке М-" + s.displayId() + " отменён (stage " + stage + ", causer '" + causer + "', причина '" + cancellation + "')", !sellerFault);
        } else if (stage == 2 || (receiveUntil > 0 && nowSec > receiveUntil + RECEIVE_BUFFER_SEC && data.path("settlement").asLong(0) > 0)) {
            // stage 2 маркетплейс ставит не сразу (по факту 24.11.2026 у успешного обмена stage остаётся 1 до конца 7-дневного периода): считаем кейс полученным,
            // когда срок приёма receive_until прошёл с запасом, обмен не отменён и не истёк
            complete(s, data.path("paid").asDouble(0), stage);
        } else if (s.createdAt() != null && s.createdAt().isBefore(LocalDateTime.now().minusHours(12)) && alerted.add(id)) {
            alertAdmins("⚠️ Кейс по заявке М-" + s.displayId() + " не передан уже больше 12 часов (stage " + stage + "). Проверьте на market.csgo.com.");
        }
    }

    /** Наблюдение за отменой обмена продавцом (политика Steam: до 7 дней): раз в полчаса смотрим выданные автовыдачей кейсы за 7 дней; отмена - алерт админам. */
    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void watchReversals() {
        if (!isActive()) return;
        LocalDateTime since7 = LocalDateTime.now().minusDays(7);
        List<Long> ids = new java.util.ArrayList<>();
        for (String group : AUTO_GROUPS) {
            ids.addAll(requestRepository.findApprovedIdsSince(group, since7));
        }
        int checked = 0;
        for (Long id : ids) {
            if (checked >= 40 || alerted.contains(-id)) continue;
            try {
                JsonNode info = call("get-buy-info-by-custom-id", Map.of("custom_id", "egc-" + id));
                checked++;
                if (info == null || !info.path("success").asBoolean(false)) continue; // ручная выдача или нет связи
                JsonNode data = info.path("data");
                JsonNode refund = data.path("refund");
                boolean cancelled = !data.path("cancellation_reason").isNull() && !data.path("cancellation_reason").asText("").isEmpty();
                boolean reversed = data.path("stage").asInt(0) == 5 || cancelled || (refund.isObject() && refund.size() > 0);
                if (reversed && alerted.add(-id)) {
                    Snap s = snapshot(id);
                    alertAdmins("⚠️ Возможна отмена обмена продавцом по заявке М-" + (s == null ? id : s.displayId())
                            + (s == null ? "" : " (" + escape(s.title()) + ", " + escape(s.nick()) + ")")
                            + ": заявка у нас закрыта и EXC списаны, а на маркетплейсе обмен выглядит отменённым. Проверьте на market.csgo.com, игрок мог остаться без кейса.");
                }
            } catch (Exception e) {
                log.warn("[Cs2CaseAutoIssue] reversal watch failed for request {}", id, e);
            }
        }
    }

    private void complete(Snap s, double paidRub, int stage) {
        try {
            rewardService.approveRequest(s.id());
        } catch (IllegalArgumentException alreadyHandled) {
            return;
        }
        missedInRow.set(0);
        String player = "✅ <b>" + noun(s) + " у вас!</b>\n\n📦 " + escape(s.title()) + " · заявка <b>М-" + s.displayId() + "</b>\n"
                + "🪙 Списано: <b>" + fmtExc(s.paidExc()) + " EXC</b>\n\nПриятного открытия 🎉\nЧто-то не так? Напишите в поддержку.";
        String admin = "✅ <b>Закрыта выплата (автовыдача CS2)</b>\n\nЗаявка М-" + s.displayId() + " · " + escape(s.title()) + "\n"
                + "👤 " + escape(s.nick()) + " (" + s.telegramId() + ")\n"
                + "🪙 Списано: " + fmtExc(s.paidExc()) + " EXC" + (paidRub > 0 ? " · закупка на маркетплейсе: " + paidRub + " ₽" : "")
                + "\n🔎 Признак: срок приёма обмена прошёл, отмены нет (stage " + stage + ")";
        if (Cs2SkinCatalog.GROUP.equals(s.group())) {
            // скин: игроку - картинка скина с просьбой оценить покупку (жёсткий замок на следующий скин, +100 EXC после проверки отзыва), админам - как обычно
            String image = skinCatalog.all().stream().filter(k -> k.title().equals(s.title())).map(Cs2SkinCatalog.SkinDef::imageResource).findFirst().orElse(null);
            String caption = player + "\n\n⭐ <b>Оцените покупку, это 10 секунд.</b> Отзыв появится в нашем канале после проверки, и вы получите <b>+" + REVIEW_REWARD_EXC + " EXC</b>.";
            bot.skinIssuedReviewPrompt(s.telegramId(), s.id(), caption, image);
            bot.caseAutoNotify(null, null, admin);
            return;
        }
        bot.caseAutoNotify(s.telegramId(), player, admin);
    }

    /** Отклоняем заявку (EXC возвращаются), пишем игроку причину; adminDetail != null - ещё и алерт админам. */
    private void fail(Snap s, String playerText, String adminDetail) {
        fail(s, playerText, adminDetail, false);
    }

    /** notAccepted - игрок не принял обмен вовремя: комментарий получает метку (блок кейсов на 30 дней), счётчик подряд идущих пропусков растёт. */
    private void fail(Snap s, String playerText, String adminDetail, boolean notAccepted) {
        try {
            rewardService.rejectRequest(s.id(), (notAccepted ? NOT_ACCEPTED_MARKER + " " : "") + playerText);
        } catch (IllegalArgumentException alreadyHandled) {
            return;
        } catch (Exception e) {
            log.error("[Cs2CaseAutoIssue] reject failed for request {}", s.id(), e);
            alertAdmins("⚠️ Автовыдача кейса: не удалось отклонить и вернуть EXC по заявке М-" + s.displayId() + " (" + escape(e.getMessage()) + "). Проверьте вручную.");
            return;
        }
        bot.caseAutoNotify(s.telegramId(), "❌ <b>" + noun(s) + " " + notIssued(s) + "</b>\n\n📦 " + escape(s.title()) + " · заявка <b>М-" + s.displayId() + "</b>\n"
                + escape(playerText) + "\n\n🪙 <b>" + fmtExc(s.paidExc()) + " EXC</b> вернулись на баланс."
                + (notAccepted ? "\n⏳ Кейсы и скины будут недоступны 30 дней." : ""), null);
        if (adminDetail != null) {
            alertAdmins("⚠️ Автовыдача кейса: заявка М-" + s.displayId() + " отклонена, EXC возвращены. Причина: " + escape(adminDetail));
        }        if (notAccepted && missedInRow.incrementAndGet() >= PAUSE_AFTER_MISSED && !paused) {
            paused = true;
            alertAdmins("🛑 <b>Автовыдача кейсов остановлена</b>: " + PAUSE_AFTER_MISSED + " обмена подряд не приняты игроками (штрафы за это могут лечь на аккаунт маркетплейса). "
                    + "Новые заявки на кейсы идут вручную, начатые заявки доводятся. Вернуть автовыдачу: перезапуск бота (деплой).");
        }
    }

    private void alertAdmins(String text) {
        try {
            bot.caseAutoNotify(null, null, text);
        } catch (Exception e) {
            log.error("[Cs2CaseAutoIssue] alert failed: {}", text, e);
        }
    }

    /** Группы товаров, которые выдаёт автовыдача: кейсы, скины, капсулы. */
    public static final List<String> AUTO_GROUPS = List.of(Cs2CaseCatalog.GROUP, Cs2SkinCatalog.GROUP, Cs2CapsuleCatalog.GROUP);

    public static boolean isAutoGroup(String group) {
        return group != null && AUTO_GROUPS.contains(group);
    }

    private static boolean isCs2Group(String group) {
        return isAutoGroup(group);
    }

    private static String noun(Snap s) {
        return Cs2SkinCatalog.GROUP.equals(s.group()) ? "Скин" : Cs2CapsuleCatalog.GROUP.equals(s.group()) ? "Капсула" : "Кейс";
    }

    /** «не выдан» / «не выдана» по роду существительного. */
    private static String notIssued(Snap s) {
        return Cs2CapsuleCatalog.GROUP.equals(s.group()) ? "не выдана" : "не выдан";
    }

    private Def findDef(String title) {
        for (Cs2CaseCatalog.CaseDef c : catalog.all()) {
            if (c.title().equals(title)) return new Def(c.hashName(), c.costRub(), MAX_MARKUP);
        }
        for (Cs2SkinCatalog.SkinDef k : skinCatalog.all()) {
            if (k.title().equals(title)) return new Def(k.hashName(), k.costRub(), MAX_MARKUP_SKIN);
        }
        for (Cs2CapsuleCatalog.CapsuleDef k : capsuleCatalog.all()) {
            if (k.title().equals(title)) return new Def(k.hashName(), k.costRub(), MAX_MARKUP);
        }
        return null;
    }

    private Snap snapshot(Long id) {
        return tx.execute(st -> requestRepository.findById(id).map(r -> new Snap(r.getId(), r.getStatus(), r.getRewardItem().getTitle(),
                r.getPayoutDetails(), r.getUser().getTelegramId(), r.getDisplayId(), r.getUser().getNickname(),
                rewardService.actualPaidPrice(r), r.getCreatedAt(),
                r.getRewardItem().getPurchaseGroup())).orElse(null));
    }

    /** Минимальный интервал между запросами к маркетплейсу: он удаляет ключ при более чем 5 запросах в секунду, держим около 2 в секунду на все потоки сразу. */
    private static final long MIN_CALL_INTERVAL_MS = 450;
    private final Object rateLock = new Object();
    private long lastCallAtMs = 0;

    private void throttle() throws InterruptedException {
        synchronized (rateLock) {
            long wait = lastCallAtMs + MIN_CALL_INTERVAL_MS - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastCallAtMs = System.currentTimeMillis();
        }
    }

    /** Ключ маркетплейса задан (для скина-награды за квест открытость кейсов не нужна). */
    public boolean isMarketConfigured() {
        return !apiKey.isEmpty();
    }

    /** Вызов API маркетплейса для других выдач (скин за квест): общий ограничитель частоты запросов. */
    public JsonNode marketCall(String method, Map<String, String> params) {
        return call(method, params);
    }

    /** GET к API маркетплейса; null - нет ответа или не JSON. Ключ в логи не попадает. */
    private JsonNode call(String method, Map<String, String> params) {
        try {
            throttle();
            StringBuilder url = new StringBuilder(API).append(method).append("?key=").append(URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
            for (Map.Entry<String, String> e : params.entrySet()) {
                url.append('&').append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[Cs2CaseAutoIssue] {} -> HTTP {}", method, resp.statusCode());
                return null;
            }
            return mapper.readTree(resp.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("[Cs2CaseAutoIssue] {} failed: {}", method, e.getClass().getSimpleName());
            return null;
        }
    }

    private static String fmtExc(long value) {
        return String.format("%,d", value).replace(',', ' ');
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
