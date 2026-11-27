package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.LeadgidConversion;
import ru.gamebot.platform.domain.model.QuestSkinReward;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSkinRewardRepository;

/**
 * Автовыдача скинов за партнёрские квесты (26.11.2026, решение владельца): скин покупается ТОЛЬКО после постбека LeadGid о том, что за этого игрока нам положена выплата.
 * Условия автозапуска (все сразу): статус постбека APPROVED («подлежит оплате»), оффер своего квеста (метка заявки на витрине общая для всех банков, чужой оффер скин не открывает),
 * выплата нам больше нуля, заявка на квест жива и взята после перехода на скин, награды этому игроку ещё не было и бонус по квесту не выплачивался. Затем игрок присылает ссылку обмена Steam,
 * бот покупает скин на market.csgo.com (buy-for, потолок цены) и после передачи начисляет компенсацию траты. Любое сомнение - MANUAL и алерт админам, деньги не тратятся.
 */
@Slf4j
@Service
public class QuestSkinService {

    /** Скин квеста: оффер LeadGid (null - постбека нет, выдачу открывает админ командой), market_hash_name, потолок закупки ₽ (≈ цена на 26.11.2026 + 20%; дороже - не покупаем,
     *  зовём менеджера), сумма «выбери любой другой скин до N ₽» из карточки. */
    public record Skin(String offerId, String hashName, int maxPriceRub, int choiceRub) {
        public String label() {
            return hashName.substring(0, hashName.lastIndexOf(" ("));
        }
    }

    public static final Map<String, Skin> SKINS = Map.of(
            "psb", new Skin("7249", "AK-47 | Searing Rage (Minimal Wear)", 600, 500),
            "alfa", new Skin("7049", "M4A1-S | Black Lotus (Field-Tested)", 360, 300),
            "fora", new Skin("6236", "FAMAS | Rapid Eye Movement (Field-Tested)", 460, 400),
            "tbankblack", new Skin("4001", "Desert Eagle | Firebreathing (Factory New)", 460, 400),
            "ubrr", new Skin("5393", "AWP | Green Energy (Field-Tested)", 570, 500),
            "vtb", new Skin("7040", "M4A1-S | Electrum (Factory New)", 570, 500),
            "otp", new Skin("6626", "AWP | Mortis (Field-Tested)", 450, 400),
            "tblack", new Skin(null, "AK-47 | Elite Build (Minimal Wear)", 300, 250));
    /** Ключ app_settings «когда квест переведён на скин»: заявки, взятые раньше, автоматически не выдаются (им обещана прежняя награда). */
    public static final String SWITCH_KEY_PREFIX = "quest.skin.switch.";
    private static final int MAX_ATTEMPTS = 3;

    private final QuestSkinRewardRepository repository;
    private final Cs2CaseAutoIssueService market;
    private final QuestService questService;
    private final ExcTransactionService excTransactionService;
    private final UserService userService;
    private final SponsorBalanceService sponsorBalanceService;
    private final AppUserRepository appUserRepository;
    private final AppSettingRepository appSettingRepository;
    private final GamePlatformBot bot;
    private final TransactionTemplate tx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Long> alerted = ConcurrentHashMap.newKeySet();

