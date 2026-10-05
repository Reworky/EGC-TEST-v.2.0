package ru.gamebot.platform.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.RewardRequestStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.RewardItemRepository;
import ru.gamebot.platform.domain.repository.RewardRequestRepository;

@Service
@RequiredArgsConstructor
public class RewardService {

    private static final String WITHDRAWAL_CATEGORY = "Вывод";
    /** Минимальная сумма вывода в рубли/TON (то же число проверяют бот и мини-апп; здесь — страховка внутри сервиса). */
    private static final long MIN_WITHDRAWAL_EXC = 5_000;

    private final RewardItemRepository rewardItemRepository;
    private final RewardRequestRepository rewardRequestRepository;
    private final UserService userService;
    private final SinkShopService sinkShopService;
    private final HealthRatioService healthRatioService;
    private final ShopLimitService shopLimitService;
    private final EntityManager entityManager;
    private final ExcTransactionService excTx;
    private final AppUserRepository appUserRepository;
    private final ApplicationEventPublisher eventPublisher;

    // ── Защита вывода от мультиаккаунтов (решение владельца 2026-10-03) ──
    /** Вывод открывается не раньше, чем через столько дней после регистрации... */
    public static final int WITHDRAWAL_MIN_ACCOUNT_AGE_DAYS = 3;
    /** ...и после стольких одобренных квестов. Схема «зарегистрировался по приглашению, взял бонусы и вывел за час» не проходит. */
    public static final int WITHDRAWAL_MIN_APPROVED_QUESTS = 3;

    /** null — вывод доступен; иначе понятное игроку объяснение, чего не хватает. */
    public String withdrawalEligibilityBlock(AppUser user) {
        StringBuilder missing = new StringBuilder();
        if (user.getCreatedAt() != null && user.getCreatedAt().plusDays(WITHDRAWAL_MIN_ACCOUNT_AGE_DAYS).isAfter(LocalDateTime.now())) {
            java.time.Duration left = java.time.Duration.between(LocalDateTime.now(), user.getCreatedAt().plusDays(WITHDRAWAL_MIN_ACCOUNT_AGE_DAYS));
            long hours = Math.max(1, left.toHours() + (left.toMinutesPart() > 0 ? 1 : 0));
            missing.append("подождать ещё ").append(hours >= 24 ? (hours / 24) + " дн. " + (hours % 24) + " ч." : hours + " ч.");
        }
        if (user.getCompletedQuests() < WITHDRAWAL_MIN_APPROVED_QUESTS) {
            if (missing.length() > 0) missing.append(" и ");
            missing.append("выполнить ещё ").append(WITHDRAWAL_MIN_APPROVED_QUESTS - user.getCompletedQuests()).append(" кв.");
        }
        if (missing.length() == 0) return null;
        return "Вывод открывается через " + WITHDRAWAL_MIN_ACCOUNT_AGE_DAYS + " дня после регистрации и после "
                + WITHDRAWAL_MIN_APPROVED_QUESTS + " одобренных квестов — так мы защищаем клуб от накруток. Вам нужно: " + missing + ".";
    }

    /** Ключ реквизитов для сравнения между аккаунтами: телефон/карта — только цифры (телефон без +7/8), кошелёк — адрес
     *  в нижнем регистре. Для текста без номера (например, только название банка) ключа нет — такие не сравниваем. */
    static String destinationKey(String details) {
        if (details == null || details.isBlank()) return null;
        String d = details.trim();
        if (d.regionMatches(true, 0, "TON:", 0, 4) || d.startsWith("USDT")) {
            String after = d.contains(":") ? d.substring(d.indexOf(':') + 1) : d;
            String addr = after.split(":rubles=")[0].trim();
            return addr.length() < 20 ? null : "w:" + addr.toLowerCase();
        }
        String digits = d.replaceAll("\\D", "");
        if (digits.length() < 10) return null;
        if (digits.length() == 11 && (digits.startsWith("7") || digits.startsWith("8"))) digits = digits.substring(1);
        return "d:" + digits;
    }

