# syntax=docker/dockerfile:1.7

# ---------- build ----------
# The wrapper is used here too, so the image and a developer laptop build with
# the exact same Maven version.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S sdip && adduser -S -G sdip sdip

WORKDIR /app
COPY --from=build /build/sdip-api/target/sdip-api-1.0.0.jar app.jar

USER sdip
EXPOSE 8080

# Compose's depends_on waits for this to report healthy before anything else.
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=12 \
    CMD wget -q -O - http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
