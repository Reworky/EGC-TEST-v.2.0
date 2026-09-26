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

    /** Автопост «топ недели» не формируется, если за неделю выполнено меньше (не выдаём слабые цифры за успех). Ручная кнопка порог игнорирует. */
    private static final long MIN_WEEK_COMPLETIONS = 10;
    private static final int MAX_GAMES_IN_NEW_POST = 5;
    private static final int MAX_QUESTS_PER_GAME = 3;

    private final QuestRepository questRepository;
    private final QuestSubmissionRepository submissionRepository;
    private final ChannelPostDraftRepository draftRepository;
    private final AppSettingRepository settingRepository;
    private final ApplicationEventPublisher eventPublisher;

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
        return NEW_QUESTS.equals(type) ? "cc.newq." : "cc.topw.";
    }

    public TypeSettings settings(String type) {
        String p = prefix(type);
        int defHour = NEW_QUESTS.equals(type) ? 12 : 10;
        int hour = parseInt(get(p + "hour"), defHour);
        int dow = parseInt(get(p + "dow"), 1);
        return new TypeSettings("1".equals(get(p + "enabled")), Math.max(0, Math.min(23, hour)), Math.max(1, Math.min(7, dow)), get(p + "last"));
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
        ChannelPostDraft d = new ChannelPostDraft();
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
        for (String type : List.of(NEW_QUESTS, TOP_QUESTS_WEEK)) {
            try {
                TypeSettings s = settings(type);
                if (!s.enabled() || now.getHour() != s.hour()) continue;
                if (TOP_QUESTS_WEEK.equals(type) && now.getDayOfWeek().getValue() != s.dayOfWeek()) continue;
                String today = LocalDate.now().toString();
                if (today.equals(s.lastRun())) continue;
                put(prefix(type) + "last", today);
                if (NEW_QUESTS.equals(type)) createNewQuestsDraft(false); else createTopQuestsDraft(false);
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
