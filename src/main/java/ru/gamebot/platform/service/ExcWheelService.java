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
        sb.append("\nБилетов выдано: ").append(tickets).append(" (оценка эмиссии при средней награде колеса 300 EXC: ≈ ")
                .append(String.format("%,d", tickets * 300).replace(',', ' ')).append(" EXC)\n");
        return sb.toString();
    }

    public List<Object[]> topPlayers(int days, int limit) {
        return spinRepository.topPlayersSince(LocalDateTime.now().minusDays(days), org.springframework.data.domain.PageRequest.of(0, limit));
    }
}
