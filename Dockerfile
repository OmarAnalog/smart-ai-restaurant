# syntax=docker/dockerfile:1
FROM node:22-bookworm-slim AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build/backend
COPY backend/pom.xml ./
COPY backend/src/ ./src/
# Serve the built React app from Spring Boot, so /api uses the same origin.
COPY --from=frontend-build /build/frontend/dist/ ./src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=backend-build --chown=app:app /build/backend/target/restaurant-assistant-0.0.1-SNAPSHOT.jar ./app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
