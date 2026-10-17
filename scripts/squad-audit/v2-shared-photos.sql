-- В2: пары аккаунтов в одном отряде, чьи заявки содержат один и тот же файл (Telegram file_unique_id - это идентификатор файла, не перцептивный хеш: совпадает у точно того же файла, пересланного/загруженного повторно; изменённый скриншот не поймает).
-- Сравнивается первое фото одной заявки со всеми фото другой (и наоборот через пару A/B): заявки с несколькими фото могут дать неполный результат.
WITH s AS (SELECT qs.id, qs.user_id, u.squad_id, qs.created_at, qs.photo_unique_ids AS ids,
                  CASE WHEN POSITION('|' IN qs.photo_unique_ids) > 0 THEN SUBSTRING(qs.photo_unique_ids, 1, POSITION('|' IN qs.photo_unique_ids) - 1) ELSE qs.photo_unique_ids END AS first_id
           FROM quest_submissions qs JOIN app_users u ON u.id = qs.user_id
           WHERE u.squad_id IS NOT NULL AND u.registration_completed = TRUE AND qs.photo_unique_ids IS NOT NULL AND qs.photo_unique_ids <> '')
SELECT a.squad_id, a.user_id AS user_a, b.user_id AS user_b, COUNT(*) AS matching_submission_pairs, MIN(a.created_at) AS first_seen, MAX(b.created_at) AS last_seen
FROM s a JOIN s b ON a.squad_id = b.squad_id AND a.user_id < b.user_id AND a.first_id <> ''
 AND ('|' || b.ids || '|') LIKE ('%|' || a.first_id || '|%')
GROUP BY a.squad_id, a.user_id, b.user_id
ORDER BY matching_submission_pairs DESC
