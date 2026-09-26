package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.ChannelPostDraft;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.Squad;
import ru.gamebot.platform.domain.repository.SquadRepository;
import ru.gamebot.platform.event.SquadPrizeEvent;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.domain.repository.RewardRequestRepository;
import ru.gamebot.platform.event.TournamentCancelledEvent;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;
import ru.gamebot.platform.domain.repository.TournamentRepository;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import org.springframework.context.event.EventListener;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.repository.RewardItemRepository;
import ru.gamebot.platform.event.HallOfFameEvent;
import ru.gamebot.platform.event.LeagueWeekEvent;
import ru.gamebot.platform.event.ReferralLeaderboardRewardEvent;
import ru.gamebot.platform.domain.repository.ChannelPostDraftRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.ChannelPostDraftEvent;

/**
 * Автоматический контент для канала по разделу «Квесты и игры» (решение владельца 2026-09-26): два типа постов - «новые квесты» и «топ квестов
 * недели». Каждый пост сначала уходит админам на согласование (карточка с ✅/✏️/❌, картинку админ добавляет сам через «Изменить»),
 * в канал публикуется только после ✅. Расписание по умолчанию ВЫКЛЮЧЕНО (время выберем, когда определимся со всеми автопостами) -
 * пока работает ручная кнопка «Сформировать сейчас» в админке («Коммуникации» → «Контент канала»).
 * Настройки и «отметка воды» лежат в app_settings. Безопасность на накопленных данных: первый автозапуск «новых квестов» без отметки
 * не выдаёт весь бэклог, а только ставит отметку; квесты, уже попавшие в любой черновик, не повторяются.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelContentService {

    public static final String NEW_QUESTS = "NEW_QUESTS";
    public static final String TOP_QUESTS_WEEK = "TOP_QUESTS_WEEK";
    public static final String SQUAD_MIDWEEK = "SQUAD_MIDWEEK";
    public static final String SQUAD_RESULTS = "SQUAD_RESULTS";
    public static final String SQUAD_STATS = "SQUAD_STATS";
    /** Порядок типов в админке. */
    public static final String TOURNEY_REG_CLOSING = "TOURNEY_REG_CLOSING";
    public static final String TOURNEY_ACTIVE = "TOURNEY_ACTIVE";
    public static final String TOURNEY_CANCELLED = "TOURNEY_CANCELLED";
    public static final String WITHDRAW_SUMMARY = "WITHDRAW_SUMMARY";
    public static final String WITHDRAW_MILESTONE = "WITHDRAW_MILESTONE";
    public static final String WITHDRAW_HOWTO = "WITHDRAW_HOWTO";
    public static final String WITHDRAW_PROOF = "WITHDRAW_PROOF";
    public static final String HALL_OF_FAME = "HALL_OF_FAME";
    public static final String WEEKLY_RACE = "WEEKLY_RACE";
    public static final String LEAGUES_WEEK = "LEAGUES_WEEK";
    public static final String SHOP_NEW = "SHOP_NEW";
    public static final String EGCPASS_PERK = "EGCPASS_PERK";
    public static final String SHOP_POPULAR = "SHOP_POPULAR";
    public static final String SHOP_ITEMS = "SHOP_ITEMS";
    public static final String REFERRAL_TOP = "REFERRAL_TOP";
    public static final String REFERRAL_HOWTO = "REFERRAL_HOWTO";
    public static final String REFERRAL_STATS = "REFERRAL_STATS";
    public static final List<String> ALL_TYPES = List.of(NEW_QUESTS, TOP_QUESTS_WEEK, SQUAD_MIDWEEK, SQUAD_RESULTS, SQUAD_STATS,
            TOURNEY_REG_CLOSING, TOURNEY_ACTIVE, TOURNEY_CANCELLED, WITHDRAW_SUMMARY, WITHDRAW_MILESTONE, WITHDRAW_HOWTO, WITHDRAW_PROOF,
            HALL_OF_FAME, WEEKLY_RACE, LEAGUES_WEEK, SHOP_NEW, EGCPASS_PERK, SHOP_POPULAR, SHOP_ITEMS,
            REFERRAL_TOP, REFERRAL_HOWTO, REFERRAL_STATS);

    /** Как часто повторяется расписание типа, в днях: 0 - каждый день, 7 - раз в неделю, 14 и 28 - раз в две и в четыре недели. */
    public static int intervalDays(String type) {
        return switch (type) {
            case NEW_QUESTS, SHOP_NEW -> 0;
            case SQUAD_STATS, WITHDRAW_SUMMARY, SHOP_POPULAR, REFERRAL_STATS -> 14;
            case WITHDRAW_HOWTO, EGCPASS_PERK, SHOP_ITEMS, REFERRAL_HOWTO -> 28;
            default -> 7;
        };
    }

    /** Типы без часа в расписании: создаются по событию или по срокам турнира (за 24 ч до старта/финиша), в админке у них только переключатель. */
    public static boolean isEventType(String type) {
        return SQUAD_RESULTS.equals(type) || TOURNEY_REG_CLOSING.equals(type) || TOURNEY_ACTIVE.equals(type) || TOURNEY_CANCELLED.equals(type)
                || WITHDRAW_MILESTONE.equals(type) || WITHDRAW_PROOF.equals(type) || HALL_OF_FAME.equals(type) || LEAGUES_WEEK.equals(type) || REFERRAL_TOP.equals(type);
    }

    /** Событийные посты недельного итога: данные фиксируются в момент сброса недели (понедельник 00:00 UTC), а карточка админу приходит в назначенный день и час (настраивается в админке). */
    public static boolean isDelayedEvent(String type) {
        return HALL_OF_FAME.equals(type) || SQUAD_RESULTS.equals(type) || LEAGUES_WEEK.equals(type) || REFERRAL_TOP.equals(type);
    }

    private static final int TOURNEY_REMIND_HOURS = 24;
    /** Автопост «отряды в цифрах» не формируется, если активных отрядов меньше (решение владельца 2026-09-26). */
    private static final int MIN_SQUADS_FOR_STATS = 10;

    /** Автопост «топ недели» не формируется, если за неделю выполнено меньше (не выдаём слабые цифры за успех). Ручная кнопка порог игнорирует. */
    private static final long MIN_WEEK_COMPLETIONS = 10;
    /** «Гонка за Зал славы» и «Лиги недели» не публикуем, если за неделю XP набрали меньше игроков (решение владельца 2026-09-26). Ручная кнопка порог игнорирует. */
    private static final int MIN_ACTIVE_FOR_WEEK_POSTS = 10;
    /** «Популярное в магазине» не формируется, если за 14 дней заказов каталога меньше. */
    private static final long MIN_SHOP_ORDERS = 10;
    /** Цена EGC Pass в Stars: держать в синхроне с EGC_PASS_STARS_PRICE в GamePlatformBot. */
    private static final int EGC_PASS_STARS = 150;
    private static final int MAX_SHOP_ITEMS_IN_POST = 5;
    /** «Топ рефереров недели» не создаём, если призёров меньше (слабая неделя). */
    private static final int MIN_REFERRAL_WINNERS = 3;
    /** «Рефералы в цифрах» не формируется, если за 14 дней по приглашениям пришло меньше игроков. Ручная кнопка порог игнорирует. */
    private static final long MIN_REFERRED_FOR_STATS = 10;
    private static final int MAX_GAMES_IN_NEW_POST = 5;
    private static final int MAX_QUESTS_PER_GAME = 3;

    private final QuestRepository questRepository;
    private final QuestSubmissionRepository submissionRepository;
    private final ChannelPostDraftRepository draftRepository;
    private final AppSettingRepository settingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SquadService squadService;
    private final SquadRepository squadRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentService tournamentService;
    private final RewardService rewardService;
    private final RewardRequestRepository rewardRequestRepository;
    private final AppUserRepository appUserRepository;
    private final RewardItemRepository rewardItemRepository;

    @Value("${app.bot-username:}")
    private String botUsername;

    public record TypeSettings(boolean enabled, int hour, int dayOfWeek, String lastRun) {}

    // ───────────────────────── настройки ─────────────────────────

    private String get(String key) {
        return settingRepository.findById(key).map(AppSetting::getValue).orElse(null);
    }

    private void put(String key, String value) {
        AppSetting s = settingRepository.findById(key).orElseGet(AppSetting::new);
        s.setKey(key);
        s.setValue(value);
        settingRepository.save(s);
    }

    private static String prefix(String type) {
        return switch (type) {
            case NEW_QUESTS -> "cc.newq.";
            case TOP_QUESTS_WEEK -> "cc.topw.";
            case SQUAD_MIDWEEK -> "cc.sqmid.";
            case SQUAD_RESULTS -> "cc.sqres.";
            case TOURNEY_REG_CLOSING -> "cc.trc.";
            case TOURNEY_ACTIVE -> "cc.tra.";
            case TOURNEY_CANCELLED -> "cc.trx.";
            case WITHDRAW_SUMMARY -> "cc.wsum.";
            case WITHDRAW_MILESTONE -> "cc.wmil.";
            case WITHDRAW_HOWTO -> "cc.whow.";
            case WITHDRAW_PROOF -> "cc.wprf.";
            case HALL_OF_FAME -> "cc.hof.";
            case WEEKLY_RACE -> "cc.race.";
            case LEAGUES_WEEK -> "cc.lgw.";
            case SHOP_NEW -> "cc.shopn.";
            case EGCPASS_PERK -> "cc.pass.";
            case SHOP_POPULAR -> "cc.shopp.";
            case SHOP_ITEMS -> "cc.shopi.";
            case REFERRAL_TOP -> "cc.reftop.";
            case REFERRAL_HOWTO -> "cc.refhow.";
            case REFERRAL_STATS -> "cc.refstat.";
            default -> "cc.sqstat.";
        };
    }

    /** Все автопосты включены по умолчанию (2026-09-26, сетка согласована с владельцем): каждый всё равно идёт админу на согласование, а расписание задаёт день и час; отключить любой можно в админке. */
    private static boolean defaultEnabled(String type) {
        return true;
    }

    public TypeSettings settings(String type) {
        String p = prefix(type);
        // Сетка (UTC; МСК = UTC+3): дефолты согласованы с владельцем 2026-09-26.
        int defHour = switch (type) {
            case TOP_QUESTS_WEEK, SQUAD_MIDWEEK, WEEKLY_RACE, SHOP_POPULAR, REFERRAL_STATS, HALL_OF_FAME, LEAGUES_WEEK -> 9;
            case SQUAD_STATS, WITHDRAW_SUMMARY, WITHDRAW_HOWTO, EGCPASS_PERK, SHOP_ITEMS, SQUAD_RESULTS, REFERRAL_TOP -> 15;
            case REFERRAL_HOWTO -> 6;
            case SHOP_NEW -> 16;
            default -> 12;
        };
        int defDow = switch (type) {
            case HALL_OF_FAME, SQUAD_RESULTS -> 1;
            case LEAGUES_WEEK, REFERRAL_TOP -> 2;
            case SQUAD_MIDWEEK, EGCPASS_PERK -> 3;
            case WEEKLY_RACE, WITHDRAW_HOWTO -> 4;
            case TOP_QUESTS_WEEK, SQUAD_STATS -> 5;
            case SHOP_POPULAR, SHOP_ITEMS -> 6;
            case WITHDRAW_SUMMARY, REFERRAL_HOWTO, REFERRAL_STATS -> 7;
            default -> 1;
        };
        String en = get(p + "enabled");
        int hour = parseInt(get(p + "hour"), defHour);
        int dow = parseInt(get(p + "dow"), defDow);
        return new TypeSettings(en == null ? defaultEnabled(type) : "1".equals(en), Math.max(0, Math.min(23, hour)), Math.max(1, Math.min(7, dow)), get(p + "last"));
    }

    private static int parseInt(String v, int def) {
        try { return v == null ? def : Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public boolean toggleEnabled(String type) {
        boolean now = !settings(type).enabled();
        put(prefix(type) + "enabled", now ? "1" : "0");
        return now;
    }

    public int shiftHour(String type, int delta) {
        int h = (settings(type).hour() + delta + 24) % 24;
        put(prefix(type) + "hour", String.valueOf(h));
        return h;
    }

    public int nextDayOfWeek(String type) {
        int d = settings(type).dayOfWeek() % 7 + 1;
        put(prefix(type) + "dow", String.valueOf(d));
        return d;
    }

    public long pendingCount() {
        return draftRepository.countByStatus(ChannelPostDraft.PENDING);
    }

    // ───────────────────────── черновики ─────────────────────────

    public Optional<ChannelPostDraft> findDraft(Long id) {
        return draftRepository.findById(id);
    }

    public void updateText(Long id, String text) {
        draftRepository.findById(id).ifPresent(d -> {
            d.setPostText(text.length() > 4000 ? text.substring(0, 4000) : text);
            draftRepository.save(d);
        });
    }

    public void updatePhoto(Long id, String fileId) {
        draftRepository.findById(id).ifPresent(d -> {
            d.setPhotoFileId(fileId);
            draftRepository.save(d);
        });
    }

    public void markPublished(Long id) {
        draftRepository.findById(id).ifPresent(d -> {
            d.setStatus(ChannelPostDraft.PUBLISHED);
            d.setPublishedAt(LocalDateTime.now());
            draftRepository.save(d);
        });
    }

    public void markRejected(Long id) {
        draftRepository.findById(id).ifPresent(d -> {
            d.setStatus(ChannelPostDraft.REJECTED);
            draftRepository.save(d);
        });
    }

    private ChannelPostDraft saveDraft(String type, String text, String meta) {
        return saveDraft(type, text, meta, null);
    }

    private ChannelPostDraft saveDraft(String type, String text, String meta, String photoFileId) {
        ChannelPostDraft d = new ChannelPostDraft();
        d.setPhotoFileId(photoFileId);
        d.setType(type);
        d.setPostText(text.length() > 4000 ? text.substring(0, 4000) : text);
        d.setMeta(meta);
        if (isDelayedEvent(type)) d.setSendAfter(nextSlot(type));
        d = draftRepository.save(d);
        if (d.getSendAfter() != null && d.getSendAfter().isAfter(LocalDateTime.now())) {
            log.info("[ChannelContent] Draft {} ({}) created, card will be sent at {} UTC", d.getId(), type, d.getSendAfter());
            return d; // карточку разошлёт deliverDueCards() в назначенный час
        }
        try {
            eventPublisher.publishEvent(new ChannelPostDraftEvent(this, d.getId()));
        } catch (Exception e) {
            log.warn("[ChannelContent] Failed to publish draft event {}", d.getId(), e);
        }
        return d;
    }

    // ───────────────────────── расписание ─────────────────────────

    /** Ближайший день недели и час типа (по настройкам), не раньше текущего момента. */
    private LocalDateTime nextSlot(String type) {
        TypeSettings s = settings(type);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime slot = now.toLocalDate().atTime(s.hour(), 0);
        int diff = (s.dayOfWeek() - now.getDayOfWeek().getValue() + 7) % 7;
        slot = slot.plusDays(diff);
        if (!slot.isAfter(now)) slot = slot.plusDays(7);
        return slot;
    }

    /** Рассылает админам карточки событийных постов, у которых наступил назначенный час. */
    private void deliverDueCards() {
        LocalDateTime now = LocalDateTime.now();
        for (ChannelPostDraft d : draftRepository.findAllByStatusAndCardSentAtIsNull(ChannelPostDraft.PENDING)) {
            if (d.getSendAfter() == null || d.getSendAfter().isAfter(now)) continue;
            try {
                d.setCardSentAt(now);
                draftRepository.save(d);
                eventPublisher.publishEvent(new ChannelPostDraftEvent(this, d.getId()));
            } catch (Exception e) {
                log.error("[ChannelContent] Failed to deliver card for draft {}", d.getId(), e);
            }
        }
    }

    /** Разведение раз-в-2-недели и раз-в-4-недели постов по разным неделям (индекс недели от понедельника): по неделям набор не совпадает. */
    private static boolean phaseOk(String type) {
        long week = (LocalDate.now().toEpochDay() - 4) / 7; // 1970-01-05 - понедельник
        return switch (type) {
            case SQUAD_STATS, REFERRAL_STATS -> week % 2 == 0;
            case SHOP_POPULAR, WITHDRAW_SUMMARY -> week % 2 == 1;
            case EGCPASS_PERK -> week % 4 == 0;
            case WITHDRAW_HOWTO -> week % 4 == 1;
            case SHOP_ITEMS -> week % 4 == 2;
            case REFERRAL_HOWTO -> week % 4 == 3;
            default -> true;
        };
    }

    /** Раз в 10 минут: если автопост включён, наступил его час (по времени сервера, UTC) и сегодня ещё не формировался - делаем черновик. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        try {
            deliverDueCards();
        } catch (Exception e) {
            log.error("[ChannelContent] deliverDueCards failed", e);
        }
        for (String type : ALL_TYPES) {
            if (isEventType(type)) continue; // событийные/по срокам турнира: см. onSquadPrize, tournamentTick, onTournamentCancelled
            try {
                TypeSettings s = settings(type);
                if (!s.enabled() || now.getHour() != s.hour()) continue;
                boolean weekly = !NEW_QUESTS.equals(type) && !SHOP_NEW.equals(type);
                if (weekly && now.getDayOfWeek().getValue() != s.dayOfWeek()) continue;
                if (!phaseOk(type)) continue;
                String today = LocalDate.now().toString();
                if (today.equals(s.lastRun())) continue;
                int every = intervalDays(type);
                if (every > 7 && s.lastRun() != null) {
                    try {
                        if (LocalDate.parse(s.lastRun()).isAfter(LocalDate.now().minusDays(every))) continue; // раз в 2 или 4 недели
                    } catch (Exception ignored) { }
                }
                put(prefix(type) + "last", today);
                switch (type) {
                    case NEW_QUESTS -> createNewQuestsDraft(false);
                    case TOP_QUESTS_WEEK -> createTopQuestsDraft(false);
                    case SQUAD_MIDWEEK -> createSquadMidweekDraft(false);
                    case WITHDRAW_SUMMARY -> createWithdrawSummaryDraft(false);
                    case WITHDRAW_HOWTO -> createWithdrawHowToDraft();
                    case WEEKLY_RACE -> createWeeklyRaceDraft(false);
                    case SHOP_NEW -> createShopNewDraft(false);
                    case EGCPASS_PERK -> createEgcPassDraft();
                    case SHOP_POPULAR -> createShopPopularDraft(false);
                    case SHOP_ITEMS -> createShopItemsDraft();
                    case REFERRAL_HOWTO -> createReferralHowToDraft();
                    case REFERRAL_STATS -> createReferralStatsDraft(false);
                    default -> createSquadStatsDraft(false);
                }
            } catch (Exception e) {
                log.error("[ChannelContent] Scheduled run failed for {}", type, e);
            }
        }
    }

    // ───────────────────────── «новые квесты» ─────────────────────────

    private static boolean plainGameQuest(Quest q) {
        String g = q.getGameName();
        return q.isActive() && !q.isSponsored() && !q.isExternalAutoApprove() && q.getSponsorId() == null
                && g != null && !g.isBlank() && !"UGC".equalsIgnoreCase(g)
                && !g.contains("://") && !g.contains("t.me") && !g.matches(".*\\S/\\S.*");
    }

    private static boolean autoVerified(Quest q) {
        return q.getBrawlVerifyType() != null || q.getClashVerifyType() != null || q.getClashRoyaleVerifyType() != null
                || q.getDotaVerifyType() != null || q.getCs2VerifyType() != null || q.getPubgVerifyType() != null;
    }

    /** @param force true - ручной запуск из админки (без отметки берёт квесты за 3 дня); false - автозапуск (без отметки только ставит её). */
    public Optional<ChannelPostDraft> createNewQuestsDraft(boolean force) {
        LocalDateTime now = LocalDateTime.now();
        String wmRaw = get("cc.newq.wm");
        LocalDateTime since;
        if (wmRaw == null) {
            if (!force) {
                put("cc.newq.wm", now.toString());
                log.info("[ChannelContent] NEW_QUESTS watermark initialised, no post on first auto run");
                return Optional.empty();
            }
            since = now.minusDays(3);
        } else {
            try { since = LocalDateTime.parse(wmRaw); } catch (Exception e) { since = now.minusDays(3); }
        }
        Set<Long> already = new HashSet<>();
        for (ChannelPostDraft d : draftRepository.findAllByType(NEW_QUESTS)) {
            if (d.getMeta() == null) continue;
            for (String id : d.getMeta().split(",")) {
                try { already.add(Long.parseLong(id.trim())); } catch (NumberFormatException ignored) { }
            }
        }
        final LocalDateTime from = since;
        List<Quest> fresh = questRepository.findAll().stream()
                .filter(ChannelContentService::plainGameQuest)
                .filter(q -> q.getCreatedAt() != null && !q.getCreatedAt().isBefore(from))
                .filter(q -> !already.contains(q.getId()))
                .sorted(Comparator.comparingLong(Quest::getRewardCoins).reversed())
                .toList();
        put("cc.newq.wm", now.toString());
        if (fresh.isEmpty()) return Optional.empty();

        Map<String, List<Quest>> byGame = new LinkedHashMap<>();
        fresh.stream().collect(Collectors.groupingBy(Quest::getGameName)).entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .forEach(e -> byGame.put(e.getKey(), e.getValue()));

        StringBuilder sb = new StringBuilder("🆕 <b>новые квесты в клубе</b>\n\n");
        sb.append("Добавили <b>").append(fresh.size()).append("</b> ").append(plural(fresh.size(), "квест", "квеста", "квестов")).append(":\n\n");
        int shownGames = 0;
        boolean anyAuto = false;
        for (Map.Entry<String, List<Quest>> e : byGame.entrySet()) {
            if (shownGames++ >= MAX_GAMES_IN_NEW_POST) break;
            List<Quest> qs = e.getValue();
            sb.append("🎮 <b>").append(esc(e.getKey())).append("</b>\n");
            int n = 0;
            for (Quest q : qs) {
                if (n++ >= MAX_QUESTS_PER_GAME) break;
                boolean auto = autoVerified(q);
                anyAuto |= auto;
                sb.append("• ").append(esc(q.getTitle())).append(" — <b>").append(num(q.getRewardCoins())).append(" EXC</b>").append(auto ? " ⚡" : "").append("\n");
            }
            if (qs.size() > MAX_QUESTS_PER_GAME) sb.append("и ещё ").append(qs.size() - MAX_QUESTS_PER_GAME).append("\n");
            sb.append("\n");
        }
        int hiddenGames = byGame.size() - MAX_GAMES_IN_NEW_POST;
        if (hiddenGames > 0) sb.append("А ещё квесты в других играх: ").append(hiddenGames).append("\n\n");
        if (anyAuto) sb.append("⚡ — бот проверяет прогресс сам, отчёт не нужен.\n\n");
        sb.append(ending(fresh.size(), "Какой возьмёшь первым?", "Ставь 🔥, если уже выбрал.", "Все они уже ждут в боте."));
        sb.append(botLink());
        String meta = fresh.stream().map(q -> String.valueOf(q.getId())).collect(Collectors.joining(","));
        return Optional.of(saveDraft(NEW_QUESTS, sb.toString(), meta));
    }

    // ───────────────────────── «топ квестов недели» ─────────────────────────

    public Optional<ChannelPostDraft> createTopQuestsDraft(boolean force) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        long total = submissionRepository.countApprovedSince(since);
        if (!force && total < MIN_WEEK_COMPLETIONS) {
            log.info("[ChannelContent] TOP_QUESTS_WEEK skipped: only {} completions this week", total);
            return Optional.empty();
        }
        List<Object[]> rows = submissionRepository.findTopQuestsByCompletionsSince(since);
        List<String> lines = new ArrayList<>();
        String[] marks = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
        for (Object[] r : rows) {
            if (lines.size() >= 5) break;
            Long questId = ((Number) r[0]).longValue();
            Quest q = questRepository.findById(questId).orElse(null);
            if (q == null || !plainGameQuest(q)) continue;
            long cnt = ((Number) r[4]).longValue();
            lines.add(marks[lines.size()] + " " + esc(q.getTitle()) + "\n     " + esc(q.getGameName()) + " · <b>" + cnt + "</b> " + plural((int) cnt, "выполнение", "выполнения", "выполнений"));
        }
        if (lines.isEmpty()) return Optional.empty();
        StringBuilder sb = new StringBuilder("🏆 <b>топ квестов недели</b>\n\n");
        sb.append("За неделю игроки выполнили <b>").append(num(total)).append("</b> ").append(plural((int) Math.min(total, Integer.MAX_VALUE), "квест", "квеста", "квестов")).append(". Чаще всего брали:\n\n");
        sb.append(String.join("\n\n", lines)).append("\n\n");
        sb.append(ending(total, "Что возьмёшь ты?", "Ставь 🔥, если уже проходил.", "Новая неделя уже началась."));
        sb.append(botLink());
        return Optional.of(saveDraft(TOP_QUESTS_WEEK, sb.toString(), null));
    }

    // ───────────────────────── отряды ─────────────────────────

    private static final String[] BAD_WORDS = {"хуй", "хуе", "хуя", "пизд", "ебан", "ебат", "ёбан", "ёбат", "бляд", "блят", "сука", "сучк",
            "мудак", "мудил", "пидор", "пидар", "гандон", "залуп", "шлюх", "нацист", "fuck", "shit", "nigg"};

    /** Название отряда придумывает игрок: в автопост не берём ссылки, @упоминания и явную брань (админ всё равно смотрит пост перед публикацией). */
    private static String safeName(String name) {
        if (name == null || name.isBlank()) return null;
        String low = name.toLowerCase(Locale.ROOT);
        if (low.contains("://") || low.contains("t.me") || low.contains("@") || low.contains("www.")
                || low.matches(".*\\w\\.(ru|com|net|org|io|me|gg|ly|tv|xyz|club|site)\\b.*")) return null;
        for (String w : BAD_WORDS) if (low.contains(w)) return null;
        return name.trim();
    }

    private String squadLink() {
        if (botUsername == null || botUsername.isBlank()) return "";
        return "\n\n⚔️ Отряды - в нашем боте: <a href=\"https://t.me/" + botUsername + "\">@" + botUsername + "</a>";
    }

    /** «Гонка отрядов - экватор недели» (раньше тизер жил в памяти бота): топ-5 недельного рейтинга, отставание второго от лидера, приз. */
    public Optional<ChannelPostDraft> createSquadMidweekDraft(boolean force) {
        List<SquadService.SquadRankEntry> top = squadService.getLeaderboard().stream()
                .filter(e -> e.memberCount() >= 2 && safeName(e.squad().getName()) != null)
                .limit(5).toList();
        if (top.isEmpty() || (!force && top.size() < 2)) return Optional.empty();
        String[] marks = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
        StringBuilder sb = new StringBuilder("🛡️ <b>гонка отрядов - экватор недели</b>\n\n");
        for (int i = 0; i < top.size(); i++) {
            SquadService.SquadRankEntry e = top.get(i);
            sb.append(marks[i]).append(" <b>").append(esc(safeName(e.squad().getName()))).append("</b>\n")
              .append("     ").append(num(e.weeklyXp())).append(" XP · ").append(e.memberCount()).append(" ").append(plural((int) e.memberCount(), "чел.", "чел.", "чел.")).append("\n\n");
        }
        if (top.size() >= 2) {
            long gap = top.get(0).weeklyXp() - top.get(1).weeklyXp();
            sb.append("До лидера второму отряду не хватает <b>").append(num(gap)).append(" XP</b>.\n\n");
        }
        sb.append("🏆 Приз победителю - <b>").append(num(SquadService.WEEKLY_PRIZE_POOL)).append(" EXC</b>: их делят лучшие по опыту участники отряда. ")
          .append("Неделя закончится в понедельник в 00:00 UTC (03:00 по Москве).\n\n");
        sb.append(ending(top.get(0).weeklyXp(), "Кто успеет подтянуться?", "Ставь ⚔️, если твой отряд в гонке.", "Ещё есть время подняться выше."));
        sb.append(squadLink());
        return Optional.of(saveDraft(SQUAD_MIDWEEK, sb.toString(), null));
    }

    /** «Итоги недели у отрядов»: создаётся в момент выплаты приза (понедельник 00:00 UTC, до сброса недельных очков), публикуется после согласования. */
    @EventListener
    public void onSquadPrize(SquadPrizeEvent e) {
        try {
            if (!settings(SQUAD_RESULTS).enabled()) return;
            createSquadResultsDraft(e.getSquad(), e.getTotalWeeklyXp(), e.getMembers().size(), e.getPrizePerMember());
        } catch (Exception ex) {
            log.error("[ChannelContent] Failed to create squad results draft", ex);
        }
    }

    private Optional<ChannelPostDraft> createSquadResultsDraft(Squad winner, long winnerXp, int winnersCount, long prizePerMember) {
        String winnerName = safeName(winner.getName());
        long members = squadService.memberCount(winner);
        StringBuilder sb = new StringBuilder("🏆 <b>итоги недели у отрядов</b>\n\n");
        sb.append("Победил отряд <b>").append(winnerName != null ? "«" + esc(winnerName) + "»" : "без публичного названия").append("</b>: ")
          .append(num(winnerXp)).append(" XP, ").append(members).append(" ").append(plural((int) members, "игрок", "игрока", "игроков")).append(".\n");
        sb.append("Приз <b>").append(num(SquadService.WEEKLY_PRIZE_POOL)).append(" EXC</b> поделили ").append(winnersCount).append(" ")
          .append(plural(winnersCount, "лучший участник", "лучших участника", "лучших участников")).append(" - по <b>").append(num(prizePerMember)).append(" EXC</b>.\n\n");
        List<SquadService.SquadRankEntry> rest = squadService.getLeaderboard().stream()
                .filter(x -> !x.squad().getId().equals(winner.getId()) && safeName(x.squad().getName()) != null).limit(2).toList();
        String[] marks = {"🥈", "🥉"};
        for (int i = 0; i < rest.size(); i++) {
            sb.append(marks[i]).append(" «").append(esc(safeName(rest.get(i).squad().getName()))).append("» - ").append(num(rest.get(i).weeklyXp())).append(" XP\n");
        }
        if (!rest.isEmpty()) sb.append("\n");
        sb.append(ending(winnerXp, "Кто начнёт гонку заново первым?", "Ставь ⚔️, если готов отбить первое место.", "Новая неделя уже началась."));
        sb.append(squadLink());
        return Optional.of(saveDraft(SQUAD_RESULTS, sb.toString(), null));
    }

    /** «Отряды в цифрах»: сколько отрядов и игроков в них, новые за неделю, самый большой отряд. Автопост - только если отрядов не меньше порога. */
    public Optional<ChannelPostDraft> createSquadStatsDraft(boolean force) {
        List<Squad> active = squadRepository.findAllByStatus("ACTIVE");
        if (active.isEmpty() || (!force && active.size() < MIN_SQUADS_FOR_STATS)) {
            log.info("[ChannelContent] SQUAD_STATS skipped: {} active squads", active.size());
            return Optional.empty();
        }
        long members = 0;
        Squad biggest = null;
        long biggestCount = 0;
        for (Squad sq : active) {
            long c = squadService.memberCount(sq);
            members += c;
            if (c > biggestCount && safeName(sq.getName()) != null) { biggest = sq; biggestCount = c; }
        }
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        long fresh = active.stream().filter(sq -> sq.getCreatedAt() != null && sq.getCreatedAt().isAfter(weekAgo)).count();
        StringBuilder sb = new StringBuilder("📈 <b>отряды в цифрах</b>\n\n");
        sb.append("В клубе <b>").append(active.size()).append("</b> ").append(plural(active.size(), "отряд", "отряда", "отрядов"))
          .append(" и <b>").append(members).append("</b> ").append(plural((int) members, "игрок", "игрока", "игроков")).append(" в них.\n");
        if (fresh > 0) sb.append("За неделю появилось новых отрядов: <b>").append(fresh).append("</b>.\n");
        if (biggest != null) sb.append("Самый большой - <b>«").append(esc(safeName(biggest.getName()))).append("»</b>, ").append(biggestCount).append(" ")
                .append(plural((int) biggestCount, "человек", "человека", "человек")).append(".\n");
        sb.append("\n").append(ending(active.size(), "Твой отряд уже в списке?", "Ставь ⚔️, если ищешь команду.", "Собрать свой отряд можно за минуту."));
        sb.append(squadLink());
        return Optional.of(saveDraft(SQUAD_STATS, sb.toString(), null));
    }

    // ───────────────────────── турниры ─────────────────────────

    private static final DateTimeFormatter TOURNEY_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private boolean tourneyDrafted(String type, Long tournamentId) {
        String key = "T:" + tournamentId;
        return draftRepository.findAllByType(type).stream().anyMatch(d -> key.equals(d.getMeta()));
    }

    private String tourneyLink() {
        if (botUsername == null || botUsername.isBlank()) return "";
        return "\n\n🎮 Все турниры - в нашем боте: <a href=\"https://t.me/" + botUsername + "\">@" + botUsername + "</a>";
    }

    private static String prizeRule() {
        return "1 место забирает 60% фонда, места со 2 по 10 делят остальное.";
    }

    /** Раз в 10 минут: за 24 ч до старта регистрации - «скоро закроется», за 24 ч до финиша - «финишная прямая». По одному посту на турнир и тип. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 150_000)
    public void tournamentTick() {
        LocalDateTime now = LocalDateTime.now();
        try {
            if (settings(TOURNEY_REG_CLOSING).enabled()) {
                for (Tournament t : tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.REGISTRATION)) {
                    LocalDateTime start = t.getStartDate();
                    if (start == null || !t.isRegistrationOpen()) continue;
                    if (now.isBefore(start.minusHours(TOURNEY_REMIND_HOURS)) || !now.isBefore(start)) continue;
                    LocalDateTime opened = t.getRegistrationOpenDate() != null ? t.getRegistrationOpenDate() : t.getCreatedAt();
                    // регистрация открылась уже внутри последних 24 ч - «скоро закроется» дублировало бы свежий анонс
                    if (opened != null && opened.isAfter(start.minusHours(TOURNEY_REMIND_HOURS))) continue;
                    if (tourneyDrafted(TOURNEY_REG_CLOSING, t.getId())) continue;
                    createRegClosingDraft(t);
                }
            }
            if (settings(TOURNEY_ACTIVE).enabled()) {
                for (Tournament t : tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.ACTIVE)) {
                    LocalDateTime end = t.getEndDate();
                    if (end == null || t.getStartDate() == null) continue;
                    if (now.isBefore(end.minusHours(TOURNEY_REMIND_HOURS)) || !now.isBefore(end)) continue;
                    if (t.getStartDate().isAfter(end.minusHours(TOURNEY_REMIND_HOURS))) continue; // турнир короче суток
                    if (tourneyDrafted(TOURNEY_ACTIVE, t.getId())) continue;
                    createActiveDraft(t);
                }
            }
        } catch (Exception e) {
            log.error("[ChannelContent] tournamentTick failed", e);
        }
    }

    private void createRegClosingDraft(Tournament t) {
        long count = tournamentService.entryCount(t);
        long hoursLeft = Math.max(1, Duration.between(LocalDateTime.now(), t.getStartDate()).toHours());
        StringBuilder sb = new StringBuilder("⏰ <b>регистрация на турнир «" + esc(t.getName()) + "» скоро закроется</b>\n\n");
        sb.append("Старт: <b>").append(t.getStartDate().format(TOURNEY_FMT)).append(" UTC</b>, осталось около ").append(hoursLeft).append(" ч.\n");
        if (t.getGameName() != null && !t.getGameName().isBlank()) sb.append("Игра: <b>").append(esc(t.getGameName())).append("</b>.\n");
        sb.append("Взнос: <b>").append(num(t.getEntryFeeExc())).append(" EXC</b>. Записалось: <b>").append(count).append("</b>, призовой фонд сейчас: <b>")
          .append(num(t.getPrizePoolExc())).append(" EXC</b>.\n");
        if (t.getMinParticipants() != null) {
            long need = t.getMinParticipants() - count;
            if (need > 0) sb.append("Минимум участников — ").append(t.getMinParticipants()).append(". Не хватает <b>").append(need)
                    .append("</b>: если не наберётся, турнир отменится, а взносы вернутся.\n");
            else sb.append("Минимум участников уже набран, турнир состоится.\n");
        }
        sb.append(prizeRule()).append("\n\n");
        sb.append(ending(t.getId(), "Успеешь записаться?", "Ставь ⚔️, если уже в деле.", "Записаться можно прямо сейчас."));
        sb.append(tourneyLink());
        saveDraft(TOURNEY_REG_CLOSING, sb.toString(), "T:" + t.getId(), t.getPhotoFileId());
    }

    private void createActiveDraft(Tournament t) {
        List<TournamentEntry> entries = tournamentService.getLeaderboard(t);
        long hoursLeft = Math.max(1, Duration.between(LocalDateTime.now(), t.getEndDate()).toHours());
        StringBuilder sb = new StringBuilder("📊 <b>турнир «" + esc(t.getName()) + "» — финишная прямая</b>\n\n");
        sb.append("До финиша около ").append(hoursLeft).append(" ч (<b>").append(t.getEndDate().format(TOURNEY_FMT)).append(" UTC</b>). Участников: <b>")
          .append(entries.size()).append("</b>, призовой фонд: <b>").append(num(t.getPrizePoolExc())).append(" EXC</b>.\n\n");
        if (t.getScoringType().isTrophyRace()) {
            sb.append("Побеждает тот, кто нарастил больше трофеев: стартовые значения зафиксированы, итоги подведём сразу после финиша.\n\n");
        } else {
            record Row(String nick, long score) {}
            List<Row> rows = new ArrayList<>();
            for (TournamentEntry e : entries) {
                if (e.isDisqualified() || e.getUser() == null) continue;
                long score = tournamentService.questScoreDuring(t, e.getUser());
                if (score > 0 && e.getUser().getNickname() != null) rows.add(new Row(e.getUser().getNickname(), score));
            }
            rows.sort(Comparator.comparingLong(Row::score).reversed());
            if (rows.isEmpty()) {
                sb.append("Пока никто не открыл счёт: самое время начать.\n\n");
            } else {
                sb.append("Сейчас впереди:\n");
                String[] marks = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
                for (int i = 0; i < Math.min(5, rows.size()); i++) {
                    sb.append(marks[i]).append(" ").append(esc(rows.get(i).nick())).append(" — <b>").append(rows.get(i).score()).append("</b> ")
                      .append(plural((int) rows.get(i).score(), "квест", "квеста", "квестов")).append("\n");
                }
                sb.append("\n");
            }
        }
        sb.append(prizeRule()).append("\n\n");
        sb.append(ending(t.getId(), "Кто вырвется вперёд?", "Ставь 🔥, если следишь за таблицей.", "До финиша ещё можно успеть."));
        sb.append(tourneyLink());
        saveDraft(TOURNEY_ACTIVE, sb.toString(), "T:" + t.getId(), t.getPhotoFileId());
    }

    /** Турнир отменён из-за недобора участников: пост про возврат взносов (прозрачность вместо тишины). */
    @EventListener
    public void onTournamentCancelled(TournamentCancelledEvent e) {
        try {
            if (!settings(TOURNEY_CANCELLED).enabled()) return;
            Tournament t = e.getTournament();
            if (tourneyDrafted(TOURNEY_CANCELLED, t.getId())) return;
            int refunded = e.getRefundedEntries().size();
            StringBuilder sb = new StringBuilder("🚫 <b>турнир «" + esc(t.getName()) + "» отменён</b>\n\n");
            sb.append("Не набралось минимальное число участников");
            if (t.getMinParticipants() != null) sb.append(" (нужно ").append(t.getMinParticipants()).append(", записалось ").append(refunded).append(")");
            sb.append(".\nВзносы (").append(num(t.getEntryFeeExc())).append(" EXC) вернули всем участникам в полном объёме.\n\n");
            sb.append("Следующий турнир объявим отдельно.");
            sb.append(tourneyLink());
            saveDraft(TOURNEY_CANCELLED, sb.toString(), "T:" + t.getId(), t.getPhotoFileId());
        } catch (Exception ex) {
            log.error("[ChannelContent] Failed to create cancelled-tournament draft", ex);
        }
    }

    // ───────────────────────── вывод и пруфы ─────────────────────────

    /** Меньше выплат за период - сводку не публикуем (слабые цифры вредят доверию): сначала расширяем период с 14 до 30 дней. */
    private static final int MIN_PAYOUTS_FOR_SUMMARY = 5;
    private static final long[] COUNT_MILESTONES = {10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};
    private static final long[] RUB_MILESTONES = {10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 10_000_000};

    private String payoutsLink() {
        if (botUsername == null || botUsername.isBlank()) return "";
        return "\n\n💸 Как вывести — в нашем боте: <a href=\"https://t.me/" + botUsername + "\">@" + botUsername + "</a>";
    }

    /** «Пруф от Экси»: сводка выплат за 2 недели (при малом числе - за месяц), без имён игроков, только цифры; скорость выплаты - если данных достаточно и она честная. */
    public Optional<ChannelPostDraft> createWithdrawSummaryDraft(boolean force) {
        LocalDateTime now = LocalDateTime.now();
        int days = 14;
        List<RewardRequest> reqs = rewardRequestRepository.findApprovedWithdrawalsSince(now.minusDays(days));
        if (reqs.size() < MIN_PAYOUTS_FOR_SUMMARY) {
            days = 30;
            reqs = rewardRequestRepository.findApprovedWithdrawalsSince(now.minusDays(days));
        }
        if (reqs.isEmpty() || (!force && reqs.size() < MIN_PAYOUTS_FOR_SUMMARY)) {
            log.info("[ChannelContent] WITHDRAW_SUMMARY skipped: {} payouts in {} days", reqs.size(), days);
            return Optional.empty();
        }
        RewardService.WithdrawalPeriodStats st = rewardService.withdrawalStatsSince(now.minusDays(days));
        long players = rewardRequestRepository.countDistinctWithdrawalUsersSince(now.minusDays(days));
        String period = days == 14 ? "две недели" : "месяц";
        StringBuilder sb = new StringBuilder("💸 <b>пруф от Экси — выплаты за " + period + "</b>\n\n");
        sb.append("Выплатили <b>").append(st.count()).append("</b> ").append(plural((int) st.count(), "заявку", "заявки", "заявок")).append(" на вывод, ")
          .append("получили <b>").append(players).append("</b> ").append(plural((int) players, "игрок", "игрока", "игроков")).append(".\n");
        sb.append("Всего выведено: <b>").append(num(st.totalExc())).append(" EXC</b>.\n");
        if (st.totalRub() > 0) sb.append("Рублями: <b>").append(num(st.totalRub())).append(" ₽</b>\n");
        if (st.totalTonRub() > 0) sb.append("В GRAM (TON) на сумму около <b>").append(num(st.totalTonRub())).append(" ₽</b>\n");
        if (st.totalStars() > 0) sb.append("Звёздами Telegram: <b>").append(num(st.totalStars())).append(" ⭐</b>\n");
        List<Long> minutes = reqs.stream().filter(r -> r.getPaidAt() != null && r.getCreatedAt() != null)
                .map(r -> Duration.between(r.getCreatedAt(), r.getPaidAt()).toMinutes()).sorted().toList();
        if (minutes.size() >= 5) {
            long median = minutes.get(minutes.size() / 2);
            if (median <= 24 * 60) {
                sb.append("Время от заявки до выплаты (медиана): <b>").append(median < 60 ? "меньше часа" : "около " + Math.round(median / 60.0) + " ч").append("</b>\n");
            }
        }
        sb.append("\nЧеки публикуем в канале выплат.\n\n");
        sb.append(ending(st.count(), "Уже подал заявку?", "Ставь 💸, если ждёшь свою.", "Каждая заявка обрабатывается в течение 24 часов."));
        sb.append(payoutsLink());
        return Optional.of(saveDraft(WITHDRAW_SUMMARY, sb.toString(), null));
    }

    /** «Как вывести»: две заготовки по очереди, факты сверены с ботом (минимум 5 000 EXC, одна заявка в сутки, способы, сроки, лимит по уровню). */
    public Optional<ChannelPostDraft> createWithdrawHowToDraft() {
        long variant = draftRepository.findAllByType(WITHDRAW_HOWTO).size() % 2;
        StringBuilder sb = new StringBuilder();
        if (variant == 0) {
            sb.append("🧾 <b>как вывести EXC</b>\n\n")
              .append("1. В боте открой «Кошелёк» и выбери вывод.\n")
              .append("2. Выбери способ: рубли по реквизитам банка, GRAM (TON) на кошелёк или звёзды Telegram.\n")
              .append("3. Укажи сумму от <b>5 000 EXC</b>. Одна заявка в сутки.\n")
              .append("4. Заявку обрабатываем в течение 24 часов, чек придёт в бот.\n\n")
              .append("Месячный лимит вывода растёт вместе с уровнем: чем выше уровень, тем больше.\n\n")
              .append(ending(variant, "Уже пробовал?", "Ставь 💸, если пригодится.", "Всё занимает пару минут."));
        } else {
            sb.append("🧾 <b>вывод EXC: что подготовить заранее</b>\n\n")
              .append("Перед первым выводом в профиле нужно указать страну и возраст и один раз подтвердить номер телефона.\n")
              .append("Минимальная сумма — <b>5 000 EXC</b>, заявка одна в сутки.\n")
              .append("Рубли уходят по реквизитам банка, GRAM (TON) на кошелёк, звёзды Telegram на юзернейм.\n")
              .append("Итоговая сумма в рублях зависит от коэффициента клуба, он виден в разделе «Магазин».\n\n")
              .append("Подписчики EGC Pass идут в очереди на вывод первыми.\n\n")
              .append(ending(variant, "Что выберешь, рубли или GRAM?", "Ставь 💸, если уже выводил.", "Инструкция всегда в разделе «Помощь»."));
        }
        sb.append(payoutsLink());
        return Optional.of(saveDraft(WITHDRAW_HOWTO, sb.toString(), null));
    }

    /** Проверка вех раз в 10 минут: при пересечении круглого порога числа выплат или суммы - пост «веха». Первый запуск только запоминает пороги (без залпа по накопленному). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
    public void milestoneTick() {
        try {
            if (!settings(WITHDRAW_MILESTONE).enabled()) return;
            RewardService.WithdrawalPeriodStats all = rewardService.withdrawalStatsSince(LocalDateTime.of(2020, 1, 1, 0, 0));
            long count = all.count();
            long rub = all.totalRub() + all.totalTonRub();
            long crossedCount = highest(COUNT_MILESTONES, count);
            long crossedRub = highest(RUB_MILESTONES, rub);
            String lastCount = get("cc.wmil.count");
            String lastRub = get("cc.wmil.rub");
            if (lastCount == null || lastRub == null) {
                put("cc.wmil.count", String.valueOf(crossedCount));
                put("cc.wmil.rub", String.valueOf(crossedRub));
                return;
            }
            if (crossedCount > parseLong(lastCount)) {
                put("cc.wmil.count", String.valueOf(crossedCount));
                saveDraft(WITHDRAW_MILESTONE, "🏁 <b>" + crossedCount + "-я выплата в клубе</b>\n\nКлуб выплатил уже <b>" + count + "</b> заявок на вывод. "
                        + "Всего выведено: <b>" + num(all.totalExc()) + " EXC</b>.\n\nСпасибо всем, кто играет и выполняет квесты.\n\n"
                        + ending(crossedCount, "Кто станет следующим?", "Ставь 🔥, если уже среди них.", "Следующая веха уже впереди.") + payoutsLink(), "M:C" + crossedCount);
            }
            if (crossedRub > parseLong(lastRub)) {
                put("cc.wmil.rub", String.valueOf(crossedRub));
                saveDraft(WITHDRAW_MILESTONE, "🏁 <b>выплачено больше " + num(crossedRub) + " ₽</b>\n\nИгроки клуба вывели рублями и в GRAM (TON) уже более <b>"
                        + num(crossedRub) + " ₽</b>. Чеки публикуем в канале выплат.\n\n"
                        + ending(crossedRub, "Куда потратишь свою награду?", "Ставь 💸, если тоже выводил.", "Каждая заявка обрабатывается в течение 24 часов.") + payoutsLink(), "M:R" + crossedRub);
            }
        } catch (Exception e) {
            log.error("[ChannelContent] milestoneTick failed", e);
        }
    }

    private static long highest(long[] steps, long value) {
        long best = 0;
        for (long st : steps) if (value >= st) best = st;
        return best;
    }

    private static long parseLong(String v) {
        try { return v == null ? 0 : Long.parseLong(v.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** Пруф по одной выплате (текст без ника игрока, чек - как картинка); публикуется в канал выплат после согласования. false - тип выключен или уже создан. */
    public boolean createProofDraft(Long requestId, String text, String receiptFileId) {
        if (!settings(WITHDRAW_PROOF).enabled()) return false;
        String key = "W:" + requestId;
        if (draftRepository.findAllByType(WITHDRAW_PROOF).stream().anyMatch(d -> key.equals(d.getMeta()))) return false;
        saveDraft(WITHDRAW_PROOF, text, key, receiptFileId);
        return true;
    }


    // ───────────────────────── рейтинг и Зал славы ─────────────────────────

    /** Ник игрока для публичного поста: без @username, ссылок и брани; без публичного ника - нейтральная подпись. */
    private static String publicNick(String nickname) {
        String n = safeName(nickname);
        return n != null ? n : "игрок без публичного ника";
    }

    /** «Зал славы»: топ-3 недели по XP. Раньше уходил в канал сразу (с @username), теперь - на согласование, ник без @. Баннер подставляет бот. */
    @EventListener
    public void onHallOfFame(HallOfFameEvent e) {
        try {
            if (!settings(HALL_OF_FAME).enabled() || e.getTop3().isEmpty()) return;
            StringBuilder sb = new StringBuilder("🏆 <b>зал славы недели</b>\n\n");
            for (HallOfFameEvent.HallEntry en : e.getTop3()) {
                String nick = "<b>" + esc(publicNick(en.nickname())) + "</b>";
                switch (en.rank()) {
                    case 1 -> sb.append("👑 ").append(nick).append(" - чемпион недели\n     ").append(num(en.weeklyXp())).append(" XP за неделю, всего в клубе ").append(num(en.totalXp())).append(" XP\n\n");
                    case 2 -> sb.append("🥈 ").append(nick).append("\n     ").append(num(en.weeklyXp())).append(" XP за неделю\n\n");
                    default -> sb.append("🥉 ").append(nick).append("\n     ").append(num(en.weeklyXp())).append(" XP за неделю\n\n");
                }
            }
            sb.append("Поздравляем! Новая неделя уже началась, и таблица снова пустая.\n\n");
            sb.append(ending(e.getTop3().get(0).weeklyXp(), "Кто попадёт в зал славы на этой неделе?", "Ставь 🏆, если метишь в тройку.", "Каждый квест приближает к тройке."));
            sb.append(botLink());
            saveDraft(HALL_OF_FAME, sb.toString(), null);
        } catch (Exception ex) {
            log.error("[ChannelContent] Failed to create hall of fame draft", ex);
        }
    }

    /** «Гонка за Зал славы» (середина недели): топ-5 недели по нику, отставание второго от лидера и порог тройки. Автопост - только при достаточной активности. */
    public Optional<ChannelPostDraft> createWeeklyRaceDraft(boolean force) {
        long active = appUserRepository.countActiveThisWeek();
        List<AppUser> top = appUserRepository.findTop20ByRegistrationCompletedTrueAndWeeklyXpGreaterThanOrderByWeeklyXpDescTelegramIdAsc(0)
                .stream().limit(5).toList();
        if (top.isEmpty() || (!force && active < MIN_ACTIVE_FOR_WEEK_POSTS)) {
            log.info("[ChannelContent] WEEKLY_RACE skipped: {} active players", active);
            return Optional.empty();
        }
        String[] marks = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
        StringBuilder sb = new StringBuilder("🏁 <b>гонка за зал славы</b>\n\n");
        sb.append("Опыт за неделю набрали <b>").append(active).append("</b> ").append(plural((int) active, "игрок", "игрока", "игроков")).append(". Сейчас впереди:\n\n");
        for (int i = 0; i < top.size(); i++) {
            sb.append(marks[i]).append(" <b>").append(esc(publicNick(top.get(i).getNickname()))).append("</b> - ").append(num(top.get(i).getWeeklyXp())).append(" XP\n");
        }
        sb.append("\n");
        if (top.size() >= 2) {
            long gap = top.get(0).getWeeklyXp() - top.get(1).getWeeklyXp();
            sb.append("До лидера второму месту не хватает <b>").append(num(gap)).append(" XP</b>.\n");
        }
        if (top.size() >= 3) {
            sb.append("Чтобы попасть в тройку, нужно набрать больше <b>").append(num(top.get(2).getWeeklyXp())).append(" XP</b>.\n");
        }
        sb.append("Неделя закончится в понедельник в 00:00 UTC (03:00 по Москве).\n\n");
        sb.append(ending(top.get(0).getWeeklyXp(), "Кто успеет подняться в тройку?", "Ставь 🏁, если ещё в гонке.", "До конца недели можно многое успеть."));
        sb.append(botLink());
        return Optional.of(saveDraft(WEEKLY_RACE, sb.toString(), null));
    }

    /** «Лиги недели»: создаётся при сбросе недельного XP (понедельник 00:00 UTC): сколько игроков в каждой лиге и сколько выплачено призами. Только цифры, без имён. */
    @EventListener
    public void onLeagueWeek(LeagueWeekEvent e) {
        try {
            if (!settings(LEAGUES_WEEK).enabled()) return;
            if (e.getActivePlayers() < MIN_ACTIVE_FOR_WEEK_POSTS) {
                log.info("[ChannelContent] LEAGUES_WEEK skipped: {} active players", e.getActivePlayers());
                return;
            }
            StringBuilder sb = new StringBuilder("🏅 <b>лиги недели</b>\n\n");
            sb.append("Неделя закрыта: опыт набрали <b>").append(e.getActivePlayers()).append("</b> ")
              .append(plural(e.getActivePlayers(), "игрок", "игрока", "игроков")).append(". Расклад по лигам:\n\n");
            for (LeagueWeekEvent.LeagueRow r : e.getRows()) {
                if (r.players() <= 0) continue;
                sb.append(esc(r.displayName())).append(" - <b>").append(r.players()).append("</b>");
                if (r.excPrize() > 0) sb.append(" (от ").append(num(r.minWeeklyXp())).append(" XP, приз ").append(num(r.excPrize())).append(" EXC)");
                sb.append("\n");
            }
            if (e.getTotalPrize() > 0) sb.append("\nПризов лиг выплатили <b>").append(num(e.getTotalPrize())).append(" EXC</b>.\n");
            sb.append("Чтобы подняться в лигу выше, нужно больше опыта за неделю: лиги пересчитываются каждый понедельник.\n\n");
            sb.append(ending(e.getActivePlayers(), "В какой лиге ты закончишь эту неделю?", "Ставь 🏅, если метишь выше.", "Новая неделя уже началась."));
            sb.append(botLink());
            saveDraft(LEAGUES_WEEK, sb.toString(), null);
        } catch (Exception ex) {
            log.error("[ChannelContent] Failed to create leagues draft", ex);
        }
    }

    // ───────────────────────── магазин и EGC Pass ─────────────────────────

    private static String shortDescription(String d) {
        if (d == null) return "";
        String t = d.replaceAll("\\s+", " ").trim();
        if (t.length() <= 120) return t;
        int cut = t.lastIndexOf(' ', 120);
        return t.substring(0, cut > 60 ? cut : 120).trim() + "…";
    }

    private static boolean shopVisible(RewardItem it) {
        return it.isActive() && !it.isComingSoon() && !"Вывод".equals(it.getCategory()) && it.getTitle() != null;
    }

    /** «Новое в магазине»: позиции каталога, появившиеся после отметки воды (без повторов из прошлых постов). Первый автозапуск только ставит отметку. Фото позиции подставляется само. */
    public Optional<ChannelPostDraft> createShopNewDraft(boolean force) {
        LocalDateTime now = LocalDateTime.now();
        String wmRaw = get("cc.shopn.wm");
        LocalDateTime since;
        if (wmRaw == null) {
            if (!force) {
                put("cc.shopn.wm", now.toString());
                log.info("[ChannelContent] SHOP_NEW watermark initialised, no post on first auto run");
                return Optional.empty();
            }
            since = now.minusDays(3);
        } else {
            try { since = LocalDateTime.parse(wmRaw); } catch (Exception e) { since = now.minusDays(3); }
        }
        Set<Long> already = new HashSet<>();
        for (ChannelPostDraft d : draftRepository.findAllByType(SHOP_NEW)) {
            if (d.getMeta() == null) continue;
            for (String id : d.getMeta().split(",")) {
                try { already.add(Long.parseLong(id.trim())); } catch (NumberFormatException ignored) { }
            }
        }
        final LocalDateTime from = since;
        List<RewardItem> fresh = rewardItemRepository.findAll().stream()
                .filter(ChannelContentService::shopVisible)
                .filter(it -> it.getCreatedAt() != null && !it.getCreatedAt().isBefore(from))
                .filter(it -> !already.contains(it.getId()))
                .sorted(Comparator.comparingLong(RewardItem::getPriceCoins))
                .toList();
        put("cc.shopn.wm", now.toString());
        if (fresh.isEmpty()) return Optional.empty();

        StringBuilder sb = new StringBuilder("🛍 <b>новое в магазине</b>\n\n");
        sb.append(fresh.size() == 1 ? "В магазине появилась новая награда:\n\n" : "В магазине появились новые награды:\n\n");
        int shown = 0;
        for (RewardItem it : fresh) {
            if (shown++ >= MAX_SHOP_ITEMS_IN_POST) break;
            sb.append("• <b>").append(esc(it.getTitle())).append("</b> - ").append(num(it.getPriceCoins())).append(" EXC\n");
            String desc = shortDescription(it.getDescription());
            if (!desc.isEmpty()) sb.append("  ").append(esc(desc)).append("\n");
            sb.append("\n");
        }
        if (fresh.size() > MAX_SHOP_ITEMS_IN_POST) sb.append("И ещё позиций: ").append(fresh.size() - MAX_SHOP_ITEMS_IN_POST).append("\n\n");
        sb.append(ending(fresh.size(), "Что заберёшь первым?", "Ставь 🛍, если присмотрел награду.", "Все позиции уже в магазине бота."));
        sb.append(botLink());
        String meta = fresh.stream().map(it -> String.valueOf(it.getId())).collect(Collectors.joining(","));
        String photo = fresh.stream().map(RewardItem::getPhotoFileId).filter(f -> f != null && !f.isBlank()).findFirst().orElse(null);
        return Optional.of(saveDraft(SHOP_NEW, sb.toString(), meta, photo));
    }

    /** «Популярное в магазине»: топ-3 позиции каталога по числу заказов за 14 дней, без имён игроков. Автопост - только при достаточном числе заказов. */
    public Optional<ChannelPostDraft> createShopPopularDraft(boolean force) {
        List<Object[]> rows = rewardRequestRepository.countShopOrdersByItemSince(LocalDateTime.now().minusDays(14));
        long total = 0;
        for (Object[] r : rows) total += ((Number) r[1]).longValue();
        if (rows.isEmpty() || (!force && total < MIN_SHOP_ORDERS)) {
            log.info("[ChannelContent] SHOP_POPULAR skipped: {} orders", total);
            return Optional.empty();
        }
        String[] marks = {"🥇", "🥈", "🥉"};
        StringBuilder sb = new StringBuilder("🔥 <b>популярное в магазине</b>\n\nЧаще всего за две недели заказывали:\n\n");
        int n = 0;
        String photo = null;
        long topCount = 0;
        for (Object[] r : rows) {
            if (n >= 3) break;
            RewardItem it = rewardItemRepository.findById(((Number) r[0]).longValue()).orElse(null);
            if (it == null || it.getTitle() == null) continue;
            long cnt = ((Number) r[1]).longValue();
            if (n == 0) { photo = it.getPhotoFileId(); topCount = cnt; }
            sb.append(marks[n]).append(" <b>").append(esc(it.getTitle())).append("</b> - ").append(cnt).append(" ")
              .append(plural((int) cnt, "заказ", "заказа", "заказов")).append(", ").append(num(it.getPriceCoins())).append(" EXC\n");
            n++;
        }
        if (n == 0) return Optional.empty();
        sb.append("\n").append(ending(topCount, "А что выберешь ты?", "Ставь 🔥, если уже пробовал.", "Полный каталог - в магазине бота."));
        sb.append(botLink());
        return Optional.of(saveDraft(SHOP_POPULAR, sb.toString(), null, photo));
    }

    /** «EGC Pass: что даёт» (раз в 4 недели): один перк на пост по очереди. Факты сверены с sendEgcPassScreen/кодом; без цифр подписчиков и без обещаний заработка. */
    public Optional<ChannelPostDraft> createEgcPassDraft() {
        String[][] perks = {
                {"✨ <b>EGC Pass: больше EXC за квесты</b>", "С EGC Pass за каждый квест начисляется на 10% больше EXC (бонус до 10 000 EXC в месяц) и на 5% больше XP."},
                {"🎁 <b>EGC Pass: сундук дня без реролла</b>", "Подписчикам EGC Pass каждый день доступен бесплатный улучшенный сундук: призы в нём щедрее обычного, а докупать реролл за Stars не нужно."},
                {"⚡ <b>EGC Pass: приоритет на вывод</b>", "Заявки подписчиков EGC Pass на вывод EXC обрабатываются в очереди первыми."},
                {"📂 <b>EGC Pass: доп. слот квеста</b>", "Пока EGC Pass активен, у тебя есть дополнительный слот квеста: можно вести больше квестов одновременно, не покупая слот за EXC."},
                {"💸 <b>EGC Pass: донат по закупочной цене</b>", "Гемы для Brawl Stars, Clash Royale и Clash of Clans подписчики EGC Pass покупают по закупочной цене, без наценки клуба, а XP-бонус начисляется как за полную цену."},
        };
        int idx = draftRepository.findAllByType(EGCPASS_PERK).size() % perks.length;
        StringBuilder sb = new StringBuilder(perks[idx][0]).append("\n\n").append(perks[idx][1]).append("\n\n");
        sb.append("Ещё в EGC Pass: значок в профиле и другие перки - полный список в боте.\n");
        sb.append("Стоимость: <b>").append(EGC_PASS_STARS).append(" ⭐ на 30 дней</b>, продлевается автоматически, отменить можно в настройках платежей Telegram.");
        sb.append(botLink());
        return Optional.of(saveDraft(EGCPASS_PERK, sb.toString(), null));
    }

    /** «Предметы клуба» (раз в 4 недели): что можно купить за EXC и сколько это стоит; цены берутся из SinkShopService, чтобы не устаревали. */
    public Optional<ChannelPostDraft> createShopItemsDraft() {
        StringBuilder sb = new StringBuilder("🧰 <b>на что потратить EXC</b>\n\n");
        sb.append("Кроме наград в магазине, EXC можно вложить в предметы клуба:\n\n");
        sb.append("📈 XP-буст +20% на 24 ч - ").append(num(SinkShopService.PRICE_XP_BOOST_24H)).append(" EXC\n");
        sb.append("✨ EXC-буст +20% на 24 ч - ").append(num(SinkShopService.PRICE_EXC_BOOST_24H)).append(" EXC\n");
        sb.append("⚡ Двойной буст (XP и EXC) на 24 ч - ").append(num(SinkShopService.PRICE_DOUBLE_BOOST_24H)).append(" EXC\n");
        sb.append("📂 Доп. слот квеста на 48 ч - ").append(num(SinkShopService.PRICE_EXTRA_SLOT)).append(" EXC\n");
        sb.append("⏱ Снятие кулдауна квеста - ").append(num(SinkShopService.PRICE_COOLDOWN_REMOVAL)).append(" EXC\n");
        sb.append("🛡 Страховка повторной попытки - ").append(num(SinkShopService.PRICE_INSURANCE)).append(" EXC\n\n");
        sb.append("Всё это лежит в разделе магазина в боте.\n\n");
        long seed = draftRepository.findAllByType(SHOP_ITEMS).size();
        sb.append(ending(seed, "Что пригодилось бы тебе?", "Ставь ⚡, если пользуешься бустами.", "Бусты действуют сутки, слот - двое."));
        sb.append(botLink());
        return Optional.of(saveDraft(SHOP_ITEMS, sb.toString(), null));
    }

    // ───────────────────────── рефералы ─────────────────────────

    /** «Топ рефереров недели»: создаётся в момент выплаты недельного приза (понедельник 00:00 UTC): ники без @ и призы. Пост только при 3+ призёрах. */
    @EventListener
    public void onReferralTop(ReferralLeaderboardRewardEvent e) {
        try {
            if (!settings(REFERRAL_TOP).enabled() || e.getWinners().size() < MIN_REFERRAL_WINNERS) return;
            String[] marks = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
            long pool = 0;
            StringBuilder sb = new StringBuilder("🤝 <b>топ рефереров недели</b>\n\nЛучше всех на этой неделе приглашали друзей:\n\n");
            for (UserService.ReferralRankEntry w : e.getWinners()) {
                if (w.rank() < 1 || w.rank() > marks.length) continue;
                pool += w.prizeExc();
                sb.append(marks[w.rank() - 1]).append(" <b>").append(esc(publicNick(w.user().getNickname()))).append("</b> - приз ")
                  .append(num(w.prizeExc())).append(" EXC\n");
            }
            sb.append("\nПризовой фонд недели - <b>").append(num(pool)).append(" EXC</b>, его делят пять лучших по доходу от рефералов.\n\n");
            sb.append(ending(pool, "Кто в топе на этой неделе?", "Ставь 🤝, если зовёшь друзей.", "Новая неделя уже началась."));
            sb.append(botLink());
            saveDraft(REFERRAL_TOP, sb.toString(), null);
        } catch (Exception ex) {
            log.error("[ChannelContent] Failed to create referral top draft", ex);
        }
    }

    /** «Как работает реферальная система» (раз в 4 недели): две заготовки по очереди. Суммы и правила сверены с FAQ 2026-09-26 и с наградами в боте; при смене условий обновить. */
    public Optional<ChannelPostDraft> createReferralHowToDraft() {
        int idx = draftRepository.findAllByType(REFERRAL_HOWTO).size() % 2;
        StringBuilder sb = new StringBuilder();
        if (idx == 0) {
            sb.append("🤝 <b>как пригласить друга в клуб</b>\n\n");
            sb.append("Личная ссылка лежит в разделе «Рефералы» в боте.\n");
            sb.append("Друг регистрируется и подписывается на канал: он получает <b>500 EXC</b>, ты - <b>300 EXC</b>.\n");
            sb.append("Друг выполняет первый квест: ему ещё <b>3 000 EXC</b>, тебе <b>2 500 EXC</b>.\n\n");
            sb.append("Приглашай тех, кому игры правда интересны: так честнее и полезнее вам обоим.");
        } else {
            sb.append("💸 <b>что ещё даёт реферальная ссылка</b>\n\n");
            sb.append("Кроме стартовых бонусов, ты получаешь <b>10%</b> от наград за квесты друга, пока он остаётся активным. ");
            sb.append("Если друг 14 дней не выполняет квесты, отчисления ставятся на паузу и сами возобновляются, когда он вернётся.\n\n");
            sb.append("Каждую неделю пятёрка лучших рефереров делит призовой фонд <b>2 000 EXC</b>.");
        }
        sb.append("\n\n").append(ending(idx, "Кого позовёшь первым?", "Ставь 🤝, если уже приглашал друзей.", "Ссылка ждёт в разделе «Рефералы»."));
        sb.append(botLink());
        return Optional.of(saveDraft(REFERRAL_HOWTO, sb.toString(), null));
    }

    /** «Рефералы в цифрах» (раз в 2 недели): сколько игроков пришло по приглашениям, сколько всего и какая доля уже выполнила квест. Только агрегаты; автопост - при 10+ новых за период. */
    public Optional<ChannelPostDraft> createReferralStatsDraft(boolean force) {
        long fresh = appUserRepository.countReferredNewUsersSince(LocalDateTime.now().minusDays(14));
        long total = appUserRepository.countAllReferredUsers();
        if (total == 0 || (!force && fresh < MIN_REFERRED_FOR_STATS)) {
            log.info("[ChannelContent] REFERRAL_STATS skipped: {} new referred", fresh);
            return Optional.empty();
        }
        long withQuest = appUserRepository.countReferredUsersWithAtLeastOneQuest();
        StringBuilder sb = new StringBuilder("📈 <b>рефералы в цифрах</b>\n\n");
        sb.append("За две недели по приглашениям пришло <b>").append(fresh).append("</b> ").append(plural((int) fresh, "игрок", "игрока", "игроков")).append(".\n");
        sb.append("Всего в клубе по приглашениям <b>").append(num(total)).append("</b> ").append(plural((int) Math.min(total, Integer.MAX_VALUE), "игрок", "игрока", "игроков"))
          .append(", из них <b>").append(num(withQuest)).append("</b> уже выполнили хотя бы один квест.\n\n");
        sb.append(ending(fresh, "Сколько друзей позовёшь ты?", "Ставь 🤝, если ты среди приглашённых.", "Ссылка для друзей - в разделе «Рефералы»."));
        sb.append(botLink());
        return Optional.of(saveDraft(REFERRAL_STATS, sb.toString(), null));
    }

    // ───────────────────────── вспомогательное ─────────────────────────

    /** Концовка чередуется (вопрос / реакция / обычная), как в остальных постах Экси. */
    private static String ending(long seed, String question, String reaction, String plain) {
        return switch ((int) (seed % 3)) {
            case 0 -> question;
            case 1 -> reaction;
            default -> plain;
        };
    }

    private String botLink() {
        if (botUsername == null || botUsername.isBlank()) return "";
        return "\n\n🎮 Все квесты - в нашем боте: <a href=\"https://t.me/" + botUsername + "\">@" + botUsername + "</a>";
    }

    private static String num(long v) {
        return String.format(Locale.forLanguageTag("ru"), "%,d", v);
    }

    private static String plural(int n, String one, String few, String many) {
        int m100 = n % 100, m10 = n % 10;
        if (m100 >= 11 && m100 <= 19) return many;
        return switch (m10) { case 1 -> one; case 2, 3, 4 -> few; default -> many; };
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
