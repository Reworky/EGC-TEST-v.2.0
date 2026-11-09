-- Диагностика отзывов для сайта (только чтение): сколько реальных отзывов есть в базе и какие из них видит публичный эндпоинт /api/public/reviews.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-bot-reviews.sql
-- Эндпоинт берёт PUBLISHED, 4-5 звёзд, текст не короче 10 символов, максимум 6 штук (нужные нам цифры - в первом запросе).

-- 1) Сколько отзывов по статусам и звёздам, за какой период, сколько из них с текстом >= 10 символов
SELECT status, stars, COUNT(*) AS n,
       SUM(CASE WHEN text IS NOT NULL AND LENGTH(TRIM(text)) >= 10 THEN 1 ELSE 0 END) AS with_text,
       MIN(created_at) AS first_at, MAX(created_at) AS last_at
FROM bot_reviews
GROUP BY status, stars
ORDER BY status, stars DESC;

-- 2) Все опубликованные отзывы с текстом (от новых к старым): то, из чего можно собрать раздел на сайте
SELECT r.id, r.created_at, r.stars, r.status, LENGTH(r.text) AS len, r.text
FROM bot_reviews r
WHERE r.status = 'PUBLISHED' AND r.text IS NOT NULL AND LENGTH(TRIM(r.text)) > 0
ORDER BY r.created_at DESC
LIMIT 80;
