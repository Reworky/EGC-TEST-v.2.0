-- Почему у игрока «не работает второй слот квеста»: слот, занятые слоты, ограничение «1 квест в час». Только чтение.
-- Подставьте Telegram ID игрока вместо 0 в первой строке и запустите на сервере:
--   cd /root/gamebot && git pull origin main && sed 's/__TG__/123456789/' scripts/check-player-slots.sql > /tmp/q.sql && bash scripts/run-sql.sh /tmp/q.sql
-- Слотов у игрока: база 1 (2, пока одобрено меньше 5 квестов) + 1, пока действует купленный слот (quest_slot_extra_until); максимум 3; у EGC Pass или «слота навсегда» - 3.
-- Слот занимают квесты в статусах DRAFT (в работе) и PENDING (на проверке) с неистёкшим сроком; спонсорские и партнёрские квесты слотов не занимают.
-- Отдельно от слотов действует «1 квест в час» (15 минут для новичка, пока одобрено меньше 5): второй квест можно взять только после паузы с момента последнего взятия.

-- 1) Игрок: слот, Pass, последнее взятие квеста
SELECT u.id, u.telegram_id, u.nickname, u.completed_quests,
       CAST(u.quest_slot_extra_until AS TIMESTAMP(0)) AS extra_slot_until, u.permanent_extra_slot,
       CAST(u.egc_pass_active_until AS TIMESTAMP(0)) AS pass_until,
       CAST(u.last_quest_taken_at AS TIMESTAMP(0)) AS last_quest_taken_at, CAST(CURRENT_TIMESTAMP AS TIMESTAMP(0)) AS now_utc
FROM app_users u WHERE u.telegram_id = __TG__;

-- 2) Квесты в работе или на проверке (они занимают слоты)
SELECT s.id, s.status, q.game_name, q.title, q.sponsored, CAST(s.created_at AS TIMESTAMP(0)) AS taken_at, CAST(s.expires_at AS TIMESTAMP(0)) AS expires_at
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = __TG__ AND s.status IN ('DRAFT', 'PENDING') ORDER BY s.created_at DESC;

-- 3) Покупки слота и снятия кулдауна за последние дни
SELECT CAST(t.created_at AS TIMESTAMP(0)) AS at, t.description, t.amount
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE u.telegram_id = __TG__ AND t.type = 'SINK' AND t.created_at >= DATEADD('DAY', -5, CURRENT_TIMESTAMP) ORDER BY t.created_at DESC;
