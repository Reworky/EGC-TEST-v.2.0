-- ВКЛЮЧИТЬ спонсорский баланс: награды банковских миссий и внешних партнёрских квестов начисляются на отдельный спонсорский баланс (игрок выводит его по курсу 100% без лимитов, минимум 5 000).
-- По умолчанию выключено. Включать после деплоя бота и проверки вывода со спонсорского баланса на тестовом аккаунте. Выключить снова: scripts/sponsor-balance-off.sql.
-- Накопленное на спонсорском балансе при выключении НЕ пропадает (поле хранится), но экран и вывод скрываются, поэтому выключать только временно.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/sponsor-balance-on.sql   (применяется сразу, без перезапуска)
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('sponsor.balance.enabled', 'true');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key = 'sponsor.balance.enabled';
