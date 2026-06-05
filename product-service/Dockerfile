# ── Stage 1: Build ────────────────────────────────────────────
FROM gradle:8-jdk21 AS builder
WORKDIR /app

# 루트 프로젝트 파일 (멀티모듈 의존성 해소에 필요)
COPY build.gradle settings.gradle ./
COPY gradle gradle/

# 서브모듈 build.gradle (의존성 캐시 레이어 분리)
COPY payment-service/build.gradle          payment-service/
COPY order-service/build.gradle            order-service/
COPY merchant-limit-service/build.gradle   merchant-limit-service/
COPY risk-management-service/build.gradle  risk-management-service/
COPY product-service/build.gradle          product-service/

# 의존성 다운로드 (소스 변경 시 재사용)
RUN gradle :product-service:dependencies --no-daemon -q

# 소스 복사 후 빌드
COPY product-service/src product-service/src
RUN gradle :product-service:bootJar --no-daemon -x test

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/product-service/build/libs/*.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]
