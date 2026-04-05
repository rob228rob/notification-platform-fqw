FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :services:custom-loader:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/custom-loader/build/install/custom-loader /app/custom-loader
ENTRYPOINT ["/app/custom-loader/bin/custom-loader"]
