# ── Stage 1: Build ────────────────────────────────────────────
FROM gradle:8-jdk21 AS builder
WORKDIR /app

COPY build.gradle settings.gradle ./
COPY gradle gradle/

COPY payment-service/build.gradle          payment-service/
COPY order-service/build.gradle            order-service/
COPY merchant-limit-service/build.gradle   merchant-limit-service/
COPY risk-management-service/build.gradle  risk-management-service/
COPY product-service/build.gradle          product-service/

RUN gradle :risk-management-service:dependencies --no-daemon -q

COPY risk-management-service/src risk-management-service/src
RUN gradle :risk-management-service:bootJar --no-daemon -x test

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/risk-management-service/build/libs/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