    /** Бросает исключение (и предупреждает админов), если те же реквизиты уже использует другой аккаунт. */
    private void checkDestinationNotShared(AppUser lockedUser, String payoutDetails) {
        String key = destinationKey(payoutDetails);
        if (key == null) return;
        List<AppUser> others = rewardRequestRepository.findActiveWithdrawalsWithDetailsOfOtherUsers(lockedUser.getId()).stream()
                .filter(r -> key.equals(destinationKey(r.getPayoutDetails())))
                .map(RewardRequest::getUser)
                .distinct()
                .toList();
        if (!others.isEmpty()) {
            eventPublisher.publishEvent(new ru.gamebot.platform.event.WithdrawalDestinationConflictEvent(this, lockedUser, others, payoutDetails));
            throw new IllegalArgumentException("Эти реквизиты уже используются другим аккаунтом. Укажите свои реквизиты "
                    + "(один номер или кошелёк — один игрок). Если это ошибка, напишите в поддержку.");
        }
    }

    private void checkWithdrawalAllowed(AppUser lockedUser) {
        String block = withdrawalEligibilityBlock(lockedUser);
        if (block != null) {
            throw new IllegalArgumentException(block);
        }
    }

    public List<RewardItem> findAvailableRewards() {
        // Категория "Вывод" (сейчас — Telegram Stars) сознательно исключена из общего каталога магазина
        // (бот и мини-апп используют этот метод) — это способ вывода, доступный только через меню
        // вывода (shop:group:telegram_stars), где уже применена проверка "1 заявка на вывод в сутки".
        // В общем каталоге эта проверка не срабатывает вообще.
        return rewardItemRepository.findAllByActiveTrueOrderByPriceCoinsAsc().stream()
                .filter(item -> !"Вывод".equals(item.getCategory()))
                .toList();
    }

    public List<RewardItem> findByPurchaseGroup(String purchaseGroup) {
        return rewardItemRepository.findAllByActiveTrueAndPurchaseGroupOrderByPriceCoinsAsc(purchaseGroup);
    }

    public List<RewardItem> findComingSoon() {
        return rewardItemRepository.findAllByComingSoonTrueOrderByTitle();
    }

    public RewardItem getRewardItem(Long rewardId) {
        return rewardItemRepository.findById(rewardId)
                .orElseThrow(() -> new IllegalArgumentException("Награда не найдена."));
    }

    // 3.2 Level B: effective price adjusted by Health Ratio (worse HR → higher EXC price).
    // Сама формула теперь в HealthRatioService — общий источник истины с ShopLimitService
    // (иначе статус товара и реальная проверка лимита при оформлении расходятся, см. её javadoc).
    public long effectivePrice(RewardItem item) {
        return healthRatioService.effectivePrice(item);
    }

    /**
     * Строка пользователя блокируется на всё время проверок лимитов ({@link AppUserRepository#findByIdForUpdate}) —
     * без этого два почти одновременных запроса могли пройти все 4 слоя лимитов до того, как первый
     * успеет сохраниться, и превысить месячный лимит/лимит группы товара на 1 покупку.
     */
    @Transactional
    public RewardRequest createRewardRequest(AppUser user, RewardItem rewardItem) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        // Покупать можно только то, что реально лежит в каталоге: раньше POST /api/shop/items/{id}/purchase принимал ЛЮБОЙ id —
        // в том числе скрытые «виртуальные» позиции чужих выводов («Вывод N EXC → M ₽», active=false), что обходило проверки
        // вывода и позволяло взять старый курс (аудит вывода 2026-10-03).
        if (!rewardItem.isActive() || rewardItem.isComingSoon()) {
            throw new IllegalArgumentException("Эта позиция сейчас недоступна.");
        }
        if (WITHDRAWAL_CATEGORY.equals(rewardItem.getCategory())) {
            // Вывод через каталог — только Telegram Stars; рубли и TON идут своими методами. «1 заявка в сутки» проверяем
            // здесь, под блокировкой игрока: раньше она стояла только на входе в меню и обходилась старыми кнопками и параллельными запросами.
            if (!"telegram_stars".equals(rewardItem.getPurchaseGroup())) {
                throw new IllegalArgumentException("Эта позиция сейчас недоступна.");
            }
            checkWithdrawalAllowed(lockedUser);
            if (hasWithdrawalTodayOrPending(lockedUser)) {
                throw new IllegalArgumentException("Лимит: 1 заявка на вывод в сутки. Следующую можно создать через 24 часа после предыдущей.");
            }
        }

