FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/game-platform-bot-1.0.0.jar app.jar
ENV BOT_TOKEN=""
ENV BOT_USERNAME=""
ENV APP_ADMIN_IDS=""
ENV APP_MODERATOR_IDS=""
ENV APP_SUPPORT_USERNAME="support_manager"
ENV APP_CLUB_NAME="Game Quest Club"
ENV SPRING_DATASOURCE_URL="jdbc:h2:file:/app/data/gamebot;AUTO_SERVER=TRUE"
EXPOSE 8080
VOLUME ["/app/data"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
