package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AdCampaign;
import ru.gamebot.platform.domain.model.AdPriceItem;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.repository.AdCampaignRepository;
import ru.gamebot.platform.domain.repository.AdPriceItemRepository;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.ExcTransactionRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

/**
 * Раздел «Рекламодателям» (ТЗ «EGC - Метрики и медиа-кит для рекламодателей»): витринные метрики, история кампаний, прайс-лист,
 * медиа-кит в HTML (полная версия для админа и облегчённая публичная по ссылке с токеном). Живые метрики - из платформы; то, чего
 * Bot API канала не отдаёт (средние просмотры поста, язык аудитории, устройства), вводится админом вручную и хранится в AppSetting.
 */
@Service
@RequiredArgsConstructor
public class AdvertiserService {

    public static final String K_AVG_VIEWS = "adv.avgViews";
    public static final String K_GEO = "adv.geoLang";
    public static final String K_DEVICES = "adv.devices";
    public static final String K_TOKEN = "adv.publicToken";
    public static final String K_BASE_URL = "adv.baseUrl";
    public static final String K_SUBS = "adv.subscribers";
    public static final String K_SUBS_AT = "adv.subscribersAt";

    private static final String DEFAULT_BASE_URL = "https://egc-club.online";
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AppSettingRepository settings;
    private final AdCampaignRepository campaigns;
    private final AdPriceItemRepository prices;
    private final AnalyticsService analytics;
    private final UserService userService;
    private final ExcTransactionService excService;
    private final ExcTransactionRepository excRepo;
    private final QuestSubmissionRepository submissionRepo;
    private final TrafficFunnelService funnelService;

    // ───────────── настройки ─────────────

    public String get(String key) {
        return settings.findById(key).map(AppSetting::getValue).orElse(null);
    }

    public void set(String key, String value) {
        AppSetting s = settings.findById(key).orElseGet(AppSetting::new);
        s.setKey(key);
        s.setValue(value);
        settings.save(s);
    }

    /** Токен публичной ссылки; создаётся при первом обращении. */
    public String ensureToken() {
        String t = get(K_TOKEN);
        if (t == null || t.isBlank()) {
            t = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            set(K_TOKEN, t);
        }
        return t;
    }

    /** Новый токен: прежняя публичная ссылка перестаёт работать. */
    public String regenerateToken() {
        set(K_TOKEN, UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        return get(K_TOKEN);
    }

    public boolean tokenMatches(String token) {
        String t = get(K_TOKEN);
        return t != null && !t.isBlank() && t.equals(token);
    }

    public String publicUrl() {
        String base = get(K_BASE_URL);
        return (base == null || base.isBlank() ? DEFAULT_BASE_URL : base.trim()) + "/api/public/mediakit/" + ensureToken();
    }

    /** Число подписчиков канала запрашивает бот (Bot API), тут только запоминаем - для публичной страницы, где бота нет под рукой. */
    public void rememberSubscribers(long subscribers) {
        set(K_SUBS, String.valueOf(subscribers));
        set(K_SUBS_AT, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));
    }

    public Long cachedSubscribers() {
        try {
            return Long.parseLong(get(K_SUBS));
        } catch (Exception e) {
            return null;
        }
    }

    // ───────────── кампании и прайс-лист ─────────────

    public List<AdCampaign> campaigns() {
        return campaigns.findAllByOrderByStartDateDesc();
    }

    public AdCampaign addCampaign(String advertiser, LocalDate start, LocalDate end, String sourceCode, long budgetRub, Long impressions) {
        AdCampaign c = new AdCampaign();
        c.setAdvertiser(advertiser.length() > 120 ? advertiser.substring(0, 120) : advertiser);
        c.setStartDate(start);
        c.setEndDate(end);
        c.setSourceCode(sourceCode == null || sourceCode.isBlank() || "-".equals(sourceCode) ? null : sourceCode.trim());
        c.setBudgetRub(budgetRub);
        c.setImpressions(impressions);
        return campaigns.save(c);
    }

    public void deleteCampaign(Long id) {
        campaigns.deleteById(id);
    }

