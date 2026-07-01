# EXPERIENCE GAMING CLUB — Telegram Bot

Telegram-бот игровой платформы EGC с квестами, рейтингами, магазином наград, рефералами и REST API для веб-сайта.

## Реализованный функционал

### Игровая часть
- Регистрация игрока: никнейм, возраст, страна, платформы, игровые интересы
- Личный кабинет: уровень, XP, EXC-баланс, квесты, рейтинг, серия входов, достижения, профильный титул
- Система квестов: категории (Лёгкие / Средние / Сложные), каталог по играм, взятие в работу, отправка отчёта (скриншот / видео / документ / ссылка)
- Модерация: очередь заявок, одобрение, отклонение, запрос уточнений
- Автоматические награды: начисление XP и EXC после одобрения модератором
- Рейтинги: общий и недельный топ-10
- Реферальная система: персональная `start`-ссылка, бонусы за приглашения
- Магазин наград: каталог предметов клуба, заявка администратору
- Sink-механика: реролл квеста (2 000 EXC), страховка провала (1 500 EXC)

### Экономика (EGC Economy v2.0)
- Валюта: **EXC** (Experience Coin)
- Курс вывода: **100 EXC = 1 ₽** (фиксированный)
- Health Ratio: отношение Payout Pool к суммарному балансу игроков, минимум 10%
- Лимиты вывода по уровням: от 5 000 до 500 000 EXC/мес
- Награды квестов ×15 от базовых (Лёгкие: 150 XP / 750 EXC, Средние: 500 XP / 2 500 EXC, Сложные: 1 500 XP / 7 500 EXC)

### Уровни
| Уровень | Название | XP |
|---------|----------|----|
| 1 | Новичок | 0 |
| 2 | Игрок | 1 000 |
| 3 | Ветеран | 5 000 |
| 4 | Элита | 15 000 |
| 5 | Мастер | 35 000 |
| 6 | Легенда | 75 000 |
| 7 | Чемпион | 150 000 |
| 8 | Бессмертный | 300 000 |

### Магазин наград
| Предмет | Цена (EXC) |
|---------|-----------|
| Gift Card Steam 100₽ | 12 000 |
| Gift Card Steam 250₽ | 28 000 |
| Gift Card Steam 500₽ | 55 000 |
| Gift Card PSN 500₽ | 55 000 |
| Значок EGC | 20 000 |
| Футболка EGC | 75 000 |
| EGC Council (статус) | 100 000 |

### Sink-магазин (предметы клуба)
| Предмет | Цена (EXC) |
|---------|-----------|
| Реролл квеста | 2 000 |
| Страховка провала | 1 500 |

### REST API (для веб-сайта)
| Метод | Endpoint | Доступ |
|-------|----------|--------|
| POST | `/api/auth/telegram` | Публичный |
| GET | `/api/leaderboard?type=overall\|weekly` | Публичный |
| GET | `/api/quests` | Публичный |
| GET | `/api/quests/games` | Публичный |
| GET | `/api/shop/items` | Публичный |
| GET | `/api/shop/stats` | Публичный |
| GET | `/api/stats` | Публичный |
| GET | `/api/profile` | JWT |
| GET | `/api/profile/submissions` | JWT |
| GET | `/api/profile/rewards` | JWT |

## Технологии

- Java 21
- Spring Boot 3.3.1
- Spring Data JPA + Spring Web + Spring Security
- TelegramBots long polling
- H2 file database
- JJWT 0.12.6
- Docker multi-stage build

## Запуск

```bash
docker build -t game-platform-bot .
docker rm -f game-platform-bot 2>/dev/null || true

docker run -d \
  --name game-platform-bot \
  --restart unless-stopped \
  --network host \
  -e TELEGRAM_BOT_TOKEN='YOUR_BOT_TOKEN' \
  -e TELEGRAM_BOT_USERNAME='EXPERIENCEgamingbot' \
  -e INITIAL_ADMIN_ID='YOUR_TELEGRAM_ID' \
  -e APP_ADMIN_IDS='YOUR_TELEGRAM_ID' \
  -e APP_MODERATOR_IDS='' \
  -e APP_SUPPORT_USERNAME='support_manager' \
  -e APP_CLUB_NAME='EXPERIENCE GAMING CLUB' \
  -e REQUIRED_CHANNEL_USERNAME='@exgamingclub' \
  -e REQUIRED_CHANNEL_TITLE='EXPERIENCE GAMING CLUB' \
  -e DB_PATH='/data/game-platform-bot' \
  -e JWT_SECRET='your-secret-key-min-32-chars' \
  -e CORS_ALLOWED_ORIGINS='https://your-site.ru' \
  -v "$(pwd)/data:/data" \
  game-platform-bot
```

## Переменные окружения

| Переменная | Описание |
|-----------|----------|
| `TELEGRAM_BOT_TOKEN` | Токен Telegram-бота |
| `TELEGRAM_BOT_USERNAME` | Username бота без `@` |
| `INITIAL_ADMIN_ID` | Telegram ID первого администратора |
| `APP_ADMIN_IDS` | Telegram ID администраторов через запятую |
| `APP_MODERATOR_IDS` | Telegram ID модераторов через запятую |
| `APP_SUPPORT_USERNAME` | Username аккаунта поддержки |
| `APP_CLUB_NAME` | Название клуба |
| `REQUIRED_CHANNEL_USERNAME` | Username канала с `@` |
| `REQUIRED_CHANNEL_TITLE` | Название канала |
| `DB_PATH` | Путь к H2-файлу базы данных |
| `JWT_SECRET` | Секрет для JWT (мин. 32 символа) |
| `CORS_ALLOWED_ORIGINS` | Разрешённые origins для API |
| `SPRING_DATASOURCE_URL` | Полный URL БД (переопределяет DB_PATH) |

## Связанные проекты

- **egc-web** — Next.js 14 веб-сайт EGC (фронтенд + REST клиент)
- **egc-landing** — Статический лендинг EGC
