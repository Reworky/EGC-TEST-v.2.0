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

## Локальный запуск

Рекомендуемый JDK для локальной сборки: 21 или 23.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
export PATH="$JAVA_HOME/bin:$PATH"

mvn -DskipTests package
BOT_TOKEN=123456:token \
BOT_USERNAME=your_game_bot \
APP_ADMIN_IDS=123456789 \
APP_MODERATOR_IDS=123456789 \
APP_SUPPORT_USERNAME=support_manager \
java -jar target/game-platform-bot-1.0.0.jar
```

## Docker

Сборка образа:

```bash
docker build -t game-platform-bot .
```

Запуск контейнера:

```bash
docker run -d \
  --name game-platform-bot \
  --restart unless-stopped \
  -p 8080:8080 \
  -v "$(pwd)/data:/app/data" \
  -e BOT_TOKEN=123456:token \
  -e BOT_USERNAME=your_game_bot \
  -e APP_ADMIN_IDS=123456789 \
  -e APP_MODERATOR_IDS=123456789,987654321 \
  -e APP_SUPPORT_USERNAME=support_manager \
  -e APP_CLUB_NAME="Game Quest Club" \
  game-platform-bot
```

## Переменные окружения

- `BOT_TOKEN` - токен Telegram-бота
- `BOT_USERNAME` - username бота без `@`
- `APP_ADMIN_IDS` - Telegram ID администраторов через запятую
- `APP_MODERATOR_IDS` - Telegram ID модераторов через запятую
- `APP_SUPPORT_USERNAME` - username аккаунта поддержки
- `APP_CLUB_NAME` - отображаемое название платформы
- `SPRING_DATASOURCE_URL` - URL базы данных

## Важные заметки

- Все основные экраны используют inline-кнопки.
- Для кратких кнопок применяется компактная раскладка по две в строке; длинные идут по одной.
- База данных хранится в H2-файле и переживает перезапуск контейнера при подключённом volume.
- Недельный рейтинг обнуляется по расписанию каждый понедельник.