    public void setImpressions(Long id, long impressions) {
        campaigns.findById(id).ifPresent(c -> {
            c.setImpressions(impressions);
            campaigns.save(c);
        });
    }

    public List<AdPriceItem> priceItems() {
        return prices.findAllByOrderByIdAsc();
    }

    public AdPriceItem addPrice(String title, long priceRub, String conditions) {
        AdPriceItem p = new AdPriceItem();
        p.setTitle(title.length() > 120 ? title.substring(0, 120) : title);
        p.setPriceRub(priceRub);
        p.setConditions(conditions == null || conditions.isBlank() ? null : (conditions.length() > 250 ? conditions.substring(0, 250) : conditions));
        return prices.save(p);
    }

    public void deletePrice(Long id) {
        prices.deleteById(id);
    }

    // ───────────── витрина ─────────────

    /** Итог по кампании: пришло (зашли в бота по метке) / дошли до 1-го квеста / CTR (клики по ссылке / просмотры поста). */
    public record CampaignResult(AdCampaign campaign, Long came, Long firstQuest, Long clicks, Double ctrPercent) {}

    public List<CampaignResult> campaignResults() {
        Map<String, TrafficFunnelService.SourceFunnel> byCode = funnelService.compute().stream()
                .collect(Collectors.toMap(f -> f.source().getCode(), f -> f, (a, b) -> a));
        List<CampaignResult> out = new ArrayList<>();
        for (AdCampaign c : campaigns()) {
            TrafficFunnelService.SourceFunnel f = c.getSourceCode() == null ? null : byCode.get(c.getSourceCode());
            Long clicks = f == null ? null : f.source().getClicks();
            Double ctr = (clicks != null && c.getImpressions() != null && c.getImpressions() > 0) ? clicks * 100.0 / c.getImpressions() : null;
            out.add(new CampaignResult(c, f == null ? null : f.started(), f == null ? null : f.firstQuest(), clicks, ctr));
        }
        return out;
    }

    private static String num(long v) {
        return String.format(RU, "%,d", v);
    }

    private static String dbl(double v) {
        return v == Math.rint(v) ? num((long) v) : String.format(RU, "%,.1f", v);
    }

    private Optional<AnalyticsService.Line> line(AnalyticsService.TabData d, String key) {
        return d.lines().stream().filter(l -> key.equals(l.key())).findFirst();
    }

    private static String v(Optional<AnalyticsService.Line> l, String unit) {
        return l.map(AnalyticsService.Line::value).map(x -> dbl(x) + (unit.isEmpty() ? "" : " " + unit)).orElse("—");
    }

    /** Показатели витрины: [заголовок блока, строки «метрика|значение»...]. subscribers - из Bot API (или кэш), null - неизвестно. */
    public record Block(String title, List<String[]> rows) {}

