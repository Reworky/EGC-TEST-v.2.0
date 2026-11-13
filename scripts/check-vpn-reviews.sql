-- Отзывы VPN-бота: где застрял отзыв (оценка, текст, согласие на публикацию, модерация, публикация). Только чтение.
-- Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-vpn-reviews.sql
-- Канал отзывов задаётся в .env: VPN2_REVIEWS_CHANNEL (проверить: grep VPN2_REVIEWS_CHANNEL /root/gamebot/.env). Пусто - публиковать нельзя.
-- Путь отзыва: оценка 4-5 -> «Написать отзыв» -> текст от 20 символов -> «Да, анонимно» (publish_consent) -> статус PENDING + сообщение админам в VPN-боте с кнопкой «Опубликовать» -> PUBLISHED.
-- Автопубликации нет: отзыв уходит в канал только после нажатия «Опубликовать» админом.

-- 1) Последние 15 отзывов: что заполнено и какой статус (NEW - не дошёл до согласия; PENDING - ждёт кнопки админа; PUBLISHED; REJECTED)
SELECT f.id, f.telegram_id, f.kind, f.rating, CHAR_LENGTH(COALESCE(f.review_text, '')) AS text_len, f.publish_consent AS consent, f.status,
       CAST(f.created_at AS TIMESTAMP(0)) AS created, CAST(f.published_at AS TIMESTAMP(0)) AS published
FROM vpn2_feedback f ORDER BY f.id DESC LIMIT 15;

-- 2) Сколько отзывов в каждом статусе и сколько из них с текстом 20+ символов и согласием
SELECT f.status, COUNT(*) AS total,
       SUM(CASE WHEN f.rating >= 4 THEN 1 ELSE 0 END) AS rating_4_5,
       SUM(CASE WHEN CHAR_LENGTH(COALESCE(f.review_text, '')) >= 20 THEN 1 ELSE 0 END) AS text_20plus,
       SUM(CASE WHEN f.publish_consent THEN 1 ELSE 0 END) AS consent
FROM vpn2_feedback f GROUP BY f.status ORDER BY f.status;
