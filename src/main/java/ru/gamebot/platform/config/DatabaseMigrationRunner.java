package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseMigrationRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        // Log ALL constraints so we can see what's actually in the DB
        try {
            List<Map<String, Object>> all = jdbcTemplate.queryForList(
                    "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE, TABLE_NAME " +
                    "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS");
            log.info("[DBMigration] All TABLE_CONSTRAINTS ({} total): {}", all.size(), all);
        } catch (Exception e) {
            log.error("[DBMigration] Cannot query TABLE_CONSTRAINTS: {}", e.getMessage());
        }

        dropCheckConstraints("QUEST_SUBMISSIONS");
        dropCheckConstraints("REWARD_REQUESTS");
        deduplicateQuests();
        deduplicateNicknames();
        addNicknameUniqueIndex();
        backfillQuestTicketRewards();
        seedEgcAvatarFrame();
        fixSponsoredQuestFlag();
        seedGtaVCatalog();
        deleteGamesAndQuests();
        deactivateSopranosQuest();
        fixNullDurationText();
        backfillOwnedFrames();
        backfillCooldownReminderBaseline();
        resetStaleAttackWinsBaseline();
        removeAccidentalParticipantLimits();
        backfillOwnedTitlesFromHistory();
        syncCountryWithPhone();
        moveSquadMidweekAutopostTo0500Utc();
    }

    /** Инцидент 2026-09-22 (тикет поддержки #213): ClashQuestVerificationService для ATTACK_WINS
     *  считал прогресс от top-level поля attackWins — оказалось, оно обнуляется по сезону/новому
     *  режиму "Рейтинговое сражение" (см. javadoc ClashOfClansApiService.PlayerInfo.multiplayerWins).
     *  Источник переключён на ачивку "Conqueror" (монотонная, никогда не сбрасывается) — но заявки,
     *  УЖЕ взятые ДО этого деплоя, хранят базу (clash_baseline_value), зафиксированную по-старому
     *  (у большинства 0, т.к. attackWins был обнулён у всех разом). Без сброса первый же опрос по
     *  новому коду прочитает multiplayerWins (тысячи побед за карьеру) как "текущее" и вычтет из
     *  старой базы (0) — получится многотысячная фиктивная дельта, квест одобрится всем мгновенно
     *  и незаслуженно. Сбрасываем базу ТОЛЬКО у заявок, взятых до деплоя этого фикса (created_at
     *  раньше даты деплоя) — новые заявки, взятые после, получают верную базу от multiplayerWins
     *  сразу и этим условием не затрагиваются, так что миграция безопасно бездействует на будущих
     *  перезапусках (created_at < cutoff никогда не станет истинным для новых записей). */
    private void resetStaleAttackWinsBaseline() {
        try {
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.of(2026, 9, 23, 0, 0);
            int updated = jdbcTemplate.update(
                "UPDATE quest_submissions SET clash_baseline_value = NULL, clash_progress_count = 0 " +
                "WHERE status = 'DRAFT' AND clash_baseline_value IS NOT NULL AND created_at < ? " +
                "AND quest_id IN (SELECT id FROM quests WHERE clash_verify_type = 'ATTACK_WINS')",
                cutoff);
            if (updated > 0) {
                log.info("[DBMigration] resetStaleAttackWinsBaseline: reset stale attackWins-based baseline for {} in-progress submissions (incident 2026-09-22 fix, switch to Conqueror achievement)", updated);
            }
        } catch (Exception e) {
            log.error("[DBMigration] resetStaleAttackWinsBaseline failed: {}", e.getMessage());
        }
    }

    /** Инцидент 2026-09-20: при включении повторного напоминания о снятом кулдауне (см.
     *  WeeklyResetScheduler.notifyCooldownReminderIfIgnored) первый прогон нашёл ВЕСЬ исторический
     *  бэклог (у всех игроков разом) и разослал по сообщению на каждый просроченный квест —
     *  выглядело как спам-залп. По просьбе пользователя фича должна применяться только "с текущего
     *  момента", не ко всем прошлым квестам — эта миграция один раз молча помечает весь СУЩЕСТВУЮЩИЙ
     *  на момент деплоя бэклог как "уже напомнили" (без реальной отправки), чтобы дальше шедулер
     *  видел только квесты, чей кулдаун истёк ПОСЛЕ этого деплоя. При повторных перезапусках сервера
     *  здесь уже нечего помечать (WHERE находит только ещё не помеченные старые записи) — безопасно
     *  оставить в постоянных миграциях, а не удалять после одного раза. */
    private void backfillCooldownReminderBaseline() {
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            java.time.LocalDateTime cutoff = now.minusHours(36); // 24ч кулдаун + 12ч задержка напоминания
            int updated = jdbcTemplate.update(
                "UPDATE quest_submissions SET cooldown_reminder_sent_at = ? " +
                "WHERE cooldown_reminder_sent_at IS NULL AND status = 'APPROVED' AND updated_at <= ?",
                now, cutoff);
            if (updated > 0) {
                log.info("[DBMigration] backfillCooldownReminderBaseline: silently marked {} already-overdue quest_submissions as reminded (incident 2026-09-20 fix)", updated);
            }
        } catch (Exception e) {
            log.error("[DBMigration] backfillCooldownReminderBaseline failed: {}", e.getMessage());
        }
    }

    private void deduplicateNicknames() {
        try {
            List<Map<String, Object>> dups = jdbcTemplate.queryForList(
                "SELECT LOWER(nickname) as lnick, COUNT(*) as cnt FROM app_users " +
                "WHERE nickname IS NOT NULL GROUP BY LOWER(nickname) HAVING COUNT(*) > 1");
            for (Map<String, Object> row : dups) {
                String lnick = (String) row.get("LNICK");
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, nickname FROM app_users WHERE LOWER(nickname) = ? ORDER BY id ASC", lnick);
                for (int i = 1; i < rows.size(); i++) {
                    Long id = ((Number) rows.get(i).get("ID")).longValue();
                    String oldNick = (String) rows.get(i).get("NICKNAME");
                    // Ошибка на одной строке не должна обрывать остальные дубли (прод 2026-09-24: «Максим_2» уже
                    // существовал, UPDATE упал по уникальному индексу и миграция бросила ВСЕ оставшиеся дубли)
                    try {
                        String newNick = freeRenamedNickname(oldNick);
                        jdbcTemplate.update("UPDATE app_users SET nickname = ? WHERE id = ?", newNick, id);
                        log.warn("[DBMigration] Renamed duplicate nickname '{}' -> '{}' for user id={}", oldNick, newNick, id);
                    } catch (Exception e) {
                        log.error("[DBMigration] Could not rename duplicate nickname '{}' for user id={}: {}", oldNick, id, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[DBMigration] deduplicateNicknames failed: {}", e.getMessage());
        }
    }

    /** «Ник_2», «Ник_3», ... — первое имя, которого ещё нет в базе (без учёта регистра). */
    private String freeRenamedNickname(String oldNick) {
        for (int n = 2; n < 1000; n++) {
            String candidate = oldNick + "_" + n;
            Integer taken = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM app_users WHERE LOWER(nickname) = LOWER(?)", Integer.class, candidate);
            if (taken == null || taken == 0) {
                return candidate;
            }
        }
        return oldNick + "_" + System.nanoTime();
    }

    private void addNicknameUniqueIndex() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES " +
                "WHERE UPPER(TABLE_NAME) = 'APP_USERS' AND INDEX_NAME = 'IDX_APP_USERS_NICKNAME'",
                Integer.class);
            if (count == null || count == 0) {
                jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IDX_APP_USERS_NICKNAME ON app_users(nickname)");
                log.info("[DBMigration] Created unique index on app_users.nickname");
            }
        } catch (Exception e) {
            log.error("[DBMigration] addNicknameUniqueIndex failed: {}", e.getMessage());
        }
    }

    private void deduplicateQuests() {
        try {
            List<Map<String, Object>> dups = jdbcTemplate.queryForList(
                "SELECT title, game_name, COUNT(*) as cnt FROM quests GROUP BY title, game_name HAVING COUNT(*) > 1");
            for (Map<String, Object> row : dups) {
                String title = (String) row.get("TITLE");
                String gameName = (String) row.get("GAME_NAME");
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM quests WHERE title = ? AND game_name = ? ORDER BY id ASC", title, gameName);
                // Keep the first (oldest), delete the rest
                for (int i = 1; i < rows.size(); i++) {
                    Long id = ((Number) rows.get(i).get("ID")).longValue();
                    jdbcTemplate.update("DELETE FROM quest_submissions WHERE quest_id = ?", id);
                    jdbcTemplate.update("DELETE FROM quests WHERE id = ?", id);
                    log.warn("[DBMigration] Removed duplicate quest id={} title='{}' game='{}'", id, title, gameName);
                }
            }
        } catch (Exception e) {
            log.error("[DBMigration] deduplicateQuests failed: {}", e.getMessage());
        }
    }

    private void backfillQuestTicketRewards() {
        try {
            int updated = jdbcTemplate.update(
                "UPDATE quests SET ticket_reward = CASE category " +
                "WHEN 'Лёгкие' THEN 1 WHEN 'Средние' THEN 2 WHEN 'Сложные' THEN 3 ELSE 1 END " +
                "WHERE ticket_reward = 0");
            if (updated > 0) {
                log.info("[DBMigration] backfilled ticket_reward for {} quests", updated);
            }
        } catch (Exception e) {
            log.error("[DBMigration] backfillQuestTicketRewards failed: {}", e.getMessage());
        }
    }

    private void fixNullDurationText() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, duration_days FROM quests WHERE duration_text IS NULL AND duration_days > 0");
            for (Map<String, Object> row : rows) {
                int days = ((Number) row.get("DURATION_DAYS")).intValue();
                String text = days + " " + (days == 1 ? "день" : days < 5 ? "дня" : "дней");
                jdbcTemplate.update("UPDATE quests SET duration_text = ? WHERE id = ?", text, row.get("ID"));
            }
            if (!rows.isEmpty()) log.info("[DBMigration] Fixed duration_text for {} quests", rows.size());
        } catch (Exception e) {
            log.error("[DBMigration] fixNullDurationText failed: {}", e.getMessage());
        }
    }

    private void deleteGamesAndQuests() {
        try {
            for (String game : List.of("EA FC 26", "Mobile Legends: Bang Bang")) {
                // Delete submissions for quests of this game
                jdbcTemplate.update(
                    "DELETE FROM quest_submissions WHERE quest_id IN (SELECT id FROM quests WHERE game_name = ?)", game);
                // Delete quests
                int deleted = jdbcTemplate.update("DELETE FROM quests WHERE game_name = ?", game);
                // Delete from catalog
                jdbcTemplate.update("DELETE FROM game_catalog WHERE game_name = ?", game);
                if (deleted > 0) log.info("[DBMigration] Deleted {} quests and catalog entry for '{}'", deleted, game);
            }
        } catch (Exception e) {
            log.error("[DBMigration] deleteGamesAndQuests failed: {}", e.getMessage());
        }
    }

    /** Квест «NFT / THE SOPRANOS» создан вручную в админке и не нужен (решение владельца 2026-09-25).
     *  Мягкое удаление, как в QuestService.deleteQuest: active=false, заявки игроков не трогаем (ожидающие
     *  К-3167/К-3433 модератор отклоняет сам). Точное совпадение названия, повторный запуск ничего не меняет. */
    private void deactivateSopranosQuest() {
        try {
            int n = jdbcTemplate.update("UPDATE quests SET active = FALSE WHERE active = TRUE AND title = ?", "NFT / THE SOPRANOS");
            if (n > 0) log.info("[DBMigration] Deactivated {} quest(s) 'NFT / THE SOPRANOS'", n);
        } catch (Exception e) {
            log.error("[DBMigration] deactivateSopranosQuest failed: {}", e.getMessage());
        }
    }

    private void seedGtaVCatalog() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_catalog WHERE game_name = 'GTA V'", Integer.class);
            if (count != null && count > 0) return;
            jdbcTemplate.update("INSERT INTO game_catalog (game_name) VALUES ('GTA V')");
            log.info("[DBMigration] Added GTA V to game catalog");
        } catch (Exception e) {
            log.error("[DBMigration] seedGtaVCatalog failed: {}", e.getMessage());
        }
    }

    /** Инцидент 2026-09-30 (жалоба владельца после тикета #255 про закрытый квест "100/100"):
     *  participant_limit оказался проставлен на 100 по умолчанию во ВСЕХ обычных квестах, созданных
     *  через QuestSeeder.seed()/seedFlat() и через шаблоны в админ-создании квеста (GamePlatformBot) -
     *  случайный дефолт, не привязанный ни к какому реальному ограничению, из-за которого игровые
     *  квесты неожиданно "закрывались" после 100 одобренных заявок. Плюс один квест CS2 "Ранговый
     *  прорыв" был искусственно ограничен до 50 на период отладки верификации (давно неактуально).
     *  Убираем лимит у ВСЕХ квестов, КРОМЕ реально привязанных к деньгам третьей стороны:
     *  sponsored=true (раздел "Рекламодателям", лимит 10000 - бюджет спонсора) и
     *  external_auto_approve=true (CPA-сети типа actionpay/admitad, лимит 1000 - рекламодатель платит
     *  только за первые N конверсий). Владелец подтвердил 2026-09-30: лимиты нужны только там. */
    private void removeAccidentalParticipantLimits() {
        try {
            int updated = jdbcTemplate.update(
                "UPDATE quests SET participant_limit = NULL " +
                "WHERE participant_limit IS NOT NULL AND sponsored = false AND external_auto_approve = false");
            if (updated > 0) {
                log.info("[DBMigration] Removed accidental participant_limit from {} regular quests", updated);
            }
        } catch (Exception e) {
            log.error("[DBMigration] removeAccidentalParticipantLimits failed: {}", e.getMessage());
        }
    }

    /** Инцидент 2026-09-30 (жалоба игрока): SinkShopService.purchaseTitle списывал EXC при КАЖДОМ
     *  нажатии на кнопку титула, даже за титул, который у игрока уже был надет и уже был оплачен
     *  раньше - ownedTitlesCsv до сегодняшнего фикса вообще не отслеживал обычные EXC-титулы (только
     *  эксклюзивный Stars-титул "patron"). Код уже поправлен - теперь платим только за титулы, которых
     *  нет в ownedTitlesCsv. Но игроки, которые ЧЕСТНО заплатили за титул(ы) ДО этого фикса, не должны
     *  платить за них ещё раз при следующем клике - задним числом возвращаем владение по факту прошлых
     *  оплат, видных в exc_transactions (type=SINK, description вида "Титул: <название>"). */
    private void backfillOwnedTitlesFromHistory() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, description FROM exc_transactions WHERE type = 'SINK' AND description LIKE 'Титул: %'");
            java.util.Map<Long, java.util.LinkedHashSet<String>> byUser = new java.util.HashMap<>();
            for (Map<String, Object> row : rows) {
                Long userId = ((Number) row.get("USER_ID")).longValue();
                String description = (String) row.get("DESCRIPTION");
                String title = description.substring("Титул: ".length());
                byUser.computeIfAbsent(userId, k -> new java.util.LinkedHashSet<>()).add(title);
            }
            int updated = 0;
            for (Map.Entry<Long, java.util.LinkedHashSet<String>> entry : byUser.entrySet()) {
                String existingCsv = jdbcTemplate.queryForObject(
                    "SELECT owned_titles_csv FROM app_users WHERE id = ?", String.class, entry.getKey());
                java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
                if (existingCsv != null && !existingCsv.isBlank()) {
                    merged.addAll(java.util.Arrays.asList(existingCsv.split(",")));
                }
                int before = merged.size();
                merged.addAll(entry.getValue());
                if (merged.size() != before) {
                    jdbcTemplate.update("UPDATE app_users SET owned_titles_csv = ? WHERE id = ?",
                        String.join(",", merged), entry.getKey());
                    updated++;
                }
            }
            if (updated > 0) {
                log.info("[DBMigration] Backfilled owned_titles_csv for {} users from title purchase history", updated);
            }
        } catch (Exception e) {
            log.error("[DBMigration] backfillOwnedTitlesFromHistory failed: {}", e.getMessage());
        }
    }

    private void fixSponsoredQuestFlag() {
        try {
            int updated = jdbcTemplate.update(
                "UPDATE quests SET sponsored = true WHERE sponsor_id IS NOT NULL AND sponsored = false");
            if (updated > 0) {
                log.info("[DBMigration] Fixed sponsored flag for {} quests", updated);
            }
        } catch (Exception e) {
            log.error("[DBMigration] fixSponsoredQuestFlag failed: {}", e.getMessage());
        }
    }

    private void seedEgcAvatarFrame() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_items WHERE avatar_frame_image = 'egc'", Integer.class);
            if (count != null && count > 0) return;
            jdbcTemplate.update(
                "INSERT INTO reward_items (title, description, category, price_coins, active, purchase_group, avatar_frame_image, avatar_frame_color, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                "👑 Рамка EGC", "Эксклюзивная рамка аватара Experience Gaming Club", "Рамка",
                50000, true, "avatar_frame", "egc", "#7C3AED");
            log.info("[DBMigration] Inserted EGC avatar frame");
        } catch (Exception e) {
            log.error("[DBMigration] seedEgcAvatarFrame failed: {}", e.getMessage());
        }
    }

    private void backfillOwnedFrames() {
        try {
            int updated = jdbcTemplate.update(
                "UPDATE app_users SET owned_frames_csv = avatar_frame_image " +
                "WHERE avatar_frame_image IS NOT NULL AND owned_frames_csv IS NULL");
            if (updated > 0) log.info("[DBMigration] backfillOwnedFrames: {} users updated", updated);
        } catch (Exception e) {
            log.warn("[DBMigration] backfillOwnedFrames failed: {}", e.getMessage());
        }
    }

    private void dropCheckConstraints(String table) {
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE UPPER(TABLE_NAME) = ? AND CONSTRAINT_TYPE = 'CHECK'",
                    String.class, table.toUpperCase());
            log.info("[DBMigration] CHECK constraints on {}: {}", table, names);
            for (String name : names) {
                jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + name + "\"");
                log.error("[DBMigration] Dropped constraint '{}' on {}", name, table);
            }
        } catch (Exception e) {
            log.error("[DBMigration] Failed for {}: {}", table, e.getMessage());
        }
    }

    /** Страна в профиле берётся строго из подтверждённого номера телефона (05.10.2026): игрок мог сменить страну
     *  после отмены заявки, номер при этом оставался прежним. Выравниваем уже накопленные профили с номером.
     *  Идемпотентно: меняются только строки, где страна расходится с кодом номера; номера с нераспознанным кодом не трогаем. */
    private void syncCountryWithPhone() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, phone_number, country FROM app_users WHERE phone_number IS NOT NULL AND phone_number <> ''");
            int updated = 0;
            for (Map<String, Object> row : rows) {
                String fromPhone = ru.gamebot.platform.service.PhoneCountry.fromPhone((String) row.get("PHONE_NUMBER"));
                String current = (String) row.get("COUNTRY");
                if (fromPhone != null && !fromPhone.equals(current)) {
                    jdbcTemplate.update("UPDATE app_users SET country = ? WHERE id = ?", fromPhone, row.get("ID"));
                    updated++;
                }
            }
            if (updated > 0) {
                log.info("[DBMigration] syncCountryWithPhone: страна приведена к коду номера у {} игроков", updated);
            }
        } catch (Exception e) {
            log.error("[DBMigration] syncCountryWithPhone failed: {}", e.getMessage());
        }
    }

    /** «Гонка отрядов, экватор недели» (автопост ChannelContentService.SQUAD_MIDWEEK) - среда 05:00 UTC (12:00 по МСК+4 владельца), навсегда
     *  (решение 06.10.2026). Если час раньше меняли в админке и он сохранён в app_settings, один раз приводим его к 5; повторно на следующих
     *  стартах не трогаем (маркер cc.sqmid.hour.v2), чтобы не перебивать будущие правки в админке. Без сохранённого часа действует дефолт 5. */
    private void moveSquadMidweekAutopostTo0500Utc() {
        try {
            Integer done = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_settings WHERE setting_key = 'cc.sqmid.hour.v2'", Integer.class);
            if (done != null && done > 0) return;
            int updated = jdbcTemplate.update("UPDATE app_settings SET setting_value = '5' WHERE setting_key = 'cc.sqmid.hour'");
            jdbcTemplate.update("INSERT INTO app_settings (setting_key, setting_value) VALUES ('cc.sqmid.hour.v2', '1')");
            log.info("[DBMigration] SQUAD_MIDWEEK autopost hour set to 05:00 UTC (stored value updated: {})", updated > 0);
        } catch (Exception e) {
            log.error("[DBMigration] moveSquadMidweekAutopostTo0500Utc failed: {}", e.getMessage());
        }
    }
}