    public QuestSkinService(QuestSkinRewardRepository repository, Cs2CaseAutoIssueService market, QuestService questService,
                            ExcTransactionService excTransactionService, UserService userService, SponsorBalanceService sponsorBalanceService,
                            AppUserRepository appUserRepository, AppSettingRepository appSettingRepository,
                            @Lazy GamePlatformBot bot, PlatformTransactionManager txManager) {
        this.appSettingRepository = appSettingRepository;
        this.repository = repository;
        this.market = market;
        this.questService = questService;
        this.excTransactionService = excTransactionService;
        this.userService = userService;
        this.sponsorBalanceService = sponsorBalanceService;
        this.appUserRepository = appUserRepository;
        this.bot = bot;
        this.tx = new TransactionTemplate(txManager);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /** true - конверсия относится к квесту со скином-наградой (кнопка ручного начисления админу тогда не показывается при успешном автозапуске). */
    public static boolean isSkinQuest(PartnerMission pm) {
        return pm != null && SKINS.containsKey(pm.key());
    }

    /** Сколько рублей скин квеста добавляет в суммы «до N ₽»: сумма «любой другой скин до N ₽» из карточки; 0 - квест без скина. */
    public static int skinRub(PartnerMission pm) {
        return isSkinQuest(pm) ? SKINS.get(pm.key()).choiceRub() : 0;
    }

    /** Реакция на постбек. Возвращает строку для сообщения админам; started = автовыдача открыта (или уже была открыта). */
    public Result onConversion(LeadgidConversion c, QuestSubmission sub, PartnerMission pm) {
        try {
            if (!LeadgidConversion.APPROVED.equals(c.getStatusNorm())) return new Result(false, null);
            Skin skin = SKINS.get(pm.key());
            if (skin.offerId() == null) {
                return new Result(false, "⚠️ Скин по этому квесту открывается только командой /" + pm.bonusCommand() + " ID скин.");
            }
            if (c.getOfferId() == null || !skin.offerId().equals(c.getOfferId().trim())) {
                return new Result(false, "⚠️ Скин НЕ выдаётся автоматически: оффер в постбеке «" + c.getOfferId() + "», а нужен " + skin.offerId() + ".");
            }
            if (!positivePayout(c.getPayout())) {
                return new Result(false, "⚠️ Скин НЕ выдаётся автоматически: в постбеке нет суммы выплаты нам. Проверьте в кабинете LeadGid.");
            }
            if (sub.getStatus() == SubmissionStatus.CANCELLED || sub.getStatus() == SubmissionStatus.REJECTED) {
                return new Result(false, "⚠️ Скин НЕ выдаётся автоматически: заявка игрока отменена или отклонена.");
            }
            if (takenBeforeSwitch(pm, sub)) {
                return new Result(false, "⚠️ Скин НЕ выдаётся автоматически: заявка взята ДО перехода на скин, игроку обещана прежняя награда. Выдайте вручную, или откройте скин: /"
                        + pm.bonusCommand() + " " + sub.getUser().getTelegramId() + " скин");
            }
            return open(pm, sub, false);
        } catch (Exception e) {
            log.error("[QuestSkin] postback handling failed, conversion {}", c.getId(), e);
            return new Result(false, "❌ Автовыдача скина не запустилась: " + escape(e.getMessage()) + ". Проверьте вручную.");
        }
    }

    public record Result(boolean started, String adminNote) {}

    /** Выдача по команде админа («/…bonus ID скин» или кнопка начисления): админ сам проверил выплату. */
    public Result openByAdmin(PartnerMission pm, Long telegramId) {
        try {
            AppUser player = appUserRepository.findByTelegramId(telegramId).orElse(null);
            if (player == null) return new Result(false, "⚠️ Игрок с таким ID не найден.");
            QuestSubmission sub = questService.findLatestPartnerSubmission(player.getId(), pm.gameName());
            if (sub == null) return new Result(false, "⚠️ У игрока нет заявки по этому квесту.");
            return open(pm, sub, true);
        } catch (Exception e) {
            log.error("[QuestSkin] admin open failed, player {}", telegramId, e);
            return new Result(false, "❌ Не получилось открыть скин: " + escape(e.getMessage()));
        }
    }

    private boolean takenBeforeSwitch(PartnerMission pm, QuestSubmission sub) {
        if (sub.getCreatedAt() == null) return false;
        try {
            return appSettingRepository.findById(SWITCH_KEY_PREFIX + pm.key())
                    .map(a -> sub.getCreatedAt().isBefore(LocalDateTime.parse(a.getValue()))).orElse(false);
        } catch (Exception e) {
            log.warn("[QuestSkin] switch time unreadable for {}", pm.key(), e);
            return true; // сомнение - не тратим деньги автоматически
        }
    }

    private Result open(PartnerMission pm, QuestSubmission sub, boolean byAdmin) {
        Skin skin = SKINS.get(pm.key());
        AppUser player = sub.getUser();
        Long playerId = player.getId();
        Long playerTg = player.getTelegramId();
        String key = pm.key() + ":" + playerId;
        if (repository.findByRewardKey(key).isPresent()) {
            return new Result(true, "ℹ️ Скин этому игроку уже открыт, повторно не выдаётся.");
        }
        // У партнёрского квеста (ОТП) отметка выплаты принадлежит команде /otpbonus (статус партнёра), скину она не мешает
        if (!PartnerMissions.grantsPartner(pm) && questService.isPartnerBonusPaid(playerId, pm.gameName())) {
            return new Result(false, "ℹ️ Награда по этому квесту игроку уже выдавалась, скин не открываю.");
        }
        QuestSkinReward r = new QuestSkinReward();
        r.setRewardKey(key);
        r.setSubmissionId(sub.getId());
        r.setUserId(playerId);
        r.setTelegramId(playerTg);
        r.setStatus(QuestSkinReward.WAIT_LINK);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        try {
            r = repository.saveAndFlush(r);
        } catch (DataIntegrityViolationException duplicate) {
            return new Result(true, "ℹ️ Скин этому игроку уже открыт, повторно не выдаётся.");
        }
        bot.questSkinAskLink(playerTg, r.getId(), "🎁 <b>" + (byAdmin ? "Скин твой!" : "Банк подтвердил карту: скин твой!") + "</b>\n\n"
                + "📦 " + escape(skin.hashName()) + "\n\n"
                + "<b>Пришли сюда ссылку обмена Steam одним сообщением.</b>\nSteam → Инвентарь → Предложения обмена → «Кто может отправлять мне предложения обмена?».\n\n"
                + "Уже есть такой скин? Нажми «Хочу другой скин».");
        return new Result(true, "🤖 Скин выдаётся автоматически: у игрока запрошена ссылка обмена. Кнопку начисления нажимать не нужно."
                + (PartnerMissions.grantsPartner(pm) ? " Статус партнёра и вывод откройте, когда банк выплатит игроку 1 000 ₽: <code>/" + pm.bonusCommand() + " " + playerTg + "</code>" : ""));
    }

    private static PartnerMission missionOf(QuestSkinReward r) {
        String key = r.getRewardKey().substring(0, r.getRewardKey().indexOf(':'));
        return PartnerMissions.ALL.stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    /** Сообщение игрока со ссылкой обмена. true - сообщение относилось к скину-награде и обработано. */
    public boolean submitLink(Long telegramId, String text) {
        if (Cs2CaseAutoIssueService.parseTradeUrl(text) == null) return false;
        QuestSkinReward r = repository.findAllByTelegramIdAndStatus(telegramId, QuestSkinReward.WAIT_LINK).stream().findFirst().orElse(null);
        if (r == null) return false;
        Long id = r.getId();
        String url = text.trim();
        if (url.length() > 300) url = url.substring(0, 300);
        if (!market.isMarketConfigured()) {
            if (move(id, QuestSkinReward.WAIT_LINK, QuestSkinReward.MANUAL)) {
                saveUrl(id, url, false);
                bot.caseAutoNotify(telegramId, "✅ Ссылку получили. Скин отправит менеджер вручную.",
                        "⚠️ Скин за партнёрский квест игроку " + telegramId + ": нет ключа market.csgo.com, выдайте вручную.\nСсылка: " + escape(url));
            }
            return true;
        }
        if (!move(id, QuestSkinReward.WAIT_LINK, QuestSkinReward.BUYING)) return true;
        saveUrl(id, url, true);
        bot.caseAutoNotify(telegramId, "⏳ Ссылку получили, отправляем скин.", null);
        executor.submit(() -> buy(id));
        return true;
    }

    /** «Хочу другой скин»: замену подбирает и покупает менеджер вручную. */
    public void wantOther(Long telegramId, Long rewardId) {
        QuestSkinReward r = repository.findById(rewardId).orElse(null);
        if (r == null || !r.getTelegramId().equals(telegramId)) return;
        if (!move(rewardId, QuestSkinReward.WAIT_LINK, QuestSkinReward.MANUAL)) {
            bot.caseAutoNotify(telegramId, "Этот скин уже в обработке.", null);
            return;
        }
        PartnerMission pm = missionOf(r);
        int limit = SKINS.get(pm.key()).choiceRub();
        bot.caseAutoNotify(telegramId, "✅ Напиши менеджеру, какой скин до " + limit + " ₽ хочешь, и пришли ссылку обмена Steam. Он проверит цену и отправит скин.",
                "🔄 Игрок " + telegramId + " по квесту «" + escape(pm.shortTitle()) + "» хочет ДРУГОЙ скин (до " + limit + " ₽ по market.csgo.com). Купите вручную, потом: <code>/"
                        + pm.bonusCommand() + " " + telegramId + " вручную</code>");
    }

    private void buy(Long id) {
        QuestSkinReward r = repository.findById(id).orElse(null);
        if (r == null || !QuestSkinReward.BUYING.equals(r.getStatus())) return;
        try {
            String[] link = Cs2CaseAutoIssueService.parseTradeUrl(r.getTradeUrl());
            Skin skin = SKINS.get(missionOf(r).key());
            String customId = customId(r);
            JsonNode existing = market.marketCall("get-buy-info-by-custom-id", Map.of("custom_id", customId));
            if (existing != null && existing.path("success").asBoolean(false)) return; // покупка уже есть, статус заберёт опрос
            Map<String, String> params = new LinkedHashMap<>();
            params.put("hash_name", skin.hashName());
            params.put("price", String.valueOf(skin.maxPriceRub() * 100));
            params.put("partner", link[0]);
            params.put("token", link[1]);
            params.put("custom_id", customId);
            JsonNode res = market.marketCall("buy-for", params);
            if (res == null) {
                // Ответа нет, но покупка могла пройти: не повторяем и не отменяем, разбирается опросом по custom_id
                log.warn("[QuestSkin] no answer from market for reward {}", id);
                return;
            }
            if (res.path("success").asBoolean(false)) {
                bot.caseAutoNotify(r.getTelegramId(), "🎁 <b>Скин уже в пути</b>\n\n<b>Что сделать сейчас:</b>\n1️⃣ Открой Steam → «Предложения обмена»\n2️⃣ Нажми «Принять» в течение <b>5 минут</b>", null);
                return;
            }
            int code = res.path("code").asInt(0);
            if (code == 3 || code == 5 || code == 6 || code == 7 || code == 12 || code == 21) {
                retryOrManual(r, "Steam не принял ссылку или аккаунт не готов к обмену: проверь ссылку, открой инвентарь, включи мобильный аутентификатор.");
                return;
            }
            toManual(r, "buy-for отказал (code " + code + "): " + res.path("error").asText("") + ", " + skin.hashName() + ", потолок " + skin.maxPriceRub() + " ₽");
        } catch (Exception e) {
            log.error("[QuestSkin] buy failed for reward {}", id, e);
            alert("❌ Скин за партнёрский квест, награда #" + id + ": сбой покупки (" + escape(e.getMessage()) + "). Проверьте на market.csgo.com, не ушла ли покупка.");
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 90_000)
    public void poll() {
        if (!market.isMarketConfigured()) return;
        for (QuestSkinReward r : repository.findAllByStatus(QuestSkinReward.BUYING)) {
            try {
                check(r);
            } catch (Exception e) {
                log.warn("[QuestSkin] check failed for reward {}", r.getId(), e);
            }
        }
    }

    private void check(QuestSkinReward r) {
        JsonNode info = market.marketCall("get-buy-info-by-custom-id", Map.of("custom_id", customId(r)));
        if (info == null) return;
        if (!info.path("success").asBoolean(false)) {
            if (r.getUpdatedAt() != null && r.getUpdatedAt().isBefore(LocalDateTime.now().minusMinutes(15))) {
                toManual(r, "покупки на маркетплейсе нет спустя 15 минут");
            }
            return;
        }
        JsonNode data = info.path("data");
        int stage = data.path("stage").asInt(0);
        long receiveUntil = data.path("receive_until").asLong(0);
        long now = System.currentTimeMillis() / 1000;
        String cancellation = data.path("cancellation_reason").isNull() ? "" : data.path("cancellation_reason").asText("");
        if (stage == 5 || !cancellation.isEmpty()) {
            retryOrManual(r, "Обмен отменён: он не был принят за 5 минут или продавец отказался.");
        } else if (stage == 2 || (receiveUntil > 0 && now > receiveUntil + 120 && data.path("settlement").asLong(0) > 0)) {
            complete(r, data.path("paid").asDouble(0)); // признак успеха тот же, что у автовыдачи кейсов
        } else if (r.getUpdatedAt() != null && r.getUpdatedAt().isBefore(LocalDateTime.now().minusHours(12)) && alerted.add(r.getId())) {
            alert("⚠️ Скин за партнёрский квест, награда #" + r.getId() + ": не передан больше 12 часов. Проверьте на market.csgo.com.");
        }
    }

    private void complete(QuestSkinReward r, double paidRub) {
        PartnerMission pm = missionOf(r);
        boolean partnerQuest = PartnerMissions.grantsPartner(pm);
        long[] compensated = {0};
        try {
            Boolean mine = tx.execute(st -> {
                if (repository.moveStatus(r.getId(), QuestSkinReward.BUYING, QuestSkinReward.DONE, LocalDateTime.now()) != 1) return false;
                if (pm.compensationExc() > 0 && !questService.isPartnerBonusPaid(r.getUserId(), pm.gameName())) {
                    AppUser user = appUserRepository.findById(r.getUserId()).orElseThrow();
                    String reason = "Компенсация траты по квесту: " + pm.shortTitle();
                    if (sponsorBalanceService.enabled()) {
                        excTransactionService.creditSponsor(user, pm.compensationExc(), ExcTransactionService.BONUS, reason);
                    } else {
                        userService.addReward(user, 0, pm.compensationExc(), ExcTransactionService.BONUS, reason);
                    }
                    compensated[0] = pm.compensationExc();
                }
                if (!partnerQuest) questService.markPartnerBonusPaid(r.getUserId(), pm.gameName());
                return true;
            });
            if (!Boolean.TRUE.equals(mine)) return;
        } catch (Exception e) {
            log.error("[QuestSkin] completion failed for reward {}", r.getId(), e);
            if (alerted.add(-r.getId())) {
                alert("❌ Скин за партнёрский квест передан, но компенсацию начислить не удалось (награда #" + r.getId() + ", игрок " + r.getTelegramId() + "): " + escape(e.getMessage()));
            }
            return;
        }
        String unit = sponsorBalanceService.enabled() ? " EXCASH" : " EXC";
        bot.caseAutoNotify(r.getTelegramId(), "🏆 <b>Скин выдан!</b>\n\n" + escape(SKINS.get(pm.key()).label()) + " у тебя в Steam."
                        + (compensated[0] > 0 ? "\n💸 И ещё <b>+" + fmt(compensated[0]) + unit + "</b>"
                        + (sponsorBalanceService.enabled() ? " (≈ " + fmt(compensated[0] / 100) + " ₽): выводятся на карту или по СБП без лимитов." : " на баланс: компенсация траты.") : ""),
                "✅ <b>Скин за квест «" + escape(pm.shortTitle()) + "» выдан</b>\n👤 " + r.getTelegramId() + (paidRub > 0 ? "\n🛒 Закупка: " + paidRub + " ₽" : "")
                        + (compensated[0] > 0 ? "\n💸 Компенсация: " + fmt(compensated[0]) + unit : ""));
    }

    private void retryOrManual(QuestSkinReward r, String playerText) {
        if (r.getAttempt() >= MAX_ATTEMPTS) {
            toManual(r, "три неудачные попытки. Последняя: " + playerText);
            return;
        }
        if (!move(r.getId(), QuestSkinReward.BUYING, QuestSkinReward.WAIT_LINK)) return;
        bot.questSkinAskLink(r.getTelegramId(), r.getId(), "❌ <b>Скин не передан</b>\n\n" + escape(playerText) + "\n\nПришли ссылку обмена Steam ещё раз.");
        alert("⚠️ Скин за партнёрский квест, игрок " + r.getTelegramId() + ": попытка " + r.getAttempt() + " не удалась, запрошена ссылка заново.");
    }

    private void toManual(QuestSkinReward r, String reason) {
        if (!move(r.getId(), QuestSkinReward.BUYING, QuestSkinReward.MANUAL)) return;
        bot.caseAutoNotify(r.getTelegramId(), "⏳ Автоматически отправить скин не получилось. Менеджер отправит его вручную, напиши ему.",
                "⚠️ Скин за партнёрский квест, игрок " + r.getTelegramId() + ": выдайте ВРУЧНУЮ (сначала проверьте на market.csgo.com, не прошла ли покупка), потом <code>/" + missionOf(r).bonusCommand() + " "
                        + r.getTelegramId() + " вручную</code>.\nПричина: " + escape(reason));
    }

    private boolean move(Long id, String from, String to) {
        Integer moved = tx.execute(st -> repository.moveStatus(id, from, to, LocalDateTime.now()));
        return moved != null && moved == 1;
    }

    private void saveUrl(Long id, String url, boolean newAttempt) {
        tx.executeWithoutResult(st -> repository.findById(id).ifPresent(x -> {
            x.setTradeUrl(url);
            if (newAttempt) x.setAttempt(x.getAttempt() + 1);
            repository.save(x);
        }));
    }

    private static String customId(QuestSkinReward r) {
        return "egcq-" + r.getId() + "-" + r.getAttempt();
    }

    private static boolean positivePayout(String payout) {
        if (payout == null) return false;
        try {
            return Double.parseDouble(payout.trim().replace(",", ".")) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void alert(String text) {
        try {
            bot.caseAutoNotify(null, null, text);
        } catch (Exception e) {
            log.error("[QuestSkin] alert failed: {}", text, e);
        }
    }

    private static String fmt(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