        // 4-layer shop limits check (throws IllegalArgumentException on violation)
        shopLimitService.checkAllLimits(lockedUser, rewardItem);

        boolean isAvatarFrame = "avatar_frame".equals(rewardItem.getPurchaseGroup());

        if (isAvatarFrame && isFrameOwned(lockedUser, rewardItem.getAvatarFrameImage())) {
            throw new IllegalArgumentException("Эта рамка уже в вашей коллекции. Переключитесь бесплатно в профиле.");
        }

        long price = effectivePrice(rewardItem);

        // 3.3 Withdrawal limit check (min 5000 EXC per model)
        if (price < 5_000) {
            price = rewardItem.getPriceCoins(); // small items bypass withdrawal tracking
        }

        if (lockedUser.getCoins() < price) {
            throw new IllegalArgumentException("Недостаточно EXC. Нужно " + price + " EXC.");
        }

        if (!isAvatarFrame) {
            long remaining = sinkShopService.getRemainingWithdrawalLimit(lockedUser);
            if (price > remaining) {
                throw new IllegalArgumentException(
                        "Превышен месячный лимит вывода. Доступно ещё: " + remaining + " EXC.");
            }
        }

        excTx.creditExc(lockedUser, -price, ExcTransactionService.SHOP_BUY, "Покупка: " + rewardItem.getTitle());
        if (!isAvatarFrame) {
            // Рамки не учитываются в месячном лимите трат и не ставят cooldown — по требованию пользователя,
            // они полностью без ограничений и не должны мешать другим покупкам в магазине.
            sinkShopService.recordWithdrawal(lockedUser, price);
            // Вывод в звёздах не должен ставить общий ценовой cooldown — иначе он блокировал бы
            // покупку несвязанных товаров магазина в том же ценовом диапазоне (см. ShopLimitService.checkAllLimits).
            if (!"telegram_stars".equals(rewardItem.getPurchaseGroup())) {
                shopLimitService.recordPurchaseCooldown(lockedUser, rewardItem.getPriceCoins());
            }
        }

        RewardRequest request = new RewardRequest();
        request.setUser(lockedUser);
        request.setRewardItem(rewardItem);
        request.setCreatedAt(LocalDateTime.now());
        request.setDisplayId(rewardRequestRepository.findMaxShopDisplayId() + 1);
        // Снимок реально списанной суммы — rewardItem переиспользуется всеми покупками этой позиции,
        // а effectivePrice меняется вместе с Health Ratio, так что позже (при отмене/отклонении) без
        // этого снимка невозможно узнать, сколько было списано именно в этот раз.
        request.setPaidPriceCoins(price);