    public List<Block> showcase(Long subscribers) {
        List<Block> blocks = new ArrayList<>();
        // 2.1 Охват и размер аудитории
        List<String[]> reach = new ArrayList<>();
        reach.add(new String[]{"Подписчиков канала", subscribers == null ? "—" : num(subscribers)});
        Double avgViews = null;
        try {
            avgViews = Double.parseDouble(get(K_AVG_VIEWS));
        } catch (Exception ignored) {
        }
        reach.add(new String[]{"Средние просмотры поста (10-20 последних)", avgViews == null ? "не указаны" : dbl(avgViews)});
        reach.add(new String[]{"ERR% (просмотры / подписчики)", (avgViews != null && subscribers != null && subscribers > 0) ? dbl(avgViews * 100.0 / subscribers) + "%" : "—"});
        blocks.add(new Block("Охват и размер аудитории", reach));
        // 2.2 Доказательство живой аудитории
        AnalyticsService.TabData eng = analytics.compute(AnalyticsService.Tab.ENGAGEMENT, AnalyticsService.Period.lastDays(7));
        AnalyticsService.TabData act = analytics.compute(AnalyticsService.Tab.ACTIVITY, AnalyticsService.Period.lastDays(7));
        List<String[]> alive = new ArrayList<>();
        alive.add(new String[]{"DAU / MAU", v(line(eng, "dau_mau"), "%")});
        alive.add(new String[]{"Возврат за 7 дней", v(line(act, "ret7"), "%")});
        alive.add(new String[]{"Возврат за 30 дней", v(line(act, "ret30"), "%")});
        alive.add(new String[]{"Активны за 7 дней от базы", v(line(act, "active7pct"), "%")});
        blocks.add(new Block("Живая аудитория", alive));
        // 2.3 Экономическая вовлечённость
        LocalDateTime now = LocalDateTime.now();
        long mau = Math.max(1, line(eng, "mau").map(AnalyticsService.Line::value).map(Double::longValue).orElse(1L));
        long earned = excService.sumEarnedSince(now.minusDays(30));
        long spent = Math.abs(excRepo.sumNegativeSince(now.minusDays(30)));
        List<String[]> economy = new ArrayList<>();
        economy.add(new String[]{"EXC заработано на активного игрока за 30 дней", dbl((double) earned / mau)});
        economy.add(new String[]{"EXC потрачено на активного игрока за 30 дней", dbl((double) spent / mau)});
        economy.add(new String[]{"Выполнено квестов за 7 дней", num(submissionRepo.countApprovedSince(now.minusDays(7)))});
        economy.add(new String[]{"Выполнено квестов за 30 дней", num(submissionRepo.countApprovedSince(now.minusDays(30)))});
        blocks.add(new Block("Экономическая вовлечённость", economy));
        // 2.4 Гео и демография
        List<String[]> geo = new ArrayList<>();
        List<Object[]> countries = userService.countUsersByCountry();
        long withCountry = countries.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        int i = 0;
        for (Object[] r : countries) {
            if (i++ >= 5) break;
            long c = ((Number) r[1]).longValue();
            geo.add(new String[]{String.valueOf(r[0]), num(c) + (withCountry > 0 ? " (" + dbl(c * 100.0 / withCountry) + "%)" : "")});
        }
        if (geo.isEmpty()) geo.add(new String[]{"Страны", "данных пока нет (страну указывают в профиле)"});
        String lang = get(K_GEO);
        String dev = get(K_DEVICES);
        geo.add(new String[]{"Язык аудитории", lang == null || lang.isBlank() ? "не указан" : lang});
        geo.add(new String[]{"Устройства", dev == null || dev.isBlank() ? "не указаны" : dev});
        blocks.add(new Block("Гео и демография", geo));
        return blocks;
    }

    /** Текст витрины для сообщения в боте (HTML Telegram). */
    public String showcaseText(Long subscribers) {
        StringBuilder sb = new StringBuilder("📊 <b>Витрина для рекламодателя</b>\n");
        for (Block b : showcase(subscribers)) {
            sb.append("\n<b>").append(esc(b.title())).append("</b>\n");
            for (String[] r : b.rows()) sb.append("• ").append(esc(r[0])).append(": <b>").append(esc(r[1])).append("</b>\n");
        }
        List<CampaignResult> results = campaignResults();
        sb.append("\n<b>История размещений</b>\n");
        if (results.isEmpty()) sb.append("Кампаний пока нет: добавьте в «📢 Кампании».\n");
        sb.append("Всего кампаний: <b>").append(results.size()).append("</b>\n");
        return sb.toString();
    }

    // ───────────── медиа-кит (HTML) ─────────────

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Самодостаточный HTML (открывается в браузере, «Печать → PDF» даёт готовый файл). Публичная версия без бюджетов и записей о ценах сделок. */
    public String mediaKitHtml(Long subscribers, boolean publicVersion) {
        if (!publicVersion) return buildMediaKitHtml(subscribers, false);
        // Публичная страница открывается без входа: считает метрики по БД, поэтому кэшируем на 10 минут, чтобы ссылка не нагружала базу.
        long now = System.currentTimeMillis();
        String cached = cachedPublicHtml;
        if (cached != null && now - cachedPublicAt < 600_000) return cached;
        String html = buildMediaKitHtml(subscribers, true);
        cachedPublicHtml = html;
        cachedPublicAt = now;
        return html;
    }

    private volatile String cachedPublicHtml;
    private volatile long cachedPublicAt;

