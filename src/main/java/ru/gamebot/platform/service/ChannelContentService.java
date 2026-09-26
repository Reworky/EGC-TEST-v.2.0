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
import ru.gamebot.platform.event.TournamentCancelledEvent;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;
import ru.gamebot.platform.domain.repository.TournamentRepository;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import org.springframework.context.event.EventListener;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
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
    public static final List<String> ALL_TYPES = List.of(NEW_QUESTS, TOP_QUESTS_WEEK, SQUAD_MIDWEEK, SQUAD_RESULTS, SQUAD_STATS,
            TOURNEY_REG_CLOSING, TOURNEY_ACTIVE, TOURNEY_CANCELLED);

    /** Типы без часа в расписании: создаются по событию или по срокам турнира (за 24 ч до старта/финиша), в админке у них только переключатель. */
    public static boolean isEventType(String type) {
        return SQUAD_RESULTS.equals(type) || TOURNEY_REG_CLOSING.equals(type) || TOURNEY_ACTIVE.equals(type) || TOURNEY_CANCELLED.equals(type);
    }

    private static final int TOURNEY_REMIND_HOURS = 24;
    /** Автопост «отряды в цифрах» не формируется, если активных отрядов меньше (решение владельца 2026-09-26). */
    private static final int MIN_SQUADS_FOR_STATS = 10;

    /** Автопост «топ недели» не формируется, если за неделю выполнено меньше (не выдаём слабые цифры за успех). Ручная кнопка порог игнорирует. */
    private static final long MIN_WEEK_COMPLETIONS = 10;
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
            default -> "cc.sqstat.";
        };
    }

    /** Тизер среды уже работал до переноса на эту систему и «итоги недели» привязаны к выплате приза - включены по умолчанию (всё равно с согласованием). */
    private static boolean defaultEnabled(String type) {
        return SQUAD_MIDWEEK.equals(type) || SQUAD_RESULTS.equals(type) || type.startsWith("TOURNEY_");
    }

    public TypeSettings settings(String type) {
        String p = prefix(type);
        int defHour = switch (type) { case TOP_QUESTS_WEEK -> 10; default -> 12; };
        int defDow = switch (type) { case SQUAD_MIDWEEK -> 3; case SQUAD_STATS -> 5; default -> 1; };
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
        d = draftRepository.save(d);
        try {
            eventPublisher.publishEvent(new ChannelPostDraftEvent(this, d.getId()));
        } catch (Exception e) {
            log.warn("[ChannelContent] Failed to publish draft event {}", d.getId(), e);
        }
        return d;
    }

    // ───────────────────────── расписание ─────────────────────────

    /** Раз в 10 минут: если автопост включён, наступил его час (по времени сервера, UTC) и сегодня ещё не формировался - делаем черновик. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        for (String type : ALL_TYPES) {
            if (isEventType(type)) continue; // событийные/по срокам турнира: см. onSquadPrize, tournamentTick, onTournamentCancelled
            try {
                TypeSettings s = settings(type);
                if (!s.enabled() || now.getHour() != s.hour()) continue;
                boolean weekly = !NEW_QUESTS.equals(type);
                if (weekly && now.getDayOfWeek().getValue() != s.dayOfWeek()) continue;
                String today = LocalDate.now().toString();
                if (today.equals(s.lastRun())) continue;
                if (SQUAD_STATS.equals(type) && s.lastRun() != null) {
                    try {
                        if (LocalDate.parse(s.lastRun()).isAfter(LocalDate.now().minusDays(14))) continue; // раз в две недели
                    } catch (Exception ignored) { }
                }
                put(prefix(type) + "last", today);
                switch (type) {
                    case NEW_QUESTS -> createNewQuestsDraft(false);
                    case TOP_QUESTS_WEEK -> createTopQuestsDraft(false);
                    case SQUAD_MIDWEEK -> createSquadMidweekDraft(false);
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
