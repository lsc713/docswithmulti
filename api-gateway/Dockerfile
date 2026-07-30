# ── Stage 1: Build ────────────────────────────────────────────
FROM gradle:8-jdk21 AS builder
WORKDIR /app

# 루트 프로젝트 파일 (멀티모듈 의존성 해소에 필요)
COPY build.gradle settings.gradle ./
COPY gradle gradle/

# 서브모듈 build.gradle (의존성 캐시 레이어 분리)
# settings.gradle이 api-gateway를 include하므로 api-gateway/build.gradle이 없으면
# :api-gateway:bootJar가 task-not-found로 실패 → 반드시 포함.
COPY payment-service/build.gradle          payment-service/
COPY order-service/build.gradle            order-service/
COPY merchant-limit-service/build.gradle   merchant-limit-service/
COPY risk-management-service/build.gradle  risk-management-service/
COPY product-service/build.gradle          product-service/
COPY user-service/build.gradle             user-service/
COPY api-gateway/build.gradle              api-gateway/

# 의존성 다운로드 (소스 변경 시 재사용)
RUN gradle :api-gateway:dependencies --no-daemon -q

# 소스 복사 후 빌드
COPY api-gateway/src api-gateway/src
RUN gradle :api-gateway:bootJar --no-daemon -x test

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/api-gateway/build/libs/*.jar app.jar

EXPOSE 8000

ENTRYPOINT ["java", "-jar", "app.jar"]
