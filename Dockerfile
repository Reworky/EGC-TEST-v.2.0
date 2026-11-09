FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
# psmisc ставим ДО копирования jar: слой кэшируется и не пересобирается при каждом деплое (раньше сбой DNS у archive.ubuntu.com ронял деплой, хотя Java уже собралась)
RUN apt-get update && apt-get install -y --no-install-recommends psmisc && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/game-platform-bot-1.0.0.jar app.jar
ENV TELEGRAM_BOT_TOKEN=""
ENV TELEGRAM_BOT_USERNAME=""
ENV INITIAL_ADMIN_ID=""
ENV APP_ADMIN_IDS=""
ENV APP_MODERATOR_IDS=""
ENV APP_SUPPORT_USERNAME="support_manager"
ENV APP_CLUB_NAME="Game Quest Club"
ENV DB_PATH="/data/game-platform-bot"
ENV JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false"
VOLUME ["/data"]
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
