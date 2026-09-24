package ru.gamebot.platform.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.SupportTicket;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.ExcTransactionRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.RewardRequestRepository;
import ru.gamebot.platform.domain.repository.SquadRepository;
import ru.gamebot.platform.domain.repository.SupportAttachmentRepository;
import ru.gamebot.platform.domain.repository.SupportTicketRepository;
import ru.gamebot.platform.event.LeagueRewardEvent;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final ExcTransactionService excTx;
    private final ReferralBoostService referralBoostService;

    private static final List<LevelTier> LEVEL_TIERS = List.of(
            new LevelTier(1, "Новичок", 0, 0),
            new LevelTier(2, "Игрок", 1_000, 5),
            new LevelTier(3, "Ветеран", 5_000, 10),
            new LevelTier(4, "Элита", 15_000, 15),
            new LevelTier(5, "Легенда", 35_000, 20),
            new LevelTier(6, "Герой EXPERIENCE", 75_000, 25),
            new LevelTier(7, "Чемпион EXPERIENCE", 150_000, 30),
            new LevelTier(8, "Амбассадор EXPERIENCE", 300_000, 50)
    );

    public enum League {
        BRONZE("🥉 Бронза",    0,    0),
        SILVER("🥈 Серебро",   250,  300),
        GOLD  ("🥇 Золото",    600,  800),
        PLAT  ("💎 Платина",  1500, 2000),
        ELITE ("👑 Элита",    3500, 5000);

        public final String displayName;
        public final int minWeeklyXp;
        public final long excPrize;

        League(String displayName, int minWeeklyXp, long excPrize) {
            this.displayName = displayName;
            this.minWeeklyXp = minWeeklyXp;
            this.excPrize = excPrize;
        }
    }

    public static League getLeague(long weeklyXp) {
        League result = League.BRONZE;
        for (League l : League.values()) {
            if (weeklyXp >= l.minWeeklyXp) result = l;
        }
        return result;
    }

    private final AppUserRepository appUserRepository;
    private final ExcTransactionRepository excTransactionRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final RewardRequestRepository rewardRequestRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SupportAttachmentRepository supportAttachmentRepository;
    private final WheelService wheelService;
    private final AppProperties appProperties;
    private final SquadRepository squadRepository;

    /** Варианты текста для кнопки «Поделиться» (Telegram share-ссылка) — случайный выбор при каждом
     *  построении, чтобы сообщения разных отправителей не выглядели как рассылка одного шаблона. */
    private static final String[] SHARE_TEMPLATES = {
            "Залетай в EGC — там реально платят за игру 🎮 У меня уже {баланс_EXC} EXC, ранг «{ранг}».",
            "Я в EGC уже «{ранг}» и заработал {баланс_EXC} EXC просто за квесты в играх. Присоединяйся:",
            "Нашёл клуб, где платят EXC за прохождение квестов в играх — уже накопил {баланс_EXC}, ранг «{ранг}». Залетай:"
    };

    /** Вариант для игроков с отрядом — акцент на "играть вместе", а не на разовый бонус
     *  (слияние "Поделиться" с приглашением в отряд, см. combined deep-link ref_<id>_sq_<code>). */
    private static final String[] SQUAD_SHARE_TEMPLATES = {
            "Собираю команду в EGC — я уже «{ранг}», {баланс_EXC} EXC на счету. Врывайся в мой отряд, будем зарабатывать вместе:",
            "У меня отряд в EGC, играем вместе за EXC. Присоединяйся — сразу окажешься в команде:",
            "Ищу людей в отряд EGC — топ-отряд недели получает 10 000 EXC. Заходи, играем вместе:"
    };

    /** Голая реферальная ссылка (для копирования и для отображения в UI) — ref_<id>, либо
     *  комбинированная ref_<id>_sq_<inviteCode>, если у отправителя есть активный отряд. Единая точка
     *  построения — переиспользуется buildShareUrl() и ReferralController (бот и мини-апп не должны
     *  каждый по-своему собирать эту ссылку, иначе легко разойтись, как уже случилось с мини-аппом). */
    public String buildReferralLink(AppUser user) {
        String referralLink = "https://t.me/" + appProperties.getBotUsername() + "?start=ref_" + user.getTelegramId();
        if (user.getSquadId() != null) {
            String inviteCode = squadRepository.findById(user.getSquadId())
                    .filter(s -> "ACTIVE".equals(s.getStatus()))
                    .map(ru.gamebot.platform.domain.model.Squad::getInviteCode)
                    .orElse(null);
            if (inviteCode != null) {
                referralLink = referralLink + "_sq_" + inviteCode;
            }
        }
        return referralLink;
    }

    /** Ссылка вида t.me/share/url?... — открывает нативный пикер пересылки Telegram с готовым текстом
     *  и реферальной ссылкой, без специального Bot API метода (просто обычная URL-кнопка). Если у
     *  отправителя есть отряд — ссылка комбинированная (ref_<id>_sq_<code>), и новый игрок после
     *  завершения регистрации автоматически вступает в тот же отряд (см. GamePlatformBot.consumePendingSquadInvite). */
    public String buildShareUrl(AppUser user) {
        String referralLink = buildReferralLink(user);
        String[] templates = referralLink.contains("_sq_") ? SQUAD_SHARE_TEMPLATES : SHARE_TEMPLATES;
        String template = templates[ThreadLocalRandom.current().nextInt(templates.length)];
        String text = template
                .replace("{баланс_EXC}", String.valueOf(user.getCoins()))
                .replace("{ранг}", getLevelName(user.getXp()));
        return "https://t.me/share/url?url=" + encodeUrlComponent(referralLink)
                + "&text=" + encodeUrlComponent(text);
    }

    /** URLEncoder кодирует пробел как '+' (правило application/x-www-form-urlencoded) — Telegram при
     *  открытии t.me/share/url его обратно в пробел не разворачивает, и в тексте показываются буквально
     *  плюсы вместо пробелов. Нужен настоящий percent-encoding (пробел → %20), поэтому докручиваем вручную. */
    private static String encodeUrlComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Transactional
    public AppUser getOrCreate(User telegramUser, Long referredByTelegramId) {
        Optional<AppUser> existing = appUserRepository.findByTelegramId(telegramUser.getId());
        if (existing.isPresent()) {
            AppUser user = existing.get();
            updateTelegramProfile(user, telegramUser);
            return appUserRepository.save(user);
        }

        AppUser user = new AppUser();
        user.setTelegramId(telegramUser.getId());
        user.setReferredByTelegramId(resolveReferral(telegramUser.getId(), referredByTelegramId));
        user.setXp(0);
        user.setWeeklyXp(0);
        user.setCoins(0);
        user.setTickets(0);
        user.setCompletedQuests(0);
        user.setInvitedFriends(0);
        user.setStreakDays(0);
        user.setProfileCompleted(false);
        user.setRegistrationCompleted(false);
        user.setWelcomeShown(false);
        user.setStaffRole("USER");
        user.setCreatedAt(LocalDateTime.now());
        java.time.YearMonth now = java.time.YearMonth.now();
        user.setWithdrawalMonth(now.getMonthValue());
        user.setWithdrawalYear(now.getYear());
        user.setMonthlyWithdrawnExc(0);
        updateTelegramProfile(user, telegramUser);
        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser save(AppUser user) {
        return appUserRepository.save(user);
    }

    /** Выдаёт фиолетовую рамку аватара «EGC» — та же логика, что и приз колеса фортуны
     * (WheelService.spin, тип AVATAR_FRAME), переиспользуется для прямой покупки за Telegram Stars. */
    @Transactional
    public void grantEgcAvatarFrame(AppUser user) {
        user.setAvatarFrameColor("#7C3AED");
        user.setAvatarFrameImage("egc");
        String csv = user.getOwnedFramesCsv();
        if (csv == null || csv.isBlank()) {
            user.setOwnedFramesCsv("egc");
        } else if (!Arrays.asList(csv.split(",")).contains("egc")) {
            user.setOwnedFramesCsv(csv + ",egc");
        }
        appUserRepository.save(user);
    }

    /** Выдаёт эксклюзивный титул «Покровитель EGC» — второй Stars-товар после рамки (2026-09-15),
     * тот же принцип: чистая косметика/статус, без влияния на EXC-экономику. В отличие от обычных
     * EXC-титулов (SinkShopService.purchaseTitle — их можно перекупать сколько угодно раз, деньги
     * просто списываются заново), владение фиксируется в ownedTitlesCsv, чтобы нельзя было оплатить
     * второй раз то, что уже куплено — так же, как с рамками. Сразу надевается поверх текущего титула. */
    @Transactional
    public void grantPatronTitle(AppUser user) {
        user.setProfileTitle("💎 Покровитель EGC");
        String csv = user.getOwnedTitlesCsv();
        if (csv == null || csv.isBlank()) {
            user.setOwnedTitlesCsv("patron");
        } else if (!Arrays.asList(csv.split(",")).contains("patron")) {
            user.setOwnedTitlesCsv(csv + ",patron");
        }
        appUserRepository.save(user);
    }

    /** Выдаёт доп. слот квеста навсегда — четвёртый Stars-товар (2026-09-15), первый сервисный
     * (не косметика/статус): постоянная версия существующего временного буста за EXC (48ч,
     * SinkShopService.purchaseExtraSlot). Продаёт "удобство/скорость", не влияет на размер наград —
     * тот же принцип "не pay-to-win", что и у остальных Stars-товаров. */
    @Transactional
    public void grantPermanentExtraSlot(AppUser user) {
        user.setPermanentExtraSlot(true);
        appUserRepository.save(user);
    }

    /** «EGC Pass» — первая recurring-подписка проекта (Telegram Stars subscription, 30 дней,
     * 2026-09-15): единственный Stars-товар, который сам собой приносит доход каждый месяц, а не
     * разово. Telegram шлёт successful_payment одинаково и на первую оплату, и на каждое
     * автопродление — поэтому продлеваем от максимума(сейчас, текущий срок действия), а не просто
     * "+30 дней от текущего момента": если игрок продлил чуть раньше срока (или Telegram прислал
     * платёж с небольшой задержкō после истечения), дни не должны теряться/задваиваться. */
    @Transactional
    public void renewEgcPass(AppUser user) {
        LocalDateTime base = isEgcPassActive(user) ? user.getEgcPassActiveUntil() : LocalDateTime.now();
        user.setEgcPassActiveUntil(base.plusDays(30));
        appUserRepository.save(user);
    }

    public boolean isEgcPassActive(AppUser user) {
        return user.getEgcPassActiveUntil() != null && LocalDateTime.now().isBefore(user.getEgcPassActiveUntil());
    }

    /** +10% к EXC-награде за квесты, пока активна подписка EGC Pass — с помесячным потолком, иначе
     *  активный фармер получает от процента в разы больше EXC, чем редкий игрок, за одну и ту же
     *  подписку (тот же риск, что решали кривой убывания на decay-квестах). Потолок посчитан от цены
     *  подписки (150⭐ ≈ 236₽ ≈ 23 600 EXC-эквивалента) за вычетом уже занятого улучшенным сундуком
     *  бюджета (~8 550 EXC/мес), с запасом ниже расчётного предела (обсуждение 2026-09-16). */
    public static final int EGC_PASS_BOOST_PCT = 10;
    /** +N% к XP за квесты для подписчиков EGC Pass (2026-09-24, решение владельца: «немного, чтобы не
     *  ударило по экономике»). Умышленно вдвое меньше XP-буста сезонного Battle Pass (по умолчанию 10%).
     *  XP — не валюта, но от него зависят уровень (бонус к EXC-наградам и месячный лимит вывода) и
     *  недельный рейтинг, поэтому цифра скромная. Аддитивен к остальным XP-бустам (SinkShop, Battle Pass). */
    public static final int EGC_PASS_XP_BOOST_PCT = 5;
    public static final long EGC_PASS_BOOST_MONTHLY_CAP_EXC = 10_000L;

    /** Сколько ещё EXC можно начислить бонусом EGC Pass в текущем календарном месяце — сбрасывает
     *  счётчик при смене месяца (тот же паттерн, что и SinkShopService.refreshWithdrawalMonthIfNeeded).
     *  Безопасно вызывать многократно для превью — сам не инкрементирует счётчик, только читает остаток. */
    @Transactional
    public long egcPassBoostRemainingThisMonth(AppUser user) {
        java.time.YearMonth now = java.time.YearMonth.now();
        if (user.getEgcPassBoostMonth() != now.getMonthValue() || user.getEgcPassBoostYear() != now.getYear()) {
            user.setEgcPassBoostMonthlyExc(0);
            user.setEgcPassBoostMonth(now.getMonthValue());
            user.setEgcPassBoostYear(now.getYear());
            appUserRepository.save(user);
        }
        return Math.max(0, EGC_PASS_BOOST_MONTHLY_CAP_EXC - user.getEgcPassBoostMonthlyExc());
    }

    /** Фиксирует реально начисленный бонус EGC Pass против месячного потолка — вызывать РОВНО ОДИН РАЗ,
     *  в момент фактического начисления награды (QuestService.approveSubmission), не в превью. */
    @Transactional
    public void recordEgcPassBoost(AppUser user, long bonusExc) {
        if (bonusExc <= 0) return;
        user.setEgcPassBoostMonthlyExc(user.getEgcPassBoostMonthlyExc() + bonusExc);
        appUserRepository.save(user);
    }

    public Optional<AppUser> findByTelegramId(Long telegramId) {
        return appUserRepository.findByTelegramId(telegramId);
    }

    public Optional<AppUser> findById(Long id) {
        return appUserRepository.findById(id);
    }

    public Optional<AppUser> findDuplicatePhoneUser(String phoneNumber, Long excludeTelegramId) {
        if (phoneNumber == null || phoneNumber.isBlank()) return Optional.empty();
        return appUserRepository.findByPhoneNumberAndTelegramIdNot(phoneNumber, excludeTelegramId);
    }

    public Optional<AppUser> findByNickname(String nickname) {
        return appUserRepository.findFirstByNicknameIgnoreCaseOrderByIdAsc(nickname);
    }

    public Optional<AppUser> findByTelegramUsername(String telegramUsername) {
        return appUserRepository.findByTelegramUsernameIgnoreCase(telegramUsername);
    }

    /**
     * Освобождает никнейм у аккаунта (для повторной регистрации другим человеком под тем же ником).
     * Аккаунт не блокируется и не удаляется, только теряет ник — уникальность поля допускает
     * несколько null-значений одновременно. Возвращает освобождённый ник для уведомления админа.
     */
    @Transactional
    public String releaseNickname(Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок не найден."));
        String oldNickname = user.getNickname();
        user.setNickname(null);
        appUserRepository.save(user);
        return oldNickname;
    }

    public long getOverallRank(AppUser user) {
        List<AppUser> sorted = appUserRepository.findAllByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();
        return getRank(sorted, user.getTelegramId());
    }

    public long getWeeklyRank(AppUser user) {
        List<AppUser> sorted = appUserRepository.findAllByRegistrationCompletedTrueOrderByWeeklyXpDescTelegramIdAsc();
        return getRank(sorted, user.getTelegramId());
    }

    public long getWeeklyRankFast(AppUser user) {
        return appUserRepository.countWithMoreWeeklyXp(user.getWeeklyXp()) + 1;
    }

    public long countActiveThisWeek() {
        return appUserRepository.countActiveThisWeek();
    }

    private long getRank(List<AppUser> sorted, Long telegramId) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getTelegramId().equals(telegramId)) {
                return i + 1L;
            }
        }
        return sorted.size() + 1L;
    }

    public int getLevelNumber(long xp) {
        return resolveLevelTier(xp).number();
    }

    public String getLevelName(long xp) {
        return resolveLevelTier(xp).name();
    }

    public int getExcBonusPercent(long xp) {
        return resolveLevelTier(xp).excBonusPercent();
    }

    public long currentLevelFloor(long xp) {
        return resolveLevelTier(xp).minXp();
    }

    public long nextLevelCeiling(long xp) {
        LevelTier current = resolveLevelTier(xp);
        int nextIndex = current.number();
        if (nextIndex >= LEVEL_TIERS.size()) {
            return current.minXp();
        }
        return LEVEL_TIERS.get(nextIndex).minXp();
    }

    public record LevelTierInfo(int number, String name, long minXp, int excBonusPercent) {}

    public List<LevelTierInfo> getAllLevelTiers() {
        return LEVEL_TIERS.stream()
                .map(t -> new LevelTierInfo(t.number(), t.name(), t.minXp(), t.excBonusPercent()))
                .toList();
    }

    // Модуль 3 максимизации рефералки — майлстоуны по КОЛИЧЕСТВУ приглашённых друзей (независимо
    // от денежной шкалы майлстоунов заработка с рефералов). Пороги+названия — один список пар, не
    // раздельные yml-числа/Java-названия, чтобы не разъезжались при правке одного без другого.
    private record InvitedFriendsBadge(int threshold, String name) {}

    private static final List<InvitedFriendsBadge> INVITED_FRIENDS_BADGES = List.of(
            new InvitedFriendsBadge(5, "🥉 Проводник"),
            new InvitedFriendsBadge(10, "🥈 Посол клуба"),
            new InvitedFriendsBadge(25, "🥇 Легенда рефералки"),
            new InvitedFriendsBadge(50, "💎 Икона EGC")
    );

    public Optional<Integer> highestInvitedFriendsMilestone(int invitedFriends) {
        Integer result = null;
        for (InvitedFriendsBadge b : INVITED_FRIENDS_BADGES) {
            if (invitedFriends >= b.threshold()) result = b.threshold(); else break;
        }
        return Optional.ofNullable(result);
    }

    public String invitedFriendsBadgeName(int threshold) {
        return INVITED_FRIENDS_BADGES.stream()
                .filter(b -> b.threshold() == threshold).findFirst()
                .map(InvitedFriendsBadge::name).orElse("🎖️ " + threshold + " друзей");
    }

    public Optional<String> currentInvitedFriendsBadge(int invitedFriends) {
        return highestInvitedFriendsMilestone(invitedFriends).map(this::invitedFriendsBadgeName);
    }

    /** null = все бейджи уже получены. */
    public Integer nextInvitedFriendsMilestone(int invitedFriends) {
        for (InvitedFriendsBadge b : INVITED_FRIENDS_BADGES) {
            if (invitedFriends < b.threshold()) return b.threshold();
        }
        return null;
    }

    public List<String> getAchievements(AppUser user) {
        return Stream.of(
                user.getCompletedQuests() >= 1 ? "🏅 Первое задание" : null,
                user.getCompletedQuests() >= 10 ? "🔥 10 заданий" : null,
                user.getCompletedQuests() >= 100 ? "👑 100 заданий" : null,
                user.getInvitedFriends() >= 1 ? "🤝 Первый реферал" : null,
                user.getInvitedFriends() >= 10 ? "🚀 10 рефералов" : null,
                user.getXp() >= 35_000 ? "🌟 Легенда клуба" : null
        ).filter(item -> item != null).toList();
    }

    @Transactional
    public AppUser completeRegistration(AppUser user, String nickname) {
        user.setNickname(nickname);
        user.setProfileCompleted(true);
        user.setRegistrationCompleted(false);
        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser activateAccount(AppUser user) {
        user.setProfileCompleted(true);
        user.setRegistrationCompleted(true);
        return appUserRepository.save(user);
    }

    @Transactional
    public boolean applyWelcomeBonus(AppUser user) {
        AppUser fresh = appUserRepository.findByIdForUpdate(user.getId()).orElse(null);
        if (fresh == null || fresh.isWelcomeBonusPaid()) {
            return false;
        }
        fresh.setCoins(fresh.getCoins() + 200);
        fresh.setWelcomeBonusPaid(true);
        fresh.setLastBonusDate(LocalDate.now());
        appUserRepository.save(fresh);
        excTx.log(fresh, 200, ExcTransactionService.WELCOME_BONUS, "Приветственный бонус за регистрацию");
        return true;
    }

    public List<AppUser> topOverall() {
        return appUserRepository.findTop20ByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();
    }

    public List<AppUser> findPendingChannelActivation() {
        return appUserRepository.findAllByProfileCompletedTrueAndRegistrationCompletedFalse();
    }

    public List<AppUser> topWeekly() {
        // Только weeklyXp > 0 — иначе список добивался неактивными игроками (0 XP за неделю, отсортированы по TG ID).
        return appUserRepository.findTop20ByRegistrationCompletedTrueAndWeeklyXpGreaterThanOrderByWeeklyXpDescTelegramIdAsc(0);
    }

    public List<AppUser> top5Overall() {
        return appUserRepository.findTop5ByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();
    }

    public long countNewUsersSince(java.time.LocalDateTime since) {
        return appUserRepository.countNewUsersSince(since);
    }

    /** Троттлинг раз в день на запись (не на чтение) — иначе каждое сообщение/callback в боте писало бы
     *  в БД, а бот получает такие апдейты на порядки чаще, чем открытия мини-аппа. */
    @Transactional
    public void touchBotActivity(Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (user.getLastBotActivityAt() != null && user.getLastBotActivityAt().toLocalDate().equals(now.toLocalDate())) {
            return;
        }
        user.setLastBotActivityAt(now);
        appUserRepository.save(user);
    }

    @Transactional
    public void touchMiniAppOpen(Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (user.getLastMiniAppOpenAt() != null && user.getLastMiniAppOpenAt().toLocalDate().equals(now.toLocalDate())) {
            return;
        }
        user.setLastMiniAppOpenAt(now);
        appUserRepository.save(user);
    }

    public record SurfaceActivityReport(long botActive7d, long botActive30d, long miniAppActive7d, long miniAppActive30d) {}

    /** Сравнение "бот vs мини-апп" по числу уникальных активных игроков за 7/30 дней — для admin-кнопки. */
    public SurfaceActivityReport getSurfaceActivityReport() {
        LocalDateTime since7d = LocalDateTime.now().minusDays(7);
        LocalDateTime since30d = LocalDateTime.now().minusDays(30);
        return new SurfaceActivityReport(
                appUserRepository.countByLastBotActivityAtAfter(since7d),
                appUserRepository.countByLastBotActivityAtAfter(since30d),
                appUserRepository.countByLastMiniAppOpenAtAfter(since7d),
                appUserRepository.countByLastMiniAppOpenAtAfter(since30d)
        );
    }

    /** Три метрики вовлечённости, аналог ER канала, но для продукта с активным (не пассивным) действием —
     *  см. обсуждение 2026-09-14: у канала 20-30% дневного охвата уже отлично, но для бота, где "вовлечение"
     *  требует реально сыграть и отправить отчёт, а не просто увидеть пост, ориентиры ниже и считаются иначе. */
    public record EngagementReport(
            long dau, long mau, double dauMauPercent,
            long weeklyQuestTakers, double weeklyQuestPercent,
            long retentionCohort, long retentionReturned, double retentionPercent
    ) {}

    /** Сколько дней аккаунт должен существовать, чтобы попасть в сегмент "Без рекламы" — без этого сразу
     *  после закупа в DAU/MAU попадает партия только что зарегистрированных, которые технически "активны"
     *  просто потому что недавно зашли, ещё не успели ни прижиться, ни отвалиться, и раздувают цифру
     *  (запрошено 2026-09-16). Не влияет на "Все" и на отдельные закупки — только на "Без рекламы". */
    private static final int ORGANIC_MIN_ACCOUNT_AGE_DAYS = 14;

    /** sourceFilter: null = вся аудитория, "ORGANIC" = только органика/реферал (trafficSourceCode IS NULL)
     *  и аккаунту не меньше ORGANIC_MIN_ACCOUNT_AGE_DAYS дней, иначе — код конкретной рекламной закупки
     *  (см. TrafficSource.code), без ограничения по возрасту. */
    public EngagementReport getEngagementReport(String sourceFilter) {
        LocalDateTime since1d = LocalDateTime.now().minusDays(1);
        LocalDateTime since7d = LocalDateTime.now().minusDays(7);
        LocalDateTime since30d = LocalDateTime.now().minusDays(30);
        LocalDateTime maxCreatedAt = "ORGANIC".equals(sourceFilter)
                ? LocalDateTime.now().minusDays(ORGANIC_MIN_ACCOUNT_AGE_DAYS) : null;

        long dau = appUserRepository.countDistinctActiveSince(since1d.toLocalDate(), since1d, sourceFilter, maxCreatedAt);
        long mau = appUserRepository.countDistinctActiveSince(since30d.toLocalDate(), since30d, sourceFilter, maxCreatedAt);
        double dauMauPercent = mau > 0 ? dau * 100.0 / mau : 0;

        long weeklyQuestTakers = questSubmissionRepository.countDistinctUsersWithApprovedSince(since7d, sourceFilter, maxCreatedAt);
        double weeklyQuestPercent = mau > 0 ? weeklyQuestTakers * 100.0 / mau : 0;

        SecondQuestRetention retention = secondQuestRetention(sourceFilter, maxCreatedAt);

        return new EngagementReport(dau, mau, dauMauPercent,
                weeklyQuestTakers, weeklyQuestPercent,
                retention.cohortSize(), retention.returned(), retention.ratePercent());
    }

    private record SecondQuestRetention(long cohortSize, long returned, double ratePercent) {}

    /** Из всех, кто выполнил свой первый одобренный квест не позже чем 7 дней назад (иначе рано судить,
     *  у них ещё есть время вернуться) — какая доля выполнила второй одобренный квест в течение недели
     *  после первого. Считается в Java, а не в JPQL: оконные функции по группам ("второе значение в
     *  отсортированной группе") плохо переносятся между H2 (тесты) и Postgres (прод), а объём данных
     *  (одобренные квесты по всем игрокам) на текущем масштабе проекта не проблема для in-memory группировки. */
    private SecondQuestRetention secondQuestRetention(String sourceFilter, LocalDateTime maxCreatedAt) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        List<Object[]> rows = questSubmissionRepository.findApprovedUserIdAndDateForRetention(sourceFilter, maxCreatedAt);
        java.util.Map<Long, List<LocalDateTime>> byUser = new java.util.HashMap<>();
        for (Object[] row : rows) {
            Long telegramId = (Long) row[0];
            LocalDateTime approvedAt = (LocalDateTime) row[1];
            byUser.computeIfAbsent(telegramId, k -> new java.util.ArrayList<>()).add(approvedAt);
        }
        long cohort = 0;
        long returned = 0;
        for (List<LocalDateTime> dates : byUser.values()) {
            if (dates.isEmpty()) continue;
            java.util.Collections.sort(dates);
            LocalDateTime first = dates.get(0);
            if (first.isAfter(cutoff)) continue;
            cohort++;
            if (dates.size() >= 2 && !dates.get(1).isAfter(first.plusDays(7))) {
                returned++;
            }
        }
        double rate = cohort > 0 ? returned * 100.0 / cohort : 0;
        return new SecondQuestRetention(cohort, returned, rate);
    }

    @Transactional
    public String registerActivity(AppUser user) {
        LocalDate today = LocalDate.now();
        LocalDate lastDate = user.getLastActivityDate();
        if (lastDate != null && lastDate.equals(today)) {
            return null;
        }

        if (lastDate != null && lastDate.plusDays(1).equals(today)) {
            user.setStreakDays(user.getStreakDays() + 1);
        } else {
            snapshotBrokenStreak(user);
            user.setStreakDays(1);
        }
        user.setLastActivityDate(today);
        user.setLastDormancyTierNotified(0);

        long xpBonus = 0;
        if (user.getStreakDays() == 7) {
            xpBonus = 20;
        } else if (user.getStreakDays() == 30) {
            xpBonus = 100;
        } else if (user.getStreakDays() == 90) {
            xpBonus = 500;
        }

        if (xpBonus > 0) {
            user.setXp(user.getXp() + xpBonus);
            user.setWeeklyXp(user.getWeeklyXp() + xpBonus);
        }

        appUserRepository.save(user);
        return null;
    }

    public boolean isDailyBonusAvailable(AppUser user) {
        return user.getLastBonusDate() == null || !user.getLastBonusDate().equals(LocalDate.now());
    }

    @Transactional
    public DailyBonusResult claimDailyBonus(AppUser user) {
        if (!isDailyBonusAvailable(user)) {
            return null;
        }
        // Update streak first
        LocalDate today = LocalDate.now();
        LocalDate lastActivity = user.getLastActivityDate();
        if (lastActivity == null || !lastActivity.equals(today)) {
            if (lastActivity != null && lastActivity.plusDays(1).equals(today)) {
                user.setStreakDays(user.getStreakDays() + 1);
            } else {
                snapshotBrokenStreak(user);
                user.setStreakDays(1);
            }
            user.setLastActivityDate(today);
        }

        int streak = user.getStreakDays();
        long dailyExc = Math.min(150L + (long)(streak - 1) * 50, 500L);
        long milestoneExc = 0;
        long xpBonus = 0;
        String milestoneText = null;

        // Билеты колеса фортуны за серию входов
        int streakTickets = switch (streak) {
            case 3 -> 1;
            case 7 -> 2;
            case 14 -> 3;
            default -> 0;
        };
        if (streakTickets > 0) {
            wheelService.addTickets(user, streakTickets, "Серия входов: " + streak + " дней");
        }

        if (streak == 7) {
            milestoneExc = 1000;
            xpBonus = 20;
            milestoneText = "🔥 Неделя подряд!";
        } else if (streak == 14) {
            milestoneExc = 1500;
            milestoneText = "💥 2 недели подряд!";
        } else if (streak == 30) {
            milestoneExc = 5000;
            xpBonus = 100;
            milestoneText = "⭐ Месяц без пропуска!";
        } else if (streak == 90) {
            milestoneExc = 15000;
            xpBonus = 500;
            milestoneText = "🏆 3 месяца подряд!";
        }

        long totalExc = dailyExc + milestoneExc;
        user.setCoins(user.getCoins() + totalExc);
        excTx.log(user, totalExc, ExcTransactionService.DAILY,
                "Ежедневный бонус (день " + user.getStreakDays() + ")");
        if (xpBonus > 0) {
            user.setXp(user.getXp() + xpBonus);
            user.setWeeklyXp(user.getWeeklyXp() + xpBonus);
        }
        user.setLastBonusDate(today);
        // Бонус за сегодня так или иначе получен (продолжение серии, свежий сброс "начать заново" или
        // только что восстановленная через restoreStreak() серия) — любой оставшийся снимок разрыва
        // больше не актуален для СЕГОДНЯ, не должен предлагаться повторно. restoreStreak() уже очищает
        // его сам перед вызовом этого метода — здесь просто гарантия на случай прямого claimDailyBonus
        // без восстановления (иначе "Начать заново" оставлял бы висеть предложение купить старую серию).
        user.setLastBrokenStreakDays(null);
        user.setLastBrokenStreakUntil(null);
        appUserRepository.save(user);

        return new DailyBonusResult(totalExc, dailyExc, milestoneExc, xpBonus, streak, milestoneText);
    }

    public record DailyBonusResult(
            long totalExc, long dailyExc, long milestoneExc,
            long xpBonus, int streakDays, String milestoneText
    ) {}

    private static final int STREAK_RESTORE_GRACE_DAYS = 2;

    /** Снимок серии ПЕРЕД сбросом на 1 — вызывается и из registerActivity(), и из claimDailyBonus()
     *  (какой из двух сработает первым после пропуска дня), см. поля на AppUser. Не перезаписывает уже
     *  существующий снимок повторно в тот же день — иначе повторный вызов (например claimDailyBonus
     *  сразу после registerActivity в одном заходе) затёр бы валидный снимок нулём/старой датой. */
    private void snapshotBrokenStreak(AppUser user) {
        if (user.getStreakDays() < 2) {
            return; // серию из 0-1 дня восстанавливать нечего и не за что платить
        }
        LocalDate today = LocalDate.now();
        if (user.getLastBrokenStreakUntil() != null && !today.isAfter(user.getLastBrokenStreakUntil())) {
            return; // снимок уже сделан в этом же окне (двойной вызов за один заход)
        }
        user.setLastBrokenStreakDays(user.getStreakDays());
        user.setLastBrokenStreakUntil(today.plusDays(STREAK_RESTORE_GRACE_DAYS));
    }

    /** Публичная, вызываемая ПРОАКТИВНО из sendDailyBonus ДО claimDailyBonus — сама проверяет разрыв
     *  (в отличие от private snapshotBrokenStreak, которая только сохраняет, вызывающий уже знает про
     *  разрыв). Без этого метода первый заход на экран бонуса после пропуска дня (если до этого не
     *  было /start → registerActivity) сразу проваливался бы в claimDailyBonus и обнулял серию, даже
     *  не успев показать предложение восстановить — снимок создавался бы ПОСЛЕ решения сбросить, в
     *  одной и той же транзакции. Идемпотентна и не трогает streakDays/lastActivityDate — безопасно
     *  вызывать всегда, даже когда серия на самом деле цела (тогда просто ничего не делает). */
    @Transactional
    public void captureStreakBreakIfNeeded(AppUser user) {
        LocalDate today = LocalDate.now();
        LocalDate lastActivity = user.getLastActivityDate();
        boolean broken = lastActivity != null && !lastActivity.equals(today) && !lastActivity.plusDays(1).equals(today);
        if (broken) {
            snapshotBrokenStreak(user);
            appUserRepository.save(user);
        }
    }

    /** Есть ли сейчас актуальное предложение "восстановить серию за Stars" — снимок существует и окно
     *  (см. STREAK_RESTORE_GRACE_DAYS) ещё не истекло. Проверяется в момент открытия экрана ежедневного
     *  бонуса, ДО обычного claimDailyBonus (иначе он бы уже сбросил серию заново, затерев смысл предложения). */
    public boolean hasRestorableStreak(AppUser user) {
        return user.getLastBrokenStreakDays() != null && user.getLastBrokenStreakDays() >= 2
                && user.getLastBrokenStreakUntil() != null && !LocalDate.now().isAfter(user.getLastBrokenStreakUntil());
    }

    /** Возвращает снятое число дней серии для текста предложения — вызывать только после
     *  hasRestorableStreak() == true. */
    public int restorableStreakDays(AppUser user) {
        return user.getLastBrokenStreakDays() != null ? user.getLastBrokenStreakDays() : 0;
    }

    /** Восстанавливает сохранённую серию и сразу же засчитывает вчерашний день как пройденный, чтобы
     *  последующий claimDailyBonus() продолжил её с сегодняшнего дня (+1), а не начал с 1 — сам
     *  claimDailyBonus не трогаем, чтобы не дублировать расчёт награды/XP-майлстоунов, вызывающая
     *  сторона (GamePlatformBot, покупка "starsitem:STREAK_RESTORE") должна вызвать его следующим шагом. */
    @Transactional
    public void restoreStreak(AppUser user) {
        int savedStreak = restorableStreakDays(user);
        user.setStreakDays(savedStreak);
        user.setLastActivityDate(LocalDate.now().minusDays(1));
        user.setLastBrokenStreakDays(null);
        user.setLastBrokenStreakUntil(null);
        appUserRepository.save(user);
    }

    /** Сундук дня — отдельная от ежедневного бонуса механика (аудит вовлечённости, 2026-09-14):
     *  элемент случайности/предвкушения, а не гарантированная сумма. Раз в сутки, независимый от
     *  streak и isDailyBonusAvailable лимит — оба бонуса можно забрать в один день. */
    public boolean isChestAvailable(AppUser user) {
        return user.getLastChestOpenedDate() == null || !user.getLastChestOpenedDate().equals(LocalDate.now());
    }

    @Transactional
    public ChestResult openChest(AppUser user) {
        if (!isChestAvailable(user)) {
            return null;
        }
        // Подписчикам EGC Pass бесплатный сундук дня сразу крутится по улучшенному пулу (2026-09-15) —
        // один из перков пакета: не нужно отдельно платить 15⭐ за реролл каждый день.
        ChestResult result = isEgcPassActive(user) ? rollAndApplyPremiumChestPrize(user) : rollAndApplyChestPrize(user);
        user.setLastChestOpenedDate(LocalDate.now());
        appUserRepository.save(user);
        return result;
    }

    /** Платный реролл за Telegram Stars (запрошено 2026-09-14) — ОТДЕЛЬНЫЙ, заметно более щедрый пул
     * призов (см. rollAndApplyPremiumChestPrize), не тот же самый, что у бесплатного сундука. Причина:
     * делить один и тот же скромный "подарок за заход" пул между бесплатным и платным вариантом —
     * нечестно по отношению к игроку (15⭐ ≈ 21₽ за шанс на 50-100 EXC ≈ <1₽ читается как обман).
     * Намеренно НЕ трогает lastChestOpenedDate — платный реролл не засчитывается за бесплатный сундук
     * дня и не блокирует его: можно получить и бесплатный, и докупить реролл(ы) поверх, в любом порядке. */
    @Transactional
    public ChestResult openChestPaidReroll(AppUser user) {
        ChestResult result = rollAndApplyPremiumChestPrize(user);
        appUserRepository.save(user);
        return result;
    }

    private ChestResult rollAndApplyChestPrize(AppUser user) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        long exc = 0;
        int tickets = 0;
        String label;
        if (roll < 2) {
            exc = 500;
            label = "🎉 Джекпот!";
        } else if (roll < 10) {
            tickets = 1;
            label = "🎟️ Билет колеса фортуны!";
        } else if (roll < 35) {
            exc = ThreadLocalRandom.current().nextInt(150, 251);
            label = "✨ Хороший улов!";
        } else {
            exc = ThreadLocalRandom.current().nextInt(75, 126);
            label = "🪙 Немного EXC";
        }
        if (exc > 0) {
            user.setCoins(user.getCoins() + exc);
            excTx.log(user, exc, ExcTransactionService.CHEST, "Сундук дня");
        }
        if (tickets > 0) {
            wheelService.addTickets(user, tickets, "Сундук дня");
        }
        return new ChestResult(label, exc, tickets);
    }

    /** Пул призов платного реролла (2026-09-14) — заметно щедрее бесплатного (см. rollAndApplyChestPrize):
     * больший джекпот, 2 билета вместо 1, выше и нижняя, и верхняя планка EXC-диапазонов. Матожидание
     * ~500 EXC (≈5₽ при HR=100%) — около 24% от цены реролла (15⭐≈21₽), заметно щедрее бесплатного
     * (~149 EXC), но всё ещё явно меньше уплаченного — не превращается в ставку с плюсовым ожиданием. */
    private ChestResult rollAndApplyPremiumChestPrize(AppUser user) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        long exc = 0;
        int tickets = 0;
        String label;
        if (roll < 5) {
            exc = 2000;
            label = "🎉 Джекпот!";
        } else if (roll < 20) {
            tickets = 2;
            label = "🎟️ 2 билета колеса фортуны!";
        } else if (roll < 60) {
            exc = ThreadLocalRandom.current().nextInt(400, 601);
            label = "✨ Отличный улов!";
        } else {
            exc = ThreadLocalRandom.current().nextInt(200, 351);
            label = "🪙 Хороший улов";
        }
        if (exc > 0) {
            user.setCoins(user.getCoins() + exc);
            excTx.log(user, exc, ExcTransactionService.CHEST, "Сундук дня (реролл за Stars)");
        }
        if (tickets > 0) {
            wheelService.addTickets(user, tickets, "Сундук дня (реролл за Stars)");
        }
        return new ChestResult(label, exc, tickets);
    }

    public record ChestResult(String prizeLabel, long exc, int tickets) {}

    /** У каждой рекламной сети свой дневной лимит показов на игрока — antifraud-риски и реальная
     * монетизация повторных показов различаются по сети, единый лимит на всех не годится. */
    public enum AdRewardSource {
        ADSGRAM(10), TELEGA(5);

        private final int dailyCap;

        AdRewardSource(int dailyCap) {
            this.dailyCap = dailyCap;
        }

        public int getDailyCap() {
            return dailyCap;
        }
    }

    private static final long AD_REWARD_EXC = 30;

    public int getAdRewardDailyCap(AdRewardSource source) {
        return source.getDailyCap();
    }

    private int adRewardCountToday(AppUser user, AdRewardSource source) {
        if (user.getAdRewardDate() == null || !user.getAdRewardDate().equals(LocalDate.now())) {
            return 0;
        }
        return source == AdRewardSource.ADSGRAM ? user.getAdRewardCountAdsgram() : user.getAdRewardCountTelega();
    }

    public int getAdRewardsRemainingToday(AppUser user, AdRewardSource source) {
        return Math.max(0, source.getDailyCap() - adRewardCountToday(user, source));
    }

    /** Цель показа для рекламного колеса: награда за просмотр — спин колеса, а не 30 EXC. */
    public static final String AD_PURPOSE_WHEEL = "WHEEL";

    @Transactional
    public void markAdRequested(AppUser user) {
        markAdRequested(user, null);
    }

    /** purpose == null — обычный показ (30 EXC); иначе см. AD_PURPOSE_*. Каждый новый запрос перезаписывает
     * предыдущую цель, чтобы «зависший» показ для колеса не превратил следующий обычный показ в спин. */
    @Transactional
    public void markAdRequested(AppUser user, String purpose) {
        user.setPendingAdRewardAt(LocalDateTime.now());
        user.setPendingAdPurpose(purpose);
        appUserRepository.save(user);
    }

    public record AdRewardResult(boolean granted, long totalExc, long milestoneBonus, int viewsToday, int dailyCap,
                                 boolean wheelSpin) {
        public static final AdRewardResult NOT_GRANTED = new AdRewardResult(false, 0, 0, 0, 0, false);
    }

    /** Бонус за отметки прогресса (2026-09-09) — подталкивает досматривать лимит сети целиком,
     * а не бросать на середине: без него часть игроков не добирает дневной лимит, и клуб теряет
     * рекламный доход на недосмотренных показах. Суммы небольшие относительно базовой награды
     * (30 EXC/показ), чтобы не обесценить сам факт просмотра как источник дохода. */
    private long adRewardMilestoneBonus(AdRewardSource source, int viewsToday) {
        return switch (source) {
            case ADSGRAM -> viewsToday == 5 ? 50 : viewsToday == 10 ? 100 : 0;
            case TELEGA -> viewsToday == 3 ? 50 : viewsToday == 5 ? 75 : 0;
        };
    }

    /** Засчитывает награду за просмотр рекламы — только если у игрока есть непросроченный
     * "ожидающий показ" (выставляется в {@link #markAdRequested}), иначе тихо отказывает. Одноразово.
     * source определяется вызывающим постбек-эндпоинтом (своя сеть — свой URL), не хранится отдельно
     * от pendingAdRewardAt: сам факт "показ был запрошен недавно" не завязан на конкретную сеть. */
    @Transactional
    public AdRewardResult claimPendingAdReward(Long telegramId, AdRewardSource source) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null || user.getPendingAdRewardAt() == null) {
            return AdRewardResult.NOT_GRANTED;
        }
        if (user.getPendingAdRewardAt().isBefore(LocalDateTime.now().minusHours(1))) {
            return AdRewardResult.NOT_GRANTED;
        }
        boolean wheelSpin = AD_PURPOSE_WHEEL.equals(user.getPendingAdPurpose());
        user.setPendingAdRewardAt(null);
        user.setPendingAdPurpose(null);
        LocalDate today = LocalDate.now();
        if (user.getAdRewardDate() == null || !user.getAdRewardDate().equals(today)) {
            user.setAdRewardDate(today);
            user.setAdRewardCountAdsgram(0);
            user.setAdRewardCountTelega(0);
        }
        int viewsToday;
        if (source == AdRewardSource.ADSGRAM) {
            viewsToday = user.getAdRewardCountAdsgram() + 1;
            user.setAdRewardCountAdsgram(viewsToday);
        } else {
            viewsToday = user.getAdRewardCountTelega() + 1;
            user.setAdRewardCountTelega(viewsToday);
        }
        long milestoneBonus = adRewardMilestoneBonus(source, viewsToday);
        // Для колеса вместо плоских 30 EXC копится спин (разыгрывается в AdWheelService); бонус за отметки
        // прогресса (5-й/10-й показ) платится в обоих режимах — иначе игрок, выбравший колесо, терял бы его.
        long totalExc = (wheelSpin ? 0 : AD_REWARD_EXC) + milestoneBonus;
        if (wheelSpin) {
            user.setAdWheelSpins(user.getAdWheelSpins() + 1);
        }
        user.setCoins(user.getCoins() + totalExc);
        appUserRepository.save(user);
        if (totalExc > 0) {
            String description = (wheelSpin ? "Просмотр рекламы для колеса (" : "Просмотр рекламы (") + source + ")";
            if (milestoneBonus > 0) {
                description += " + бонус за " + viewsToday + "/" + source.getDailyCap() + " просмотров";
            }
            excTx.log(user, totalExc, ExcTransactionService.AD_REWARD, description);
            eventPublisher.publishEvent(new ru.gamebot.platform.event.AdRewardGrantedEvent(
                    this, user.getId(), totalExc, milestoneBonus));
        }
        return new AdRewardResult(true, totalExc, milestoneBonus, viewsToday, source.getDailyCap(), wheelSpin);
    }

    public record ReferralActivationResult(
            long invitedBonus,
            Long referrerTelegramId,
            String referrerNickname,
            long referrerBonus,
            String invitedNickname
    ) {}

    // Called on channel subscription — gives instant EXC to both parties
    @Transactional
    public ReferralActivationResult grantReferralReward(AppUser invitedUser) {
        if (invitedUser.isReferralRewardProcessed()) {
            return null;
        }
        Long referrerTelegramId = invitedUser.getReferredByTelegramId();
        if (referrerTelegramId == null) {
            return null;
        }
        AppUser referrer = appUserRepository.findByTelegramId(referrerTelegramId).orElse(null);
        if (referrer == null) {
            return null;
        }
        referrer.setInvitedFriends(referrer.getInvitedFriends() + 1);

        // Модуль 5 максимизации рефералки: буст-уикенд умножает только мгновенную награду
        int boostMultiplier = referralBoostService.currentMultiplier();
        String boostSuffix = boostMultiplier > 1 ? " (буст ×" + boostMultiplier + ")" : "";

        // Instant bonus: 500 EXC to invited user
        long invitedBonus = 500L * boostMultiplier;
        invitedUser.setCoins(invitedUser.getCoins() + invitedBonus);
        excTx.log(invitedUser, invitedBonus, ExcTransactionService.REFERRAL_WELCOME, "Реферальный бонус (приглашён)" + boostSuffix);

        // Instant bonus: 300 EXC to referrer
        long referrerBonus = 300L * boostMultiplier;
        referrer.setCoins(referrer.getCoins() + referrerBonus);
        excTx.log(referrer, referrerBonus, ExcTransactionService.REFERRAL,
                "Реферальный бонус за приглашение: " + invitedUser.getNickname() + boostSuffix);
        referrer.setReferralEarnedExc(referrer.getReferralEarnedExc() + referrerBonus);

        appUserRepository.save(referrer);
        invitedUser.setReferralRewardProcessed(true);
        appUserRepository.save(invitedUser);

        return new ReferralActivationResult(
                invitedBonus,
                referrer.getTelegramId(),
                referrer.getNickname(),
                referrerBonus,
                invitedUser.getNickname()
        );
    }

    // Разовый бонус рефереру (2026-09-09) — по обратной связи игроков гарантированная крупная
    // награда мотивирует звать друзей сильнее, чем размазанные во времени 10% отчисления
    // (которые остаются без изменений, это ДОПОЛНИТЕЛЬНЫЙ бонус). Порог реальных данных перед
    // внедрением: средний ручеёк на активированного реферала оказался ~213 EXC (отчёт
    // "Экономика рефералки"), 2500 EXC — сознательное решение поднять стоимость привлечения.
    private static final long REFERRER_FIRST_QUEST_BONUS = 2_500;

    // 3.5: 3000 EXC bonus to invited user on their first approved quest + разовый бонус рефереру
    @Transactional
    public boolean grantFirstQuestReferralBonus(AppUser invitedUser) {
        Long referrerTelegramId = invitedUser.getReferredByTelegramId();
        if (referrerTelegramId == null) {
            return false;
        }
        if (invitedUser.getCompletedQuests() != 0) {
            return false; // only on first quest (completedQuests is incremented before this call)
        }
        addReward(invitedUser, 0, 3_000);

        // Самореферал не награждаем (та же защита, что и в QuestService.grantReferralBonus);
        // если referrer не найден — бонус приглашённому выше всё равно уже начислен.
        if (!referrerTelegramId.equals(invitedUser.getTelegramId())) {
            appUserRepository.findByTelegramId(referrerTelegramId).ifPresent(referrer -> {
                // addReward применяет %-бонус уровня игрока к сумме (системное поведение) — логируем и
                // показываем в уведомлении РЕАЛЬНО начисленную сумму (totalExc), а не сырую константу,
                // иначе у реферера с бонусом уровня баланс разойдётся с тем, что написано в уведомлении.
                RewardGrant grant = addReward(referrer, 0, REFERRER_FIRST_QUEST_BONUS);
                long awardedExc = grant.totalExc();
                excTx.log(referrer, awardedExc, ExcTransactionService.REFERRAL_FIRST_QUEST_BONUS,
                        "Бонус за первый квест друга: " + invitedUser.getNickname());
                referrer.setReferralEarnedExc(referrer.getReferralEarnedExc() + awardedExc);
                appUserRepository.save(referrer);
                eventPublisher.publishEvent(new ru.gamebot.platform.event.ReferrerFirstQuestBonusEvent(
                        this, referrer.getTelegramId(), invitedUser.getNickname(), awardedExc));
            });
        }
        return true;
    }

    @Transactional
    public RewardGrant addReward(AppUser user, long xp, long coins) {
        return addReward(user, xp, coins, 0);
    }

    @Transactional
    public RewardGrant addReward(AppUser user, long xp, long coins, long tickets) {
        RewardGrant rewardGrant = previewReward(user, xp, coins, tickets);
        user.setXp(user.getXp() + xp);
        user.setWeeklyXp(user.getWeeklyXp() + xp);
        user.setCoins(user.getCoins() + rewardGrant.totalExc());
        user.setTickets(user.getTickets() + tickets);
        appUserRepository.save(user);
        return rewardGrant;
    }

    @Transactional
    public RewardGrant addManualBonus(Long telegramId, long xp, long coins, long tickets) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        RewardGrant grant = addReward(user, xp, coins, tickets);
        if (coins > 0) excTx.log(user, grant.totalExc(), ExcTransactionService.BONUS,
                "Ручное начисление администратором");
        return grant;
    }

    @Transactional
    public BalanceDebit debitManualBalance(Long telegramId, long xp, long coins, long tickets) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));

        if (user.getXp() < xp) {
            throw new IllegalArgumentException("Недостаточно XP для списания.");
        }
        if (user.getCoins() < coins) {
            throw new IllegalArgumentException("Недостаточно EXC для списания.");
        }
        if (user.getTickets() < tickets) {
            throw new IllegalArgumentException("Недостаточно билетов для списания.");
        }

        user.setXp(user.getXp() - xp);
        user.setWeeklyXp(Math.max(0, user.getWeeklyXp() - xp));
        user.setCoins(user.getCoins() - coins);
        user.setTickets(user.getTickets() - tickets);
        appUserRepository.save(user);
        if (coins > 0) excTx.log(user, -coins, ExcTransactionService.DEBIT,
                "Ручное списание администратором");
        return new BalanceDebit(xp, coins, tickets);
    }

    @Transactional
    public long forceResetWeeklyXp() {
        List<AppUser> users = appUserRepository.findAll();
        long count = 0;
        for (AppUser u : users) {
            if (u.isRegistrationCompleted() && u.getWeeklyXp() > 0) {
                u.setWeeklyXp(0);
                count++;
            }
        }
        appUserRepository.saveAll(users);
        return count;
    }

    private static final int REFERRAL_TOP_N = 5;
    private static final long REFERRAL_WEEKLY_POOL = 2_000L;
    private static final long[] REFERRAL_SHARE_BPS = {4000, 2500, 1500, 1200, 800};

    public record ReferralRankEntry(AppUser user, int rank, long weeklyReferralExc, long prizeExc) {}

    /**
     * Награда топ-5 рефереров недели из пула {@link #REFERRAL_WEEKLY_POOL} EXC (у отрядов пул больше —
     * 10 000 EXC/нед, — реферальная награда сознательно меньше, доступна более широкому кругу игроков).
     * referralEarnedExc на AppUser — накопительный итог за всё время, недельный доход считаем отдельно
     * через ExcTransaction (тип REFERRAL), не трогая накопительное поле.
     */
    @Transactional
    public void rewardTopReferrers(LocalDateTime weekStart, LocalDateTime weekEnd) {
        List<Object[]> rows = excTx.findReferralEarningsRankingBetween(weekStart, weekEnd);
        if (rows.isEmpty()) return;

        int n = Math.min(REFERRAL_TOP_N, rows.size());
        List<ReferralRankEntry> winners = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long userId = (Long) rows.get(i)[0];
            long weeklyReferralExc = ((Number) rows.get(i)[1]).longValue();
            AppUser user = appUserRepository.findById(userId).orElse(null);
            if (user == null) continue;

            long prize = REFERRAL_WEEKLY_POOL * REFERRAL_SHARE_BPS[i] / 10_000;
            user.setCoins(user.getCoins() + prize);
            appUserRepository.save(user);
            excTx.log(user, prize, ExcTransactionService.REFERRAL_PRIZE,
                    "Топ-" + (i + 1) + " реферер недели (+" + prize + " EXC)");
            winners.add(new ReferralRankEntry(user, i + 1, weeklyReferralExc, prize));
        }
        if (!winners.isEmpty()) {
            eventPublisher.publishEvent(new ru.gamebot.platform.event.ReferralLeaderboardRewardEvent(this, winners));
            log.info("Referral weekly leaderboard rewarded: {} winner(s), pool={} EXC", winners.size(), REFERRAL_WEEKLY_POOL);
        }
    }

    @Transactional
    public void resetWeeklyXp() {
        List<AppUser> users = appUserRepository.findAll();

        // Publish hall of fame before reset
        List<ru.gamebot.platform.event.HallOfFameEvent.HallEntry> top3 = users.stream()
                .filter(u -> u.isRegistrationCompleted() && u.getWeeklyXp() > 0)
                .sorted(java.util.Comparator.comparingLong(AppUser::getWeeklyXp).reversed())
                .limit(3)
                .map((AppUser u) -> ru.gamebot.platform.event.HallOfFameEvent.fromUser(0, u))
                .collect(java.util.stream.Collectors.toList());
        // Assign ranks
        for (int i = 0; i < top3.size(); i++) {
            ru.gamebot.platform.event.HallOfFameEvent.HallEntry e = top3.get(i);
            top3.set(i, new ru.gamebot.platform.event.HallOfFameEvent.HallEntry(i + 1, e.nickname(), e.username(), e.weeklyXp(), e.totalXp()));
        }
        if (!top3.isEmpty()) {
            eventPublisher.publishEvent(new ru.gamebot.platform.event.HallOfFameEvent(this, top3));
        }

        for (AppUser user : users) {
            if (!user.isRegistrationCompleted()) continue;
            League league = getLeague(user.getWeeklyXp());
            if (league.excPrize > 0) {
                user.setCoins(user.getCoins() + league.excPrize);
                excTx.log(user, league.excPrize, ExcTransactionService.LEAGUE,
                        "Еженедельная награда лиги: " + league.displayName);
                eventPublisher.publishEvent(new LeagueRewardEvent(
                        this, user.getTelegramId(), league.displayName, league.excPrize, (int) user.getWeeklyXp()
                ));
            }
            user.setWeeklyXp(0);
        }
        appUserRepository.saveAll(users);
    }

    public long totalRegisteredUsers() {
        return appUserRepository.countByRegistrationCompletedTrue();
    }

    /** Игроки, заходившие в бота сегодня (хотя бы раз отправляли /start). */
    public long countActiveToday() {
        return appUserRepository.countActiveOnDate(LocalDate.now());
    }

    public long countActiveSince(java.time.LocalDate since) {
        return appUserRepository.countActiveSince(since);
    }

    /** Снапшот экономики рефералки (2026-09-09) — для решения "разовый бонус рефереру vs текущие
     * 10% отчислений": сколько всего рефералов, сколько из них реально сделали хотя бы 1 квест
     * (порог, на который планируется завязать разовый бонус), сколько уже выплачено ручейком.
     * ВАЖНО: totalReferralTrickleExc — это ТОЛЬКО 10% с квестов, БЕЗ разового инстант-бонуса
     * "+300 EXC за приглашение" (оба логируются под одним типом REFERRAL, различаются по тексту
     * заметки транзакции — см. ExcTransactionRepository.sumReferralTrickleOnly). Смешивать их нельзя:
     * инстант-бонус в любом случае остаётся неизменным независимо от решения по разовому бонусу. */
    public record ReferralEconomicsSnapshot(
            long totalReferred,
            long referredWithAtLeastOneQuest,
            double avgCompletedQuestsAmongReferred,
            long totalReferralTrickleExc,
            long referrersWithTrickleEarnings
    ) {
        public long avgTrickleExcPerActivatedReferral() {
            return referredWithAtLeastOneQuest == 0 ? 0 : totalReferralTrickleExc / referredWithAtLeastOneQuest;
        }
    }

    public ReferralEconomicsSnapshot referralEconomicsSnapshot() {
        return new ReferralEconomicsSnapshot(
                appUserRepository.countAllReferredUsers(),
                appUserRepository.countReferredUsersWithAtLeastOneQuest(),
                appUserRepository.avgCompletedQuestsAmongReferred(),
                excTransactionRepository.sumReferralTrickleOnly(),
                excTransactionRepository.countReferrersWithTrickleEarnings()
        );
    }

    public long sumAllCoins() {
        return appUserRepository.sumAllCoins();
    }

    /** Топ стран по числу игроков, [страна, количество] — для статистики под рекламодателя. */
    public List<Object[]> countUsersByCountry() {
        return appUserRepository.countUsersByCountry();
    }

    public long countReferredNewUsersSince(java.time.LocalDateTime since) {
        return appUserRepository.countReferredNewUsersSince(since);
    }

    public long sumAllTickets() {
        return appUserRepository.sumAllTickets();
    }

    public long countRegisteredBetween(java.time.LocalDateTime from, java.time.LocalDateTime to) {
        return appUserRepository.countRegisteredBetween(from, to);
    }

    public long countRegisteredBetweenAndActiveSince(java.time.LocalDateTime from, java.time.LocalDateTime to, java.time.LocalDate activeSince) {
        return appUserRepository.countRegisteredBetweenAndActiveSince(from, to, activeSince);
    }

    public long countRegisteredBetweenWithCompletedQuestsBetween(java.time.LocalDateTime from, java.time.LocalDateTime to, int minQuests, int maxQuests) {
        return appUserRepository.countRegisteredBetweenWithCompletedQuestsBetween(from, to, minQuests, maxQuests);
    }

    public record ZeroQuestBreakdown(long neverReturned, long returnedButNoQuest) {}

    /** Из тех, кто не взял ни одного квеста — сколько вообще ни разу не открывали бота повторно
     *  (lastBotActivityAt совпадает с днём регистрации), а сколько возвращались, но так и не взяли
     *  квест (lastBotActivityAt позже дня регистрации). Различает "продукт не зацепил вообще" от
     *  "человек искал квест и не нашёл/не разобрался". */
    public ZeroQuestBreakdown breakdownZeroQuestUsers(java.time.LocalDateTime from, java.time.LocalDateTime to) {
        List<AppUser> users = appUserRepository.findRegisteredBetweenWithCompletedQuests(from, to, 0);
        long returnedLater = users.stream()
                .filter(u -> u.getLastBotActivityAt() != null
                        && u.getLastBotActivityAt().toLocalDate().isAfter(u.getCreatedAt().toLocalDate()))
                .count();
        return new ZeroQuestBreakdown(users.size() - returnedLater, returnedLater);
    }

    public List<AppUser> allRegisteredUsers() {
        return appUserRepository.findAll().stream()
                .filter(AppUser::isRegistrationCompleted)
                .collect(Collectors.toList());
    }

    public List<AppUser> findByTrafficSource(String code) {
        return appUserRepository.findAllByTrafficSourceCodeOrderByCreatedAtDesc(code);
    }

    public List<AppUser> findReferredFriends(Long telegramId) {
        return appUserRepository.findAllByReferredByTelegramId(telegramId);
    }

    public List<AppUser> findUsersWithClashTags() {
        return appUserRepository.findAllWithClashTags();
    }

    public long countByTrafficSource(String code) {
        return appUserRepository.countByTrafficSourceCode(code);
    }

    public long countRegisteredByTrafficSource(String code) {
        return appUserRepository.countByTrafficSourceCodeAndProfileCompletedTrue(code);
    }

    public long countActivatedByTrafficSource(String code) {
        return appUserRepository.countByTrafficSourceCodeAndRegistrationCompletedTrue(code);
    }

    /** Игровые теги, которые можно сбросить админу (карточка пользователя → "🏷️ Сбросить теги") —
     *  на случай неверно привязанного тега (опечатка, чужой аккаунт) или по просьбе игрока привязать
     *  заново. Не трогает прогресс уже существующих заявок на авто-верификацию (baseline/progress
     *  в QuestSubmission остаются как есть) — если игрок привяжет тег заново, дальнейший опрос пойдёт
     *  уже по новому тегу, старый прогресс не пересчитывается задним числом. */
    public enum GameTagKey { BRAWL_STARS, CLASH_OF_CLANS, CLASH_ROYALE, DOTA2, CS2, PUBG }

    /** Текущее значение тега/ID для отображения в админке — null, если не привязан. */
    public String getGameTagValue(AppUser user, GameTagKey key) {
        return switch (key) {
            case BRAWL_STARS -> user.getBrawlStarsTag();
            case CLASH_OF_CLANS -> user.getClashOfClansTag();
            case CLASH_ROYALE -> user.getClashRoyaleTag();
            case DOTA2 -> user.getDotaAccountId() != null ? String.valueOf(user.getDotaAccountId()) : null;
            case CS2 -> user.getCs2SteamId64() != null ? String.valueOf(user.getCs2SteamId64()) : null;
            case PUBG -> user.getPubgAccountId();
        };
    }

    @Transactional
    public void resetGameTag(AppUser user, GameTagKey key) {
        switch (key) {
            case BRAWL_STARS -> {
                user.setBrawlStarsTag(null);
                user.setBrawlTagConfirmedAt(null);
            }
            case CLASH_OF_CLANS -> {
                user.setClashOfClansTag(null);
                user.setClashTagConfirmedAt(null);
            }
            case CLASH_ROYALE -> {
                user.setClashRoyaleTag(null);
                user.setClashRoyaleTagConfirmedAt(null);
            }
            case DOTA2 -> {
                user.setDotaAccountId(null);
                user.setDotaLinkedAt(null);
            }
            case CS2 -> {
                user.setCs2SteamId64(null);
                user.setCs2LinkedAt(null);
            }
            case PUBG -> {
                user.setPubgAccountId(null);
                user.setPubgLinkedAt(null);
            }
        }
        appUserRepository.save(user);
    }

    public List<AppUser> allUsersSorted() {
        return appUserRepository.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AppUser::getTelegramId, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    @Transactional
    public AppUser updateStaffRole(Long telegramId, String staffRole) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        user.setStaffRole(staffRole);
        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser blockUser(Long telegramId, String reason) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        user.setBlocked(true);
        user.setBlockReason(reason);
        user.setBlockedAt(java.time.LocalDateTime.now());
        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser blockAndConfiscate(Long telegramId, String reason) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        user.setBlocked(true);
        user.setBlockReason(reason);
        user.setBlockedAt(java.time.LocalDateTime.now());
        long confiscated = user.getCoins();
        if (confiscated > 0) {
            user.setCoins(0);
            appUserRepository.save(user);
            excTx.log(user, -confiscated, ExcTransactionService.CONFISCATE, "Конфискация при блокировке: " + reason);
        } else {
            appUserRepository.save(user);
        }
        return user;
    }

    @Transactional
    public AppUser unblockUser(Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        user.setBlocked(false);
        user.setBlockReason(null);
        user.setBlockedAt(null);
        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser clearPersonalProgress(Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));

        questSubmissionRepository.deleteAllByUser(user);
        rewardRequestRepository.deleteAllByUser(user);

        List<SupportTicket> tickets = supportTicketRepository.findAllByUser(user);
        for (SupportTicket ticket : tickets) {
            supportAttachmentRepository.deleteAllByTicket(ticket);
        }
        supportTicketRepository.deleteAllByUser(user);

        user.setNickname(null);
        user.setAge(null);
        user.setCountry(null);
        user.setPlatformsCsv(null);
        user.setInterestsCsv(null);
        user.setProfileCompleted(false);
        user.setRegistrationCompleted(false);
        user.setXp(0);
        user.setWeeklyXp(0);
        user.setCoins(0);
        user.setTickets(0);
        user.setCompletedQuests(0);
        user.setInvitedFriends(0);
        user.setStreakDays(0);
        user.setReferredByTelegramId(null);
        user.setReferralRewardProcessed(false);
        user.setLastActivityDate(null);

        // Расширено 2026-09-14 для запроса "сбросить до состояния только что создан" (тестирование
        // воронки регистрации с нуля) — раньше сбрасывались только анкета и базовый прогресс, теперь
        // ещё и всё, что могло бы повлиять на прохождение флоу заново (привязки игр, отряд, буст/кулдаун
        // состояния, онбординг, телефон, welcome-бонус). Намеренно НЕ трогаем: id, telegramId, staffRole,
        // blocked/blockReason/blockedAt, fraudSuspect — это не про "анкету", а про права доступа и
        // модерацию, сбрасывать их тут было бы неожиданно и рискованно.
        user.setCreatedAt(java.time.LocalDateTime.now());
        user.setWelcomeShown(false);
        user.setWelcomeBonusPaid(false);
        user.setRulesAccepted(false);
        user.setReferralActive(true);
        user.setReferralEarnedExc(0);
        user.setPendingSquadInviteCode(null);
        user.setSquadId(null);
        user.setTrafficSourceCode(null);
        user.setProfileTitle(null);
        user.setAvatarFileId(null);
        user.setAvatarFrameColor(null);
        user.setAvatarFrameImage(null);
        user.setOwnedFramesCsv(null);
        user.setPhoneNumber(null);
        user.setLastQuestTakenAt(null);
        user.setLastNotifiedLevelNumber(null);
        user.setLastNotifiedExcMilestone(null);
        user.setLastNotifiedInvitedFriendsMilestone(null);
        user.setLastDormancyTierNotified(0);
        user.setLastBotActivityAt(null);
        user.setLastMiniAppOpenAt(null);
        user.setFixedRubBalance(0);

        // Привязки игровых аккаунтов (авто-верификация квестов)
        user.setBrawlStarsTag(null);
        user.setBrawlTagConfirmedAt(null);
        user.setClashOfClansTag(null);
        user.setClashTagConfirmedAt(null);
        user.setClashRoyaleTag(null);
        user.setClashRoyaleTagConfirmedAt(null);
        user.setDotaAccountId(null);
        user.setDotaLinkedAt(null);
        user.setCs2SteamId64(null);
        user.setCs2LinkedAt(null);
        user.setSeasonPassActiveUntil(null);

        // Бусты/страховка/кулдауны магазина
        user.setExcBoostActiveUntil(null);
        user.setXpBoostActiveUntil(null);
        user.setQuestSlotExtraUntil(null);
        user.setCooldownBypassGame(null);
        user.setRetryInsuranceActive(false);
        user.setShopCooldownSmallUntil(null);
        user.setShopCooldownMediumUntil(null);
        user.setShopCooldownLargeUntil(null);
        user.setLastBonusDate(null);

        // Дневные/месячные счётчики
        user.setDailyRerollCount(0);
        user.setDailyRerollDate(null);
        user.setDailyBoostCount(0);
        user.setDailyBoostDate(null);
        user.setDailyCooldownRemovals(0);
        user.setDailyCooldownDate(null);
        user.setDailyGiftsSent(0);
        user.setDailyGiftSentDate(null);
        user.setDailyGiftsReceived(0);
        user.setDailyGiftReceivedDate(null);
        user.setAdRewardCountAdsgram(0);
        user.setAdRewardCountTelega(0);
        user.setAdRewardDate(null);
        user.setPendingAdRewardAt(null);
        user.setMonthlyWithdrawnExc(0);
        user.setWithdrawalMonth(0);
        user.setWithdrawalYear(0);

        // Онбординг-гайд ("быстрый старт")
        user.setOnboardingStep(0);
        user.setOnboardingCompleted(false);
        user.setOnboardingGame(null);
        user.setOnboardingQuestId(null);
        user.setOnboardingStartedAt(null);
        user.setOnboardingCompletedAt(null);
        user.setOnboardingNotificationsSent(0);
        user.setLastOnboardingNotification(null);

        return appUserRepository.save(user);
    }

    private Long resolveReferral(Long currentTelegramId, Long referredByTelegramId) {
        if (referredByTelegramId == null || referredByTelegramId.equals(currentTelegramId)) {
            return null;
        }
        return referredByTelegramId;
    }

    private void updateTelegramProfile(AppUser user, User telegramUser) {
        user.setTelegramUsername(telegramUser.getUserName());
        user.setTelegramFirstName(telegramUser.getFirstName());
        user.setTelegramLastName(telegramUser.getLastName());
        if (user.getNickname() == null || user.getNickname().isBlank()) {
            String fallback = telegramUser.getUserName();
            user.setNickname(uniquePlaceholderNickname(
                    fallback != null && !fallback.isBlank() ? fallback : telegramUser.getFirstName(), telegramUser.getId()));
        }
    }

    /** Временный ник до того, как игрок сам введёт его на шаге регистрации (REG_NAME). Раньше подставлялось
     * имя из Telegram как есть — а на nickname стоит уникальный индекс, поэтому у нового игрока с частым
     * именем («Саша», «Влад»), уже занятым кем-то, save() падал и /start отвечал «Что-то пошло не так»:
     * человек вообще не мог зарегистрироваться (~57 таких падений за сутки, 2026-09-24). Проверка без учёта
     * регистра — индекс регистрозависимый, но остальной код ищет ники без учёта регистра. */
    private String uniquePlaceholderNickname(String base, Long telegramId) {
        String candidate = base == null || base.isBlank() ? "Игрок" : base.trim();
        if (!appUserRepository.existsByNicknameIgnoreCase(candidate)) {
            return candidate;
        }
        String withSuffix = candidate + "_" + Math.abs(telegramId % 10_000);
        if (!appUserRepository.existsByNicknameIgnoreCase(withSuffix)) {
            return withSuffix;
        }
        return candidate + "_" + telegramId;
    }

    public RewardGrant previewReward(AppUser user, long xp, long coins, long tickets) {
        long resultingXp = user.getXp() + xp;
        int excBonusPercent = getExcBonusPercent(resultingXp);
        long bonusExc = coins * excBonusPercent / 100;
        return new RewardGrant(xp, coins, bonusExc, coins + bonusExc, tickets, excBonusPercent);
    }

    public List<String> csvToList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private LevelTier resolveLevelTier(long xp) {
        LevelTier current = LEVEL_TIERS.get(0);
        for (LevelTier tier : LEVEL_TIERS) {
            if (xp >= tier.minXp()) {
                current = tier;
            } else {
                break;
            }
        }
        return current;
    }

    public List<AppUser> getFraudSuspects() {
        return appUserRepository.findAllByFraudSuspectTrue();
    }

    public long countFraudSuspects() {
        return appUserRepository.findAllByFraudSuspectTrue().size();
    }

    @Transactional
    public void clearFraudSuspect(Long telegramId) {
        appUserRepository.findByTelegramId(telegramId).ifPresent(user -> {
            user.setFraudSuspect(false);
            appUserRepository.save(user);
        });
    }

    public record RewardGrant(long xp, long baseExc, long bonusExc, long totalExc, long tickets, int excBonusPercent) {
    }

    public record BalanceDebit(long xp, long exc, long tickets) {
    }

    private record LevelTier(int number, String name, long minXp, int excBonusPercent) {
    }
}
