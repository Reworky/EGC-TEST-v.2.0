# Game Platform Telegram Bot

Telegram-бот для игровой платформы с квестами, рейтингами, наградами, рефералами и inline-навигацией.

## Что уже реализовано

- Полная регистрация игрока: никнейм, возраст, страна, платформы, игровые интересы
- Личный кабинет: уровень, XP, монеты, квесты, рейтинг, серия входов, достижения
- Система квестов: категории, карточка квеста, взятие в работу, отправка отчёта скриншотом, видео, документом или ссылкой
- Модерация: очередь заявок, одобрение, отклонение, запрос уточнений
- Автоматические награды: начисление XP и монет после одобрения
- Рейтинги: общий и недельный
- Реферальная система: персональная `start`-ссылка и бонусы за приглашения
- Магазин наград: каталог и заявка администратору на выдачу
- Админ-панель: создание квестов, базовое редактирование, ручные бонусы, рассылки, статистика
- Seed-данные: стартовые квесты, награды и новости

## Технологии

- Java 21
- Spring Boot 3
- TelegramBots long polling
- Spring Data JPA
- H2 file database
- Docker multi-stage build
`

Запуск контейнера в привычном стиле без проброса порта:

docker build -t game-platform-bot .
docker rm -f game-platform-bot 2>/dev/null || true

docker run -d \
  --name game-platform-bot \
  --restart unless-stopped \
  --network host \
  --add-host api.telegram.org:149.154.167.220 \
  -e TELEGRAM_BOT_TOKEN='YOUR_BOT_TOKEN' \
  -e TELEGRAM_BOT_USERNAME='invitetogamebot' \
  -e INITIAL_ADMIN_ID='726773708,631884742' \
  -e APP_ADMIN_IDS='726773708' \
  -e APP_MODERATOR_IDS='' \
  -e APP_SUPPORT_USERNAME='support_manager' \
  -e APP_CLUB_NAME='EXPERIENCE GAMING CLUB' \
  -e DB_PATH='/data/game-platform-bot' \
  -e JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false' \
  -v "$(pwd)/data:/data" \
  game-platform-bot


## Переменные окружения

- `TELEGRAM_BOT_TOKEN` - основной токен Telegram-бота
- `TELEGRAM_BOT_USERNAME` - username бота без `@`
- `INITIAL_ADMIN_ID` - первый администратор, удобен для быстрого старта
- `APP_ADMIN_IDS` - Telegram ID администраторов через запятую
- `APP_MODERATOR_IDS` - Telegram ID модераторов через запятую
- `APP_SUPPORT_USERNAME` - username аккаунта поддержки
- `APP_CLUB_NAME` - отображаемое название платформы
- `DB_PATH` - базовый путь для H2-файла
- `SPRING_DATASOURCE_URL` - URL базы данных
