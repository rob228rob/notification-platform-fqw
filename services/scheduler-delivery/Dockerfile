FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :services:scheduler-delivery:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/scheduler-delivery/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
