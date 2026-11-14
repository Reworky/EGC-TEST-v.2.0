package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.ExcWheelSpin;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.ExcWheelSpinRepository;

/**
 * «Колесо за EXC» (владелец, 13.11.2026): спин стоит {@link #PRICE} EXC, EXC уходят из обращения (тип SINK), призы - только предметы клуба, EXC в призах нет
 * (платный шанс на выводимый выигрыш был бы азартной игрой). Суточного лимита на спины нет: среднее по спину в пользу проекта, число спинов только увеличивает сток.
 *
 * Шансы (сумма 100) и ценность по прайсу магазина: билет бесплатного колеса 61% (300), купон -15% 20% (450), страховка провала 8% (1 500), XP-буст 24 ч 3% (3 000),
 * снятие кулдауна 3% (3 000), повторный спин 5% (бесплатный спин). Среднее около 600 EXC на спин по прайсу (60% цены); реальные расходы проекта - только билеты
 * (~183 EXC эмиссии на спин). Шансы показаны игроку: при правке менять и тексты в боте (GamePlatformBot.sendExWheelMenu).
 *
 * Выключено по умолчанию: app_settings fw.enabled = true включает (команда /excwheel on). fw.age18 (по умолчанию включено): колесо только игрокам, указавшим возраст 18+.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcWheelService {

    public static final long PRICE = 1_000;
    public static final int COUPON_PERCENT = 15;
    public static final int COUPON_DAYS = 14;
    private static final int MAX_RESPINS = 3;

    public static final String KEY_ENABLED = "fw.enabled";
    public static final String KEY_SLOT = "slot.enabled";
    public static final String KEY_AGE18 = "fw.age18";

    public static final String TICKET = "TICKET";
    public static final String COUPON = "COUPON";
    public static final String INSURANCE = "INSURANCE";
    public static final String XP_BOOST = "XP_BOOST";
    public static final String COOLDOWN = "COOLDOWN";
    public static final String RESPIN = "RESPIN";
    public static final String TICKET_FALLBACK = "TICKET_FALLBACK";

    private static final String[] TYPES = {TICKET, COUPON, INSURANCE, XP_BOOST, COOLDOWN, RESPIN};
    private static final int[] WEIGHTS = {61, 20, 8, 3, 3, 5};

    private static final Random RNG = new Random();

    private final AppUserRepository appUserRepository;
    private final ExcTransactionService excTx;
    private final ExcWheelSpinRepository spinRepository;
    private final AppSettingRepository appSettingRepository;

    public record Prize(String type, String label, String description) {}

    public record SpinResult(List<Prize> prizes, long spent, long balanceAfter) {}

    // ── Слот «3 катушки» (владелец, 14.11.2026) ─────────────────────────────────────────────────────────────────────────────────────────────
    // Спин SLOT_PRICE EXC, платит только тройка одинаковых: 🍌 (52%^3 = 14,06%) - снятие кулдауна (самая частая покупка в «Предметах»: 95 за 90 дн., 26 игроков),
    // 🍒 (38%^3 = 5,49%) - дополнительный слот квеста на 48 ч (вторая по частоте: 34 покупки, 18 игроков), 7️⃣ (10%^3 = 0,1%, 1 из 1 000) - EGC Pass на 7 дней.
    // Разные символы - приза нет. Среднее по спину по прайсу ≈ 617 EXC (кулдаун 3 000, слот 3 500, Pass 7 дн ≈ 2 700). Данные популярности - stats-shop-usage.sql (07.10.2026), обновлять.
    // Расход проекта: буст и кулдаун ≈ 0, Pass 7 дней консервативно ≈ 10 000 EXC (бонус +10% EXC до потолка, лимит вывода, донат по закупочной цене) x 0,001 = ≈ 10 EXC на спин,
    // то есть ~1% от сожжённых 1 000 EXC (сожжённое в ~100 раз больше). Защита: Pass не чаще раза в 30 дней на игрока и не более SLOT_PASS_PER_DAY в сутки на всех,
    // сверх этого вместо Pass выдаётся снятие кулдауна (или билет). Призы без EXC и без Stars. Шансы показаны игроку: при правке менять и экран слота в боте.
    public static final long SLOT_PRICE = 1_000;
    public static final String[] SLOT_SYMBOLS = {"🍌", "🍒", "7️⃣"};
    public static final int[] SLOT_WEIGHTS = {52, 38, 10};
    public static final int SLOT_PASS_DAYS = 7;
    public static final int SLOT_PASS_PER_DAY = 2;
    public static final int SLOT_PASS_PLAYER_DAYS = 30;
    public static final String SLOT_NONE = "SLOT_NONE";
    public static final String SLOT_EXTRA_SLOT = "SLOT_EXTRA_SLOT";
    public static final String SLOT_COOLDOWN = "SLOT_COOLDOWN";
    public static final String SLOT_PASS = "SLOT_PASS";
    public static final String SLOT_TICKET = "SLOT_TICKET";

    public record SlotResult(String[] reels, Prize prize, String note, long spent, long balanceAfter) {}

    public boolean isSlotEnabled() {
        return appSettingRepository.findById(KEY_SLOT).map(AppSetting::getValue).map(v -> "true".equalsIgnoreCase(v.trim())).orElse(false);
    }

    public String slotBlockReason(AppUser user) {
        if (!isSlotEnabled()) return "Слот сейчас недоступен.";
        if (isAgeGateOn()) {
            if (user.getAge() == null) return "Слот доступен с 18 лет. Укажи возраст в профиле («Расскажи о себе»), и он откроется.";
            if (user.getAge() < 18) return "Слот доступен с 18 лет.";
        }
        return null;
    }

    private int rollReel() {
        int r = RNG.nextInt(100);
        int acc = 0;
        for (int i = 0; i < SLOT_WEIGHTS.length; i++) {
            acc += SLOT_WEIGHTS[i];
            if (r < acc) return i;
        }
        return 0;
    }

    private boolean passJackpotAllowed(Long userId) {
        LocalDateTime dayStart = java.time.LocalDate.now().atStartOfDay();
        if (spinRepository.countByPrizeTypeAndCreatedAtAfter(SLOT_PASS, dayStart) >= SLOT_PASS_PER_DAY) return false;
        return spinRepository.countByUserIdAndPrizeTypeAndCreatedAtAfter(userId, SLOT_PASS, LocalDateTime.now().minusDays(SLOT_PASS_PLAYER_DAYS)) == 0;
    }

    /** Дополнительный слот квеста на 48 ч (как покупка в «Предметах»); если слот и так есть (навсегда, Pass или уже активен) - вместо него билет. */
    private Prize applyExtraSlot(AppUser user) {
        LocalDateTime now = LocalDateTime.now();
        boolean has = user.isPermanentExtraSlot()
                || (user.getEgcPassActiveUntil() != null && now.isBefore(user.getEgcPassActiveUntil()))
                || (user.getQuestSlotExtraUntil() != null && now.isBefore(user.getQuestSlotExtraUntil()));
        if (has) return ticketFallback(user);
        user.setQuestSlotExtraUntil(now.plusHours(48));
        return new Prize("EXTRA_SLOT", "📂 Дополнительный слот квеста на 48 часов", "Можно вести два квеста одновременно.");
    }

    private Prize applyPass(AppUser user) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cur = user.getEgcPassActiveUntil();
        user.setEgcPassActiveUntil((cur != null && now.isBefore(cur) ? cur : now).plusDays(SLOT_PASS_DAYS));
        return new Prize("PASS", "👑 EGC Pass на " + SLOT_PASS_DAYS + " дней", "Все перки Pass: +10% EXC за квесты, лимит вывода +10 000 EXC, 3-й слот и другое.");
    }

    @Transactional
    public SlotResult spinSlot(Long userId) {
        AppUser user = appUserRepository.findByIdForUpdate(userId).orElseThrow(() -> new IllegalArgumentException("Игрок не найден."));
        String block = slotBlockReason(user);
        if (block != null) throw new IllegalArgumentException(block);
        if (user.getCoins() < SLOT_PRICE) {
            throw new IllegalArgumentException("Недостаточно EXC. Нужно " + SLOT_PRICE + ", есть " + user.getCoins() + ".");
        }
        excTx.creditExc(user, -SLOT_PRICE, ExcTransactionService.SINK, "Слот");
        int a = rollReel(), b = rollReel(), c = rollReel();
        String[] reels = {SLOT_SYMBOLS[a], SLOT_SYMBOLS[b], SLOT_SYMBOLS[c]};
        Prize prize = null;
        String note = null;
        String recType = SLOT_NONE;
        if (a == b && b == c) {
            if (a == 2) {
                if (passJackpotAllowed(userId)) {
                    prize = applyPass(user);
                    recType = SLOT_PASS;
                } else {
                    note = "Джекпот на сегодня уже выдан или у тебя он был недавно, поэтому вместо него:";
                    prize = apply(user, COOLDOWN);
                    recType = COOLDOWN.equals(prize.type()) ? SLOT_COOLDOWN : SLOT_TICKET;
                }
            } else if (a == 1) {
                prize = applyExtraSlot(user);
                recType = "EXTRA_SLOT".equals(prize.type()) ? SLOT_EXTRA_SLOT : SLOT_TICKET;
            } else {
                prize = apply(user, COOLDOWN);
                recType = COOLDOWN.equals(prize.type()) ? SLOT_COOLDOWN : SLOT_TICKET;
            }
        }
        appUserRepository.save(user);
        record(userId, new Prize(recType, prize == null ? "Не повезло" : prize.label(), prize == null ? "" : prize.description()), SLOT_PRICE);
        return new SlotResult(reels, prize, note, SLOT_PRICE, user.getCoins());
    }

    /** Шансы по порядку призов для экрана игрока: {тип, шанс в процентах}. */
    public static int[] weights() {
        return WEIGHTS.clone();
    }

    public static String[] types() {
        return TYPES.clone();
    }

    public boolean isEnabled() {
        return appSettingRepository.findById(KEY_ENABLED).map(AppSetting::getValue).map(v -> "true".equalsIgnoreCase(v.trim())).orElse(false);
    }

    public boolean isAgeGateOn() {
        return appSettingRepository.findById(KEY_AGE18).map(AppSetting::getValue).map(v -> !"false".equalsIgnoreCase(v.trim())).orElse(true);
    }

    public void setSetting(String key, boolean value) {
        AppSetting s = new AppSetting();
        s.setKey(key);
        s.setValue(String.valueOf(value));
        appSettingRepository.save(s);
    }

    /** null - играть можно; иначе причина отказа для игрока. */
    public String blockReason(AppUser user) {
        if (!isEnabled()) return "Колесо за EXC сейчас недоступно.";
        if (isAgeGateOn()) {
            if (user.getAge() == null) return "Колесо доступно с 18 лет. Укажи возраст в профиле («Расскажи о себе»), и оно откроется.";
            if (user.getAge() < 18) return "Колесо доступно с 18 лет.";
        }
        return null;
    }

    @Transactional
    public SpinResult spin(Long userId) {
        AppUser user = appUserRepository.findByIdForUpdate(userId).orElseThrow(() -> new IllegalArgumentException("Игрок не найден."));
        String block = blockReason(user);
        if (block != null) throw new IllegalArgumentException(block);
        if (user.getCoins() < PRICE) {
            throw new IllegalArgumentException("Недостаточно EXC. Нужно " + PRICE + ", есть " + user.getCoins() + ".");
        }
        excTx.creditExc(user, -PRICE, ExcTransactionService.SINK, "Колесо за EXC");
        List<Prize> prizes = new ArrayList<>();
        int respins = 0;
        boolean first = true;
        while (true) {
            String type = roll(respins >= MAX_RESPINS);
            Prize prize = RESPIN.equals(type) ? new Prize(RESPIN, "🔁 Повторный спин", "Бесплатный спин сразу.") : apply(user, type);
            prizes.add(prize);
            record(user.getId(), prize, first ? PRICE : 0);
            first = false;
            if (!RESPIN.equals(type)) break;
            respins++;
        }
        appUserRepository.save(user);
        return new SpinResult(prizes, PRICE, user.getCoins());
    }

    private String roll(boolean noRespin) {
        int total = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            if (noRespin && RESPIN.equals(TYPES[i])) continue;
            total += WEIGHTS[i];
        }
        int r = RNG.nextInt(total);
        int acc = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            if (noRespin && RESPIN.equals(TYPES[i])) continue;
            acc += WEIGHTS[i];
            if (r < acc) return TYPES[i];
        }
        return TICKET;
    }

    private Prize apply(AppUser user, String type) {
        LocalDateTime now = LocalDateTime.now();
        switch (type) {
            case COUPON -> {
                boolean has = user.getShopCouponPercent() > 0 && user.getShopCouponUntil() != null && now.isBefore(user.getShopCouponUntil());
                if (has) return ticketFallback(user);
                user.setShopCouponPercent(COUPON_PERCENT);
                user.setShopCouponUntil(now.plusDays(COUPON_DAYS));
                return new Prize(COUPON, "🏷 Купон −" + COUPON_PERCENT + "%",
                        "Скидка " + COUPON_PERCENT + "% на ближайшую покупку в «Предметах клуба». Действует " + COUPON_DAYS + " дней.");
            }
            case INSURANCE -> {
                if (user.isRetryInsuranceActive()) return ticketFallback(user);
                user.setRetryInsuranceActive(true);
                return new Prize(INSURANCE, "🛡 Страховка провала", "Если следующий отчёт отклонят, его можно отправить повторно без штрафа.");
            }
            case XP_BOOST -> {
                LocalDateTime cur = user.getXpBoostActiveUntil();
                LocalDateTime from = cur != null && now.isBefore(cur) ? cur : now;
                user.setXpBoostActiveUntil(from.plusHours(24));
                return new Prize(XP_BOOST, "⚡ XP-буст на 24 часа", "Повышенный опыт за квесты в ближайшие сутки.");
            }
            case COOLDOWN -> {
                if (user.getCooldownBypassGame() != null) return ticketFallback(user);
                user.setCooldownBypassGame("ANY");
                user.setCooldownBypassFree(true);
                return new Prize(COOLDOWN, "⏱ Снятие кулдауна", "Ближайший квест можно взять без ожидания.");
            }
            default -> {
                user.setTickets(user.getTickets() + 1);
                return new Prize(TICKET, "🎟 Билет бесплатного колеса", "Можно крутить обычное колесо фортуны.");
            }
        }
    }

    /** Приз, который у игрока уже есть и не складывается (купон, страховка, кулдаун): вместо него билет, чтобы выпадение не пропадало впустую. */
    private Prize ticketFallback(AppUser user) {
        user.setTickets(user.getTickets() + 1);
        return new Prize(TICKET_FALLBACK, "🎟 Билет бесплатного колеса", "Выпал предмет, который у тебя уже есть, поэтому вместо него билет.");
    }

    private void record(Long userId, Prize prize, long cost) {
        ExcWheelSpin row = new ExcWheelSpin();
        row.setUserId(userId);
        row.setPrizeType(prize.type());
        row.setPrizeLabel(prize.label().length() > 100 ? prize.label().substring(0, 100) : prize.label());
        row.setCost(cost);
        spinRepository.save(row);
    }

    public record MyPrizes(List<ExcWheelSpin> last, long total, long spent) {}

    /** Для игрока: последние призы колеса (до 20), число спинов и сколько EXC потрачено. */
    @Transactional(readOnly = true)
    public MyPrizes myPrizes(Long userId) {
        return new MyPrizes(spinRepository.findTop20ByUserIdAndPrizeTypeNotOrderByCreatedAtDescIdDesc(userId, SLOT_NONE), spinRepository.countByUserId(userId), spinRepository.sumCostByUser(userId));
    }

    /** Для админа: сводка за days дней. */
    public String statsText(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        long spent = spinRepository.sumCostSince(since);
        long players = spinRepository.countPlayersSince(since);
        StringBuilder sb = new StringBuilder();
        sb.append("🎰 <b>Колесо за EXC: ").append(days).append(" дн.</b>\n\n");
        sb.append("Игроков: ").append(players).append("\nСожжено EXC (цена спинов): ").append(String.format("%,d", spent).replace(',', ' ')).append("\n");
        long totalPrizes = 0;
        long tickets = 0;
        StringBuilder byPrize = new StringBuilder();
        for (Object[] row : spinRepository.countByPrizeSince(since)) {
            String type = String.valueOf(row[0]);
            long n = ((Number) row[1]).longValue();
            totalPrizes += n;
            if (TICKET.equals(type) || TICKET_FALLBACK.equals(type)) tickets += n;
            byPrize.append("• ").append(type).append(": ").append(n).append("\n");
        }
        sb.append("Выдано призов: ").append(totalPrizes).append("\n").append(byPrize);
        long slotSpent = spinRepository.sumSlotCostSince(since);
        long slotPass = spinRepository.countByPrizeTypeAndCreatedAtAfter(SLOT_PASS, since);
        sb.append("\n🎰 Слот: сожжено ").append(String.format("%,d", slotSpent).replace(',', ' ')).append(" EXC, Pass выдан ").append(slotPass)
                .append(" раз (оценка расхода ≈ ").append(String.format("%,d", slotPass * 10_000).replace(',', ' ')).append(" EXC)\n");
        sb.append("\nБилетов выдано: ").append(tickets).append(" (оценка эмиссии при средней награде колеса 300 EXC: ≈ ")
                .append(String.format("%,d", tickets * 300).replace(',', ' ')).append(" EXC)\n");
        return sb.toString();
    }

    public List<Object[]> topPlayers(int days, int limit) {
        return spinRepository.topPlayersSince(LocalDateTime.now().minusDays(days), org.springframework.data.domain.PageRequest.of(0, limit));
    }
}
