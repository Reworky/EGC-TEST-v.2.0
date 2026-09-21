# EGC (Experience Gaming Club) — Telegram-бот + мини-апп

Spring Boot 3 / Java 21 бэкенд (`src/main/java/ru/gamebot/platform`) + React/Vite мини-апп (`miniapp/`). БД — H2 в файле, `ddl-auto: update`.

## Карта кода
- `bot/GamePlatformBot.java` — весь Telegram-бот (>15k строк). НЕ читать целиком: `graphify explain "<символ>"` или Read с offset/limit.
- `service/` — QuestService, UserService, SinkShopService, GemPurchaseService и др.; `api/controller/` — REST для мини-аппа.
- `config/QuestSeeder`, `RewardSeeder`, `DatabaseMigrationRunner` — CommandLineRunner'ы, выполняются на КАЖДОМ старте бота.
- `domain/model|repository|enums` — JPA. Главные узлы графа: `AppUser`, `GamePlatformBot`, `UserService`, `QuestService`.

## Правила
- Общаться по-русски. После изменений — итог отдельно «для игрока» и «для админа».
- Граф кода: `source $HOME/.local/bin/env && graphify explain "X"` / `graphify path "A" "B" --undirected`; после правок `graphify update .`. `graphify-out/` в .gitignore.
- Push всегда в оба репо: `git push origin main && git push test main`. Коммитить и пушить после каждого готового фикса без отдельного вопроса, если пользователь не сказал обратного; в коммит — только затронутые файлы (не .DS_Store, не `.claude/launch.json`, не `content-farm/`).
- Maven локально не установлен: сборку не проверить, перед коммитом перепроверять синтаксис вручную. Первая реальная сборка — на сервере.
- Деплой бэкенда — на пользователе (SSH), схема в памяти `deploy_procedure.md`. Мини-апп деплоит Claude: `cd miniapp && npm run deploy`.
- Тексты бота в parseMode=HTML: `<`, `>`, `&` вне тегов экранировать.
- Новое примитивное поле на существующей Entity — только с `@Column(columnDefinition=...default...)`; расширение enum на `@Enumerated(STRING)` — с `columnDefinition=varchar(N)` по длине СВОЕГО enum'а.
- Новый `@Scheduled` или сидер: сначала продумать поведение на уже накопленных данных (бэклог), не только в стабильном режиме.
- Любой delete внутри QuestSeeder/CommandLineRunner проверять на коллизию с актуальными названиями (equalsIgnoreCase не различает регистр).
- Платёжный код всегда в try/catch с алертом админам.

## Память проекта
Индекс — `~/.claude/projects/-Users-olegdanilov-Desktop-gamebot/memory/MEMORY.md`; хронология — `implementation_log.md` (650 КБ, только grep по теме).
