-- Сводит разные написания стран игроков к одному названию (по данным stats-countries.sql 06.11.2026). Меняет базу, запускать один раз.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/normalize-countries.sql
UPDATE app_users SET country = 'Россия' WHERE LOWER(country) IN ('russia','россия','рф','russian','rossi','росия','росси','россиия','росссия','russland','рооо','российская федерация','🇷🇺','rf','россии');
UPDATE app_users SET country = 'Украина' WHERE LOWER(country) IN ('украина','україна','ukraine','украйна','украйіна','укринв','з україны','з україни','украіна');
UPDATE app_users SET country = 'Казахстан' WHERE LOWER(country) IN ('казахстан','kazakhstan','қазақстан','казакстан','kazak','kazakstan','казахистан','rk','казахи');
UPDATE app_users SET country = 'Беларусь' WHERE LOWER(country) IN ('беларусь','belarus','белорусь','белоруссия','белоруссь','белорусы');
UPDATE app_users SET country = 'Узбекистан' WHERE LOWER(country) IN ('узбекистан','uzbekistan','узбекистана');
UPDATE app_users SET country = 'Кыргызстан' WHERE LOWER(country) IN ('кыргызстан','киргизия','кыргызтан','кыргыз','🇰🇬') OR UPPER(country) = 'КЫРГЫЗТАН';
UPDATE app_users SET country = 'США' WHERE LOWER(country) IN ('сша','usa','america');
UPDATE app_users SET country = 'Таджикистан' WHERE LOWER(country) IN ('таджикистан','tajikistan','таджиуистан');
UPDATE app_users SET country = 'Туркменистан' WHERE LOWER(country) IN ('туркменистан','turkmenistan','türkmenistan');
UPDATE app_users SET country = 'Молдова' WHERE LOWER(country) IN ('молдова','moldova','молдавия','republic of moldova');
UPDATE app_users SET country = 'Германия' WHERE LOWER(country) IN ('германия','germany');
SELECT country, COUNT(*) AS n FROM app_users WHERE country IS NOT NULL AND country <> '' GROUP BY country ORDER BY n DESC LIMIT 15;
