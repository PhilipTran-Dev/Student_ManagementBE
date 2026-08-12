FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /app

COPY . .

RUN mvn clean package -pl notification_service -am -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /app/notification_service/target/*.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]