# Stage 1: Build
FROM gradle:8.12-jdk21 AS build
WORKDIR /app
COPY . .
RUN ./gradlew :app:bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/modules/app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
