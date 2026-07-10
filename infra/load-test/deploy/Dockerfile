# 부하 실측용 서비스 이미지 (멀티스테이지, 4개 서비스 공용)
#   build context = repo root
#   dockerfile    = infra/load-test/deploy/Dockerfile
#   ARG MODULE    = payment-service | risk-management-service | merchant-limit-service | order-service
#   CI: docker buildx --platform linux/arm64 → Docker Hub push (loadtest-images.yml)

# build 스테이지는 러너 네이티브 아키텍처에서 실행(--platform=$BUILDPLATFORM).
# bootJar 산출물(JAR)은 아키텍처 독립이라 arm64 QEMU 에뮬레이션 없이 빠르게 빌드된다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG MODULE
RUN ./gradlew :${MODULE}:bootJar -x test --no-daemon

# 최종 런타임 스테이지만 타깃 아키텍처(arm64)로 결정 — JRE base만 arm64로 pull.
FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/build/libs/*.jar /app/app.jar
# OTel Java agent — 이미지에 굽되 -javaagent 는 미설정. 활성화는 compose env(OTEL_JAVAAGENT).
ARG OTEL_AGENT_VERSION=2.11.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
# 서비스별 포트는 compose 에서 host network 로 노출
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
