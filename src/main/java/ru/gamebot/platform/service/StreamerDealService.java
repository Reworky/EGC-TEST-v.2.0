package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.FinanceEntry;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;

/**
 * Калькулятор сделки со стримером (2026-10-11): по показателям стримера (средний онлайн на Twitch, доля аудитории из РФ/СНГ,
 * число упоминаний) считает воронку (зрители → клики → /start → регистрации → 1-й квест → активные через 30 дней), стоимость
 * реферальных выплат по его ссылке и максимальную ФИКСИРОВАННУЮ цену, при которой проект не в убытке.
 *
 * Допущения берутся из данных проекта, где их достаточно (доля регистраций и квестов у «приглашённых», удержание, чистый доход
 * на игрока из P&L, цена привлечения по закупам), иначе — из настроек app_settings «streamer.*» или значений по умолчанию.
 * Ничего не пишет в базу, кроме настроек, которые меняет админ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamerDealService {

    public static final String K_CTR = "streamer.ctr";
    public static final String K_START = "streamer.start_rate";
    public static final String K_REG = "streamer.reg_rate";
    public static final String K_QUEST = "streamer.quest_rate";
    public static final String K_RET = "streamer.ret30";
    public static final String K_MONTHS = "streamer.months";
    public static final String K_EARN = "streamer.earn_exc";
    public static final String K_ARPU = "streamer.arpu_net";
    public static final String K_SAFETY = "streamer.safety";
    public static final String K_REPEAT = "streamer.repeat";

    /** Реферальные награды из UserService: за регистрацию друга (реферер/друг) и за его первый квест (реферер/друг), доля реферера от заработка друга. */
    private static final double REG_REFERRER_EXC = 300;
    private static final double REG_FRIEND_EXC = 500;
    private static final double FIRST_QUEST_REFERRER_EXC = 2_500;
    private static final double FIRST_QUEST_FRIEND_EXC = 3_000;
    private static final double TRICKLE_SHARE = 0.10;

    private static final int MIN_SAMPLE_FUNNEL = 40;
    private static final int MIN_CLICKS_SAMPLE = 100;
    private static final int COHORT_DAYS = 90;

    /** auto = 0 в настройке означает «брать из данных проекта». */
    public record ParamDef(String key, String label, String unit, double def, boolean auto, double min, double max) {}

    public static final List<ParamDef> PARAMS = List.of(
            new ParamDef(K_CTR, "Клики по ссылке за одно упоминание", "% зрителей", 2.0, false, 0.1, 30),
            new ParamDef(K_START, "Клик → /start в боте", "%", 65, true, 1, 100),
            new ParamDef(K_REG, "/start → регистрация (подписка на канал)", "%", 45, true, 1, 100),
            new ParamDef(K_QUEST, "Регистрация → 1-й квест", "%", 40, true, 1, 100),
            new ParamDef(K_RET, "Остаются активными через 30 дней", "%", 25, true, 1, 100),
            new ParamDef(K_MONTHS, "Сколько месяцев приносит доход активный игрок", "мес.", 3, false, 0.5, 24),
            new ParamDef(K_EARN, "Заработок активного игрока (для 10% реферера)", "EXC/мес.", 4_000, false, 0, 100_000),
            new ParamDef(K_ARPU, "Чистый доход с активного игрока", "₽/мес.", 0, true, 0.1, 1_000),
            new ParamDef(K_SAFETY, "Запас в рекомендованной цене", "%", 40, false, 0, 90),
            new ParamDef(K_REPEAT, "Новые зрители на каждом повторном упоминании", "%", 50, false, 0, 100));

    private final AppSettingRepository appSettingRepository;
    private final AppUserRepository appUserRepository;
    private final FinanceService financeService;
    private final RewardService rewardService;
    private final HealthRatioService healthRatioService;
    private final UserService userService;
    private final TrafficFunnelService trafficFunnelService;

    // ───────────────────────── допущения ─────────────────────────

    public record ParamView(ParamDef def, double value, String source) {}

    public record Assumptions(java.util.Map<String, ParamView> byKey, double rubPerExc, double incomeMonthRub, double payoutsMonthRub,
                              long mau, Double adCostPerQuester) {
        public double v(String key) { return byKey.get(key).value(); }
    }

    private Double stored(String key) {
        try {
            return appSettingRepository.findById(key).map(AppSetting::getValue).map(s -> Double.parseDouble(s.trim().replace(',', '.'))).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public Assumptions assumptions() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // Цена привлечения по закупам: сколько платим сейчас за игрока с 1-м одобренным квестом (только источники с расходом).
        long spend = 0, firstQuest = 0, clicks = 0, started = 0;
        try {
            for (TrafficFunnelService.SourceFunnel f : trafficFunnelService.compute()) {
                clicks += f.source().getClicks();
                started += f.started();
                if (f.source().getSpendRub() > 0 && f.firstQuest() > 0) {
                    spend += f.source().getSpendRub();
                    firstQuest += f.firstQuest();
                }
            }
        } catch (Exception e) {
            log.warn("Streamer calc: traffic funnel failed", e);
        }
        Double adCpa = firstQuest > 0 ? (double) spend / firstQuest : null;

        // Воронка «приглашённых по рефералке» за 90 дней (самый близкий аналог игрока от стримера).
        LocalDateTime cohortFrom = now.minusDays(COHORT_DAYS);
        long refBase = 0, refReg = 0, refQuest = 0;
        try {
            refBase = appUserRepository.countReferredCreatedSince(cohortFrom);
            refReg = appUserRepository.countReferredRegisteredSince(cohortFrom);
            refQuest = appUserRepository.countReferredQuestersSince(cohortFrom);
        } catch (Exception e) {
            log.warn("Streamer calc: referral cohort failed", e);
        }

        double ret30Auto = -1;
        try {
            UserService.RetentionReport rr = userService.retention();
            if (rr.cohort30() >= MIN_SAMPLE_FUNNEL) ret30Auto = rr.retained30() * 100.0 / rr.cohort30();
        } catch (Exception e) {
            log.warn("Streamer calc: retention failed", e);
        }

        // Чистый доход с активного игрока за месяц = (доходы − выплаты игрокам) / MAU; доходы вносятся в «💵 Доходы клуба».
        double income = 0, payouts = 0;
        long mau = 0;
        try {
            LocalDate from = today.minusDays(29);
            income = financeService.sumByKind(FinanceEntry.DIRECT_ADS, from, today) + financeService.sumByKind(FinanceEntry.YANDEX_RSYA, from, today)
                    + financeService.sumByKind(FinanceEntry.TELEGA, from, today)
                    + financeService.sumByKind(FinanceEntry.SPONSOR, from, today) + financeService.sumByKind(FinanceEntry.OTHER, from, today);
            RewardService.WithdrawalPeriodStats w = rewardService.withdrawalStatsSince(now.minusDays(30));
            payouts = w.totalRub() + w.totalTonRub();
            mau = userService.getEngagementReport(null).mau();
        } catch (Exception e) {
            log.warn("Streamer calc: P&L failed", e);
        }
        double arpuAuto = mau > 0 ? (income - payouts) / mau : 0;

        java.util.Map<String, ParamView> map = new java.util.LinkedHashMap<>();
        for (ParamDef p : PARAMS) {
            Double s = stored(p.key());
            double value;
            String source;
            if (s != null && (s > 0 || (!p.auto() && s >= p.min()))) {
                value = Math.max(p.min(), Math.min(p.max(), s));
                source = "настройка";
            } else if (p.auto()) {
                switch (p.key()) {
                    case K_START -> {
                        if (clicks >= MIN_CLICKS_SAMPLE) { value = clamp(started * 100.0 / clicks, 20, 95); source = "по данным (" + clicks + " кликов)"; }
                        else { value = p.def(); source = "по умолчанию (мало кликов)"; }
                    }
                    case K_REG -> {
                        if (refBase >= MIN_SAMPLE_FUNNEL) { value = clamp(refReg * 100.0 / refBase, 5, 95); source = "по данным (" + refBase + " приглашённых)"; }
                        else { value = p.def(); source = "по умолчанию (мало данных)"; }
                    }
                    case K_QUEST -> {
                        if (refReg >= 30) { value = clamp(refQuest * 100.0 / refReg, 5, 95); source = "по данным (" + refReg + " зарег.)"; }
                        else { value = p.def(); source = "по умолчанию (мало данных)"; }
                    }
                    case K_RET -> {
                        if (ret30Auto >= 0) { value = clamp(ret30Auto, 2, 95); source = "по данным (возврат 30 дн.)"; }
                        else { value = p.def(); source = "по умолчанию (мало данных)"; }
                    }
                    default -> { // K_ARPU
                        value = arpuAuto;
                        source = income <= 0 ? "доходы за 30 дней не внесены" : "по P&L за 30 дн.";
                    }
                }
            } else {
                value = p.def();
                source = "по умолчанию";
            }
            map.put(p.key(), new ParamView(p, value, source));
        }
        double rubPerExc = healthRatioService.getCurrentRatio() / 100.0; // 1 000 EXC = 10 ₽ × состояние фонда
        return new Assumptions(map, rubPerExc, income, payouts, mau, adCpa);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ───────────────────────── ввод ─────────────────────────

    public record Input(double ccv, double ruPct, double mentions, double fee, Double ctrOverride, Double monthsOverride, Double arpuOverride, boolean tracked) {}

    private static double num(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace("₽", "").replace("руб", "").replace("р", "").replace(" ", "").replace(',', '.');
        double mult = 1;
        if (s.endsWith("k") || s.endsWith("к")) { mult = 1_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("%")) { s = s.substring(0, s.length() - 1); }
        return Double.parseDouble(s) * mult;
    }

    /** Формат: «250 90 3 15000» (онлайн, % РФ/СНГ, упоминаний, цена ₽) или ключами: ccv=250 ru=90 m=3 fee=15к ctr=3 months=2 arpu=12 link=src. */
    public Input parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Пустой ввод.");
        double ccv = -1, ru = 90, m = 3, fee = 0;
        Double ctr = null, months = null, arpu = null;
        boolean tracked = false;
        int pos = 0;
        for (String tok : text.trim().split("[\\s;]+")) {
            if (tok.isBlank()) continue;
            try {
                int eq = tok.indexOf('=');
                if (eq > 0) {
                    String k = tok.substring(0, eq).toLowerCase(Locale.ROOT);
                    String v = tok.substring(eq + 1);
                    switch (k) {
                        case "ccv", "online", "онлайн" -> ccv = num(v);
                        case "ru", "рф", "geo" -> ru = num(v);
                        case "m", "mentions", "упом" -> m = num(v);
                        case "fee", "price", "цена" -> fee = num(v);
                        case "ctr" -> ctr = num(v);
                        case "months", "мес" -> months = num(v);
                        case "arpu" -> arpu = num(v);
                        case "link", "ссылка" -> tracked = v.toLowerCase(Locale.ROOT).startsWith("src") || v.toLowerCase(Locale.ROOT).startsWith("тр");
                        default -> throw new IllegalArgumentException("Неизвестный параметр «" + k + "».");
                    }
                } else {
                    double n = num(tok);
                    switch (pos++) {
                        case 0 -> ccv = n;
                        case 1 -> ru = n;
                        case 2 -> m = n;
                        case 3 -> fee = n;
                        default -> throw new IllegalArgumentException("Лишнее значение «" + tok + "».");
                    }
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Не понял число «" + tok + "».");
            }
        }
        if (ccv <= 0 || ccv > 200_000) throw new IllegalArgumentException("Средний онлайн (первое число) должен быть от 1 до 200 000.");
        if (ru < 1 || ru > 100) throw new IllegalArgumentException("Доля аудитории из РФ/СНГ — число от 1 до 100 (%).");
        if (m < 1 || m > 30) throw new IllegalArgumentException("Число упоминаний — от 1 до 30.");
        if (fee < 0) throw new IllegalArgumentException("Цена не может быть отрицательной.");
        return new Input(ccv, ru, m, fee, ctr, months, arpu, tracked);
    }

    // ───────────────────────── расчёт ─────────────────────────

    private record Funnel(double reach, double clicks, double starts, double registered, double questers, double retained) {}

    private Funnel funnel(Input in, Assumptions a, double ctrMult) {
        double ctr = (in.ctrOverride() != null ? in.ctrOverride() : a.v(K_CTR)) * ctrMult;
        double repeat = a.v(K_REPEAT) / 100.0;
        double reach = in.ccv() * in.ruPct() / 100.0 * (1 + repeat * Math.max(0, in.mentions() - 1));
        double clicks = reach * ctr / 100.0;
        double starts = clicks * a.v(K_START) / 100.0;
        double registered = starts * a.v(K_REG) / 100.0;
        double questers = registered * a.v(K_QUEST) / 100.0;
        double retained = registered * a.v(K_RET) / 100.0;
        return new Funnel(reach, clicks, starts, registered, questers, retained);
    }

    private double months(Input in, Assumptions a) { return in.monthsOverride() != null ? in.monthsOverride() : a.v(K_MONTHS); }

    private double arpu(Input in, Assumptions a) { return in.arpuOverride() != null ? in.arpuOverride() : Math.max(0, a.v(K_ARPU)); }

    /** Выручка от активных игроков за их «жизнь» в проекте (₽). */
    private double value(Funnel f, Input in, Assumptions a) { return f.retained() * arpu(in, a) * months(in, a); }

    /** Стоимость реферальной программы по ссылке стримера (₽): награды ему и его друзьям + 10% от заработка активных друзей. */
    private double referralCostRub(Funnel f, Input in, Assumptions a) {
        double exc = f.registered() * (REG_REFERRER_EXC + REG_FRIEND_EXC)
                + f.questers() * (FIRST_QUEST_REFERRER_EXC + FIRST_QUEST_FRIEND_EXC)
                + f.retained() * TRICKLE_SHARE * a.v(K_EARN) * months(in, a);
        return exc * a.rubPerExc();
    }

    private static String n(double v) {
        return String.format(Locale.forLanguageTag("ru"), "%,d", Math.round(v)).replace(' ', ' ').replace(' ', ' ');
    }

    private static String n1(double v) {
        return String.format(Locale.forLanguageTag("ru"), "%.1f", v);
    }

    public String report(Input in, Assumptions a) {
        Funnel base = funnel(in, a, 1.0);
        Funnel pess = funnel(in, a, 0.5);
        Funnel opt = funnel(in, a, 1.5);

        double valueBase = value(base, in, a);
        double refBase = referralCostRub(base, in, a);
        double maxFeeRef = valueBase - refBase;      // стример на личной реферальной ссылке (EXC ему и друзьям)
        double maxFeeSrc = valueBase;                // трекинговая ссылка src_ без реферальных EXC
        double maxFeePess = value(pess, in, a) - referralCostRub(pess, in, a);
        double maxFeeOpt = value(opt, in, a) - referralCostRub(opt, in, a);
        double safety = a.v(K_SAFETY);
        double recRef = Math.max(0, maxFeeRef * (1 - safety / 100.0));
        double recSrc = Math.max(0, maxFeeSrc * (1 - safety / 100.0));

        StringBuilder sb = new StringBuilder("🎙 <b>Расчёт сделки со стримером</b>\n");
        sb.append("Онлайн ").append(n(in.ccv())).append(" · РФ/СНГ ").append(n(in.ruPct())).append("% · упоминаний ").append(n(in.mentions()));
        if (in.fee() > 0) sb.append(" · просит <b>").append(n(in.fee())).append(" ₽</b>");
        sb.append("\n\n<b>Ожидаемая воронка (базовый сценарий)</b>\n");
        sb.append("👀 охват ").append(n(base.reach())).append(" → 👆 клики ").append(n1(base.clicks())).append(" → 🚀 /start ").append(n1(base.starts()))
          .append("\n✅ регистрации <b>").append(n1(base.registered())).append("</b> → 🎯 с 1-м квестом ").append(n1(base.questers()))
          .append(" → 🔥 активны через 30 дн. ").append(n1(base.retained())).append("\n");
        if (base.registered() < 1) {
            sb.append("\n⚠️ Меньше одной регистрации: аудитория слишком мала для фиксированной оплаты.\n");
        }

        sb.append("\n<b>Сколько проект может заплатить (фикс, ₽)</b>\n");
        sb.append("💰 Выручка от активных игроков: ").append(n(valueBase)).append(" ₽ (").append(n1(base.retained())).append(" × ").append(n1(arpu(in, a)))
          .append(" ₽/мес. × ").append(n1(months(in, a))).append(" мес.)\n");
        sb.append("🔗 Реферальные выплаты по его ссылке: −").append(n(refBase)).append(" ₽ (EXC ему и друзьям по курсу фонда)\n");
        sb.append("• <b>С личной реф-ссылкой:</b> безубыточный фикс <b>").append(n(Math.max(0, maxFeeRef))).append(" ₽</b>, рекомендую до <b>")
          .append(n(recRef)).append(" ₽</b> (запас ").append(n(safety)).append("%)\n");
        sb.append("• <b>С трекинговой ссылкой (без реф-EXC):</b> безубыточный фикс ").append(n(Math.max(0, maxFeeSrc))).append(" ₽, рекомендую до ")
          .append(n(recSrc)).append(" ₽\n");
        sb.append("📉 Если кликов вдвое меньше: ").append(n(Math.max(0, maxFeePess))).append(" ₽ · если в 1,5 раза больше: ").append(n(Math.max(0, maxFeeOpt))).append(" ₽\n");

        // Чистая выручка с одного зарегистрированного игрока для выбранного типа ссылки (реферальная — минус награды по ссылке).
        double refPerReg = (!in.tracked() && base.registered() > 0) ? refBase / base.registered() : 0;
        double unit = (a.v(K_RET) / 100.0) * arpu(in, a) * months(in, a) - refPerReg;
        double cpaReg = Math.max(0, unit * (1 - safety / 100.0));
        double questRate = a.v(K_QUEST) / 100.0;
        double cpaQuester = questRate > 0 ? cpaReg / questRate : 0;
        sb.append("\n🧮 <b>Оплата за результат (CPA):</b> до ").append(n(cpaReg)).append(" ₽ за регистрацию, до ").append(n(cpaQuester))
          .append(" ₽ за игрока с 1-м квестом — безопаснее фикса: платите только за реально пришедших.\n");
        if (a.adCostPerQuester() != null) {
            sb.append("🏷 По текущей цене закупов (").append(n(a.adCostPerQuester())).append(" ₽ за игрока с 1-м квестом) этот стример стоил бы ")
              .append(n(a.adCostPerQuester() * base.questers())).append(" ₽ за ожидаемых ").append(n1(base.questers())).append(" таких игроков.\n");
        }

        if (in.fee() > 0) {
            sb.append("\n<b>Вердикт по его цене ").append(n(in.fee())).append(" ₽</b>\n");
            double fee = in.fee();
            double feeForRef = in.tracked() ? maxFeeSrc : maxFeeRef;
            double rec = in.tracked() ? recSrc : recRef;
            if (feeForRef <= 0) {
                sb.append("❌ При текущих допущениях проект не зарабатывает на привлечённом игроке (чистый доход ")
                  .append(n1(arpu(in, a))).append(" ₽/мес.). Фикс платить нельзя; возможна только реф-ссылка без фикса.\n");
            } else if (fee <= rec) {
                sb.append("✅ В пределах рекомендованной цены. Окупается при ≥ ").append(unit > 0 ? n(Math.ceil(fee / unit)) : "—")
                  .append(" регистрациях (прогноз ").append(n1(base.registered())).append(").\n");
            } else if (fee <= feeForRef) {
                sb.append("⚠️ Выходит в ноль только в базовом сценарии, запаса нет. Торгуйтесь до ").append(n(rec)).append(" ₽ или платите частью после результата.\n");
            } else {
                sb.append("❌ Убыточно даже в базовом сценарии (максимум ").append(n(feeForRef)).append(" ₽). Предложите ≤ ").append(n(rec)).append(" ₽ либо оплату за результат.\n");
            }
            if (base.questers() > 0) {
                double perQuester = (fee + (in.tracked() ? 0 : refBase)) / base.questers();
                sb.append("💡 Цена привлечённого игрока с 1-м квестом: ").append(n(perQuester)).append(" ₽");
                if (a.adCostPerQuester() != null) sb.append(" (по закупам сейчас ").append(n(a.adCostPerQuester())).append(" ₽)");
                sb.append("\n");
            }
        }

        sb.append("\n<i>Допущения: клик ").append(n1(in.ctrOverride() != null ? in.ctrOverride() : a.v(K_CTR))).append("% зрителей, /start ").append(n(a.v(K_START)))
          .append("%, регистрация ").append(n(a.v(K_REG))).append("%, 1-й квест ").append(n(a.v(K_QUEST))).append("%, активны ").append(n(a.v(K_RET)))
          .append("%, чистый доход ").append(n1(arpu(in, a))).append(" ₽/мес., курс 1 000 EXC = ").append(n1(a.rubPerExc() * 1000)).append(" ₽. ");
        if (a.v(K_ARPU) <= 0 && in.arpuOverride() == null) {
            sb.append("Чистый доход с игрока за 30 дней не положителен или не внесён — внесите доходы в «💵 Доходы клуба» или задайте arpu= / в «⚙️ Допущения».");
        } else {
            sb.append("Меняются в «⚙️ Допущения».");
        }
        sb.append("</i>");
        return sb.toString();
    }

    public String configText(Assumptions a) {
        StringBuilder sb = new StringBuilder("⚙️ <b>Допущения калькулятора стримеров</b>\n\n");
        for (ParamView pv : a.byKey().values()) {
            sb.append("• ").append(pv.def().label()).append(": <b>").append(pv.def().key().equals(K_ARPU) && pv.value() <= 0 ? "нет данных" : n1(pv.value()))
              .append("</b> ").append(pv.def().unit()).append(" — <i>").append(pv.source()).append("</i>\n");
        }
        sb.append("\nДоход за 30 дней (внесённый): ").append(n(a.incomeMonthRub())).append(" ₽ · выплаты игрокам: ").append(n(a.payoutsMonthRub()))
          .append(" ₽ · MAU: ").append(n(a.mau())).append("\n");
        if (a.adCostPerQuester() != null) sb.append("Сейчас за закупы платим в среднем ").append(n(a.adCostPerQuester())).append(" ₽ за игрока с 1-м квестом.\n");
        sb.append("\nНажмите параметр, чтобы задать своё значение. <code>0</code> у параметров с данными проекта возвращает автоматический расчёт.");
        return sb.toString();
    }

    public List<ParamDef> editableParams() {
        return new ArrayList<>(PARAMS);
    }

    public ParamDef param(String key) {
        for (ParamDef p : PARAMS) if (p.key().equals(key)) return p;
        return null;
    }
}