        if (rewardItem.getAvatarFrameColor() != null) {
            // Цифровая косметика — применяется мгновенно, без очереди на одобрение администратора
            lockedUser.setAvatarFrameColor(rewardItem.getAvatarFrameColor());
            lockedUser.setAvatarFrameImage(rewardItem.getAvatarFrameImage());
            addOwnedFrame(lockedUser, rewardItem.getAvatarFrameImage());
            userService.save(lockedUser);
            request.setStatus(RewardRequestStatus.APPROVED);
        } else {
            request.setStatus(RewardRequestStatus.PENDING);
        }
        return rewardRequestRepository.save(request);
    }

    public List<RewardRequest> findPendingRequests() {
        List<RewardRequest> pending = rewardRequestRepository.findAllByStatusOrderByCreatedAtAsc(RewardRequestStatus.PENDING);
        List<RewardRequest> inProgress = rewardRequestRepository.findAllByStatusOrderByCreatedAtAsc(RewardRequestStatus.IN_PROGRESS);
        return java.util.stream.Stream.concat(pending.stream(), inProgress.stream())
                .filter(r -> !"Вывод".equals(r.getRewardItem().getCategory()))
                .toList();
    }

    /** Очередь заявок на вывод. Приоритет EGC Pass (одна из привилегий подписки) реализован именно здесь —
     *  подписчики (на момент просмотра очереди) идут первыми, внутри каждой группы порядок по времени
     *  создания. Раньше «приоритет» был лишь бейджем внутри карточки заявки, а сама очередь сортировалась
     *  только по дате — обещание подписки ничего не значило на практике. sorted() у упорядоченного потока
     *  стабилен, поэтому порядок по createdAt внутри групп сохраняется. */
    public List<RewardRequest> findPendingWithdrawals() {
        return rewardRequestRepository.findAllByStatusAndRewardItemCategoryOrderByCreatedAtAsc(
                        RewardRequestStatus.PENDING, "Вывод")
                .stream()
                .sorted(java.util.Comparator.comparing((RewardRequest r) -> !userService.isEgcPassActive(r.getUser())))
                .toList();
    }

    /** Фраза для подтверждения заявки на вывод — только у подписчиков EGC Pass (и только пока подписка
     *  активна). Пустая строка для остальных, чтобы можно было просто дописывать к сообщению. */
    public String withdrawalPriorityNote(AppUser user) {
        return userService.isEgcPassActive(user)
                ? " ⭐ Приоритет EGC Pass: ваша заявка рассматривается в первую очередь, обычно в течение " + PassPayoutSlaService.SLA_HOURS + " часов."
                : "";
    }

    public org.springframework.data.domain.Page<RewardRequest> findWithdrawalHistory(int page) {
        return rewardRequestRepository.findAllByRewardItemCategoryOrderByCreatedAtDesc(
                "Вывод", org.springframework.data.domain.PageRequest.of(page, 10));
    }

    public boolean hasPendingWithdrawal(AppUser user) {
        return rewardRequestRepository.countPendingWithdrawalsByUser(user) > 0;
    }

    public boolean hasWithdrawalTodayOrPending(AppUser user) {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        return rewardRequestRepository.countWithdrawalsByUserSince(user, since) > 0
                || rewardRequestRepository.countPendingWithdrawalsByUser(user) > 0;
    }

    public long countPendingWithdrawalsByUser(AppUser user) {
        return rewardRequestRepository.countPendingWithdrawalsByUser(user);
    }

    public List<RewardRequest> findDuplicateDestinationWithdrawals(RewardRequest req) {
        String pd = req.getPayoutDetails();
        if (pd == null || pd.isBlank()) return List.of();
        String key;
        if (pd.startsWith("TON:")) {
            String after = pd.substring(4);
            int space = after.indexOf(' ');
            key = (space > 0 ? after.substring(0, space) : after).trim();
        } else {
            key = pd.trim();
        }
        if (key.isBlank()) return List.of();
        return rewardRequestRepository.findApprovedWithdrawalsWithPayoutDetailsContaining("%" + key + "%", req.getUser());
    }

    public List<RewardRequest> findUserRequests(AppUser user) {
        return rewardRequestRepository.findAllByUserOrderByCreatedAtDesc(user);
    }

    public long countPendingRequests() {
        return rewardRequestRepository.countNonWithdrawalByStatusIn(
                java.util.List.of(RewardRequestStatus.PENDING, RewardRequestStatus.IN_PROGRESS));
    }

    public long totalPaidOutExc() {
        return rewardRequestRepository.sumApprovedWithdrawalExc();
    }

    public long totalPaidOutExcSince(java.time.LocalDateTime since) {
        return rewardRequestRepository.sumApprovedWithdrawalExcSince(since);
    }

    /** Возвращает [rubTotal, tonRubEquivalent, starsTotal] — суммы в рублях по рублёвым и GRAM-выводам
     *  отдельно, и суммарное число звёзд Telegram по выводам через реселлера. */
    public long[] totalPaidOutRubAndTonRub() {
        return rubAndTonRubOf(rewardRequestRepository.findAllApprovedWithdrawals());
    }

    private long[] rubAndTonRubOf(List<RewardRequest> requests) {
        long rubTotal = 0;
        long tonRubTotal = 0;
        long starsTotal = 0;
        for (var r : requests) {
            String pd = r.getPayoutDetails();
            RewardItem item = r.getRewardItem();
            String title = item != null ? item.getTitle() : "";
            if ("telegram_stars".equals(item != null ? item.getPurchaseGroup() : null)) {
                // "Telegram Stars - 50 ⭐" — номинал сразу после дефиса.
                String num = title.replaceAll(".*- (\\d+).*", "$1");
                try { starsTotal += Long.parseLong(num); } catch (Exception ignored) {}
            } else if (pd != null && (pd.startsWith("TON:") || pd.startsWith("USDT"))) {
                if (pd.contains("rubles=")) {
                    String num = pd.substring(pd.indexOf("rubles=") + 7).split("[^0-9]")[0];
                    try { tonRubTotal += Long.parseLong(num); } catch (Exception ignored) {}
                }
            } else if (title.contains("→") && title.contains("₽")) {
                // "Вывод N EXC → M ₽"
                String part = title.substring(title.lastIndexOf("→") + 2).trim();
                String num = part.split("[^0-9]")[0];
                try { rubTotal += Long.parseLong(num); } catch (Exception ignored) {}
            }
        }
        return new long[]{rubTotal, tonRubTotal, starsTotal};
    }

    /** Одна выплаченная заявка на вывод для аналитики. method: RUB, TON или STARS; rub - сумма из заявки (у Stars и старых заявок 0). */
    public record WithdrawalRow(long userId, LocalDateTime at, long exc, long rub, String method) {}

    public List<WithdrawalRow> approvedWithdrawalRows() {
        List<WithdrawalRow> rows = new java.util.ArrayList<>();
        for (Object[] r : rewardRequestRepository.findApprovedWithdrawalRows()) {
            String group = (String) r[4];
            String details = (String) r[5];
            String method = "telegram_stars".equals(group) ? "STARS"
                    : (details != null && (details.startsWith("TON:") || details.startsWith("USDT"))) ? "TON" : "RUB";
            rows.add(new WithdrawalRow(((Number) r[0]).longValue(), (LocalDateTime) r[1], ((Number) r[2]).longValue(),
                    ((Number) r[3]).longValue(), method));
        }
        return rows;
    }

    public long countUniqueWithdrawalRecipients() {
        return rewardRequestRepository.countDistinctUsersWithApprovedWithdrawals();
    }

    public record WithdrawalPeriodStats(long count, long totalExc, long totalRub, long totalTonRub, long totalStars) {}

    /** Для поста в канал (недельный/месячный отчёт по выполненным выводам) — см. запрос пользователя
     *  2026-09-09. Период — скользящее окно от now-N дней, как и остальные N-дневные метрики в проекте
     *  (см. sendAdminStats), а не календарная неделя/месяц. */
    public WithdrawalPeriodStats withdrawalStatsSince(java.time.LocalDateTime since) {
        List<RewardRequest> requests = rewardRequestRepository.findApprovedWithdrawalsSince(since);
        long totalExc = requests.stream().mapToLong(this::actualPaidPrice).sum();
        long[] rubAndTon = rubAndTonRubOf(requests);
        return new WithdrawalPeriodStats(requests.size(), totalExc, rubAndTon[0], rubAndTon[1], rubAndTon[2]);
    }

    public RewardRequest getRequest(Long requestId) {
        return rewardRequestRepository.findWithUserAndRewardItemById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
    }

    /** Блокирует строку заявки и возвращает её с игроком и позицией. Все изменения статуса идут через этот метод. */
    private RewardRequest lockAndGet(Long requestId) {
        rewardRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
        return getRequest(requestId);
    }

    /** Понятное объяснение, почему с заявкой в этом статусе уже нельзя ничего делать. */
    private static String alreadyHandledMessage(RewardRequestStatus status) {
        return switch (status) {
            case APPROVED -> "Заявка уже выполнена (одобрена) — повторно ничего делать не нужно.";
            case REJECTED -> "Заявка уже отклонена, EXC возвращены игроку.";
            case CANCELLED -> "Заявка отменена игроком, EXC уже возвращены ему. Деньги или награду по ней НЕ выдавайте.";
            default -> "Заявка уже обработана.";
        };
    }

    @Transactional
    public RewardRequest takeInProgressRequest(Long requestId) {
        RewardRequest req = lockAndGet(requestId);
        if (req.getStatus() != RewardRequestStatus.PENDING) {
            throw new IllegalArgumentException(alreadyHandledMessage(req.getStatus()));
        }
        req.setStatus(RewardRequestStatus.IN_PROGRESS);
        return rewardRequestRepository.save(req);
    }

    @Transactional
    public RewardRequest approveRequest(Long requestId) {
        RewardRequest req = lockAndGet(requestId);
        if (req.getStatus() != RewardRequestStatus.PENDING && req.getStatus() != RewardRequestStatus.IN_PROGRESS) {
            throw new IllegalArgumentException(alreadyHandledMessage(req.getStatus()));
        }
        req.setStatus(RewardRequestStatus.APPROVED);
        req.setPaidAt(java.time.LocalDateTime.now());
        if (WITHDRAWAL_CATEGORY.equals(req.getRewardItem().getCategory())) {
            long rubles = parseRubFromTitle(req.getRewardItem().getTitle(), req.getRewardItem().getPriceCoins());
            healthRatioService.deductFromPayoutPool(rubles);
        }
        return rewardRequestRepository.save(req);
    }

    private long parseRubFromTitle(String title, long excFallback) {
        if (title != null && title.contains("→")) {
            try {
                String after = title.substring(title.lastIndexOf('→') + 1).trim();
                return Long.parseLong(after.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {}
        }
        return excFallback / 100;
    }

    /** Сколько EXC реально было списано при создании ЭТОЙ заявки — приоритет у снимка paidPriceCoins
     *  (не совпадает с текущей rewardItem.getPriceCoins()/effectivePrice(), если Health Ratio с момента
     *  покупки изменился, а сам RewardItem — общий переиспользуемый каталог, не персональная копия).
     *  Фолбэк на старую логику — для заявок, созданных до появления этого поля. */
    public long actualPaidPrice(RewardRequest req) {
        if (req.getPaidPriceCoins() != null) {
            return req.getPaidPriceCoins();
        }
        boolean isWithdrawal = "Вывод".equals(req.getRewardItem().getCategory());
        return isWithdrawal ? req.getRewardItem().getPriceCoins() : effectivePrice(req.getRewardItem());
    }

    @Transactional
    public RewardRequest cancelRequest(Long requestId, AppUser requester) {
        RewardRequest req = lockAndGet(requestId);
        // Без этой проверки любой пользователь мог отменить ЧУЖУЮ заявку по угаданному/подобранному ID
        // и получить возврат EXC на СВОЙ баланс — реальная уязвимость, найдена при разработке API кошелька.
        if (!req.getUser().getTelegramId().equals(requester.getTelegramId())) {
            throw new IllegalArgumentException("Это не ваша заявка.");
        }
        if (req.getStatus() != RewardRequestStatus.PENDING) {
            throw new IllegalArgumentException("Заявку можно отменить только в статусе «Ожидает».");
        }
        req.setStatus(RewardRequestStatus.CANCELLED);
        long price = actualPaidPrice(req);
        excTx.creditExc(requester, price, ExcTransactionService.SHOP_REFUND, "Отмена заявки: " + req.getRewardItem().getTitle());
        // reverseWithdrawal for ALL items since recordWithdrawal is called for all in createRewardRequest
        sinkShopService.reverseWithdrawal(requester, price);
        return rewardRequestRepository.save(req);
    }

    @Transactional
    public RewardRequest rejectRequest(Long requestId, String comment) {
        RewardRequest req = lockAndGet(requestId);
        if (req.getStatus() != RewardRequestStatus.PENDING && req.getStatus() != RewardRequestStatus.IN_PROGRESS) {
            throw new IllegalArgumentException(alreadyHandledMessage(req.getStatus()));
        }
        req.setStatus(RewardRequestStatus.REJECTED);
        req.setAdminComment(comment);
        AppUser user = req.getUser();
        boolean isWithdrawal = "Вывод".equals(req.getRewardItem().getCategory());
        long price = actualPaidPrice(req);
        excTx.creditExc(user, price, ExcTransactionService.SHOP_REFUND, "Возврат (отклонение): " + req.getRewardItem().getTitle());
        sinkShopService.reverseWithdrawal(user, price);
        // Отклонение — не вина игрока, поэтому снимаем cooldown по ценовому диапазону товара (Layer 4)
        if (!isWithdrawal) {
            shopLimitService.reverseCooldown(user, req.getRewardItem().getPriceCoins());
        }
        return rewardRequestRepository.save(req);
    }

    @Transactional
    public RewardRequest saveRequest(RewardRequest req) {
        return rewardRequestRepository.save(req);
    }

    public List<RewardItem> findAllRewards() {
        return rewardItemRepository.findAll().stream()
                .filter(i -> !WITHDRAWAL_CATEGORY.equals(i.getCategory()))
                .toList();
    }

    @Transactional
    public RewardItem save(RewardItem item) {
        return rewardItemRepository.save(item);
    }

    @Transactional
    public void deleteRewardItem(Long id) {
        RewardItem item = rewardItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Награда не найдена."));
        rewardRequestRepository.deleteAllByRewardItem(item);
        rewardItemRepository.delete(item);
    }

    @Transactional
    public RewardItem createRewardItem(String title, String description, String category, long priceCoins) {
        return createRewardItem(title, description, category, priceCoins, null);
    }

    @Transactional
    public RewardItem createRewardItem(String title, String description, String category, long priceCoins, String photoFileId) {
        RewardItem item = new RewardItem();
        item.setTitle(title);
        item.setDescription(description);
        item.setPhotoFileId(photoFileId);
        item.setCategory(category);
        item.setPriceCoins(priceCoins);
        item.setActive(true);
        item.setCreatedAt(LocalDateTime.now());
        return rewardItemRepository.save(item);
    }

    /** Строка пользователя блокируется на всё время проверки лимита ({@link AppUserRepository#findByIdForUpdate}) —
     * та же защита от гонки состояний, что и в {@link #createRewardRequest}. */
    @Transactional
    public RewardRequest createTonWithdrawalRequest(AppUser user, long excAmount, long rubles, long fixedRubUsed, String tonWallet) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        checkWithdrawalAllowed(lockedUser);
        if (excAmount < MIN_WITHDRAWAL_EXC) {
            throw new IllegalArgumentException("Минимальная сумма вывода — 5 000 EXC.");
        }
        if (hasWithdrawalTodayOrPending(lockedUser)) {
            throw new IllegalArgumentException("Лимит: 1 заявка на вывод в сутки. Следующую можно создать через 24 часа после предыдущей.");
        }
        checkDestinationNotShared(lockedUser, "TON:" + tonWallet);

        long remaining = sinkShopService.getRemainingWithdrawalLimit(lockedUser);
        if (excAmount > remaining) {
            throw new IllegalArgumentException("Превышен месячный лимит вывода. Доступно ещё: " + remaining + " EXC.");
        }
        if (excAmount > lockedUser.getCoins()) {
            throw new IllegalArgumentException("Недостаточно EXC. Ваш баланс: " + lockedUser.getCoins() + " EXC.");
        }

        RewardItem withdrawItem = new RewardItem();
        withdrawItem.setTitle("Вывод " + excAmount + " EXC → " + rubles + " ₽ (GRAM/TON)");
        withdrawItem.setDescription("Заявка на вывод в GRAM (TON) · Кошелёк: " + tonWallet);
        withdrawItem.setCategory("Вывод");
        withdrawItem.setPriceCoins(excAmount);
        withdrawItem.setActive(false);
        withdrawItem.setCreatedAt(LocalDateTime.now());
        RewardItem saved = rewardItemRepository.save(withdrawItem);

        sinkShopService.recordWithdrawal(lockedUser, excAmount);
        if (fixedRubUsed > 0) {
            lockedUser.setFixedRubBalance(Math.max(0, lockedUser.getFixedRubBalance() - fixedRubUsed));
        }
        excTx.creditExc(lockedUser, -excAmount, ExcTransactionService.WITHDRAWAL, "Вывод → TON");

        RewardRequest request = new RewardRequest();
        request.setUser(lockedUser);
        request.setRewardItem(saved);
        request.setStatus(RewardRequestStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());
        // Префикс "TON:" — новый формат. Старые заявки могли быть сохранены с "USDT·TON:" (до перехода на TON) —
        // код чтения (isCryptoWithdrawal/cryptoWalletFromPayoutDetails в GamePlatformBot) понимает оба варианта.
        request.setPayoutDetails("TON:" + tonWallet + ":rubles=" + rubles);
        request.setDisplayId(rewardRequestRepository.findMaxWithdrawalDisplayId() + 1);
        request.setFixedRubValue(rubles);
        request.setPaidPriceCoins(excAmount);
        return rewardRequestRepository.save(request);
    }

    @Transactional
    public void resetWithdrawalRequestIds() {
        // Find all withdrawal reward items
        List<RewardItem> withdrawalItems = rewardItemRepository.findAll().stream()
                .filter(i -> "Вывод".equals(i.getCategory()))
                .toList();
        // Delete all requests referencing these items first (FK constraint)
        for (RewardItem item : withdrawalItems) {
            rewardRequestRepository.deleteAllByRewardItem(item);
        }
        // Then delete the virtual reward items
        rewardItemRepository.deleteAll(withdrawalItems);
        // Flush so H2 sees the deletes before DDL
        entityManager.flush();
        // Reset reward_request identity sequence (H2 stores table as lowercase quoted)
        entityManager.createNativeQuery(
                "ALTER TABLE REWARD_REQUESTS ALTER COLUMN id RESTART WITH 1")
                .executeUpdate();
    }

    /** Строка пользователя блокируется на всё время проверки лимита ({@link AppUserRepository#findByIdForUpdate}) —
     * та же защита от гонки состояний, что и в {@link #createRewardRequest}. Лимит/баланс проверяются здесь
     * повторно (defense-in-depth) — раньше единственная проверка была на шаге ввода суммы, ДО этого метода. */
    @Transactional
    public RewardRequest createWithdrawalRequestWithDetails(AppUser user, long excAmount, long rubles, long fixedRubUsed, String payoutDetails) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        checkWithdrawalAllowed(lockedUser);
        if (excAmount < MIN_WITHDRAWAL_EXC) {
            throw new IllegalArgumentException("Минимальная сумма вывода — 5 000 EXC.");
        }
        if (hasWithdrawalTodayOrPending(lockedUser)) {
            throw new IllegalArgumentException("Лимит: 1 заявка на вывод в сутки. Следующую можно создать через 24 часа после предыдущей.");
        }
        checkDestinationNotShared(lockedUser, payoutDetails);

        long remaining = sinkShopService.getRemainingWithdrawalLimit(lockedUser);
        if (excAmount > remaining) {
            throw new IllegalArgumentException("Превышен месячный лимит вывода. Доступно ещё: " + remaining + " EXC.");
        }
        if (excAmount > lockedUser.getCoins()) {
            throw new IllegalArgumentException("Недостаточно EXC. Ваш баланс: " + lockedUser.getCoins() + " EXC.");
        }

        RewardItem withdrawItem = new RewardItem();
        withdrawItem.setTitle("Вывод " + excAmount + " EXC → " + rubles + " ₽");
        withdrawItem.setDescription("Заявка на вывод средств");
        withdrawItem.setCategory("Вывод");
        withdrawItem.setPriceCoins(excAmount);
        withdrawItem.setActive(false);
        withdrawItem.setCreatedAt(LocalDateTime.now());
        RewardItem saved = rewardItemRepository.save(withdrawItem);

        sinkShopService.recordWithdrawal(lockedUser, excAmount);
        if (fixedRubUsed > 0) {
            lockedUser.setFixedRubBalance(Math.max(0, lockedUser.getFixedRubBalance() - fixedRubUsed));
        }
        excTx.creditExc(lockedUser, -excAmount, ExcTransactionService.WITHDRAWAL, "Вывод → " + rubles + " ₽");

        RewardRequest request = new RewardRequest();
        request.setUser(lockedUser);
        request.setRewardItem(saved);
        request.setStatus(RewardRequestStatus.PENDING);
        request.setPayoutDetails(payoutDetails);
        request.setCreatedAt(LocalDateTime.now());
        request.setDisplayId(rewardRequestRepository.findMaxWithdrawalDisplayId() + 1);
        request.setFixedRubValue(rubles);
        request.setPaidPriceCoins(excAmount);
        return rewardRequestRepository.save(request);
    }

    public boolean isFrameOwned(AppUser user, String frameKey) {
        if (frameKey == null || user.getOwnedFramesCsv() == null) return false;
        return Arrays.asList(user.getOwnedFramesCsv().split(",")).contains(frameKey);
    }

    public void addOwnedFrame(AppUser user, String frameKey) {
        if (frameKey == null) return;
        String csv = user.getOwnedFramesCsv();
        if (csv == null || csv.isBlank()) {
            user.setOwnedFramesCsv(frameKey);
        } else if (!Arrays.asList(csv.split(",")).contains(frameKey)) {
            user.setOwnedFramesCsv(csv + "," + frameKey);
        }
    }

}