    private String buildMediaKitHtml(Long subscribers, boolean publicVersion) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>EGC - медиа-кит</title><style>")
         .append("body{margin:0;background:#0b0620;color:#f2eefc;font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;line-height:1.5}")
         .append(".w{max-width:860px;margin:0 auto;padding:32px 20px}h1{margin:0 0 4px;font-size:34px}.sub{color:#b9a8ee;margin-bottom:28px}")
         .append("h2{margin:32px 0 10px;font-size:20px;color:#d8c7ff}.card{background:#150c33;border:1px solid #3a2a78;border-radius:14px;padding:6px 18px}")
         .append(".r{display:flex;justify-content:space-between;gap:16px;padding:10px 0;border-bottom:1px solid #2a1e5a}.r:last-child{border:0}")
         .append(".r b{color:#ffd23f;text-align:right}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px 8px;border-bottom:1px solid #2a1e5a;font-size:14px}")
         .append("th{color:#b9a8ee;font-weight:600}.foot{margin-top:36px;color:#8f7fc9;font-size:12px}@media print{body{background:#fff;color:#111}.card{background:#fff;border-color:#bbb}h2{color:#333}.r b{color:#000}}")
         .append("</style></head><body><div class=\"w\">");
        h.append("<h1>EXPERIENCE GAMING CLUB</h1><div class=\"sub\">Медиа-кит · данные на ")
         .append(LocalDate.now().format(DATE)).append("</div>");
        for (Block b : showcase(subscribers)) {
            h.append("<h2>").append(esc(b.title())).append("</h2><div class=\"card\">");
            for (String[] r : b.rows()) {
                h.append("<div class=\"r\"><span>").append(esc(r[0])).append("</span><b>").append(esc(r[1])).append("</b></div>");
            }
            h.append("</div>");
        }
        List<CampaignResult> results = campaignResults();
        h.append("<h2>История размещений</h2><div class=\"card\">");
        if (results.isEmpty()) {
            h.append("<div class=\"r\"><span>Кампаний пока нет</span><b>—</b></div>");
        } else {
            h.append("<table><tr><th>Площадка / рекламодатель</th><th>Даты</th><th>Пришло</th><th>Дошли до 1-го квеста</th><th>CTR</th>")
             .append(publicVersion ? "" : "<th>Бюджет</th>").append("</tr>");
            for (CampaignResult r : results) {
                AdCampaign c = r.campaign();
                h.append("<tr><td>").append(esc(c.getAdvertiser())).append("</td><td>")
                 .append(c.getStartDate() == null ? "" : c.getStartDate().format(DATE)).append(c.getEndDate() == null ? "" : " - " + c.getEndDate().format(DATE))
                 .append("</td><td>").append(r.came() == null ? "—" : num(r.came())).append("</td><td>")
                 .append(r.firstQuest() == null ? "—" : num(r.firstQuest())).append("</td><td>")
                 .append(r.ctrPercent() == null ? "—" : dbl(r.ctrPercent()) + "%").append("</td>")
                 .append(publicVersion ? "" : "<td>" + num(c.getBudgetRub()) + " ₽</td>").append("</tr>");
            }
            h.append("</table>");
        }
        h.append("</div>");
        List<AdPriceItem> items = priceItems();
        h.append("<h2>Прайс-лист</h2><div class=\"card\">");
        if (items.isEmpty()) {
            h.append("<div class=\"r\"><span>Цены по запросу</span><b>—</b></div>");
        } else {
            h.append("<table><tr><th>Размещение</th><th>Цена</th><th>Условия</th></tr>");
            for (AdPriceItem p : items) {
                h.append("<tr><td>").append(esc(p.getTitle())).append("</td><td><b>").append(num(p.getPriceRub())).append(" ₽</b></td><td>")
                 .append(esc(p.getConditions())).append("</td></tr>");
            }
            h.append("</table>");
        }
        h.append("</div><div class=\"foot\">Все показатели считаются по реальным данным платформы: ежедневные снимки, возврат по когортам, выполненные задания. ")
         .append("Метрики канала, недоступные боту (просмотры, язык, устройства), указаны по данным Telegram Analytics.</div></div></body></html>");
        return h.toString();
    }
}
