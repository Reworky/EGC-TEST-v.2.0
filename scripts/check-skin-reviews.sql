-- Последние 8 отзывов бота: сохранился ли отзыв, на проверке он (PENDING), опубликован (PUBLISHED, published_message_id заполнен) или отклонён. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-skin-reviews.sql
SELECT id, reward_request_id, stars, status, created_at, published_message_id, reward_granted, (photo_file_id IS NOT NULL) AS has_photo
FROM bot_reviews
ORDER BY id DESC LIMIT 8;
