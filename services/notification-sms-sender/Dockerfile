FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :services:notification-sms-sender:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/notification-sms-sender/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
